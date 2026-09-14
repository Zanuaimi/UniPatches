package unipatches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import helpers.bytecode.cloneMutableAndPreserveParameters
import java.util.logging.Logger

internal class YandexAdsAdapter(
    context: BytecodePatchContext,
    logger: Logger,
) : ContextAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(context) {
        val detected = MyTargetBaseInterstitialShowFingerprint.methodOrNull != null ||
            YandexUnityRewardedWrapperShowFingerprint.methodOrNull != null
        DetectionResult("Yandex / MyTarget", detected, setOf("rewarded"), detected, detected)
    }

    override fun applyStatic(plan: StaticSdkPlan): PatchResult = apply(plan.settings, plan.coverageEnabled)
    override fun applyRuntime(plan: RuntimeSdkPlan): PatchResult = apply(plan.settings, plan.coverageEnabled)

    private fun apply(settings: AdsPatchSettings, coverageEnabled: Boolean): PatchResult {
        if (!coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        context.applyMyTargetStrategy(logger)
        context.applyYandexWrapperStrategy(logger)
        return PatchResult(patched = 1)
    }
}

internal class InMobiAdsAdapter(
    context: BytecodePatchContext,
    logger: Logger,
) : ContextAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(context) {
        val detected = InMobiRewardedShowFingerprint.methodOrNull != null ||
            InMobiInterstitialShowFingerprint.methodOrNull != null
        DetectionResult("InMobi", detected, setOf("interstitial", "rewarded"), InMobiRewardedShowFingerprint.methodOrNull != null, detected)
    }

    override fun applyStatic(plan: StaticSdkPlan): PatchResult = apply(plan.settings, plan.coverageEnabled)
    override fun applyRuntime(plan: RuntimeSdkPlan): PatchResult = apply(plan.settings, plan.coverageEnabled)

    private fun apply(settings: AdsPatchSettings, coverageEnabled: Boolean): PatchResult {
        if (!coverageEnabled || !detect().detected) return PatchResult(skipped = 1)
        if (context.run { InMobiRewardedShowFingerprint.methodOrNull } != null) {
            logger.warning("Ads Free Rewards: InMobi rewarded show hook skipped because no verified callback-safe fingerprint is available; availability-only handling remains supported.")
        }
        return PatchResult(skipped = 1)
    }
}

internal fun BytecodePatchContext.applyMyTargetStrategy(logger: Logger) {
    val target = MyTargetBaseInterstitialShowFingerprint.methodOrNull ?: return
    if ((target.implementation?.registerCount ?: 0) < 2) return
    val showClass = MyTargetBaseInterstitialShowFingerprint.classDefOrNull ?: return
    target.cloneMutableAndPreserveParameters(showClass).addInstructions(0, guardedInstantReward("""
        instance-of v0, p0, Lcom/my/target/ads/RewardedAd;
        if-eqz v0, :morphe_rustore_mytarget_original_show
        check-cast p0, Lcom/my/target/ads/RewardedAd;
        invoke-virtual {p0}, Lcom/my/target/ads/RewardedAd;->getListener()Lcom/my/target/ads/RewardedAd${'$'}RewardedAdListener;
        move-result-object v0
        if-eqz v0, :morphe_rustore_mytarget_done
        invoke-interface {v0, p0}, Lcom/my/target/ads/RewardedAd${'$'}RewardedAdListener;->onDisplay(Lcom/my/target/ads/RewardedAd;)V
        invoke-static {}, Lcom/my/target/ads/Reward;->getDefault()Lcom/my/target/ads/Reward;
        move-result-object p1
        invoke-interface {v0, p1, p0}, Lcom/my/target/ads/RewardedAd${'$'}RewardedAdListener;->onReward(Lcom/my/target/ads/Reward;Lcom/my/target/ads/RewardedAd;)V
        invoke-interface {v0, p0}, Lcom/my/target/ads/RewardedAd${'$'}RewardedAdListener;->onDismiss(Lcom/my/target/ads/RewardedAd;)V
        :morphe_rustore_mytarget_done
        return-void
        :morphe_rustore_mytarget_original_show
    """.trimIndent(), "morphe_rustore_mytarget_reward_original"))
    logger.info("Ads Free Rewards: MyTarget rewarded patch applied")
}

