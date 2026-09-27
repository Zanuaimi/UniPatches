package unipatch.overlaycore;

/** Billing Library v9 purchase construction boundary. */
public final class BillingV9PurchaseFactory {
    private BillingV9PurchaseFactory() { }

    public static Object create(String productId, String packageName) throws ReflectiveOperationException {
        Class<?> purchaseClass = Class.forName("com.android.billingclient.api.Purchase");
        String token = BillingPurchaseFactory.identity(productId, "unipatches-v9-token");
        String order = BillingPurchaseFactory.identity(productId, "unipatches-v9-order");
        return BillingPurchaseFactory.construct(purchaseClass, productId, packageName, order, token, true);
    }
}
