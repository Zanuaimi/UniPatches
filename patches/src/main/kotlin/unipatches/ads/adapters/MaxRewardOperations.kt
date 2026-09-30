package unipatches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.ads.*
import helpers.bytecode.*
import java.util.logging.Logger

internal fun maxRuntimeShowGuard(
    skipCallbacks: String,
    instantCallbacks: String,
    requestSetup: String,
    requestRegister: String,
    originalLabel: String,
): String {
    val safeOriginalLabel = "unipatch_ads_${originalLabel}"
    val safeSkipCallbacks = uniquifyInjectedLabels(skipCallbacks, "${safeOriginalLabel}_skip")
    val safeInstantCallbacks = uniquifyInjectedLabels(instantCallbacks, "${safeOriginalLabel}_instant")
    return """
    invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldSkipRewarded()Z
    move-result v0
    if-eqz v0, :${safeOriginalLabel}_instant
    invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldGrantReward()Z
    move-result v0
    if-eqz v0, :${safeOriginalLabel}_return
    $safeSkipCallbacks
    :${safeOriginalLabel}_return
    return-void
    :${safeOriginalLabel}_instant
    invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldGrantReward()Z
    move-result v0
    if-eqz v0, :${safeOriginalLabel}_original
    $requestSetup
    if-eqz $requestRegister, :${safeOriginalLabel}_original
    $safeInstantCallbacks
    invoke-static {$requestRegister}, Lunipatch/overlaycore/AdsRuntimePolicy;->armInstantReward(Ljava/lang/String;)V
    :${safeOriginalLabel}_original
    """.trimIndent()
}

private fun maxUnityRewardedCallbacks(): String = """
    new-instance v0, Lorg/json/JSONObject;
    invoke-direct {v0}, Lorg/json/JSONObject;-><init>()V
    const-string v1, "name"
    const-string v2, "OnRewardedAdDisplayedEvent"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "adUnitId"
    invoke-static {v0, v1, p1}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "adFormat"
    const-string v2, "rewarded"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    invoke-static {v0}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->forwardUnityEvent(Lorg/json/JSONObject;)V
    new-instance v0, Lorg/json/JSONObject;
    invoke-direct {v0}, Lorg/json/JSONObject;-><init>()V
    const-string v1, "name"
    const-string v2, "OnRewardedAdReceivedRewardEvent"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "adUnitId"
    invoke-static {v0, v1, p1}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "adFormat"
    const-string v2, "rewarded"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "rewardLabel"
    const-string v2, "reward"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "rewardAmount"
    const-string v2, "1"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    invoke-static {v0}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->forwardUnityEvent(Lorg/json/JSONObject;)V
    new-instance v0, Lorg/json/JSONObject;
    invoke-direct {v0}, Lorg/json/JSONObject;-><init>()V
    const-string v1, "name"
    const-string v2, "OnRewardedAdHiddenEvent"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "adUnitId"
    invoke-static {v0, v1, p1}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "adFormat"
    const-string v2, "rewarded"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    invoke-static {v0}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->forwardUnityEvent(Lorg/json/JSONObject;)V
""".trimIndent()

private fun maxUnityImmediateRewardedCallbacks(): String = """
    new-instance v0, Lorg/json/JSONObject;
    invoke-direct {v0}, Lorg/json/JSONObject;-><init>()V
    const-string v1, "name"
    const-string v2, "OnRewardedAdDisplayedEvent"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "adUnitId"
    invoke-static {v0, v1, p1}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "adFormat"
    const-string v2, "rewarded"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    invoke-static {v0}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->forwardUnityEvent(Lorg/json/JSONObject;)V
    new-instance v0, Lorg/json/JSONObject;
    invoke-direct {v0}, Lorg/json/JSONObject;-><init>()V
    const-string v1, "name"
    const-string v2, "OnRewardedAdReceivedRewardEvent"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "adUnitId"
    invoke-static {v0, v1, p1}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "adFormat"
    const-string v2, "rewarded"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "rewardLabel"
    const-string v2, "reward"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    const-string v1, "rewardAmount"
    const-string v2, "1"
    invoke-static {v0, v1, v2}, Lcom/applovin/impl/sdk/utils/JsonUtils;->putString(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;)V
    invoke-static {v0}, Lcom/applovin/mediation/unity/MaxUnityAdManager;->forwardUnityEvent(Lorg/json/JSONObject;)V
""".trimIndent()

