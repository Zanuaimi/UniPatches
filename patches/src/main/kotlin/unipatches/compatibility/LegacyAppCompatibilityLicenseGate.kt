package unipatches.compatibility

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import helpers.bytecode.cloneMutable
import java.util.logging.Logger

private const val DOWNLOADER_SERVICE = "Lcom/google/android/vending/expansion/downloader/impl/DownloaderService;"
private const val DOWNLOADER_MARSHALLER = "Lcom/google/android/vending/expansion/downloader/DownloaderClientMarshaller;"
private const val LICENSE_CALLBACK = "Lcom/google/android/vending/licensing/LicenseCheckerCallback;"
private const val RESOURCES_DOWNLOADED = "resourcesDownloaded"
private const val ORIGINAL_CALLED = "originalCalled"
private const val ORIGINAL_ALLOW = "originalAllow"
private const val PACKAGE_NOT_FOUND_EXCEPTION = "Landroid/content/pm/PackageManager\$NameNotFoundException;"

internal class LegacyExpansionDownloaderLicenseGateResult {
    var verified: Boolean = false
}

private fun Instruction.fieldReference(name: String): FieldReference? {
    if (this !is ReferenceInstruction) return null
    return (reference as? FieldReference)?.takeIf { it.name == name }
}

private fun Instruction.methodReference(): MethodReference? {
    if (this !is ReferenceInstruction) return null
    return reference as? MethodReference
}

private fun List<Instruction>.callIndices(name: String, owner: String? = null): List<Int> =
    indices.filter { index ->
        val reference = get(index).methodReference()
        reference?.name == name && (owner == null || reference.definingClass == owner)
    }

private fun invocationRegisters(instruction: Instruction): List<Int>? = when (instruction) {
    is FiveRegisterInstruction -> if (instruction.registerCount in 1..5) {
        listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
    } else null
    is RegisterRangeInstruction -> if (instruction.registerCount in 1..5) {
        List(instruction.registerCount) { instruction.startRegister + it }
    } else null
    else -> null
}

