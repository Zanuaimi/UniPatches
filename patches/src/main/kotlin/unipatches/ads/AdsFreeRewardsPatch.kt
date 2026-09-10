package unipatches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import helpers.ads.*
import helpers.bytecode.*
import java.util.logging.Logger

internal var adsFreeRewardsRuntimeGuardEnabled = false

internal data class AdsSdkCoverage(
    val max: Boolean = true,
    val adMob: Boolean = true,
    val unity: Boolean = true,
    val ironSource: Boolean = true,
    val appLovin: Boolean = true,
    val vungle: Boolean = true,
    val meta: Boolean = true,
    val pangle: Boolean = true,
    val huawei: Boolean = true,
    val yandex: Boolean = true,
    val other: Boolean = true,
)

private fun guardedPolicyBlock(policyMethod: String, instructions: String, originalLabel: String): String {
    if (!adsFreeRewardsRuntimeGuardEnabled) return instructions
    return """
        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->$policyMethod()Z
        move-result v0
        if-eqz v0, :$originalLabel
        $instructions
        :$originalLabel
    """.trimIndent()
}

private fun guardedInstantReward(instructions: String, originalLabel: String): String {
    if (!adsFreeRewardsRuntimeGuardEnabled) return instructions
    val skipLabel = "${originalLabel}_skip"
    val runtimeInstructions = instructions
        .replace(
            "return-void",
            """
            invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldSkipRewarded()Z
            move-result v0
            if-eqz v0, :$skipLabel
            return-void
            :$skipLabel
            """.trimIndent(),
        )
        .replace(
            "return v0",
            """
            invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldSkipRewarded()Z
            move-result v0
            if-eqz v0, :$skipLabel
            const/4 v0, 0x1
            return v0
            :$skipLabel
            """.trimIndent(),
        )
    return guardedPolicyBlock("shouldGrantReward", runtimeInstructions, originalLabel)
}

private fun guardedFakeAvailability(originalLabel: String): String {
    if (!adsFreeRewardsRuntimeGuardEnabled) {
        return """
            const/4 v0, 0x1
            return v0
        """.trimIndent()
    }
    return """
        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldFakeRewardAvailability()Z
        move-result v0
        if-eqz v0, :$originalLabel
        const/4 v0, 0x1
        return v0
        :$originalLabel
    """.trimIndent()
}

/**
 * Forces ad SDKs to report that an ad is currently available.
 *
 * Many games gate their rewarded / interstitial buttons behind an SDK
 * "isReady" / "isAvailable" check (e.g. Unity Ads Advertisement.isReady,
 * ironSource isRewardedVideoAvailable, AppLovin MAX isReady, or the Yandex
 * MyTarget mediation adapter's isLoaded). When no real ad can fill (no
 * network, ad-blocker, or re-signed build), these return false and the game
 * never calls show(), so the instant-reward hooks above never fire. Forcing
 * the gates to return true makes the game proceed to show(), letting the
 * reward flow grant without a real ad.
 */
