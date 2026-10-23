package unipatches.iap

/** Shared BillingClient coordinator; version-specific selection is capability-based. */
internal fun applyBillingClientPatches(context: InAppManagedAdapterContext) {
    BillingClientDispatcher.select(context.billingCapabilities)?.apply(context)
}
