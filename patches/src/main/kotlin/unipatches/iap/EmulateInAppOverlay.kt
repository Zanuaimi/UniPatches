package unipatches.iap

import app.morphe.patcher.patch.bytecodePatch
import unipatches.overlay.OverlayInAppRuntimeIntegration
import unipatches.overlay.attachQueuedInAppRuntimePolicy

/** Internal bytecode phase that coordinates the optional overlay bridge. */
internal fun emulateInAppOverlayBridgePatch(optionsProvider: () -> Pair<Boolean, Boolean>) = bytecodePatch(
    name = null,
    description = "Internal InApp Emulation overlay bridge phase.",
    default = false,
) {
    execute {
        val (enabled, initiallyEnabled) = optionsProvider()
        if (!enabled) return@execute
        val policy = "1|inAppEmulation|inAppEmulation|${if (initiallyEnabled) "1" else "0"}"
        OverlayInAppRuntimeIntegration.queue(policy)
        val earlierBridge = OverlayInAppRuntimeIntegration.takeUnconfiguredBridge(this)
        if (earlierBridge != null && attachQueuedInAppRuntimePolicy(earlierBridge, policy)) {
            OverlayInAppRuntimeIntegration.markInjected()
            println("Emulate InApp: attached overlay module policy to the existing Universal Overlay bridge")
        } else {
            println("Emulate InApp: queued overlay module policy for Universal Overlay")
        }
    }
}
