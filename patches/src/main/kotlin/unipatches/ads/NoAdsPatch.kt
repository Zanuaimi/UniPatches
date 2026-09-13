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
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import java.util.Locale
import java.util.logging.Logger
import helpers.ads.*
import helpers.bytecode.*
import unipatches.overlay.OverlayAdsRuntimeIntegration
import unipatches.overlay.attachQueuedAdsRuntimePolicy

private val logger = Logger.getLogger("unipatches.ads.NoAdsPatch")
private const val ADS_POLICY_CLASS = "Lunipatch/overlaycore/AdsRuntimePolicy;"
private var runtimeHooksEnabled = false
private var runtimeHostsEnabled = false
private var runtimeCategoryByFingerprint: Map<Fingerprint, String> = emptyMap()

private enum class AdsFallbackKind {
    VOID,
    RETURN_FALSE,
}

private data class AdsFallbackKey(
    val name: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val target: String?,
)

private data class AdsFallbackOperation(
    val kind: AdsFallbackKind,
    val fingerprint: Fingerprint,
    val key: AdsFallbackKey,
)

private data class AdsFallbackMatch(
    val classType: String,
    val methodName: String,
    val returnType: String,
    val parameterTypes: List<String>,
)

private data class AdsGuardMethodKey(
    val classType: String,
    val methodName: String,
    val returnType: String,
    val parameterTypes: List<String>,
)

private val adsFallbackOperations = mutableListOf<AdsFallbackOperation>()
private val adsFallbackMutableClasses = mutableMapOf<String, MutableClass>()
private val runtimeGuardedMethods = mutableSetOf<AdsGuardMethodKey>()

private fun resetAdsFallbackIndex() {
    adsFallbackOperations.clear()
    adsFallbackMutableClasses.clear()
    runtimeGuardedMethods.clear()
}

private val knownAdsPackagePrefixes = listOf(
    "com/google/android/gms/ads/",
    "com/google/ads/mediation/",
    "com/applovin/",
    "com/ironsource/",
    "com/unity3d/ads/",
    "com/unity3d/mediation/",
    "com/unity3d/ironsourceads/",
    "com/vungle/",
    "com/facebook/ads/",
    "com/bytedance/",
    "com/huawei/hms/ads/",
    "com/my/target/",
    "com/yandex/mobile/ads/",
    "com/startapp/",
    "com/mopub/",
    "com/chartboost/",
    "com/inmobi/",
    "com/mbridge/msdk/",
    "com/mintegral/",
    "net/pubnative/",
)

private val excludedNonAdPackagePrefixes = listOf(
    "com/mbridge/msdk/playercommon/",
    "com/google/android/exoplayer2/",
    "androidx/media3/",
    "com/unity3d/services/store/",
    "com/android/billingclient/",
    "com/android/vending/billing/",
)

private fun normalizedClassPath(type: String): String = type
    .removePrefix("L")
    .removeSuffix(";")
    .lowercase(Locale.ROOT)

internal fun isKnownAdsClass(type: String): Boolean {
    val path = normalizedClassPath(type)
    return knownAdsPackagePrefixes.any(path::startsWith) &&
        excludedNonAdPackagePrefixes.none(path::startsWith)
}

internal fun isAdsDefiningClass(type: String, target: String?): Boolean =
    (target != null && type == target) || (target == null && isKnownAdsClass(type))

internal fun hasKnownAdsReference(reference: String): Boolean =
    excludedNonAdPackagePrefixes.none { prefix -> normalizedClassPath(reference).contains(prefix) } &&
        knownAdsPackagePrefixes.any { prefix ->
            normalizedClassPath(reference).contains(prefix)
        }

/**
 * Selects a safe fallback candidate. A fingerprint with a defining class is exact by design;
 * reference scanning is reserved for fingerprints that intentionally identify obfuscated SDK
 * methods by their body strings.
 */
internal fun isAdsFallbackCandidate(
    classType: String,
    target: String?,
    hasAdsReference: Boolean,
): Boolean = if (target != null) {
    classType == target
} else {
    isKnownAdsClass(classType) || hasAdsReference
}

private fun guardMethodKey(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) =
    AdsGuardMethodKey(
        classType = method.definingClass,
        methodName = method.name,
        returnType = method.returnType,
        parameterTypes = method.parameterTypes.map { it.toString() },
    )

private fun hasRuntimePolicyGuard(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod): Boolean =
    method.implementation?.instructions?.any { instruction ->
        instruction.toString().contains("AdsRuntimePolicy;->")
    } == true

