package unipatches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.ads.*
import helpers.bytecode.*
import java.util.logging.Logger

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
            nativeShow.addInstructions(0, guardedInstantReward(fireRewardedAdCallbacks(), "morphe_native_max_original"))
        } else logger.warning("Ads Free Rewards: native MAX showAd() needs seven local registers; skipped to avoid an unsafe bytecode rewrite.")
    }
}
