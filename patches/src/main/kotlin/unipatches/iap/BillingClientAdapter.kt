package unipatches.iap

/** Version adapter contract. Adapters must only describe version-specific capabilities. */
internal interface BillingClientAdapter {
    val key: String
    fun supports(capabilities: BillingClientCapabilities): Boolean
    fun apply(context: InAppManagedAdapterContext)
}
