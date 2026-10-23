package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import java.util.logging.Logger

internal class AdMobAdsAdapter(
    context: BytecodePatchContext,
    logger: Logger,
) : ContextAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(context) {
        val detected = AdMobRewardedShowFingerprint.methodOrNull != null ||
            AdMobLegacyRewardedVideoShowFingerprint.methodOrNull != null
        DetectionResult(
            sdkName = "Google Mobile Ads",
            detected = detected,
            supportedFormats = setOf("rewarded"),
            supportsRewards = detected,
            supportsAvailability = false,
        )
    }

    override fun applyStatic(plan: StaticSdkPlan): PatchResult {
        if (!plan.coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        context.applyLegacyAdMobRewardedStrategy(logger, true, plan.settings.instantReward)
        return PatchResult(patched = 1)
    }

    override fun applyRuntime(plan: RuntimeSdkPlan): PatchResult {
        if (!plan.coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        context.applyLegacyAdMobRewardedStrategy(logger, true, plan.settings.instantReward)
        return PatchResult(patched = 1)
    }
}
