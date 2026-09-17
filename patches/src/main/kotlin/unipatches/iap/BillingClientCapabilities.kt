package unipatches.iap

/** API capabilities detected from the target APK, independent of obfuscated field names. */
internal data class BillingClientCapabilities(
    val billingClientPresent: Boolean = false,
    val productDetails: Boolean = false,
    val skuDetails: Boolean = false,
    val offerTokens: Boolean = false,
    val legacyLaunchFlow: Boolean = false,
)

internal fun BillingClientCapabilities.adapterKey(): String = when {
    productDetails && offerTokens -> "v9"
    skuDetails || legacyLaunchFlow -> "v3"
    else -> "unknown"
}