private fun addRuntimeMaxShowGuard(
    method: com.android.tools.smali.dexlib2.iface.Method,
    mutableClass: app.morphe.patcher.util.proxy.mutableTypes.MutableClass,
    callbacks: String,
    instantCallbacks: String,
    requestSetup: String,
    requestRegister: String,
    label: String,
): Boolean {
    if (method.returnType != "V" || method.implementation == null) return false
    val originalImplementation = method.implementation ?: return false
    if (!isTypedGuardSafe(
            actualReturnType = method.returnType,
            expectedReturnType = AdsGuardReturnType.VOID,
            localRegisters = originalImplementation.registerCount - method.numberOfParameterRegisters,
            body = callbacks,
        )) return false
    val guard = maxRuntimeShowGuard(callbacks, instantCallbacks, requestSetup, requestRegister, label)
    if (!hasResolvedLabels(guard)) return false
    val allocation = method.cloneMutableAndAllocateScratchRegisters(mutableClass, 8)
    allocation.method.addInstructions(0, guard)
    return true
}

private fun addRuntimeMaxRewardCallbackGuard(
    method: com.android.tools.smali.dexlib2.iface.Method,
    mutableClass: app.morphe.patcher.util.proxy.mutableTypes.MutableClass,
    register: String,
    label: String,
): Boolean {
    if (method.returnType != "V" || method.implementation == null) return false
    val body = """
        invoke-static {$register}, Lunipatch/overlaycore/AdsRuntimePolicy;->consumeInstantNativeReward(Ljava/lang/Object;)Z
        move-result v0
        if-eqz v0, :${label}_original
        return-void
        :${label}_original
    """.trimIndent()
    val originalImplementation = method.implementation ?: return false
    if (!isTypedGuardSafe(
            actualReturnType = method.returnType,
            expectedReturnType = AdsGuardReturnType.VOID,
            localRegisters = originalImplementation.registerCount - method.numberOfParameterRegisters,
            body = body,
        )) return false
    val allocation = method.cloneMutableAndAllocateScratchRegisters(mutableClass, 1)
    allocation.method.addInstructions(0, uniquifyInjectedLabels(body, label))
    return true
}

private fun addRuntimeMaxUnityRewardCallbackGuard(
    method: com.android.tools.smali.dexlib2.iface.Method,
    mutableClass: app.morphe.patcher.util.proxy.mutableTypes.MutableClass,
): Boolean {
    val body = """
        invoke-static {p0}, Lunipatch/overlaycore/AdsRuntimePolicy;->consumeInstantUnityReward(Ljava/lang/Object;)Z
        move-result v0
        if-eqz v0, :morphe_max_unity_reward_original
        return-void
        :morphe_max_unity_reward_original
    """.trimIndent()
    if (method.returnType != "V" || method.implementation == null) return false
    val originalImplementation = method.implementation ?: return false
    if (!isTypedGuardSafe(
            actualReturnType = method.returnType,
            expectedReturnType = AdsGuardReturnType.VOID,
            localRegisters = originalImplementation.registerCount - method.numberOfParameterRegisters,
            body = body,
        )) return false
    val allocation = method.cloneMutableAndAllocateScratchRegisters(mutableClass, 1)
    allocation.method.addInstructions(0, uniquifyInjectedLabels(body, "max_unity_reward_callback"))
    return true
}

internal fun BytecodePatchContext.applyRuntimeMaxUnityRewardedShow(logger: Logger): Int {
    val method = MaxUnityAdManagerShowRewardedAdFingerprint.methodOrNull ?: return 0
    val mutableClass = MaxUnityAdManagerShowRewardedAdFingerprint.classDefOrNull ?: return 0
    val callback = MaxUnityForwardUnityEventFingerprint.methodOrNull ?: run {
        logger.warning("Ads Free Rewards: skipped MAX Unity runtime reward hooks because duplicate-event interception is unavailable")
        return 0
    }
    val callbackClass = MaxUnityForwardUnityEventFingerprint.classDefOrNull ?: return 0
    if (!addRuntimeMaxUnityRewardCallbackGuard(callback, callbackClass)) {
        logger.warning("Ads Free Rewards: skipped MAX Unity runtime reward hooks because duplicate-event interception is unsafe")
        return 0
    }
    val patched = if (addRuntimeMaxShowGuard(
            method,
            mutableClass,
            "invoke-static {p1}, Lunipatch/overlaycore/MaxRuntimeBridge;->dispatchUnitySyntheticReward(Ljava/lang/String;)V",
            "invoke-static {p1}, Lunipatch/overlaycore/MaxRuntimeBridge;->dispatchUnityImmediateReward(Ljava/lang/String;)V",
            "move-object v7, p1\ninvoke-static {v7}, Lunipatch/overlaycore/AdsRuntimePolicy;->beginInstantReward(Ljava/lang/String;)V",
            "v7",
            "morphe_max_unity_runtime",
        )) {
        logger.info("Ads Free Rewards: MAX Unity runtime rewarded show guard installed")
        1
    } else {
        logger.warning("Ads Free Rewards: skipped MAX Unity runtime rewarded show because its method body is unavailable")
        0
    }
    if (patched == 1) {
        logger.info("Ads Free Rewards: MAX Unity duplicate reward guard installed")
    }
    return patched
}

