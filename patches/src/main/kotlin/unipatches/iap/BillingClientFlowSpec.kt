package unipatches.iap

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod

/** Version-specific argument extraction kept outside the shared lifecycle patch. */
internal enum class BillingClientFlowSpec {
    V3 {
        override fun productArguments(method: MutableMethod, register: (MutableMethod, Int) -> String): List<String> =
            method.parameterTypes.mapIndexedNotNull { index, type ->
                if (type.contains("SkuDetails") || type.contains("BillingFlowParams")) register(method, index) else null
            }.take(2)
    },
    V9 {
        override fun productArguments(method: MutableMethod, register: (MutableMethod, Int) -> String): List<String> =
            method.parameterTypes.mapIndexedNotNull { index, type ->
                if (type.contains("BillingFlowParams") || type.contains("ProductDetails")) register(method, index) else null
            }.take(2)
    };

    abstract fun productArguments(method: MutableMethod, register: (MutableMethod, Int) -> String): List<String>
}
