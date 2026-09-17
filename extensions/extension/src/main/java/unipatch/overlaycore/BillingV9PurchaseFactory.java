package unipatch.overlaycore;

/** Billing Library v9 purchase construction boundary. */
public final class BillingV9PurchaseFactory {
    private BillingV9PurchaseFactory() { }

    public static Object create(String productId, String packageName) throws ReflectiveOperationException {
        return BillingPurchaseFactory.create(productId, packageName);
    }
}
