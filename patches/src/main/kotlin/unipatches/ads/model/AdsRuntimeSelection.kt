package unipatches.ads

internal data class AdsRuntimeSelection(
    val policyEnabled: Boolean,
    val noAdsModuleSelected: Boolean,
    val rewardsModuleSelected: Boolean,
    val hostsModuleSelected: Boolean,
    val overlayNoAdsModuleSelected: Boolean = noAdsModuleSelected,
    val overlayRewardsModuleSelected: Boolean = rewardsModuleSelected,
    val overlayHostsModuleSelected: Boolean = hostsModuleSelected,
)
