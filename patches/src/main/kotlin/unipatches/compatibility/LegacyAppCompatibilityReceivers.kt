package unipatches.compatibility

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.bytecode.cloneMutable
import java.util.logging.Logger

/**
 * App-wide dynamic receiver registration fix for target SDK 33+.
 *
 * ponytail: duplicates the branch-emission mechanics from LegacyAppCompatibilityOpenIab.kt
 * (private there). When a third receiver variant is needed, extract shared helpers into
 * helpers/bytecode instead of copying again.
 */
private val frameworkContextOwners = setOf(
    "Landroid/content/Context;",
    "Landroid/content/ContextWrapper;",
    "Landroid/app/Activity;",
    "Landroid/app/Application;",
    "Landroid/app/Service;",
    "Landroid/view/ContextThemeWrapper;",
)

private fun receiverCallRegisters(instruction: ReferenceInstruction): List<Int>? = when (instruction) {
    is BuilderInstruction35c -> if (instruction.registerCount == 3) {
        listOf(instruction.registerC, instruction.registerD, instruction.registerE)
    } else null
    is BuilderInstruction3rc -> if (instruction.registerCount == 3) {
        (instruction.startRegister until instruction.startRegister + 3).toList()
    } else null
    else -> null
}

private fun isVerifiedContextOwner(
    type: String,
    parents: Map<String, String>,
    seen: MutableSet<String> = mutableSetOf(),
): Boolean = when {
    type in frameworkContextOwners -> true
    type == "Ljava/lang/Object;" || !seen.add(type) -> false
    else -> parents[type]?.let { isVerifiedContextOwner(it, parents, seen) } == true
}

internal fun legacyReceiverFlagsPatch(enabledProvider: () -> Boolean) = bytecodePatch(
    name = null,
    description = "Internal app-wide dynamic receiver compatibility phase.",
    default = false,
) {
    execute {
        val logger = Logger.getLogger(this::class.java.name)
        if (!enabledProvider()) {
            logger.info("Legacy compatibility: app-wide dynamic receiver fix disabled.")
            return@execute
        }

        val parents = mutableMapOf<String, String>()
        classDefForEach { classDef -> classDef.superclass?.let { parents[classDef.type] = it } }

        // Collect candidates first (immutable view), then patch.
        data class Candidate(val classType: String, val method: com.android.tools.smali.dexlib2.iface.Method)
        val candidates = mutableListOf<Candidate>()
        classDefForEach classLoop@{ classDef ->
            // Dedicated OpenIAB option owns org.onepf classes to avoid double patching;
            // never patch the extension's own runtime classes.
            if (classDef.type.startsWith("Lorg/onepf/") || classDef.type.startsWith("Lunipatch/")) return@classLoop
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue
                val hasCall = implementation.instructions.any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        ?: return@any false
                    isVerifiedContextOwner(reference.definingClass, parents) &&
                        reference.name == "registerReceiver" &&
                        reference.returnType == "Landroid/content/Intent;" &&
                        reference.parameterTypes == listOf(
                            "Landroid/content/BroadcastReceiver;",
                            "Landroid/content/IntentFilter;",
                        )
                }
                if (hasCall) candidates += Candidate(classDef.type, method)
            }
        }
        if (candidates.isEmpty()) {
            logger.info("Legacy compatibility: no unflagged dynamic receiver registrations found; app-wide fix skipped.")
            return@execute
        }

        var patched = 0
        var skipped = 0
        for (candidate in candidates) {
            val classDef = classDefByOrNull(candidate.classType) ?: continue
            val mutableClass = mutableClassDefByOrNull(candidate.classType) ?: continue
            val method = mutableClass.methods.firstOrNull {
                it.name == candidate.method.name &&
                    it.parameterTypes == candidate.method.parameterTypes &&
                    it.returnType == candidate.method.returnType
            } ?: continue
            val implementation = method.implementation ?: continue
            val immutableInstructions = implementation.instructions.toList()
            val calls = implementation.instructions.mapIndexedNotNull { index, instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@mapIndexedNotNull null
                if (!isVerifiedContextOwner(reference.definingClass, parents) ||
                    reference.name != "registerReceiver" ||
                    reference.returnType != "Landroid/content/Intent;" ||
                    reference.parameterTypes != listOf(
                        "Landroid/content/BroadcastReceiver;",
                        "Landroid/content/IntentFilter;",
                    )
                ) return@mapIndexedNotNull null
                index to instruction
            }
            if (calls.isEmpty()) continue

            val originalRegisterCount = implementation.registerCount
            val scratchBase = originalRegisterCount
            // if-lt (22t) accepts only v0..v15; skip frames that cannot fit the
            // branch-safe scratch layout instead of emitting malformed bytecode.
            if (scratchBase + 5 > 15) {
                skipped += calls.size
                logger.info("Legacy compatibility: skipped receiver fix in ${classDef.type}->${method.name}; scratch registers cannot fit the branch-safe v0..v15 range.")
                continue
            }

            val cloned = method.cloneMutable(additionalRegisters = 6)
            val prologueSize = cloned.implementation!!.instructions.size - immutableInstructions.size
            for ((ordinal, call) in calls.asReversed().withIndex()) {
                val index = call.first + prologueSize
                val registers = receiverCallRegisters(call.second) ?: run {
                    skipped++
                    continue
                }
                val oldOwner = ((call.second.reference as? MethodReference)?.definingClass)
                    ?: "Landroid/content/Context;"
                val oldReference = "$oldOwner->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;"
                val oldLabel = ":morphe_legacy_receiver_old_${ordinal}"
                val doneLabel = ":morphe_legacy_receiver_done_${ordinal}"
                val block = """
                    move-object/from16 v$scratchBase, v${registers[0]}
                    move-object/from16 v${scratchBase + 1}, v${registers[1]}
                    move-object/from16 v${scratchBase + 2}, v${registers[2]}
                    sget v${scratchBase + 5}, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                    const/16 v${scratchBase + 4}, $RECEIVER_FLAGS_API
                    if-lt v${scratchBase + 5}, v${scratchBase + 4}, $oldLabel
                    const/16 v${scratchBase + 3}, $RECEIVER_NOT_EXPORTED
                    invoke-virtual/range {v$scratchBase .. v${scratchBase + 3}}, Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;
                    goto $doneLabel
                    $oldLabel
                    invoke-virtual/range {v$scratchBase .. v${scratchBase + 2}}, $oldReference
                    $doneLabel
                """.trimIndent()
                cloned.replaceInstruction(index, block)
                patched++
            }
            mutableClass.methods.remove(method)
            mutableClass.methods.add(cloned)
            logger.info("Legacy compatibility: wrapped ${calls.size} receiver registration call(s) in ${classDef.type}->${method.name} with RECEIVER_NOT_EXPORTED on API 33+.")
        }

        if (patched == 0 && skipped == 0) {
            logger.info("Legacy compatibility: no app-wide receiver registrations required the fix.")
        } else {
            logger.info("Legacy compatibility: app-wide receiver fix wrapped $patched call(s); skipped $skipped.")
        }
    }
}
