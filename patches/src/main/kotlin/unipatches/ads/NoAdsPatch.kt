package unipatches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.patch.stringsOption
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger
import helpers.ads.*
import helpers.bytecode.*

private val logger = Logger.getLogger("unipatches.ads.NoAdsPatch")

private fun BytecodePatchContext.injectOrSkip(
    fingerprint: Fingerprint,
    instructions: String,
) : Int {
    val method = fingerprint.methodOrNull ?: return 0
    if (method.implementation == null) {
        logger.warning(
            "No Ads: skipping ${fingerprint.name}: method has no implementation",
        )
        return 0
    }
    if (instructions.contains("v0") &&
        (method.implementation!!.registerCount - method.numberOfParameterRegisters) < 1
    ) {
        logger.warning("No Ads: skipping ${fingerprint.name}: the injected strategy needs a local register.")
        return 0
    }
    method.addInstructions(0, instructions)
    return 1
}

private fun BytecodePatchContext.returnVoid(fingerprint: Fingerprint) =
    injectOrSkip(fingerprint, "return-void")

private fun BytecodePatchContext.patchVoid(fingerprint: Fingerprint): Int {
    val name = fingerprint.name ?: return 0
    val params = fingerprint.parameters ?: emptyList()
    val ret = fingerprint.returnType ?: return 0
    val target = fingerprint.definingClass

    // Fast path: exact match has implementation
    val exact = fingerprint.methodOrNull
    if (exact != null && exact.implementation != null) {
        exact.addInstructions(0, "return-void")
        logger.info("No Ads: blocked $name (exact 1 impl)")
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
    if (patched > 0) logger.info("No Ads: blocked $name (${patched} impl(s)) via scan")
    return patched
}

private fun BytecodePatchContext.patchReturnFalse(fingerprint: Fingerprint): Int {
    val name = fingerprint.name ?: return 0
    val params = fingerprint.parameters ?: emptyList()
    val ret = fingerprint.returnType ?: return 0
    val target = fingerprint.definingClass

    val exact = fingerprint.methodOrNull
    if (exact != null && exact.implementation != null) {
        if (exact.returnType != "Z" || (exact.implementation?.registerCount ?: 0) < 1) {
            logger.warning("No Ads: skipping $name: boolean method has no usable register")
            return 0
        }
        exact.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        logger.info("No Ads: forced $name -> false (exact 1 impl)")
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
                logger.warning("No Ads: skipping $name in ${mutableMethod.definingClass}: boolean method has no usable register")
                return@classDefForEach
            }
            mutableMethod.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
            patched++
        }
    }
    if (patched > 0) logger.info("No Ads: forced $name -> false (${patched} impl(s)) via scan")
    return patched
}

private fun BytecodePatchContext.patchWith(fingerprint: Fingerprint, smali: String): Int {
    val exact = fingerprint.methodOrNull
    if (exact != null && exact.implementation != null) {
        val localRegisters = (exact.implementation?.registerCount ?: 0) - exact.numberOfParameterRegisters
        if ((fingerprint === MaxInterstitialAdShowAdFingerprint || fingerprint === MaxAppOpenAdShowAdFingerprint || fingerprint === MaxRewardedAdShowAdFingerprint) && localRegisters < 7) {
            logger.warning("No Ads: skipping ${fingerprint.name} in ${exact.definingClass}: fewer than 7 local registers are available for its callback patch.")
            return 0
        }
        exact.addInstructions(0, smali)
        logger.info("No Ads: patched ${fingerprint.name} (exact 1 impl)")
        return 1
    }
    // fallback scan for these is risky (they need exact class reflection), skip
    return 0
}

private val easyListHosts = setOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "admob.com",
    "amazon-adsystem.com", "adsrvr.org", "criteo.com", "taboola.com", "outbrain.com",
)
private val adGuardMobileHosts = setOf(
    "applovin.com", "unityads.unity3d.com", "ironsrc.com", "supersonicads.com",
    "vungle.com", "chartboost.com", "mopub.com", "inmobi.com", "adcolony.com",
    "tapjoy.com", "mintegral.com", "pangle.io", "startappservice.com",
)
private val oisdPrivacyHosts = setOf(
    "google-analytics.com", "app-measurement.com", "adjust.com", "appsflyer.com",
    "amplitude.com", "mixpanel.com", "segment.io", "sentry.io",
)

