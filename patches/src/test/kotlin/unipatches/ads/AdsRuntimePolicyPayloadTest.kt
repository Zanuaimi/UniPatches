package unipatches.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsRuntimePolicyPayloadTest {
    @Test
    fun runtimePolicyRequiresAtLeastOneRuntimeModule() {
        assertFalse(isAdsRuntimePolicyActive(true, false, false))
        assertTrue(isAdsRuntimePolicyActive(true, true, false))
        assertTrue(isAdsRuntimePolicyActive(true, false, true))
        assertFalse(isAdsRuntimePolicyActive(false, true, true))
    }

    @Test
    fun moduleMaskContainsOnlySupportedModules() {
        assertEquals(0, buildAdsRuntimeModuleMask(false, true, true))
        assertEquals(1, buildAdsRuntimeModuleMask(true, true, false))
        assertEquals(2, buildAdsRuntimeModuleMask(true, false, true))
        assertEquals(3, buildAdsRuntimeModuleMask(true, true, true))
    }

    @Test
    fun blockedFormatMaskPreservesOrdinaryRewardedAdBlocking() {
        assertEquals(21, buildAdsBlockedFormatsMask(true, false, true, false, true, false))
    }

    @Test
    fun policySerializationUsesVersionTwoAndStableHosts() {
        assertEquals(
            "2|1|16|1|0|a.example,z.example|1",
            serializeAdsRuntimePolicy(1, 16, true, false, listOf("z.example", "a.example")),
        )
        assertEquals(
            "2|3|0|0|0||0|0",
            serializeAdsRuntimePolicy(3, 0, false, false, emptyList(), overlayModuleMask = 0),
        )
        assertEquals(
            "2|2|0|0|1||0",
            serializeAdsRuntimePolicy(2, 0, false, true, emptyList(), hostsAllowedEnabled = false),
        )
    }

    @Test
    fun plannerKeepsBlockAdsAsARealRuntimeModule() {
        val settings = AdsPatchSettings(
            noAdsEnabled = true,
            blockInterstitials = true,
            blockBanners = true,
            blockAppOpen = true,
            blockMRec = true,
            blockRewarded = false,
            blockNative = true,
            hostsEnabled = false,
            wildcardHosts = false,
        )
        val plan = AdsPatchPlanner.resolve(
            settings = settings,
            selection = AdsRuntimeSelection(
                policyEnabled = true,
                noAdsModuleSelected = true,
                hostsModuleSelected = false,
            ),
            sdkCoverage = AdsSdkCoverage(),
        )

        assertEquals(AdsPatchMode.RUNTIME, plan.noAds.mode)
        assertEquals(AdsRuntimeModule.BLOCK_ADS, plan.runtimeModuleMask)
        assertTrue(plan.runtimePolicyEnabled)
    }

    @Test
    fun managedStartupKeepsPolicyButHidesOverlayModules() {
        val settings = AdsPatchSettings(
            noAdsEnabled = true,
            blockInterstitials = true,
            blockBanners = false,
            blockAppOpen = false,
            blockMRec = false,
            blockRewarded = false,
            blockNative = false,
            hostsEnabled = true,
            wildcardHosts = false,
        )
        val plan = AdsPatchPlanner.resolve(
            settings = settings,
            selection = AdsRuntimeSelection(
                policyEnabled = true,
                noAdsModuleSelected = true,
                hostsModuleSelected = true,
                overlayNoAdsModuleSelected = false,
                overlayHostsModuleSelected = false,
            ),
            sdkCoverage = AdsSdkCoverage(),
        )

        assertEquals(3, plan.runtimeModuleMask)
        assertEquals(0, plan.overlayRuntimeModuleMask)
    }

    @Test
    fun runtimeOnlyModeExposesOnlySelectedOverlayModule() {
        val settings = AdsPatchSettings(
            noAdsEnabled = true,
            blockInterstitials = true,
            blockBanners = false,
            blockAppOpen = false,
            blockMRec = false,
            blockRewarded = false,
            blockNative = false,
            hostsEnabled = true,
            wildcardHosts = false,
        )
        val plan = AdsPatchPlanner.resolve(
            settings = settings,
            selection = AdsRuntimeSelection(
                policyEnabled = true,
                noAdsModuleSelected = true,
                hostsModuleSelected = false,
                overlayNoAdsModuleSelected = true,
                overlayHostsModuleSelected = false,
            ),
            sdkCoverage = AdsSdkCoverage(),
        )

        assertEquals(AdsRuntimeModule.BLOCK_ADS, plan.runtimeModuleMask)
        assertEquals(AdsRuntimeModule.BLOCK_ADS, plan.overlayRuntimeModuleMask)
    }

    @Test
    fun settingsAndSelectionExposeOnlyRemainingControls() {
        val settings = AdsPatchSettings(
            noAdsEnabled = true,
            blockInterstitials = true,
            blockBanners = true,
            blockAppOpen = true,
            blockMRec = true,
            blockRewarded = true,
            blockNative = true,
            hostsEnabled = true,
            wildcardHosts = true,
        )
        val selection = AdsRuntimeSelection(
            policyEnabled = true,
            noAdsModuleSelected = true,
            hostsModuleSelected = true,
        )

        assertTrue(settings.noAdsEnabled)
        assertTrue(selection.noAdsModuleSelected)
        assertTrue(selection.hostsModuleSelected)
    }
}
