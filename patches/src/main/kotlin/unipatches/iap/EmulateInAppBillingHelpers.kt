package unipatches.iap

import app.morphe.patcher.patch.BytecodePatchContext

internal fun BytecodePatchContext.resolveBillingListener(defClass: String): String? = try {
    val cls = mutableClassDefByOrNull(defClass) ?: return null
    val direct = cls.fields.firstOrNull { it.type == "Lcom/android/billingclient/api/PurchasesUpdatedListener;" }
    if (direct != null) return "iget-object v0, v0, $defClass->${direct.name}:${direct.type}"
    for (field in cls.fields) {
        val holder = field.type
        if (!holder.startsWith("Lcom/android/billingclient/api/") || holder.contains("Listener;")) continue
        val holderClass = mutableClassDefByOrNull(holder) ?: continue
        val inner = holderClass.fields.firstOrNull { it.type == "Lcom/android/billingclient/api/PurchasesUpdatedListener;" } ?: continue
        return "iget-object v0, v0, $defClass->${field.name}:${field.type}\n" +
            "if-eqz v0, :morphe_iap_no_listener\n" +
            "iget-object v0, v0, $holder->${inner.name}:${inner.type}"
    }
    null
} catch (_: Exception) { null }

internal fun buildBillingPurchaseBlock(
    listenerIget: String,
    productArguments: List<String>,
    nonOverlayTimeout: Int,
    overlayTimeout: Int,
    cancelledBillingResult: String,
): String {
    val first = productArguments.getOrNull(0)?.let { "move-object/from16 v1, $it" } ?: "const/4 v1, 0x0"
    val second = productArguments.getOrNull(1)?.let { "move-object/from16 v2, $it" } ?: "const/4 v2, 0x0"
    return """
        move-object/from16 v4, p0
        move-object/from16 v0, p0
        $listenerIget
        if-eqz v0, :morphe_iap_no_listener
        $first
        $second
        invoke-static {v4, v0, v1, v2}, Lunipatch/overlaycore/InAppRuntimePolicy;->validateModernPurchase(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)I
        move-result v3
        if-eqz v3, :morphe_iap_valid
        invoke-static {v3}, Lunipatch/overlaycore/InAppRuntimePolicy;->billingResult(I)Ljava/lang/Object;
        move-result-object v1
        check-cast v1, Lcom/android/billingclient/api/BillingResult;
        return-object v1
        :morphe_iap_valid
        $first
        $second
        const v3, $nonOverlayTimeout
        const v4, $overlayTimeout
        invoke-static {v3, v4}, Lunipatch/overlaycore/InAppRuntimePolicy;->configureTimeouts(II)V
        invoke-static {v0, v1, v2}, Lunipatch/overlaycore/InAppRuntimePolicy;->dispatch(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
        move-result v3
        if-eqz v3, :morphe_iap_cancelled
        goto :morphe_iap_nocb
        :morphe_iap_cancelled
        $cancelledBillingResult
        :morphe_iap_no_listener
        $cancelledBillingResult
        :morphe_iap_nocb
        invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
        move-result-object v1
        const/4 v2, 0x0
        invoke-virtual {v1, v2}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
        move-result-object v1
        invoke-virtual {v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
        move-result-object v1
        return-object v1
    """.trimIndent()
}
