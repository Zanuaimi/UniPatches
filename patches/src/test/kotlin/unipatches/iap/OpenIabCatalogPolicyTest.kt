package unipatches.iap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenIabCatalogPolicyTest {
    @Test
    fun preservesOpenIabSkuDetailsPriceGetters() {
        val owner = "Lorg/onepf/oms/appstore/googleUtils/SkuDetails;"
        assertTrue(OpenIabCatalogPolicy.isCatalogGetter(owner, "getPrice", 0))
        assertTrue(OpenIabCatalogPolicy.isCatalogGetter(owner, "getOriginalPrice", 0))
        assertTrue(OpenIabCatalogPolicy.isCatalogGetter(owner, "getFormattedPrice", 0))
    }

    @Test
    fun doesNotTreatPurchaseOrParameterizedMethodsAsCatalogGetters() {
        val owner = "Lorg/onepf/oms/appstore/googleUtils/SkuDetails;"
        assertFalse(OpenIabCatalogPolicy.isCatalogGetter(owner, "getPrice", 1))
        assertFalse(OpenIabCatalogPolicy.isCatalogGetter(owner, "getSku", 0))
    }
}