private fun BytecodePatchContext.flushAdsFallbackOperations(logger: Logger): Int {
    if (adsFallbackOperations.isEmpty()) return 0
    val operationsByKey = adsFallbackOperations.groupBy { it.key }
    val matchesByKey = operationsByKey.keys.associateWith { mutableListOf<AdsFallbackMatch>() }
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.implementation == null) return@forEach
            operationsByKey.forEach { (key, _) ->
                if (method.name != key.name || method.returnType != key.returnType) return@forEach
                if (method.parameterTypes.size != key.parameterTypes.size ||
                    method.parameterTypes.indices.any { index -> method.parameterTypes[index].toString() != key.parameterTypes[index] }
                ) return@forEach
                if (!isAdsFallbackCandidate(
                        classType = method.definingClass,
                        target = key.target,
                        hasAdsReference = method.implementation!!.instructions.any { instruction ->
                            (instruction as? ReferenceInstruction)?.reference?.toString()?.let(::hasKnownAdsReference) == true
                        },
                    )
                ) {
                    return@forEach
                }
                matchesByKey.getValue(key) += AdsFallbackMatch(
                    classType = classDef.type,
                    methodName = method.name,
                    returnType = method.returnType,
                    parameterTypes = method.parameterTypes.map { it.toString() },
                )
            }
        }
    }

    var patched = 0
    val operationsByClass = adsFallbackOperations.groupBy { it.key }
    matchesByKey.forEach { (key, matches) ->
        val operations = operationsByClass.getValue(key)
        matches.groupBy { it.classType }.forEach { (classType, classMatches) ->
            val mutableClass = cachedAdsMutableClass(classType) ?: return@forEach
            classMatches.forEach { match ->
                val mutableMethod = mutableClass.methods.firstOrNull {
                    it.name == match.methodName && it.returnType == match.returnType &&
                        it.parameterTypes.map { parameter -> parameter.toString() } == match.parameterTypes
                } ?: return@forEach
                if (mutableMethod.implementation == null) return@forEach
                // Several fingerprints can intentionally share a signature. Prefer the
                // runtime operation when present, and apply at most one operation to a method.
                // This prevents a permanent return from being placed in front of a runtime guard
                // and prevents duplicate guards from changing startup control flow.
                val operation = operations.firstOrNull { runtimeCategoryByFingerprint[it.fingerprint] != null }
                    ?: operations.firstOrNull()
                    ?: return@forEach
                val runtimeCategory = runtimeCategoryByFingerprint[operation.fingerprint]
                val methodKey = guardMethodKey(mutableMethod)
                if (runtimeCategory != null &&
                    (methodKey in runtimeGuardedMethods || hasRuntimePolicyGuard(mutableMethod))
                ) {
                    logger.warning("No Ads: skipped duplicate runtime guard in ${mutableMethod.definingClass}->${mutableMethod.name}")
                    return@forEach
                }
                when (operation.kind) {
                    AdsFallbackKind.VOID -> {
                        if (runtimeCategory != null) {
                            val guard = runtimeGuard(runtimeCategory) ?: return@forEach
                            if (mutableMethod.implementation!!.registerCount - mutableMethod.numberOfParameterRegisters < 1) return@forEach
                            mutableMethod.addInstructions(0, guard)
                        } else {
                            mutableMethod.addInstructions(0, "return-void")
                        }
                        if (runtimeCategory != null) runtimeGuardedMethods += methodKey
                        patched++
                    }
                    AdsFallbackKind.RETURN_FALSE -> {
                        if (mutableMethod.returnType != "Z" || mutableMethod.implementation!!.registerCount < 1) {
                            logger.warning("No Ads: skipping ${operation.fingerprint.name} in ${mutableMethod.definingClass}: boolean method has no usable register")
                            return@forEach
                        }
                        if (runtimeCategory != null) {
                            val guard = runtimeGuard(runtimeCategory) ?: return@forEach
                            if (mutableMethod.implementation!!.registerCount - mutableMethod.numberOfParameterRegisters < 1) return@forEach
                            mutableMethod.addInstructions(0, guard.replace("return-void", booleanReturnInstructions(false)))
                        } else {
                            mutableMethod.addInstructions(0, booleanReturnInstructions(false))
                        }
                        if (runtimeCategory != null) runtimeGuardedMethods += methodKey
                        patched++
                    }
                }
            }
        }
    }
    adsFallbackOperations.forEach { operation ->
        if (patched > 0) logger.info("No Ads: applied queued ${operation.kind.name.lowercase()} fallback operations")
    }
    return patched
}

private fun enqueueAdsFallbackOperation(
    kind: AdsFallbackKind,
    fingerprint: Fingerprint,
    name: String,
    returnType: String,
    parameterTypes: List<String>,
    target: String?,
) {
    adsFallbackOperations += AdsFallbackOperation(
        kind = kind,
        fingerprint = fingerprint,
        key = AdsFallbackKey(name, returnType, parameterTypes, target),
    )
}

private fun BytecodePatchContext.cachedAdsMutableClass(classType: String): MutableClass? {
    adsFallbackMutableClasses[classType]?.let { return it }
    return mutableClassDefByOrNull(classType)?.also { adsFallbackMutableClasses[classType] = it }
}

private fun runtimeGuard(category: String): String? {
    if (!runtimeHooksEnabled) return null
    val method = when (category) {
        "interstitials" -> "shouldBlockInterstitials"
        "banners" -> "shouldBlockBanners"
        "appOpen" -> "shouldBlockAppOpen"
        "mrec" -> "shouldBlockMrec"
        "rewarded" -> "shouldBlockRewarded"
        "rewardedAvailability" -> "shouldBlockRewardedFormat"
        "native" -> "shouldBlockNative"
        "shared" -> return """
            invoke-static {}, $ADS_POLICY_CLASS->shouldBlockInterstitials()Z
            move-result v0
            if-nez v0, :unipatch_ads_runtime_shared_block
            invoke-static {}, $ADS_POLICY_CLASS->shouldBlockRewarded()Z
            move-result v0
            if-eqz v0, :unipatch_ads_runtime_shared_continue
            :unipatch_ads_runtime_shared_block
            return-void
            :unipatch_ads_runtime_shared_continue
        """.trimIndent()
        else -> return null
    }
    return """
        invoke-static {}, $ADS_POLICY_CLASS->$method()Z
        move-result v0
        if-eqz v0, :unipatch_ads_runtime_continue
        return-void
        :unipatch_ads_runtime_continue
    """.trimIndent()
}

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
    val effectiveInstructions = runtimeCategoryByFingerprint[fingerprint]?.let { runtimeGuard(it) } ?: instructions
    val methodKey = guardMethodKey(method)
    if (effectiveInstructions.contains("AdsRuntimePolicy;->") &&
        (methodKey in runtimeGuardedMethods || hasRuntimePolicyGuard(method))
    ) {
        logger.warning("No Ads: skipped duplicate runtime guard in ${method.definingClass}->${method.name}")
        return 0
    }
    if (effectiveInstructions.contains("v0") &&
        (method.implementation!!.registerCount - method.numberOfParameterRegisters) < 1
    ) {
        logger.warning("No Ads: skipping ${fingerprint.name}: the injected strategy needs a local register.")
        return 0
    }
    method.addInstructions(0, effectiveInstructions)
    if (effectiveInstructions.contains("AdsRuntimePolicy;->")) runtimeGuardedMethods += methodKey
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
        runtimeCategoryByFingerprint[fingerprint]?.let { category ->
            return injectOrSkip(fingerprint, runtimeGuard(category) ?: return@let 0)
        }
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

    enqueueAdsFallbackOperation(
        kind = AdsFallbackKind.VOID,
        fingerprint = fingerprint,
        name = name,
        returnType = ret,
        parameterTypes = params,
        target = target,
    )
    return 0
}