private fun patchActivityGate(activity: MutableClass, classes: Collection<ClassDef>): Int {
    val finish = activity.methods.singleOrNull {
        it.name == "finishOnCreate" && it.parameterTypes.isEmpty() && it.returnType == "V"
    } ?: error("Legacy compatibility: unsupported DownloaderActivity.finishOnCreate; cannot force LVL before OBB validation.")
    val initUi = activity.methods.singleOrNull {
        it.name == "initializeDownloadUI" && it.parameterTypes.isEmpty() && it.returnType == "V"
    } ?: error("Legacy compatibility: unsupported DownloaderActivity.initializeDownloadUI; cannot receive LVL service results safely.")
    val original = finish.implementation?.instructions?.toList()
        ?: error("Legacy compatibility: DownloaderActivity.finishOnCreate has no DEX implementation.")
    val initOriginal = initUi.implementation?.instructions?.toList()
        ?: error("Legacy compatibility: DownloaderActivity.initializeDownloadUI has no DEX implementation.")

    val deliveredCalls = original.filter { call ->
        val reference = call.methodReference()
        reference?.name == "expansionFilesDelivered" && reference.parameterTypes.isEmpty() && reference.returnType == "Z"
    }
    val serviceCallIndices = original.indices.filter { index ->
        val reference = original[index].methodReference()
        reference?.definingClass == DOWNLOADER_MARSHALLER && reference.name == "startDownloadServiceIfRequired"
    }
    val validationCalls = original.filter { call ->
        val reference = call.methodReference()
        reference?.name == "validateXAPKZipFiles" && reference.parameterTypes.isEmpty() && reference.returnType == "V"
    }
    val obsoleteCalls = original.callIndices("removeObsolete", activity.type)
    require(deliveredCalls.size == 1 && serviceCallIndices.size == 1 && validationCalls.size == 1 && obsoleteCalls.size == 1) {
        "Legacy compatibility: DownloaderActivity flow is not the recognized ExpansionDownloader shape; LVL cannot be forced safely."
    }
    val serviceCallIndex = serviceCallIndices.single()
    val serviceClassInstruction = original.getOrNull(serviceCallIndex - 1)
    val serviceType = (serviceClassInstruction as? ReferenceInstruction)?.reference as? TypeReference
    val serviceClassRegister = (serviceClassInstruction as? OneRegisterInstruction)?.registerA
    val serviceCallRegisters = invocationRegisters(original[serviceCallIndex])
    require(serviceClassInstruction?.opcode == Opcode.CONST_CLASS && serviceCallRegisters?.size == 3 &&
        serviceCallRegisters.lastOrNull() == serviceClassRegister
    ) {
        "Legacy compatibility: DownloaderActivity does not pass its resolved service class to startDownloadServiceIfRequired."
    }
    val serviceClass = serviceType?.type?.let { type -> classes.singleOrNull { it.type == type } }
        ?: error("Legacy compatibility: DownloaderActivity service class cannot be resolved; LVL path is unverified.")
    var parentType: String? = serviceClass.superclass
    val seenTypes = mutableSetOf(serviceClass.type)
    while (parentType != null && parentType != DOWNLOADER_SERVICE && seenTypes.add(parentType)) {
        parentType = classes.singleOrNull { it.type == parentType }?.superclass
    }
    require(parentType == DOWNLOADER_SERVICE) {
        "Legacy compatibility: DownloaderActivity service class does not inherit standard DownloaderService; cannot prove LVL enforcement."
    }
    val deliveredFieldWrites = original.filter { instruction ->
        instruction.opcode == Opcode.SPUT_BOOLEAN && instruction.fieldReference(RESOURCES_DOWNLOADED)?.definingClass == activity.type
    }
    require(deliveredFieldWrites.size == 1) {
        "Legacy compatibility: DownloaderActivity.resourcesDownloaded write is ambiguous; LVL gate was not applied."
    }
    val downloadedReads = original.indices.filter { index ->
        original[index].opcode == Opcode.SGET_BOOLEAN && original[index].fieldReference(RESOURCES_DOWNLOADED)?.definingClass == activity.type
    }
    require(downloadedReads.size == 1) {
        "Legacy compatibility: DownloaderActivity.resourcesDownloaded branch is ambiguous; LVL gate was not applied."
    }
    val readIndex = downloadedReads.single()
    val branch = original.getOrNull(readIndex + 1)
    val branchRegister = (branch as? OneRegisterInstruction)?.registerA
    val readRegister = (original[readIndex] as? OneRegisterInstruction)?.registerA
        ?: error("Legacy compatibility: missing resourcesDownloaded register; LVL gate was not applied.")
    require(branch?.opcode == Opcode.IF_NEZ && branchRegister == readRegister) {
        "Legacy compatibility: DownloaderActivity delivered-OBB branch changed; LVL cannot be forced safely."
    }
    val initReads = initOriginal.indices.filter { index ->
        initOriginal[index].opcode == Opcode.SGET_BOOLEAN && initOriginal[index].fieldReference(RESOURCES_DOWNLOADED)?.definingClass == activity.type
    }
    val createStubCalls = initOriginal.filter { instruction ->
        val reference = instruction.methodReference()
        reference?.definingClass == DOWNLOADER_MARSHALLER && reference.name == "CreateStub"
    }
    require(initReads.size == 1 && createStubCalls.size == 1) {
        "Legacy compatibility: DownloaderActivity client setup is not the recognized shape; LVL result delivery cannot be proven."
    }
    val printStackTraceCalls = original.indices.filter { index ->
        val reference = original[index].methodReference()
        reference?.definingClass == PACKAGE_NOT_FOUND_EXCEPTION && reference.name == "printStackTrace" &&
            reference.parameterTypes.isEmpty() && reference.returnType == "V"
    }
    val handlesServiceLookupFailure = finish.implementation!!.tryBlocks.any { tryBlock ->
        tryBlock.exceptionHandlers.any { it.exceptionType == PACKAGE_NOT_FOUND_EXCEPTION }
    }
    require(printStackTraceCalls.size == 1 && handlesServiceLookupFailure) {
        "Legacy compatibility: cannot identify the DownloaderService lookup failure path; LVL gate must fail closed."
    }

    require(readRegister <= 15) {
        "Legacy compatibility: DownloaderActivity.resourcesDownloaded register cannot be safely forced to false."
    }
    val mutableFinish = finish.cloneMutable()
    mutableFinish.replaceInstruction(readIndex, "const/4 v$readRegister, 0x0")
    mutableFinish.replaceInstruction(obsoleteCalls.single(), "nop")
    mutableFinish.addInstructions(printStackTraceCalls.single() + 1, "return-void")
    val mutableInit = initUi.cloneMutable()
    val initReadIndex = initReads.single()
    val initReadRegister = (initOriginal[initReadIndex] as OneRegisterInstruction).registerA
    require(initReadRegister <= 15) {
        "Legacy compatibility: DownloaderActivity stub-init register cannot be safely forced."
    }
    mutableInit.replaceInstruction(initReadIndex, "const/4 v$initReadRegister, 0x0")
    val initInstructions = initOriginal
    val inflateCalls = initInstructions.indices.filter { index ->
        val method = initInstructions[index].methodReference()
        method?.name == "inflateDownloadUI" && method.parameterTypes.isEmpty() && method.returnType == "V"
    }
    val inflateMethod = activity.methods.singleOrNull {
        it.name == "inflateDownloadUI" && it.parameterTypes.isEmpty() && it.returnType == "V"
    } ?: error("Legacy compatibility: DownloaderActivity.inflateDownloadUI is missing; cannot hide controls safely.")
    val dashboardFieldWrite = inflateMethod.implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.IPUT_OBJECT && instruction.fieldReference("mDashboard")?.definingClass == activity.type
    } == true
    val setState = activity.methods.singleOrNull { it.name == "setState" && it.parameterTypes == listOf("I") && it.returnType == "V" }
        ?: error("Legacy compatibility: DownloaderActivity status method is unavailable; cannot display LVL progress.")
    val initReturns = initInstructions.indices.filter { initInstructions[it].opcode == Opcode.RETURN_VOID }
    require(inflateCalls.size == 1 && dashboardFieldWrite && initReturns.size == 1) {
        "Legacy compatibility: DownloaderActivity UI initialization shape changed; cannot hide controls during LVL check."
    }
    mutableInit.addInstructions(
        initReturns.single(),
        """
            sget-boolean v0, ${activity.type}->resourcesDownloaded:Z
            if-eqz v0, :legacy_gate_ui_ready
            const/4 v0, 0x2
            invoke-direct {p0, v0}, ${activity.type}->setState(I)V
            iget-object v0, p0, ${activity.type}->mDashboard:Landroid/view/View;
            const/16 v1, 0x8
            invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
            :legacy_gate_ui_ready
        """.trimIndent(),
    )

    val mutableActivity = activity
    mutableActivity.methods.remove(finish)
    mutableActivity.methods.add(mutableFinish)
    mutableActivity.methods.remove(initUi)
    mutableActivity.methods.add(mutableInit)

    val finalFinish = mutableActivity.methods.single { it.name == "finishOnCreate" && it.parameterTypes.isEmpty() }
    val finalInit = mutableActivity.methods.single { it.name == "initializeDownloadUI" && it.parameterTypes.isEmpty() }
    val finalInstructions = finalFinish.implementation?.instructions?.toList().orEmpty()
    val finalInitInstructions = finalInit.implementation?.instructions?.toList().orEmpty()
    require(finalInstructions.none { instruction ->
        val reference = instruction.methodReference()
        reference?.name == "removeObsolete" && reference.definingClass == activity.type
    })
    require(finalInstructions.any { instruction ->
        instruction.opcode == Opcode.CONST_4 &&
            (instruction as? OneRegisterInstruction)?.registerA == readRegister &&
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == 0
    } && finalInitInstructions.any { instruction ->
        instruction.opcode == Opcode.CONST_4 &&
            (instruction as? OneRegisterInstruction)?.registerA == initReadRegister &&
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == 0
    }) {
        "Legacy compatibility: emitted downloader gate does not force service check and client binding."
    }
    return 2
}

