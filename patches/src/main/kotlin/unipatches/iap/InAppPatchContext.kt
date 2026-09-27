package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod

/** Common services exposed to backend-specific managed-code patch adapters. */
internal typealias InAppPatchAll = (Fingerprint, String, Int, (MutableMethod) -> Unit) -> Unit

internal data class InAppManagedAdapterContext(
    val patchAll: InAppPatchAll,
    val safeReturn: (MutableMethod) -> String,
    val okBillingResult: String,
    val nonOverlayTimeout: Int = 10,
    val overlayTimeout: Int = 30,
    val minRegs: (MutableMethod) -> Int = { 0 },
    val expandSwap: (MutableMethod, String) -> Boolean = { _, _ -> false },
    val parameterRegister: (MutableMethod, Int) -> String = { _, _ -> "p0" },
    val listenerIget: (String) -> String? = { null },
    val buyGrantBlock: (String, List<String>) -> String = { _, _ -> "" },
    val isBillingNamespace: (String) -> Boolean = { false },
    val billingCapabilities: BillingClientCapabilities = BillingClientCapabilities(),
    val billingAdapterKey: String = "unknown",
)
