package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import java.util.logging.Logger

/** Owns the remaining exact No Ads fingerprints that do not have a dedicated SDK package. */
internal class OtherNoAdsAdapter(
    private val context: BytecodePatchContext,
    private val logger: Logger,
    private val coverage: AdsSdkCoverage,
) : ContextNoAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = DetectionResult("Other supported SDKs", false)

    override fun applyResolved(plan: NoAdsSdkPlan): PatchResult {
        var patched = 0
        if (coverage.yandex) patched += applyYandex(plan)
        if (coverage.huawei) patched += applyHuawei(plan)
        if (coverage.appLovin) patched += applyLegacyAppLovin(plan)
        if (coverage.vungle) patched += applyVungle(plan)
        if (coverage.meta) patched += applyMeta(plan)
        if (coverage.pangle) patched += applyPangle(plan)
        if (coverage.startApp && plan.interstitials) patched += context.patchVoid(StartAppAdShowFingerprint)
        if (coverage.moPub && plan.interstitials) patched += context.patchVoid(MoPubInterstitialShowFingerprint)
        if (coverage.chartboost && plan.interstitials) patched += context.patchVoid(ChartboostShowInterstitialFingerprint)
        if (coverage.inMobi && plan.interstitials) patched += context.patchVoid(InMobiInterstitialShowFingerprint)
        if (coverage.mintegral && plan.interstitials) patched += context.patchVoid(MintegralInterstitialShowFingerprint)
        if (patched > 0) logger.info("No Ads: other SDK adapters patched $patched method(s)")
        return PatchResult(patched = patched)
    }

    override fun applyRuntimeResolved(plan: NoAdsSdkPlan): PatchResult = applyResolved(plan)

    private fun applyYandex(plan: NoAdsSdkPlan): Int {
        var patched = 0
        if (plan.interstitials || plan.rewarded) {
            val checks = buildString {
                if (plan.rewarded) {
                    appendLine("instance-of v0, p0, Lcom/my/target/ads/RewardedAd;")
                    appendLine("if-nez v0, :morphe_no_ads_mytarget_block")
                }
                if (plan.interstitials) {
                    appendLine("instance-of v0, p0, Lcom/my/target/ads/InterstitialAd;")
                    appendLine("if-nez v0, :morphe_no_ads_mytarget_block")
                }
                appendLine("goto :morphe_no_ads_mytarget_continue")
                appendLine(":morphe_no_ads_mytarget_block")
                appendLine("return-void")
                appendLine(":morphe_no_ads_mytarget_continue")
            }
            patched += context.injectOrSkip(MyTargetBaseInterstitialShowFingerprint, checks.trim())
        }
        if (plan.rewarded) patched += context.returnVoid(YandexUnityRewardedWrapperShowFingerprint)
        if (plan.interstitials) patched += context.returnVoid(YandexUnityInterstitialWrapperShowFingerprint)
        if (plan.interstitials) patched += context.patchVoid(YandexInterstitialAdLoadFingerprint)
        if (plan.rewarded) patched += context.patchVoid(YandexRewardedAdLoadFingerprint)
        return patched
    }

    private fun applyHuawei(plan: NoAdsSdkPlan): Int {
        var patched = 0
        if (plan.interstitials) patched += context.patchVoid(HuaweiInterstitialAdShowFingerprint)
        if (plan.banners) patched += context.patchVoid(HuaweiBannerAdLoadFingerprint)
        if (plan.appOpen) patched += context.patchVoid(HuaweiSplashAdLoadFingerprint)
        if (plan.rewarded) {
            patched += context.patchReturnFalse(HuaweiRewardAdIsLoadedFingerprint)
            patched += context.patchVoid(HuaweiRewardAdShowFingerprint)
        }
        if (plan.native) patched += context.patchVoid(HuaweiNativeAdLoadFingerprint)
        return patched
    }

    private fun applyLegacyAppLovin(plan: NoAdsSdkPlan): Int {
        var patched = 0
        if (plan.interstitials) {
            patched += context.patchVoid(AppLovinInterstitialDialogShowFingerprint)
            patched += context.patchVoid(AppLovinInterstitialDialogShowAndRenderFingerprint)
        }
        if (plan.banners) patched += context.patchVoid(AppLovinAdViewLoadNextAdFingerprint)
        if (plan.rewarded) {
            patched += context.patchVoid(AppLovinIncentivizedShow4ListenerFingerprint)
            patched += context.patchVoid(AppLovinIncentivizedShow5ListenerFingerprint)
        }
        return patched
    }

    private fun applyVungle(plan: NoAdsSdkPlan): Int {
        var patched = 0
        if (plan.interstitials || plan.rewarded) patched += context.patchVoid(VungleBaseFullscreenAdLoadFingerprint)
        if (plan.interstitials) patched += context.patchVoid(VungleInterstitialShowFingerprint)
        if (plan.rewarded) patched += context.patchVoid(VungleRewardedShowFingerprint)
        return patched
    }

    private fun applyMeta(plan: NoAdsSdkPlan): Int {
        var patched = 0
        if (plan.interstitials) {
            patched += context.patchReturnFalse(FacebookInterstitialAdShowFingerprint)
            patched += context.patchReturnFalse(FacebookInterstitialAdShowConfigFingerprint)
        }
        if (plan.rewarded) {
            patched += context.patchReturnFalse(FacebookRewardedVideoAdShowFingerprint)
            patched += context.patchReturnFalse(FacebookRewardedVideoAdShowConfigFingerprint)
            patched += context.patchReturnFalse(FacebookRewardedInterstitialShowFingerprint)
            patched += context.patchReturnFalse(FacebookRewardedInterstitialShowConfigFingerprint)
        }
        return patched
    }

    private fun applyPangle(plan: NoAdsSdkPlan): Int {
        var patched = 0
        if (plan.interstitials) patched += context.patchVoid(PangleInterstitialShowFingerprint)
        if (plan.appOpen) patched += context.patchVoid(PangleAppOpenShowFingerprint)
        if (plan.rewarded) patched += context.patchVoid(PangleRewardedShowFingerprint)
        if (plan.native) patched += context.patchVoid(PangleNativeShowFingerprint)
        return patched
    }
}