internal fun BytecodePatchContext.applyRuntimeNativeMaxRewardedShows(logger: Logger): Int {
    val targets = listOf(
        MaxRewardedAdShowAdPlacementCustomDataActivityFingerprint,
        MaxRewardedAdShowAdPlacementCustomDataFingerprint,
        MaxRewardedAdShowAdFingerprint,
    )
    val patched = mutableSetOf<String>()
    var count = 0
    val callback = MaxRewardedAdImplRewardCallbackFingerprint.methodOrNull ?: run {
        logger.warning("Ads Free Rewards: skipped native MAX runtime reward hooks because duplicate-event interception is unavailable")
        return 0
    }
    val callbackClass = MaxRewardedAdImplRewardCallbackFingerprint.classDefOrNull ?: return 0
    if (!addRuntimeMaxRewardCallbackGuard(
            callback,
            callbackClass,
            "p1",
            "morphe_max_native_reward_callback_original",
        )) {
        logger.warning("Ads Free Rewards: skipped native MAX runtime reward hooks because duplicate-event interception is unsafe")
        return 0
    }
    for (fingerprint in targets) {
        val method = fingerprint.methodOrNull ?: continue
        val key = "${method.definingClass}->${method.name}${method.parameterTypes}${method.returnType}"
        if (!patched.add(key)) continue
        val mutableClass = fingerprint.classDefOrNull ?: continue
        if (addRuntimeMaxShowGuard(
                method,
                mutableClass,
                "invoke-static {p0}, Lunipatch/overlaycore/MaxRuntimeBridge;->dispatchNativeSyntheticReward(Ljava/lang/Object;)V",
                "invoke-static {p0}, Lunipatch/overlaycore/MaxRuntimeBridge;->dispatchNativeImmediateReward(Ljava/lang/Object;)V",
                "invoke-virtual {p0}, Lcom/applovin/mediation/ads/MaxRewardedAd;->getAdUnitId()Ljava/lang/String;\nmove-result-object v7\ninvoke-static {v7}, Lunipatch/overlaycore/AdsRuntimePolicy;->beginInstantReward(Ljava/lang/String;)V",
                "v7",
                "morphe_max_native_runtime_${count}",
            )) {
            count++
        }
    }
    if (count > 0) {
        logger.info("Ads Free Rewards: MAX native duplicate reward guard installed")
        logger.info("Ads Free Rewards: installed $count MAX native runtime rewarded show guard(s)")
    }
    return count
}

internal fun BytecodePatchContext.applyLegacyMaxUnityStrategy(logger: Logger, useMax: Boolean, instantReward: Boolean?): Boolean {
    val unityShow = ShowRewardedAdFingerprint.methodOrNull
    val unityReady = IsRewardedAdReadyFingerprint.methodOrNull
    if (!useMax || unityShow == null || unityReady == null) return false
    logger.info("Ads Free Rewards: MAX Unity Ad wrapper patch succeeded")
    addGuardedFakeAvailability(logger, unityReady, "morphe_max_unity_ready_original")
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

internal fun BytecodePatchContext.applyLegacyNativeMaxStrategy(logger: Logger, useMax: Boolean, instantReward: Boolean?) {
    val nativeReady = MaxRewardedAdIsReadyFingerprint.methodOrNull
    val nativeShow = MaxRewardedAdShowAdFingerprint.methodOrNull
    if (!useMax || nativeReady == null || nativeShow == null) return
    logger.info("Ads Free Rewards: native MAX patch succeeded")
    addGuardedFakeAvailability(logger, nativeReady, "morphe_native_max_ready_original")
    if (instantReward == true || adsFreeRewardsRuntimeGuardEnabled) {
        val rc = nativeShow.implementation?.registerCount ?: 0
        if (rc >= 7) {
            nativeShow.addInstructions(
                0,
                guardedInstantReward(
                    "invoke-static {p0}, Lunipatch/overlaycore/MaxRuntimeBridge;->dispatchNativeSyntheticReward(Ljava/lang/Object;)V\nreturn-void",
                    "morphe_native_max_original",
                ),
            )
        } else logger.warning("Ads Free Rewards: native MAX showAd() needs seven local registers; skipped to avoid an unsafe bytecode rewrite.")
    }
}
