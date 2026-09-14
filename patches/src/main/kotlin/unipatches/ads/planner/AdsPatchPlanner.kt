package unipatches.ads

/** Pure policy resolver. It must run before any fingerprint or bytecode operation. */
internal object AdsPatchPlanner {
    fun resolve(
        settings: AdsPatchSettings,
        selection: AdsRuntimeSelection,
        sdkCoverage: AdsSdkCoverage,
    ): AdsPatchPlan {
        val requestedRuntime = selection.policyEnabled
        val runtimePolicy = requestedRuntime && (
            (settings.noAdsEnabled && selection.noAdsModuleSelected) ||
                (settings.rewardsEnabled && selection.rewardsModuleSelected) ||
                selection.hostsModuleSelected
            )

        val noAds = resolvePath(
            masterEnabled = settings.noAdsEnabled,
            runtimePolicy = runtimePolicy,
            runtimeModuleSelected = selection.noAdsModuleSelected,
            initialEnabled = settings.noAdsEnabled && anyNoAdsFormatEnabled(settings),
        )
        val rewards = resolvePath(
            masterEnabled = settings.rewardsEnabled,
            runtimePolicy = runtimePolicy,
            runtimeModuleSelected = selection.rewardsModuleSelected,
            initialEnabled = settings.rewardsEnabled && (
                settings.skipRewardedAds || settings.instantReward || settings.fakeAdAvailability
                ),
        )
        val hosts = resolvePath(
            masterEnabled = settings.hostsEnabled,
            runtimePolicy = runtimePolicy,
            runtimeModuleSelected = selection.hostsModuleSelected,
            initialEnabled = settings.hostsEnabled,
        )

        val resolvedRewards = AdsPatchConflictResolver.resolveRewards(settings, noAds, rewards)
        val effectiveRuntimePolicy = runtimePolicy && (
            (noAds.mode == AdsPatchMode.RUNTIME && noAds.masterEnabled) ||
                (resolvedRewards.mode == AdsPatchMode.RUNTIME && resolvedRewards.masterEnabled) ||
                selection.hostsModuleSelected
            )

        return AdsPatchPlan(
            noAds = noAds,
            rewards = resolvedRewards,
            hosts = hosts,
            sdkCoverage = sdkCoverage,
            runtimePolicyEnabled = effectiveRuntimePolicy,
        )
    }

    private fun resolvePath(
        masterEnabled: Boolean,
        runtimePolicy: Boolean,
        runtimeModuleSelected: Boolean,
        initialEnabled: Boolean,
    ): PathPlan = when {
        !masterEnabled -> PathPlan(AdsPatchMode.DISABLED, false, runtimeModuleSelected, false)
        runtimePolicy && runtimeModuleSelected -> PathPlan(AdsPatchMode.RUNTIME, true, true, initialEnabled)
        else -> PathPlan(AdsPatchMode.STATIC, true, runtimeModuleSelected, initialEnabled)
    }

    private fun anyNoAdsFormatEnabled(settings: AdsPatchSettings): Boolean =
        settings.blockInterstitials || settings.blockBanners || settings.blockAppOpen ||
            settings.blockMRec || settings.blockRewarded || settings.blockNative
}
