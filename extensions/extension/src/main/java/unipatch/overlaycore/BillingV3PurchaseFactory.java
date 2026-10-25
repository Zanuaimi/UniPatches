package unipatch.overlaycore;

/** Billing Library v3 purchase construction boundary. */
public final class BillingV3PurchaseFactory {
    private BillingV3PurchaseFactory() { }

    public static Object create(String productId, String packageName) throws ReflectiveOperationException {
        Class<?> purchaseClass = Class.forName("com.android.billingclient.api.Purchase");
        return BillingPurchaseFactory.construct(
                purchaseClass,
                productId,
                packageName,
                BillingPurchaseFactory.orderId(productId),
                BillingPurchaseFactory.token(productId),
                false);
    }
}
