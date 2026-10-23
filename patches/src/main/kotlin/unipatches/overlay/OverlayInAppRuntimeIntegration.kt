package unipatches.overlay

/** Patch-process coordination for the optional InApp Emulation overlay module. */
internal object OverlayInAppRuntimeIntegration {
    internal data class BridgeTarget(
        val context: Any,
        val ownerType: String,
        val methodName: String,
        val returnType: String,
        val parameterTypes: List<String>,
    )

    private var pendingPolicy: String? = null
    private var unconfiguredBridge: BridgeTarget? = null

    fun queue(policy: String) {
        pendingPolicy = policy
        unconfiguredBridge = null
    }

    fun pendingPolicy(): String? = pendingPolicy
    fun markInjected() { pendingPolicy = null; unconfiguredBridge = null }
    fun recordUnconfiguredBridge(target: BridgeTarget) { unconfiguredBridge = target }

    fun takeUnconfiguredBridge(context: Any): BridgeTarget? {
        val target = unconfiguredBridge ?: return null
        if (target.context !== context) return null
        unconfiguredBridge = null
        return target
    }
}
