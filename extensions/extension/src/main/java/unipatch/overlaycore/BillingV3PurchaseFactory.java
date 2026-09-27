package unipatch.overlaycore;

/** Billing Library v3 purchase construction boundary. */
public final class BillingV3PurchaseFactory {
    private BillingV3PurchaseFactory() { }

    public static Object create(String productId, String packageName) throws ReflectiveOperationException {
        Class<?> purchaseClass = Class.forName("com.android.billingclient.api.Purchase");
        String token = BillingPurchaseFactory.identity(productId, "unipatches-v3-token");
        String order = BillingPurchaseFactory.identity(productId, "unipatches-v3-order");
        return BillingPurchaseFactory.construct(purchaseClass, productId, packageName, order, token, false);
    }
}
