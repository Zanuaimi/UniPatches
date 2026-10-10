package unipatches.compatibility

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21ih
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.bytecode.cloneMutable
import java.util.logging.Logger

internal const val FLAG_UPDATE_CURRENT = 0x08000000
internal const val FLAG_MUTABLE = 0x02000000
internal const val FLAG_IMMUTABLE = 0x04000000
private const val DOWNLOADER_ACTIVITY = "Lcom/google/android/vending/expansion/downloader_impl/DownloaderActivity;"
private const val UNPACKING_LISTENER = "Lcom/google/android/vending/expansion/downloader_impl/DownloaderActivity\$UnpackingNotificationListener;"
private const val PENDING_INTENT = "Landroid/app/PendingIntent;"

internal fun immutablePendingIntentFlags(flags: Int): Int = flags or FLAG_IMMUTABLE

internal fun isFixedDownloaderPendingIntentFlags(flags: Int): Boolean =
    (flags and FLAG_UPDATE_CURRENT) != 0 &&
        (flags and FLAG_MUTABLE) == 0 &&
        (flags and FLAG_IMMUTABLE) == 0

internal fun replacementDownloaderPendingIntentFlags(opcode: Opcode?, flagsRegisterMatches: Boolean, flags: Int?): Int? {
    if (opcode != Opcode.CONST_HIGH16 || !flagsRegisterMatches || flags == null) return null
    if (!isFixedDownloaderPendingIntentFlags(flags)) return null
    return immutablePendingIntentFlags(flags)
}

internal fun replacementDownloaderPendingIntentInstruction(instruction: Instruction?, flagsRegister: Int): BuilderInstruction21ih? {
    val constant = instruction as? OneRegisterInstruction ?: return null
    val flags = (instruction as? NarrowLiteralInstruction)?.narrowLiteral
    val mergedFlags = replacementDownloaderPendingIntentFlags(
        instruction.opcode,
        constant.registerA == flagsRegister,
        flags,
    ) ?: return null
    return BuilderInstruction21ih(Opcode.CONST_HIGH16, flagsRegister, mergedFlags)
}

private data class DownloaderPendingIntentTarget(
    val classType: String,
    val methodName: String,
    val parameterTypes: List<String>,
)

private val downloaderPendingIntentTargets = listOf(
    DownloaderPendingIntentTarget(DOWNLOADER_ACTIVITY, "finishOnCreate", emptyList()),
    DownloaderPendingIntentTarget(
        UNPACKING_LISTENER,
        "<init>",
        listOf(DOWNLOADER_ACTIVITY, "I", "I"),
    ),
)

private fun pendingIntentCallRegisters(instruction: ReferenceInstruction): List<Int>? = when (instruction) {
    is BuilderInstruction35c -> if (instruction.registerCount == 4) {
        listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF)
    } else null
    is BuilderInstruction3rc -> if (instruction.registerCount == 4) {
        (instruction.startRegister until instruction.startRegister + 4).toList()
    } else null
    else -> null
}

private fun isFixedGetActivity(instruction: ReferenceInstruction): Boolean {
    val reference = instruction.reference as? MethodReference ?: return false
    return reference.definingClass == PENDING_INTENT &&
        reference.name == "getActivity" &&
        reference.returnType == PENDING_INTENT &&
        reference.parameterTypes == listOf("Landroid/content/Context;", "I", "Landroid/content/Intent;", "I")
}

internal fun legacyDownloaderPendingIntentPatch(enabledProvider: () -> Boolean) = bytecodePatch(
    name = null,
    description = "Internal fixed PendingIntent mutability compatibility phase.",
    default = false,
) {
    execute {
        val logger = Logger.getLogger(this::class.java.name)
        if (!enabledProvider()) return@execute

        val observedTargets = mutableSetOf<DownloaderPendingIntentTarget>()
        var patched = 0
        var skipped = 0
        classDefForEach classLoop@{ classDef ->
            val target = downloaderPendingIntentTargets.firstOrNull { it.classType == classDef.type }
                ?: return@classLoop
            val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@classLoop
            val method = mutableClass.methods.firstOrNull {
                it.name == target.methodName && it.parameterTypes == target.parameterTypes && it.returnType == "V"
            }
            if (method == null) {
                logger.warning("Legacy compatibility: expected downloader method ${target.classType}->${target.methodName} was not found; leaving it unchanged.")
                return@classLoop
            }
            observedTargets += target
            val cloned = method.cloneMutable()
            val instructions = cloned.implementation?.instructions?.toList()
            if (instructions == null) {
                logger.warning("Legacy compatibility: downloader method ${target.classType}->${target.methodName} has no implementation; leaving it unchanged.")
                return@classLoop
            }
            var methodPatched = 0
            val getActivityCalls = instructions.mapIndexedNotNull { index, instruction ->
                val reference = instruction as? ReferenceInstruction ?: return@mapIndexedNotNull null
                if (isFixedGetActivity(reference)) index to reference else null
            }
            if (getActivityCalls.size != 1) {
                skipped += getActivityCalls.size
                logger.warning("Legacy compatibility: expected exactly one fixed PendingIntent.getActivity in the targeted method; found " + getActivityCalls.size + "; leaving it unchanged.")
                return@classLoop
            }
            for ((index, referenceInstruction) in getActivityCalls) {
                val registers = pendingIntentCallRegisters(referenceInstruction)
                if (registers == null) {
                    skipped++
                    logger.warning("Legacy compatibility: skipped unsupported PendingIntent.getActivity register layout in ${target.classType}->${target.methodName}.")
                    continue
                }
                val flagsRegister = registers.last()
                val constantIndex = index - 1
                val constant = instructions.getOrNull(constantIndex)
                val replacement = replacementDownloaderPendingIntentInstruction(constant, flagsRegister)
                if (replacement == null) {
                    skipped++
                    logger.warning("Legacy compatibility: skipped PendingIntent.getActivity in ${target.classType}->${target.methodName}; expected adjacent const/high16 FLAG_UPDATE_CURRENT for the flags register.")
                    continue
                }
                val mergedFlags = (replacement as NarrowLiteralInstruction).narrowLiteral
                cloned.replaceInstruction(constantIndex, "const/high16 v$flagsRegister, 0x${mergedFlags.toString(16)}")
                methodPatched++
            }
            if (methodPatched > 0) {
                mutableClass.methods.remove(method)
                mutableClass.methods.add(cloned)
                patched += methodPatched
                logger.info("Legacy compatibility: added FLAG_IMMUTABLE to $methodPatched fixed PendingIntent call(s) in ${target.classType}->${target.methodName}.")
            }
        }

        for (target in downloaderPendingIntentTargets) {
            if (target !in observedTargets) {
                logger.info("Legacy compatibility: fixed downloader target ${target.classType}->${target.methodName} was not present; no change made.")
            }
        }
        logger.info("Legacy compatibility: downloader PendingIntent fix patched $patched call(s); skipped $skipped.")
    }
}
