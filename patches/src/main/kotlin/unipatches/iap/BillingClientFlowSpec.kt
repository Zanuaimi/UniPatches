package unipatches.iap

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod

/**
 * Version-specific argument extraction kept outside the shared lifecycle patch.
 *
 * The extracted list feeds `buildBillingPurchaseBlock`, which injects
 * `validateModernPurchase(billingClient, listener, purchaseActivity, flowParams)`
 * and `dispatch(listener, purchaseActivity, flowParams)`. It is therefore
 * POSITIONAL, not a bag of matches: index 0 must resolve to the Activity and
 * index 1 to the flow parameters. Returning whatever parameter types happen to
 * match the version filter produced a one-element list for the standard
 * `launchBillingFlow(Activity, BillingFlowParams)` signature (the filter only
 * matched `BillingFlowParams`), so `purchaseActivity` received the flow
 * parameters, `flowParams` received null, and every modern purchase was
 * rejected with response code 5 before a grant could run.
 */
internal enum class BillingClientFlowSpec {
    V3 {
        override fun isFlowParams(type: String): Boolean =
            type.contains("SkuDetails") || type.contains("BillingFlowParams")
    },
    V9 {
        override fun isFlowParams(type: String): Boolean =
            type.contains("BillingFlowParams") || type.contains("ProductDetails")
    };

    abstract fun isFlowParams(type: String): Boolean

    /**
     * `[activityIndex, flowParamsIndex]` into [types], or -1 when the slot has
     * no match. The second slot falls back to the first non-Activity object
     * argument so legacy/IL2CPP signatures that carry the product id as a plain
     * `String` keep working.
     */
    fun argumentIndices(types: List<CharSequence>): List<Int> {
        // dexlib2 exposes parameterTypes as List<CharSequence>, and indexOfFirst
        // takes the ELEMENT, not the index -- walk indices explicitly.
        val activity = types.indices.indexOfFirst { isActivityType(types[it].toString()) }
        val flow = types.indices.indexOfFirst { it != activity && isFlowParams(types[it].toString()) }
        val fallback = types.indices.indexOfFirst { index ->
            index != activity && index != flow &&
                (types[index].startsWith("L") || types[index].startsWith("["))
        }
        return listOf(activity, if (flow >= 0) flow else fallback)
    }

    fun productArguments(method: MutableMethod, register: (MutableMethod, Int) -> String): List<String?> =
        argumentIndices(method.parameterTypes).map { index ->
            if (index >= 0) register(method, index) else null
        }

    private fun isActivityType(type: String): Boolean =
        type == "Landroid/app/Activity;" || type.endsWith("Activity;")
}
