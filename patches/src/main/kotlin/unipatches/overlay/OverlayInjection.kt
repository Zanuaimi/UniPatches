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

private fun MutableMethod.hasRuntimePolicy(policyClass: String): Boolean =
    implementation?.instructions?.any { instruction ->
        instruction.toString().contains("$policyClass;->configure")
    } == true

/** Inserts the shared runtime bridge and optional policies at one safe entry point. */
internal fun injectOverlayBridge(
    context: BytecodePatchContext,
    owner: MutableClass,
    method: MutableMethod,
    config: String,
    application: Boolean,
    adsRuntimePolicy: String?,
    inAppRuntimePolicy: String?,
): MutableMethod {
    val temporaryBase = method.implementation?.registerCount
        ?: error("Cannot inject into ${owner.type}->${method.name} without an implementation")
    val temporaryCount = 2 + (if (adsRuntimePolicy != null) 1 else 0) + (if (inAppRuntimePolicy != null) 1 else 0)
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + temporaryCount)
    val receiver = cloned.p0Register
    val type = if (application) "Landroid/app/Application;" else "Landroid/app/Activity;"
    val instructions = cloned.implementation?.instructions
    val superIndex = instructions?.indexOfFirst {
        val text = it.toString()
        text.contains("invoke-super") && text.contains("->onCreate(")
    } ?: -1
    // Application.onCreate must complete framework and SDK initialization before policy-aware
    // hooks become active. Activity injection already follows its superclass call for the same
    // reason. If no superclass call can be found, append at the end as a safe fallback.
    val index = if (superIndex >= 0) superIndex + 1 else maxOf(0, (instructions?.size ?: 0) - 1)
    val adsPolicy = adsRuntimePolicy?.let { policy ->
        """
        const-string v${temporaryBase + 2}, "${StartupHooks.escapeSmali(policy)}"
        invoke-static/range {v${temporaryBase + 2} .. v${temporaryBase + 2}}, Lunipatch/overlaycore/AdsRuntimePolicy;->configure(Ljava/lang/String;)V
        """.trimIndent()
    }.orEmpty()
    val inAppPolicy = inAppRuntimePolicy?.let { policy ->
        val register = temporaryBase + 2 + if (adsRuntimePolicy != null) 1 else 0
        """
        const-string v$register, "${StartupHooks.escapeSmali(policy)}"
        invoke-static/range {v$register .. v$register}, Lunipatch/overlaycore/InAppRuntimePolicy;->configure(Ljava/lang/String;)V
        """.trimIndent()
    }.orEmpty()
    cloned.addInstructionsWithLabels(index, """
        move-object/from16 v$temporaryBase, v$receiver
        const-string v${temporaryBase + 1}, "${StartupHooks.escapeSmali(config)}"
        $adsPolicy
        $inAppPolicy
        invoke-static/range {v$temporaryBase .. v${temporaryBase + 1}}, $OVERLAY_RUNTIME_CLASS->${if (application) "install" else "installActivity"}(${type}Ljava/lang/String;)V
    """.trimIndent())
    owner.methods.remove(method)
    owner.methods.add(cloned)
    if (application) StartupHooks.overlayApplicationBridgeOwner = owner.type
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
    if (inAppRuntimePolicy == null) {
        OverlayInAppRuntimeIntegration.recordUnconfiguredBridge(
            OverlayInAppRuntimeIntegration.BridgeTarget(
                context = context,
                ownerType = owner.type,
                methodName = cloned.name,
                returnType = cloned.returnType,
                parameterTypes = cloned.parameterTypes.map { it.toString() },
            ),
        )
    }
    OverlayPatchRunMarker.publish(context, owner, cloned)
    return cloned
}

