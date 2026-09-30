package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegacyProxyCleanupPolicyTest {
    @Test public void finishesOnlyActiveUnityProxy() {
        assertTrue(LegacyProxyCleanupPolicy.shouldFinish("org.onepf.openiab.UnityProxyActivity", false, false));
        assertFalse(LegacyProxyCleanupPolicy.shouldFinish("org.onepf.openiab.UnityProxyActivity", true, false));
        assertFalse(LegacyProxyCleanupPolicy.shouldFinish("android.app.Activity", false, false));
    }
}
