package unipatches.ads

/** Pure helpers for the Control App Ads to Overlay runtime-policy exchange. */
internal fun isAdsRuntimeRewardsEnabled(
    runtimeHooksEnabled: Boolean,
    runtimeRewardsModule: Boolean,
): Boolean = runtimeHooksEnabled && runtimeRewardsModule

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

internal fun serializeAdsRuntimePolicy(
    moduleMask: Int,
    blockedFormats: Int,
    adsFreeRewardsEnabled: Boolean,
    instantRewardEnabled: Boolean,
    fakeAvailabilityEnabled: Boolean,
    wildcardHostsEnabled: Boolean,
    hosts: List<String>,
): String = listOf(
    "1",
    moduleMask.toString(),
    blockedFormats.toString(),
    if (adsFreeRewardsEnabled) "1" else "0",
    if (adsFreeRewardsEnabled && instantRewardEnabled) "1" else "0",
    if (adsFreeRewardsEnabled && fakeAvailabilityEnabled) "1" else "0",
    "0",
    if (wildcardHostsEnabled) "1" else "0",
    hosts.sorted().joinToString(","),
).joinToString("|")
