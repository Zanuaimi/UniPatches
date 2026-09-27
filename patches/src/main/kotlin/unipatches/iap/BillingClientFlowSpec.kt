package unipatches.iap

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod

/** Version-specific argument extraction kept outside the shared lifecycle patch. */
internal enum class BillingClientFlowSpec {
    V3 {
        override fun productArguments(method: MutableMethod, register: (MutableMethod, Int) -> String): List<String> =
            activityAndFlowParams(method, register)
    },
    V9 {
        override fun productArguments(method: MutableMethod, register: (MutableMethod, Int) -> String): List<String> =
            activityAndFlowParams(method, register)
    };

    abstract fun productArguments(method: MutableMethod, register: (MutableMethod, Int) -> String): List<String>
}

private fun activityAndFlowParams(
    method: MutableMethod,
    register: (MutableMethod, Int) -> String,
): List<String> {
    val activity = method.parameterTypes.indexOfFirst { it == "Landroid/app/Activity;" }
    val flowParams = method.parameterTypes.indexOfFirst { it == "Lcom/android/billingclient/api/BillingFlowParams;" }
    if (activity < 0 || flowParams < 0) return emptyList()
    return listOf(register(method, activity), register(method, flowParams))
}
