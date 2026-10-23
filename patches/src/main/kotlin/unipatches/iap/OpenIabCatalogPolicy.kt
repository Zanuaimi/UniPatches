package unipatches.iap

/** Keeps OpenIAB's real catalog getters out of ownership emulation. */
internal object OpenIabCatalogPolicy {
    private const val skuDetails = "lorg/onepf/oms/appstore/googleutils/skudetails;"
    private val getters = setOf("getprice", "getoriginalprice", "getformattedprice")

    fun isCatalogGetter(owner: String, methodName: String, parameterCount: Int): Boolean =
        owner.lowercase() == skuDetails && parameterCount == 0 && methodName.lowercase() in getters
}
