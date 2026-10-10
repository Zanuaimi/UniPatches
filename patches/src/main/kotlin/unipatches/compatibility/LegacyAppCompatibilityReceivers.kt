package unipatches.compatibility

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import helpers.bytecode.cloneMutable
import helpers.startup.StartupHooks
import java.util.logging.Logger

/** App-wide dynamic receiver registration fix for target SDK 33+. */
internal fun receiverSkipDiagnostic(classType: String, methodName: String, reason: String): String =
    "Legacy compatibility: skipped receiver fix in $classType->$methodName; $reason"

private val frameworkContextOwners = setOf(
    "Landroid/content/Context;",
    "Landroid/content/ContextWrapper;",
    "Landroid/app/Activity;",
    "Landroid/app/Application;",
    "Landroid/app/Service;",
    "Landroid/view/ContextThemeWrapper;",
)

private const val INTENT_FILTER = "Landroid/content/IntentFilter;"

private fun invokeRegisters(instruction: ReferenceInstruction, expectedCount: Int): List<Int>? = when (instruction) {
    is BuilderInstruction35c -> if (instruction.registerCount == expectedCount) {
        (0 until expectedCount).map { offset ->
            when (offset) {
                0 -> instruction.registerC
                1 -> instruction.registerD
                2 -> instruction.registerE
                3 -> instruction.registerF
                else -> instruction.registerG
            }
        }
    } else null
    is BuilderInstruction3rc -> if (instruction.registerCount == expectedCount) {
        (instruction.startRegister until instruction.startRegister + expectedCount).toList()
    } else null
    else -> null
}

internal fun appWideReceiverCallRegisters(instruction: ReferenceInstruction): List<Int>? = invokeRegisters(instruction, 3)

private fun writesRegister(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction, register: Int): Boolean = when (instruction) {
    is ThreeRegisterInstruction -> instruction.registerA == register
    is TwoRegisterInstruction -> instruction.registerA == register
    is OneRegisterInstruction -> instruction.registerA == register
    else -> false
}

private fun reachingString(
    instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>,
    register: Int,
    beforeIndex: Int,
): String? {
    for (index in beforeIndex - 1 downTo 0) {
        val instruction = instructions[index]
        if (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) {
            if ((instruction as? OneRegisterInstruction)?.registerA == register) {
                return ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
            }
        }
        if (writesRegister(instruction, register)) return null
    }
    return null
}

internal fun resolvedReceiverFilterActions(
    instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>,
    registrationIndex: Int,
    filterRegister: Int,
): Set<String>? {
    if (registrationIndex !in instructions.indices) return null
    var trackedRegister = filterRegister
    val actions = linkedSetOf<String>()
    for (index in registrationIndex - 1 downTo 0) {
        val instruction = instructions[index]
        val referenceInstruction = instruction as? ReferenceInstruction
        val methodReference = referenceInstruction?.reference as? MethodReference
        if (methodReference?.definingClass == INTENT_FILTER) {
            val registers = invokeRegisters(referenceInstruction, methodReference.parameterTypes.size + 1)
                ?: return null
            if (registers.firstOrNull() == trackedRegister) {
                when {
                    methodReference.name == "addAction" && methodReference.returnType == "V" &&
                        methodReference.parameterTypes == listOf("Ljava/lang/String;") -> {
                        val action = reachingString(instructions, registers[1], index) ?: return null
                        actions += action
                    }
                    methodReference.name == "<init>" && methodReference.parameterTypes == listOf("Ljava/lang/String;") -> {
                        val action = reachingString(instructions, registers[1], index) ?: return null
                        actions += action
                    }
                    methodReference.name == "<init>" && methodReference.parameterTypes.isEmpty() -> Unit
                    methodReference.name in setOf("addCategory", "addDataScheme", "addDataType", "addDataAuthority", "addDataPath", "setPriority") -> Unit
                    else -> return null
                }
                continue
            }
        }
        if (instruction.opcode == Opcode.MOVE_OBJECT ||
            instruction.opcode == Opcode.MOVE_OBJECT_FROM16 ||
            instruction.opcode == Opcode.MOVE_OBJECT_16
        ) {
            val move = instruction as? TwoRegisterInstruction ?: return null
            if (move.registerA == trackedRegister) {
                trackedRegister = move.registerB
                continue
            }
        }
        if (instruction.opcode == Opcode.NEW_INSTANCE &&
            (instruction as? OneRegisterInstruction)?.registerA == trackedRegister
        ) {
            val type = (instruction as? ReferenceInstruction)?.reference as? TypeReference
            return actions.takeIf { type?.type == INTENT_FILTER && actions.isNotEmpty() }
        }
        if (writesRegister(instruction, trackedRegister)) return null
    }
    return null
}

