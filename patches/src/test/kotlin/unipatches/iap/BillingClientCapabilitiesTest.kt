package unipatches.iap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingClientCapabilitiesTest {
    @Test
    fun selectsBillingV3ForSkuDetailsFlow() {
        val capabilities = BillingClientCapabilities(
            billingClientPresent = true,
            skuDetails = true,
            legacyLaunchFlow = true,
        )
        assertEquals("v3", capabilities.adapterKey())
        assertEquals(BillingV3Adapter, BillingClientDispatcher.select(capabilities))
    }

    @Test
    fun selectsBillingV9ForProductDetailsWithOffers() {
        val capabilities = BillingClientCapabilities(
            billingClientPresent = true,
            productDetails = true,
            offerTokens = true,
        )
        assertEquals("v9", capabilities.adapterKey())
        assertEquals(BillingV9Adapter, BillingClientDispatcher.select(capabilities))
    }

    @Test
    fun doesNotGuessUnsupportedBillingVersion() {
        val capabilities = BillingClientCapabilities(billingClientPresent = true, productDetails = true)
        assertEquals("unknown", capabilities.adapterKey())
        assertNull(BillingClientDispatcher.select(capabilities))
    }
}