private fun extractHost(value: String): String? {
    val candidate = value.substringAfter("://", value).substringBefore('/').substringBefore(':').trim('.').lowercase()
    return candidate.takeIf { it.length in 3..253 && it.count { char -> char == '.' } >= 1 &&
        it.all { char -> char.isLetterOrDigit() || char == '.' || char == '-' } }
}

private fun parseFilterHosts(entries: Iterable<String>): Set<String> = entries.mapNotNull { entry ->
    entry.substringBefore('#').trim().takeIf { it.isNotEmpty() }?.let { line ->
        extractHost(line.substringAfter(' ').trim()) ?: extractHost(line)
    }
}.toSet()

private fun escapeSmaliString(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")

private fun BytecodePatchContext.redirectLiteralHosts(hosts: Set<String>, wildcard: Boolean, logger: Logger): Int {
    if (hosts.isEmpty()) return 0
    var replacements = 0
    classDefForEach { classDef ->
        val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@classDefForEach
        mutableClass.methods.forEach { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@forEach
            instructions.forEachIndexed { index, instruction ->
                val value = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string ?: return@forEachIndexed
                val host = extractHost(value) ?: return@forEachIndexed
                if (hosts.none { blocked -> host == blocked || (wildcard && host.endsWith(".$blocked")) }) return@forEachIndexed
                val register = (instruction as? OneRegisterInstruction)?.registerA ?: return@forEachIndexed
                val replacement = value.replace(host, "0.0.0.0", ignoreCase = true)
                method.replaceInstruction(index, "const-string v$register, \"${escapeSmaliString(replacement)}\"")
                replacements++
            }
        }
    }
    if (replacements > 0) logger.info("Control App Ads: redirected $replacements literal host string(s).")
    return replacements
}

@Suppress("unused")
val controlAppAdsPatch = bytecodePatch(
    name = "Control App Ads Patch (Experimental, Enhanced)",
    description = """
        A merged ad-control patch based on Nai64's No Ads and Ads Free Rewards patches, plus
        literal-host blocking inspired by Entree and Adobo. Block common ad formats, choose the
        SDKs to target, optionally grant ad rewards without an ad, and redirect matching literal
        ad/tracker hosts embedded in the APK.

        Host filters are built-in domain sets, not downloaded subscriptions. They only affect
        hosts stored as literal strings by the app; they cannot block dynamically generated hosts,
        encrypted traffic, or requests made entirely inside Google Play services.

        Compatibility: when combined with Disable Forced Online Checks, keep that patch's “Exclude
        ad SDK and networking code” option enabled; otherwise blocked ad hosts may be treated as
        online and retried. PairIP Firebase cleanup/removal can disable Firebase-backed reward,
        sign-in, billing, and attribution flows, which this patch cannot restore.

        Credits: Nai64Patches from Nai64; host-blocking approach inspired by Entree and Adobo.
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
    val adsFreeRewards by booleanOption(
        title = "Ads Free Rewards > Enable ad-free rewards",
        default = false,
        key = "adsFreeRewards",
        description = "Try to complete supported rewarded-ad flows without showing an ad. Keep Block rewarded ads disabled: this option needs the reward flow to remain reachable.",
    )
    val rewardStrategy by stringOption(
        title = "Ads Free Rewards > SDK strategy",
        default = "auto",
        key = "adsFreeRewardsStrategy",
        description = "Auto detects supported reward SDKs. Select one SDK only when Auto causes a problem in a specific app.",
        values = linkedMapOf("Automatic (recommended)" to "auto", "AppLovin MAX" to "max", "Unity Ads" to "unityAds", "ironSource / LevelPlay" to "ironSource", "RuStore / MyTarget" to "rustore", "Huawei Ads" to "huawei"),
    )
    val instantReward by booleanOption(
        title = "Ads Free Rewards > Grant reward immediately",
        default = true,
        key = "adsFreeRewardsInstant",
        description = "Grant the supported reward as soon as its ad flow is opened. Turn off only if an app expects the normal completion callback timing.",
    )
    val fakeAdAvailability by booleanOption(
        title = "Ads Free Rewards > Show unavailable reward buttons",
        default = true,
        key = "adsFreeRewardsAvailability",
        description = "Make supported SDK availability checks report an ad as ready, so games still show their reward button after ad blocking.",
    )
    val sdkMax by booleanOption(title = "Ad SDKs > AppLovin MAX", default = true, key = "adsSdkMax", description = "Patch AppLovin MAX Unity wrappers and native MAX ads.")
    val sdkAdMob by booleanOption(title = "Ad SDKs > Google Mobile Ads (AdMob)", default = true, key = "adsSdkAdMob", description = "Patch Google Mobile Ads, including common native-ad loaders.")
    val sdkUnity by booleanOption(title = "Ad SDKs > Unity Ads", default = true, key = "adsSdkUnity", description = "Patch Unity Ads v3/v4 interstitial and rewarded entry points.")
    val sdkIronSource by booleanOption(title = "Ad SDKs > ironSource / LevelPlay", default = true, key = "adsSdkIronSource", description = "Patch ironSource and LevelPlay ad entry points.")
    val sdkAppLovin by booleanOption(title = "Ad SDKs > AppLovin legacy", default = true, key = "adsSdkAppLovin", description = "Patch the older direct AppLovin SDK, separate from MAX.")
    val sdkVungle by booleanOption(title = "Ad SDKs > Vungle", default = true, key = "adsSdkVungle", description = "Patch Vungle fullscreen, interstitial, and rewarded ads.")
    val sdkMeta by booleanOption(title = "Ad SDKs > Meta Audience Network", default = true, key = "adsSdkMeta", description = "Patch Meta/Facebook Audience Network ads.")
    val sdkPangle by booleanOption(title = "Ad SDKs > Pangle", default = true, key = "adsSdkPangle", description = "Patch Pangle/ByteDance ads, including app-open and native formats.")
    val sdkHuawei by booleanOption(title = "Ad SDKs > Huawei Ads", default = true, key = "adsSdkHuawei", description = "Patch Huawei Ads Kit / Petal Ads formats.")
    val sdkYandex by booleanOption(title = "Ad SDKs > Yandex / MyTarget", default = true, key = "adsSdkYandex", description = "Patch Yandex Ads and VK MyTarget/RuStore ad entry points.")
    val sdkOther by booleanOption(title = "Ad SDKs > Other supported SDKs", default = true, key = "adsSdkOther", description = "Patch StartApp, MoPub, Chartboost, InMobi, and Mintegral when detected.")
    val easyListFilter by booleanOption(title = "Host filters > EasyList / uBlock ads", default = true, key = "adsFilterEasyList", description = "Redirect common advertising and ad-serving host literals. Recommended.")
    val adGuardFilter by booleanOption(title = "Host filters > AdGuard mobile ads", default = true, key = "adsFilterAdGuard", description = "Redirect common mobile ad-network host literals. Recommended.")
    val oisdFilter by booleanOption(title = "Host filters > OISD privacy", default = true, key = "adsFilterOisd", description = "Redirect a conservative set of analytics and tracking host literals. Recommended; disable if sign-in, telemetry-dependent features, or diagnostics fail.")
    val wildcardHosts by booleanOption(title = "Host filters > Match subdomains", default = true, key = "adsFilterWildcardHosts", description = "When enabled, an entry such as example.com also redirects ads.example.com and other subdomains. Disable for exact-host matching only.")
    val broadHeuristics by booleanOption(title = "Advanced > Enable broad audio-ad heuristics", default = false, key = "adsBroadAudioHeuristics", description = "Experimental: scan broadly named audio/stream classes for ad tokens. Disabled by default because obfuscated or unrelated app code can match.")
    val customFilterHosts by stringsOption(title = "Host filters > Custom host entries", default = emptyList(), key = "adsCustomFilterHosts", description = "Optional domains, URLs, or hosts-file lines to redirect. Add one entry per row. Examples: ads.example.com or 0.0.0.0 tracker.example.com. Entries also match subdomains.")

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
        var effectiveBlockRewarded = when (preset) {
            "aggressive" -> true
            "recommended" -> false
            else -> blockRewarded == true
        }
        val effectiveBlockNative = when (preset) {
            "aggressive" -> true
            "recommended" -> true
            else -> blockNative == true
        }

        if (adsFreeRewards == true && effectiveBlockRewarded) {
            effectiveBlockRewarded = false
            detectionLogger.info("Control App Ads: keeping rewarded flows enabled for Ads Free Rewards.")
        }

        if (!effectiveBlockRewarded && preset == "recommended") {
            detectionLogger.info("Control App Ads: Block rewarded is off in Recommended mode so reward flows remain usable.")
        }

        val hasMaxUnity = sdkMax == true && (ShowInterstitialFingerprint.methodOrNull != null ||
            ShowAppOpenAdFingerprint.methodOrNull != null ||
            ShowBannerFingerprint.methodOrNull != null)
        val hasNativeMax = sdkMax == true && (MaxInterstitialAdShowAdFingerprint.methodOrNull != null ||
            MaxAppOpenAdShowAdFingerprint.methodOrNull != null ||
            MaxAdViewStartAutoRefreshFingerprint.methodOrNull != null)
        val hasAdMob = sdkAdMob == true && (AdMobInterstitialShowFingerprint.methodOrNull != null ||
            AdMobLegacyInterstitialShowFingerprint.methodOrNull != null ||
            AdMobAppOpenShowFingerprint.methodOrNull != null ||
            AdMobAppOpenLoadFingerprint.methodOrNull != null ||
            AdMobRewardedShowFingerprint.methodOrNull != null ||
            AdMobLegacyRewardedVideoShowFingerprint.methodOrNull != null)
        val hasUnityAdsV3 = sdkUnity == true && (UnityAdsV3Show2ArgFingerprint.methodOrNull != null ||
            UnityAdsV3ShowOptionsFingerprint.methodOrNull != null)
        val hasUnityAdsV4 = sdkUnity == true && (UnityAdsV4Show3ArgFingerprint.methodOrNull != null ||
            UnityAdsV4Show4ArgFingerprint.methodOrNull != null ||
            UnityRewardedAdShowFingerprint.methodOrNull != null)
        val hasIronSource = sdkIronSource == true && (IronSourceShowDemandOnlyInterstitialFingerprint.methodOrNull != null ||
            IronSourceShowDemandOnlyRewardedVideoFingerprint.methodOrNull != null ||
            IronSourceShowInterstitialFingerprint.methodOrNull != null ||
            IronSourceShowRewardedVideoFingerprint.methodOrNull != null)
        val hasAppLovinLegacy = sdkAppLovin == true && (AppLovinInterstitialDialogShowFingerprint.methodOrNull != null ||
            AppLovinIncentivizedShow4ListenerFingerprint.methodOrNull != null ||
            AppLovinAdViewLoadNextAdFingerprint.methodOrNull != null)
        val hasVungle = sdkVungle == true && VungleBaseFullscreenAdLoadFingerprint.methodOrNull != null
        val hasFacebook = sdkMeta == true && (FacebookInterstitialAdShowFingerprint.methodOrNull != null ||
            FacebookRewardedVideoAdShowFingerprint.methodOrNull != null)
        val hasPangle = sdkPangle == true && (PangleInterstitialShowFingerprint.methodOrNull != null ||
            PangleRewardedShowFingerprint.methodOrNull != null)
        val hasMyTarget = sdkYandex == true && MyTargetBaseInterstitialShowFingerprint.methodOrNull != null
        val hasYandexRewarded = sdkYandex == true && YandexUnityRewardedWrapperShowFingerprint.methodOrNull != null
        val hasYandexInterstitial = sdkYandex == true && YandexUnityInterstitialWrapperShowFingerprint.methodOrNull != null
        val hasLevelPlay = sdkIronSource == true && (LevelPlayRewardedAdIsReadyFingerprint.methodOrNull != null ||
            IronSourceLevelPlayFullScreenShowAdFingerprint.methodOrNull != null)
        val hasHuawei = sdkHuawei == true && (HuaweiRewardAdIsLoadedFingerprint.methodOrNull != null ||
            HuaweiRewardAdShowFingerprint.methodOrNull != null ||
            HuaweiInterstitialAdShowFingerprint.methodOrNull != null)
        val hasStartApp = sdkOther == true && StartAppAdShowFingerprint.methodOrNull != null
        val hasMoPub = sdkOther == true && MoPubInterstitialShowFingerprint.methodOrNull != null
        val hasChartboost = sdkOther == true && ChartboostShowInterstitialFingerprint.methodOrNull != null
        val hasInMobi = sdkOther == true && InMobiInterstitialShowFingerprint.methodOrNull != null
        val hasMintegral = sdkOther == true && MintegralInterstitialShowFingerprint.methodOrNull != null
        val hasAdMobNative = sdkAdMob == true && (AdMobNativeAdViewFingerprint.methodOrNull != null || AdMobAdLoaderLoadFingerprint.methodOrNull != null)
        val hasPangleNative = sdkPangle == true && PangleNativeShowFingerprint.methodOrNull != null
        val hasVungleShow = sdkVungle == true && (VungleInterstitialShowFingerprint.methodOrNull != null || VungleRewardedShowFingerprint.methodOrNull != null)
        val hasYandexDirect = sdkYandex == true && (YandexInterstitialAdLoadFingerprint.methodOrNull != null || YandexRewardedAdLoadFingerprint.methodOrNull != null)

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
            detectionLogger.info("Control App Ads: no selected SDK fingerprint matched; host filters and Ads Free Rewards will still be evaluated.")
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

        var totalPatched = 0

        // -- VK MyTarget / RuStore build --
        if (sdkYandex == true && hasMyTarget && (effectiveBlockInterstitials || effectiveBlockRewarded)) {
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
            totalPatched += injectOrSkip(MyTargetBaseInterstitialShowFingerprint, myTargetChecks.trim())
        }

        if (sdkYandex == true && effectiveBlockRewarded) {
            totalPatched += returnVoid(YandexUnityRewardedWrapperShowFingerprint)
        }
        if (sdkYandex == true && effectiveBlockInterstitials) {
            totalPatched += returnVoid(YandexUnityInterstitialWrapperShowFingerprint)
        }

        // -- Huawei Ads Kit / Petal Ads --
        if (sdkHuawei == true && effectiveBlockInterstitials) {
            totalPatched += patchVoid(HuaweiInterstitialAdShowFingerprint)
        }
        if (sdkHuawei == true && effectiveBlockRewarded) {
            totalPatched += patchReturnFalse(HuaweiRewardAdIsLoadedFingerprint)
            totalPatched += patchVoid(HuaweiRewardAdShowFingerprint)
        }

        // -- MAX Unity wrapper --
        if (sdkMax == true && effectiveBlockInterstitials) {
            totalPatched += patchVoid(ShowInterstitialFingerprint)
        }
        if (sdkMax == true && effectiveBlockAppOpen) {
            totalPatched += patchVoid(ShowAppOpenAdFingerprint)
        }
        if (sdkMax == true && effectiveBlockBanners) {
            totalPatched += patchVoid(ShowBannerFingerprint)
            totalPatched += patchVoid(StartBannerAutoRefreshFingerprint)
        }
        if (sdkMax == true && effectiveBlockMRec) {
            totalPatched += patchVoid(ShowMRecFingerprint)
            totalPatched += patchVoid(StartMRecAutoRefreshFingerprint)
        }
        if (sdkMax == true && effectiveBlockNative) {
            // MAX native ads often use MaxAdView for native as well  -  block its refresh
            totalPatched += patchVoid(MaxAdViewStartAutoRefreshFingerprint)
        }

        // -- Native MAX (non-Unity) --
        if (sdkMax == true && effectiveBlockInterstitials) {
            totalPatched += patchWith(
                MaxInterstitialAdShowAdFingerprint,
                fireHiddenCallbacks("Lcom/applovin/mediation/ads/MaxInterstitialAd;"),
            )
        }
        if (sdkMax == true && effectiveBlockAppOpen) {
            totalPatched += patchWith(
                MaxAppOpenAdShowAdFingerprint,
                fireHiddenCallbacks("Lcom/applovin/mediation/ads/MaxAppOpenAd;"),
            )
        }
        if (sdkMax == true && (effectiveBlockBanners || effectiveBlockMRec || effectiveBlockNative)) {
            totalPatched += patchVoid(MaxAdViewStartAutoRefreshFingerprint)
        }

        // -- AdMob (Google Mobile Ads) --
        if (sdkAdMob == true && effectiveBlockInterstitials) {
            totalPatched += patchVoid(AdMobInterstitialShowFingerprint)
            totalPatched += patchVoid(AdMobLegacyInterstitialShowFingerprint)
        }
        if (sdkAdMob == true && effectiveBlockAppOpen) {
            totalPatched += patchVoid(AdMobAppOpenShowFingerprint)
            totalPatched += patchVoid(AdMobAppOpenLoadFingerprint)
        }
        if (sdkAdMob == true && effectiveBlockRewarded) {
            totalPatched += patchVoid(AdMobRewardedShowFingerprint)
            totalPatched += patchVoid(AdMobLegacyRewardedVideoShowFingerprint)
        }
        // Native ads often use AdMob NativeAdView  -  block its load if present
        // (AdMobNativeAdShowFingerprint not yet fingerprinted; handled via generic scan below)

        // -- Rewarded ads --
        if (sdkMax == true && effectiveBlockRewarded) {
            totalPatched += patchReturnFalse(IsRewardedAdReadyFingerprint)
            totalPatched += patchVoid(ShowRewardedAdFingerprint)
            totalPatched += patchReturnFalse(MaxRewardedAdIsReadyFingerprint)
            totalPatched += patchWith(
                MaxRewardedAdShowAdFingerprint,
                fireHiddenCallbacks("Lcom/applovin/mediation/ads/MaxRewardedAd;"),
            )
            totalPatched += patchReturnFalse(LevelPlayRewardedAdIsReadyFingerprint)
            totalPatched += patchReturnFalse(IronSourceUnityRewardedAdIsReadyFingerprint)
            totalPatched += patchVoid(IronSourceLevelPlayFullScreenShowAdFingerprint)
        }

        // -- Unity Ads v3 (legacy) and v4 / RewardedAd --
        if (sdkUnity == true && effectiveBlockInterstitials) {
            totalPatched += patchVoid(UnityAdsV3Show2ArgFingerprint)
            totalPatched += patchVoid(UnityAdsV3ShowOptionsFingerprint)
        }
        // Unity Ads v4 exposes one shared show(...) method for multiple ad
        // formats. Blocking it for interstitials alone also breaks rewarded
        // flows. Preserve the shared method whenever rewarded ads are allowed
        // so Ads Free Rewards can still reach its completion callbacks.
        if (sdkUnity == true && effectiveBlockInterstitials && effectiveBlockRewarded) {
            totalPatched += patchVoid(UnityAdsV4Show3ArgFingerprint)
            totalPatched += patchVoid(UnityAdsV4Show4ArgFingerprint)
        }
        if (sdkUnity == true && effectiveBlockRewarded) {
            totalPatched += patchVoid(UnityRewardedAdShowFingerprint)
        }

        // -- ironSource (LevelPlay) public API --
        if (sdkIronSource == true && effectiveBlockInterstitials) {
            totalPatched += patchVoid(IronSourceShowDemandOnlyInterstitialFingerprint)
            totalPatched += patchVoid(IronSourceShowInterstitialFingerprint)
            totalPatched += patchVoid(IronSourceShowInterstitialActivityFingerprint)
            totalPatched += patchVoid(IronSourceShowInterstitialActivityPlacementFingerprint)
            totalPatched += patchVoid(IronSourceShowInterstitialPlacementFingerprint)
        }
        if (sdkIronSource == true && effectiveBlockRewarded) {
            totalPatched += patchVoid(IronSourceShowDemandOnlyRewardedVideoFingerprint)
            totalPatched += patchVoid(IronSourceShowRewardedVideoFingerprint)
            totalPatched += patchVoid(IronSourceShowRewardedVideoActivityFingerprint)
            totalPatched += patchVoid(IronSourceShowRewardedVideoActivityPlacementFingerprint)
            totalPatched += patchVoid(IronSourceShowRewardedVideoPlacementFingerprint)
        }

        // -- AppLovin legacy (direct SDK, non-MAX) --
        if (sdkAppLovin == true && effectiveBlockInterstitials) {
            totalPatched += patchVoid(AppLovinInterstitialDialogShowFingerprint)
            totalPatched += patchVoid(AppLovinInterstitialDialogShowAndRenderFingerprint)
        }
        if (sdkAppLovin == true && effectiveBlockBanners) {
            totalPatched += patchVoid(AppLovinAdViewLoadNextAdFingerprint)
        }
        if (sdkAppLovin == true && effectiveBlockRewarded) {
            totalPatched += patchVoid(AppLovinIncentivizedShow4ListenerFingerprint)
            totalPatched += patchVoid(AppLovinIncentivizedShow5ListenerFingerprint)
        }
        // AppLovin Native (handled via MaxAdView if present)

        // -- Vungle --
        if (sdkVungle == true && (effectiveBlockInterstitials || effectiveBlockRewarded)) {
            totalPatched += patchVoid(VungleBaseFullscreenAdLoadFingerprint)
        }

        // -- Meta Audience Network (facebook/ads) --
        if (sdkMeta == true && effectiveBlockInterstitials) {
            totalPatched += patchReturnFalse(FacebookInterstitialAdShowFingerprint)
            totalPatched += patchReturnFalse(FacebookInterstitialAdShowConfigFingerprint)
        }
        if (sdkMeta == true && effectiveBlockRewarded) {
            totalPatched += patchReturnFalse(FacebookRewardedVideoAdShowFingerprint)
            totalPatched += patchReturnFalse(FacebookRewardedVideoAdShowConfigFingerprint)
            totalPatched += patchReturnFalse(FacebookRewardedInterstitialShowFingerprint)
            totalPatched += patchReturnFalse(FacebookRewardedInterstitialShowConfigFingerprint)
        }

        // -- Pangle (bytedance) --
        if (sdkPangle == true && effectiveBlockInterstitials) {
            totalPatched += patchVoid(PangleInterstitialShowFingerprint)
        }
        if (sdkPangle == true && effectiveBlockAppOpen) {
            totalPatched += patchVoid(PangleAppOpenShowFingerprint)
        }
        if (sdkPangle == true && effectiveBlockRewarded) {
            totalPatched += patchVoid(PangleRewardedShowFingerprint)
        }
        if (sdkPangle == true && effectiveBlockNative) {
            totalPatched += patchVoid(PangleNativeShowFingerprint)
        }

        // -- Vungle show (beyond load) --
        if (sdkVungle == true && effectiveBlockInterstitials) {
            totalPatched += patchVoid(VungleInterstitialShowFingerprint)
        }
        if (sdkVungle == true && effectiveBlockRewarded) {
            totalPatched += patchVoid(VungleRewardedShowFingerprint)
        }

        // -- Huawei full (banner/native/splash) --
        if (sdkHuawei == true && effectiveBlockBanners) {
            totalPatched += patchVoid(HuaweiBannerAdLoadFingerprint)
        }
        if (sdkHuawei == true && effectiveBlockNative) {
            totalPatched += patchVoid(HuaweiNativeAdLoadFingerprint)
        }
        if (sdkHuawei == true && effectiveBlockAppOpen) {
            totalPatched += patchVoid(HuaweiSplashAdLoadFingerprint)
        }

        // -- Yandex direct --
        if (sdkYandex == true && effectiveBlockInterstitials) {
            totalPatched += patchVoid(YandexInterstitialAdLoadFingerprint)
        }
        if (sdkYandex == true && effectiveBlockRewarded) {
            totalPatched += patchVoid(YandexRewardedAdLoadFingerprint)
        }

        // -- AdMob Native --
        if (sdkAdMob == true && (effectiveBlockNative || effectiveBlockBanners)) {
            totalPatched += patchVoid(AdMobNativeAdViewFingerprint)
            totalPatched += patchVoid(AdMobAdLoaderLoadFingerprint)
        }

        // -- StartApp / MoPub / Chartboost / InMobi / Mintegral (obfuscated) --
        if (sdkOther == true && effectiveBlockInterstitials) {
            totalPatched += patchVoid(StartAppAdShowFingerprint)
            totalPatched += patchVoid(MoPubInterstitialShowFingerprint)
            totalPatched += patchVoid(ChartboostShowInterstitialFingerprint)
            totalPatched += patchVoid(InMobiInterstitialShowFingerprint)
            totalPatched += patchVoid(MintegralInterstitialShowFingerprint)
        }

        // Hide rewarded UI when rewarded blocked (inverse of Ads Free Rewards fake true)
        if (effectiveBlockRewarded) {
            totalPatched += patchReturnFalse(UnityAdsAdvertisementIsReadyFingerprint)
            totalPatched += patchReturnFalse(UnityAdsAdvertisementIsReadyPlacementFingerprint)
            totalPatched += patchReturnFalse(UnityAdsSdkIsReadyFingerprint)
            totalPatched += patchReturnFalse(IronSourceIsRewardedVideoAvailableFingerprint)
            totalPatched += patchReturnFalse(IronSourceIsInterstitialReadyFingerprint)
            totalPatched += patchReturnFalse(MaxInterstitialAdIsReadyFingerprint)
            totalPatched += patchReturnFalse(MaxAppOpenAdIsReadyFingerprint)
            totalPatched += patchReturnFalse(MaxRewardedAdIsReadyFingerprint)
        }

        // Generic audio DAI ads (Klassik Radio, etc.)  -  adsIdentityToken, cuepoints.
        if (broadHeuristics == true) classDefForEach { classDef ->
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

        if (adsFreeRewards == true) {
            applyLatestAdsFreeRewards(detectionLogger, rewardStrategy, instantReward)
            if (fakeAdAvailability == true) {
                totalPatched += forceAdAvailability(detectionLogger, rewardStrategy)
            }
        }

        val filterHosts = buildSet {
            if (easyListFilter == true) addAll(easyListHosts)
            if (adGuardFilter == true) addAll(adGuardMobileHosts)
            if (oisdFilter == true) addAll(oisdPrivacyHosts)
            addAll(parseFilterHosts(customFilterHosts.orEmpty()))
        }
        totalPatched += redirectLiteralHosts(filterHosts, wildcardHosts == true, detectionLogger)

        if (totalPatched == 0) {
            detectionLogger.warning("Control App Ads: no selected literal host or patchable ad method was found. The app may use an unsupported SDK, dynamically generated endpoints, or encrypted configuration.")
        } else {
            detectionLogger.info("Control App Ads: changed $totalPatched item(s)  -  preset: $preset, interstitials=$effectiveBlockInterstitials, banners=$effectiveBlockBanners, appOpen=$effectiveBlockAppOpen, mrec=$effectiveBlockMRec, rewarded=$effectiveBlockRewarded, native=$effectiveBlockNative")
        }
    }
}
