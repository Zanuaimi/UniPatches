package unipatch.overlaycore;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;

/** Constructs purchases for OpenIAB's legacy Google utility classes. */
public final class OpenIabPurchaseFactory {
    private OpenIabPurchaseFactory() { }

    public static Object create(String productId, String packageName, String developerPayload,
                                boolean inapp) throws ReflectiveOperationException {
        Class<?> purchaseClass = Class.forName("org.onepf.oms.appstore.googleUtils.Purchase");
        String identity = identity(productId);
        long purchaseTime = System.currentTimeMillis();
        String json = "{\"productId\":\"" + PurchaseJson.escape(productId) + "\",\"orderId\":\"unipatches-order-" + identity + "\",\"packageName\":\"" +
                PurchaseJson.escape(packageName) + "\",\"purchaseToken\":\"unipatches-token-" + identity + "\",\"purchaseState\":0,\"purchaseTime\":" + purchaseTime + ",\"developerPayload\":\"" +
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
            set(purchase, "setToken", "unipatches-token-" + identity);
            return purchase;
        }
    }

    private static String identity(String productId) {
        return Integer.toHexString((productId == null ? "" : productId).hashCode()).toLowerCase(Locale.ROOT);
    }

    private static void set(Object target, String name, Object value) {
        try {
            Method method = target.getClass().getMethod(name, value instanceof Integer ? int.class : String.class);
            method.invoke(target, value);
        } catch (ReflectiveOperationException ignored) { }
    }

}
