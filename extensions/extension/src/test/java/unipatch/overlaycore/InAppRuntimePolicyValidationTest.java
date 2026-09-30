package unipatch.overlaycore;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Guards the injected validateModernPurchase gates. */
public class InAppRuntimePolicyValidationTest {
    public static final class FakeDetails {
        public String getProductId() { return "coins_100"; }
        public String getProductType() { return "inapp"; }
    }

    public static final class FakeParams {
        public FakeDetails getProductDetails() { return new FakeDetails(); }
    }

    public static final class FakeFlowParams {
        public List<FakeParams> getProductDetailsParamsList() {
            return Collections.singletonList(new FakeParams());
        }
    }

    @Test
    public void nonActivityPurchaseTargetStillValidates() {
        // launchBillingFlow may hand us anything at all once the backend is
        // obfuscated; dispatch() already falls back to the registered activity,
        // so this argument must not veto an otherwise valid purchase. It used
        // to force response code 5 on every modern sale.
        int responseCode = InAppRuntimePolicy.validateModernPurchase(
                new Object(), new Object(), new Object(), new FakeFlowParams());
        assertEquals(0, responseCode);
    }

    @Test
    public void absentPurchaseTargetStillValidates() {
        int responseCode = InAppRuntimePolicy.validateModernPurchase(
                new Object(), new Object(), null, new FakeFlowParams());
        assertEquals(0, responseCode);
    }

    @Test
    public void missingFlowParametersAreStillRejected() {
        int responseCode = InAppRuntimePolicy.validateModernPurchase(
                new Object(), new Object(), new Object(), null);
        assertEquals(5, responseCode);
    }

    @Test
    public void connectionStaysDisconnectedUntilStartConnectionLatches() {
        // Always-on readiness short-circuits connection-gated games before they
        // ever call startConnection, so their setup listener never fires. The
        // latch is process-lifetime: assert the default first, then the effect.
        assertFalse(InAppRuntimePolicy.isConnectionReady());
        assertEquals(0, InAppRuntimePolicy.connectionState());

        InAppRuntimePolicy.markConnectionReady();

        assertTrue(InAppRuntimePolicy.isConnectionReady());
        assertEquals(2, InAppRuntimePolicy.connectionState());
    }

    @Test
    public void stockResultsReadAsOkWhileStampedResultsKeepTheirCode() {
        Object stockResult = new Object();
        // Unstamped: built by the stock store, which is unreachable while
        // billing is emulated, so it must not flip a game's billing gate.
        assertEquals(0, InAppRuntimePolicy.billingResponseCode(stockResult));

        // Stamped: built by this module, where cancel (1) and validation
        // failures (4/5) must still read as failures.
        InAppRuntimePolicy.stampResponseCode(stockResult, 1);
        assertEquals(1, InAppRuntimePolicy.billingResponseCode(stockResult));

        assertEquals(0, InAppRuntimePolicy.billingResponseCode(null));
    }

    // ── Empty-catalog synthesis wrap (queryProductDetailsAsync /
    // querySkuDetailsAsync listener proxy) ─────────────────────────────

    public interface FakeProductListener {
        void onProductDetailsResponse(Object billingResult, Object details);
    }

    public interface FakeListProductListener {
        void onProductDetailsResponse(Object billingResult, List<Object> details);
    }

    public interface FakeSkuListener {
        void onSkuDetailsResponse(Object billingResult, Object skus);
    }

    /** Request element shaped like the readable production Product accessors. */
    public static final class FakeProduct {
        public String getProductType() { return "subs"; }
        public String getProductId() { return "coins_100"; }
    }

    public static final class FakeQueryParams {
        public List<FakeProduct> getProductDetailsList() {
            return Collections.singletonList(new FakeProduct());
        }
    }

    @Test
    public void catalogWrapReturnsProxyThatForwardsTheStockCallbackWhenSynthesisIsUnavailable() {
        final Object[] received = new Object[2];
        FakeProductListener listener = new FakeProductListener() {
            @Override
            public void onProductDetailsResponse(Object billingResult, Object details) {
                received[0] = billingResult;
                received[1] = details;
            }
        };
        Object wrapped = InAppRuntimePolicy.wrapProductDetailsListener(new FakeQueryParams(), listener);
        assertTrue(wrapped instanceof FakeProductListener);
        assertNotSame(listener, wrapped);

        Object stockResult = new Object();
        Object stockDetails = new Object();
        ((FakeProductListener) wrapped).onProductDetailsResponse(stockResult, stockDetails);

        // The extension compiles against android.jar only: no billing classes
        // exist on the test JVM, so synthesis must degrade to a faithful
        // forward instead of swallowing the callback.
        assertSame(stockResult, received[0]);
        assertSame(stockDetails, received[1]);
    }

    @Test
    public void listCallbackKeepsPopulatedResultsAndForwardsEmptyOnes() {
        final Object[] received = new Object[2];
        FakeListProductListener listener = new FakeListProductListener() {
            @Override
            public void onProductDetailsResponse(Object billingResult, List<Object> details) {
                received[0] = billingResult;
                received[1] = details;
            }
        };
        Object wrapped = InAppRuntimePolicy.wrapProductDetailsListener(new FakeQueryParams(), listener);
        assertTrue(wrapped instanceof FakeListProductListener);

        List<Object> stockList = Collections.singletonList(new Object());
        ((FakeListProductListener) wrapped).onProductDetailsResponse(new Object(), stockList);
        assertSame(stockList, received[1]);

        ((FakeListProductListener) wrapped).onProductDetailsResponse(new Object(), Collections.emptyList());
        assertSame(Collections.emptyList(), received[1]);
    }