private fun patchLicenseCallback(mutable: MutableClass): Int {
    fun patchFailureMethod(name: String, expectedChecks: Int, expectedStates: Set<Int>) {
        val originalMethod = mutable.methods.singleOrNull {
            it.name == name && it.parameterTypes == listOf("I") && it.returnType == "V"
        } ?: error("Legacy compatibility: unsupported LicenseCheckerCallback.$name; deny/error must block OBB validation.")
        val original = originalMethod.implementation?.instructions?.toList()
            ?: error("Legacy compatibility: LicenseCheckerCallback.$name has no implementation.")
        val checks = original.indices.filter { index ->
            original[index].opcode == Opcode.SGET_BOOLEAN &&
                original[index].fieldReference(ORIGINAL_CALLED)?.let { it.definingClass == DOWNLOADER_SERVICE && it.type == "Z" } == true
        }
        require(checks.size == expectedChecks) {
            "Legacy compatibility: unsupported LicenseCheckerCallback.$name fallback branches; refusing to ship a gate that could allow after denial."
        }
        checks.forEach { index ->
            val register = (original[index] as? OneRegisterInstruction)?.registerA
            val next = original.getOrNull(index + 1)
            require(register != null && register <= 15 && next?.opcode == Opcode.IF_NEZ &&
                (next as? OneRegisterInstruction)?.registerA == register
            ) {
                "Legacy compatibility: LicenseCheckerCallback.$name deny/error branch cannot be forced safely."
            }
        }
        val branchStates = checks.mapNotNull { index ->
            val branch = original.getOrNull(index + 1) as? BuilderInstruction21t ?: return@mapNotNull null
            val target = branch.target.location.index
            if (target !in original.indices) return@mapNotNull null
            val failurePath = original.drop(target).takeWhile { instruction ->
                instruction.opcode !in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32, Opcode.RETURN_VOID, Opcode.THROW)
            }
            val notifyIndex = failurePath.indexOfFirst { instruction ->
                val method = instruction.methodReference()
                method?.name == "onDownloadStateChanged" && method.parameterTypes == listOf("I")
            }
            if (notifyIndex < 0) return@mapNotNull null
            failurePath.take(notifyIndex).mapNotNull { instruction ->
                val literal = instruction as? NarrowLiteralInstruction ?: return@mapNotNull null
                literal.narrowLiteral.takeIf { instruction.opcode == Opcode.CONST_4 || instruction.opcode == Opcode.CONST_16 }
            }.lastOrNull()
        }.toSet()
        require(branchStates == expectedStates) {
            "Legacy compatibility: LicenseCheckerCallback.$name fallback branches do not map exactly to required deny/error states."
        }
        val originalAllowCalls = original.count { it.methodReference()?.name == ORIGINAL_ALLOW }
        require(originalAllowCalls == expectedChecks) {
            "Legacy compatibility: LicenseCheckerCallback.$name contains an unclassified fallback path."
        }
        val stateCalls = original.count { instruction ->
            val reference = instruction.methodReference()
            reference?.name == "onDownloadStateChanged" && reference.parameterTypes == listOf("I")
        }
        val stateConstants = original.mapNotNull { instruction ->
            val literal = instruction as? NarrowLiteralInstruction ?: return@mapNotNull null
            literal.narrowLiteral.takeIf { instruction.opcode == Opcode.CONST_4 || instruction.opcode == Opcode.CONST_16 }
        }.toSet()
        require(stateCalls >= expectedStates.size && stateConstants.containsAll(expectedStates)) {
            "Legacy compatibility: LicenseCheckerCallback.$name has no verified deny/error notification path."
        }
        val clone = originalMethod.cloneMutable()
        checks.asReversed().forEach { index ->
            val register = (original[index] as OneRegisterInstruction).registerA
            clone.replaceInstruction(index, "const/4 v$register, 0x1")
        }
        val patched = clone.implementation?.instructions?.toList().orEmpty()
        require(patched.none { instruction ->
            instruction.opcode == Opcode.SGET_BOOLEAN &&
                instruction.fieldReference(ORIGINAL_CALLED)?.let { it.definingClass == DOWNLOADER_SERVICE } == true
        } && checks.all { index ->
            val constant = patched.getOrNull(index)
            val branch = patched.getOrNull(index + 1)
            constant?.opcode == Opcode.CONST_4 &&
                (constant as? NarrowLiteralInstruction)?.narrowLiteral == 1 &&
                branch?.opcode == Opcode.IF_NEZ &&
                (branch as? OneRegisterInstruction)?.registerA == (constant as? OneRegisterInstruction)?.registerA
        }) {
            "Legacy compatibility: patched LicenseCheckerCallback.$name does not force fail-closed LVL results."
        }
        mutable.methods.remove(originalMethod)
        mutable.methods.add(clone)
    }

    patchFailureMethod("dontAllow", expectedChecks = 2, expectedStates = setOf(13, 14))
    patchFailureMethod("applicationError", expectedChecks = 1, expectedStates = setOf(14))
    val allow = mutable.methods.singleOrNull { it.name == "allow" && it.parameterTypes == listOf("I") && it.returnType == "V" }
        ?: error("Legacy compatibility: LicenseCheckerCallback.allow is missing; Unity success path cannot be proven.")
    require(allow.implementation?.instructions.orEmpty().any { it.methodReference()?.name == ORIGINAL_ALLOW }) {
        "Legacy compatibility: LVL allow callback no longer continues original expansion handling."
    }
    return 2
}

