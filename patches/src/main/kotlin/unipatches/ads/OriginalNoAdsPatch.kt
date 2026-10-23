package unipatches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import java.util.logging.Logger
import helpers.ads.*
import helpers.bytecode.*

private val originalNoAdsLogger = Logger.getLogger("unipatches.ads.NoAdsPatch")

private fun BytecodePatchContext.originalNoAdsInjectOrSkip(
    fingerprint: Fingerprint,
    instructions: String,
) {
    val method = fingerprint.methodOrNull ?: return
    if (method.implementation == null) {
        originalNoAdsLogger.warning(
            "No Ads: skipping ${fingerprint.name}: method has no implementation",
        )
        return
    }
    method.addInstructions(0, instructions)
}

private fun BytecodePatchContext.originalNoAdsReturnVoid(fingerprint: Fingerprint) =
    originalNoAdsInjectOrSkip(fingerprint, "return-void")

private fun BytecodePatchContext.originalNoAdsPatchVoid(fingerprint: Fingerprint): Int {
    val name = fingerprint.name ?: return 0
    val params = fingerprint.parameters ?: emptyList()
    val ret = fingerprint.returnType ?: return 0
    val target = fingerprint.definingClass

    // Fast path: exact match has implementation
    val exact = fingerprint.methodOrNull
    if (exact != null && exact.implementation != null) {
        exact.addInstructions(0, "return-void")
        originalNoAdsLogger.info("No Ads: blocked $name (exact 1 impl)")
        return 1
    }

    // Generic show() with no args is too broad to scan (every Dialog has show()V)
    if (target != null && params.isEmpty() && name == "show" && ret == "V") {
        return 0
    }
    if (target != null && params.isEmpty() && name == "show" && ret == "Z") {
        // Facebook show()Z also generic
        return 0
    }

    var patched = 0
    classDefForEach { classDef ->
        for (m in classDef.methods) {
            if (m.name != name) continue
            if (m.returnType != ret) continue
            if (m.parameterTypes.map { it.toString() } != params) continue
            if (m.implementation == null) continue
            if (target != null) {
                val def = m.definingClass
                // Tightened ad check: handle obfuscated packages (e.g., a.b.c) by also checking
                // method name and string pool for ad-related keywords
                val isAd = def.contains("ads") || def.contains("applovin") || def.contains("ironsource") || def.contains("unity3d") || def.contains("vungle") || def.contains("facebook") || def.contains("bytedance") || def.contains("google/android/gms/ads") || def.contains("huawei") || def.contains("mytarget") || def.contains("yandex") || def.contains("startapp") || def.contains("mopub") || def.contains("chartboost") || def.contains("inmobi") || def == target ||
                    m.implementation!!.instructions.any { insn ->
                        (insn as? ReferenceInstruction)?.reference?.toString()?.contains("ads", ignoreCase = true) == true
                    }
                if (!isAd) continue
            }
            val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@classDefForEach
            val mutableMethod = mutableClass.methods.find { it.name == name && it.returnType == ret && it.parameterTypes.map { p -> p.toString() } == params } ?: return@classDefForEach
            if (mutableMethod.implementation == null) return@classDefForEach
            mutableMethod.addInstructions(0, "return-void")
            patched++
        }
    }
    if (patched > 0) originalNoAdsLogger.info("No Ads: blocked $name (${patched} impl(s)) via scan")
    return patched
}

