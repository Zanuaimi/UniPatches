package unipatch.overlaycore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdsRuntimePolicyTest {
    @Test public void eachModuleBitIsDecoded() {
        AdsRuntimePolicy.configure("1|1|0|0|0|0|0|0|");
        assertTrue(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_BLOCK_ADS));
        assertFalse(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_REWARDS));
        assertFalse(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_HOSTS));

        AdsRuntimePolicy.configure("1|2|0|0|0|0|0|0|");
        assertFalse(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_BLOCK_ADS));
        assertTrue(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_REWARDS));
        assertFalse(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_HOSTS));

        AdsRuntimePolicy.configure("1|3|0|0|0|0|0|0|");
        assertTrue(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_BLOCK_ADS));
        assertTrue(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_REWARDS));
        assertFalse(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_HOSTS));

        AdsRuntimePolicy.configure("1|4|0|0|0|0|0|0|");
        assertFalse(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_BLOCK_ADS));
        assertFalse(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_REWARDS));
        assertTrue(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_HOSTS));

        AdsRuntimePolicy.configure("1|7|0|0|0|0|0|0|");
        assertTrue(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_BLOCK_ADS));
        assertTrue(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_REWARDS));
        assertTrue(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_HOSTS));
    }

    @Test public void malformedPolicyFailsOpen() {
        AdsRuntimePolicy.configure("not-a-policy");
        assertFalse(AdsRuntimePolicy.isIntegrated());
        assertFalse(AdsRuntimePolicy.hasAnyModule());

        AdsRuntimePolicy.configure("1|not-an-int|0|0|0|0|0|0|");
        assertFalse(AdsRuntimePolicy.isIntegrated());
        assertFalse(AdsRuntimePolicy.hasAnyModule());
    }
}
