package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the exact Purchase JSON shape handed to BillingClient's Purchase(String, String),
 * plus the one-identity-per-product rule that keeps callback and inventory purchases equal.
 */
public class BillingPurchaseFactoryTest {
    private static final long PURCHASE_EPOCH_MILLIS = 1672531200000L;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    public static final class FakePurchase {
        final String json;
        final String signature;

        public FakePurchase(String json, String signature) {
            this.json = json;
            this.signature = signature;
        }
    }

    private static String valueAfter(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    @Test
    public void synthesizedJsonCarriesBothProductKeysAndPurchaseStateZero() throws ReflectiveOperationException {
        FakePurchase purchase = (FakePurchase) BillingPurchaseFactory.construct(
                FakePurchase.class, "coins_100", "com.example.game", "order-1", "token-1", true);

        assertTrue(purchase.json,
                purchase.json.startsWith(
                        "{\"orderId\":\"order-1\",\"packageName\":\"com.example.game\",\"productId\":\"coins_100\"," +
                                "\"products\":[\"coins_100\"],\"purchaseTime\":"));
        assertTrue(purchase.json,
                purchase.json.contains(
                        ",\"purchaseState\":0,\"purchaseToken\":\"token-1\",\"quantity\":1,\"acknowledged\":true," +
                                "\"autoRenewing\":true,\"developerPayload\":\"\",\"obfuscatedAccountId\":\""));
        int accountIndex = purchase.json.indexOf("\"obfuscatedAccountId\":\"");
        int profileIndex = purchase.json.indexOf("\"obfuscatedProfileId\":\"");
        assertTrue(purchase.json, accountIndex > 0 && profileIndex > accountIndex);
        assertTrue(purchase.json, purchase.json.endsWith("\"}"));
        assertTrue(purchase.signature, purchase.signature.matches("[A-Za-z0-9_-]{64,}"));
    }

    @Test
    public void accountIdentifiersAreStableHexDigests() throws ReflectiveOperationException {
        FakePurchase purchase = (FakePurchase) BillingPurchaseFactory.construct(
                FakePurchase.class, "coins_100", "com.example.game", "order-1", "token-1", true);
        FakePurchase again = (FakePurchase) BillingPurchaseFactory.construct(
                FakePurchase.class, "coins_100", "com.example.game", "order-1", "token-1", true);

        assertEquals(purchase.json, again.json);
        String account = valueAfter(purchase.json, "obfuscatedAccountId");
        String profile = valueAfter(purchase.json, "obfuscatedProfileId");
        assertTrue(account, account.matches("[0-9a-f]{32}"));
        assertTrue(profile, profile.matches("[0-9a-f]{32}"));
        assertTrue("account and profile must differ", !account.equals(profile));
    }

    @Test
    public void quotedProductIdsStayEscapedInsideTheProductsArray() throws ReflectiveOperationException {
        FakePurchase purchase = (FakePurchase) BillingPurchaseFactory.construct(
                FakePurchase.class, "coin\"s", "pkg", "order", "token", false);

        assertTrue(purchase.json, purchase.json.contains("\"productId\":\"coin\\\"s\""));
        assertTrue(purchase.json, purchase.json.contains("\"products\":[\"coin\\\"s\"]"));
        assertTrue(purchase.json, purchase.json.contains("\"acknowledged\":false,"));
    }

    @Test
    public void purchaseTimeSitsInAPlausibleWindow() {
        long time = BillingPurchaseFactory.purchaseTime("coins_100");
        assertTrue(Long.toString(time), time >= PURCHASE_EPOCH_MILLIS);
        assertTrue(Long.toString(time), time < PURCHASE_EPOCH_MILLIS + 366L * DAY_MILLIS);
        assertEquals(time, BillingPurchaseFactory.purchaseTime("coins_100"));
    }

    @Test
    public void orderIdMatchesThePlayGpaShape() {
        String order = BillingPurchaseFactory.orderId("coins_100");
        assertTrue(order, order.matches("GPA\\.\\d{4}-\\d{4}-\\d{4}-\\d{5}"));
        assertEquals(order, BillingPurchaseFactory.orderId("coins_100"));
        assertTrue(!order.equals(BillingPurchaseFactory.orderId("coins_200")));
    }

    @Test
    public void tokenIsALongBase64UrlBlob() {
        String token = BillingPurchaseFactory.token("coins_100");
        assertTrue(token, token.matches("[A-Za-z0-9_-]{40}\\.[A-Za-z0-9_-]{40}"));
        assertEquals(token, BillingPurchaseFactory.token("coins_100"));
        assertTrue(!token.equals(BillingPurchaseFactory.token("coins_200")));
    }

    @Test
    public void signatureIsBase64UrlAndNotTheEmptyString() {
        String signature = BillingPurchaseFactory.signature("coins_100");
        assertTrue(signature, signature.matches("[A-Za-z0-9_-]{64,}"));
        assertEquals(signature, BillingPurchaseFactory.signature("coins_100"));
    }

    /** The whole point of the canonical identity: the two delivery paths must agree. */
    @Test
    public void inventoryAndCallbackPurchasesShareOneIdentity() throws ReflectiveOperationException {
        String productId = "coins_100";
        String token = BillingPurchaseFactory.token(productId);
        String inventory = BillingPurchaseFactory.inventoryJson(productId);
        FakePurchase callback = (FakePurchase) BillingPurchaseFactory.construct(
                FakePurchase.class, productId, "com.example.game",
                BillingPurchaseFactory.orderId(productId), token, true);

        assertEquals(token, valueAfter(inventory, "purchaseToken"));
        assertEquals(token, valueAfter(callback.json, "purchaseToken"));
        assertEquals(BillingPurchaseFactory.orderId(productId), valueAfter(inventory, "orderId"));
        assertEquals(BillingPurchaseFactory.orderId(productId), valueAfter(callback.json, "orderId"));
        String purchaseTime = "\"purchaseTime\":" + BillingPurchaseFactory.purchaseTime(productId) + ",";
        assertTrue(inventory, inventory.contains(purchaseTime));
        assertTrue(callback.json, callback.json.contains(purchaseTime));
    }

    @Test
    public void inventoryJsonStaysParseableWithoutAnActivityThread() {
        String inventory = BillingPurchaseFactory.inventoryJson("coins_100");
        assertNotNull(inventory);
        assertTrue(inventory, inventory.startsWith("{\"orderId\":\"GPA."));
        assertTrue(inventory, inventory.contains("\"productId\":\"coins_100\""));
        assertTrue(inventory, inventory.contains("\"acknowledged\":true"));
        assertEquals("", valueAfter(inventory, "packageName"));
    }
}
