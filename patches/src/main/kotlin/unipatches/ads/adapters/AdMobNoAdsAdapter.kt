package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import java.util.logging.Logger

/** Owns Google Mobile Ads No Ads operations after the path plan has been resolved. */
internal class AdMobNoAdsAdapter(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) : ContextNoAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(context) {
        DetectionResult("Google Mobile Ads", AdMobInterstitialShowFingerprint.methodOrNull != null ||
            AdMobRewardedShowFingerprint.methodOrNull != null, setOf("interstitials", "rewarded"))
    }

    override fun applyResolved(plan: NoAdsSdkPlan): PatchResult {
        var patched = 0
        if (plan.interstitials) {
            patched += context.patchVoid(AdMobInterstitialShowFingerprint)
            patched += context.patchVoid(AdMobLegacyInterstitialShowFingerprint)
        }
        if (plan.appOpen) {
            patched += context.patchVoid(AdMobAppOpenShowFingerprint)
            patched += context.patchVoid(AdMobAppOpenLoadFingerprint)
        }
        if (plan.rewarded) {
            patched += context.patchVoid(AdMobRewardedShowFingerprint)
            patched += context.patchVoid(AdMobLegacyRewardedVideoShowFingerprint)
        }
        if (plan.native || plan.banners) {
            patched += context.patchVoid(AdMobNativeAdViewFingerprint)
            patched += context.patchVoid(AdMobAdLoaderLoadFingerprint)
        }
        if (patched > 0) logger.info("No Ads: AdMob adapter patched $patched method(s)")
        return PatchResult(patched = patched)
    }

    override fun applyRuntimeResolved(plan: NoAdsSdkPlan): PatchResult = applyResolved(plan)
}
