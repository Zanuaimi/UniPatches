package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

/** Unity Purchasing callback adapter, separate from BillingClient/OpenIAB. */
internal fun applyUnityIapPatches(context: InAppManagedAdapterContext) {
    val patchAll = context.patchAll
    val safeReturn = context.safeReturn
    patchAll(Fingerprint(name = "ProcessPurchase", custom = { method, clazz ->
        method.returnType.contains("PurchaseProcessingResult") && clazz.type.lowercase().contains("purchase")
    }), "Unity.ProcessPurchase", 1) {
        it.addInstructions(0, "sget-object v0, Lcom/unity/purchasing/PurchaseProcessingResult;->Complete:Lcom/unity/purchasing/PurchaseProcessingResult;\nreturn-object v0")
    }
    patchAll(Fingerprint(name = "OnPurchaseFailed", custom = { _, clazz -> clazz.type.lowercase().contains("purchase") || clazz.type.lowercase().contains("unity") }), "Unity.OnPurchaseFailed", 1) {
        it.addInstructions(0, safeReturn(it))
    }
    patchAll(Fingerprint(name = "OnSetupFailed", custom = { _, clazz -> clazz.type.lowercase().contains("purchase") || clazz.type.lowercase().contains("unity") }), "Unity.OnSetupFailed", 1) {
        it.addInstructions(0, safeReturn(it))
    }
    patchAll(Fingerprint(name = "OnPurchaseComplete", custom = { _, clazz -> clazz.type.lowercase().contains("purchase") || clazz.type.lowercase().contains("unity") }), "Unity.OnPurchaseComplete", 1) {
        it.addInstructions(0, safeReturn(it))
    }
    patchAll(Fingerprint(name = "Validate", custom = { method, clazz -> method.returnType.contains("CrossPlatformValidator") || clazz.type.contains("CrossPlatformValidator") }), "Unity.CrossPlatformValidator.Validate", 1) { }
    for (name in listOf("hasReceipt", "getHasReceipt")) {
        patchAll(Fingerprint(name = name, returnType = "Z", custom = { _, clazz ->
            val type = clazz.type.lowercase()
            type.contains("receipt") || type.contains("purchase") || type.contains("billing") || type.contains("validator")
        }), "Unity.$name", 1) {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
    }
}
