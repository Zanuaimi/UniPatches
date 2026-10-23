package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Pins the exact Purchase JSON shape handed to BillingClient's Purchase(String, String). */
public class BillingPurchaseFactoryTest {
    public static final class FakePurchase {
        final String json;
        final String token;

        public FakePurchase(String json, String token) {
            this.json = json;
            this.token = token;
        }
    }

    @Test
    public void synthesizedJsonCarriesBothProductKeysAndPurchaseStateOne() throws ReflectiveOperationException {
        FakePurchase purchase = (FakePurchase) BillingPurchaseFactory.construct(
                FakePurchase.class, "coins_100", "com.example.game", "order-1", "token-1", true);

        assertTrue(purchase.json,
                purchase.json.startsWith(
                        "{\"orderId\":\"order-1\",\"packageName\":\"com.example.game\",\"productId\":\"coins_100\"," +
                                "\"products\":[\"coins_100\"],\"purchaseTime\":"));
        assertTrue(purchase.json,
                purchase.json.endsWith(
                        ",\"purchaseState\":1,\"purchaseToken\":\"token-1\",\"quantity\":1,\"acknowledged\":true}"));
        assertTrue(purchase.token, "token-1".equals(purchase.token));
    }

    @Test
    public void quotedProductIdsStayEscapedInsideTheProductsArray() throws ReflectiveOperationException {
        FakePurchase purchase = (FakePurchase) BillingPurchaseFactory.construct(
                FakePurchase.class, "coin\"s", "pkg", "order", "token", false);

        assertTrue(purchase.json, purchase.json.contains("\"productId\":\"coin\\\"s\""));
        assertTrue(purchase.json, purchase.json.contains("\"products\":[\"coin\\\"s\"]"));
        assertTrue(purchase.json, purchase.json.endsWith("\"acknowledged\":false}"));
    }
}
