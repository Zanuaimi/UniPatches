package unipatches.ads

internal enum class AdsPatchMode {
    DISABLED,
    STATIC,
    RUNTIME,
}

internal enum class AdsPatchExecutionMode {
    DISABLED,
    STATIC,
    RUNTIME,
    HYBRID,
}

internal data class PathPlan(
    val mode: AdsPatchMode,
    val masterEnabled: Boolean,
    val runtimeModuleSelected: Boolean,
    val initialEnabled: Boolean,
) {
    val runtimeEligible: Boolean
        get() = mode == AdsPatchMode.RUNTIME && masterEnabled && runtimeModuleSelected
}

internal data class AdsPatchPlan(
    val noAds: PathPlan,
    val rewards: PathPlan,
    val hosts: PathPlan,
    val sdkCoverage: AdsSdkCoverage,
    val runtimePolicyEnabled: Boolean,
) {
    val executionMode: AdsPatchExecutionMode
        get() {
            val modes = listOf(noAds.mode, rewards.mode, hosts.mode).toSet()
            return when {
                modes == setOf(AdsPatchMode.DISABLED) -> AdsPatchExecutionMode.DISABLED
                modes == setOf(AdsPatchMode.STATIC) || modes == setOf(AdsPatchMode.DISABLED, AdsPatchMode.STATIC) -> AdsPatchExecutionMode.STATIC
                modes == setOf(AdsPatchMode.RUNTIME) || modes == setOf(AdsPatchMode.DISABLED, AdsPatchMode.RUNTIME) -> AdsPatchExecutionMode.RUNTIME
                else -> AdsPatchExecutionMode.HYBRID
            }
        }

    val runtimeModuleMask: Int
        get() {
            if (!runtimePolicyEnabled) return 0
            var mask = 0
            if (noAds.runtimeEligible) mask = mask or AdsRuntimeModule.BLOCK_ADS
            if (rewards.runtimeEligible) mask = mask or AdsRuntimeModule.REWARDS
            // Hosts visibility is selected by policy and module selection; its master controls
            // the initial switch and whether host instrumentation exists.
            if (hosts.runtimeModuleSelected) mask = mask or AdsRuntimeModule.HOSTS
            return mask
        }
}

internal object AdsRuntimeModule {
    const val BLOCK_ADS = 1
    const val REWARDS = 2
    const val HOSTS = 4
}