private fun findLicenseCallback(classes: Collection<ClassDef>): ClassDef {
    val candidates = classes.filter { classDef ->
        LICENSE_CALLBACK in classDef.interfaces &&
            classDef.methods.any { it.name == "dontAllow" && it.parameterTypes == listOf("I") && it.returnType == "V" } &&
            classDef.methods.any { it.name == "applicationError" && it.parameterTypes == listOf("I") && it.returnType == "V" } &&
            classDef.methods.any { it.name == "allow" && it.parameterTypes == listOf("I") && it.returnType == "V" }
    }
    if (candidates.size != 1) {
        error("Legacy compatibility: expected one standard ExpansionDownloader LicenseCheckerCallback; found ${candidates.size}; cannot preserve LVL safely.")
    }
    val callback = candidates.single()
    require(callback.methods.any { method ->
        method.name == "dontAllow" && method.implementation?.instructions?.any { it.methodReference()?.name == ORIGINAL_ALLOW } == true
    }) {
        "Legacy compatibility: ExpansionDownloader LVL callback has an unrecognized denial path."
    }
    return callback
}

internal fun legacyExpansionDownloaderLicenseGatePatch(
    result: LegacyExpansionDownloaderLicenseGateResult,
    enabledProvider: () -> Boolean,
) = bytecodePatch(
    name = null,
    description = "Internal fail-closed ExpansionDownloader LVL gate for pre-staged OBBs.",
    default = false,
) {
    execute {
        result.verified = false
        if (!enabledProvider()) return@execute
        val classes = mutableListOf<ClassDef>()
        classDefForEach { classes += it }
        val licenseChecker = classes.any { it.type == "Lcom/google/android/vending/licensing/LicenseChecker;" }
        if (!licenseChecker) {
            Logger.getLogger("LegacyAppCompatibility").info("Legacy compatibility: no LicenseChecker in DEX; ExpansionDownloader LVL gate not required.")
            return@execute
        }
        val activity = classes.singleOrNull { it.type == GOOGLE_EXPANSION_DOWNLOADER_TYPE }
            ?: error("Legacy compatibility: existing LicenseChecker detected but standard DownloaderActivity is missing; cannot gate pre-staged OBB safely.")
        val baseService = classes.any { it.type == DOWNLOADER_SERVICE }
        require(baseService) {
            "Legacy compatibility: existing LicenseChecker detected but standard DownloaderService is missing; cannot gate pre-staged OBB safely."
        }
        val callback = findLicenseCallback(classes)
        val licenseCheckCallers = classes.filter { it.type.startsWith(DOWNLOADER_SERVICE.removeSuffix(";") + "\$") }
            .flatMap { it.methods }
            .mapNotNull { method ->
                val instructions = method.implementation?.instructions?.toList().orEmpty()
                val callIndices = instructions.indices.filter { index ->
                    val reference = instructions[index].methodReference()
                    reference?.definingClass == "Lcom/google/android/vending/licensing/LicenseChecker;" &&
                        reference.name == "checkAccess" &&
                        reference.parameterTypes == listOf("Lcom/google/android/vending/licensing/LicenseCheckerCallback;") &&
                        reference.returnType == "V"
                }
                callIndices.singleOrNull()?.let { index -> instructions to index }
            }
        val verifiedCallCount = licenseCheckCallers.count { (instructions, index) ->
            val callRegisters = invocationRegisters(instructions[index])
            val constructorInstruction = instructions.getOrNull(index - 1)
            val constructor = constructorInstruction?.methodReference()
            val constructorRegisters = constructorInstruction?.let(::invocationRegisters)
            val allocation = instructions.getOrNull(index - 2)
            val allocationType = (allocation as? ReferenceInstruction)?.reference as? TypeReference
            val callbackRegister = callRegisters?.getOrNull(1)
            callbackRegister != null && callRegisters.size == 2 &&
                constructor?.definingClass == callback.type && constructor.name == "<init>" &&
                constructorRegisters?.firstOrNull() == callbackRegister &&
                allocation?.opcode == Opcode.NEW_INSTANCE &&
                (allocation as? OneRegisterInstruction)?.registerA == callbackRegister &&
                allocationType?.type == callback.type
        }
        require(licenseCheckCallers.size == 1 && verifiedCallCount == 1) {
            "Legacy compatibility: standard DownloaderService does not pass its constructed LicenseCheckerCallback to exactly one checkAccess call; refusing to gate pre-staged OBB without the original LVL policy path."
        }
        val mutableActivity = mutableClassDefByOrNull(activity.type)
            ?: error("Legacy compatibility: DownloaderActivity is not mutable; LVL gate was not applied.")
        val mutableCallback = mutableClassDefByOrNull(callback.type)
            ?: error("Legacy compatibility: detected ExpansionDownloader LVL callback is not mutable.")
        val changes = patchActivityGate(mutableActivity, classes) + patchLicenseCallback(mutableCallback)
        result.verified = changes == 4
        check(result.verified) { "Legacy compatibility: emitted ExpansionDownloader LVL gate was not fully verified." }
        Logger.getLogger("LegacyAppCompatibility").info(
            "Legacy compatibility: verified ExpansionDownloader LVL gate; staged OBB is preserved, service check runs before validation, downloader controls stay hidden during license check, Unity remains behind allow callback, and deny/error block launch.",
        )
    }
}