/** Adds only missing queued policies to an existing verified shared bridge. */
internal fun attachExistingOverlayPolicies(
    owner: MutableClass,
    method: MutableMethod,
    adsRuntimePolicy: String?,
    inAppRuntimePolicy: String?,
): MutableMethod {
    val missingAds = adsRuntimePolicy != null && !method.hasRuntimePolicy("AdsRuntimePolicy")
    val missingInApp = inAppRuntimePolicy != null && !method.hasRuntimePolicy("InAppRuntimePolicy")
    if (!missingAds && !missingInApp) return method

    val base = method.implementation?.registerCount
        ?: error("Cannot attach overlay policies to ${owner.type}->${method.name} without an implementation")
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters +
        (if (missingAds) 1 else 0) + (if (missingInApp) 1 else 0))
    var register = base
    val policies = buildString {
        if (missingAds) {
            appendLine("const-string v$register, \"${helpers.startup.StartupHooks.escapeSmali(adsRuntimePolicy)}\"")
            appendLine("invoke-static/range {v$register .. v$register}, Lunipatch/overlaycore/AdsRuntimePolicy;->configure(Ljava/lang/String;)V")
            register++
        }
        if (missingInApp) {
            appendLine("const-string v$register, \"${helpers.startup.StartupHooks.escapeSmali(inAppRuntimePolicy)}\"")
            appendLine("invoke-static/range {v$register .. v$register}, Lunipatch/overlaycore/InAppRuntimePolicy;->configure(Ljava/lang/String;)V")
        }
    }.trim()
    cloned.addInstructionsWithLabels(0, policies)
    owner.methods.remove(method)
    owner.methods.add(cloned)
    return cloned
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

/** Adds a queued InApp runtime policy beside a bridge injected earlier in this patch run. */
internal fun BytecodePatchContext.attachQueuedInAppRuntimePolicy(
    target: OverlayInAppRuntimeIntegration.BridgeTarget,
    policy: String,
): Boolean {
    val owner = mutableClassDefByOrNull(target.ownerType) ?: return false
    val method = owner.methods.firstOrNull {
        it.name == target.methodName && it.returnType == target.returnType &&
            it.parameterTypes.map { parameter -> parameter.toString() } == target.parameterTypes
    } ?: return false
    val base = method.implementation?.registerCount ?: return false
    if (method.implementation?.instructions?.any { it.toString().contains("InAppRuntimePolicy;->configure") } == true) return true
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 1)
    cloned.addInstructionsWithLabels(0, """
        const-string v$base, "${StartupHooks.escapeSmali(policy)}"
        invoke-static/range {v$base .. v$base}, Lunipatch/overlaycore/InAppRuntimePolicy;->configure(Ljava/lang/String;)V
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
    fun isPackagedFrameworkActivity(type: String): Boolean {
        val frameworkNamespace = type.startsWith("Landroid/app/") ||
            type.startsWith("Landroid/support/") || type.startsWith("Landroidx/")
        return frameworkNamespace && type.endsWith("Activity;")
    }
    fun isActivity(type: String, seen: MutableSet<String> = mutableSetOf()): Boolean = when {
        type == "Landroid/app/Activity;" || isPackagedFrameworkActivity(type) -> true
        type == "Ljava/lang/Object;" || !seen.add(type) -> false
        else -> parents[type]?.let { isActivity(it, seen) } == true
    }
    val noHistory = StartupHooks.resolvedNoHistoryActivityDescriptors
    val packageName = StartupHooks.resolvedPackageName
    val candidates = mutableListOf<MutableClass>()
    classDefForEach { classDef ->
        if (!isActivity(classDef.type) || classDef.type in noHistory) return@classDefForEach
        // A missing launcher resolution must not select a support-library,
        // AndroidX, Google, or other SDK Activity by class-file order. Only
        // an application-owned Activity is a safe generic fallback.
        val binaryName = classDef.type.removePrefix("L").removeSuffix(";").replace('/', '.')
        if (packageName.isNullOrBlank() ||
            !(binaryName == packageName || binaryName.startsWith("$packageName."))
        ) return@classDefForEach
        val candidate = mutableClassDefBy(classDef)
        if (candidate.methods.any {
                it.name == "onCreate" && it.returnType == "V" &&
                    it.parameterTypes == listOf("Landroid/os/Bundle;") && it.implementation != null
            }) candidates += candidate
    }
    // The manifest has already identified this component as an Activity. Prefer it even
    // when its final framework superclass is not present in the APK's class pool.
    val preferred = preferredDescriptor?.let { descriptor ->
        mutableClassDefByOrNull(descriptor)?.takeIf { candidate ->
            candidate.type == descriptor &&
                candidate.type.removePrefix("L").removeSuffix(";").replace('/', '.')
                    .let { binaryName ->
                        !packageName.isNullOrBlank() &&
                            (binaryName == packageName || binaryName.startsWith("$packageName."))
                    } &&
                candidate.type !in noHistory &&
                candidate.methods.any {
                    it.name == "onCreate" && it.returnType == "V" &&
                        it.parameterTypes == listOf("Landroid/os/Bundle;") && it.implementation != null
                }
        }
    }
    return preferred ?: candidates.firstOrNull { it.type == preferredDescriptor }
        ?: candidates.firstOrNull()
}
