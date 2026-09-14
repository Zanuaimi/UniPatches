package unipatches.ads

/**
 * Resolves cross-path conflicts before any adapter is allowed to inspect or mutate bytecode.
 * Static rewarded blocking is authoritative because installing reward hooks beside it would
 * create contradictory behavior in the same SDK call path.
 */
internal object AdsPatchConflictResolver {
    fun resolveRewards(
        settings: AdsPatchSettings,
        noAds: PathPlan,
        rewards: PathPlan,
    ): PathPlan {
        val staticRewardedBlock = noAds.mode == AdsPatchMode.STATIC && settings.blockRewarded
        return if (staticRewardedBlock && rewards.masterEnabled) {
            rewards.copy(
                mode = AdsPatchMode.DISABLED,
                runtimeModuleSelected = false,
                initialEnabled = false,
            )
        } else {
            rewards
        }
    }
}
