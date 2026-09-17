package unipatches.iap

internal object BillingV3Adapter : BillingClientAdapter {
    override val key = "v3"
    override fun supports(capabilities: BillingClientCapabilities) =
        capabilities.billingClientPresent && !capabilities.productDetails &&
            (capabilities.skuDetails || capabilities.legacyLaunchFlow)
    override fun apply(context: InAppManagedAdapterContext) =
        applyBillingClientCorePatches(context.copy(billingAdapterKey = key))
}
