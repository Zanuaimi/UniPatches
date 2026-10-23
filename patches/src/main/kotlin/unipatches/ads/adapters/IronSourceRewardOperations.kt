package unipatches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.ads.*
import helpers.bytecode.*
import java.util.logging.Logger

internal fun BytecodePatchContext.applyLegacyIronSourceAdsStrategy(logger: Logger, useIronSource: Boolean, instantReward: Boolean?) {
    if ((instantReward != true && !adsFreeRewardsRuntimeGuardEnabled) || !useIronSource) return
    val ironAds = IronSourceAdsRewardedShowFingerprint.methodOrNull ?: return
    try {
        ironAds.addInstructions(0, guardedInstantReward("""
            return-void
        """.trimIndent(), "morphe_ironsource_ads_original"))
        logger.info("Ads Free Rewards: Unity IronSourceAds patch - forced show to success")
    } catch (e: Exception) {
        logger.warning("Ads Free Rewards: IronSourceAds patch failed: ${e.message}")
    }
}

// Precise ironSourceAds Unity wrapper (com.unity3d.ironsourceads.rewarded.RewardedAd).
// Fires shown -> earned -> dismissed on the registered listener so the Unity
// side grants the reward without a real ad. isReadyToShow is RV-only, force true.

internal fun BytecodePatchContext.applyLegacyIronSourceAdsWrapperStrategy(logger: Logger, useIronSource: Boolean, instantReward: Boolean?) {
    if ((instantReward != true && !adsFreeRewardsRuntimeGuardEnabled) || !useIronSource) return
    val ready = IronSourceAdsRewardedIsReadyPreciseFingerprint.methodOrNull
    val show = IronSourceAdsRewardedShowPreciseFingerprint.methodOrNull
    if (ready == null || show == null) return
    try {
        addGuardedFakeAvailability(logger, ready, "morphe_isads_ready_original")
        val showClass = IronSourceAdsRewardedShowPreciseFingerprint.classDefOrNull ?: return
        val cloned = show.cloneMutableAndPreserveParameters(showClass)
        cloned.addInstructions(0, guardedInstantReward("""
            invoke-virtual {p0}, Lcom/unity3d/ironsourceads/rewarded/RewardedAd;->getListener()Lcom/unity3d/ironsourceads/rewarded/RewardedAdListener;
            move-result-object v0
            if-eqz v0, :morphe_isads_done
            invoke-interface {v0, p0}, Lcom/unity3d/ironsourceads/rewarded/RewardedAdListener;->onRewardedAdShown(Lcom/unity3d/ironsourceads/rewarded/RewardedAd;)V
            invoke-interface {v0, p0}, Lcom/unity3d/ironsourceads/rewarded/RewardedAdListener;->onUserEarnedReward(Lcom/unity3d/ironsourceads/rewarded/RewardedAd;)V
            invoke-interface {v0, p0}, Lcom/unity3d/ironsourceads/rewarded/RewardedAdListener;->onRewardedAdDismissed(Lcom/unity3d/ironsourceads/rewarded/RewardedAd;)V
            :morphe_isads_done
            return-void
        """.trimIndent(), "morphe_isads_original"))
        logger.info("Ads Free Rewards: ironSourceAds wrapper patch succeeded (instant reward)")
    } catch (e: Exception) {
        logger.warning("Ads Free Rewards: ironSourceAds wrapper patch failed: ${e.message}")
    }
}

// Miniclip MADS mediation (madsunityplugin). The Unity-facing entry is
// MAdsAdsManagementWrapper.showAd(format, entryPoint, params) which serves
// banners, interstitials AND rewarded videos, so the injection checks the
// format against the RewardedVideos enum id and falls through to the
// original body for other formats. Reward fires via the RV handler
// singleton with a fresh info + reward payload.

