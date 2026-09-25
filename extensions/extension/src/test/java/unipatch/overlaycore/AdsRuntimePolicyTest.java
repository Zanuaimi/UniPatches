package unipatch.overlaycore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import unipatch.overlaycore.modules.OverlaySessionState;
import org.junit.Test;

public class AdsRuntimePolicyTest {
    @Test public void blockAdsAndHostsBitsAreDecoded() {
        AdsRuntimePolicy.configure("2|3|21|1|1|ads.example|1");

        assertTrue(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_BLOCK_ADS));
        assertTrue(AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_HOSTS));
        assertTrue(AdsRuntimePolicy.shouldBlockInterstitials());
        assertTrue(AdsRuntimePolicy.shouldBlockRewarded());
        assertTrue(AdsRuntimePolicy.hostsEnabled());
    }

    @Test public void ordinaryRewardedBlockingIsIndependentFromOtherFormats() {
        AdsRuntimePolicy.configure("2|1|16|0|0||0");

        assertFalse(AdsRuntimePolicy.shouldBlockInterstitials());
        assertTrue(AdsRuntimePolicy.shouldBlockRewarded());
        assertFalse(AdsRuntimePolicy.shouldBlockNative());
    }

    @Test public void malformedPolicyFailsOpen() {
        AdsRuntimePolicy.configure("not-a-policy");
        assertFalse(AdsRuntimePolicy.isIntegrated());
        assertFalse(AdsRuntimePolicy.hasAnyModule());

        AdsRuntimePolicy.configure("2|not-an-int|0|0|0||0");
        assertFalse(AdsRuntimePolicy.isIntegrated());
        assertFalse(AdsRuntimePolicy.hasAnyModule());

        AdsRuntimePolicy.configure("2|8|0|0|0||0");
        assertFalse(AdsRuntimePolicy.isIntegrated());

        AdsRuntimePolicy.configure("2|1|64|0|0||0");
        assertFalse(AdsRuntimePolicy.isIntegrated());
    }

    @Test public void invalidPolicyClearsPreviouslyValidRuntimeState() {
        AdsRuntimePolicy.configure("2|3|16|1|1|ads.example|1");
        assertTrue(AdsRuntimePolicy.isIntegrated());

        AdsRuntimePolicy.configure("2|3|invalid|1|1|ads.example|1");
        assertFalse(AdsRuntimePolicy.isIntegrated());
        assertFalse(AdsRuntimePolicy.hasAnyModule());
        assertFalse(AdsRuntimePolicy.hostsEnabled());
    }

    @Test public void configuringPolicyStartsFreshAdsOverlaySessionState() {
        OverlaySessionState.putBooleans("adsRuntimeBlockAds", "settings", new boolean[] {true});

        AdsRuntimePolicy.configure("2|1|0|0|0||0");

        assertFalse(OverlaySessionState.booleans("adsRuntimeBlockAds", "settings", new boolean[] {false})[0]);
    }

    @Test public void hostMasterCapabilityPreventsEnablingFiltering() {
        AdsRuntimePolicy.configure("2|2|0|0|1|ads.example|0");
        AdsRuntimePolicy.setHostsEnabled(true);

        assertTrue(AdsRuntimePolicy.rewriteHost("https://ads.example/path").contains("ads.example"));
    }
}
