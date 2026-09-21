package unipatches.overlay

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayAdsRuntimeIntegrationTest {
    @Test
    fun queuingPolicyPreservesBridgeForTheMatchingContextOnly() {
        val staleContext = Any()
        val currentContext = Any()
        OverlayAdsRuntimeIntegration.recordUnconfiguredBridge(
            OverlayAdsRuntimeIntegration.BridgeTarget(
                context = staleContext,
                ownerType = "Lcom/example/StaleApplication;",
                methodName = "onCreate",
                returnType = "V",
                parameterTypes = listOf("Landroid/os/Bundle;"),
            ),
        )

        OverlayAdsRuntimeIntegration.queue("1|1|0|0|0|0|0|0|")

        assertNull(OverlayAdsRuntimeIntegration.takeUnconfiguredBridge(currentContext))
        assertNotNull(OverlayAdsRuntimeIntegration.takeUnconfiguredBridge(staleContext))
        OverlayAdsRuntimeIntegration.markInjected("test cleanup")
    }
}