internal fun BytecodePatchContext.applyLegacyMadsStrategy(logger: Logger, useIronSource: Boolean, instantReward: Boolean?) {
    if ((instantReward != true && !adsFreeRewardsRuntimeGuardEnabled) || !useIronSource) return
    val show = MadsWrapperShowAdFingerprint.methodOrNull ?: return
    if (MadsRvHandlerOnRewardedFingerprint.methodOrNull == null) return
    val enumClass = try {
        mutableClassDefByOrNull("Lcom/miniclip/madsunityplugin/utils/MAdsSDKWrapperUtils\$MAdsWrapperAdFormat;")
    } catch (_: Exception) {
        null
    }
    val hasRvEnum = enumClass?.fields?.any { it.name == "RewardedVideos" } == true &&
        enumClass.fields.any { it.name == "id" }
    if (!hasRvEnum) {
        logger.warning("Ads Free Rewards: MADS patch skipped  -  RewardedVideos enum not found")
        return
    }
    try {
        val localRegisters = (show.implementation?.registerCount ?: 0) - show.numberOfParameterRegisters
        if (localRegisters < 8) {
            logger.warning("Ads Free Rewards: MADS show patch needs eight local registers; skipped to avoid an unsafe bytecode rewrite.")
            return
        }
        show.addInstructions(0, guardedInstantReward("""
            sget-object v0, Lcom/miniclip/madsunityplugin/utils/MAdsSDKWrapperUtils${'$'}MAdsWrapperAdFormat;->RewardedVideos:Lcom/miniclip/madsunityplugin/utils/MAdsSDKWrapperUtils${'$'}MAdsWrapperAdFormat;
            iget v0, v0, Lcom/miniclip/madsunityplugin/utils/MAdsSDKWrapperUtils${'$'}MAdsWrapperAdFormat;->id:I
                if-ne p1, v0, :morphe_mads_reward_original_body
            sget-object v0, Lcom/miniclip/madsandroidsdk/base/adunit/RewardedVideosAdHandler;->INSTANCE:Lcom/miniclip/madsandroidsdk/base/adunit/RewardedVideosAdHandler;
            new-instance v1, Lcom/miniclip/madsandroidsdk/base/MediationAdInfo;
            invoke-direct {v1}, Lcom/miniclip/madsandroidsdk/base/MediationAdInfo;-><init>()V
            new-instance v2, Lcom/miniclip/madsandroidsdk/base/Reward;
            const-string v3, "1.0"
            invoke-static {v3}, Ljava/lang/Double;->parseDouble(Ljava/lang/String;)D
            move-result-wide v6
            const-string v3, "reward"
            invoke-direct {v2, v3, v6, v7}, Lcom/miniclip/madsandroidsdk/base/Reward;-><init>(Ljava/lang/String;D)V
            move-object v3, p2
            invoke-virtual {v0, v1, v2, v3}, Lcom/miniclip/madsandroidsdk/base/adunit/RewardedVideosAdHandler;->onAdRewarded(Lcom/miniclip/madsandroidsdk/base/MediationAdInfo;Lcom/miniclip/madsandroidsdk/base/Reward;Ljava/lang/String;)V
            const/4 v0, 0x1
            return v0
                :morphe_mads_reward_original_body
        """.trimIndent(), "morphe_mads_original"))
        logger.info("Ads Free Rewards: MADS patch succeeded (instant reward)")
        val ready = MadsWrapperIsReadyFingerprint.methodOrNull
        if (ready != null) {
            val readyLocals = (ready.implementation?.registerCount ?: 0) - ready.numberOfParameterRegisters
            if (readyLocals >= 1) {
                ready.addInstructions(0, """
                    sget-object v0, Lcom/miniclip/madsunityplugin/utils/MAdsSDKWrapperUtils${'$'}MAdsWrapperAdFormat;->RewardedVideos:Lcom/miniclip/madsunityplugin/utils/MAdsSDKWrapperUtils${'$'}MAdsWrapperAdFormat;
                    iget v0, v0, Lcom/miniclip/madsunityplugin/utils/MAdsSDKWrapperUtils${'$'}MAdsWrapperAdFormat;->id:I
                    if-ne p1, v0, :morphe_mads_ready_original
                    ${guardedFakeAvailability("morphe_mads_fake_ready_original")}
                    :morphe_mads_ready_original
                """.trimIndent())
                logger.info("Ads Free Rewards: MADS patch succeeded (fake ready)")
            } else logger.warning("Ads Free Rewards: MADS ready patch needs one local register; skipped.")
        }
    } catch (e: Exception) {
        logger.warning("Ads Free Rewards: MADS patch failed: ${e.message}")
    }
}

internal fun BytecodePatchContext.applyLegacyLevelPlayStrategy(logger: Logger, useIronSource: Boolean) {
    val levelPlayReady = LevelPlayRewardedAdIsReadyFingerprint.methodOrNull ?: return
    if (!useIronSource) return
    addGuardedFakeAvailability(logger, levelPlayReady, "morphe_levelplay_ready_original")
    logger.info("Ads Free Rewards: LevelPlay patch succeeded")
}

internal fun BytecodePatchContext.applyLegacyIronSourceBridgeStrategy(logger: Logger, useIronSource: Boolean, instantReward: Boolean?): Boolean {
    val bridgeReady = IronSourceUnityRewardedAdIsReadyFingerprint.methodOrNull
    val bridgeShow = IronSourceLevelPlayFullScreenShowAdFingerprint.methodOrNull
    if (!useIronSource || bridgeReady == null || bridgeShow == null) return false
    logger.info("Ads Free Rewards: IronSource patch succeeded")
    addGuardedFakeAvailability(logger, bridgeReady, "morphe_ironsource_bridge_ready_original")
    if (instantReward == true || adsFreeRewardsRuntimeGuardEnabled) {
        bridgeShow.addInstructions(0, guardedInstantReward("""
            iget-object v0, p0, Lcom/ironsource/Ya;->k:Lcom/ironsource/Za;
            if-eqz v0, :morphe_ads_free_rewards_done
            iget-object p1, p0, Lcom/ironsource/Ya;->m:Lcom/ironsource/q6;
            invoke-interface {p1}, Lcom/ironsource/q6;->b()Lcom/unity3d/mediation/LevelPlayAdInfo;
            move-result-object p1
            invoke-interface {v0, p1}, Lcom/ironsource/Za;->onAdDisplayed(Lcom/unity3d/mediation/LevelPlayAdInfo;)V
            new-instance v1, Lcom/unity3d/mediation/rewarded/LevelPlayReward;
            const-string p2, "reward"
            const/4 p0, 0x1
            invoke-direct {v1, p2, p0}, Lcom/unity3d/mediation/rewarded/LevelPlayReward;-><init>(Ljava/lang/String;I)V
            invoke-interface {v0, v1, p1}, Lcom/ironsource/Za;->onAdRewarded(Lcom/unity3d/mediation/rewarded/LevelPlayReward;Lcom/unity3d/mediation/LevelPlayAdInfo;)V
            invoke-interface {v0, p1}, Lcom/ironsource/Za;->onAdClosed(Lcom/unity3d/mediation/LevelPlayAdInfo;)V
            :morphe_ads_free_rewards_done
            return-void
        """.trimIndent(), "morphe_ads_free_rewards_original"))
    }
    return true
}
