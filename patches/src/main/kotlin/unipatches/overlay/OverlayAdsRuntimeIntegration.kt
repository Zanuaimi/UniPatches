package unipatches.overlay

/**
 * Patch-process handoff for optional Control App Ads runtime controls.
 *
 * Control App Ads executes before overlay patches, so it queues the policy here. The overlay that
 * actually installs the shared runtime consumes it and emits the configure call adjacent to its
 * own bridge. This intentionally avoids guessing an Application or launcher Activity separately.
 */
internal object OverlayAdsRuntimeIntegration {
    internal data class BridgeTarget(
        val context: Any,
        val ownerType: String,
        val methodName: String,
        val returnType: String,
        val parameterTypes: List<String>,
    )

    private var pendingPolicy: String? = null
    private var injectedBy: String? = null
    private var unconfiguredBridge: BridgeTarget? = null

    fun queue(policy: String) {
        pendingPolicy = policy
        injectedBy = null
    }

    fun pendingPolicy(): String? = pendingPolicy

    fun markInjected(overlayName: String) {
        injectedBy = overlayName
        pendingPolicy = null
        unconfiguredBridge = null
    }

    fun injectedBy(): String? = injectedBy

    /** Records a bridge that can receive a policy if Control App Ads executes later. */
    fun recordUnconfiguredBridge(target: BridgeTarget) {
        unconfiguredBridge = target
    }

    /** Returns only a bridge from this patching context, avoiding stale cross-build state. */
    fun takeUnconfiguredBridge(context: Any): BridgeTarget? {
        val target = unconfiguredBridge ?: return null
        if (target.context !== context) return null
        unconfiguredBridge = null
        return target
    }
}