internal fun BytecodePatchContext.applyYandexWrapperStrategy(logger: Logger) {
    val show = YandexUnityRewardedWrapperShowFingerprint.methodOrNull ?: return
    val listener = YandexUnityRewardedListenerOnRewardedFingerprint.methodOrNull ?: return
    listener.addInstructions(0, guardedInstantReward("""
        iget-object v0, p0, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/a;->b:Lcom/yandex/mobile/ads/unity/wrapper/rewarded/UnityRewardedAdListener;
        if-eqz v0, :morphe_rustore_yandex_reward_done
        const/4 v1, 0x1
        const-string p1, "default"
        invoke-interface {v0, v1, p1}, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/UnityRewardedAdListener;->onRewarded(ILjava/lang/String;)V
        :morphe_rustore_yandex_reward_done
        return-void
    """.trimIndent(), "morphe_rustore_yandex_reward_original"))
    val showClass = YandexUnityRewardedWrapperShowFingerprint.classDefOrNull ?: return
    val cloned = show.cloneMutableAndPreserveParameters(showClass)
    if (!adsFreeRewardsRuntimeGuardEnabled) cloned.implementation?.removeInstructions(cloned.implementation!!.instructions.size)
    cloned.addInstructions(0, guardedInstantReward("""
        iget-object v0, p0, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/RewardedAdWrapper;->b:Lcom/yandex/mobile/ads/unity/wrapper/rewarded/a;
        if-eqz v0, :morphe_rustore_yandex_show_done
        invoke-virtual {v0}, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/a;->onAdShown()V
        const/4 v1, 0x0
        invoke-virtual {v0, v1}, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/a;->onRewarded(Lcom/yandex/mobile/ads/rewarded/Reward;)V
        invoke-virtual {v0}, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/a;->onAdDismissed()V
        :morphe_rustore_yandex_show_done
        return-void
    """.trimIndent(), "morphe_rustore_yandex_show_original"))
    logger.info("Ads Free Rewards: Yandex wrapper patch applied")
}

/** Huawei detection is registered here; its callback-specific operation remains guarded by the
 * explicit Huawei section in the coordinator until that operation is fully adapter-local. */
internal class HuaweiAdsAdapter(
    context: BytecodePatchContext,
    logger: Logger,
) : ContextAdsSdkAdapter(context, logger) {
    override fun detect(): DetectionResult = with(context) {
        val detected = HuaweiRewardAdIsLoadedFingerprint.methodOrNull != null &&
            HuaweiRewardAdShowFingerprint.methodOrNull != null
        DetectionResult(
            sdkName = "Huawei Ads Kit",
            detected = detected,
            supportedFormats = setOf("rewarded"),
            supportsRewards = detected,
            supportsAvailability = HuaweiRewardAdIsLoadedFingerprint.methodOrNull != null,
            callbackStrategy = "reward-status-listener",
        )
    }

    override fun applyStatic(plan: StaticSdkPlan): PatchResult = apply(plan.settings, plan.coverageEnabled)

    override fun applyRuntime(plan: RuntimeSdkPlan): PatchResult = apply(plan.settings, plan.coverageEnabled)

    private fun apply(settings: AdsPatchSettings, coverageEnabled: Boolean): PatchResult {
        if (!coverageEnabled || !detect().detected || (settings.instantReward != true && !adsFreeRewardsRuntimeGuardEnabled)) {
            return PatchResult(skipped = 1)
        }
        val show = context.run { HuaweiRewardAdShowFingerprint.methodOrNull }
            ?: return PatchResult(skipped = 1)
        val showClass = context.run { HuaweiRewardAdShowFingerprint.classDefOrNull }
            ?: return PatchResult(skipped = 1)
        show.cloneMutableAndPreserveParameters(showClass).addInstructions(0, guardedInstantReward("""
            if-eqz p2, :morphe_huawei_reward_done
            invoke-virtual {p2}, Lcom/huawei/hms/ads/reward/RewardAdStatusListener;->onRewardAdOpened()V
            sget-object v0, Lcom/huawei/hms/ads/reward/Reward;->DEFAULT:Lcom/huawei/hms/ads/reward/Reward;
            invoke-virtual {p2, v0}, Lcom/huawei/hms/ads/reward/RewardAdStatusListener;->onRewarded(Lcom/huawei/hms/ads/reward/Reward;)V
            invoke-virtual {p2}, Lcom/huawei/hms/ads/reward/RewardAdStatusListener;->onRewardAdClosed()V
            :morphe_huawei_reward_done
            return-void
        """.trimIndent(), "morphe_huawei_reward_original"))
        logger.info("Ads Free Rewards: Huawei rewarded patch applied")
        return PatchResult(patched = 1)
    }
}