private fun BytecodePatchContext.patchReturnFalse(fingerprint: Fingerprint): Int {
    val name = fingerprint.name ?: return 0
    val params = fingerprint.parameters ?: emptyList()
    val ret = fingerprint.returnType ?: return 0
    val target = fingerprint.definingClass

    val exact = fingerprint.methodOrNull
    if (exact != null && exact.implementation != null) {
        runtimeCategoryByFingerprint[fingerprint]?.let { category ->
            val guard = runtimeGuard(category) ?: return@let 0
            val dynamic = guard.replace("return-void", booleanReturnInstructions(false))
            return injectOrSkip(fingerprint, dynamic)
        }
        if (exact.returnType != "Z" || (exact.implementation?.registerCount ?: 0) < 1) {
            logger.warning("No Ads: skipping $name: boolean method has no usable register")
            return 0
        }
        exact.addInstructions(0, booleanReturnInstructions(false))
        logger.info("No Ads: forced $name -> false (exact 1 impl)")
        return 1
    }
    if (target != null && params.isEmpty() && name == "show") return 0

    enqueueAdsFallbackOperation(
        kind = AdsFallbackKind.RETURN_FALSE,
        fingerprint = fingerprint,
        name = name,
        returnType = ret,
        parameterTypes = params,
        target = target,
    )
    return 0
}

