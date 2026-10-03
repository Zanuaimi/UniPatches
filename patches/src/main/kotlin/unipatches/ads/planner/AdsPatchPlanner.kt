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
                selection.hostsModuleSelected
            )

        val noAds = resolvePath(
            masterEnabled = settings.noAdsEnabled,
            runtimePolicy = runtimePolicy,
            runtimeModuleSelected = selection.noAdsModuleSelected,
            initialEnabled = settings.noAdsEnabled && anyNoAdsFormatEnabled(settings),
        )
        val hosts = resolvePath(
            masterEnabled = settings.hostsEnabled,
            runtimePolicy = runtimePolicy,
            runtimeModuleSelected = selection.hostsModuleSelected,
            initialEnabled = settings.hostsEnabled,
        )

        val effectiveRuntimePolicy = runtimePolicy && (
            (noAds.mode == AdsPatchMode.RUNTIME && noAds.masterEnabled) ||
                selection.hostsModuleSelected
            )

        return AdsPatchPlan(
            noAds = noAds,
            hosts = hosts,
            sdkCoverage = sdkCoverage,
            runtimePolicyEnabled = effectiveRuntimePolicy,
            overlayNoAdsModuleSelected = selection.overlayNoAdsModuleSelected,
            overlayHostsModuleSelected = selection.overlayHostsModuleSelected,
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