internal fun BytecodePatchContext.forceAdAvailability(
    logger: Logger,
    rewardStrategy: String?,
    runtimePolicy: Boolean = false,
    sdkCoverage: AdsSdkCoverage = AdsSdkCoverage(),
): Int {
    var patched = 0
    fun patchIsReady(label: String, fingerprint: app.morphe.patcher.Fingerprint) {
        val method = fingerprint.methodOrNull ?: return
        val impl = method.implementation ?: run {
            logger.warning("Ads Free Rewards: skip $label  -  no implementation")
            return
        }
        if (method.returnType != "Z") {
            logger.warning("Ads Free Rewards: skip $label  -  expected boolean return, found ${method.returnType}")
            return
        }
        if (impl.registerCount - method.numberOfParameterRegisters < 1) {
            logger.warning("Ads Free Rewards: skip $label - no safe local register")
            return
        }
        val instructions = if (runtimePolicy) {
            """
            invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldFakeRewardAvailability()Z
            move-result v0
            if-eqz v0, :unipatch_ads_runtime_availability_original
            const/4 v0, 0x1
            return v0
            :unipatch_ads_runtime_availability_original
            """.trimIndent()
        } else {
            """
            const/4 v0, 0x1
            return v0
            """.trimIndent()
        }
        method.addInstructions(0, instructions)
        logger.info("Ads Free Rewards: faked availability for $label")
        patched++
    }

    val auto = rewardStrategy == "auto"
    val useMax = sdkCoverage.max && (auto || rewardStrategy == "max")
    val useUnity = sdkCoverage.unity && (auto || rewardStrategy == "unityAds")
    val useIronSource = sdkCoverage.ironSource && (auto || rewardStrategy == "ironSource")
    val useRustore = sdkCoverage.yandex && (auto || rewardStrategy == "rustore")
    val useHuawei = sdkCoverage.huawei && (auto || rewardStrategy == "huawei")

    if (useUnity) {
        patchIsReady("Unity Ads Advertisement.isReady()", UnityAdsAdvertisementIsReadyFingerprint)
        patchIsReady("Unity Ads Advertisement.isReady(placement)", UnityAdsAdvertisementIsReadyPlacementFingerprint)
        patchIsReady("Unity Ads UnityAds.isReady()", UnityAdsSdkIsReadyFingerprint)
    }
    if (useIronSource) {
        patchIsReady("ironSource isRewardedVideoAvailable()", IronSourceIsRewardedVideoAvailableFingerprint)
        patchIsReady("ironSource isInterstitialReady()", IronSourceIsInterstitialReadyFingerprint)
    }
    if (useMax) {
        patchIsReady("AppLovin MAX InterstitialAd.isReady()", MaxInterstitialAdIsReadyFingerprint)
        patchIsReady("AppLovin MAX AppOpenAd.isReady()", MaxAppOpenAdIsReadyFingerprint)
    }
    if (useRustore) {
        patchIsReady("Yandex/MyTarget rewarded mediation isLoaded()", YandexMyTargetRewardedIsLoadedFingerprint)
        patchIsReady("Yandex/MyTarget interstitial mediation isLoaded()", YandexMyTargetInterstitialIsLoadedFingerprint)
    }
    if (useHuawei) patchIsReady("Huawei Ads Kit RewardAd.isLoaded()", HuaweiRewardAdIsLoadedFingerprint)
    if (auto) patchIsReady("InMobi isReady()", InMobiIsReadyFingerprint)
    return patched
}

