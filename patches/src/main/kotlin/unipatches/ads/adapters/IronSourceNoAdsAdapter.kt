package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import java.util.logging.Logger

/** Owns ironSource and LevelPlay No Ads operations after path resolution. */
internal class IronSourceNoAdsAdapter(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) : ContextNoAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(context) {
        DetectionResult("ironSource / LevelPlay", IronSourceShowInterstitialFingerprint.methodOrNull != null ||
            IronSourceShowRewardedVideoFingerprint.methodOrNull != null, setOf("interstitials", "rewarded"))
    }

    override fun applyResolved(plan: NoAdsSdkPlan): PatchResult {
        var patched = 0
        if (plan.interstitials) {
            patched += context.patchVoid(IronSourceShowDemandOnlyInterstitialFingerprint)
            patched += context.patchVoid(IronSourceShowInterstitialFingerprint)
            patched += context.patchVoid(IronSourceShowInterstitialActivityFingerprint)
            patched += context.patchVoid(IronSourceShowInterstitialActivityPlacementFingerprint)
            patched += context.patchVoid(IronSourceShowInterstitialPlacementFingerprint)
        }
        if (plan.rewarded) {
            patched += context.patchVoid(IronSourceShowDemandOnlyRewardedVideoFingerprint)
            patched += context.patchVoid(IronSourceShowRewardedVideoFingerprint)
            patched += context.patchVoid(IronSourceShowRewardedVideoActivityFingerprint)
            patched += context.patchVoid(IronSourceShowRewardedVideoActivityPlacementFingerprint)
            patched += context.patchVoid(IronSourceShowRewardedVideoPlacementFingerprint)
            patched += context.patchReturnFalse(LevelPlayRewardedAdIsReadyFingerprint)
            patched += context.patchReturnFalse(IronSourceUnityRewardedAdIsReadyFingerprint)
            patched += context.patchVoid(IronSourceLevelPlayFullScreenShowAdFingerprint)
            patched += context.patchReturnFalse(IronSourceIsRewardedVideoAvailableFingerprint)
        }
        if (patched > 0) logger.info("No Ads: ironSource/LevelPlay adapter patched $patched method(s)")
        return PatchResult(patched = patched)
    }

    override fun applyRuntimeResolved(plan: NoAdsSdkPlan): PatchResult = applyResolved(plan)
}