private fun BytecodePatchContext.originalNoAdsPatchReturnFalse(fingerprint: Fingerprint): Int {
    val name = fingerprint.name ?: return 0
    val params = fingerprint.parameters ?: emptyList()
    val ret = fingerprint.returnType ?: return 0
    val target = fingerprint.definingClass

    val exact = fingerprint.methodOrNull
    if (exact != null && exact.implementation != null) {
        if (exact.returnType != "Z" || (exact.implementation?.registerCount ?: 0) < 1) {
            originalNoAdsLogger.warning("No Ads: skipping $name: boolean method has no usable register")
            return 0
        }
        exact.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        originalNoAdsLogger.info("No Ads: forced $name -> false (exact 1 impl)")
        return 1
    }
    if (target != null && params.isEmpty() && name == "show") return 0

    var patched = 0
    classDefForEach { classDef ->
        for (m in classDef.methods) {
            if (m.name != name) continue
            if (m.returnType != ret) continue
            if (m.parameterTypes.map { it.toString() } != params) continue
            if (m.implementation == null) continue
            if (target != null) {
                val def = m.definingClass
                val isAd = def.contains("ads") || def.contains("applovin") || def.contains("ironsource") || def.contains("unity3d") || def.contains("vungle") || def.contains("facebook") || def.contains("bytedance") || def.contains("google/android/gms/ads") || def.contains("huawei") || def.contains("mytarget") || def.contains("yandex") || def.contains("startapp") || def.contains("mopub") || def.contains("chartboost") || def.contains("inmobi") || def == target ||
                    m.implementation!!.instructions.any { insn ->
                        (insn as? ReferenceInstruction)?.reference?.toString()?.contains("ads", ignoreCase = true) == true
                    }
                if (!isAd) continue
            }
            val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@classDefForEach
            val mutableMethod = mutableClass.methods.find { it.name == name && it.returnType == ret && it.parameterTypes.map { p -> p.toString() } == params } ?: return@classDefForEach
            if (mutableMethod.implementation == null) return@classDefForEach
            if (mutableMethod.returnType != "Z" || (mutableMethod.implementation?.registerCount ?: 0) < 1) {
                originalNoAdsLogger.warning("No Ads: skipping $name in ${mutableMethod.definingClass}: boolean method has no usable register")
                return@classDefForEach
            }
            mutableMethod.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
            patched++
        }
    }
    if (patched > 0) originalNoAdsLogger.info("No Ads: forced $name -> false (${patched} impl(s)) via scan")
    return patched
}

private fun BytecodePatchContext.originalNoAdsPatchWith(fingerprint: Fingerprint, smali: String): Int {
    val exact = fingerprint.methodOrNull
    if (exact != null && exact.implementation != null) {
        val rc = exact.implementation?.registerCount ?: 0
        if ((fingerprint === MaxInterstitialAdShowAdFingerprint || fingerprint === MaxAppOpenAdShowAdFingerprint || fingerprint === MaxRewardedAdShowAdFingerprint) && rc < 7) {
            val classDef = fingerprint.classDefOrNull
            if (classDef != null) {
                try {
                    val cloned = exact.cloneMutableAndPreserveParameters(classDef)
                    cloned.addInstructions(0, smali)
                    originalNoAdsLogger.info("No Ads: patched ${fingerprint.name} via clone (low regs $rc) in ${exact.definingClass}")
                    return 1
                } catch (e: Exception) {
                    originalNoAdsLogger.warning("No Ads: clone failed for ${fingerprint.name}: ${e.message}")
                }
            }
            originalNoAdsLogger.warning("No Ads: skipping ${fingerprint.name} in ${exact.definingClass}: register count $rc < 7")
            return 0
        }
        exact.addInstructions(0, smali)
        originalNoAdsLogger.info("No Ads: patched ${fingerprint.name} (exact 1 impl)")
        return 1
    }
    // fallback scan for these is risky (they need exact class reflection), skip
    return 0
}

