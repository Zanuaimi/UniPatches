package unipatch.overlaycore;

/** Billing Library v3 purchase construction boundary. */
public final class BillingV3PurchaseFactory {
    private BillingV3PurchaseFactory() { }

    public static Object create(String productId, String packageName) throws ReflectiveOperationException {
        return BillingPurchaseFactory.create(productId, packageName);
    }
}
