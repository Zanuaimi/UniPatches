package unipatches.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class AdsRuntimePolicyPayloadTest {
    @Test
    fun moduleMasksUseExpectedBits() {
        assertEquals(1, buildAdsRuntimeModuleMask(true, true, false, false))
        assertEquals(2, buildAdsRuntimeModuleMask(true, false, true, false))
        assertEquals(3, buildAdsRuntimeModuleMask(true, true, true, false))
        assertEquals(4, buildAdsRuntimeModuleMask(true, false, false, true))
        assertEquals(7, buildAdsRuntimeModuleMask(true, true, true, true))
    }

    @Test
    fun disabledRuntimeControlsProduceNoModuleMask() {
        assertEquals(0, buildAdsRuntimeModuleMask(false, true, true, true))
        assertEquals(0, buildAdsRuntimeModuleMask(true, false, false, false))
    }

    @Test
    fun blockedFormatMaskUsesIndividualCheckboxesWithoutPresetOverrides() {
        assertEquals(0, buildAdsBlockedFormatsMask(false, false, false, false, false, false))
        assertEquals(16, buildAdsBlockedFormatsMask(false, false, false, false, true, false))
        assertEquals(47, buildAdsBlockedFormatsMask(true, true, true, true, false, true))
        assertEquals(63, buildAdsBlockedFormatsMask(true, true, true, true, true, true))
    }

    @Test
    fun runtimePolicyRequiresAtLeastOneModule() {
        assertEquals(false, isAdsRuntimePolicyActive(true, false, false, false))
        assertEquals(true, isAdsRuntimePolicyActive(true, false, true, false))
        assertEquals(false, isAdsRuntimePolicyActive(false, true, true, true))
    }

    @Test
    fun genericReadinessFallbackDoesNotMatchBillingClasses() {
        assertEquals(false, isAdsDefiningClass(
            "Lcom/unity3d/services/store/gpbl/bridges/billingclient/common/BillingClientBridgeCommon;",
            null,
        ))
        assertEquals(true, isAdsDefiningClass("Lcom/unity3d/ads/Advertisement;", null))
        assertEquals(false, isAdsDefiningClass(
            "Lcom/google/android/exoplayer2/source/ads/ServerSideAdInsertionMediaSource\$SampleStreamImpl;",
            null,
        ))
        assertEquals(false, hasKnownAdsReference(
            "Landroidx/media3/exoplayer/source/ads/ServerSideAdInsertionMediaSource\$SampleStreamImpl;->isReady()Z",
        ))
        assertEquals(false, isKnownAdsClass(
            "Lcom/mbridge/msdk/playercommon/exoplayer2/source/SingleSampleMediaPeriod\$SampleStreamImpl;",
        ))
        assertEquals(false, hasKnownAdsReference(
            "Lcom/mbridge/msdk/playercommon/exoplayer2/source/SingleSampleMediaPeriod\$SampleStreamImpl;->isReady()Z",
        ))
        assertEquals(false, isKnownAdsClass(
            "Lcom/unity3d/services/store/gpbl/bridges/billingclient/common/BillingClientBridgeCommon;",
        ))
        assertEquals(true, hasKnownAdsReference(
            "Lcom/google/android/gms/ads/AdView;->isLoading()Z",
        ))
    }

    @Test
    fun targetedFallbackRequiresTheExactDefiningClass() {
        assertEquals(true, isAdsFallbackCandidate(
            classType = "Lcom/applovin/mediation/ads/MaxRewardedAd;",
            target = "Lcom/applovin/mediation/ads/MaxRewardedAd;",
            hasAdsReference = false,
        ))
        assertEquals(false, isAdsFallbackCandidate(
            classType = "Lcom/applovin/mediation/ads/MaxInterstitialAd;",
            target = "Lcom/applovin/mediation/ads/MaxRewardedAd;",
            hasAdsReference = true,
        ))
        assertEquals(true, isAdsFallbackCandidate(
            classType = "Lcom/custom/ObfuscatedAd;",
            target = null,
            hasAdsReference = true,
        ))
    }

    @Test
    fun disabledRewardsAddonDoesNotEnableRuntimeRewards() {
        assertEquals(false, isAdsRuntimeRewardsEnabled(true, false))
        assertEquals(false, isAdsRuntimeRewardsEnabled(false, true))
        assertEquals(true, isAdsRuntimeRewardsEnabled(true, true))
        assertEquals(false, isAdsRuntimeModuleEnabled(true, false, true))
        assertEquals(true, isAdsRuntimeModuleEnabled(true, true, true))
    }

    @Test
    fun sdkCoverageKeepsOtherSdkSelectionsIndependent() {
        val coverage = AdsSdkCoverage(
            startApp = false,
            moPub = true,
            chartboost = false,
            inMobi = true,
            mintegral = false,
        )

        assertEquals(false, coverage.startApp)
        assertEquals(true, coverage.moPub)
        assertEquals(false, coverage.chartboost)
        assertEquals(true, coverage.inMobi)
        assertEquals(false, coverage.mintegral)
    }

    @Test
    fun rewardsAddonSerializesModuleBitTwo() {
        assertEquals("1|2|0|0|1|1|0|0|", serializeAdsRuntimePolicy(2, 0, false, true, true, false, false, emptyList()))
    }

    @Test
    fun hostListSerializationIsStableAndSorted() {
        assertEquals("1|7|16|1|1|1|1|1|a.example,z.example", serializeAdsRuntimePolicy(7, 16, true, true, true, true, true, listOf("z.example", "a.example")))
    }
}
