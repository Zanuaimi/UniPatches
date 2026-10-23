package unipatches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import helpers.ads.*
import helpers.bytecode.numberOfParameterRegisters
import java.util.logging.Logger

/*
 * Shared reward operations live in the adapter layer. SDK adapters select these operations,
 * while the patch orchestrator only resolves plans and dispatches adapters.
 */
internal fun guardedPolicyBlock(policyMethod: String, instructions: String, originalLabel: String): String {
    val safeInstructions = uniquifyInjectedLabels(instructions, originalLabel)
    if (!adsFreeRewardsRuntimeGuardEnabled) return safeInstructions
    if (hasGuardParameterWrite(safeInstructions)) return ":$originalLabel"
    return """
        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->$policyMethod()Z
        move-result v0
        if-eqz v0, :$originalLabel
        $safeInstructions
        :$originalLabel
    """.trimIndent()
}

internal fun guardedInstantReward(instructions: String, originalLabel: String): String {
    val safeInstructions = uniquifyInjectedLabels(instructions, originalLabel)
    if (!adsFreeRewardsRuntimeGuardEnabled) return safeInstructions
    if (hasGuardParameterWrite(safeInstructions)) return ":$originalLabel"
    val skipReturn = when {
        "return-void" in safeInstructions -> "return-void"
        "return v0" in safeInstructions -> "const/4 v0, 0x0\nreturn v0"
        else -> return ":$originalLabel"
    }
    val grantLabel = "${originalLabel}_grant"
    return """
        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldSkipRewarded()Z
        move-result v0
        if-eqz v0, :$grantLabel
        $skipReturn
        :$grantLabel
        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldGrantReward()Z
        move-result v0
        if-eqz v0, :$originalLabel
        $safeInstructions
        :$originalLabel
    """.trimIndent()
}

internal fun guardedFakeAvailability(originalLabel: String): String = if (!adsFreeRewardsRuntimeGuardEnabled) {
    booleanReturnInstructions(true)
} else {
    """
        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldFakeRewardAvailability()Z
        move-result v0
        if-eqz v0, :$originalLabel
        ${booleanReturnInstructions(true)}
        :$originalLabel
    """.trimIndent()
}

internal fun addGuardedFakeAvailability(
    logger: Logger,
    method: MutableMethod,
    label: String,
): Boolean {
    val implementation = method.implementation ?: run {
        logger.warning("Ads Free Rewards: skip availability guard for ${method.definingClass}->${method.name}: no implementation")
        return false
    }
    if (!hasSafeLocalRegister(implementation.registerCount, method.numberOfParameterRegisters)) {
        logger.warning("Ads Free Rewards: skip availability guard for ${method.definingClass}->${method.name}: one local register is required")
        return false
    }
    method.addInstructions(
        0,
        uniquifyInjectedLabels(
            guardedFakeAvailability(label),
            "${method.definingClass}_${method.name}",
        ),
    )
    return true
}

/** Patches only explicit availability fingerprints selected by the adapter. */
internal fun BytecodePatchContext.forceAdAvailability(
    logger: Logger,
    runtimePolicy: Boolean = false,
    sdkCoverage: AdsSdkCoverage = AdsSdkCoverage(),
): Int {
    var patched = 0
    val patchedMethods = mutableSetOf<String>()
    fun patchIsReady(label: String, fingerprint: app.morphe.patcher.Fingerprint) {
        val method = fingerprint.methodOrNull ?: return
        val implementation = method.implementation ?: return
        if (method.returnType != "Z" || implementation.registerCount - method.numberOfParameterRegisters < 1) {
            logger.warning("Ads Free Rewards: skip $label because it is not a safe boolean method")
            return
        }
        val methodKey = "${method.definingClass}->${method.name}${method.parameterTypes}${method.returnType}"
        if (!patchedMethods.add(methodKey)) return
        val instructions = if (runtimePolicy) """
            invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldFakeRewardAvailability()Z
            move-result v0
            if-eqz v0, :unipatch_ads_runtime_availability_original
            ${booleanReturnInstructions(true)}
            :unipatch_ads_runtime_availability_original
        """.trimIndent() else booleanReturnInstructions(true)
        method.addInstructions(
            0,
            uniquifyInjectedLabels(
                instructions,
                "${method.definingClass}_${method.name}",
            ),
        )
        logger.info("Ads Free Rewards: faked availability for $label")
        patched++
    }
    if (sdkCoverage.unity) {
        patchIsReady("Unity Ads Advertisement.isReady()", UnityAdsAdvertisementIsReadyFingerprint)
        patchIsReady("Unity Ads Advertisement.isReady(placement)", UnityAdsAdvertisementIsReadyPlacementFingerprint)
        patchIsReady("Unity Ads UnityAds.isReady()", UnityAdsSdkIsReadyFingerprint)
    }
    if (sdkCoverage.ironSource) patchIsReady("ironSource isRewardedVideoAvailable()", IronSourceIsRewardedVideoAvailableFingerprint)
    if (sdkCoverage.yandex) patchIsReady("Yandex/MyTarget rewarded mediation isLoaded()", YandexMyTargetRewardedIsLoadedFingerprint)
    if (sdkCoverage.huawei) patchIsReady("Huawei Ads Kit RewardAd.isLoaded()", HuaweiRewardAdIsLoadedFingerprint)
    if (sdkCoverage.inMobi) patchIsReady("InMobi isReady()", InMobiIsReadyFingerprint)
    return patched
}
