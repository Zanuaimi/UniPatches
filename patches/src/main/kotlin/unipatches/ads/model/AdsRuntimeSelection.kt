package unipatches.ads

internal data class AdsRuntimeSelection(
    val policyEnabled: Boolean,
    val noAdsModuleSelected: Boolean,
    val hostsModuleSelected: Boolean,
    val overlayNoAdsModuleSelected: Boolean = noAdsModuleSelected,
    val overlayHostsModuleSelected: Boolean = hostsModuleSelected,
)
