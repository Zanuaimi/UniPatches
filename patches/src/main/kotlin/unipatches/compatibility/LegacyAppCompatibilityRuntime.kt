package unipatches.compatibility

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.bytecode.numberOfParameterRegisters
import helpers.bytecode.p0Register
import helpers.bytecode.cloneMutable
import helpers.startup.StartupHooks
import helpers.startup.resolveStartupEntryPoint
import java.util.logging.Logger

/** Runtime hooks backed by unipatch.compatcore.LegacyCompatRuntime in the extension dex. */
internal const val COMPAT_RUNTIME = "Lunipatch/compatcore/LegacyCompatRuntime;"

/** Runtime hook selection for the shared startup injection. */
internal data class LegacyRuntimeOptions(
    val hiddenApiExemptions: Boolean,
    val trustCertificates: Boolean,
    val storageRedirect: Boolean,
    val embeddedExpansionObb: Boolean,
    val expansionDownloaderBypass: Boolean,
) {
    val anyEnabled: Boolean
        get() = hiddenApiExemptions || trustCertificates || storageRedirect || embeddedExpansionObb || expansionDownloaderBypass
}

internal fun legacyStorageReplacement(
    definingClass: String,
    name: String,
    parameterTypes: List<String>,
    returnType: String,
): String? {
    if (definingClass != "Landroid/os/Environment;" || returnType != "Ljava/io/File;") return null
    return when {
        name == "getExternalStorageDirectory" && parameterTypes.isEmpty() ->
            "$COMPAT_RUNTIME->legacyExternalStorageDirectory()Ljava/io/File;"
        name == "getExternalStoragePublicDirectory" && parameterTypes == listOf("Ljava/lang/String;") ->
            "$COMPAT_RUNTIME->legacyExternalStoragePublicDirectory(Ljava/lang/String;)Ljava/io/File;"
        else -> null
    }
}

internal fun legacyStorageReplacement(reference: MethodReference): String? = legacyStorageReplacement(
    reference.definingClass,
    reference.name,
    reference.parameterTypes.map(CharSequence::toString),
    reference.returnType,
)

/** Rewrites app callsites; framework classes remain untouched. */
internal fun BytecodePatchContext.redirectLegacyStorageCalls(): Int {
    var patched = 0
    classDefForEach { classDef ->
        var hasReference = false
        for (method in classDef.methods) {
            val implementation = method.implementation ?: continue
            if (implementation.instructions.any { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@any false
                legacyStorageReplacement(reference) != null
            }) {
                hasReference = true
                break
            }
        }
        if (!hasReference) return@classDefForEach
        val mutableClass = mutableClassDefBy(classDef)
        for (method in mutableClass.methods) {
            val instructions = method.implementation?.instructions.orEmpty()
            for ((index, instruction) in instructions.withIndex()) {
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                val replacement = legacyStorageReplacement(reference) ?: continue
                val old = "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"
                val rendered = instruction.toString()
                if (!rendered.contains(old)) continue
                method.replaceInstruction(index, rendered.replace(old, replacement))
                patched++
            }
        }
    }
    return patched
}

/**
 * Injects LegacyCompatRuntime initialization and the selected static hook calls into
 * Application.onCreate (launcher Activity fallback), mirroring the UniManager startup
 * injection shape.
 */
