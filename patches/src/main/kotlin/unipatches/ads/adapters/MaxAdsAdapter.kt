package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import java.util.logging.Logger

/** MAX Unity bridge reward adapter. */
internal class MaxUnityRewardAdapter(
    context: BytecodePatchContext,
    logger: Logger,
) : ContextAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult {
        val detected = with(context) {
            ShowRewardedAdFingerprint.methodOrNull != null && IsRewardedAdReadyFingerprint.methodOrNull != null
        }
        return DetectionResult(
            sdkName = "AppLovin MAX Unity bridge",
            detected = detected,
            supportedFormats = setOf("rewarded"),
            supportsRewards = detected,
            supportsAvailability = detected,
            minimumLocalRegisters = 1,
            preservesParameterRegisters = true,
            callbackStrategy = "native-only-static; runtime-skipped",
        )
    }

    override fun applyStatic(plan: StaticSdkPlan): PatchResult {
        if (!plan.coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        return PatchResult(patched = if (context.applyLegacyMaxUnityStrategy(logger, true, plan.settings.instantReward)) 1 else 0)
    }

    override fun applyRuntime(plan: RuntimeSdkPlan): PatchResult {
        if (!plan.coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        // Runtime MAX remains untouched until a request-scoped, parameter-preserving hook exists.
        logger.warning("Ads Free Rewards: skipped MAX Unity runtime reward hooks for startup safety")
        return PatchResult(skipped = 1)
    }
}

/** Native MAX rewarded adapter. */
internal class MaxNativeRewardedAdapter(
    context: BytecodePatchContext,
    logger: Logger,
) : ContextAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult {
        val detected = with(context) {
            MaxRewardedAdShowAdFingerprint.methodOrNull != null && MaxRewardedAdIsReadyFingerprint.methodOrNull != null
        }
        return DetectionResult(
            sdkName = "AppLovin MAX native rewarded",
            detected = detected,
            supportedFormats = setOf("rewarded"),
            supportsRewards = detected,
            supportsAvailability = detected,
            minimumLocalRegisters = 1,
            preservesParameterRegisters = true,
            callbackStrategy = "native-listener-static; runtime-skipped",
        )
    }

    override fun applyStatic(plan: StaticSdkPlan): PatchResult {
        if (!plan.coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        context.applyLegacyNativeMaxStrategy(logger, true, plan.settings.instantReward)
        return PatchResult(patched = 1)
    }

    override fun applyRuntime(plan: RuntimeSdkPlan): PatchResult {
        if (!plan.coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        logger.warning("Ads Free Rewards: skipped native MAX runtime reward hooks for startup safety")
        return PatchResult(skipped = 1)
    }
}

/** Compatibility facade that composes the isolated MAX reward adapters. */
internal class MaxAdsAdapter(
    context: BytecodePatchContext,
    logger: Logger,
) : ContextAdsSdkAdapter(context, logger) {
    private val unity = MaxUnityRewardAdapter(context, logger)
    private val native = MaxNativeRewardedAdapter(context, logger)

    override fun detect(): DetectionResult {
        val unityResult = unity.detect()
        val nativeResult = native.detect()
        return DetectionResult(
            sdkName = "AppLovin MAX",
            detected = unityResult.detected || nativeResult.detected,
            supportedFormats = unityResult.supportedFormats + nativeResult.supportedFormats,
            supportsRewards = unityResult.supportsRewards || nativeResult.supportsRewards,
            supportsAvailability = unityResult.supportsAvailability || nativeResult.supportsAvailability,
        )
    }

    override fun applyStatic(plan: StaticSdkPlan): PatchResult = combine(
        unity.applyStatic(plan), native.applyStatic(plan),
    )

    override fun applyRuntime(plan: RuntimeSdkPlan): PatchResult = combine(
        unity.applyRuntime(plan), native.applyRuntime(plan),
    )

    private fun combine(first: PatchResult, second: PatchResult): PatchResult = PatchResult(
        patched = first.patched + second.patched,
        skipped = first.skipped + second.skipped,
    )
}
