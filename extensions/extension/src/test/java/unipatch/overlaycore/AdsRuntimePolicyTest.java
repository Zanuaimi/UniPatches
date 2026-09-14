package unipatch.overlaycore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import unipatch.overlaycore.modules.OverlaySessionState;
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

        AdsRuntimePolicy.configure("1|8|0|0|0|0|0|0|");
        assertFalse(AdsRuntimePolicy.isIntegrated());
        AdsRuntimePolicy.configure("1|1|64|0|0|0|0|0|");
        assertFalse(AdsRuntimePolicy.isIntegrated());
        AdsRuntimePolicy.configure("1|1|0|2|0|0|0|0|");
        assertFalse(AdsRuntimePolicy.isIntegrated());
    }

    @Test public void decodedInitialStatesAreOwnedByTheirRuntimeModules() {
        AdsRuntimePolicy.configure("1|1|21|1|0|0|1|1|ads.example");
        assertTrue(AdsRuntimePolicy.shouldBlockInterstitials());
        assertFalse(AdsRuntimePolicy.shouldBlockBanners());
        assertTrue(AdsRuntimePolicy.shouldBlockAppOpen());
        assertFalse(AdsRuntimePolicy.shouldBlockMrec());
        assertTrue(AdsRuntimePolicy.shouldBlockRewardedFormat());
        assertTrue(AdsRuntimePolicy.hostsEnabled());
        assertFalse(AdsRuntimePolicy.shouldSkipRewarded());

        AdsRuntimePolicy.configure("1|2|0|1|1|1|0|0|");
        assertFalse(AdsRuntimePolicy.shouldBlockInterstitials());
        assertTrue(AdsRuntimePolicy.shouldSkipRewarded());
        assertTrue(AdsRuntimePolicy.shouldGrantReward());
        assertTrue(AdsRuntimePolicy.shouldFakeRewardAvailability());
        assertFalse(AdsRuntimePolicy.hostsEnabled());
    }

    @Test public void invalidPolicyClearsPreviouslyValidRuntimeState() {
        AdsRuntimePolicy.configure("1|7|16|1|1|1|1|1|ads.example");
        assertTrue(AdsRuntimePolicy.isIntegrated());
        assertTrue(AdsRuntimePolicy.hostsEnabled());

        AdsRuntimePolicy.configure("1|7|invalid|1|1|1|1|1|ads.example");
        assertFalse(AdsRuntimePolicy.isIntegrated());
        assertFalse(AdsRuntimePolicy.hasAnyModule());
        assertFalse(AdsRuntimePolicy.hostsEnabled());
        assertFalse(AdsRuntimePolicy.shouldSkipRewarded());
    }

    @Test public void configuringPolicyStartsFreshAdsOverlaySessionState() {
        OverlaySessionState.putBooleans("adsRuntimeBlockAds", "settings", new boolean[] {true});
        OverlaySessionState.putBooleans("adsRuntimeRewards", "settings", new boolean[] {true});

        AdsRuntimePolicy.configure("1|1|0|0|0|0|0|0|");

        assertFalse(OverlaySessionState.booleans("adsRuntimeBlockAds", "settings", new boolean[] {false})[0]);
        assertFalse(OverlaySessionState.booleans("adsRuntimeRewards", "settings", new boolean[] {false})[0]);
    }

    @Test public void hostMasterCapabilityPreventsEnablingFiltering() {
        AdsRuntimePolicy.configure("1|4|0|0|0|0|0|1||0");
        AdsRuntimePolicy.setHostsEnabled(true);
        org.junit.Assert.assertEquals("https://ads.example/path",
                AdsRuntimePolicy.rewriteHost("https://ads.example/path"));
    }

    @Test public void legacyNineFieldPolicyRemainsValid() {
        AdsRuntimePolicy.configure("1|4|0|0|0|0|1|1|ads.example");
        AdsRuntimePolicy.setHostsEnabled(true);
        org.junit.Assert.assertEquals("https://0.0.0.0/path",
                AdsRuntimePolicy.rewriteHost("https://ads.example/path"));
    }
}