private fun BytecodePatchContext.applyAdsFreeRewardsV1190(
    logger: Logger,
    rewardStrategy: String?,
    instantReward: Boolean?,
    sdkCoverage: AdsSdkCoverage,
) {
    val strategy = rewardStrategy
    val auto = strategy == "auto"
    val useMax = sdkCoverage.max && (strategy == "auto" || strategy == "max")
    val useUnityAds = sdkCoverage.unity && (strategy == "auto" || strategy == "unityAds")
    val useIronSource = sdkCoverage.ironSource && (strategy == "auto" || strategy == "ironSource")
    val useRustore = sdkCoverage.yandex && (strategy == "auto" || strategy == "rustore")
    val useHuawei = sdkCoverage.huawei && (strategy == "auto" || strategy == "huawei")

    logger.info("Ads Free Rewards: strategy=$strategy instantReward=$instantReward")

    val hasMaxUnity = ShowRewardedAdFingerprint.methodOrNull != null &&
        IsRewardedAdReadyFingerprint.methodOrNull != null
    val hasNativeMax = MaxRewardedAdIsReadyFingerprint.methodOrNull != null &&
        MaxRewardedAdShowAdFingerprint.methodOrNull != null
    val hasUnityAds = UnityRewardedAdShowFingerprint.methodOrNull != null
    val hasUnityAdsV4 = UnityAdsV4Show3ArgFingerprint.methodOrNull != null ||
        UnityAdsV4Show4ArgFingerprint.methodOrNull != null
    val hasLevelPlay = LevelPlayRewardedAdIsReadyFingerprint.methodOrNull != null
    val hasIronSourceUnityBridge = IronSourceUnityRewardedAdIsReadyFingerprint.methodOrNull != null &&
        IronSourceLevelPlayFullScreenShowAdFingerprint.methodOrNull != null
    val hasMyTarget = MyTargetBaseInterstitialShowFingerprint.methodOrNull != null
    val hasYandexUnityRewarded = YandexUnityRewardedWrapperShowFingerprint.methodOrNull != null
    val hasHuawei = HuaweiRewardAdIsLoadedFingerprint.methodOrNull != null &&
        HuaweiRewardAdShowFingerprint.methodOrNull != null
    val hasAdMob = AdMobRewardedShowFingerprint.methodOrNull != null
    val hasInMobi = InMobiInterstitialShowFingerprint.methodOrNull != null || InMobiRewardedShowFingerprint.methodOrNull != null
    val hasInMobiRewarded = InMobiRewardedShowFingerprint.methodOrNull != null
    val hasIronSourceAds = IronSourceAdsRewardedShowFingerprint.methodOrNull != null ||
        IronSourceAdsRewardedShowPreciseFingerprint.methodOrNull != null
    val hasMads = MadsWrapperShowAdFingerprint.methodOrNull != null &&
        MadsRvHandlerOnRewardedFingerprint.methodOrNull != null

    logger.info("Ads Free Rewards: detected SDKs  -  MAX Unity=$hasMaxUnity native MAX=$hasNativeMax UnityAds=$hasUnityAds UnityAdsV4=$hasUnityAdsV4 LevelPlay=$hasLevelPlay ironSourceBridge=$hasIronSourceUnityBridge MyTarget=$hasMyTarget Yandex=$hasYandexUnityRewarded Huawei=$hasHuawei AdMob=$hasAdMob InMobi=$hasInMobi InMobiRewarded=$hasInMobiRewarded IronSourceAds=$hasIronSourceAds MADS=$hasMads")

    if (!hasMaxUnity && !hasNativeMax && !hasUnityAds && !hasUnityAdsV4 && !hasLevelPlay && !hasIronSourceUnityBridge && !hasMyTarget && !hasYandexUnityRewarded && !hasHuawei && !hasAdMob && !hasInMobiRewarded && !hasIronSourceAds && !hasMads) {
        logger.warning("Ads Free Rewards: no supported ad SDK found for reward strategy $strategy  -  no changes applied")
        return
    }

    // -- Huawei Ads Kit / Petal Ads --
    // Huawei's rewarded callback carries the reward object as a singleton
    // DEFAULT value, so no SDK-internal implementation class is required.
    val huaweiReady = HuaweiRewardAdIsLoadedFingerprint.methodOrNull
    val huaweiShow = HuaweiRewardAdShowFingerprint.methodOrNull
    if (useHuawei && (instantReward == true || adsFreeRewardsRuntimeGuardEnabled) && huaweiReady != null && huaweiShow != null) {
        val showClass = HuaweiRewardAdShowFingerprint.classDefOrNull
        if (showClass != null) {
            val clonedShow = huaweiShow.cloneMutableAndPreserveParameters(showClass)
            clonedShow.addInstructions(0, guardedInstantReward("""
                if-eqz p2, :morphe_huawei_reward_done
                invoke-virtual {p2}, Lcom/huawei/hms/ads/reward/RewardAdStatusListener;->onRewardAdOpened()V
                sget-object v0, Lcom/huawei/hms/ads/reward/Reward;->DEFAULT:Lcom/huawei/hms/ads/reward/Reward;
                invoke-virtual {p2, v0}, Lcom/huawei/hms/ads/reward/RewardAdStatusListener;->onRewarded(Lcom/huawei/hms/ads/reward/Reward;)V
                invoke-virtual {p2}, Lcom/huawei/hms/ads/reward/RewardAdStatusListener;->onRewardAdClosed()V
                :morphe_huawei_reward_done
                return-void
            """.trimIndent(), "morphe_huawei_reward_original"))
            logger.info("Huawei Ads Kit rewarded patch succeeded")
        } else {
            logger.warning("Ads Free Rewards: Huawei show class not found  -  skipping")
        }
    } else {
        if (!useHuawei) logger.info("Ads Free Rewards: Huawei strategy disabled by rewardStrategy=$strategy")
        else if (!hasHuawei) logger.info("Ads Free Rewards: Huawei SDK not detected  -  skipping")
        else logger.info("Ads Free Rewards: Huawei skipped (instantReward=$instantReward)")
    }

    if (useRustore && (instantReward == true || adsFreeRewardsRuntimeGuardEnabled)) {
        applyMyTargetStrategy(logger)
        applyYandexWrapperStrategy(logger)
    }
    applyMaxUnityStrategy(logger, useMax, instantReward)
    applyNativeMaxStrategy(logger, useMax, instantReward)
    applyInMobiRewardedStrategy(logger, auto && sdkCoverage.other, instantReward)
    applyIronSourceAdsStrategy(logger, useIronSource, instantReward)
    applyIronSourceAdsWrapperStrategy(logger, useIronSource, instantReward)
    applyMadsStrategy(logger, useIronSource, instantReward)
    applyAdMobRewardedStrategy(logger, sdkCoverage.adMob, instantReward)
    applyLevelPlayStrategy(logger, useIronSource)
    applyIronSourceBridgeStrategy(logger, useIronSource, instantReward)
    applyUnityAdsStrategy(logger, useUnityAds, instantReward)
    applyUnityAdsV4Strategy(logger, useUnityAds, instantReward)
}

