package unipatches.overlay

import org.junit.Assert.assertNull
import org.junit.Test

class OverlayAdsRuntimeIntegrationTest {
    @Test
    fun queuingANewPolicyDropsAStaleUnconfiguredBridge() {
        val staleContext = Any()
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

        assertNull(OverlayAdsRuntimeIntegration.takeUnconfiguredBridge(staleContext))
        OverlayAdsRuntimeIntegration.markInjected("test cleanup")
    }
}
