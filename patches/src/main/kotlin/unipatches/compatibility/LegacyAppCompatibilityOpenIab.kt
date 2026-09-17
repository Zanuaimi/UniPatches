package unipatches.compatibility

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import helpers.bytecode.cloneMutable
import java.util.logging.Logger

/** Bytecode compatibility for OpenIAB dynamic receiver registration. */
private const val OPEN_IAB_UNITY_PLUGIN = "Lorg/onepf/openiab/UnityPlugin;"
private const val REGISTER_RECEIVER = "registerReceiver"
private const val RECEIVER_FLAGS_API = 33
private const val RECEIVER_EXPORTED = 0x2
private const val RECEIVER_NOT_EXPORTED = 0x4
private val frameworkContextOwners = setOf(
    "Landroid/content/Context;",
    "Landroid/content/ContextWrapper;",
    "Landroid/app/Activity;",
)
private val externalStoreActionPrefixes = listOf(
    "com.android.vending.", "com.amazon.", "com.sec.", "com.samsung.",
    "com.yandex.", "com.nokia.", "com.slideme.", "com.appland.",
    "com.aptoide.", "com.appmall.",
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

internal const val OPEN_IAB_AUTOMATIC = "automatic"
internal const val OPEN_IAB_DISABLED = "disabled"
internal const val OPEN_IAB_FORCE = "force"

private fun intentFilterActionArgumentRegister(instruction: ReferenceInstruction): Int? = when (instruction) {
    is BuilderInstruction35c -> if (instruction.registerCount == 2) instruction.registerD else null
    is BuilderInstruction3rc -> if (instruction.registerCount == 2) instruction.startRegister + 1 else null
    else -> null
}

private fun resolvedOpenIabActions(
    instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>,
    endIndex: Int,
): Set<String>? {
    fun reachingString(register: Int, beforeIndex: Int): String? {
        for (index in beforeIndex - 1 downTo 0) {
            val previous = instructions[index]
            if (previous.opcode == Opcode.CONST_STRING || previous.opcode == Opcode.CONST_STRING_JUMBO) {
                val destination = (previous as? OneRegisterInstruction)?.registerA
                if (destination == register) {
                    return ((previous as? ReferenceInstruction)?.reference as? StringReference)?.string
                }
            }
            val destination = when (previous) {
                is TwoRegisterInstruction -> previous.registerA
                is OneRegisterInstruction -> previous.registerA
                else -> null
            }
            if (destination == register) return null
        }
        return null
    }
    val actions = mutableSetOf<String>()
    for (index in 0..endIndex) {
        val instruction = instructions[index]
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (reference.definingClass != "Landroid/content/IntentFilter;" ||
            reference.name !in setOf("<init>", "addAction") ||
            reference.returnType != "V" ||
            reference.parameterTypes != listOf("Ljava/lang/String;")
        ) continue

        val argumentRegister = intentFilterActionArgumentRegister(instruction) ?: return null
        actions += reachingString(argumentRegister, index) ?: return null
    }
    return actions.takeIf { it.isNotEmpty() }
}

private fun isSafeOpenIabAction(action: String): Boolean {
    if (action.startsWith("android.")) return true
    if (action.startsWith("org.onepf.openiab.")) return true
    return false
}

private fun isExternalStoreAction(action: String): Boolean =
    externalStoreActionPrefixes.any(action::startsWith)

private fun receiverFlagsForOpenIabActions(actions: Set<String>): Int? = when {
    actions.isEmpty() -> null
    actions.all(::isSafeOpenIabAction) -> RECEIVER_NOT_EXPORTED
    actions.all(::isExternalStoreAction) -> RECEIVER_EXPORTED
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

private fun isOpenIabReceiverValue(
    instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>,
    endIndex: Int,
    receiverRegister: Int,
): Boolean {
    var register = receiverRegister
    for (index in endIndex - 1 downTo 0) {
        val instruction = instructions[index]
        when (instruction.opcode) {
            Opcode.NEW_INSTANCE -> {
                if ((instruction as? OneRegisterInstruction)?.registerA != register) continue
                val type = (instruction as? ReferenceInstruction)?.reference as? TypeReference
                return type?.type?.startsWith("Lorg/onepf/openiab/") == true ||
                    type?.type?.startsWith("Lorg/onepf/oms/") == true
            }
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                val move = instruction as? TwoRegisterInstruction ?: continue
                if (move.registerA == register) register = move.registerB
            }
            Opcode.MOVE_RESULT_OBJECT -> {
                if ((instruction as? OneRegisterInstruction)?.registerA != register) continue
                val producer = instructions.getOrNull(index - 1)
                val reference = (producer as? ReferenceInstruction)?.reference as? MethodReference
                return reference?.definingClass?.startsWith("Lorg/onepf/openiab/") == true ||
                    reference?.definingClass?.startsWith("Lorg/onepf/oms/") == true
            }
            Opcode.IGET_OBJECT, Opcode.SGET_OBJECT -> {
                val destination = (instruction as? TwoRegisterInstruction)?.registerA
                    ?: (instruction as? OneRegisterInstruction)?.registerA
                if (destination != register) continue
                val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                return field?.definingClass?.startsWith("Lorg/onepf/openiab/") == true ||
                    field?.definingClass?.startsWith("Lorg/onepf/oms/") == true
            }
            else -> Unit
        }
    }
    return false
}

internal fun openIabReceiverFlagsPatch(enabledProvider: () -> String) = bytecodePatch(
    name = null,
    description = "Internal OpenIAB dynamic receiver compatibility phase.",
    default = false,
) {
    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val mode = enabledProvider().trim().lowercase()
        if (mode == OPEN_IAB_DISABLED) return@execute
        if (mode !in setOf(OPEN_IAB_AUTOMATIC, OPEN_IAB_FORCE)) {
            logger.warning("Legacy compatibility: unknown OpenIAB receiver mode '$mode'; fix skipped.")
            return@execute
        }
        val openIab = mutableClassDefByOrNull(OPEN_IAB_UNITY_PLUGIN)
        if (openIab == null) {
            logger.info("Legacy compatibility: OpenIAB UnityPlugin was not found; dynamic receiver fix skipped.")
            return@execute
        }

        var patched = 0
        var skipped = 0
        var methodCount = 0
        val parents = mutableMapOf<String, String>()
        classDefForEach { classDef -> classDef.superclass?.let { parents[classDef.type] = it } }
        for (method in openIab.methods.filter { it.name == "createBroadcasts" }) {
            val implementation = method.implementation ?: continue
            methodCount++
            val calls = implementation.instructions.mapIndexedNotNull { index, instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapIndexedNotNull null
                if (!isVerifiedContextOwner(reference.definingClass, parents) ||
                    reference.name != REGISTER_RECEIVER ||
                    reference.returnType != "Landroid/content/Intent;" ||
                    reference.parameterTypes != listOf(
                        "Landroid/content/BroadcastReceiver;",
                        "Landroid/content/IntentFilter;",
                    )
                ) return@mapIndexedNotNull null
                index to (instruction as? ReferenceInstruction)
            }
            if (calls.isEmpty()) {
                logger.info("Legacy compatibility: OpenIAB ${openIab.type}->${method.name} has no exact legacy receiver calls.")
                continue
            }
            logger.info("Legacy compatibility: inspecting ${openIab.type}->${method.name}; receiver owner(s)=${calls.map { (it.second!!.reference as? MethodReference)?.definingClass }.distinct()}")

            if (mode == OPEN_IAB_AUTOMATIC) {
                val unverifiedCall = calls.firstOrNull { call ->
                    val receiverRegisters = receiverCallRegisters(call.second!!)
                    val owned = receiverRegisters != null && isOpenIabReceiverValue(
                        implementation.instructions,
                        call.first,
                        receiverRegisters[1],
                    )
                    if (!owned) logger.info("Legacy compatibility: skipped OpenIAB receiver call at instruction ${call.first}; receiver ownership was not proven.")
                    receiverRegisters == null || !owned
                }
                if (unverifiedCall != null) {
                    skipped += calls.size
                    logger.info("Legacy compatibility: automatic OpenIAB receiver fix skipped ${method.name}; receiver ownership was not proven for every call.")
                    continue
                }
                val actions = resolvedOpenIabActions(implementation.instructions, calls.last().first)
                if (actions == null) {
                    skipped += calls.size
                    logger.info("Legacy compatibility: automatic OpenIAB receiver fix skipped ${method.name}; IntentFilter actions were not uniquely resolved.")
                    continue
                }
                if (receiverFlagsForOpenIabActions(actions) == null) {
                    skipped += calls.size
                    logger.info("Legacy compatibility: automatic OpenIAB receiver fix skipped ${method.name}; actions were not a verified internal or external-store set: $actions")
                    continue
                }
            }

            val actions = resolvedOpenIabActions(implementation.instructions, calls.last().first)
            val receiverFlags = receiverFlagsForOpenIabActions(actions ?: emptySet()) ?: RECEIVER_NOT_EXPORTED
            logger.info("Legacy compatibility: ${openIab.type}->${method.name}; actions=${actions ?: "unknown"}; selected receiver flag=${if (receiverFlags == RECEIVER_EXPORTED) "RECEIVER_EXPORTED" else "RECEIVER_NOT_EXPORTED"}; calls=${calls.size}")

            val originalRegisterCount = implementation.registerCount
            val scratchBase = originalRegisterCount
            // if-lt is the only API-level branch used here and its 22t format
            // accepts only v0..v15. Skip larger frames rather than emitting
            // malformed bytecode; the original call remains untouched.
            if (scratchBase + 5 > 15) {
                skipped += calls.size
                logger.warning("Legacy compatibility: skipped OpenIAB receiver fix in ${method.name}; scratch registers cannot fit the branch-safe v0..v15 range.")
                continue
            }

            val cloned = method.cloneMutable(additionalRegisters = 6)
            val prologueSize = cloned.implementation!!.instructions.size - implementation.instructions.size
            for ((ordinal, call) in calls.asReversed().withIndex()) {
                val index = call.first + prologueSize
                val registers = receiverCallRegisters(call.second!!) ?: run {
                    skipped++
                    logger.info("Legacy compatibility: skipped OpenIAB receiver call at instruction ${call.first}; unsupported register form.")
                    continue
                }
                val oldOwner = ((call.second!!.reference as? MethodReference)?.definingClass)
                    ?: "Landroid/content/Context;"
                val oldReference = "$oldOwner->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;"
                val oldLabel = ":morphe_openiab_receiver_old_${ordinal}"
                val doneLabel = ":morphe_openiab_receiver_done_${ordinal}"
                val block = """
                    move-object/from16 v$scratchBase, v${registers[0]}
                    move-object/from16 v${scratchBase + 1}, v${registers[1]}
                    move-object/from16 v${scratchBase + 2}, v${registers[2]}
                    sget v${scratchBase + 5}, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                    const/16 v${scratchBase + 4}, $RECEIVER_FLAGS_API
                    if-lt v${scratchBase + 5}, v${scratchBase + 4}, $oldLabel
                    const/16 v${scratchBase + 3}, $receiverFlags
                    invoke-virtual/range {v$scratchBase .. v${scratchBase + 3}}, Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;
                    goto $doneLabel
                    $oldLabel
                    invoke-virtual/range {v$scratchBase .. v${scratchBase + 2}}, $oldReference
                    $doneLabel
                """.trimIndent()
                cloned.replaceInstruction(index, block)
                patched++
                logger.info("Legacy compatibility: patched OpenIAB receiver call at instruction ${call.first} with ${if (receiverFlags == RECEIVER_EXPORTED) "RECEIVER_EXPORTED" else "RECEIVER_NOT_EXPORTED"} for owner $oldOwner and actions=${actions ?: "unknown"}.")
            }
            val mutableClass = mutableClassDefByOrNull(OPEN_IAB_UNITY_PLUGIN) ?: continue
            mutableClass.methods.remove(method)
            mutableClass.methods.add(cloned)
        }

        if (patched == 0) {
            logger.warning("Legacy compatibility: no exact OpenIAB Context.registerReceiver calls were patched in mode=$mode; methods=$methodCount; skipped=$skipped.")
        } else {
            logger.info("Legacy compatibility: patched $patched OpenIAB dynamic receiver registration call(s) in mode=$mode; skipped=$skipped.")
        }
    }
}

