package unipatch.overlaycore;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Constructs purchases for OpenIAB's legacy Google utility classes. */
public final class OpenIabPurchaseFactory {
    private OpenIabPurchaseFactory() { }

    public static Object create(String productId, String packageName, String developerPayload,
                                boolean inapp) throws ReflectiveOperationException {
        Class<?> purchaseClass = Class.forName("org.onepf.oms.appstore.googleUtils.Purchase");
        String json = "{\"productId\":\"" + PurchaseJson.escape(productId) + "\",\"orderId\":\"morphe_fake\",\"packageName\":\"" +
                PurchaseJson.escape(packageName) + "\",\"purchaseToken\":\"morphe_fake\",\"purchaseState\":0,\"purchaseTime\":0,\"developerPayload\":\"" +
                PurchaseJson.escape(developerPayload) + "\"}";
        try {
            Constructor<?> constructor = purchaseClass.getConstructor(String.class, String.class, String.class, String.class);
            return constructor.newInstance(inapp ? "inapp" : "subs", json, "", "com.google.play");
        } catch (NoSuchMethodException missingParser) {
            Constructor<?> constructor = purchaseClass.getConstructor(String.class);
            Object purchase = constructor.newInstance("com.google.play");
            set(purchase, "setOriginalJson", json);
            set(purchase, "setSku", productId);
            set(purchase, "setPackageName", packageName);
            set(purchase, "setDeveloperPayload", developerPayload == null ? "" : developerPayload);
            set(purchase, "setPurchaseState", Integer.valueOf(0));
            set(purchase, "setToken", "morphe_fake");
            return purchase;
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Method method = target.getClass().getMethod(name, value instanceof Integer ? int.class : String.class);
            method.invoke(target, value);
        } catch (ReflectiveOperationException ignored) { }
    }

}
