package unipatches.iap

internal data class InAppCoverage(
    val billingClientV3: Boolean = false,
    val openIab: Boolean = false,
    val billingClientV9: Boolean = false,
    val gameMaker: Boolean = false,
    val cocos2d: Boolean = false,
    val revenueCat: Boolean = false,
    val unityIap: Boolean = false,
    val unityIl2Cpp: Boolean = false,
    val legacyAidl: Boolean = false,
    val amazon: Boolean = false,
    val huawei: Boolean = false,
    val samsung: Boolean = false,
    val xsolla: Boolean = false,
)

internal data class InAppPatchOptions(
    val automaticMode: Boolean,
    val fakeStartupPurchases: Boolean,
    val legacyInventoryMode: String,
    val timeouts: Pair<Int, Int>,
    val coverage: InAppCoverage,
)

/**
 * Keeps strategy selection independent from the individual bytecode adapters.
 * Automatic mode enables supported strategies except RevenueCat and Amazon, which require
 * explicit opt-in because their SDK and store paths are app-specific.
 */
internal fun InAppPatchOptions.strategyEnabled(label: String, hasBillingV9Api: Boolean): Boolean {
    val lower = label.lowercase()
    if (lower.startsWith("rc.") || lower.startsWith("revenuecat")) return coverage.revenueCat
    if (lower.startsWith("amazon.")) return coverage.amazon
    if (automaticMode) return true
    return when {
        lower.startsWith("isbillingsupported") || lower.contains("aidl") -> coverage.legacyAidl
        lower.startsWith("openiab") || lower.startsWith("unityplugin") -> coverage.openIab
        lower.startsWith("billingclient") || lower.contains("launchbillingflow") ->
            if (hasBillingV9Api) coverage.billingClientV9 else coverage.billingClientV3
        lower.startsWith("cocos") -> coverage.cocos2d
        lower.startsWith("gamemaker") -> coverage.gameMaker
        lower.startsWith("unity.process") || lower.startsWith("unity.on") || lower.startsWith("unity.cross") -> coverage.unityIap
        lower.startsWith("unity") || lower.contains("il2cpp") -> coverage.unityIl2Cpp
        lower.startsWith("amazon") -> coverage.amazon
        lower.startsWith("huawei") -> coverage.huawei
        lower.startsWith("samsung") -> coverage.samsung
        lower.startsWith("xsolla") -> coverage.xsolla
        lower.startsWith("inventory") -> fakeStartupPurchases || legacyInventoryMode != "preserve"
        lower.contains("crossplatform") || lower.startsWith("processpurchase") || lower.startsWith("onpurchase") ->
            coverage.gameMaker || coverage.unityIl2Cpp
        lower.contains("purchase") || lower.contains("billing") || lower.contains("sku") || lower.contains("offer") ->
            if (hasBillingV9Api) coverage.billingClientV9 else coverage.billingClientV3
        else -> false
    }
}

/** Prevent generic automatic fingerprints from crossing disabled backend namespaces. */
internal fun InAppPatchOptions.backendBoundaryReason(definingClass: String): String? {
    if (!automaticMode) return null
    val type = definingClass.lowercase()
    if (type.startsWith("lcom/revenuecat/") && !coverage.revenueCat) return "RevenueCat coverage disabled"
    if (type.startsWith("lcom/amazon/") && !coverage.amazon) return "Amazon coverage disabled"
    if (type.startsWith("lcom/revenuecat/purchases/amazon/") && !coverage.amazon) return "Amazon coverage disabled"
    return null
}

/** RevenueCat owns its BillingClient lifecycle and expects a single real setup result. */
internal fun preserveRevenueCatStoreLifecycle(label: String, hasRevenueCatSdk: Boolean): Boolean =
    hasRevenueCatSdk && label in setOf(
        "BillingClient.startConnection",
        "BillingClient.isReady",
        "BillingClient.getConnectionState",
        "BillingClient.endConnection",
        "BillingClient.getResponseCode",
        "isFeatureSupported",
    )
