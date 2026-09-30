package unipatches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import java.util.logging.Logger

internal class UnityAdsAdapter(
    context: BytecodePatchContext,
    logger: Logger,
) : ContextAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(context) {
        val detected = UnityRewardedAdShowFingerprint.methodOrNull != null ||
            UnityAdsV4Show3ArgFingerprint.methodOrNull != null ||
            UnityAdsV4Show4ArgFingerprint.methodOrNull != null
        DetectionResult(
            sdkName = "Unity Ads",
            detected = detected,
            supportedFormats = setOf("rewarded", "shared"),
            supportsRewards = detected,
            supportsAvailability = UnityAdsAdvertisementIsReadyFingerprint.methodOrNull != null,
        )
    }

    override fun applyStatic(plan: StaticSdkPlan): PatchResult {
        if (!plan.coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        context.applyUnityAdsStrategy(logger, true, plan.settings.instantReward)
        context.applyUnityAdsV4Strategy(logger, true, plan.settings.instantReward)
        return PatchResult(patched = 1)
    }

    override fun applyRuntime(plan: RuntimeSdkPlan): PatchResult {
        if (!plan.coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        context.applyUnityAdsStrategy(logger, true, plan.settings.instantReward)
        context.applyUnityAdsV4Strategy(logger, true, plan.settings.instantReward)
        return PatchResult(patched = 1)
    }
}

internal fun BytecodePatchContext.applyUnityAdsStrategy(logger: Logger, useUnityAds: Boolean, instantReward: Boolean?) {
    val adsShow = UnityRewardedAdShowFingerprint.methodOrNull ?: return
    if (!useUnityAds || (instantReward != true && !adsFreeRewardsRuntimeGuardEnabled)) return
    adsShow.addInstructions(0, guardedInstantReward("""
        if-eqz p3, :morphe_unity_ads_callback_done
        invoke-interface {p3, p0}, Lcom/unity3d/ads/RewardedShowListener;->onRewarded(Lcom/unity3d/ads/RewardedAd;)V
        invoke-interface {p3, p0}, Lcom/unity3d/ads/ShowListener;->onStarted(Ljava/lang/Object;)V
        sget-object v0, Lcom/unity3d/ads/ShowFinishState;->COMPLETED:Lcom/unity3d/ads/ShowFinishState;
        invoke-interface {p3, p0, v0}, Lcom/unity3d/ads/ShowListener;->onCompleted(Ljava/lang/Object;Lcom/unity3d/ads/ShowFinishState;)V
        :morphe_unity_ads_callback_done
        return-void
    """.trimIndent(), "morphe_unity_ads_original"))
    logger.info("Ads Free Rewards: Unity Ads patch succeeded")
}

internal fun BytecodePatchContext.applyUnityAdsV4Strategy(logger: Logger, useUnityAds: Boolean, instantReward: Boolean?) {
    if (!useUnityAds || (instantReward != true && !adsFreeRewardsRuntimeGuardEnabled)) return
    UnityAdsV4Show3ArgFingerprint.methodOrNull?.addInstructions(0, guardedInstantReward("""
        if-eqz p2, :morphe_unity_ads_v4_3_callback_done
        invoke-interface {p2, p1}, Lcom/unity3d/ads/IUnityAdsShowListener;->onUnityAdsShowStart(Ljava/lang/String;)V
        sget-object v0, Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;->COMPLETED:Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;
        invoke-interface {p2, p1, v0}, Lcom/unity3d/ads/IUnityAdsShowListener;->onUnityAdsShowComplete(Ljava/lang/String;Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;)V
        :morphe_unity_ads_v4_3_callback_done
        return-void
    """.trimIndent(), "morphe_unity_ads_v4_3_original"))
    UnityAdsV4Show4ArgFingerprint.methodOrNull?.addInstructions(0, guardedInstantReward("""
        if-eqz p3, :morphe_unity_ads_v4_4_callback_done
        invoke-interface {p3, p1}, Lcom/unity3d/ads/IUnityAdsShowListener;->onUnityAdsShowStart(Ljava/lang/String;)V
        sget-object v0, Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;->COMPLETED:Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;
        invoke-interface {p3, p1, v0}, Lcom/unity3d/ads/IUnityAdsShowListener;->onUnityAdsShowComplete(Ljava/lang/String;Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;)V
        :morphe_unity_ads_v4_4_callback_done
        return-void
    """.trimIndent(), "morphe_unity_ads_v4_4_original"))
    logger.info("Ads Free Rewards: Unity Ads v4 patch evaluated")
}
