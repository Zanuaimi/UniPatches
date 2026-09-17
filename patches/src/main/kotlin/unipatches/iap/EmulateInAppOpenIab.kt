package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

/** OpenIAB and UnityPlugin adapter for legacy Unity billing flows. */
internal fun applyOpenIabPatches(context: InAppManagedAdapterContext) {
    val patchAll = context.patchAll
    val minRegs = context.minRegs
    val expandSwap = context.expandSwap
    val parameterRegister = context.parameterRegister
    val nonOverlayTimeout = context.nonOverlayTimeout
    val overlayTimeout = context.overlayTimeout

    val unityPlugin = "Lorg/onepf/openiab/UnityPlugin;"
    for ((methodName, inapp) in listOf("purchaseProduct" to true, "purchaseSubscription" to false)) {
        patchAll(Fingerprint(
            name = methodName,
            definingClass = unityPlugin,
            returnType = "V",
            custom = { method, _ -> method.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/String;") },
        ), "UnityPlugin.$methodName-entry", 3) { method ->
            val block = """
                move-object/from16 v0, p0
                move-object/from16 v1, p1
                move-object/from16 v2, p2
                const/4 v3, ${if (inapp) "0x1" else "0x0"}
                const v4, $nonOverlayTimeout
                const v5, $overlayTimeout
                invoke-static/range {v0 .. v5}, Lunipatch/overlaycore/InAppRuntimePolicy;->interceptLegacyPurchaseEntry(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;ZII)Z
                move-result v0
                if-eqz v0, :morphe_unity_original_purchase
                return-void
                :morphe_unity_original_purchase
            """.trimIndent()
            // Always inject into an expanded clone.  The timeout arguments
            // must never be written into p0/p1/etc.; on small frames those
            // aliases can be the original method parameters (including
            // UnityPlugin's `this` register).  Expansion also gives the
            // generated range invoke stable scratch registers.
            expandSwap(method, block)
        }
    }

    val openIabHelper = "Lorg/onepf/oms/OpenIabHelper;"
    val listenerType = "Lorg/onepf/oms/appstore/googleUtils/IabHelper${'$'}OnIabPurchaseFinishedListener;"
    val signatures = listOf(
        listOf("Landroid/app/Activity;", "Ljava/lang/String;", "I", listenerType),
        listOf("Landroid/app/Activity;", "Ljava/lang/String;", "I", listenerType, "Ljava/lang/String;"),
        listOf("Landroid/app/Activity;", "Ljava/lang/String;", "Ljava/lang/String;", "I", listenerType, "Ljava/lang/String;"),
    )
    for (flowName in listOf("launchPurchaseFlow", "launchSubscriptionPurchaseFlow")) {
        for (signature in signatures) {
            patchAll(Fingerprint(
                name = flowName,
                definingClass = openIabHelper,
                returnType = "V",
                custom = { method, _ -> method.parameterTypes == signature },
            ), "OpenIAB.$flowName", 3) { method ->
                val listenerIndex = signature.indexOf(listenerType)
                val activity = parameterRegister(method, 0)
                val sku = parameterRegister(method, 1)
                val listener = parameterRegister(method, listenerIndex)
                val payload = if (signature.size >= 5) parameterRegister(method, signature.lastIndex) else null
                val payloadInstruction = if (payload == null) "const-string v3, \"\"" else "move-object/from16 v3, $payload"
                val inappInstruction = if (signature.size == 6 && flowName == "launchPurchaseFlow") {
                    val itemType = parameterRegister(method, 2)
                    """
                    const-string v4, "subs"
                    move-object/from16 v5, $itemType
                    invoke-virtual {v5, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                    move-result v4
                    xor-int/lit8 v4, v4, 0x1
                    """.trimIndent()
                } else "const/4 v4, ${if (flowName == "launchPurchaseFlow") "0x1" else "0x0"}"
                val block = """
                    move-object/from16 v0, $listener
                    move-object/from16 v1, $sku
                    move-object/from16 v2, $activity
                    $payloadInstruction
                    $inappInstruction
                    const v5, $nonOverlayTimeout
                    const v6, $overlayTimeout
                    invoke-static {v5, v6}, Lunipatch/overlaycore/InAppRuntimePolicy;->configureTimeouts(II)V
                    invoke-static {v0, v2, v1, v3, v4}, Lunipatch/overlaycore/InAppRuntimePolicy;->routeLegacyPurchase(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Z)Z
                    move-result v5
                    if-eqz v5, :morphe_openiab_original_purchase_flow
                    return-void
                    :morphe_openiab_original_purchase_flow
                """.trimIndent()
                if (minRegs(method) >= 7) {
                    try { method.addInstructions(0, block) } catch (_: Exception) { expandSwap(method, block) }
                } else expandSwap(method, block)
            }
        }
    }
}
