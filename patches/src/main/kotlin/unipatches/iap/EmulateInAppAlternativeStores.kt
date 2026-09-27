package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

/** Patches optional third-party store adapters without touching Play billing adapters. */
internal fun applyAlternativeStorePatches(context: InAppManagedAdapterContext) {
    val patchAll = context.patchAll
    val safeReturn = context.safeReturn
    val okBillingResult = context.okBillingResult

    patchAll(Fingerprint(name = "launchBillingFlow", custom = { _, c -> c.type.lowercase().contains("xsolla") }), "Xsolla.launchBillingFlow", 1) {
        val block = when {
            it.returnType.contains("BillingResult") -> okBillingResult
            it.returnType == "Z" -> "const/4 v0, 0x1\nreturn v0"
            it.returnType == "I" -> "const/4 v0, 0x0\nreturn v0"
            else -> safeReturn(it)
        }
        it.addInstructions(0, block)
    }
    for (name in listOf("isAvailable", "isUserAvailable", "isPaymentAvailable", "isInventoryAvailable", "isStoreAvailable")) {
        patchAll(Fingerprint(name = name, returnType = "Z", custom = { _, c -> c.type.lowercase().contains("xsolla") }), "Xsolla.$name", 1) {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
    }
    for (name in listOf("getAmount", "getBalance", "getVirtualCurrencyBalance", "getInventory")) {
        patchAll(Fingerprint(name = name, returnType = "I", custom = { _, c -> c.type.lowercase().contains("xsolla") }), "Xsolla.$name", 1) {
            it.addInstructions(0, "const v0, 0xf423f\nreturn v0")
        }
    }
    for (name in listOf("openPayStation", "openPurchase", "createPayment", "validatePurchase", "checkOrder", "getPayStationUrl")) {
        patchAll(Fingerprint(name = name, custom = { _, c -> c.type.lowercase().contains("xsolla") }), "Xsolla.$name", 1) {
            when {
                it.returnType == "V" -> it.addInstructions(0, "return-void")
                it.returnType == "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                it.returnType.contains("String") -> it.addInstructions(0, "const-string v0, \"https://paystation.xsolla.com\"\nreturn-object v0")
            }
        }
    }

    for (name in listOf("purchase", "getUserData", "getProductData", "getPurchaseUpdates", "onProductDataResponse", "onPurchaseResponse", "onUserDataResponse")) {
        patchAll(Fingerprint(name = name, custom = { _, c -> c.type.lowercase().contains("amazon") }), "Amazon.$name", 1) {
            when (it.returnType) {
                "V" -> it.addInstructions(0, "return-void")
                "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                else -> if (it.returnType.contains("String")) it.addInstructions(0, "const-string v0, \"\"\nreturn-object v0") else it.addInstructions(0, safeReturn(it))
            }
        }
    }
    patchAll(Fingerprint(name = "getUserData", custom = { m, c -> m.returnType == "V" && c.type.contains("PurchasingService") }), "Amazon.PurchasingService.getUserData", 1) {
        it.addInstructions(0, "return-void")
    }

    for (name in listOf("isEnvReady", "obtainProductInfo", "createPurchaseIntent", "consumeOwnedPurchase", "obtainOwnedPurchases", "obtainOwnedPurchaseRecord", "isSandboxActivated")) {
        patchAll(Fingerprint(name = name, custom = { _, c -> c.type.lowercase().contains("huawei") }), "Huawei.$name", 1) {
            when (it.returnType) {
                "V" -> it.addInstructions(0, "return-void")
                "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                else -> it.addInstructions(0, safeReturn(it))
            }
        }
    }

    for (name in listOf("getProductsDetails", "startPayment", "getOwnedList", "consumePurchasedItems", "getProductDetails", "checkPurchasedItem")) {
        patchAll(Fingerprint(name = name, custom = { _, c -> c.type.lowercase().contains("samsung") }), "Samsung.$name", 1) {
            when (it.returnType) {
                "V" -> it.addInstructions(0, "return-void")
                "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                else -> it.addInstructions(0, safeReturn(it))
            }
        }
    }
}
