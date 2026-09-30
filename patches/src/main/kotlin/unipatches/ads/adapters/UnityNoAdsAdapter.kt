package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import java.util.logging.Logger

/** Owns Unity Ads No Ads operations for both resolved static and runtime paths. */
internal class UnityNoAdsAdapter(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) : ContextNoAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(context) {
        DetectionResult("Unity Ads", UnityAdsV3Show2ArgFingerprint.methodOrNull != null ||
            UnityRewardedAdShowFingerprint.methodOrNull != null, setOf("interstitials", "rewarded"))
    }

    override fun applyResolved(plan: NoAdsSdkPlan): PatchResult {
        var patched = 0
        if (plan.interstitials) {
            patched += context.patchVoid(UnityAdsV3Show2ArgFingerprint)
            patched += context.patchVoid(UnityAdsV3ShowOptionsFingerprint)
        }
        // Unity Ads v4 show(...) is shared by rewarded and interstitial placements. It may only
        // be permanently replaced when no runtime policy can later need the original entry point.
        if (shouldPatchUnityAdsV4Permanently(
                runtimeHooksEnabled = runtimePolicyActiveForNoAds(plan),
                unitySdkEnabled = true,
                blockInterstitials = plan.interstitials,
                blockRewarded = plan.rewarded,
            )
        ) {
            patched += context.patchVoid(UnityAdsV4Show3ArgFingerprint)
            patched += context.patchVoid(UnityAdsV4Show4ArgFingerprint)
        }
        if (plan.rewarded) patched += context.patchVoid(UnityRewardedAdShowFingerprint)
        if (plan.rewarded) {
            patched += context.patchReturnFalse(UnityAdsAdvertisementIsReadyFingerprint)
            patched += context.patchReturnFalse(UnityAdsAdvertisementIsReadyPlacementFingerprint)
            patched += context.patchReturnFalse(UnityAdsSdkIsReadyFingerprint)
        }
        if (patched > 0) logger.info("No Ads: Unity adapter patched $patched method(s)")
        return PatchResult(patched = patched)
    }

    override fun applyRuntimeResolved(plan: NoAdsSdkPlan): PatchResult = applyResolved(plan)

    private fun runtimePolicyActiveForNoAds(plan: NoAdsSdkPlan): Boolean =
        plan.mode == AdsPatchMode.RUNTIME || adsFreeRewardsRuntimeGuardEnabled
}