private fun BytecodePatchContext.patchWith(fingerprint: Fingerprint, smali: String): Int {
    val exact = fingerprint.methodOrNull
    if (exact != null && exact.implementation != null) {
        runtimeCategoryByFingerprint[fingerprint]?.let { category ->
            return injectOrSkip(fingerprint, runtimeGuard(category) ?: return@let 0)
        }
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
// Small embedded subsets; these are not complete upstream subscriptions. Keep authentication,
// billing, Play Games, and social-login domains out of every default set.
private val uBlockHosts = setOf(
    "adnxs.com", "advertising.com", "adform.net", "adsafeprotected.com",
    "moatads.com", "rubiconproject.com", "pubmatic.com", "33across.com",
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
private val hageziProMiniHosts = setOf(
    "adnxs.com", "adsrvr.org", "criteo.com", "doubleclick.net", "googlesyndication.com",
    "scorecardresearch.com", "quantserve.com", "zedo.com",
)
private val privacyEssentialsHosts = setOf(
    "app-measurement.com", "google-analytics.com",
    "branch.io", "kochava.com", "singular.net",
)
private val easyPrivacyHosts = setOf(
    "demdex.net", "everesttech.net", "omtrdc.net", "krxd.net", "rlcdn.com",
    "mathtag.com", "bluekai.com", "exelator.com",
)
private val peterLoweHosts = setOf(
    "banners.adfox.ru", "ads.mopub.com", "ads.reddit.com", "adserver.adtech.de",
    "spylog.com", "hitbox.com", "counter.hitslink.com", "stats.wp.com",
)

private fun logHeap(logger: Logger, phase: String) {
    if (System.getProperty("unipatches.heapDiagnostics") != "true") return
    val runtime = Runtime.getRuntime()
    val used = runtime.totalMemory() - runtime.freeMemory()
    logger.info(
        "Control App Ads heap [$phase]: used=${used / 1024 / 1024}MiB " +
            "committed=${runtime.totalMemory() / 1024 / 1024}MiB " +
            "max=${runtime.maxMemory() / 1024 / 1024}MiB",
    )
}

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
        val matches = classDef.methods.mapNotNull { method ->
            val instructionMatches = mutableListOf<Triple<Int, Int, String>>()
            method.implementation?.instructions?.forEachIndexed { index, instruction ->
                val value = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string ?: return@forEachIndexed
                val host = extractHost(value) ?: return@forEachIndexed
                if (hosts.none { blocked -> host == blocked || (wildcard && host.endsWith(".$blocked")) }) return@forEachIndexed
                val register = (instruction as? OneRegisterInstruction)?.registerA ?: return@forEachIndexed
                instructionMatches += Triple(index, register, value)
            }
            if (instructionMatches.isEmpty()) null else method to instructionMatches
        }
        if (matches.isEmpty()) return@classDefForEach
        val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@classDefForEach
        matches.forEach { (immutableMethod, instructionMatches) ->
            val method = mutableClass.methods.firstOrNull {
                it.name == immutableMethod.name &&
                    it.returnType == immutableMethod.returnType &&
                    it.parameterTypes == immutableMethod.parameterTypes
            } ?: return@forEach
            instructionMatches.asReversed().forEach { (index, register, value) ->
                val host = extractHost(value) ?: return@forEach
                val replacement = if (runtimeHostsEnabled) {
                    """
                    const-string v$register, "${escapeSmaliString(value)}"
                    invoke-static/range {v$register .. v$register}, $ADS_POLICY_CLASS->rewriteHost(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                    """.trimIndent()
                } else {
                    val blocked = value.replace(host, "0.0.0.0", ignoreCase = true)
                    "const-string v$register, \"${escapeSmaliString(blocked)}\""
                }
                method.replaceInstruction(index, replacement)
                replacements++
            }
        }
    }
    if (replacements > 0) logger.info("Control App Ads: redirected $replacements literal host string(s).")
    return replacements
}

@Suppress("unused")
val controlAppAdsPatch = bytecodePatch(
    name = "Control App Ads Patch ( Experimental, Enhanced, Has Overlay Addon )",
    description = """
        A merged ad-control patch based on Nai64's No Ads and Ads Free Rewards patches, plus
        literal-host blocking inspired by Entree and Adobo. Block common ad formats, choose the
        SDKs to target, optionally grant ad rewards without an ad, and redirect matching literal
        ad/tracker hosts embedded in the APK.

        Host filters are small embedded subsets inspired by uBlock Origin, EasyList, AdGuard,
        OISD, HaGeZi Pro mini, Privacy Essentials, EasyPrivacy, and Peter Lowe's list; they are
        not downloaded full subscriptions. Each filter is independently toggleable. They only
        affect hosts stored as literal strings by the app; they cannot block dynamically generated
        hosts, encrypted traffic, or requests made entirely inside Google Play services.

        Compatibility: when combined with Disable Forced Online Checks, keep that patch's “Exclude
        ad SDK and networking code” option enabled; otherwise blocked ad hosts may be treated as
        online and retried. PairIP Firebase cleanup/removal can disable Firebase-backed reward,
        sign-in, billing, and attribution flows, which this patch cannot restore.

        This patch includes an optional Universal Overlay addon. To use the addon, patch Control App Ads
        together with Universal Overlay, enable “Overlay integration >
        Enable runtime controls”, and select one or more of its three overlay addon modules:
        “Block Ads”, “Ads Free Rewards”, and “Block ad/tracking hosts”. The selected modules
        appear under “Ad control hook modules” in Universal Overlay when selected. Host blocking
        starts according to the Enable Block Ads / Tracking Hosts master setting. Universal Overlay is required for the runtime policy to be
        installed; if it is not selected, the normal static ad controls still work but these
        runtime addon modules are not available.
        Runtime changes last for the current app process and affect only methods and literal hosts
        successfully instrumented by this patch. Its policy is attached to the selected overlay's
        own startup bridge, including a manual Activity override; it does not independently choose
        an Application or Activity. Native, dynamically generated, unsupported, or unrecognized
        SDK paths remain unchanged.

        Credits: Nai64Patches from Nai64; host-blocking approach inspired by Entree and Adobo.
    """.trimIndent(),
    default = false,
) {
    // Guarded: morphe-patcher < 1.13.0 has no category() and keeps the patch ungrouped.
    try { category("Control App Ads Enhanced") } catch (_: NoSuchMethodError) {}
    extendWith("extensions/extension.mpe")

    val enableNoAds by booleanOption(
        title = "Enable No Ads",
        default = true,
        key = "enableNoAds",
        description = "Enable permanent or runtime-controlled blocking for the selected ad formats below.",
    )
    val blockInterstitials by booleanOption(
        title = "Ad formats > Block interstitial ads",
        default = true,
        key = "blockInterstitials",
        description = "Full-screen ads between levels or menus. Safe to block in most apps.",
    )
    val blockBanners by booleanOption(
        title = "Ad formats > Block banner ads",
        default = true,
        key = "blockBanners",
        description = "Thin banners at top/bottom. Safe to block; rarely breaks layout.",
    )
    val blockAppOpen by booleanOption(
        title = "Ad formats > Block app-open ads",
        default = true,
        key = "blockAppOpen",
        description = "Ads on cold start. Block to skip the launch ad.",
    )
    val blockMRec by booleanOption(
        title = "Ad formats > Block MREC ads",
        default = true,
        key = "blockMRec",
        description = "Medium rectangles (300x250) inside feeds. Safe to block.",
    )
    val blockRewarded by booleanOption(
        title = "Ad formats > Block rewarded ads",
        default = false,
        key = "blockRewarded",
        description = "Rewarded video. Disable if you use Ads Free Rewards, otherwise progress gates may break. Enable only to fully remove rewarded ads.",
    )
    val blockNative by booleanOption(
        title = "Ad formats > Block native ads",
        default = true,
        key = "blockNative",
        description = "Native ads blended into feeds/lists. Enable for cleaner feeds (may leave empty placeholders).",
    )
    val enableAdsFreeRewards by booleanOption(
        title = "Enable Ads Free Rewards",
        default = true,
        key = "enableAdsFreeRewards",
        description = "Enable permanent or runtime-controlled Ads Free Rewards behavior for the three settings below.",
    )
    val skipRewardedAds by booleanOption(
        title = "Ads Free Rewards > Skip Rewarded Ads",
        default = true,
        key = "adsFreeRewardsSkip",
        description = "Do not show a supported rewarded ad after the user clicks its button. In runtime mode, this becomes the initial value of the overlay control.",
    )
    val instantReward by booleanOption(
        title = "Ads Free Rewards > Instant Rewards",
        default = true,
        key = "adsFreeRewardsInstant",
        description = "Give the supported reward immediately after the rewarded-ad button is clicked. If Skip Rewarded Ads is disabled, the ad can still be shown while the reward is granted immediately.",
    )
    val fakeAdAvailability by booleanOption(
        title = "Ads Free Rewards > Fake Ad Availability",
        default = true,
        key = "adsFreeRewardsAvailability",
        description = "Make supported SDK availability checks report an ad as available so the game can display its rewarded-ad button.",
    )
    val sdkMax by booleanOption(title = "SDK coverage > AppLovin MAX", default = true, key = "adsSdkMax", description = "Allow patches for detected AppLovin MAX wrappers and native MAX ads.")
    val sdkAdMob by booleanOption(title = "SDK coverage > Google Mobile Ads (AdMob)", default = true, key = "adsSdkAdMob", description = "Allow patches for detected Google Mobile Ads and common native-ad loaders.")
    val sdkUnity by booleanOption(title = "SDK coverage > Unity Ads", default = true, key = "adsSdkUnity", description = "Allow patches for detected Unity Ads v3/v4 entry points.")
    val sdkIronSource by booleanOption(title = "SDK coverage > ironSource / LevelPlay", default = true, key = "adsSdkIronSource", description = "Allow patches for detected ironSource and LevelPlay entry points.")
    val sdkAppLovin by booleanOption(title = "SDK coverage > AppLovin legacy", default = true, key = "adsSdkAppLovin", description = "Allow patches for the older direct AppLovin SDK, separate from MAX.")
    val sdkVungle by booleanOption(title = "SDK coverage > Vungle", default = true, key = "adsSdkVungle", description = "Allow patches for detected Vungle ad entry points.")
    val sdkMeta by booleanOption(title = "SDK coverage > Meta Audience Network", default = true, key = "adsSdkMeta", description = "Allow patches for detected Meta/Facebook Audience Network ads.")
    val sdkPangle by booleanOption(title = "SDK coverage > Pangle", default = true, key = "adsSdkPangle", description = "Allow patches for detected Pangle/ByteDance ad entry points.")
    val sdkHuawei by booleanOption(title = "SDK coverage > Huawei Ads", default = true, key = "adsSdkHuawei", description = "Allow patches for detected Huawei Ads Kit / Petal Ads formats.")
    val sdkYandex by booleanOption(title = "SDK coverage > Yandex / MyTarget", default = true, key = "adsSdkYandex", description = "Allow patches for detected Yandex Ads and VK MyTarget/RuStore entry points.")
    val sdkStartApp by booleanOption(title = "SDK coverage > StartApp", default = true, key = "adsSdkStartApp", description = "Allow detected StartApp interstitial entry points to be patched.")
    val sdkMoPub by booleanOption(title = "SDK coverage > MoPub", default = true, key = "adsSdkMoPub", description = "Allow detected MoPub interstitial entry points to be patched.")
    val sdkChartboost by booleanOption(title = "SDK coverage > Chartboost", default = true, key = "adsSdkChartboost", description = "Allow detected Chartboost interstitial entry points to be patched.")
    val sdkInMobi by booleanOption(title = "SDK coverage > InMobi", default = true, key = "adsSdkInMobi", description = "Allow detected InMobi interstitial and rewarded entry points to be patched, including automatic reward availability checks.")
    val sdkMintegral by booleanOption(title = "SDK coverage > Mintegral", default = true, key = "adsSdkMintegral", description = "Allow detected Mintegral interstitial entry points to be patched.")
    val enableBlockHosts by booleanOption(title = "Enable Block Ads / Tracking Hosts", default = true, key = "enableBlockHosts", description = "Enable permanent or runtime-controlled blocking for the host filters below.")
    val uBlockFilter by booleanOption(title = "Host filters > uBlock Origin ads", default = true, key = "adsFilterUblock", description = "Enable a small embedded subset of uBlock Origin-style ad-network hosts. This is not the complete uBlock subscription and does not target sign-in or billing domains.")
    val easyListFilter by booleanOption(title = "Host filters > EasyList ads", default = true, key = "adsFilterEasyList", description = "Enable a small embedded subset of EasyList-style advertising hosts. This is separate from the uBlock subset and only affects literal hosts found in the APK.")
    val adGuardFilter by booleanOption(title = "Host filters > AdGuard mobile ads", default = true, key = "adsFilterAdGuard", description = "Redirect common mobile ad-network host literals. Recommended.")
    val oisdFilter by booleanOption(title = "Host filters > OISD privacy", default = true, key = "adsFilterOisd", description = "Redirect a conservative set of analytics and tracking host literals. Recommended; disable if sign-in, telemetry-dependent features, or diagnostics fail.")
    val hageziProMiniFilter by booleanOption(title = "Host filters > HaGeZi Pro mini", default = true, key = "adsFilterHageziProMini", description = "Enable a conservative embedded subset inspired by HaGeZi Pro mini. It targets common ad and telemetry hosts, not account, billing, or social-login endpoints.")
    val privacyEssentialsFilter by booleanOption(title = "Host filters > Privacy Essentials", default = true, key = "adsFilterPrivacyEssentials", description = "Enable a conservative embedded privacy subset. Disable it if an app depends on analytics or installation attribution for sign-in or account linking.")
    val easyPrivacyFilter by booleanOption(title = "Host filters > EasyPrivacy", default = false, key = "adsFilterEasyPrivacy", description = "More invasive tracker blocking inspired by EasyPrivacy. Disabled by default because it can interfere with login, attribution, consent, or telemetry-dependent features.")
    val peterLoweFilter by booleanOption(title = "Host filters > Peter Lowe's ad and tracking list", default = true, key = "adsFilterPeterLowe", description = "Enable a small embedded subset of Peter Lowe's ad and tracking list. Disable it if a site or account flow behaves unexpectedly.")
    val wildcardHosts by booleanOption(title = "Host filters > Match subdomains", default = true, key = "adsFilterWildcardHosts", description = "When enabled, an entry such as example.com also redirects ads.example.com and other subdomains. Disable for exact-host matching only.")
    val customFilterHosts by stringsOption(title = "Host filters > Custom host entries", default = emptyList(), key = "adsCustomFilterHosts", description = "Optional domains, URLs, or hosts-file lines to redirect. Add one per row, for example ads.example.com or 0.0.0.0 tracker.example.com. Entries match subdomains while Match subdomains is enabled.")
    val runtimeHookModules by booleanOption(title = "Overlay integration > Enable runtime controls", default = false, key = "adsRuntimeHookModules", description = "Optional. Enable this and one or more controls below, then select Universal Overlay in the same patch operation. The controls appear in Universal Overlay; they are session-only and affect only methods and literal hosts instrumented by this patch. Without Universal Overlay, the runtime policy is not installed.")
    val runtimeBlockAdsModule by booleanOption(title = "Overlay integration > Runtime controls > Block Ads", default = false, key = "adsRuntimeBlockAdsModule", description = "Add one Block Ads settings control to the overlay. It changes only safely instrumented ad-format methods selected by this patch; it does not force SDK preload or initialization paths. Requires Enable runtime controls.")
    val runtimeRewardsModule by booleanOption(title = "Overlay integration > Runtime controls > Ads Free Rewards", default = false, key = "adsRuntimeRewardsModule", description = "Add one Ads Free Rewards settings control to Universal Overlay. Its three controls mirror the Ads Free Rewards patch settings initially and affect only safely instrumented reward paths; unsupported and native reward paths remain unchanged. Requires Enable runtime controls and Universal Overlay.")
    val runtimeHostsModule by booleanOption(title = "Overlay integration > Runtime controls > Block Ads / Tracking Hosts", default = false, key = "adsRuntimeHostsModule", description = "Add one host-blocking checkbox to the overlay. Its initial state follows Enable Block Ads / Tracking Hosts and controls literal hosts instrumented by this patch; encrypted or dynamically generated requests remain unchanged. Requires Enable runtime controls.")
    val broadHeuristics by booleanOption(title = "Advanced > Heuristic matching > Enable broad audio-ad heuristics", default = false, key = "adsBroadAudioHeuristics", description = "Normal SDK coverage changes only exact, known ad-SDK methods. Enable this only when an audio or radio app still plays inserted ads after normal controls find nothing: it additionally looks for stream-like classes and ad-metadata methods such as adsIdentityToken, adsResponse, adsDuration, adsId, or cuepoints, then returns empty metadata so detected server-inserted audio ad breaks may be skipped. It does not block every audio ad, visual ad, network request, or unknown SDK. Because it matches names rather than an exact fingerprint, unrelated playback or stream code can match and break app features; disabled by default.")

    execute {
        val detectionLogger = Logger.getLogger(this::class.java.name)
        resetAdsFallbackIndex()
        logHeap(detectionLogger, "start")

        // Each format has one source of truth. Do not apply a second policy layer that can
        // silently override the values shown in patch settings.
        var effectiveBlockInterstitials = blockInterstitials == true
        var effectiveBlockBanners = blockBanners == true
        var effectiveBlockAppOpen = blockAppOpen == true
        var effectiveBlockMRec = blockMRec == true
        var effectiveBlockRewarded = blockRewarded == true
        var effectiveBlockNative = blockNative == true

        val staticAdsFreeRewardsEnabled = enableAdsFreeRewards == true &&
            (skipRewardedAds == true || instantReward == true || fakeAdAvailability == true)
        effectiveBlockInterstitials = enableNoAds == true && effectiveBlockInterstitials
        effectiveBlockBanners = enableNoAds == true && effectiveBlockBanners
        effectiveBlockAppOpen = enableNoAds == true && effectiveBlockAppOpen
        effectiveBlockMRec = enableNoAds == true && effectiveBlockMRec
        effectiveBlockRewarded = enableNoAds == true && effectiveBlockRewarded
        effectiveBlockNative = enableNoAds == true && effectiveBlockNative
        if (staticAdsFreeRewardsEnabled && effectiveBlockRewarded) {
            effectiveBlockRewarded = false
            detectionLogger.info("Control App Ads: keeping rewarded flows enabled for Ads Free Rewards.")
        }

        val runtimeControlsRequested = runtimeHookModules == true
        runtimeHooksEnabled = isAdsRuntimePolicyActive(
            runtimeControlsRequested = runtimeControlsRequested,
            blockAdsModule = enableNoAds == true && runtimeBlockAdsModule == true,
            rewardsModule = enableAdsFreeRewards == true && runtimeRewardsModule == true,
            hostsModule = runtimeHostsModule == true,
        )
        if (runtimeControlsRequested && !runtimeHooksEnabled) {
            detectionLogger.info("Control App Ads: runtime controls requested without selected modules; using permanent patch settings.")
        }
        val runtimeBlockAdsEnabled = isAdsRuntimeModuleEnabled(
            runtimeHooksEnabled,
            enableNoAds == true,
            runtimeBlockAdsModule == true,
        )
        val configuredBlockedFormats = buildAdsBlockedFormatsMask(
            blockInterstitials = effectiveBlockInterstitials,
            blockBanners = effectiveBlockBanners,
            blockAppOpen = effectiveBlockAppOpen,
            blockMRec = effectiveBlockMRec,
            blockRewarded = effectiveBlockRewarded,
            blockNative = effectiveBlockNative,
        )
        // Runtime mode must preserve original behavior until a runtime control changes it. Keep
        // the static flags only as instrumentation selectors for the selected Block Ads module;
        // every such method is guarded by runtimeCategoryByFingerprint below.
        if (runtimeHooksEnabled) {
            effectiveBlockInterstitials = runtimeBlockAdsEnabled
            effectiveBlockBanners = runtimeBlockAdsEnabled
            effectiveBlockAppOpen = runtimeBlockAdsEnabled
            effectiveBlockMRec = runtimeBlockAdsEnabled
            effectiveBlockRewarded = runtimeBlockAdsEnabled
            effectiveBlockNative = runtimeBlockAdsEnabled
        }
        // Selecting the runtime Rewards control also requests its guarded reward hooks. The
        // separate Ads Free Rewards option controls the non-runtime/static policy only; requiring
        // it here would silently drop the selected runtime module from the overlay mask.
        val runtimeRewardsEnabled = isAdsRuntimeModuleEnabled(
            runtimeHooksEnabled = runtimeHooksEnabled,
            masterEnabled = enableAdsFreeRewards == true,
            moduleSelected = runtimeRewardsModule == true,
        )
        runtimeHostsEnabled = runtimeHooksEnabled && runtimeHostsModule == true
        adsFreeRewardsRuntimeGuardEnabled = runtimeRewardsEnabled
        val sdkCoverage = AdsSdkCoverage(
            max = sdkMax == true,
            adMob = sdkAdMob == true,
            unity = sdkUnity == true,
            ironSource = sdkIronSource == true,
            appLovin = sdkAppLovin == true,
            vungle = sdkVungle == true,
            meta = sdkMeta == true,
            pangle = sdkPangle == true,
            huawei = sdkHuawei == true,
            yandex = sdkYandex == true,
            startApp = sdkStartApp == true,
            moPub = sdkMoPub == true,
            chartboost = sdkChartboost == true,
            inMobi = sdkInMobi == true,
            mintegral = sdkMintegral == true,
        )
        runtimeCategoryByFingerprint = if (runtimeHooksEnabled) buildMap {
            fun add(enabled: Boolean, category: String, vararg fingerprints: Fingerprint) {
                if (!enabled) return
                fingerprints.forEach { put(it, category) }
            }
            add(sdkCoverage.max, "interstitials", ShowInterstitialFingerprint, MaxInterstitialAdShowAdFingerprint,
                MaxInterstitialAdIsReadyFingerprint,
            )
            add(sdkCoverage.adMob, "interstitials",
                AdMobInterstitialShowFingerprint, AdMobLegacyInterstitialShowFingerprint,
            )
            add(sdkCoverage.unity, "interstitials",
                UnityAdsV3Show2ArgFingerprint, UnityAdsV3ShowOptionsFingerprint,
            )
            add(sdkCoverage.ironSource, "interstitials",
                IronSourceShowDemandOnlyInterstitialFingerprint, IronSourceShowInterstitialFingerprint,
                IronSourceIsInterstitialReadyFingerprint,
                IronSourceShowInterstitialActivityFingerprint, IronSourceShowInterstitialActivityPlacementFingerprint,
                IronSourceShowInterstitialPlacementFingerprint,
            )
            add(sdkCoverage.appLovin, "interstitials",
                AppLovinInterstitialDialogShowFingerprint, AppLovinInterstitialDialogShowAndRenderFingerprint,
                AppLovinAdViewLoadNextAdFingerprint,
            )
            add(sdkCoverage.meta, "interstitials",
                FacebookInterstitialAdShowFingerprint, FacebookInterstitialAdShowConfigFingerprint,
            )
            add(sdkCoverage.pangle, "interstitials", PangleInterstitialShowFingerprint)
            add(sdkCoverage.vungle, "interstitials", VungleInterstitialShowFingerprint)
            add(sdkCoverage.huawei, "interstitials", HuaweiInterstitialAdShowFingerprint)
            add(sdkCoverage.yandex, "interstitials",
                YandexInterstitialAdLoadFingerprint,
                YandexUnityInterstitialWrapperShowFingerprint, MyTargetBaseInterstitialShowFingerprint,
            )
            add(sdkCoverage.startApp, "interstitials", StartAppAdShowFingerprint)
            add(sdkCoverage.moPub, "interstitials", MoPubInterstitialShowFingerprint)
            add(sdkCoverage.chartboost, "interstitials", ChartboostShowInterstitialFingerprint)
            add(sdkCoverage.inMobi, "interstitials", InMobiInterstitialShowFingerprint)
            add(sdkCoverage.mintegral, "interstitials", MintegralInterstitialShowFingerprint)
            add(sdkCoverage.max, "appOpen", ShowAppOpenAdFingerprint, MaxAppOpenAdShowAdFingerprint)
            add(sdkCoverage.max, "appOpen", MaxAppOpenAdIsReadyFingerprint)
            add(sdkCoverage.adMob, "appOpen",
                AdMobAppOpenShowFingerprint, AdMobAppOpenLoadFingerprint,
            )
            add(sdkCoverage.pangle, "appOpen", PangleAppOpenShowFingerprint)
            add(sdkCoverage.huawei, "appOpen", HuaweiSplashAdLoadFingerprint)
            add(sdkCoverage.max, "banners", ShowBannerFingerprint, StartBannerAutoRefreshFingerprint,
                MaxAdViewStartAutoRefreshFingerprint,
            )
            add(sdkCoverage.huawei, "banners", HuaweiBannerAdLoadFingerprint)
            add(sdkCoverage.appLovin, "banners", AppLovinAdViewLoadNextAdFingerprint)
            add(sdkCoverage.max, "mrec", ShowMRecFingerprint, StartMRecAutoRefreshFingerprint)
            add(sdkCoverage.appLovin, "mrec", StartMRecAutoRefreshFingerprint)
            add(sdkCoverage.max, "rewarded", ShowRewardedAdFingerprint, MaxRewardedAdShowAdFingerprint)
            add(sdkCoverage.max, "rewardedAvailability", MaxRewardedAdIsReadyFingerprint, IsRewardedAdReadyFingerprint)
            add(sdkCoverage.adMob, "rewarded",
                AdMobRewardedShowFingerprint, AdMobLegacyRewardedVideoShowFingerprint,
            )
            add(sdkCoverage.unity, "rewarded", UnityRewardedAdShowFingerprint)
            add(sdkCoverage.unity, "rewardedAvailability",
                UnityAdsAdvertisementIsReadyFingerprint, UnityAdsAdvertisementIsReadyPlacementFingerprint,
                UnityAdsSdkIsReadyFingerprint,
            )
            add(sdkCoverage.ironSource, "rewarded",
                IronSourceShowDemandOnlyRewardedVideoFingerprint, IronSourceShowRewardedVideoFingerprint,
                IronSourceShowRewardedVideoActivityFingerprint, IronSourceShowRewardedVideoActivityPlacementFingerprint,
                IronSourceShowRewardedVideoPlacementFingerprint,
            )
            add(sdkCoverage.ironSource, "rewardedAvailability",
                IronSourceIsRewardedVideoAvailableFingerprint, LevelPlayRewardedAdIsReadyFingerprint,
                IronSourceUnityRewardedAdIsReadyFingerprint,
            )
            add(sdkCoverage.appLovin, "rewarded",
                AppLovinIncentivizedShow4ListenerFingerprint, AppLovinIncentivizedShow5ListenerFingerprint,
            )
            add(sdkCoverage.meta, "rewarded",
                FacebookRewardedVideoAdShowFingerprint, FacebookRewardedVideoAdShowConfigFingerprint,
                FacebookRewardedInterstitialShowFingerprint, FacebookRewardedInterstitialShowConfigFingerprint,
            )
            add(sdkCoverage.pangle, "rewarded", PangleRewardedShowFingerprint)
            add(sdkCoverage.vungle, "rewarded", VungleRewardedShowFingerprint)
            add(sdkCoverage.yandex, "rewarded",
                YandexRewardedAdLoadFingerprint, YandexUnityRewardedWrapperShowFingerprint,
            )
            add(sdkCoverage.huawei, "rewarded", HuaweiRewardAdShowFingerprint)
            add(sdkCoverage.huawei, "rewardedAvailability", HuaweiRewardAdIsLoadedFingerprint)
            add(sdkCoverage.inMobi, "rewarded", InMobiRewardedShowFingerprint)
            add(sdkCoverage.ironSource, "shared", IronSourceLevelPlayFullScreenShowAdFingerprint)
            // Unity Ads v4 show(Activity, placement, listener[, options]) is shared by rewarded
            // and interstitial placements. It cannot be safely classified by this method alone.
            // Leave it to the guarded Rewards implementation; combining it with Block Ads here
            // can create a re-entry loop during SDK startup.
            add(sdkCoverage.vungle, "shared", VungleBaseFullscreenAdLoadFingerprint)
            add(sdkCoverage.pangle, "native", PangleNativeShowFingerprint)
            add(sdkCoverage.adMob, "native", AdMobNativeAdViewFingerprint, AdMobAdLoaderLoadFingerprint)
            add(sdkCoverage.huawei, "native", HuaweiNativeAdLoadFingerprint)
        }.filter { (_, category) ->
            (category == "rewarded" || category == "shared") && runtimeRewardsEnabled ||
                runtimeBlockAdsEnabled
        } else emptyMap()

        // Runtime block controls guard the formats selected for static patching. Do not
        // force every format on here: that would also activate preload and initialization
        // paths that are unsafe to intercept during app startup. Runtime Rewards uses its
        // guarded reward hooks below and must not enable the broad static rewarded-blocking
        // branches, which can prevent an app from completing startup.

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
        val hasStartApp = sdkStartApp == true && StartAppAdShowFingerprint.methodOrNull != null
        val hasMoPub = sdkMoPub == true && MoPubInterstitialShowFingerprint.methodOrNull != null
        val hasChartboost = sdkChartboost == true && ChartboostShowInterstitialFingerprint.methodOrNull != null
        val hasInMobi = sdkInMobi == true && (InMobiInterstitialShowFingerprint.methodOrNull != null ||
            InMobiRewardedShowFingerprint.methodOrNull != null)
        val hasMintegral = sdkMintegral == true && MintegralInterstitialShowFingerprint.methodOrNull != null
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
        // flows. In runtime mode, never permanently replace this method: its
        // guarded reward implementation must retain control of the original
        // SDK call until the session policy explicitly changes behavior.
        if (shouldPatchUnityAdsV4Permanently(
                runtimeHooksEnabled = runtimeHooksEnabled,
                unitySdkEnabled = sdkUnity == true,
                blockInterstitials = effectiveBlockInterstitials,
                blockRewarded = effectiveBlockRewarded,
            )
        ) {
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
        if (sdkStartApp == true && effectiveBlockInterstitials) totalPatched += patchVoid(StartAppAdShowFingerprint)
        if (sdkMoPub == true && effectiveBlockInterstitials) totalPatched += patchVoid(MoPubInterstitialShowFingerprint)
        if (sdkChartboost == true && effectiveBlockInterstitials) totalPatched += patchVoid(ChartboostShowInterstitialFingerprint)
        if (sdkInMobi == true && effectiveBlockInterstitials) totalPatched += patchVoid(InMobiInterstitialShowFingerprint)
        if (sdkMintegral == true && effectiveBlockInterstitials) totalPatched += patchVoid(MintegralInterstitialShowFingerprint)

        // Hide rewarded UI only when rewarded ads were explicitly blocked by the static policy
        // (inverse of Ads Free Rewards fake true).
        if (effectiveBlockRewarded) {
            totalPatched += patchReturnFalse(UnityAdsAdvertisementIsReadyFingerprint)
            totalPatched += patchReturnFalse(UnityAdsAdvertisementIsReadyPlacementFingerprint)
            totalPatched += patchReturnFalse(UnityAdsSdkIsReadyFingerprint)
            totalPatched += patchReturnFalse(IronSourceIsRewardedVideoAvailableFingerprint)
            if (!runtimeHooksEnabled) {
                totalPatched += patchReturnFalse(MaxRewardedAdIsReadyFingerprint)
            }
        }

        totalPatched += flushAdsFallbackOperations(detectionLogger)

        // Generic audio DAI ads (Klassik Radio, etc.)  -  adsIdentityToken, cuepoints.
        if (broadHeuristics == true && !runtimeHooksEnabled) classDefForEach { classDef ->
            val tl = classDef.type.lowercase()
            if (!tl.contains("song") && !tl.contains("station") && !tl.contains("stream") && !tl.contains("ad")) return@classDefForEach
            if (tl.contains("okhttp") || tl.contains("androidx")) return@classDefForEach
            val matchingMethods = classDef.methods.filter { method ->
                val name = method.name.lowercase()
                method.implementation != null &&
                    (name.contains("adsidentitytoken") || name.contains("adsresponse") ||
                        name.contains("adsduration") || name.contains("cuepoints") || name.contains("adsid"))
            }
            if (matchingMethods.isEmpty()) return@classDefForEach
            try {
                val mutableClass = mutableClassDefBy(classDef)
                for (method in mutableClass.methods.filter { candidate ->
                    matchingMethods.any {
                        it.name == candidate.name &&
                            it.returnType == candidate.returnType &&
                            it.parameterTypes == candidate.parameterTypes
                    }
                }) {
                    val n = method.name.lowercase()
                    val isAdToken = n.contains("adsidentitytoken") || n.contains("adsresponse") || n.contains("adsduration") || n.contains("cuepoints") || n.contains("adsid")
                    if (!isAdToken) continue
                    try {
                        if ((method.implementation?.registerCount ?: 0) - method.numberOfParameterRegisters < 1) {
                            detectionLogger.info("Control App Ads: skipped heuristic match ${classDef.type}->$n because it has no safe local register.")
                            continue
                        }
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

        if ((!runtimeRewardsEnabled && staticAdsFreeRewardsEnabled) || runtimeRewardsEnabled) {
            applyAdsFreeRewards(detectionLogger, instantReward, sdkCoverage)
        }
        logHeap(detectionLogger, "after-method-patches")
        // Availability needs a guarded method even when the initial runtime value is false;
        // otherwise the overlay checkbox could never enable it after patching.
        if ((!runtimeRewardsEnabled && staticAdsFreeRewardsEnabled && fakeAdAvailability == true) || runtimeRewardsEnabled) {
            totalPatched += forceAdAvailability(
                detectionLogger,
                runtimeRewardsEnabled,
                sdkCoverage,
            )
        }

        val filterHosts = buildSet {
            if (uBlockFilter == true) addAll(uBlockHosts)
            if (easyListFilter == true) addAll(easyListHosts)
            if (adGuardFilter == true) addAll(adGuardMobileHosts)
            if (oisdFilter == true) addAll(oisdPrivacyHosts)
            if (hageziProMiniFilter == true) addAll(hageziProMiniHosts)
            if (privacyEssentialsFilter == true) addAll(privacyEssentialsHosts)
            if (easyPrivacyFilter == true) addAll(easyPrivacyHosts)
            if (peterLoweFilter == true) addAll(peterLoweHosts)
            addAll(parseFilterHosts(customFilterHosts.orEmpty()))
        }
        if (runtimeHooksEnabled) {
            val moduleMask = buildAdsRuntimeModuleMask(
                runtimeHooksEnabled = runtimeHooksEnabled,
                blockAdsEnabled = runtimeBlockAdsModule == true,
                rewardsEnabled = runtimeRewardsEnabled,
                hostsEnabled = runtimeHostsModule == true,
            )
            val policy = serializeAdsRuntimePolicy(
                moduleMask = moduleMask,
                blockedFormats = configuredBlockedFormats,
                skipRewardedAdsEnabled = skipRewardedAds == true,
                instantRewardEnabled = instantReward == true,
                fakeAvailabilityEnabled = fakeAdAvailability == true,
                hostsEnabled = enableBlockHosts == true,
                wildcardHostsEnabled = wildcardHosts == true,
                hosts = filterHosts.toList(),
            )
            OverlayAdsRuntimeIntegration.queue(policy)
            val earlierBridge = OverlayAdsRuntimeIntegration.takeUnconfiguredBridge(this)
            if (earlierBridge != null && attachQueuedAdsRuntimePolicy(earlierBridge, policy)) {
                OverlayAdsRuntimeIntegration.markInjected("previously injected overlay bridge")
                detectionLogger.info("Control App Ads: attached runtime policy to the previously injected overlay bridge.")
            } else {
                detectionLogger.info("Control App Ads: queued runtime policy for Universal Overlay injection.")
            }
        }
        // In runtime mode, host rewriting is installed only for the selected Hosts module and
        // remains disabled until the overlay policy enables it.
        if (enableBlockHosts == true && (!runtimeHooksEnabled || runtimeHostsEnabled)) {
            totalPatched += redirectLiteralHosts(filterHosts, wildcardHosts == true, detectionLogger)
        }
        logHeap(detectionLogger, "after-host-rewrites")
        resetAdsFallbackIndex()

        if (totalPatched == 0) {
            detectionLogger.warning("Control App Ads: no selected literal host or patchable ad method was found. The app may use an unsupported SDK, dynamically generated endpoints, or encrypted configuration.")
        } else {
            detectionLogger.info("Control App Ads: changed $totalPatched item(s)  -  interstitials=$effectiveBlockInterstitials, banners=$effectiveBlockBanners, appOpen=$effectiveBlockAppOpen, mrec=$effectiveBlockMRec, rewarded=$effectiveBlockRewarded, native=$effectiveBlockNative")
        }
    }
}
