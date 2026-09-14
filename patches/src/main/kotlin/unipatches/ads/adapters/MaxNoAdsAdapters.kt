package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import helpers.bytecode.fireHiddenCallbacks
import java.util.logging.Logger

/** Static MAX format adapters. Runtime MAX is intentionally skipped until hooks are request-scoped. */
internal class MaxInterstitialNoAdsAdapter(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) : ContextNoAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(noAdsContext) {
        DetectionResult("MAX interstitial", ShowInterstitialFingerprint.methodOrNull != null)
    }
    override fun applyResolved(plan: NoAdsSdkPlan): PatchResult {
        if (!plan.interstitials) return PatchResult()
        var patched = 0
        patched += context.patchVoid(ShowInterstitialFingerprint)
        patched += context.patchWith(
            MaxInterstitialAdShowAdFingerprint,
            fireHiddenCallbacks("Lcom/applovin/mediation/ads/MaxInterstitialAd;"),
        )
        logger.info("No Ads: MAX interstitial adapter patched $patched method(s)")
        return PatchResult(patched = patched)
    }

    override fun applyRuntimeResolved(plan: NoAdsSdkPlan): PatchResult = PatchResult(skipped = 1)

}

internal class MaxAppOpenNoAdsAdapter(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) : ContextNoAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(noAdsContext) {
        DetectionResult("MAX app-open", ShowAppOpenAdFingerprint.methodOrNull != null)
    }
    override fun applyResolved(plan: NoAdsSdkPlan): PatchResult {
        if (!plan.appOpen) return PatchResult()
        var patched = 0
        patched += context.patchVoid(ShowAppOpenAdFingerprint)
        patched += context.patchWith(
            MaxAppOpenAdShowAdFingerprint,
            fireHiddenCallbacks("Lcom/applovin/mediation/ads/MaxAppOpenAd;"),
        )
        logger.info("No Ads: MAX app-open adapter patched $patched method(s)")
        return PatchResult(patched = patched)
    }

    override fun applyRuntimeResolved(plan: NoAdsSdkPlan): PatchResult = PatchResult(skipped = 1)

}

internal class MaxBannerNoAdsAdapter(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) : ContextNoAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(noAdsContext) {
        DetectionResult("MAX banner/MREC", MaxAdViewStartAutoRefreshFingerprint.methodOrNull != null)
    }
    override fun applyResolved(plan: NoAdsSdkPlan): PatchResult {
        if (!(plan.banners || plan.mrec || plan.native)) return PatchResult()
        var patched = 0
        if (plan.banners) {
            patched += context.patchVoid(ShowBannerFingerprint)
            patched += context.patchVoid(StartBannerAutoRefreshFingerprint)
        }
        if (plan.mrec) {
            patched += context.patchVoid(ShowMRecFingerprint)
            patched += context.patchVoid(StartMRecAutoRefreshFingerprint)
        }
        patched += context.patchVoid(MaxAdViewStartAutoRefreshFingerprint)
        logger.info("No Ads: MAX banner/MREC adapter patched $patched method(s)")
        return PatchResult(patched = patched)
    }

    override fun applyRuntimeResolved(plan: NoAdsSdkPlan): PatchResult = PatchResult(skipped = 1)

}

internal class MaxRewardedNoAdsAdapter(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) : ContextNoAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(noAdsContext) {
        DetectionResult("MAX rewarded", MaxRewardedAdShowAdFingerprint.methodOrNull != null)
    }
    override fun applyResolved(plan: NoAdsSdkPlan): PatchResult {
        if (!plan.rewarded) return PatchResult()
        var patched = 0
        patched += context.patchReturnFalse(IsRewardedAdReadyFingerprint)
        patched += context.patchVoid(ShowRewardedAdFingerprint)
        patched += context.patchReturnFalse(MaxRewardedAdIsReadyFingerprint)
        patched += context.patchWith(
            MaxRewardedAdShowAdFingerprint,
            fireHiddenCallbacks("Lcom/applovin/mediation/ads/MaxRewardedAd;"),
        )
        logger.info("No Ads: MAX rewarded adapter patched $patched method(s)")
        return PatchResult(patched = patched)
    }

    override fun applyRuntimeResolved(plan: NoAdsSdkPlan): PatchResult = PatchResult(skipped = 1)

}

internal class MaxNoAdsAdapterSet(
    context: BytecodePatchContext,
    logger: Logger,
) : NoAdsSdkAdapter {
    private val interstitial = MaxInterstitialNoAdsAdapter(context, logger)
    private val appOpen = MaxAppOpenNoAdsAdapter(context, logger)
    private val banner = MaxBannerNoAdsAdapter(context, logger)
    private val rewarded = MaxRewardedNoAdsAdapter(context, logger)

    override fun detect(): DetectionResult = DetectionResult("AppLovin MAX", listOf(
        interstitial, appOpen, banner, rewarded,
    ).any { it.detect().detected })

    override fun applyStatic(plan: NoAdsSdkPlan): PatchResult = combine(
        interstitial.applyStatic(plan), appOpen.applyStatic(plan), banner.applyStatic(plan), rewarded.applyStatic(plan),
    )

    override fun applyRuntime(plan: NoAdsSdkPlan): PatchResult = combine(
        interstitial.applyRuntime(plan), appOpen.applyRuntime(plan), banner.applyRuntime(plan), rewarded.applyRuntime(plan),
    )

    private fun combine(vararg results: PatchResult): PatchResult = PatchResult(
        patched = results.sumOf { it.patched },
        skipped = results.sumOf { it.skipped },
    )
}