internal fun receiverFlagsForActions(actions: Set<String>?, applicationId: String?): Int? {
    if (actions.isNullOrEmpty() || applicationId.isNullOrBlank()) return null
    val flags = actions.map { action ->
        when {
            action == applicationId || action.startsWith("$applicationId.") || action.startsWith("org.onepf.openiab.") -> RECEIVER_NOT_EXPORTED
            action.startsWith("android.") ||
                action == "com.android.vending.INSTALL_REFERRER" ||
                action.startsWith("com.android.vending.billing.") ||
                action.startsWith("com.google.android.c2dm.intent.") ||
                isExternalStoreAction(action) -> RECEIVER_EXPORTED
            else -> return null
        }
    }
    return if (flags.contains(RECEIVER_EXPORTED)) RECEIVER_EXPORTED else RECEIVER_NOT_EXPORTED
}

internal fun isVerifiedAppContextOwner(
    type: String,
    parents: Map<String, String>,
    seen: MutableSet<String> = mutableSetOf(),
): Boolean = when {
    type in frameworkContextOwners -> true
    type == "Ljava/lang/Object;" || !seen.add(type) -> false
    else -> parents[type]?.let { isVerifiedAppContextOwner(it, parents, seen) } == true
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
        data class ClassifiedCall(
            val index: Int,
            val instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction,
            val registers: List<Int>,
            val actions: Set<String>,
            val flags: Int,
        )
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
                    isVerifiedAppContextOwner(reference.definingClass, parents) &&
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
            val candidateCalls = implementation.instructions.mapIndexedNotNull { index, instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@mapIndexedNotNull null
                if (!isVerifiedAppContextOwner(reference.definingClass, parents) ||
                    reference.name != "registerReceiver" ||
                    reference.returnType != "Landroid/content/Intent;" ||
                    reference.parameterTypes != listOf(
                        "Landroid/content/BroadcastReceiver;",
                        "Landroid/content/IntentFilter;",
                    )
                ) return@mapIndexedNotNull null
                index to instruction
            }
            if (candidateCalls.isEmpty()) continue

            val classifiedCalls = candidateCalls.mapNotNull { (index, instruction) ->
                val callInstruction = instruction as? ReferenceInstruction
                val registers = callInstruction?.let(::appWideReceiverCallRegisters)
                if (registers == null) {
                    skipped++
                    logger.info(receiverSkipDiagnostic(classDef.type, method.name, "invoke register layout is not supported (expected exactly 3 registers in 35c/3rc form)."))
                    return@mapNotNull null
                }
                val actions = resolvedReceiverFilterActions(immutableInstructions, index, registers[2])
                val flags = receiverFlagsForActions(actions, StartupHooks.resolvedPackageName)
                if (flags == null) {
                    skipped++
                    val reason = when {
                        actions.isNullOrEmpty() -> "IntentFilter actions for this call could not be resolved."
                        StartupHooks.resolvedPackageName.isNullOrBlank() -> "application package is unavailable for action classification."
                        else -> "IntentFilter contains unclassified actions: $actions"
                    }
                    logger.info(receiverSkipDiagnostic(classDef.type, method.name, reason))
                    return@mapNotNull null
                }
                ClassifiedCall(index, instruction, registers, requireNotNull(actions), flags)
            }
            if (classifiedCalls.isEmpty()) continue
            val calls = classifiedCalls.map { it.index to it.instruction }

            val originalRegisterCount = implementation.registerCount
            val scratchBase = originalRegisterCount
            if (scratchBase + 5 > 15) {
                skipped += classifiedCalls.size
                logger.info(receiverSkipDiagnostic(classDef.type, method.name, "scratch registers cannot fit the branch-safe v0..v15 range."))
                continue
            }

            val cloned = method.cloneMutable(additionalRegisters = 6)
            val prologueSize = cloned.implementation!!.instructions.size - immutableInstructions.size
            var privateCallCount = 0
            var exportedCallCount = 0
            for ((ordinal, call) in classifiedCalls.asReversed().withIndex()) {
                val index = call.index + prologueSize
                val registers = call.registers
                val oldOwner = ((call.instruction as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass
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
                    const/16 v${scratchBase + 3}, ${call.flags}
                    invoke-virtual/range {v$scratchBase .. v${scratchBase + 3}}, Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;
                    goto $doneLabel
                    $oldLabel
                    invoke-virtual/range {v$scratchBase .. v${scratchBase + 2}}, $oldReference
                    $doneLabel
                """.trimIndent()
                cloned.replaceInstruction(index, block)
                patched++
                if (call.flags == RECEIVER_EXPORTED) exportedCallCount++ else privateCallCount++
                val selectedFlag = if (call.flags == RECEIVER_EXPORTED) "RECEIVER_EXPORTED" else "RECEIVER_NOT_EXPORTED"
                logger.info("Legacy compatibility: ${classDef.type}->${method.name}; actions=${call.actions}; selected receiver flag=$selectedFlag.")
            }
            mutableClass.methods.remove(method)
            mutableClass.methods.add(cloned)
            logger.info("Legacy compatibility: wrapped ${classifiedCalls.size} of ${candidateCalls.size} receiver call(s) in ${classDef.type}->${method.name}; exported=$exportedCallCount private=$privateCallCount.")
        }

        if (patched == 0 && skipped == 0) {
            logger.info("Legacy compatibility: no app-wide receiver registrations required the fix.")
        } else {
            logger.info("Legacy compatibility: app-wide receiver fix wrapped $patched call(s); skipped $skipped.")
        }
    }
}
