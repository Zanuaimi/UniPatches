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
    DownloaderPendingIntentTarget(GOOGLE_EXPANSION_DOWNLOADER_TYPE, "finishOnCreate", emptyList()),
    DownloaderPendingIntentTarget(
        UNPACKING_LISTENER,
        "<init>",
        listOf(GOOGLE_EXPANSION_DOWNLOADER_TYPE, "I", "I"),
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

internal fun licenseServiceIntentConstructors(instructions: List<Instruction>): List<Pair<Int, List<Int>>> =
    instructions.mapIndexedNotNull { constructorIndex, instruction ->
        val constructor = instruction as? ReferenceInstruction ?: return@mapIndexedNotNull null
        val registers = intentStringConstructorRegisters(constructor) ?: return@mapIndexedNotNull null
        if (hasLicenseServiceBinding(instructions, registers[0], constructorIndex)) constructorIndex to registers else null
    }

internal fun hasVerifiableLicenseServiceIntent(instructions: List<Instruction>): Boolean =
    licenseServiceIntentConstructors(instructions).size == 1

internal fun hasPlayScopedLicenseServiceIntent(instructions: List<Instruction>): Boolean {
    if (!hasExplicitPlayPackage(instructions)) return false
    return instructions.indices.any { packageIndex ->
        val setPackage = (instructions[packageIndex] as? ReferenceInstruction)?.reference as? MethodReference
            ?: return@any false
        if (setPackage.definingClass != INTENT_CLASS || setPackage.name != "setPackage" ||
            setPackage.parameterTypes != listOf("Ljava/lang/String;") || setPackage.returnType != INTENT_CLASS
        ) return@any false
        val packageRegisters = invocationRegisters(instructions[packageIndex]) ?: return@any false
        if (packageRegisters.size != 2) return@any false
        val intentRegister = packageRegisters[0]
        val constructorIndex = (packageIndex - 1 downTo 0).firstOrNull { index ->
            val constructor = instructions[index] as? ReferenceInstruction ?: return@firstOrNull false
            intentStringConstructorRegisters(constructor)?.firstOrNull() == intentRegister
        } ?: return@any false
        constructorIndex < packageIndex && hasLicenseServiceBinding(instructions, intentRegister, packageIndex)
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

/**
 * Google ExpansionDownloader skips its LVL service check when expansion data is already delivered.
 * Allow pre-staging or launcher bypass only after the standard service gate has been emitted and
 * verified; unsupported LicenseChecker flows remain fail-closed.
 */
internal fun validateExpansionDownloaderLicenseFlow(
    bypassSelected: Boolean,
    embeddedObbSelected: Boolean,
    hasExpansionDownloader: Boolean,
    hasLicenseChecker: Boolean,
    verifiedGate: Boolean,
) {
    val gateRequired = hasLicenseChecker && (bypassSelected || embeddedObbSelected)
    require(!gateRequired || (hasExpansionDownloader && verifiedGate)) {
        "Legacy compatibility: OBB pre-staging or downloader bypass could skip an existing LicenseChecker flow; a verified ExpansionDownloader LVL gate was not emitted. Disable OBB pre-staging/downloader bypass or use a supported downloader."
    }
}

/** Restricts the LVL service intent to Google Play without bypassing license validation. */
internal fun legacyLicenseServiceIntentPatch(
    enabledProvider: () -> Boolean,
    bypassExpansionDownloaderProvider: () -> Boolean = { false },
    embeddedExpansionObbProvider: () -> Boolean = { false },
    verifiedExpansionLicenseGateProvider: () -> Boolean = { false },
    diagnosticsOnlyProvider: () -> Boolean = { false },
) = bytecodePatch(
    name = null,
    description = "Internal explicit Google Play licensing service intent phase.",
    default = false,
) {
    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val bypassSelected = bypassExpansionDownloaderProvider()
        val embeddedObbSelected = embeddedExpansionObbProvider()
        if (!enabledProvider() && !bypassSelected && !embeddedObbSelected) return@execute

        var hasLicenseChecker = false
        var hasExpansionDownloader = false
        classDefForEach { classDef ->
            if (classDef.type == LICENSE_CHECKER_CLASS) hasLicenseChecker = true
            if (classDef.type == GOOGLE_EXPANSION_DOWNLOADER_TYPE) hasExpansionDownloader = true
        }
        if (diagnosticsOnlyProvider()) {
            if (hasLicenseChecker && (bypassSelected || embeddedObbSelected)) {
                logger.info("Legacy compatibility: diagnostics-only would require a verified ExpansionDownloader LVL gate; no DEX changes applied.")
            }
            return@execute
        }
        validateExpansionDownloaderLicenseFlow(
            bypassSelected,
            embeddedObbSelected,
            hasExpansionDownloader,
            hasLicenseChecker,
            verifiedExpansionLicenseGateProvider(),
        )
        if (!hasLicenseChecker) {
            logger.info("Legacy compatibility: Google Play LicenseChecker not found; no LVL intent change made.")
            return@execute
        }
        if (!enabledProvider()) return@execute
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
        val constructors = licenseServiceIntentConstructors(instructions)
        if (constructors.size != 1) {
            error("Legacy compatibility: expected one Intent(String) passed to Context.bindService in LicenseChecker.checkAccess; found ${constructors.size}; refusing to ship an unverified LVL service intent.")
        }
        val (constructorIndex, registers) = constructors.single()
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
            "Legacy compatibility: patched LicenseChecker.checkAccess is missing explicit Intent.setPackage(com.android.vending) on the Context.bindService intent; refusing to ship an invalid LVL binding."
        }
        mutableClass.methods.remove(method)
        mutableClass.methods.add(cloned)
        val patchedMethod = mutableClass.methods.firstOrNull {
            it.name == "checkAccess" && it.parameterTypes == listOf(LICENSE_CHECKER_CALLBACK) && it.returnType == "V"
        }
        check(patchedMethod?.implementation?.instructions?.toList()?.let(::hasPlayScopedLicenseServiceIntent) == true) {
            "Legacy compatibility: emitted LicenseChecker.checkAccess DEX is missing explicit Intent.setPackage(com.android.vending) on the Context.bindService intent; refusing to ship an invalid LVL binding."
        }
        logger.info("Legacy compatibility: LicenseChecker.checkAccess verified; original service action retained, explicit Intent.setPackage(com.android.vending) added to its bound intent, and license verification remains enabled.")
    }
}
