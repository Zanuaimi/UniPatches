package unipatches.iap

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Resolves the PurchasesUpdatedListener on a BillingClient implementation into
 * a snippet that loads it into v0, or null when the class does not hold one.
 *
 * Two rules, both learned from a real crash:
 *
 * 1. The holder's listener field is frequently PRIVATE FINAL. Billing's own
 *    code reads it from inside the holder class, which is legal; an injected
 *    snippet in the BillingClient class reading the same field throws
 *    IllegalAccessError at runtime, after patching and installing cleanly.
 *    So a holder is entered through a public accessor when the class has one,
 *    and a direct field read is only emitted for a field the reading class can
 *    actually reach.
 *
 * 2. Access flags decide that, not the descriptor alone. A field of the right
 *    type with the wrong access is worse than no match: it produces bytecode
 *    that installs and then dies on the first purchase.
 */
internal fun BytecodePatchContext.resolveBillingListener(defClass: String): String? = try {
    val cls = mutableClassDefByOrNull(defClass) ?: return null
    val listenerType = "Lcom/android/billingclient/api/PurchasesUpdatedListener;"

    // A field declared on the client itself that this class can read.
    val direct = cls.fields.firstOrNull {
        it.type == listenerType && cls.type.readsField(defClass, it.accessFlags)
    }
    if (direct != null) return "iget-object v0, v0, $defClass->${direct.name}:${direct.type}"

    for (field in cls.fields) {
        val holder = field.type
        if (!holder.startsWith("Lcom/android/billingclient/api/") || holder.contains("Listener;")) continue
        if (!cls.type.readsField(defClass, field.accessFlags)) continue
        val holderClass = mutableClassDefByOrNull(holder) ?: continue

        // Prefer the accessor: it is the only route that survives a private field.
        // Package-private counts here, because both classes sit in
        // com.android.billingclient.api and the injector runs inside the client.
        val accessor = holderClass.methods.firstOrNull { method ->
            method.returnType == listenerType &&
                method.parameterTypes.isEmpty() &&
                method.implementation != null &&
                method.name != "<init>" &&
                !AccessFlags.PRIVATE.isSet(method.accessFlags) &&
                !AccessFlags.PROTECTED.isSet(method.accessFlags) &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
        }
        if (accessor != null) {
            return "iget-object v0, v0, $defClass->${field.name}:${field.type}\n" +
                "if-eqz v0, :morphe_iap_no_listener\n" +
                // move-result-object is mandatory after invoke: without it v0 still
                // holds the holder and the call site hands dispatch() the client
                // implementation instead of the listener.
                "invoke-virtual {v0}, $holder->${accessor.name}()$listenerType\n" +
                "move-result-object v0"
        }

        val inner = holderClass.fields.firstOrNull {
            it.type == listenerType && cls.type.readsField(holder, it.accessFlags)
        } ?: continue
        return "iget-object v0, v0, $defClass->${field.name}:${field.type}\n" +
            "if-eqz v0, :morphe_iap_no_listener\n" +
            "iget-object v0, v0, $holder->${inner.name}:${inner.type}"
    }
    null
} catch (_: Exception) { null }

/**
 * Whether a class may read a field declared on [owner]. Same class, same
 * package, or a public/protected field all work; anything else is off limits.
 * Obfuscated billing classes keep their package, so the package test compares
 * the descriptor prefixes.
 */
private fun String.readsField(owner: String, accessFlags: Int): Boolean {
    if (AccessFlags.PUBLIC.isSet(accessFlags) || AccessFlags.PROTECTED.isSet(accessFlags)) return true
    if (this == owner) return true
    return this.substringBeforeLast('/') == owner.substringBeforeLast('/')
}

internal fun buildBillingPurchaseBlock(
    listenerIget: String,
    productArguments: List<String?>,
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
        invoke-virtual {v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
        move-result-object v1
        return-object v1
    """.trimIndent()
}
