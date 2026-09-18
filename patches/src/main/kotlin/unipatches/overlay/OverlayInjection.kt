package unipatches.overlay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import helpers.bytecode.cloneMutable
import helpers.bytecode.numberOfParameterRegisters
import helpers.bytecode.p0Register
import helpers.startup.StartupHooks
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import java.util.logging.Logger

internal const val OVERLAY_RUNTIME_CLASS = "Lunipatch/overlaycore/OverlayRuntime;"

internal data class ResolvedOverlayActivity(
    val owner: MutableClass,
    val onCreate: MutableMethod,
)

internal fun hasRecognizedActivityAncestor(
    start: String,
    superclassOf: (String) -> String?,
): Boolean {
    val seen = mutableSetOf<String>()
    var current: String? = start
    while (current != null && seen.add(current)) {
        if (current == "Landroid/app/Activity;" || current == "Landroid/app/NativeActivity;") {
            return true
        }
        current = superclassOf(current)
    }
    return false
}

internal fun acceptsLauncherOwnership(manifestResolved: Boolean, packageOwned: Boolean): Boolean =
    manifestResolved || packageOwned

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
    return cloned
}

/**
 * Creates an Application-owned onCreate override when the manifest Application only inherits
 * onCreate from an SDK superclass. The override calls the immediate superclass so SDK startup
 * remains intact, while keeping the overlay injection out of the shared SDK class.
 */
internal fun createApplicationOnCreateOverride(
    owner: MutableClass,
): MutableMethod {
    val superclass = owner.superclass
        ?: error("Application ${owner.type} has no superclass")
    require(superclass != "Ljava/lang/Object;") {
        "Application ${owner.type} has no safe superclass"
    }
    val method = ImmutableMethod(
        owner.type,
        "onCreate",
        emptyList(),
        "V",
        AccessFlags.PUBLIC.value,
        emptySet(),
        emptySet(),
        ImmutableMethodImplementation(
            1,
            emptyList(),
            emptyList(),
            emptyList(),
        ),
    ).toMutable()
    method.addInstructionsWithLabels(
        0,
        "invoke-super {p0}, $superclass->onCreate()V\nreturn-void",
    )
    owner.methods.add(method)
    return method
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

/** Validates the manifest-resolved launcher before generic Activity discovery. */
internal fun BytecodePatchContext.resolveOverlayLauncherActivity(
    descriptor: String?,
    logger: Logger,
): ResolvedOverlayActivity? {
    if (descriptor.isNullOrBlank()) {
        logger.info("Universal Overlay launcher validation skipped: no resolved launcher")
        return null
    }

    val classDef = classDefByOrNull(descriptor)
    if (classDef == null) {
        logger.warning("Universal Overlay launcher validation rejected $descriptor: class lookup failed")
        return null
    }
    logger.info("Universal Overlay launcher class lookup succeeded: $descriptor")
    val manifestResolved = descriptor == StartupHooks.resolvedLauncherActivityDescriptor
    logger.info("Universal Overlay launcher manifest-resolved: descriptor=$descriptor value=$manifestResolved")

    val chain = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var current: String? = descriptor
    while (current != null && seen.add(current)) {
        chain += current
        current = if (current == descriptor) classDef.superclass else classDefByOrNull(current)?.superclass
    }
    val hasActivityAncestor = hasRecognizedActivityAncestor(descriptor) { type ->
        when {
            type == descriptor -> classDef.superclass
            type == "Landroid/app/Activity;" || type == "Landroid/app/NativeActivity;" -> null
            else -> classDefByOrNull(type)?.superclass
        }
    }
    logger.info("Universal Overlay launcher superclass chain: ${chain.joinToString(" -> ")}")
    logger.info("Universal Overlay launcher Activity ancestor: descriptor=$descriptor resolved=$hasActivityAncestor")

    val binaryName = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
    val packageName = StartupHooks.resolvedPackageName
    val packageOwned = !packageName.isNullOrBlank() &&
        (binaryName == packageName || binaryName.startsWith("$packageName."))
    val ownershipAccepted = acceptsLauncherOwnership(manifestResolved, packageOwned)
    logger.info(
        "Universal Overlay launcher package ownership: descriptor=$descriptor package=$packageName " +
            "owned=$packageOwned accepted=$ownershipAccepted",
    )
    if (!ownershipAccepted) {
        logger.warning("Universal Overlay launcher rejected $descriptor: class is outside the application package")
        return null
    }

    val noHistory = descriptor in StartupHooks.resolvedNoHistoryActivityDescriptors
    logger.info("Universal Overlay launcher noHistory: descriptor=$descriptor value=$noHistory")
    if (noHistory) {
        logger.warning("Universal Overlay launcher rejected $descriptor: activity is marked noHistory")
        return null
    }
    if (!hasActivityAncestor) {
        logger.warning(
            "Universal Overlay launcher rejected $descriptor: no recognized Activity ancestor was resolved",
        )
        return null
    }

    val onCreateExists = classDef.methods.any {
        it.name == "onCreate" && it.returnType == "V" &&
            it.parameterTypes == listOf("Landroid/os/Bundle;") && it.implementation != null
    }
    logger.info("Universal Overlay launcher onCreate(Bundle) lookup: descriptor=$descriptor found=$onCreateExists")
    if (!onCreateExists) {
        logger.warning("Universal Overlay launcher rejected $descriptor: onCreate(Bundle) was not found")
        return null
    }

    val owner = try { mutableClassDefBy(classDef) } catch (error: Exception) {
        logger.warning("Universal Overlay launcher rejected $descriptor: mutable class lookup failed: ${error.message}")
        return null
    }
    logger.info("Universal Overlay launcher mutability: descriptor=$descriptor mutable=true")
    val onCreate = owner.methods.firstOrNull {
        it.name == "onCreate" && it.returnType == "V" &&
            it.parameterTypes == listOf("Landroid/os/Bundle;") && it.implementation != null
    }
    if (onCreate == null) {
        logger.warning("Universal Overlay launcher rejected $descriptor: mutable onCreate(Bundle) lookup failed")
        return null
    }
    logger.info("Universal Overlay launcher validation succeeded: $descriptor->onCreate(Bundle)")
    return ResolvedOverlayActivity(owner, onCreate)
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
