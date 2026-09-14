package unipatches.ads

/** Format-level execution inputs passed to No Ads SDK adapters after policy resolution. */
internal data class NoAdsSdkPlan(
    val mode: AdsPatchMode,
    val interstitials: Boolean,
    val banners: Boolean,
    val appOpen: Boolean,
    val mrec: Boolean,
    val rewarded: Boolean,
    val native: Boolean,
)
