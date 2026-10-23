package unipatches.iap

internal object BillingClientDispatcher {
    private val adapters = listOf(BillingV9Adapter, BillingV3Adapter)

    fun select(capabilities: BillingClientCapabilities): BillingClientAdapter? {
        val key = capabilities.adapterKey()
        return adapters.firstOrNull { it.key == key && it.supports(capabilities) }
    }
}
