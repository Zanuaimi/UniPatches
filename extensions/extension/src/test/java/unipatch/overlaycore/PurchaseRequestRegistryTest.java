package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PurchaseRequestRegistryTest {
    @Test public void suppressesDuplicateEntryPointsUntilReleased() {
        PurchaseRequestRegistry registry = new PurchaseRequestRegistry();
        assertTrue(registry.claim(" coins_100 "));
        assertFalse(registry.claim("coins_100"));
        assertTrue(registry.release("coins_100"));
        assertTrue(registry.claim("coins_100"));
    }
}
