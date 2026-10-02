package unipatches.overlay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import helpers.bytecode.cloneMutable
import helpers.bytecode.numberOfParameterRegisters
import helpers.bytecode.p0Register
import helpers.startup.StartupHooks
import java.util.logging.Logger

internal const val OVERLAY_RUNTIME_CLASS = "Lunipatch/overlaycore/OverlayRuntime;"

/** Forwards Activity results to the immediate superclass when an override omits that call. */
internal fun patchActivityResultForwarding(
    owner: MutableClass,
    logger: Logger,
): Boolean {
    val method = owner.methods.firstOrNull {
        it.name == "onActivityResult" && it.returnType == "V" &&
            it.parameterTypes == listOf("I", "I", "Landroid/content/Intent;") && it.implementation != null
    } ?: run {
        logger.info("Universal Overlay Activity result forwarding skipped: ${owner.type}->onActivityResult(int,int,Intent) not found")
        return false
    }
    val instructions = method.implementation?.instructions ?: return false
    val hasSuperForwarding = instructions.any {
        it.toString().contains("invoke-super") && it.toString().contains("->onActivityResult(")
    }
    val hasDiagnostics = instructions.count {
        it.toString().contains("OverlayRuntime;->logActivityResult")
    } >= 2
    if (hasSuperForwarding && hasDiagnostics) {
        logger.info("Universal Overlay Activity result forwarding and diagnostics already present: ${owner.type}->onActivityResult")
        return false
    }
    val superclass = owner.superclass
    if (superclass.isNullOrBlank() || superclass == "Ljava/lang/Object;") {
        logger.warning("Universal Overlay Activity result forwarding skipped: ${owner.type} has no safe superclass")
        return false
    }
    val returns = instructions.indices.filter { index ->
        instructions[index].toString().trimStart().startsWith("return-void")
    }.reversed()
    if (returns.isEmpty()) {
        logger.warning("Universal Overlay Activity result forwarding skipped: ${owner.type}->onActivityResult has no return-void")
        return false
    }
    return try {
        val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters)
        if (!hasDiagnostics) {
            cloned.addInstructionsWithLabels(
                0,
                "invoke-static/range {p0 .. p2}, $OVERLAY_RUNTIME_CLASS->logActivityResultEntry(Landroid/app/Activity;II)V",
            )
        }
        for (index in returns) {
            val instructionsToAdd = buildString {
                if (!hasSuperForwarding) {
                    append("invoke-super/range {p0 .. p3}, $superclass->onActivityResult(IILandroid/content/Intent;)V\n")
                }
                append("invoke-static/range {p0 .. p2}, $OVERLAY_RUNTIME_CLASS->logActivityResultExit(Landroid/app/Activity;II)V")
            }
            cloned.addInstructionsWithLabels(index + if (!hasDiagnostics) 1 else 0, instructionsToAdd)
        }
        owner.methods.remove(method)
        owner.methods.add(cloned)
        logger.info(
            "Universal Overlay Activity result handling updated: ${owner.type}->onActivityResult " +
                "superForwarding=${!hasSuperForwarding} diagnostics=${!hasDiagnostics}",
        )
        true
    } catch (error: Exception) {
        logger.warning("Universal Overlay Activity result forwarding failed at ${owner.type}: ${error.message}")
        false
    }
}

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
): MutableMethod {
    val temporaryBase = method.implementation?.registerCount
        ?: error("Cannot inject into ${owner.type}->${method.name} without an implementation")
    val temporaryCount = 2 + (if (adsRuntimePolicy != null) 1 else 0)
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
    cloned.addInstructionsWithLabels(index, """
        move-object/from16 v$temporaryBase, v$receiver
        const-string v${temporaryBase + 1}, "${StartupHooks.escapeSmali(config)}"
        $adsPolicy
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
    OverlayPatchRunMarker.publish(context, owner, cloned)
    PermissionGuardOverlayIntegration.attach(context)
    return cloned
}

/** Adds only missing queued policies to an existing verified shared bridge. */
internal fun attachExistingOverlayPolicies(
    owner: MutableClass,
    method: MutableMethod,
    adsRuntimePolicy: String?,
): MutableMethod {
    val missingAds = adsRuntimePolicy != null && !method.hasRuntimePolicy("AdsRuntimePolicy")
    if (!missingAds) return method

    val base = method.implementation?.registerCount
        ?: error("Cannot attach overlay policies to ${owner.type}->${method.name} without an implementation")
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 1)
    var register = base
    val policies = buildString {
        if (missingAds) {
            appendLine("const-string v$register, \"${helpers.startup.StartupHooks.escapeSmali(adsRuntimePolicy)}\"")
            appendLine("invoke-static/range {v$register .. v$register}, Lunipatch/overlaycore/AdsRuntimePolicy;->configure(Ljava/lang/String;)V")
            register++
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
    val implementation = method.implementation ?: return false
    val profileLiteral = StartupHooks.escapeSmali(profileId)
    if (implementation.instructions.any {
            it.toString().contains("configureAppSpecific") && it.toString().contains(profileLiteral)
        }) return true
    val base = implementation.registerCount
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 2)
    cloned.addInstructionsWithLabels(0, """
        const-string v$base, "$profileLiteral"
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
