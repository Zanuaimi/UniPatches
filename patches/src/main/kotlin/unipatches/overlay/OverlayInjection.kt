package unipatches.overlay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import helpers.bytecode.cloneMutable
import helpers.bytecode.numberOfParameterRegisters
import helpers.bytecode.p0Register
import helpers.startup.StartupHooks

internal const val OVERLAY_RUNTIME_CLASS = "Lunipatch/overlaycore/OverlayRuntime;"

/** Inserts the shared runtime bridge and optional Control App Ads policy at one safe entry point. */
internal fun injectOverlayBridge(
    context: BytecodePatchContext,
    owner: MutableClass,
    method: MutableMethod,
    config: String,
    application: Boolean,
    adsRuntimePolicy: String?,
) {
    val temporaryBase = method.implementation?.registerCount
        ?: error("Cannot inject into ${owner.type}->${method.name} without an implementation")
    val temporaryCount = if (adsRuntimePolicy == null) 2 else 3
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + temporaryCount)
    val receiver = cloned.p0Register
    val type = if (application) "Landroid/app/Application;" else "Landroid/app/Activity;"
    val index = if (application) 0 else {
        val instructions = cloned.implementation?.instructions
        val superIndex = instructions?.indexOfFirst {
            val text = it.toString()
            text.contains("invoke-super") && text.contains("->onCreate(")
        } ?: -1
        if (superIndex >= 0) superIndex + 1 else maxOf(0, (instructions?.size ?: 0) - 1)
    }
    val adsPolicy = adsRuntimePolicy?.let { policy ->
        """
        const-string v${temporaryBase + 2}, "${StartupHooks.escapeSmali(policy)}"
        invoke-static/range {v${temporaryBase + 2} .. v${temporaryBase + 2}}, Lunipatch/overlaycore/AdsRuntimePolicy;->configure(Ljava/lang/String;)V
        """.trimIndent()
    }.orEmpty()
    cloned.addInstructionsWithLabels(index, """
        move-object/from16 v$temporaryBase, v$receiver
        const-string v${temporaryBase + 1}, "${StartupHooks.escapeSmali(config)}"
        $adsPolicy
        invoke-static/range {v$temporaryBase .. v${temporaryBase + 1}}, $OVERLAY_RUNTIME_CLASS->${if (application) "install" else "installActivity"}(${type}Ljava/lang/String;)V
    """.trimIndent())
    owner.methods.remove(method)
    owner.methods.add(cloned)
    if (adsRuntimePolicy == null) {
        OverlayAdsRuntimeIntegration.recordUnconfiguredBridge(
            OverlayAdsRuntimeIntegration.BridgeTarget(
                context = context,
                ownerType = owner.type,
                methodName = cloned.name,
                returnType = cloned.returnType,
                parameterTypes = cloned.parameterTypes.map { it.toString() },
            ),
        )
    }
    OverlayPatchRunMarker.publish(context, owner, cloned)
}

/** Adds app-specific module selection to the bridge previously injected by Universal Overlay. */
internal fun BytecodePatchContext.injectAppSpecificModules(
    bridge: OverlayPatchRunMarker.Bridge,
    profileId: String,
    selectedModules: String,
): Boolean {
    val owner = mutableClassDefByOrNull(bridge.ownerType) ?: return false
    val method = owner.methods.firstOrNull {
        it.name == bridge.methodName && it.returnType == bridge.returnType &&
            it.parameterTypes.map { parameter -> parameter.toString() } == bridge.parameterTypes
    } ?: return false
    val base = method.implementation?.registerCount ?: return false
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 2)
    cloned.addInstructionsWithLabels(0, """
        const-string v$base, "${StartupHooks.escapeSmali(profileId)}"
        const-string v${base + 1}, "${StartupHooks.escapeSmali(selectedModules)}"
        invoke-static/range {v$base .. v${base + 1}}, $OVERLAY_RUNTIME_CLASS->configureAppSpecific(Ljava/lang/String;Ljava/lang/String;)V
    """.trimIndent())
    owner.methods.remove(method)
    owner.methods.add(cloned)
    return true
}

/** Adds a queued Ads runtime policy beside a bridge injected earlier in this same patching run. */
internal fun BytecodePatchContext.attachQueuedAdsRuntimePolicy(
    target: OverlayAdsRuntimeIntegration.BridgeTarget,
    policy: String,
): Boolean {
    val owner = mutableClassDefByOrNull(target.ownerType) ?: return false
    val method = owner.methods.firstOrNull {
        it.name == target.methodName && it.returnType == target.returnType &&
            it.parameterTypes.map { parameter -> parameter.toString() } == target.parameterTypes
    } ?: return false
    val base = method.implementation?.registerCount ?: return false
    if (method.implementation?.instructions?.any { it.toString().contains("AdsRuntimePolicy;->configure") } == true) return true
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 1)
    cloned.addInstructionsWithLabels(0, """
        const-string v$base, "${StartupHooks.escapeSmali(policy)}"
        invoke-static/range {v$base .. v$base}, Lunipatch/overlaycore/AdsRuntimePolicy;->configure(Ljava/lang/String;)V
    """.trimIndent())
    owner.methods.remove(method)
    owner.methods.add(cloned)
    return true
}

/** Finds a real, non-transient Activity when an explicit target is unavailable. */
internal fun BytecodePatchContext.findOverlayFallbackActivity(
    preferredDescriptor: String? = StartupHooks.resolvedLauncherActivityDescriptor,
): MutableClass? {
    val parents = mutableMapOf<String, String>()
    classDefForEach { classDef -> classDef.superclass?.let { parents[classDef.type] = it } }
    fun isActivity(type: String, seen: MutableSet<String> = mutableSetOf()): Boolean = when {
        type == "Landroid/app/Activity;" -> true
        type == "Ljava/lang/Object;" || !seen.add(type) -> false
        else -> parents[type]?.let { isActivity(it, seen) } == true
    }
    val noHistory = StartupHooks.resolvedNoHistoryActivityDescriptors
    val candidates = mutableListOf<MutableClass>()
    classDefForEach { classDef ->
        if (!isActivity(classDef.type) || classDef.type in noHistory) return@classDefForEach
        val candidate = mutableClassDefBy(classDef)
        if (candidate.methods.any {
                it.name == "onCreate" && it.returnType == "V" &&
                    it.parameterTypes == listOf("Landroid/os/Bundle;") && it.implementation != null
            }) candidates += candidate
    }
    return candidates.firstOrNull { it.type == preferredDescriptor } ?: candidates.firstOrNull()
}
