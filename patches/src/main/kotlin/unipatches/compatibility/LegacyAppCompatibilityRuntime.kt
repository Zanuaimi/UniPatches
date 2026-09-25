package unipatches.compatibility

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import helpers.bytecode.numberOfParameterRegisters
import helpers.bytecode.p0Register
import helpers.bytecode.cloneMutable
import helpers.startup.StartupHooks
import java.util.logging.Logger

/** Runtime hooks backed by unipatch.compatcore.LegacyCompatRuntime in the extension dex. */
internal const val COMPAT_RUNTIME = "Lunipatch/compatcore/LegacyCompatRuntime;"

/** Runtime hook selection for the shared startup injection. */
internal data class LegacyRuntimeOptions(
    val hiddenApiExemptions: Boolean,
    val trustCertificates: Boolean,
    val storageRedirect: Boolean,
) {
    val anyEnabled: Boolean
        get() = hiddenApiExemptions || trustCertificates || storageRedirect
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
    if (method.implementation?.instructions?.any { it.toString().contains("LegacyCompatRuntime;->") } == true) {
        return false
    }
    val base = method.implementation?.registerCount ?: return false
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 1)
    val receiver = cloned.p0Register
    val instructions = cloned.implementation?.instructions.orEmpty()
    val superIndex = instructions.indexOfFirst { instruction ->
        instruction.toString().contains("invoke-super") && instruction.toString().contains("->onCreate(")
    }
    val index = if (superIndex >= 0) superIndex + 1 else 0
    val block = buildString {
        if (options.storageRedirect) {
            appendLine("move-object/from16 v$base, v$receiver")
            appendLine("invoke-static/range {v$base .. v$base}, $COMPAT_RUNTIME->init(Landroid/content/Context;)V")
        }
        if (options.hiddenApiExemptions) {
            appendLine("invoke-static {}, $COMPAT_RUNTIME->exemptHiddenApis()V")
        }
        if (options.trustCertificates) {
            appendLine("invoke-static {}, $COMPAT_RUNTIME->trustAllCertificates()V")
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

        val candidates = listOfNotNull(
            StartupHooks.resolvedApplicationDescriptor,
            StartupHooks.resolvedLauncherActivityDescriptor,
        )
        for (descriptor in candidates) {
            val classDef = classDefByOrNull(descriptor) ?: continue
            val method = classDef.methods.firstOrNull {
                it.name == "onCreate" && it.returnType == "V" && it.parameterTypes.isEmpty()
            } ?: continue
            val mutableClass = mutableClassDefByOrNull(classDef.type) ?: continue
            val mutableMethod = mutableClass.methods.firstOrNull {
                it.name == "onCreate" && it.returnType == "V" && it.parameterTypes.isEmpty()
            } ?: continue
            if (injectLegacyCompatStartup(mutableClass, mutableMethod, options)) {
                logger.info("Legacy compatibility: injected LegacyCompatRuntime startup hooks ($options) into ${classDef.type}->onCreate.")
                return@execute
            }
        }
        logger.warning("Legacy compatibility: no suitable Application.onCreate or launcher onCreate found; runtime hooks skipped.")
    }
}