private fun BytecodePatchContext.applyMyTargetStrategy(logger: Logger) {
    val myTargetShow = MyTargetBaseInterstitialShowFingerprint.methodOrNull ?: return
    val hasShow = myTargetShow.implementation?.registerCount ?: 0 >= 2
    if (!hasShow) {
        logger.warning("Ads Free Rewards: skip MyTarget  -  low registerCount")
        return
    }
    val showClass = MyTargetBaseInterstitialShowFingerprint.classDefOrNull ?: return
    val cloned = myTargetShow.cloneMutableAndPreserveParameters(showClass)
    cloned.addInstructions(0, guardedInstantReward("""
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
    logger.info("Ads Free Rewards: RuStore / VK MyTarget rewarded patch succeeded")
}

private fun BytecodePatchContext.applyYandexWrapperStrategy(logger: Logger) {
    val yandexRewardedShow = YandexUnityRewardedWrapperShowFingerprint.methodOrNull ?: return
    val yandexOnRewarded = YandexUnityRewardedListenerOnRewardedFingerprint.methodOrNull ?: return
    yandexOnRewarded.addInstructions(0, guardedInstantReward("""
        iget-object v0, p0, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/a;->b:Lcom/yandex/mobile/ads/unity/wrapper/rewarded/UnityRewardedAdListener;
        if-eqz v0, :morphe_rustore_yandex_reward_done
        const/4 v1, 0x1
        const-string p1, "default"
        invoke-interface {v0, v1, p1}, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/UnityRewardedAdListener;->onRewarded(ILjava/lang/String;)V
        :morphe_rustore_yandex_reward_done
        return-void
    """.trimIndent(), "morphe_rustore_yandex_reward_original"))
    val showClass = YandexUnityRewardedWrapperShowFingerprint.classDefOrNull ?: return
    val clonedShow = yandexRewardedShow.cloneMutableAndPreserveParameters(showClass)
    // Replace the original method for static patches. Runtime patches retain
    // the original body so disabling instant rewards falls through normally.
    if (!adsFreeRewardsRuntimeGuardEnabled) {
        val showImplementation = clonedShow.implementation ?: return
        showImplementation.removeInstructions(showImplementation.instructions.size)
    }
    clonedShow.addInstructions(0, guardedInstantReward("""
        iget-object v0, p0, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/RewardedAdWrapper;->b:Lcom/yandex/mobile/ads/unity/wrapper/rewarded/a;
        if-eqz v0, :morphe_rustore_yandex_show_done
        invoke-virtual {v0}, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/a;->onAdShown()V
        const/4 v1, 0x0
        invoke-virtual {v0, v1}, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/a;->onRewarded(Lcom/yandex/mobile/ads/rewarded/Reward;)V
        invoke-virtual {v0}, Lcom/yandex/mobile/ads/unity/wrapper/rewarded/a;->onAdDismissed()V
        :morphe_rustore_yandex_show_done
        return-void
    """.trimIndent(), "morphe_rustore_yandex_show_original"))
    logger.info("Ads Free Rewards: RuStore / Yandex Unity rewarded patch succeeded")
}

private fun BytecodePatchContext.applyInMobiRewardedStrategy(logger: Logger, useAutoFallback: Boolean, instantReward: Boolean?) {
    // These fingerprints belong to the InMobi adapter used by MAX. Do not
    // modify it when MAX has been disabled by the selected reward strategy.
    if (!useAutoFallback || (instantReward != true && !adsFreeRewardsRuntimeGuardEnabled)) return
    // InMobi mediated via AppLovin MAX - patching the show to instantly reward covers both interstitial and rewarded
    // Use the rewarded fingerprint if available, otherwise fallback to interstitial
    val target = InMobiRewardedShowFingerprint.methodOrNull ?: InMobiInterstitialShowFingerprint.methodOrNull ?: return
    try {
        target.addInstructions(0, guardedInstantReward("""
            return-void
        """.trimIndent(), "morphe_inmobi_original"))
        logger.info("Ads Free Rewards: InMobi patch - forced show to success")
    } catch (e: Exception) {
        logger.warning("Ads Free Rewards: InMobi patch failed: ${e.message}")
    }
}

private fun BytecodePatchContext.applyIronSourceAdsStrategy(logger: Logger, useIronSource: Boolean, instantReward: Boolean?) {
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
private fun BytecodePatchContext.applyIronSourceAdsWrapperStrategy(logger: Logger, useIronSource: Boolean, instantReward: Boolean?) {
    if ((instantReward != true && !adsFreeRewardsRuntimeGuardEnabled) || !useIronSource) return
    val ready = IronSourceAdsRewardedIsReadyPreciseFingerprint.methodOrNull
    val show = IronSourceAdsRewardedShowPreciseFingerprint.methodOrNull
    if (ready == null || show == null) return
    try {
        ready.addInstructions(0, guardedFakeAvailability("morphe_isads_ready_original"))
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
private fun BytecodePatchContext.applyMadsStrategy(logger: Logger, useIronSource: Boolean, instantReward: Boolean?) {
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

private fun BytecodePatchContext.applyMaxUnityStrategy(logger: Logger, useMax: Boolean, instantReward: Boolean?): Boolean {
    val unityShow = ShowRewardedAdFingerprint.methodOrNull
    val unityReady = IsRewardedAdReadyFingerprint.methodOrNull
    if (!useMax || unityShow == null || unityReady == null) return false
    logger.info("Ads Free Rewards: MAX Unity Ad wrapper patch succeeded")
    unityReady.addInstructions(0, guardedFakeAvailability("morphe_max_unity_ready_original"))
    if (instantReward == true || adsFreeRewardsRuntimeGuardEnabled) {
        val showClass = ShowRewardedAdFingerprint.classDefOrNull ?: return true
        val clonedShow = unityShow.cloneMutableAndPreserveParameters(showClass)
        clonedShow.addInstructions(0, guardedInstantReward("""
            move-object v0, p1
            new-instance p0, Lorg/json/JSONObject;
            invoke-direct {p0}, Lorg/json/JSONObject;-><init>()V
            const-string p1, "name"
            const-string p2, "OnRewardedAdDisplayedEvent"
            invoke-static {p0, p1, p2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            const-string p1, "adUnitId"
            invoke-static {p0, p1, v0}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            const-string p1, "adFormat"
            const-string p2, "rewarded"
            invoke-static {p0, p1, p2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            invoke-static {p0}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->forwardUnityEvent(Lorg/json/JSONObject;)V
            new-instance p0, Lorg/json/JSONObject;
            invoke-direct {p0}, Lorg/json/JSONObject;-><init>()V
            const-string p1, "name"
            const-string p2, "OnRewardedAdReceivedRewardEvent"
            invoke-static {p0, p1, p2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            const-string p1, "adUnitId"
            invoke-static {p0, p1, v0}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            const-string p1, "adFormat"
            const-string p2, "rewarded"
            invoke-static {p0, p1, p2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            const-string p1, "rewardLabel"
            const-string p2, "reward"
            invoke-static {p0, p1, p2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            const-string p1, "rewardAmount"
            const-string p2, "1"
            invoke-static {p0, p1, p2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            invoke-static {p0}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->forwardUnityEvent(Lorg/json/JSONObject;)V
            new-instance p0, Lorg/json/JSONObject;
            invoke-direct {p0}, Lorg/json/JSONObject;-><init>()V
            const-string p1, "name"
            const-string p2, "OnRewardedAdHiddenEvent"
            invoke-static {p0, p1, p2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            const-string p1, "adUnitId"
            invoke-static {p0, p1, v0}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            const-string p1, "adFormat"
            const-string p2, "rewarded"
            invoke-static {p0, p1, p2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
            invoke-static {p0}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->forwardUnityEvent(Lorg/json/JSONObject;)V
            return-void
        """.trimIndent(), "morphe_max_unity_original"))
        val unityLoad = LoadRewardedAdFingerprint.methodOrNull
        if (unityLoad != null) {
            logger.info("Ads Free Rewards: MAX Unity loadRewardedAd patching")
            val loadClass = LoadRewardedAdFingerprint.classDefOrNull ?: return true
            val clonedLoad = unityLoad.cloneMutableAndPreserveParameters(loadClass)
            clonedLoad.addInstructions(0, guardedPolicyBlock("shouldFakeRewardAvailability", """
                move-object v0, p1
                new-instance p0, Lorg/json/JSONObject;
                invoke-direct {p0}, Lorg/json/JSONObject;-><init>()V
                const-string p1, "name"
                const-string v1, "OnRewardedAdLoadedEvent"
                invoke-static {p0, p1, v1}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
                const-string p1, "adUnitId"
                invoke-static {p0, p1, v0}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
                const-string p1, "adFormat"
                const-string v1, "rewarded"
                invoke-static {p0, p1, v1}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
                invoke-static {p0}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->forwardUnityEvent(Lorg/json/JSONObject;)V
                return-void
            """.trimIndent(), "morphe_max_unity_load_original"))
        }
    }
    return true
}

private fun BytecodePatchContext.applyNativeMaxStrategy(logger: Logger, useMax: Boolean, instantReward: Boolean?) {
    val nativeReady = MaxRewardedAdIsReadyFingerprint.methodOrNull
    val nativeShow = MaxRewardedAdShowAdFingerprint.methodOrNull
    if (!useMax || nativeReady == null || nativeShow == null) return
    logger.info("Ads Free Rewards: native MAX patch succeeded")
    nativeReady.addInstructions(0, guardedFakeAvailability("morphe_native_max_ready_original"))
    if (instantReward == true || adsFreeRewardsRuntimeGuardEnabled) {
        val rc = nativeShow.implementation?.registerCount ?: 0
        if (rc >= 7) {
            nativeShow.addInstructions(0, guardedInstantReward(fireRewardedAdCallbacks(), "morphe_native_max_original"))
        } else logger.warning("Ads Free Rewards: native MAX showAd() needs seven local registers; skipped to avoid an unsafe bytecode rewrite.")
    }
}

private fun BytecodePatchContext.applyAdMobRewardedStrategy(logger: Logger, useAdMob: Boolean, instantReward: Boolean?) {
    if (!useAdMob || (instantReward != true && !adsFreeRewardsRuntimeGuardEnabled)) return
    // AdMob RewardedAd is from GMS (not in app dex), so patch call sites instead of definition
    var patchedCallSites = 0
    classDefForEach { classDef ->
        val tl = classDef.type.lowercase()
        if (tl.contains("okhttp") || tl.contains("androidx") || tl.contains("com/google/android/gms/ads/rewarded")) return@classDefForEach
        val matches = classDef.methods.mapNotNull { method ->
            val instructionMatches = mutableListOf<Triple<Int, Int, String>>()
            method.implementation?.instructions?.forEachIndexed { index, insn ->
                val ref = (insn as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference
                    ?: return@forEachIndexed
                if (ref.definingClass != "Lcom/google/android/gms/ads/rewarded/RewardedAd;" || ref.name != "show" || ref.returnType != "V") return@forEachIndexed
                if (ref.parameterTypes.size != 2 || ref.parameterTypes[1] != "Lcom/google/android/gms/ads/OnUserEarnedRewardListener;") return@forEachIndexed
                val listenerRegister = when (insn) {
                        is com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c -> {
                            // invoke-virtual {v0, v1, v2}, RewardedAd.show
                            // v0 = this (RewardedAd), v1 = Activity, v2 = listener
                            // Need to parse: for 35c, registerCount, registers C/D/E etc.
                            // For show with 3 regs (this, activity, listener), C=this, D=activity, E=listener
                            if (insn.registerCount < 3) return@forEachIndexed
                            insn.registerE
                        }
                        is com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc -> {
                            // invoke-virtual/range {v0..v2}
                            val start = insn.startRegister
                            start + 2
                        }
                        else -> return@forEachIndexed
                    }
                    val originalInvoke = when (insn) {
                        is com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c ->
                            "invoke-virtual {v${insn.registerC}, v${insn.registerD}, v${insn.registerE}}, Lcom/google/android/gms/ads/rewarded/RewardedAd;->show(Landroid/app/Activity;Lcom/google/android/gms/ads/OnUserEarnedRewardListener;)V"
                        is com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc ->
                            "invoke-virtual/range {v${insn.startRegister} .. v${insn.startRegister + 2}}, Lcom/google/android/gms/ads/rewarded/RewardedAd;->show(Landroid/app/Activity;Lcom/google/android/gms/ads/OnUserEarnedRewardListener;)V"
                        else -> return@forEachIndexed
                    }
                    instructionMatches += Triple(index, listenerRegister, originalInvoke)
            }
            if (instructionMatches.isEmpty()) null else method to instructionMatches
        }
        if (matches.isEmpty()) return@classDefForEach
        val mutableClass = try { mutableClassDefBy(classDef) } catch (_: Exception) { return@classDefForEach }
        matches.forEach { (immutableMethod, instructionMatches) ->
            val method = mutableClass.methods.firstOrNull {
                it.name == immutableMethod.name &&
                    it.returnType == immutableMethod.returnType &&
                    it.parameterTypes == immutableMethod.parameterTypes
            } ?: return@forEach
            instructionMatches.asReversed().forEach { (index, listenerReg, originalInvoke) ->
                // Found call site: RewardedAd.show(Activity, OnUserEarnedRewardListener)
                // Replace it with a conditional reward callback or the original show call.
                try {
                    method.replaceInstruction(index, """
                        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldGrantReward()Z
                        move-result v0
                        if-eqz v0, :morphe_admob_check_skip_$index
                        if-eqz v$listenerReg, :morphe_admob_check_skip_$index
                        const/4 v0, 0x0
                        invoke-interface {v$listenerReg, v0}, Lcom/google/android/gms/ads/OnUserEarnedRewardListener;->onUserEarnedReward(Lcom/google/android/gms/ads/rewarded/RewardItem;)V
                        :morphe_admob_check_skip_$index
                        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldSkipRewarded()Z
                        move-result v0
                        if-eqz v0, :morphe_admob_original_$index
                        goto :morphe_admob_done_$index
                        :morphe_admob_original_$index
                        $originalInvoke
                        :morphe_admob_done_$index
                    """.trimIndent())
                    patchedCallSites++
                } catch (_: Exception) {}
            }
        }
    }
    if (patchedCallSites > 0) {
        logger.info("Ads Free Rewards: AdMob patch - patched $patchedCallSites call site(s)")
    } else {
        logger.info("Ads Free Rewards: AdMob show call sites not found - skipping")
    }
}

private fun BytecodePatchContext.applyLevelPlayStrategy(logger: Logger, useIronSource: Boolean) {
    val levelPlayReady = LevelPlayRewardedAdIsReadyFingerprint.methodOrNull ?: return
    if (!useIronSource) return
    levelPlayReady.addInstructions(0, guardedFakeAvailability("morphe_levelplay_ready_original"))
    logger.info("Ads Free Rewards: LevelPlay patch succeeded")
}

private fun BytecodePatchContext.applyIronSourceBridgeStrategy(logger: Logger, useIronSource: Boolean, instantReward: Boolean?): Boolean {
    val bridgeReady = IronSourceUnityRewardedAdIsReadyFingerprint.methodOrNull
    val bridgeShow = IronSourceLevelPlayFullScreenShowAdFingerprint.methodOrNull
    if (!useIronSource || bridgeReady == null || bridgeShow == null) return false
    logger.info("Ads Free Rewards: IronSource patch succeeded")
    bridgeReady.addInstructions(0, guardedFakeAvailability("morphe_ironsource_bridge_ready_original"))
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

private fun BytecodePatchContext.applyUnityAdsStrategy(logger: Logger, useUnityAds: Boolean, instantReward: Boolean?) {
    val adsShow = UnityRewardedAdShowFingerprint.methodOrNull ?: return
    if (!useUnityAds || (instantReward != true && !adsFreeRewardsRuntimeGuardEnabled)) return
    adsShow.addInstructions(0, guardedInstantReward("""
        invoke-interface {p3, p0}, Lcom/unity3d/ads/RewardedShowListener;->onRewarded(Lcom/unity3d/ads/RewardedAd;)V
        invoke-interface {p3, p0}, Lcom/unity3d/ads/ShowListener;->onStarted(Ljava/lang/Object;)V
        sget-object v0, Lcom/unity3d/ads/ShowFinishState;->COMPLETED:Lcom/unity3d/ads/ShowFinishState;
        invoke-interface {p3, p0, v0}, Lcom/unity3d/ads/ShowListener;->onCompleted(Ljava/lang/Object;Lcom/unity3d/ads/ShowFinishState;)V
        return-void
    """.trimIndent(), "morphe_unity_ads_original"))
    logger.info("Ads Free Rewards: Unity Ads patch succeeded")
}

private fun BytecodePatchContext.applyUnityAdsV4Strategy(logger: Logger, useUnityAds: Boolean, instantReward: Boolean?) {
    if (!useUnityAds || (instantReward != true && !adsFreeRewardsRuntimeGuardEnabled)) return
    val v4Show3 = UnityAdsV4Show3ArgFingerprint.methodOrNull
    if (v4Show3 != null) {
        v4Show3.addInstructions(0, guardedInstantReward("""
            invoke-interface {p2, p1}, Lcom/unity3d/ads/IUnityAdsShowListener;->onUnityAdsShowStart(Ljava/lang/String;)V
            sget-object v0, Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;->COMPLETED:Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;
            invoke-interface {p2, p1, v0}, Lcom/unity3d/ads/IUnityAdsShowListener;->onUnityAdsShowComplete(Ljava/lang/String;Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;)V
            return-void
        """.trimIndent(), "morphe_unity_ads_v4_3_original"))
        logger.info("Ads Free Rewards: Unity Ads v4 patch succeeded (3-arg show)")
    }
    val v4Show4 = UnityAdsV4Show4ArgFingerprint.methodOrNull
    if (v4Show4 != null) {
        v4Show4.addInstructions(0, guardedInstantReward("""
            invoke-interface {p3, p1}, Lcom/unity3d/ads/IUnityAdsShowListener;->onUnityAdsShowStart(Ljava/lang/String;)V
            sget-object v0, Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;->COMPLETED:Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;
            invoke-interface {p3, p1, v0}, Lcom/unity3d/ads/IUnityAdsShowListener;->onUnityAdsShowComplete(Ljava/lang/String;Lcom/unity3d/ads/UnityAds${'$'}UnityAdsShowCompletionState;)V
            return-void
        """.trimIndent(), "morphe_unity_ads_v4_4_original"))
        logger.info("Ads Free Rewards: Unity Ads v4 patch succeeded (4-arg show)")
    }
}

// Historical snapshots - each version is a frozen copy.
// Newer entries delegate to the current implementation for now; future
// bundle releases can diverge them with version-specific fixes.
private fun BytecodePatchContext.applyAdsFreeRewardsV1200(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.20.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}
private fun BytecodePatchContext.applyAdsFreeRewardsV1210(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.21.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}
private fun BytecodePatchContext.applyAdsFreeRewardsV1220(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.22.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}
private fun BytecodePatchContext.applyAdsFreeRewardsV1300(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.30.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}
private fun BytecodePatchContext.applyAdsFreeRewardsV1310(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.31.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}
private fun BytecodePatchContext.applyAdsFreeRewardsV1320(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.32.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}
private fun BytecodePatchContext.applyAdsFreeRewardsV1330(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.33.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}
private fun BytecodePatchContext.applyAdsFreeRewardsV1340(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.34.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}
private fun BytecodePatchContext.applyAdsFreeRewardsV1380(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.38.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}
private fun BytecodePatchContext.applyAdsFreeRewardsV1400(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.40.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}
private fun BytecodePatchContext.applyAdsFreeRewardsV1410(logger: Logger, rewardStrategy: String?, instantReward: Boolean?, sdkCoverage: AdsSdkCoverage) {
    logger.info("Ads Free Rewards v1.41.0 selected")
    applyAdsFreeRewardsV1190(logger, rewardStrategy, instantReward, sdkCoverage)
}

/** Latest stable strategy shared by Control App Ads. */
internal fun BytecodePatchContext.applyLatestAdsFreeRewards(
    logger: Logger,
    rewardStrategy: String?,
    instantReward: Boolean?,
    sdkCoverage: AdsSdkCoverage = AdsSdkCoverage(),
) = applyAdsFreeRewardsV1320(logger, rewardStrategy, instantReward, sdkCoverage)
