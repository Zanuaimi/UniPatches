package unipatches.compatibility

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21ih
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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

// Shared Google Play expansion-downloader library call sites; apply only when these classes are present.
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

internal fun hasExplicitPlayPackage(instructions: Iterable<Instruction>): Boolean {
    val list = instructions.toList()
    return list.indices.any { index ->
        if (index < 2) return@any false
        val invoke = list[index]
        val method = (invoke as? ReferenceInstruction)?.reference as? MethodReference
        if (method?.definingClass != INTENT_CLASS || method.name != "setPackage" ||
            method.parameterTypes != listOf("Ljava/lang/String;") || method.returnType != INTENT_CLASS
        ) return@any false
        val invokeRegisters = when (invoke) {
            is FiveRegisterInstruction -> if (invoke.registerCount == 2) listOf(invoke.registerC, invoke.registerD) else null
            is RegisterRangeInstruction -> if (invoke.registerCount == 2) listOf(invoke.startRegister, invoke.startRegister + 1) else null
            else -> null
        } ?: return@any false
        val stringInstruction = list[index - 1]
        val stringReference = (stringInstruction as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference
        val stringRegister = (stringInstruction as? OneRegisterInstruction)?.registerA
        if ((stringInstruction.opcode != Opcode.CONST_STRING && stringInstruction.opcode != Opcode.CONST_STRING_JUMBO) ||
            stringReference?.string != PLAY_STORE_PACKAGE || stringRegister != invokeRegisters[1]
        ) return@any false
        val constructor = list[index - 2] as? ReferenceInstruction ?: return@any false
        intentStringConstructorRegisters(constructor)?.firstOrNull() == invokeRegisters[0]
    }
}

internal fun hasPlayScopedLicenseServiceIntent(instructions: List<Instruction>): Boolean {
    if (!hasApkPureLicenseAction(instructions) || !hasExplicitPlayPackage(instructions)) return false
    val packageIntentRegisters = instructions.indices.mapNotNull { index ->
        val reference = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        if (reference.definingClass != INTENT_CLASS || reference.name != "setPackage" ||
            reference.parameterTypes != listOf("Ljava/lang/String;") || reference.returnType != INTENT_CLASS
        ) return@mapNotNull null
        val invokeRegisters = invocationRegisters(instructions[index]) ?: return@mapNotNull null
        val packageInstruction = instructions.getOrNull(index - 1) ?: return@mapNotNull null
        val packageString = (packageInstruction as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference
        if (packageString?.string != PLAY_STORE_PACKAGE ||
            (packageInstruction as? OneRegisterInstruction)?.registerA != invokeRegisters.getOrNull(1)
        ) return@mapNotNull null
        val constructor = instructions.getOrNull(index - 2) as? ReferenceInstruction ?: return@mapNotNull null
        val constructorRegisters = intentStringConstructorRegisters(constructor) ?: return@mapNotNull null
        if (invokeRegisters.size == 2 && constructorRegisters[0] == invokeRegisters[0]) Triple(index - 2, index, constructorRegisters) else null
    }
    return packageIntentRegisters.any { (constructorIndex, packageIndex, registers) ->
        hasApkPureLicenseActionForIntent(instructions, constructorIndex, registers[1]) &&
            hasLicenseServiceBinding(instructions, registers[0], packageIndex)
    }
}

private fun invocationRegisters(instruction: Instruction): List<Int>? = when (instruction) {
    is FiveRegisterInstruction -> {
        if (instruction.registerCount !in 1..5) null
        else listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG)
            .take(instruction.registerCount)
    }
    is RegisterRangeInstruction -> {
        if (instruction.registerCount !in 1..5) null
        else List(instruction.registerCount) { instruction.startRegister + it }
    }
    else -> null
}

private fun hasApkPureLicenseActionForIntent(instructions: List<Instruction>, constructorIndex: Int, actionRegister: Int): Boolean {
    val stringConstructor = instructions.getOrNull(constructorIndex - 1) as? ReferenceInstruction ?: return false
    val stringMethod = stringConstructor.reference as? MethodReference ?: return false
    if (stringMethod.definingClass != "Ljava/lang/String;" || stringMethod.name != "<init>" ||
        stringMethod.parameterTypes != listOf("[B")
    ) return false
    val stringRegisters = invocationRegisters(stringConstructor) ?: return false
    if (stringRegisters.size != 2 || stringRegisters[0] != actionRegister) return false
    val moveResult = instructions.getOrNull(constructorIndex - 2) ?: return false
    if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT || (moveResult as? OneRegisterInstruction)?.registerA != stringRegisters[1]) return false
    val decode = instructions.getOrNull(constructorIndex - 3) as? ReferenceInstruction ?: return false
    val decodeMethod = decode.reference as? MethodReference ?: return false
    if (decodeMethod.definingClass != "Lcom/google/android/vending/licensing/util/Base64;" ||
        decodeMethod.name != "decode" || decodeMethod.parameterTypes != listOf("Ljava/lang/String;") || decodeMethod.returnType != "[B"
    ) return false
    val decodeRegisters = invocationRegisters(decode) ?: return false
    val actionString = instructions.getOrNull(constructorIndex - 4) ?: return false
    val reference = (actionString as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference
    return (actionString.opcode == Opcode.CONST_STRING || actionString.opcode == Opcode.CONST_STRING_JUMBO) &&
        reference?.string == APKPURE_LICENSE_ACTION_BASE64 &&
        (actionString as? OneRegisterInstruction)?.registerA == decodeRegisters.singleOrNull()
}

private fun hasLicenseServiceBinding(instructions: List<Instruction>, intentRegister: Int, packageIndex: Int): Boolean =
    instructions.withIndex().any { (index, instruction) ->
        if (index <= packageIndex) return@any false
        val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        method.definingClass == "Landroid/content/Context;" && method.name == "bindService" &&
            method.parameterTypes == listOf("Landroid/content/Intent;", "Landroid/content/ServiceConnection;", "I") &&
            method.returnType == "Z" && invocationRegisters(instruction)?.getOrNull(1) == intentRegister
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
        is FiveRegisterInstruction -> if (instruction.registerCount == 2) {
            listOf(instruction.registerC, instruction.registerD)
        } else null
        is RegisterRangeInstruction -> if (instruction.registerCount == 2) {
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
            ?: error("Legacy compatibility: LicenseChecker class was detected but could not be modified; refusing to ship an implicit LVL service intent.")
        val method = mutableClass.methods.firstOrNull {
            it.name == "checkAccess" && it.parameterTypes == listOf(LICENSE_CHECKER_CALLBACK) && it.returnType == "V"
        }
        if (method == null) {
            error("Legacy compatibility: detected LicenseChecker has no supported checkAccess method; refusing to ship an unverified LVL service intent.")
        }
        val implementation = method.implementation
        val instructions = implementation?.instructions?.toList()
        if (instructions == null) {
            error("Legacy compatibility: LicenseChecker.checkAccess has no implementation; refusing to ship an unverified LVL service intent.")
        }
        if (!hasApkPureLicenseAction(instructions)) {
            error("Legacy compatibility: APKPure LVL service-action fingerprint was not found in LicenseChecker.checkAccess; refusing to ship an unverified service intent.")
        }
        val constructors = instructions.mapIndexedNotNull { index, instruction ->
            val reference = instruction as? ReferenceInstruction ?: return@mapIndexedNotNull null
            if (isIntentStringConstructor(reference)) index to reference else null
        }
        if (constructors.size != 1) {
            error("Legacy compatibility: expected one Intent(String) constructor in LicenseChecker.checkAccess; found ${constructors.size}; refusing to ship an unverified LVL service intent.")
        }
        val (constructorIndex, constructor) = constructors.single()
        val registers = intentStringConstructorRegisters(constructor)
        val originalRegisterCount = implementation.registerCount
        val packageRegister = originalRegisterCount
        val block = registers?.let { licenseServiceIntentPackageBlock(it[0], it[1], packageRegister) }
        if (block == null) {
            error("Legacy compatibility: unsupported Intent(String) register layout in LicenseChecker.checkAccess; refusing to ship an unverified LVL service intent.")
        }

        val cloned = method.cloneMutable(additionalRegisters = 1)
        val prologueSize = cloned.implementation!!.instructions.size - instructions.size
        val replacementInstructions = block.lines()
        cloned.replaceInstruction(constructorIndex + prologueSize, replacementInstructions.first())
        cloned.addInstructions(constructorIndex + prologueSize + 1, replacementInstructions.drop(1).joinToString("\n"))
        check(hasPlayScopedLicenseServiceIntent(cloned.implementation!!.instructions.toList())) {
            "Legacy compatibility: patched LicenseChecker.checkAccess is missing the APKPure license action or explicit Intent.setPackage(com.android.vending); refusing to ship an invalid LVL binding."
        }
        mutableClass.methods.remove(method)
        mutableClass.methods.add(cloned)
        val patchedMethod = mutableClass.methods.firstOrNull {
            it.name == "checkAccess" && it.parameterTypes == listOf(LICENSE_CHECKER_CALLBACK) && it.returnType == "V"
        }
        check(patchedMethod?.implementation?.instructions?.toList()?.let(::hasPlayScopedLicenseServiceIntent) == true) {
            "Legacy compatibility: post-patch LicenseChecker.checkAccess DEX is missing the APKPure license action or explicit Intent.setPackage(com.android.vending); refusing to ship an invalid LVL binding."
        }
        logger.info("Legacy compatibility: LicenseChecker.checkAccess patch verified; APKPure license action preserved, Intent.setPackage(com.android.vending) added, and license verification remains enabled.")
    }
}