    /** Production shape: R8 strips all accessors; id/type live in private String fields. */
    public static final class FakeStrippedProduct {
        private final String a = "coins_100";
        private final String b = "subs";
    }

    public static final class FakeZzbtParams {
        private final List<FakeStrippedProduct> held =
                Collections.singletonList(new FakeStrippedProduct());

        /** Obfuscated internal-list accessor, like QueryProductDetailsParams.zza(). */
        public List<FakeStrippedProduct> zza() {
            return held;
        }
    }

    public static final class FakeFieldOnlyParams {
        private final List<FakeStrippedProduct> a =
                Collections.singletonList(new FakeStrippedProduct());
    }

    @Test
    public void strippedProductFieldsStillYieldRequestIds() {
        final Object[] received = new Object[2];
        FakeProductListener listener = new FakeProductListener() {
            @Override
            public void onProductDetailsResponse(Object billingResult, Object details) {
                received[0] = billingResult;
                received[1] = details;
            }
        };
        Object wrapped = InAppRuntimePolicy.wrapProductDetailsListener(new FakeZzbtParams(), listener);
        assertTrue(wrapped instanceof FakeProductListener);
        assertNotSame(listener, wrapped);

        Object stockResult = new Object();
        Object stockDetails = new Object();
        ((FakeProductListener) wrapped).onProductDetailsResponse(stockResult, stockDetails);
        assertSame(stockResult, received[0]);
        assertSame(stockDetails, received[1]);
    }

    @Test
    public void fieldOnlyParamsHolderStillExtractsRequestIds() {
        FakeProductListener listener = new FakeProductListener() {
            @Override
            public void onProductDetailsResponse(Object billingResult, Object details) { }
        };
        Object wrapped = InAppRuntimePolicy.wrapProductDetailsListener(new FakeFieldOnlyParams(), listener);
        assertTrue(wrapped instanceof FakeProductListener);
        assertNotSame(listener, wrapped);
    }

    @Test
    public void skuWrapForwardsWhenSynthesisIsUnavailable() {
        final Object[] received = new Object[2];
        FakeSkuListener listener = new FakeSkuListener() {
            @Override
            public void onSkuDetailsResponse(Object billingResult, Object skus) {
                received[0] = billingResult;
                received[1] = skus;
            }
        };
        Object wrapped = InAppRuntimePolicy.wrapSkuDetailsListener(new FakeQueryParams(), listener);
        assertTrue(wrapped instanceof FakeSkuListener);
        ((FakeSkuListener) wrapped).onSkuDetailsResponse(new Object(), null);
        assertNotNull(received[0]);
        assertNull(received[1]);
    }

    @Test
    public void wrapIsNullSafeAndKeepsListenersWithoutRequestIds() {
        assertNull(InAppRuntimePolicy.wrapProductDetailsListener(null, null));
        assertNull(InAppRuntimePolicy.wrapSkuDetailsListener(null, null));

        FakeProductListener listener = new FakeProductListener() {
            @Override
            public void onProductDetailsResponse(Object billingResult, Object details) { }
        };
        assertSame(listener, InAppRuntimePolicy.wrapProductDetailsListener(null, listener));
        assertSame(listener, InAppRuntimePolicy.wrapSkuDetailsListener(null, listener));
    }

    @Test
    public void productDetailsJsonCarriesTheRequiredSubsOfferShape() {
        String json = InAppRuntimePolicy.productDetailsJson("coins_100", "subs");
        assertTrue(json.contains("\"productId\":\"coins_100\""));
        assertTrue(json.contains("\"type\":\"subs\""));
        assertTrue(json.contains("\"subscriptionOfferDetails\""));
        assertTrue(json.contains("\"offerIdToken\":\"unipatch.coins_100\""));
        assertTrue(json.contains("\"pricingPhases\":["));
        assertTrue(json.contains("\"billingPeriod\":\"P1M\""));
        assertTrue(json.contains("\"formattedPrice\":\"0.00\""));
        assertTrue(json.contains("\"priceAmountMicros\":0"));
        assertFalse(json.contains("oneTimePurchaseOfferDetails"));
    }

    @Test
    public void productDetailsJsonCarriesBothOneTimePurchaseFormsForInapp() {
        String json = InAppRuntimePolicy.productDetailsJson("coins_100", "inapp");
        assertTrue(json.contains("\"type\":\"inapp\""));
        assertTrue(json.contains("\"oneTimePurchaseOfferDetails\""));
        assertTrue(json.contains("\"oneTimePurchaseOfferDetailsList\""));
        assertFalse(json.contains("subscriptionOfferDetails"));
    }

    @Test
    public void playPassSubsNormalizeOntoSubs() {
        String json = InAppRuntimePolicy.productDetailsJson("coins_100", "play_pass_subs");
        assertTrue(json.contains("\"type\":\"subs\""));
        assertFalse(json.contains("play_pass_subs"));
    }

    @Test
    public void skuDetailsJsonCarriesRequiredIdentityFields() {
        String json = InAppRuntimePolicy.skuDetailsJson("coins_100", "inapp");
        assertTrue(json.contains("\"productId\":\"coins_100\""));
        assertTrue(json.contains("\"type\":\"inapp\""));
        assertTrue(json.contains("\"price\":\"0.00\""));
        assertTrue(json.contains("\"price_currency_code\":\"USD\""));
        assertFalse(json.contains("subscriptionPeriod"));
    }

    @Test
    public void jsonBuildersEscapeHostileIds() {
        String product = InAppRuntimePolicy.productDetailsJson("a\"b\\c", "inapp");
        assertTrue(product.contains("a\\\"b\\\\c"));
        String sku = InAppRuntimePolicy.skuDetailsJson("a\"b\\c", "subs");
        assertTrue(sku.contains("a\\\"b\\\\c"));
    }
}