internal fun injectLegacyCompatStartup(
    owner: MutableClass,
    method: MutableMethod,
    options: LegacyRuntimeOptions,
): Boolean {
    val originalInstructions = method.implementation?.instructions.orEmpty()
    val hasInit = originalInstructions.any { it.toString().contains("$COMPAT_RUNTIME->init(") }
    val hasPrepare = originalInstructions.any { it.toString().contains("$COMPAT_RUNTIME->prepareEmbeddedExpansion(") }
    val hasHiddenApi = originalInstructions.any { it.toString().contains("$COMPAT_RUNTIME->exemptHiddenApis(") }
    val hasTrust = originalInstructions.any { it.toString().contains("$COMPAT_RUNTIME->trustAllCertificates(") }
    val addInit = (options.storageRedirect || options.embeddedExpansionObb) && !hasInit
    val addPrepare = options.embeddedExpansionObb && !hasPrepare
    val addHiddenApi = options.hiddenApiExemptions && !hasHiddenApi
    val addTrust = options.trustCertificates && !hasTrust
    if (!addInit && !addPrepare && !addHiddenApi && !addTrust) return false

    val base = method.implementation?.registerCount ?: return false
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 1)
    val receiver = cloned.p0Register
    val instructions = cloned.implementation?.instructions.orEmpty()
    val superIndex = instructions.indexOfFirst { instruction ->
        instruction.toString().contains("invoke-super") && instruction.toString().contains("->onCreate(")
    }
    val index = if (superIndex >= 0) superIndex + 1 else 0
    val block = buildString {
        if (addInit || addPrepare) {
            appendLine("move-object/from16 v$base, v$receiver")
        }
        if (addInit) {
            appendLine("invoke-static/range {v$base .. v$base}, $COMPAT_RUNTIME->init(Landroid/content/Context;)V")
        }
        if (addPrepare) {
            appendLine("invoke-static/range {v$base .. v$base}, $COMPAT_RUNTIME->prepareEmbeddedExpansion(Landroid/content/Context;)V")
        }
        if (addHiddenApi) {
            appendLine("invoke-static {}, $COMPAT_RUNTIME->exemptHiddenApis()V")
        }
        if (addTrust) {
            appendLine("const/16 v$base, 0x1")
            appendLine("invoke-static {v$base}, $COMPAT_RUNTIME->trustAllCertificates(Z)V")
        }
    }.trimEnd()
    if (block.isEmpty()) return false
    cloned.addInstructionsWithLabels(index, block)
    owner.methods.remove(method)
    owner.methods.add(cloned)
    return true
}

internal fun legacyRuntimeHooksPatch(optionsProvider: () -> LegacyRuntimeOptions) = bytecodePatch(
    name = null,
    description = "Internal legacy runtime hooks phase.",
    default = false,
) {
    // Runtime helpers (unipatch.compatcore, org.lsposed.hiddenapibypass) live in the extension dex.
    extendWith("extensions/extension.mpe")
    dependsOn(StartupHooks.resolveRealApplicationPatch)

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val options = optionsProvider()
        if (!options.anyEnabled) {
            logger.info("Legacy compatibility: no runtime hooks selected; startup injection skipped.")
            return@execute
        }

        val entry = resolveStartupEntryPoint(logger)
        var startupReady = false
        if (entry == null) {
            val message = "Legacy compatibility: no suitable Application.onCreate or launcher onCreate found; startup hooks skipped."
            if (options.expansionDownloaderBypass) error(message + " Expansion downloader bypass cannot continue safely.")
            logger.warning(message)
        } else {
            val injected = injectLegacyCompatStartup(entry.owner, entry.onCreate, options)
            val hasInit = entry.onCreate.implementation?.instructions.orEmpty().any {
                it.toString().contains("$COMPAT_RUNTIME->init(")
            }
            startupReady = !options.storageRedirect && !options.embeddedExpansionObb || injected || hasInit
            if (injected) {
                logger.info("Legacy compatibility: injected LegacyCompatRuntime startup hooks ($options) into ${entry.owner.type}->onCreate.")
            } else {
                logger.info("Legacy compatibility: startup hooks already present in ${entry.owner.type}->onCreate.")
            }
        }
        if (options.expansionDownloaderBypass && !startupReady) {
            error("Legacy compatibility: expansion downloader bypass requires proven startup injection for embedded OBB staging.")
        }
        if (options.storageRedirect) {
            if (!startupReady) {
                logger.warning("Legacy compatibility: storage callsite rewriting skipped because LegacyCompatRuntime initialization was not proven.")
            } else {
                val rewritten = redirectLegacyStorageCalls()
                logger.info("Legacy compatibility: redirected $rewritten Environment external-storage callsite(s).")
            }
        }
    }
}
