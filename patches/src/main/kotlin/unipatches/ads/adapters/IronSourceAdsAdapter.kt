package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import java.util.logging.Logger

internal class IronSourceAdsAdapter(
    context: BytecodePatchContext,
    logger: Logger,
) : ContextAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(context) {
        val detected = IronSourceAdsRewardedShowFingerprint.methodOrNull != null ||
            LevelPlayRewardedAdIsReadyFingerprint.methodOrNull != null ||
            IronSourceUnityRewardedAdIsReadyFingerprint.methodOrNull != null
        DetectionResult(
            sdkName = "ironSource / LevelPlay",
            detected = detected,
            supportedFormats = setOf("rewarded"),
            supportsRewards = detected,
            supportsAvailability = detected,
        )
    }

    override fun applyStatic(plan: StaticSdkPlan): PatchResult = apply(plan.settings, plan.coverageEnabled)

    override fun applyRuntime(plan: RuntimeSdkPlan): PatchResult = apply(plan.settings, plan.coverageEnabled)

    private fun apply(settings: AdsPatchSettings, coverageEnabled: Boolean): PatchResult {
        if (!coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        context.applyLegacyIronSourceAdsStrategy(logger, true, settings.instantReward)
        context.applyLegacyIronSourceAdsWrapperStrategy(logger, true, settings.instantReward)
        context.applyLegacyLevelPlayStrategy(logger, true)
        context.applyLegacyIronSourceBridgeStrategy(logger, true, settings.instantReward)
        context.applyLegacyMadsStrategy(logger, true, settings.instantReward)
        return PatchResult(patched = 1)
    }
}
