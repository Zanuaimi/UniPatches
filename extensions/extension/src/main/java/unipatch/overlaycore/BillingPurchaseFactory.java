package unipatch.overlaycore;

import java.lang.reflect.Constructor;

/** Shared low-level BillingClient purchase construction helpers. */
public final class BillingPurchaseFactory {
    private BillingPurchaseFactory() { }

    static Object construct(Class<?> purchaseClass, String productId, String packageName,
                            String orderId, String token, boolean acknowledged)
            throws ReflectiveOperationException {
        Constructor<?> constructor = purchaseClass.getConstructor(String.class, String.class);
        // Every fragment closes its own value before the next key, so fields can be
        // reordered without stranding a quote. "products" is what Purchase.getProducts()
        // parses on BillingClient 5+; older releases read "productId", so both ship.
        String json = "{\"orderId\":\"" + PurchaseJson.escape(orderId) + "\"" +
                ",\"packageName\":\"" + PurchaseJson.escape(packageName) + "\"" +
                ",\"productId\":\"" + PurchaseJson.escape(productId) + "\"" +
                ",\"products\":[\"" + PurchaseJson.escape(productId) + "\"]" +
                ",\"purchaseTime\":" + System.currentTimeMillis() +
                ",\"purchaseState\":1" +
                ",\"purchaseToken\":\"" + PurchaseJson.escape(token) + "\"" +
                ",\"quantity\":1" +
                ",\"acknowledged\":" + acknowledged +
                "}";
        return constructor.newInstance(json, token);
    }

    static String identity(String productId, String prefix) {
        String value = productId == null ? "" : productId.trim();
        return prefix + "-" + Integer.toHexString(value.hashCode());
    }
}
