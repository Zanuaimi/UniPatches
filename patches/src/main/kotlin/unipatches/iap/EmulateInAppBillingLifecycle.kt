package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import helpers.bytecode.fitsBelowParameters

internal fun applyBillingClientLifecycleAndInventoryPatches(
    context: InAppManagedAdapterContext,
    fakeStartupPurchases: Boolean,
    legacyInventoryMode: String,
    isBillingNamespace: (String) -> Boolean,
    isInterfaceType: (String) -> Boolean,
) {
    val patchAllRaw = context.patchAll
    fun patchAll(fingerprint: Fingerprint, label: String, needRegs: Int = 1, injector: (app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) -> Unit) =
        patchAllRaw(fingerprint, label, needRegs, injector)
    val parameterRegister = context.parameterRegister
    val safeReturn = context.safeReturn
    val okBillingResult = context.okBillingResult
    val expandSwap = context.expandSwap
    val emulateInventory = fakeStartupPurchases || legacyInventoryMode != "preserve"

        // Legacy getBuyIntent() must remain stock. Its BUY_INTENT value is a
        // PendingIntent, not an integer, and replacing it prevents old AIDL
        // clients from opening the billing flow.

        // isBillingSupported (AIDL) -> 0 = BILLING_RESPONSE_RESULT_OK
        patchAll(Fingerprint(name = "isBillingSupported", custom = { _, c -> isBillingNamespace(c.type) }), "isBillingSupported") {
            if (it.returnType == "I") it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
            else if (it.returnType == "Z") it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        patchAll(Fingerprint(name = "isBillingSupportedExtraParams", returnType = "I", custom = { _, c -> isBillingNamespace(c.type) }), "isBillingSupportedExtraParams") {
            it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        }

        // Preserve legacy getPurchases() and the Bundle-shaped catalog APIs by
        // default. Inventing owned inventory is opt-in because old
        // OpenIAB-style wrappers commonly read this path before showing their
        // store UI, and a fabricated owned product there changes what the store
        // displays.
        //
        // The modern callback query is different and is always answered: a
        // wrapper that just granted an emulated purchase re-reads its own
        // inventory from inside onPurchasesUpdated to resolve that purchase,
        // and an untouched query answered that read as "purchase not found",
        // dropping the grant. The answer is the purchase that was delivered,
        // and nothing at all when none was, so a cold start still reports an
        // empty inventory and no owned product is ever fabricated.
        for (qn in listOf("getPurchases", "queryPurchases", "queryPurchasesAsync", "queryPurchaseHistory", "queryPurchaseHistoryAsync", "queryPurchasesHistory")) {
            val modernCallbackQuery = qn.contains("Async")
            if (!emulateInventory && !modernCallbackQuery) continue
            patchAll(Fingerprint(name = qn, custom = { m, c -> c.type.contains("BillingClient") || m.definingClass.contains("billing") || isBillingNamespace(c.type) }), "Inventory.$qn", 3) {
                // fakeStartupPurchases is intended for modern callback-based
                // inventory. Do not let it alter legacy Bundle APIs while
                // the dedicated legacy setting remains in preserve mode.
                if (it.returnType == "Landroid/os/Bundle;" && legacyInventoryMode == "preserve") return@patchAll
                val catalogInputs = it.parameterTypes.mapIndexedNotNull { index, type ->
                    if ((type.startsWith("L") || type.startsWith("[")) &&
                        (type.toString().lowercase().contains("sku") || type.toString().lowercase().contains("product") || type.toString().lowercase().contains("purchase")))
                        parameterRegister(it, index) else null
                }.distinct()
                val rememberCatalog = catalogInputs.joinToString("\n") { register ->
                    "invoke-static {$register}, Lunipatch/overlaycore/InAppRuntimePolicy;->rememberCatalogProduct(Ljava/lang/Object;)V"
                }
                val listenerIdx = it.parameterTypes.indexOfFirst { p -> p.contains("PurchasesResponseListener") || p.contains("PurchaseHistoryResponseListener") }
                if (listenerIdx >= 0 && it.returnType == "V") {
                    val isHistory = it.parameterTypes[listenerIdx].contains("History")
                    val listenerReg = parameterRegister(it, listenerIdx)
                    val iface = if (isHistory) "Lcom/android/billingclient/api/PurchaseHistoryResponseListener;" else "Lcom/android/billingclient/api/PurchasesResponseListener;"
                    val cb = if (isHistory) "onPurchaseHistoryResponse" else "onQueryPurchasesResponse"
                    if (!fakeStartupPurchases) {
                        // A wrapper that granted an emulated purchase re-reads its
                        // own inventory to resolve it, and an empty answer turns
                        // that grant into "purchase not found". Report the
                        // purchase that was just delivered when there is one; a
                        // cold start still reports an empty inventory.
                        val emptyBlock = """
                            $rememberCatalog
                            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                            move-result-object v0
                            const/4 v1, 0x0
                            invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                            move-result-object v0
                            invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
                            move-result-object v0
                            new-instance v1, Ljava/util/ArrayList;
                            invoke-direct {v1}, Ljava/util/ArrayList;-><init>()V
                            const-class v2, ${if (isHistory) "Lcom/android/billingclient/api/PurchaseHistoryRecord;" else "Lcom/android/billingclient/api/Purchase;"}
                            invoke-static {v2}, Lunipatch/overlaycore/InAppRuntimePolicy;->deliveredPurchase(Ljava/lang/Class;)Ljava/lang/Object;
                            move-result-object v2
                            if-eqz v2, :morphe_iap_query_empty
                            invoke-virtual {v1, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :morphe_iap_query_empty
                            move-object/from16 v2, $listenerReg
                            if-eqz v2, :morphe_iap_query_done
                            invoke-interface {v2, v0, v1}, $iface->$cb(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
                            :morphe_iap_query_done
                            return-void
                        """.trimIndent()
                        // The listener is read back after v0/v1 are written, so a tight
                        // frame (p0Register <= 2) would pass the freshly built result
                        // object where the listener belongs.
                        if (it.fitsBelowParameters(emptyBlock)) {
                            it.addInstructions(0, emptyBlock)
                        } else expandSwap(it, emptyBlock)
                    } else {
                    // v0..v3: expanded into a grown frame via expandSwap so
                    // tiny delegate frames (e.g. 3-reg BillingClientImpl
                    // methods) verify instead of killing their class.
                    val block = """
                        $rememberCatalog
                        invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                        move-result-object v0
                        const/4 v1, 0x0
                        invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                        move-result-object v0
                        invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
                        move-result-object v0
                        new-instance v1, Ljava/util/ArrayList;
                        invoke-direct {v1}, Ljava/util/ArrayList;-><init>()V
                        const-class v2, ${if (isHistory) "Lcom/android/billingclient/api/PurchaseHistoryRecord;" else "Lcom/android/billingclient/api/Purchase;"}
                        invoke-static {v2}, Lunipatch/overlaycore/InAppRuntimePolicy;->emulatedInventoryPurchase(Ljava/lang/Class;)Ljava/lang/Object;
                        move-result-object v2
                        if-eqz v2, :morphe_iap_query_empty
                        invoke-virtual {v1, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                        :morphe_iap_query_empty
                        move-object/from16 v2, $listenerReg
                        if-eqz v2, :morphe_iap_query_done
                        invoke-interface {v2, v0, v1}, $iface->$cb(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
                        :morphe_iap_query_done
                        return-void
                    """.trimIndent()
                    if (!expandSwap(it, block) && it.fitsBelowParameters(block)) {
                        it.addInstructions(0, block)
                    }
                    }
                } else when {
                    it.returnType.contains("List") -> it.addInstructions(0, "invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;\nmove-result-object v0\nreturn-object v0")
                    it.returnType == "Landroid/os/Bundle;" -> it.addInstructions(0, """
                        new-instance v0, Landroid/os/Bundle;
                        invoke-direct {v0}, Landroid/os/Bundle;-><init>()V
                        const-string v1, "RESPONSE_CODE"
                        const/4 v2, 0x0
                        invoke-virtual {v0, v1, v2}, Landroid/os/Bundle;->putInt(Ljava/lang/String;I)V
                        const-string v1, "INAPP_PURCHASE_DATA_LIST"
                        new-instance v2, Ljava/util/ArrayList;
                        invoke-direct {v2}, Ljava/util/ArrayList;-><init>()V
                        // Fake mode never invents an owned product ID. The empty
                        // response is intentional until a requested catalog ID
                        // can be associated with the inventory query.
                        invoke-virtual {v0, v1, v2}, Landroid/os/Bundle;->putStringArrayList(Ljava/lang/String;Ljava/util/ArrayList;)V
                        return-object v0
                    """.trimIndent())
                    else -> try { it.addInstructions(0, safeReturn(it)) } catch (_: Exception) {}
                }
            }
        }

        // queryProductDetailsAsync / querySkuDetailsAsync: the listener is
        // routed through InAppRuntimePolicy.wrap*Listener so a device with no
        // Play connection (empty response, e.g. "Service connection is
        // disconnected") gets synthesized entries for exactly the IDs the game
        // requested, while a populated stock catalog is forwarded untouched.
        // The wrap call returns Object, so check-cast restores the declared
        // listener type for the stock body below. The block touches only
        // parameter registers (no vN), so it fits every frame without
        // expansion. The sync List variants (queryProductDetails /
        // querySkuDetails) stay stock: faking them stalls Unity shop init on
        // loading screens, and the grant happens at buy time via
        // launchBillingFlow above.
        for ((queryName, queryParamsType, queryListenerType) in listOf(
            Triple("queryProductDetailsAsync", "QueryProductDetailsParams", "ProductDetailsResponseListener"),
            Triple("querySkuDetailsAsync", "SkuDetailsParams", "SkuDetailsResponseListener"),
        )) {
            patchAll(Fingerprint(name = queryName, custom = { m, c ->
                m.returnType == "V" && m.parameterTypes.size == 2 &&
                    m.parameterTypes[0].contains(queryParamsType) &&
                    m.parameterTypes[1].contains(queryListenerType) &&
                    isBillingNamespace(c.type)
            }), "BillingClient.$queryName", 3) {
                // A class-typed listener (Unity's proxy wrappers) can never be a
                // java.lang.reflect.Proxy, so those bridges keep the stock path.
                if (!isInterfaceType(it.parameterTypes[1].toString())) return@patchAll
                val wrapper = if (queryParamsType.startsWith("Query")) "wrapProductDetailsListener" else "wrapSkuDetailsListener"
                val paramsReg = parameterRegister(it, 0)
                val listenerReg = parameterRegister(it, 1)
                it.addInstructions(0, """
                    invoke-static {$paramsReg, $listenerReg}, Lunipatch/overlaycore/InAppRuntimePolicy;->$wrapper(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object $listenerReg
                    check-cast $listenerReg, ${it.parameterTypes[1]}
                """.trimIndent())
            }
        }

        // Legacy getSkuDetails / getProductDetails AIDL methods are left
        // stock so their real product data reaches older app catalogs.

        // consumePurchase / consumeAsync -> fire listener callback with OK, else spoof return
        for (cn in listOf("consumePurchase", "consumeAsync", "consumePurchaseAsync")) {
            patchAll(Fingerprint(name = cn, custom = { _, c ->
                val t = c.type.lowercase()
            isBillingNamespace(c.type) || t.contains("purchase") || t.contains("iap")
            }), cn, 3) {
                val listenerIdx = it.parameterTypes.indexOfFirst { p -> p.contains("ConsumeResponseListener") }
                if (listenerIdx == 1 && it.parameterTypes.size == 2 && it.parameterTypes[0].contains("ConsumeParams") && it.returnType == "V") {
                    val listenerReg = parameterRegister(it, listenerIdx)
                    val paramsReg = parameterRegister(it, 0)
                    // params are (ConsumeParams, ConsumeResponseListener); token is first param -> p1
                    val block = """
                        invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                        move-result-object v0
                        const/4 v1, 0x0
                        invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                        move-result-object v0
                        invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
                        move-result-object v0
                        move-object/from16 v1, $paramsReg
                        invoke-virtual {v1}, Lcom/android/billingclient/api/ConsumeParams;->getPurchaseToken()Ljava/lang/String;
                        move-result-object v1
                        move-object/from16 v2, $listenerReg
                        if-eqz v2, :morphe_iap_consume_done
                        invoke-interface {v2, v0, v1}, Lcom/android/billingclient/api/ConsumeResponseListener;->onConsumeResponse(Lcom/android/billingclient/api/BillingResult;Ljava/lang/String;)V
                        :morphe_iap_consume_done
                        return-void
                    """.trimIndent()
                    // ConsumeParams and the listener are read back after v0/v1 are
                    // written. In a tight frame those v registers are the parameters
                    // themselves, so getPurchaseToken() would run on null.
                    if (it.fitsBelowParameters(block)) {
                        it.addInstructions(0, block)
                    } else expandSwap(it, block)
                } else when {
                    it.returnType.contains("BillingResult") -> it.addInstructions(0, okBillingResult)
                    it.returnType == "Landroid/os/Bundle;" -> it.addInstructions(0, """
                        new-instance v0, Landroid/os/Bundle;
                        invoke-direct {v0}, Landroid/os/Bundle;-><init>()V
                        const-string v1, "RESPONSE_CODE"
                        const/4 v2, 0x0
                        invoke-virtual {v0, v1, v2}, Landroid/os/Bundle;->putInt(Ljava/lang/String;I)V
                        return-object v0
                    """.trimIndent())
                    it.returnType == "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                    it.returnType == "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                    else -> it.addInstructions(0, safeReturn(it))
                }
            }
        }

        patchAll(Fingerprint(name = "acknowledgePurchase", custom = { _, c ->
            val t = c.type.lowercase()
            isBillingNamespace(c.type) || t.contains("purchase")
        }), "acknowledgePurchase", 2) {
            val listenerIdx = it.parameterTypes.indexOfFirst { p -> p.contains("AcknowledgePurchaseResponseListener") }
            if (listenerIdx == 1 && it.parameterTypes.size == 2 && it.parameterTypes[0].contains("AcknowledgePurchaseParams") && it.returnType == "V") {
                val listenerReg = parameterRegister(it, listenerIdx)
                it.addInstructions(0, """
                    invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                    move-result-object v0
                    const/4 v1, 0x0
                    invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                    move-result-object v0
                    invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
                    move-result-object v0
                    move-object/from16 v1, $listenerReg
                    if-eqz v1, :morphe_iap_ack_done
                    invoke-interface {v1, v0}, Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;->onAcknowledgePurchaseResponse(Lcom/android/billingclient/api/BillingResult;)V
                    :morphe_iap_ack_done
                    return-void
                """.trimIndent())
            } else when {
                it.returnType.contains("BillingResult") -> it.addInstructions(0, okBillingResult)
                it.returnType == "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                else -> it.addInstructions(0, safeReturn(it))
            }
        }

        patchAll(Fingerprint(name = "getBillingConfig", custom = { _, c -> isBillingNamespace(c.type) }), "getBillingConfig", 2) {
            when {
                it.returnType.contains("BillingResult") -> it.addInstructions(0, okBillingResult)
                else -> it.addInstructions(0, safeReturn(it))
            }
        }

        // OpenIAB SkuDetails is catalog data, not purchase ownership data.
        // Leave its price getters untouched so the app receives the real
        // store price and does not hide otherwise valid products.
        for (pm in listOf("getPrice", "getOriginalPrice", "getFormattedPrice", "getDisplayPrice", "getPriceString")) {
            patchAll(Fingerprint(name = pm, returnType = "Ljava/lang/String;", custom = { m, c ->
                val t = c.type.lowercase()
                !OpenIabCatalogPolicy.isCatalogGetter(c.type, m.name, m.parameterTypes.size) &&
                    (t.contains("sku") || t.contains("product") || t.contains("billing"))
            }), pm) {
                if (it.parameterTypes.isEmpty() && !OpenIabCatalogPolicy.isCatalogGetter(it.definingClass, it.name, it.parameterTypes.size)) {
                    it.addInstructions(0, "const-string v0, \"0.00\"\nreturn-object v0")
                }
            }
        }
        // OneTimePurchaseOfferDetails / SubscriptionOfferDetails micros
        patchAll(Fingerprint(name = "getPriceAmountMicros", returnType = "J", custom = { _, c ->
            val t = c.type.lowercase()
            t.contains("offer") || t.contains("product") || t.contains("sku") || t.contains("billing")
        }), "getPriceAmountMicros") {
            it.addInstructions(0, "const-wide/16 v0, 0x0\nreturn-wide v0")
        }
        patchAll(Fingerprint(name = "getPriceAmountMicros", custom = { _, c -> c.type.lowercase().contains("offer") }), "Offer.getPriceAmountMicros") {
            if (it.returnType == "J") it.addInstructions(0, "const-wide/16 v0, 0x0\nreturn-wide v0")
        }
        // Purchase state getters -> look owned/valid (scoped to billing/purchase classes only;
        // all purchase metadata getters remain stock so product/package/token information
        // from real or factory-created purchases is preserved.
        patchAll(Fingerprint(name = "getPurchaseState", returnType = "I", custom = { _, c -> val t = c.type.lowercase(); t.contains("billing") || t.contains("purchase") }), "Purchase.getPurchaseState") {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        patchAll(Fingerprint(name = "isAcknowledged", returnType = "Z", custom = { _, c -> val t = c.type.lowercase(); t.contains("billing") || t.contains("purchase") }), "Purchase.isAcknowledged") {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        patchAll(Fingerprint(name = "getQuantity", returnType = "I", custom = { _, c -> val t = c.type.lowercase(); t.contains("billing") || t.contains("purchase") }), "Purchase.getQuantity") {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        patchAll(Fingerprint(name = "isFeatureSupported", custom = { _, c -> isBillingNamespace(c.type) }), "isFeatureSupported", 2) {
            when {
                it.returnType.contains("BillingResult") -> it.addInstructions(0, okBillingResult)
                it.returnType == "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                it.returnType == "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
            }
        }

        // Explicit prefix: automaticCandidate counts only one indicator on this
        // method (empty params, int return), so the bare label never matched.
        // Same latch as isReady: DISCONNECTED until startConnection runs, so
        // state-gated connection checks fall through to startConnection instead
        // of returning "connected" on a client that never connected.
        patchAll(Fingerprint(name = "getConnectionState", returnType = "I", custom = { _, c -> isBillingNamespace(c.type) }), "BillingClient.getConnectionState") {
            it.addInstructions(0, "invoke-static {}, Lunipatch/overlaycore/InAppRuntimePolicy;->connectionState()I\nmove-result v0\nreturn v0")
        }

        // Stock store failures (SERVICE_UNAVAILABLE, NETWORK_ERROR, ...) must not
        // reach the game as failures: a non-OK setup/catalog/inventory code flips
        // the game's own billing gate to "unavailable" even though every emulated
        // call succeeds. Results this patch builds stamp their real code through
        // InAppRuntimePolicy first, so cancel and validation failures still read
        // as failures. p0 is the BillingResult receiver; v0 is dead on entry.
        patchAll(Fingerprint(name = "getResponseCode", returnType = "I", custom = { _, c -> c.type == "Lcom/android/billingclient/api/BillingResult;" }), "BillingClient.getResponseCode", 1) {
            if (com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags)) return@patchAll
            // p0 is `this` for the instance getter; parameterRegister() indexes
            // real arguments, which this method has none of.
            it.addInstructions(0, "invoke-static {p0}, Lunipatch/overlaycore/InAppRuntimePolicy;->billingResponseCode(Ljava/lang/Object;)I\nmove-result v0\nreturn v0")
        }

        // NOTE: onBillingSetupFinished is intentionally left intact: the game
        // learns billing is ready through its own listener, and startConnection
        // above already fires it with OK. Suppressing it breaks init.

        // ──────────────────────────────────────────────

}
