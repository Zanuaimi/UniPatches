package unipatches.iap

internal object BillingV9Adapter : BillingClientAdapter {
    override val key = "v9"
    override fun supports(capabilities: BillingClientCapabilities) =
        capabilities.billingClientPresent && capabilities.productDetails && capabilities.offerTokens
    override fun apply(context: InAppManagedAdapterContext) =
        applyBillingClientCorePatches(context.copy(billingAdapterKey = key), BillingClientFlowSpec.V9)
}
