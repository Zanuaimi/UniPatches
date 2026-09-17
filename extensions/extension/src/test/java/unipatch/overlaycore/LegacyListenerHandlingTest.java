package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Contracts used by UnityPlugin/OpenIAB null-listener and duplicate paths. */
public class LegacyListenerHandlingTest {
    @Test
    public void missingListenerIsNotInvoked() {
        CallbackDeliveryResult result = CallbackInvoker.invoke(false, () -> {
            throw new AssertionError("missing listener must not be called");
        });
        assertFalse(result.located);
        assertFalse(result.started);
    }

    @Test
    public void duplicateLegacyRequestIsConsumedOnce() {
        PurchaseRequestRegistry registry = new PurchaseRequestRegistry();
        assertTrue(registry.claim("ruby_pack"));
        assertFalse(registry.claim(" ruby_pack "));
        assertTrue(registry.release("ruby_pack"));
    }

    @Test
    public void proxyCleanupOnlyFinishesAnActiveUnityProxy() {
        assertTrue(LegacyProxyCleanupPolicy.shouldFinish(
                "org.onepf.openiab.UnityProxyActivity", false, false));
        assertFalse(LegacyProxyCleanupPolicy.shouldFinish(
                "org.onepf.openiab.UnityProxyActivity", true, false));
    }
}
