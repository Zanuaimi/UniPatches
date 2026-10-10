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

internal data class DownloaderPendingIntentTarget(
    val classType: String,
    val methodName: String,
    val parameterTypes: List<String>,
)

internal val downloaderPendingIntentTargets = listOf(
    DownloaderPendingIntentTarget(DOWNLOADER_ACTIVITY, "finishOnCreate", emptyList()),
    DownloaderPendingIntentTarget(
        UNPACKING_LISTENER,
        "<init>",
        listOf(DOWNLOADER_ACTIVITY, "I", "I"),
    ),
)

internal fun pendingIntentCallRegisters(instruction: ReferenceInstruction): List<Int>? = when (instruction) {
    is BuilderInstruction35c -> if (instruction.registerCount == 4) {
        listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF)
    } else null
    is BuilderInstruction3rc -> if (instruction.registerCount == 4) {
        (instruction.startRegister until instruction.startRegister + 4).toList()
    } else null
    else -> null
}

internal fun isFixedGetActivity(instruction: ReferenceInstruction): Boolean {
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

private const val LICENSE_CHECKER_CLASS = "Lcom/google/android/vending/licensing/LicenseChecker;"
private const val LICENSE_CHECKER_CALLBACK = "Lcom/google/android/vending/licensing/LicenseCheckerCallback;"
private const val INTENT_CLASS = "Landroid/content/Intent;"
private const val PLAY_STORE_PACKAGE = "com.android.vending"
internal const val APKPURE_LICENSE_ACTION_BASE64 = "Y29tLmFuZHJvaWQudmVuZGluZy5saWNlbnNpbmcuSUxpY2Vuc2luZ1NlcnZpY2U="

internal fun hasApkPureLicenseAction(instructions: List<Instruction>): Boolean = instructions.any { instruction ->
    val reference = (instruction as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference
    (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
        reference?.string == APKPURE_LICENSE_ACTION_BASE64
}

internal fun licenseServiceIntentPackageBlock(intentRegister: Int, actionRegister: Int, packageRegister: Int): String? {
    if (listOf(intentRegister, actionRegister, packageRegister).any { it !in 0..15 }) return null
    if (packageRegister == intentRegister || packageRegister == actionRegister) return null
    return """
        invoke-direct {v$intentRegister, v$actionRegister}, $INTENT_CLASS-><init>(Ljava/lang/String;)V
        const-string v$packageRegister, "$PLAY_STORE_PACKAGE"
        invoke-virtual {v$intentRegister, v$packageRegister}, $INTENT_CLASS->setPackage(Ljava/lang/String;)Landroid/content/Intent;
    """.trimIndent()
}

internal fun isIntentStringConstructor(instruction: ReferenceInstruction): Boolean {
    val reference = instruction.reference as? MethodReference ?: return false
    return reference.definingClass == INTENT_CLASS &&
        reference.name == "<init>" &&
        reference.returnType == "V" &&
        reference.parameterTypes == listOf("Ljava/lang/String;")
}

internal fun intentStringConstructorRegisters(instruction: ReferenceInstruction): List<Int>? {
    if (!isIntentStringConstructor(instruction)) return null
    return when (instruction) {
        is BuilderInstruction35c -> if (instruction.registerCount == 2) {
            listOf(instruction.registerC, instruction.registerD)
        } else null
        is BuilderInstruction3rc -> if (instruction.registerCount == 2) {
            listOf(instruction.startRegister, instruction.startRegister + 1)
        } else null
        else -> null
    }
}

/** Restricts the LVL service intent to Google Play without bypassing license validation. */
internal fun legacyLicenseServiceIntentPatch(enabledProvider: () -> Boolean) = bytecodePatch(
    name = null,
    description = "Internal explicit Google Play licensing service intent phase.",
    default = false,
) {
    execute {
        val logger = Logger.getLogger(this::class.java.name)
        if (!enabledProvider()) return@execute

        var hasLicenseChecker = false
        classDefForEach { classDef ->
            if (classDef.type == LICENSE_CHECKER_CLASS) hasLicenseChecker = true
        }
        if (!hasLicenseChecker) {
            logger.info("Legacy compatibility: Google Play LicenseChecker not found; no LVL intent change made.")
            return@execute
        }
        val mutableClass = mutableClassDefByOrNull(LICENSE_CHECKER_CLASS)
        val method = mutableClass?.methods?.firstOrNull {
            it.name == "checkAccess" && it.parameterTypes == listOf(LICENSE_CHECKER_CALLBACK) && it.returnType == "V"
        }
        if (method == null) {
            logger.warning("Legacy compatibility: expected LicenseChecker.checkAccess method not found; leaving LVL binding unchanged.")
            return@execute
        }
        val implementation = method.implementation
        val instructions = implementation?.instructions?.toList()
        if (instructions == null) {
            logger.warning("Legacy compatibility: LicenseChecker.checkAccess has no implementation; leaving LVL binding unchanged.")
            return@execute
        }
        if (!hasApkPureLicenseAction(instructions)) {
            logger.warning("Legacy compatibility: APKPure LVL service-action fingerprint was not found; leaving LicenseChecker unchanged.")
            return@execute
        }
        val constructors = instructions.mapIndexedNotNull { index, instruction ->
            val reference = instruction as? ReferenceInstruction ?: return@mapIndexedNotNull null
            if (isIntentStringConstructor(reference)) index to reference else null
        }
        if (constructors.size != 1) {
            logger.warning("Legacy compatibility: expected one Intent(String) constructor in LicenseChecker.checkAccess; found ${constructors.size}; leaving LVL binding unchanged.")
            return@execute
        }
        val (constructorIndex, constructor) = constructors.single()
        val registers = intentStringConstructorRegisters(constructor)
        val originalRegisterCount = implementation.registerCount
        val packageRegister = originalRegisterCount
        val block = registers?.let { licenseServiceIntentPackageBlock(it[0], it[1], packageRegister) }
        if (block == null) {
            logger.warning("Legacy compatibility: unsupported Intent(String) register layout in LicenseChecker.checkAccess; leaving LVL binding unchanged.")
            return@execute
        }

        val cloned = method.cloneMutable(additionalRegisters = 1)
        val prologueSize = cloned.implementation!!.instructions.size - instructions.size
        cloned.replaceInstruction(constructorIndex + prologueSize, block)
        mutableClass.methods.remove(method)
        mutableClass.methods.add(cloned)
        logger.info("Legacy compatibility: scoped the Google Play LVL service intent to $PLAY_STORE_PACKAGE in LicenseChecker.checkAccess.")
    }
}
