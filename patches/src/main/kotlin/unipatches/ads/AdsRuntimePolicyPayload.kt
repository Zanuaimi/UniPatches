package unipatches.ads

/** Pure helpers for the Control App Ads to Overlay runtime-policy exchange. */
internal fun isAdsRuntimePolicyActive(
    runtimeControlsRequested: Boolean,
    blockAdsModule: Boolean,
    rewardsModule: Boolean,
    hostsModule: Boolean,
): Boolean = runtimeControlsRequested && (blockAdsModule || rewardsModule || hostsModule)

internal fun isAdsRuntimeModuleEnabled(
    runtimeHooksEnabled: Boolean,
    masterEnabled: Boolean,
    moduleSelected: Boolean,
): Boolean = runtimeHooksEnabled && masterEnabled && moduleSelected

internal fun isAdsRuntimeRewardsEnabled(
    runtimeHooksEnabled: Boolean,
    runtimeRewardsModule: Boolean,
): Boolean = isAdsRuntimeModuleEnabled(runtimeHooksEnabled, true, runtimeRewardsModule)

internal fun buildAdsRuntimeModuleMask(
    runtimeHooksEnabled: Boolean,
    blockAdsEnabled: Boolean,
    rewardsEnabled: Boolean,
    hostsEnabled: Boolean,
): Int {
    if (!runtimeHooksEnabled) return 0
    return (if (blockAdsEnabled) 1 else 0) or
        (if (rewardsEnabled) 2 else 0) or
        (if (hostsEnabled) 4 else 0)
}

/** Converts the six independent patch-time ad-format switches to policy bits. */
internal fun buildAdsBlockedFormatsMask(
    blockInterstitials: Boolean,
    blockBanners: Boolean,
    blockAppOpen: Boolean,
    blockMRec: Boolean,
    blockRewarded: Boolean,
    blockNative: Boolean,
): Int = (if (blockInterstitials) 1 else 0) or
    (if (blockBanners) 2 else 0) or
    (if (blockAppOpen) 4 else 0) or
    (if (blockMRec) 8 else 0) or
    (if (blockRewarded) 16 else 0) or
    (if (blockNative) 32 else 0)

internal fun serializeAdsRuntimePolicy(
    moduleMask: Int,
    blockedFormats: Int,
    skipRewardedAdsEnabled: Boolean,
    instantRewardEnabled: Boolean,
    fakeAvailabilityEnabled: Boolean,
    hostsEnabled: Boolean,
    wildcardHostsEnabled: Boolean,
    hosts: List<String>,
): String = listOf(
    "1",
    moduleMask.toString(),
    blockedFormats.toString(),
    if (skipRewardedAdsEnabled) "1" else "0",
    if (instantRewardEnabled) "1" else "0",
    if (fakeAvailabilityEnabled) "1" else "0",
    if (hostsEnabled) "1" else "0",
    if (wildcardHostsEnabled) "1" else "0",
    hosts.sorted().joinToString(","),
).joinToString("|")
