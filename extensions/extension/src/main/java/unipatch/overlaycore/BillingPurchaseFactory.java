package unipatch.overlaycore;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Constructs the version-neutral JSON payload used by BillingClient purchases. */
public final class BillingPurchaseFactory {
    private BillingPurchaseFactory() { }

    public static Object create(String productId, String packageName) throws ReflectiveOperationException {
        Class<?> purchaseClass = Class.forName("com.android.billingclient.api.Purchase");
        Constructor<?> constructor = purchaseClass.getConstructor(String.class, String.class);
        String json = "{\"orderId\":\"morphe_fake\",\"packageName\":\"" + PurchaseJson.escape(packageName) +
                "\",\"productId\":\"" + PurchaseJson.escape(productId) +
                "\",\"purchaseTime\":0,\"purchaseState\":1,\"purchaseToken\":\"morphe_fake\",\"quantity\":1,\"acknowledged\":true}";
        return constructor.newInstance(json, "morphe_fake");
    }
}
