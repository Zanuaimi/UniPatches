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
 * Automatic mode deliberately short-circuits this check so newly supported
 * strategies remain covered without requiring a settings migration.
 */
internal fun InAppPatchOptions.strategyEnabled(label: String, hasBillingV9Api: Boolean): Boolean {
    if (automaticMode) return true
    val lower = label.lowercase()
    return when {
        lower.startsWith("isbillingsupported") || lower.contains("aidl") -> coverage.legacyAidl
        lower.startsWith("openiab") || lower.startsWith("unityplugin") -> coverage.openIab
        lower.startsWith("billingclient") || lower.contains("launchbillingflow") ->
            if (hasBillingV9Api) coverage.billingClientV9 else coverage.billingClientV3
        lower.startsWith("cocos") -> coverage.cocos2d
        lower.startsWith("gamemaker") -> coverage.gameMaker
        lower.startsWith("unity.process") || lower.startsWith("unity.on") || lower.startsWith("unity.cross") -> coverage.unityIap
        lower.startsWith("unity") || lower.contains("il2cpp") -> coverage.unityIl2Cpp
        lower.startsWith("rc.") || lower.startsWith("revenuecat") -> coverage.revenueCat
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