@Suppress("unused")
val noAdsPatch = bytecodePatch(
    name = "No Ads (Experimental)",
    description = """
        Blocks ads by type. Pick what to block. For rewarded ads use Ads Free Rewards instead. Select this standalone patch instead of Control App Ads for the same app.

        Credits: Nai64Patches from Nai64.
    """.trimIndent(),
    default = false,
) {
    val preset by stringOption(
        key = "preset",
        default = "recommended",
        title = "Preset",
        description = "Quick setup: Recommended blocks most ads but keeps rewarded ads for Ads Free Rewards compatibility. Aggressive blocks everything including rewarded. Custom lets you pick below.",
        values = linkedMapOf(
            "Recommended (keep rewarded)" to "recommended",
            "Aggressive (block all)" to "aggressive",
            "Custom" to "custom",
        ),
    )
    val blockInterstitials by booleanOption(
        title = "Block Interstitials",
        default = true,
        key = "blockInterstitials",
        description = "Full-screen ads between levels or menus. Safe to block in most apps.",
    )
    val blockBanners by booleanOption(
        title = "Block Banners",
        default = true,
        key = "blockBanners",
        description = "Thin banners at top/bottom. Safe to block; rarely breaks layout.",
    )
    val blockAppOpen by booleanOption(
        title = "Block App Open",
        default = true,
        key = "blockAppOpen",
        description = "Ads on cold start. Block to skip the launch ad.",
    )
    val blockMRec by booleanOption(
        title = "Block MREC",
        default = true,
        key = "blockMRec",
        description = "Medium rectangles (300x250) inside feeds. Safe to block.",
    )
    val blockRewarded by booleanOption(
        title = "Block Rewarded",
        default = false,
        key = "blockRewarded",
        description = "Rewarded video  -  disable if you use Ads Free Rewards, otherwise progress gates may break. Enable only to fully remove rewarded ads.",
    )
    val blockNative by booleanOption(
        title = "Block Native",
        default = true,
        key = "blockNative",
        description = "Native ads blended into feeds/lists. Enable for cleaner feeds (may leave empty placeholders).",
    )

    execute {
        val detectionLogger = Logger.getLogger(this::class.java.name)

        // Apply preset logic
        val effectiveBlockInterstitials = when (preset) {
            "aggressive" -> true
            "recommended" -> true
            else -> blockInterstitials == true
        }
        val effectiveBlockBanners = when (preset) {
            "aggressive" -> true
            "recommended" -> true
            else -> blockBanners == true
        }
        val effectiveBlockAppOpen = when (preset) {
            "aggressive" -> true
            "recommended" -> true
            else -> blockAppOpen == true
        }
        val effectiveBlockMRec = when (preset) {
            "aggressive" -> true
            "recommended" -> true
            else -> blockMRec == true
        }
        val effectiveBlockRewarded = when (preset) {
            "aggressive" -> true
            "recommended" -> false
            else -> blockRewarded == true
        }
        val effectiveBlockNative = when (preset) {
            "aggressive" -> true
            "recommended" -> true
            else -> blockNative == true
        }

        if (!effectiveBlockRewarded && preset == "recommended") {
            detectionLogger.info("No Ads: Block Rewarded is off in Recommended preset  -  use Ads Free Rewards to keep rewarded progress working")
        }

        val hasMaxUnity = ShowInterstitialFingerprint.methodOrNull != null ||
            ShowAppOpenAdFingerprint.methodOrNull != null ||
            ShowBannerFingerprint.methodOrNull != null
        val hasNativeMax = MaxInterstitialAdShowAdFingerprint.methodOrNull != null ||
            MaxAppOpenAdShowAdFingerprint.methodOrNull != null ||
            MaxAdViewStartAutoRefreshFingerprint.methodOrNull != null
        val hasAdMob = AdMobInterstitialShowFingerprint.methodOrNull != null ||
            AdMobLegacyInterstitialShowFingerprint.methodOrNull != null ||
            AdMobAppOpenShowFingerprint.methodOrNull != null ||
            AdMobAppOpenLoadFingerprint.methodOrNull != null ||
            AdMobRewardedShowFingerprint.methodOrNull != null ||
            AdMobLegacyRewardedVideoShowFingerprint.methodOrNull != null
        val hasUnityAdsV3 = UnityAdsV3Show2ArgFingerprint.methodOrNull != null ||
            UnityAdsV3ShowOptionsFingerprint.methodOrNull != null
        val hasUnityAdsV4 = UnityAdsV4Show3ArgFingerprint.methodOrNull != null ||
            UnityAdsV4Show4ArgFingerprint.methodOrNull != null ||
            UnityRewardedAdShowFingerprint.methodOrNull != null
        val hasIronSource = IronSourceShowDemandOnlyInterstitialFingerprint.methodOrNull != null ||
            IronSourceShowDemandOnlyRewardedVideoFingerprint.methodOrNull != null ||
            IronSourceShowInterstitialFingerprint.methodOrNull != null ||
            IronSourceShowRewardedVideoFingerprint.methodOrNull != null
        val hasAppLovinLegacy = AppLovinInterstitialDialogShowFingerprint.methodOrNull != null ||
            AppLovinIncentivizedShow4ListenerFingerprint.methodOrNull != null ||
            AppLovinAdViewLoadNextAdFingerprint.methodOrNull != null
        val hasVungle = VungleBaseFullscreenAdLoadFingerprint.methodOrNull != null
        val hasFacebook = FacebookInterstitialAdShowFingerprint.methodOrNull != null ||
            FacebookRewardedVideoAdShowFingerprint.methodOrNull != null
        val hasPangle = PangleInterstitialShowFingerprint.methodOrNull != null ||
            PangleRewardedShowFingerprint.methodOrNull != null
        val hasMyTarget = MyTargetBaseInterstitialShowFingerprint.methodOrNull != null
        val hasYandexRewarded = YandexUnityRewardedWrapperShowFingerprint.methodOrNull != null
        val hasYandexInterstitial = YandexUnityInterstitialWrapperShowFingerprint.methodOrNull != null
        val hasLevelPlay = LevelPlayRewardedAdIsReadyFingerprint.methodOrNull != null ||
            IronSourceLevelPlayFullScreenShowAdFingerprint.methodOrNull != null
        val hasHuawei = HuaweiRewardAdIsLoadedFingerprint.methodOrNull != null ||
            HuaweiRewardAdShowFingerprint.methodOrNull != null ||
            HuaweiInterstitialAdShowFingerprint.methodOrNull != null
        val hasStartApp = StartAppAdShowFingerprint.methodOrNull != null
        val hasMoPub = MoPubInterstitialShowFingerprint.methodOrNull != null
        val hasChartboost = ChartboostShowInterstitialFingerprint.methodOrNull != null
        val hasInMobi = InMobiInterstitialShowFingerprint.methodOrNull != null
        val hasMintegral = MintegralInterstitialShowFingerprint.methodOrNull != null
        val hasAdMobNative = AdMobNativeAdViewFingerprint.methodOrNull != null || AdMobAdLoaderLoadFingerprint.methodOrNull != null
        val hasPangleNative = PangleNativeShowFingerprint.methodOrNull != null
        val hasVungleShow = VungleInterstitialShowFingerprint.methodOrNull != null || VungleRewardedShowFingerprint.methodOrNull != null
        val hasYandexDirect = YandexInterstitialAdLoadFingerprint.methodOrNull != null || YandexRewardedAdLoadFingerprint.methodOrNull != null

        if (
            !hasMaxUnity &&
            !hasNativeMax &&
            !hasAdMob &&
            !hasUnityAdsV3 &&
            !hasUnityAdsV4 &&
            !hasIronSource &&
            !hasAppLovinLegacy &&
            !hasVungle &&
            !hasFacebook &&
            !hasPangle &&
            !hasLevelPlay &&
            !hasMyTarget &&
            !hasYandexRewarded &&
            !hasYandexInterstitial &&
            !hasHuawei &&
            !hasStartApp &&
            !hasMoPub &&
            !hasChartboost &&
            !hasInMobi &&
            !hasMintegral &&
            !hasAdMobNative &&
            !hasPangleNative &&
            !hasVungleShow &&
            !hasYandexDirect
        ) {
            detectionLogger.warning(
                "Could not find supported ad SDK (MAX Unity, native MAX, AdMob, " +
                    "Unity Ads v3/v4, ironSource/LevelPlay, AppLovin, Vungle, " +
                    "Meta, Pangle, VK MyTarget, Yandex or Huawei Ads Kit). No changes applied. " +
                    "If this app shows ads but wasn't detected, please report the APK  -  it may use StartApp/MoPub/Chartboost/InMobi or a custom wrapper.",
            )
            return@execute
        } else {
            val found = buildList {
                if (hasMaxUnity) add("MAX Unity")
                if (hasNativeMax) add("native MAX")
                if (hasAdMob) add("AdMob")
                if (hasAdMobNative) add("AdMob Native")
                if (hasUnityAdsV3) add("Unity v3")
                if (hasUnityAdsV4) add("Unity v4/RewardedAd")
                if (hasIronSource) add("ironSource")
                if (hasAppLovinLegacy) add("AppLovin legacy")
                if (hasVungle) add("Vungle")
                if (hasVungleShow) add("Vungle show")
                if (hasFacebook) add("Meta")
                if (hasPangle) add("Pangle")
                if (hasPangleNative) add("Pangle Native")
                if (hasLevelPlay) add("LevelPlay")
                if (hasMyTarget) add("MyTarget")
                if (hasYandexRewarded) add("Yandex rewarded")
                if (hasYandexInterstitial) add("Yandex interstitial")
                if (hasYandexDirect) add("Yandex direct")
                if (hasHuawei) add("Huawei")
                if (hasStartApp) add("StartApp")
                if (hasMoPub) add("MoPub")
                if (hasChartboost) add("Chartboost")
                if (hasInMobi) add("InMobi")
                if (hasMintegral) add("Mintegral")
            }
            detectionLogger.info("No Ads: detected SDK(s): ${found.joinToString(", ")}")
        }

        // -- VK MyTarget / RuStore build --
        if (hasMyTarget && (effectiveBlockInterstitials || effectiveBlockRewarded)) {
            val myTargetChecks = buildString {
                if (effectiveBlockRewarded) {
                    appendLine("instance-of v0, p0, Lcom/my/target/ads/RewardedAd;")
                    appendLine("if-nez v0, :morphe_no_ads_mytarget_block")
                }
                if (effectiveBlockInterstitials) {
                    appendLine("instance-of v0, p0, Lcom/my/target/ads/InterstitialAd;")
                    appendLine("if-nez v0, :morphe_no_ads_mytarget_block")
                }
                appendLine("goto :morphe_no_ads_mytarget_continue")
                appendLine(":morphe_no_ads_mytarget_block")
                appendLine("return-void")
                appendLine(":morphe_no_ads_mytarget_continue")
            }
            originalNoAdsInjectOrSkip(MyTargetBaseInterstitialShowFingerprint, myTargetChecks.trim())
        }

        if (effectiveBlockRewarded) {
            originalNoAdsReturnVoid(YandexUnityRewardedWrapperShowFingerprint)
        }
        if (effectiveBlockInterstitials) {
            originalNoAdsReturnVoid(YandexUnityInterstitialWrapperShowFingerprint)
        }

        var totalPatched = 0

        // -- Huawei Ads Kit / Petal Ads --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchVoid(HuaweiInterstitialAdShowFingerprint)
        }
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchReturnFalse(HuaweiRewardAdIsLoadedFingerprint)
            totalPatched += originalNoAdsPatchVoid(HuaweiRewardAdShowFingerprint)
        }

        // -- MAX Unity wrapper --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchVoid(ShowInterstitialFingerprint)
        }
        if (effectiveBlockAppOpen) {
            totalPatched += originalNoAdsPatchVoid(ShowAppOpenAdFingerprint)
        }
        if (effectiveBlockBanners) {
            totalPatched += originalNoAdsPatchVoid(ShowBannerFingerprint)
            totalPatched += originalNoAdsPatchVoid(StartBannerAutoRefreshFingerprint)
        }
        if (effectiveBlockMRec) {
            totalPatched += originalNoAdsPatchVoid(ShowMRecFingerprint)
            totalPatched += originalNoAdsPatchVoid(StartMRecAutoRefreshFingerprint)
        }
        if (effectiveBlockNative) {
            // MAX native ads often use MaxAdView for native as well  -  block its refresh
            totalPatched += originalNoAdsPatchVoid(MaxAdViewStartAutoRefreshFingerprint)
        }

        // -- Native MAX (non-Unity) --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchWith(
                MaxInterstitialAdShowAdFingerprint,
                fireHiddenCallbacks("Lcom/applovin/mediation/ads/MaxInterstitialAd;"),
            )
        }
        if (effectiveBlockAppOpen) {
            totalPatched += originalNoAdsPatchWith(
                MaxAppOpenAdShowAdFingerprint,
                fireHiddenCallbacks("Lcom/applovin/mediation/ads/MaxAppOpenAd;"),
            )
        }
        if (effectiveBlockBanners || effectiveBlockMRec || effectiveBlockNative) {
            totalPatched += originalNoAdsPatchVoid(MaxAdViewStartAutoRefreshFingerprint)
        }

        // -- AdMob (Google Mobile Ads) --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchVoid(AdMobInterstitialShowFingerprint)
            totalPatched += originalNoAdsPatchVoid(AdMobLegacyInterstitialShowFingerprint)
        }
        if (effectiveBlockAppOpen) {
            totalPatched += originalNoAdsPatchVoid(AdMobAppOpenShowFingerprint)
            totalPatched += originalNoAdsPatchVoid(AdMobAppOpenLoadFingerprint)
        }
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchVoid(AdMobRewardedShowFingerprint)
            totalPatched += originalNoAdsPatchVoid(AdMobLegacyRewardedVideoShowFingerprint)
        }
        // Native ads often use AdMob NativeAdView  -  block its load if present
        // (AdMobNativeAdShowFingerprint not yet fingerprinted; handled via generic scan below)

        // -- Rewarded ads --
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchReturnFalse(IsRewardedAdReadyFingerprint)
            totalPatched += originalNoAdsPatchVoid(ShowRewardedAdFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(MaxRewardedAdIsReadyFingerprint)
            totalPatched += originalNoAdsPatchWith(
                MaxRewardedAdShowAdFingerprint,
                fireHiddenCallbacks("Lcom/applovin/mediation/ads/MaxRewardedAd;"),
            )
            totalPatched += originalNoAdsPatchReturnFalse(LevelPlayRewardedAdIsReadyFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(IronSourceUnityRewardedAdIsReadyFingerprint)
            totalPatched += originalNoAdsPatchVoid(IronSourceLevelPlayFullScreenShowAdFingerprint)
        }

        // -- Unity Ads v3 (legacy) and v4 / RewardedAd --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchVoid(UnityAdsV3Show2ArgFingerprint)
            totalPatched += originalNoAdsPatchVoid(UnityAdsV3ShowOptionsFingerprint)
        }
        // Unity Ads v4 exposes one shared show(...) method for multiple ad
        // formats. Blocking it for interstitials alone also breaks rewarded
        // flows. Preserve the shared method whenever rewarded ads are allowed
        // so Ads Free Rewards can still reach its completion callbacks.
        if (effectiveBlockInterstitials && effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchVoid(UnityAdsV4Show3ArgFingerprint)
            totalPatched += originalNoAdsPatchVoid(UnityAdsV4Show4ArgFingerprint)
        }
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchVoid(UnityRewardedAdShowFingerprint)
        }

        // -- ironSource (LevelPlay) public API --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchVoid(IronSourceShowDemandOnlyInterstitialFingerprint)
            totalPatched += originalNoAdsPatchVoid(IronSourceShowInterstitialFingerprint)
            totalPatched += originalNoAdsPatchVoid(IronSourceShowInterstitialActivityFingerprint)
            totalPatched += originalNoAdsPatchVoid(IronSourceShowInterstitialActivityPlacementFingerprint)
            totalPatched += originalNoAdsPatchVoid(IronSourceShowInterstitialPlacementFingerprint)
        }
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchVoid(IronSourceShowDemandOnlyRewardedVideoFingerprint)
            totalPatched += originalNoAdsPatchVoid(IronSourceShowRewardedVideoFingerprint)
            totalPatched += originalNoAdsPatchVoid(IronSourceShowRewardedVideoActivityFingerprint)
            totalPatched += originalNoAdsPatchVoid(IronSourceShowRewardedVideoActivityPlacementFingerprint)
            totalPatched += originalNoAdsPatchVoid(IronSourceShowRewardedVideoPlacementFingerprint)
        }

        // -- AppLovin legacy (direct SDK, non-MAX) --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchVoid(AppLovinInterstitialDialogShowFingerprint)
            totalPatched += originalNoAdsPatchVoid(AppLovinInterstitialDialogShowAndRenderFingerprint)
        }
        if (effectiveBlockBanners) {
            totalPatched += originalNoAdsPatchVoid(AppLovinAdViewLoadNextAdFingerprint)
        }
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchVoid(AppLovinIncentivizedShow4ListenerFingerprint)
            totalPatched += originalNoAdsPatchVoid(AppLovinIncentivizedShow5ListenerFingerprint)
        }
        // AppLovin Native (handled via MaxAdView if present)

        // -- Vungle --
        if (effectiveBlockInterstitials || effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchVoid(VungleBaseFullscreenAdLoadFingerprint)
        }

        // -- Meta Audience Network (facebook/ads) --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchReturnFalse(FacebookInterstitialAdShowFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(FacebookInterstitialAdShowConfigFingerprint)
        }
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchReturnFalse(FacebookRewardedVideoAdShowFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(FacebookRewardedVideoAdShowConfigFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(FacebookRewardedInterstitialShowFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(FacebookRewardedInterstitialShowConfigFingerprint)
        }

        // -- Pangle (bytedance) --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchVoid(PangleInterstitialShowFingerprint)
        }
        if (effectiveBlockAppOpen) {
            totalPatched += originalNoAdsPatchVoid(PangleAppOpenShowFingerprint)
        }
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchVoid(PangleRewardedShowFingerprint)
        }
        if (effectiveBlockNative) {
            totalPatched += originalNoAdsPatchVoid(PangleNativeShowFingerprint)
        }

        // -- Vungle show (beyond load) --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchVoid(VungleInterstitialShowFingerprint)
        }
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchVoid(VungleRewardedShowFingerprint)
        }

        // -- Huawei full (banner/native/splash) --
        if (effectiveBlockBanners) {
            totalPatched += originalNoAdsPatchVoid(HuaweiBannerAdLoadFingerprint)
        }
        if (effectiveBlockNative) {
            totalPatched += originalNoAdsPatchVoid(HuaweiNativeAdLoadFingerprint)
        }
        if (effectiveBlockAppOpen) {
            totalPatched += originalNoAdsPatchVoid(HuaweiSplashAdLoadFingerprint)
        }

        // -- Yandex direct --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchVoid(YandexInterstitialAdLoadFingerprint)
        }
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchVoid(YandexRewardedAdLoadFingerprint)
        }

        // -- AdMob Native --
        if (effectiveBlockNative || effectiveBlockBanners) {
            totalPatched += originalNoAdsPatchVoid(AdMobNativeAdViewFingerprint)
            totalPatched += originalNoAdsPatchVoid(AdMobAdLoaderLoadFingerprint)
        }

        // -- StartApp / MoPub / Chartboost / InMobi / Mintegral (obfuscated) --
        if (effectiveBlockInterstitials) {
            totalPatched += originalNoAdsPatchVoid(StartAppAdShowFingerprint)
            totalPatched += originalNoAdsPatchVoid(MoPubInterstitialShowFingerprint)
            totalPatched += originalNoAdsPatchVoid(ChartboostShowInterstitialFingerprint)
            totalPatched += originalNoAdsPatchVoid(InMobiInterstitialShowFingerprint)
            totalPatched += originalNoAdsPatchVoid(MintegralInterstitialShowFingerprint)
        }

        // Hide rewarded UI when rewarded blocked (inverse of Ads Free Rewards fake true)
        if (effectiveBlockRewarded) {
            totalPatched += originalNoAdsPatchReturnFalse(UnityAdsAdvertisementIsReadyFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(UnityAdsAdvertisementIsReadyPlacementFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(UnityAdsSdkIsReadyFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(IronSourceIsRewardedVideoAvailableFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(IronSourceIsInterstitialReadyFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(MaxInterstitialAdIsReadyFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(MaxAppOpenAdIsReadyFingerprint)
            totalPatched += originalNoAdsPatchReturnFalse(MaxRewardedAdIsReadyFingerprint)
        }

        // Generic audio DAI ads (Klassik Radio, etc.)  -  adsIdentityToken, cuepoints
        classDefForEach { classDef ->
            val tl = classDef.type.lowercase()
            if (!tl.contains("song") && !tl.contains("station") && !tl.contains("stream") && !tl.contains("ad")) return@classDefForEach
            if (tl.contains("okhttp") || tl.contains("androidx")) return@classDefForEach
            try {
                val mutableClass = mutableClassDefBy(classDef)
                for (method in mutableClass.methods) {
                    val n = method.name.lowercase()
                    val isAdToken = n.contains("adsidentitytoken") || n.contains("adsresponse") || n.contains("adsduration") || n.contains("cuepoints") || n.contains("adsid")
                    if (!isAdToken) continue
                    try {
                        if (method.returnType == "Ljava/lang/String;" && method.implementation != null) {
                            method.addInstructions(0, "const-string v0, \"\"\nreturn-object v0")
                            totalPatched++
                        } else if ((method.returnType.contains("List") || method.returnType.contains("Collection")) && method.implementation != null) {
                            method.addInstructions(0, "invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;\nmove-result-object v0\nreturn-object v0")
                            totalPatched++
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        if (totalPatched == 0) {
            detectionLogger.warning("No Ads: no patchable ad methods found for selected options. Try enabling more categories or the app uses an unsupported SDK (check log for detected SDKs).")
        } else {
            detectionLogger.info("No Ads: patched $totalPatched method(s) in total  -  preset: $preset, interstitials=$effectiveBlockInterstitials, banners=$effectiveBlockBanners, appOpen=$effectiveBlockAppOpen, mrec=$effectiveBlockMRec, rewarded=$effectiveBlockRewarded, native=$effectiveBlockNative")
        }
    }
}
