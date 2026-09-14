package unipatches.ads

import app.morphe.patcher.Fingerprint
import helpers.ads.*

/** Builds only the runtime-owned No Ads fingerprint plan. It performs no bytecode mutation. */
internal class RuntimeNoAdsCoordinator(
    private val sdkCoverage: AdsSdkCoverage,
    private val blockAdsRuntime: Boolean,
    private val rewardsRuntime: Boolean,
) {
    fun fingerprintCategories(): Map<Fingerprint, String> = buildMap {
        // MAX readiness and wrapper methods are frequently called while mediation is
        // initializing. Keep the entire MAX runtime surface untouched until it has
        // request-scoped, parameter-preserving adapters.
        val maxRuntimeCoverage = sdkCoverage.max && !shouldSkipMaxRuntimeCoverage(
            runtimeBlockAds = blockAdsRuntime,
            runtimeRewards = rewardsRuntime,
            maxCoverageEnabled = sdkCoverage.max,
        )

        fun add(enabled: Boolean, category: String, vararg fingerprints: Fingerprint) {
            if (!enabled) return
            fingerprints.forEach { put(it, category) }
        }

        add(maxRuntimeCoverage, "interstitials", ShowInterstitialFingerprint, MaxInterstitialAdShowAdFingerprint,
            MaxInterstitialAdIsReadyFingerprint,
        )
        add(sdkCoverage.adMob, "interstitials",
            AdMobInterstitialShowFingerprint, AdMobLegacyInterstitialShowFingerprint,
        )
        add(sdkCoverage.unity, "interstitials",
            UnityAdsV3Show2ArgFingerprint, UnityAdsV3ShowOptionsFingerprint,
        )
        add(sdkCoverage.ironSource, "interstitials",
            IronSourceShowDemandOnlyInterstitialFingerprint, IronSourceShowInterstitialFingerprint,
            IronSourceIsInterstitialReadyFingerprint,
            IronSourceShowInterstitialActivityFingerprint, IronSourceShowInterstitialActivityPlacementFingerprint,
            IronSourceShowInterstitialPlacementFingerprint,
        )
        add(sdkCoverage.appLovin, "interstitials",
            AppLovinInterstitialDialogShowFingerprint, AppLovinInterstitialDialogShowAndRenderFingerprint,
        )
        add(sdkCoverage.meta, "interstitials",
            FacebookInterstitialAdShowFingerprint, FacebookInterstitialAdShowConfigFingerprint,
        )
        add(sdkCoverage.pangle, "interstitials", PangleInterstitialShowFingerprint)
        add(sdkCoverage.vungle, "interstitials", VungleInterstitialShowFingerprint)
        add(sdkCoverage.huawei, "interstitials", HuaweiInterstitialAdShowFingerprint)
        add(sdkCoverage.yandex, "interstitials",
            YandexInterstitialAdLoadFingerprint,
            YandexUnityInterstitialWrapperShowFingerprint, MyTargetBaseInterstitialShowFingerprint,
        )
        add(sdkCoverage.startApp, "interstitials", StartAppAdShowFingerprint)
        add(sdkCoverage.moPub, "interstitials", MoPubInterstitialShowFingerprint)
        add(sdkCoverage.chartboost, "interstitials", ChartboostShowInterstitialFingerprint)
        add(sdkCoverage.inMobi, "interstitials", InMobiInterstitialShowFingerprint)
        add(sdkCoverage.mintegral, "interstitials", MintegralInterstitialShowFingerprint)
        add(maxRuntimeCoverage, "appOpen", ShowAppOpenAdFingerprint, MaxAppOpenAdShowAdFingerprint)
        add(maxRuntimeCoverage, "appOpen", MaxAppOpenAdIsReadyFingerprint)
        add(sdkCoverage.adMob, "appOpen", AdMobAppOpenShowFingerprint, AdMobAppOpenLoadFingerprint)
        add(sdkCoverage.pangle, "appOpen", PangleAppOpenShowFingerprint)
        add(sdkCoverage.huawei, "appOpen", HuaweiSplashAdLoadFingerprint)
        add(maxRuntimeCoverage, "banners", ShowBannerFingerprint, StartBannerAutoRefreshFingerprint,
            MaxAdViewStartAutoRefreshFingerprint,
        )
        add(sdkCoverage.huawei, "banners", HuaweiBannerAdLoadFingerprint)
        add(sdkCoverage.appLovin, "banners", AppLovinAdViewLoadNextAdFingerprint)
        add(maxRuntimeCoverage, "mrec", ShowMRecFingerprint, StartMRecAutoRefreshFingerprint)
        add(maxRuntimeCoverage, "rewarded", ShowRewardedAdFingerprint, MaxRewardedAdShowAdFingerprint)
        add(maxRuntimeCoverage, "rewardedAvailability", MaxRewardedAdIsReadyFingerprint, IsRewardedAdReadyFingerprint)
        add(sdkCoverage.adMob, "rewarded",
            AdMobRewardedShowFingerprint, AdMobLegacyRewardedVideoShowFingerprint,
        )
        add(sdkCoverage.unity, "rewarded", UnityRewardedAdShowFingerprint)
        add(sdkCoverage.unity, "rewardedAvailability",
            UnityAdsAdvertisementIsReadyFingerprint, UnityAdsAdvertisementIsReadyPlacementFingerprint,
            UnityAdsSdkIsReadyFingerprint,
        )
        add(sdkCoverage.ironSource, "rewarded",
            IronSourceShowDemandOnlyRewardedVideoFingerprint, IronSourceShowRewardedVideoFingerprint,
            IronSourceShowRewardedVideoActivityFingerprint, IronSourceShowRewardedVideoActivityPlacementFingerprint,
            IronSourceShowRewardedVideoPlacementFingerprint,
        )
        add(sdkCoverage.ironSource, "rewardedAvailability",
            IronSourceIsRewardedVideoAvailableFingerprint, LevelPlayRewardedAdIsReadyFingerprint,
            IronSourceUnityRewardedAdIsReadyFingerprint,
        )
        add(sdkCoverage.appLovin, "rewarded",
            AppLovinIncentivizedShow4ListenerFingerprint, AppLovinIncentivizedShow5ListenerFingerprint,
        )
        add(sdkCoverage.meta, "rewarded",
            FacebookRewardedVideoAdShowFingerprint, FacebookRewardedVideoAdShowConfigFingerprint,
            FacebookRewardedInterstitialShowFingerprint, FacebookRewardedInterstitialShowConfigFingerprint,
        )
        add(sdkCoverage.pangle, "rewarded", PangleRewardedShowFingerprint)
        add(sdkCoverage.vungle, "rewarded", VungleRewardedShowFingerprint)
        add(sdkCoverage.yandex, "rewarded", YandexRewardedAdLoadFingerprint, YandexUnityRewardedWrapperShowFingerprint)
        add(sdkCoverage.huawei, "rewarded", HuaweiRewardAdShowFingerprint)
        add(sdkCoverage.huawei, "rewardedAvailability", HuaweiRewardAdIsLoadedFingerprint)
        add(sdkCoverage.inMobi, "rewarded", InMobiRewardedShowFingerprint)
        add(sdkCoverage.ironSource, "shared", IronSourceLevelPlayFullScreenShowAdFingerprint)
        add(sdkCoverage.vungle, "shared", VungleBaseFullscreenAdLoadFingerprint)
        add(sdkCoverage.pangle, "native", PangleNativeShowFingerprint)
        add(sdkCoverage.adMob, "native", AdMobNativeAdViewFingerprint, AdMobAdLoaderLoadFingerprint)
        add(sdkCoverage.huawei, "native", HuaweiNativeAdLoadFingerprint)
    }.filter { (_, _) ->
        // This coordinator owns only No Ads instrumentation. Rewards runtime coverage is
        // dispatched by RuntimeRewardsCoordinator and must never cause No Ads fingerprints
        // to be queued or statically flushed when the Block Ads module is not selected.
        blockAdsRuntime
    }
}
