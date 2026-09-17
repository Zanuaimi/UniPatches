package unipatch.overlaycore;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import unipatch.overlaycore.modules.OverlaySessionState;
import unipatch.overlaycore.modules.advanced.OverlayRuntimeLogger;

/** Session-local policy and callback bridge for the optional InApp overlay module. */
public final class InAppRuntimePolicy {
    public interface ConfirmationCallback { void complete(boolean save); }

    private static final String MODULE = "inAppEmulation";
    private static final int MAX_SAVED_PURCHASES = 128;
    private static final Set<String> SAVED = new LinkedHashSet<>();
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static Pending pending;
    private static Redirect redirect;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean configured;
    private static boolean popupEnabled = true;
    private static String lastEvent = "No purchase request this session";

    private InAppRuntimePolicy() { }

    public static synchronized void configure(String encoded) {
        configured = false;
        pending = null;
        redirect = null;
        SAVED.clear();
        OverlaySessionState.clearModule(MODULE);
        if (encoded == null) return;
        String[] values = encoded.split("\\|", -1);
        if (values.length != 4 || !"1".equals(values[0]) || !MODULE.equals(values[1]) || !MODULE.equals(values[2])) return;
        if (!"0".equals(values[3]) && !"1".equals(values[3])) return;
        popupEnabled = "1".equals(values[3]);
        configured = true;
    }

    public static synchronized boolean isConfigured() { return configured; }
    public static synchronized boolean popupEnabled() { return popupEnabled; }
    public static synchronized void setPopupEnabled(boolean enabled) { popupEnabled = enabled; }
    public static synchronized String lastEvent() { return lastEvent; }

    public static synchronized void reset() {
        configured = false;
        popupEnabled = true;
        pending = null;
        redirect = null;
        SAVED.clear();
        activity.clear();
        lastEvent = "No purchase request this session";
    }

    public static synchronized void registerActivity(Activity value) {
        if (value != null) activity = new WeakReference<>(value);
    }

    public static synchronized void onActivityDetached(Activity value) {
        if (value != null && pending != null && pending.activity.get() == value) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Purchase Activity detached before confirmation: product=" + pending.product);
            cancelPending();
        }
    }

    public static synchronized String[] savedPurchases() { return SAVED.toArray(new String[0]); }

    public static synchronized void removeUnsaved(boolean[] values, String[] identifiers) {
        if (values == null || identifiers == null) return;
        for (int i = 0; i < identifiers.length; i++) {
            if (i >= values.length || !values[i]) SAVED.remove(identifiers[i]);
        }
    }

    public static boolean dispatch(Object listener, Object purchaseActivity, Object flowParams) {
        if (listener == null) return false;
        Activity target = purchaseActivity instanceof Activity ? (Activity) purchaseActivity : null;
        String product = productId(flowParams, null);
        synchronized (InAppRuntimePolicy.class) {
            if (!configured || !popupEnabled || SAVED.contains(product)) {
                lastEvent = "Emulated purchase delivered: " + product;
                return deliver(listener, product, 0, true);
            }
            if (pending != null) {
                lastEvent = "Purchase request rejected while another confirmation is pending: " + product;
                deliver(listener, product, 1, false);
                return false;
            }
            pending = new Pending(listener, product, false, target);
            lastEvent = "Waiting for confirmation: " + product;
            OverlayRuntimeLogger.log("INFO", "InApp", "Purchase request received: listener=" + listener.getClass().getName() +
                    ", activity=" + (target == null ? "null" : target.getClass().getName()) + ", product=" + product);
        }
        final Pending request;
        synchronized (InAppRuntimePolicy.class) { request = pending; }
        MAIN.postDelayed(() -> timeout(request), 30000L);
        if (target != null) OverlayRuntime.ensureActivity(target);
        if (!OverlayRuntime.showInAppPurchaseConfirmation(target, product)) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Purchase confirmation popup could not attach; cancelling purchase: product=" + product);
            cancelPending();
            return false;
        }
        return true;
    }

    /** Returns a BillingClient-compatible response code and completes rejected callbacks. */
    public static int validateModernPurchase(Object billingClient, Object listener,
                                             Object purchaseActivity, Object flowParams) {
        int responseCode;
        if (billingClient == null || listener == null || !(purchaseActivity instanceof Activity) || flowParams == null) {
            responseCode = 5;
        } else if (!listenerHolderMatches(billingClient, listener)) {
            responseCode = 5;
        } else {
            Integer state = connectionState(billingClient);
            responseCode = state != null && state != 2 ? 2 : validateFlowParams(flowParams);
        }
        if (responseCode != 0) {
            deliver(listener, productId(flowParams, null), responseCode, false);
        }
        return responseCode;
    }

    /** Builds BillingResult without linking the extension against a BillingClient version. */
    public static Object billingResult(int responseCode) {
        try {
            Class<?> resultClass = Class.forName("com.android.billingclient.api.BillingResult");
            Object builder = resultClass.getMethod("newBuilder").invoke(null);
            builder = builder.getClass().getMethod("setResponseCode", int.class).invoke(builder, responseCode);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Integer connectionState(Object billingClient) {
        // BillingClientImpl uses an obfuscated volatile int for its connection state. Restrict
        // reflection to conventional names and the known zzb slot; unknown versions are treated
        // as indeterminate so emulation remains compatible with future BillingClient releases.
        for (Class<?> type = billingClient.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != int.class) continue;
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (!(name.equals("zzb") || name.contains("connectionstate") || name.contains("billingstate"))) continue;
                try {
                    field.setAccessible(true);
                    return field.getInt(billingClient);
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
        return null;
    }

    private static boolean listenerHolderMatches(Object billingClient, Object listener) {
        for (Class<?> type = billingClient.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object holder = field.get(billingClient);
                    if (holder == listener) return true;
                    if (holder == null || field.getType().isPrimitive() || holder instanceof String) continue;
                    for (Class<?> holderType = holder.getClass(); holderType != null && holderType != Object.class; holderType = holderType.getSuperclass()) {
                        for (Field holderField : holderType.getDeclaredFields()) {
                            if (holderField.getType() != null && holderField.getType().getName().equals("com.android.billingclient.api.PurchasesUpdatedListener")) {
                                holderField.setAccessible(true);
                                return holderField.get(holder) == listener;
                            }
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
        // Unknown BillingClient versions may hide the holder behind a different shape. Do not
        // reject those versions solely because their private fields cannot be classified.
        return true;
    }

    private static int validateFlowParams(Object flowParams) {
        try {
            Method developer = flowParams.getClass().getMethod("getDeveloperBillingOptionParams");
            if (developer.invoke(flowParams) != null) return 5;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        boolean productDetailsApi = false;
        try {
            Method productsMethod = flowParams.getClass().getMethod("getProductDetailsParamsList");
            productDetailsApi = true;
            Object products = productsMethod.invoke(flowParams);
            if (!(products instanceof Iterable<?>)) return 4;
            boolean found = false;
            for (Object params : (Iterable<?>) products) {
                if (params == null) continue;
                Method detailsMethod = params.getClass().getMethod("getProductDetails");
                Object details = detailsMethod.invoke(params);
                if (details == null) continue;
                Method idMethod = details.getClass().getMethod("getProductId");
                Object id = idMethod.invoke(details);
                if (!(id instanceof String) || !valid((String) id)) continue;
                found = true;
                Method typeMethod = details.getClass().getMethod("getProductType");
                Object productType = typeMethod.invoke(details);
                if (!("inapp".equals(productType) || "subs".equals(productType))) return 5;
                if ("subs".equals(productType)) {
                    Method offerMethod;
                    try {
                        offerMethod = params.getClass().getMethod("getOfferToken");
                    } catch (NoSuchMethodException error) {
                        return 5;
                    }
                    Object offer = offerMethod.invoke(params);
                    if (!(offer instanceof String) || !valid((String) offer)) return 4;
                }
            }
            return found ? 0 : 4;
        } catch (NoSuchMethodException ignored) {
            // BillingClient versions before ProductDetails use a different flow shape. The
            // non-null parameter has already passed the stable validation available to us.
            return productDetailsApi ? 5 : 0;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return 5;
        }
    }

    /** Exact OpenIAB callback bridge used by both immediate and overlay-confirmed modes. */
    public static void dispatchLegacy(Object listener, String product) {
        dispatchLegacy(listener, null, product);
    }

    /** Exact OpenIAB callback bridge with the Activity that initiated the flow when available. */
    public static void dispatchLegacy(Object listener, Object purchaseActivity, String product) {
        if (listener == null) return;
        String normalized = valid(product) ? normalize(product) : "morphe_fake";
        Activity target = purchaseActivity instanceof Activity
                ? (Activity) purchaseActivity : activity.get();
        synchronized (InAppRuntimePolicy.class) {
            if (!configured || !popupEnabled || SAVED.contains(normalized)) {
                lastEvent = "Emulated legacy purchase delivered: " + normalized;
                deliverLegacy(listener, normalized, true);
                finishLegacyProxy(target);
                return;
            }
            if (pending != null) {
                lastEvent = "Legacy purchase request rejected while another confirmation is pending: " + normalized;
                deliverLegacy(listener, normalized, false);
                return;
            }
            pending = new Pending(listener, normalized, true, target);
            lastEvent = "Waiting for legacy confirmation: " + normalized;
        }
        final Pending request;
        synchronized (InAppRuntimePolicy.class) { request = pending; }
        MAIN.postDelayed(() -> timeout(request), 30000L);
        if (target != null) OverlayRuntime.ensureActivity(target);
        if (!OverlayRuntime.showInAppPurchaseConfirmation(target, normalized)) {
            OverlayRuntimeLogger.log("INFO", "InApp", "Legacy purchase confirmation popup queued until an active overlay Activity is available: product=" + normalized);
        }
    }

    /** Routes an OpenIAB call while preserving the proxy Activity used by old Unity plugins. */
    public static boolean routeLegacyPurchase(Object listener, Object purchaseActivity, String product,
                                              String developerPayload, boolean inapp) {
        Activity target = purchaseActivity instanceof Activity ? (Activity) purchaseActivity : null;
        if (isProxyActivity(target)) {
            return dispatchLegacyFromProxy(listener, target, product);
        }
        if (listener == null) return false;
        synchronized (InAppRuntimePolicy.class) {
            if (!configured || !popupEnabled || SAVED.contains(valid(product) ? normalize(product) : "morphe_fake")) {
                // Non-overlay mode deliberately keeps the original direct emulation behavior.
            } else {
                if (redirect != null || pending != null) {
                    deliverLegacy(listener, valid(product) ? normalize(product) : "morphe_fake", false);
                    return true;
                }
                redirect = new Redirect(listener, product, developerPayload, inapp);
            }
        }
        if (!isConfigured() || !popupEnabled() || SAVED.contains(valid(product) ? normalize(product) : "morphe_fake")) {
            dispatchLegacy(listener, target, product);
            return true;
        }
        if (!launchProxy(target, product, developerPayload, inapp)) {
            synchronized (InAppRuntimePolicy.class) {
                if (redirect != null && redirect.listener == listener) redirect = null;
            }
            deliverLegacy(listener, valid(product) ? normalize(product) : "morphe_fake", false);
        }
        return true;
    }

    private static boolean dispatchLegacyFromProxy(Object fallbackListener, Activity proxy, String product) {
        Object listener = fallbackListener;
        String redirectedProduct = product;
        synchronized (InAppRuntimePolicy.class) {
            if (redirect != null) {
                listener = redirect.listener;
                redirectedProduct = redirect.product;
                redirect = null;
            }
        }
        if (listener == null) return false;
        dispatchLegacy(listener, proxy, redirectedProduct);
        return true;
    }

    private static boolean isProxyActivity(Activity value) {
        return value != null && "org.onepf.openiab.UnityProxyActivity".equals(value.getClass().getName());
    }

    private static boolean launchProxy(Activity source, String product, String developerPayload, boolean inapp) {
        if (source == null) return false;
        try {
            Class<?> plugin = Class.forName("org.onepf.openiab.UnityPlugin");
            java.lang.reflect.Field request = plugin.getDeclaredField("sendRequest");
            request.setAccessible(true);
            request.setBoolean(null, true);
            Intent intent = new Intent(source, Class.forName("org.onepf.openiab.UnityProxyActivity"));
            intent.putExtra("sku", product);
            intent.putExtra("inapp", inapp);
            intent.putExtra("developerPayload", developerPayload);
            if (Looper.myLooper() == Looper.getMainLooper()) {
                source.startActivity(intent);
                return true;
            }
            return MAIN.post(() -> {
                try {
                    source.startActivity(intent);
                } catch (RuntimeException error) {
                    failRedirect(product, listenerForRedirect(product));
                }
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Could not launch legacy purchase proxy: " + error.getClass().getSimpleName());
            return false;
        }
    }

    private static Object listenerForRedirect(String product) {
        synchronized (InAppRuntimePolicy.class) {
            return redirect != null && redirect.product.equals(valid(product) ? normalize(product) : "morphe_fake")
                    ? redirect.listener : null;
        }
    }

    private static void failRedirect(String product, Object listener) {
        if (listener == null) return;
        synchronized (InAppRuntimePolicy.class) {
            if (redirect == null || redirect.listener != listener) return;
            redirect = null;
        }
        deliverLegacy(listener, valid(product) ? normalize(product) : "morphe_fake", false);
    }

    /** Retries a pending confirmation after the target Activity has resumed and its overlay attached. */
    public static synchronized void retryPendingConfirmation(Activity target) {
        if (pending == null) return;
        if (target != null) OverlayRuntime.ensureActivity(target);
        OverlayRuntime.showInAppPurchaseConfirmation(target, pending.product);
    }

    private static void timeout(Pending request) {
        synchronized (InAppRuntimePolicy.class) {
            if (pending != request) return;
        }
        OverlayRuntimeLogger.log("WARN", "InApp", "Purchase request timed out: product=" + request.product);
        cancelPending();
    }

    public static synchronized void complete(boolean save) {
        Pending request = pending;
        pending = null;
        if (request == null) return;
        if (save && SAVED.size() < MAX_SAVED_PURCHASES) SAVED.add(request.product);
        lastEvent = "Emulated purchase delivered: " + request.product;
        boolean delivered = request.legacy
                ? deliverLegacy(request.listener, request.product, true)
                : deliver(request.listener, request.product, 0, true);
        if (!delivered) OverlayRuntimeLogger.log("WARN", "InApp", "Purchase success callback failed: product=" + request.product);
        if (request.legacy) finishLegacyProxy(request.activity.get());
    }

    public static synchronized void cancelPending() {
        Pending request = pending;
        pending = null;
        if (request == null) return;
        lastEvent = "Purchase cancelled: " + request.product;
        boolean delivered = request.legacy
                ? deliverLegacy(request.listener, request.product, false)
                : deliver(request.listener, request.product, 1, false);
        if (!delivered) OverlayRuntimeLogger.log("WARN", "InApp", "Purchase cancellation callback failed: product=" + request.product);
        if (request.legacy) finishLegacyProxy(request.activity.get());
    }

    /** Mirrors UnityProxyActivity's original result-handled cleanup for emulated legacy flows. */
    private static void finishLegacyProxy(Activity target) {
        if (target == null || !"org.onepf.openiab.UnityProxyActivity".equals(target.getClass().getName())) return;
        MAIN.post(() -> {
            if (!target.isFinishing() && (android.os.Build.VERSION.SDK_INT < 17 || !target.isDestroyed())) {
                try { target.finish(); }
                catch (RuntimeException ignored) { }
            }
        });
    }

    private static String productId(Object first, Object second) {
        String direct = first instanceof String ? (String) first : second instanceof String ? (String) second : null;
        if (valid(direct)) return normalize(direct);
        for (Object value : new Object[] {first, second}) {
            String found = inspect(value, 0);
            if (valid(found)) return normalize(found);
        }
        return "morphe_fake";
    }

    private static String inspect(Object value, int depth) {
        if (value == null || depth > 2) return null;
        try {
            for (Method method : value.getClass().getMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (method.getParameterTypes().length != 0) continue;
                Object result = method.invoke(value);
                if (result instanceof String && (name.contains("productid") || name.equals("getsku") || name.contains("sku")) && valid((String) result)) {
                    return (String) result;
                }
                if (depth < 2 && (name.contains("product") || name.contains("sku"))) {
                    if (result instanceof Iterable<?>) {
                        for (Object item : (Iterable<?>) result) {
                            String found = inspect(item, depth + 1);
                            if (valid(found)) return found;
                        }
                    } else {
                        String found = inspect(result, depth + 1);
                        if (valid(found)) return found;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return null;
    }

    private static boolean valid(String value) { return value != null && !value.trim().isEmpty() && value.length() <= 256; }
    private static String normalize(String value) { return value.trim(); }

    private static boolean deliver(Object listener, String product, int responseCode, boolean includePurchase) {
        try {
            Class<?> resultClass = Class.forName("com.android.billingclient.api.BillingResult");
            Object builder = resultClass.getMethod("newBuilder").invoke(null);
            builder = builder.getClass().getMethod("setResponseCode", int.class).invoke(builder, responseCode);
            Object result = builder.getClass().getMethod("build").invoke(builder);
            ArrayList<Object> purchases = new ArrayList<>();
            if (includePurchase) {
                Class<?> purchaseClass = Class.forName("com.android.billingclient.api.Purchase");
                Constructor<?> constructor = purchaseClass.getConstructor(String.class, String.class);
                String json = "{\"orderId\":\"morphe_fake\",\"packageName\":\"morphe_fake\",\"productId\":\"" + jsonEscape(product) + "\",\"purchaseTime\":0,\"purchaseState\":1,\"purchaseToken\":\"morphe_fake\",\"quantity\":1,\"acknowledged\":true}";
                purchases.add(constructor.newInstance(json, "morphe_fake"));
            }
            Method callback = findPurchaseCallback(listener);
            if (callback == null) {
                OverlayRuntimeLogger.log("WARN", "InApp", "No compatible onPurchasesUpdated callback: listener=" + listener.getClass().getName());
                return false;
            }
            callback.setAccessible(true);
            callback.invoke(listener, result, purchases);
            OverlayRuntimeLogger.log("INFO", "InApp", "Purchase callback delivered: listener=" + listener.getClass().getName() +
                    ", callback=" + callback.getDeclaringClass().getName() + "->" + callback.getName());
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Purchase callback delivery failed: listener=" +
                    (listener == null ? "null" : listener.getClass().getName()) + ", error=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean deliverLegacy(Object listener, String product, boolean includePurchase) {
        try {
            Class<?> resultClass = Class.forName("org.onepf.oms.appstore.googleUtils.IabResult");
            Object result = resultClass.getConstructor(int.class, String.class)
                    .newInstance(includePurchase ? 0 : 1, includePurchase ? "Success" : "Cancelled");
            Object purchase = null;
            if (includePurchase) {
                Class<?> purchaseClass = Class.forName("org.onepf.oms.appstore.googleUtils.Purchase");
                String json = "{\"productId\":\"" + jsonEscape(product) + "\",\"orderId\":\"morphe_fake\",\"purchaseToken\":\"morphe_fake\",\"purchaseState\":0,\"purchaseTime\":0}";
                purchase = purchaseClass.getConstructor(String.class).newInstance(json);
            }
            for (Method method : listener.getClass().getMethods()) {
                if ("onIabPurchaseFinished".equals(method.getName()) && method.getParameterTypes().length == 2) {
                    method.setAccessible(true);
                    method.invoke(listener, result, purchase);
                    OverlayRuntimeLogger.log("INFO", "InApp", "Legacy purchase callback delivered: listener=" + listener.getClass().getName());
                    return true;
                }
            }
            OverlayRuntimeLogger.log("WARN", "InApp", "No compatible legacy purchase callback: listener=" + listener.getClass().getName());
        } catch (ReflectiveOperationException | RuntimeException error) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Legacy callback delivery failed: listener=" +
                    (listener == null ? "null" : listener.getClass().getName()) + ", error=" + error.getClass().getSimpleName());
        }
        return false;
    }

    private static Method findPurchaseCallback(Object listener) {
        try {
            Class<?> proxy = Class.forName("com.unity3d.services.store.gpbl.proxies.PurchaseUpdatedListenerProxy");
            if (proxy.isInstance(listener)) {
                return proxy.getMethod("onPurchasesUpdated", Object.class, List.class);
            }
        } catch (ReflectiveOperationException ignored) { }
        try {
            Class<?> api = Class.forName("com.android.billingclient.api.PurchasesUpdatedListener");
            if (api.isInstance(listener)) {
                return api.getMethod("onPurchasesUpdated", Class.forName("com.android.billingclient.api.BillingResult"), List.class);
            }
        } catch (ReflectiveOperationException ignored) { }
        for (Method method : listener.getClass().getMethods()) {
            if (!"onPurchasesUpdated".equals(method.getName()) || method.getParameterTypes().length != 2) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters[1].isAssignableFrom(ArrayList.class) || parameters[1].isAssignableFrom(List.class)) return method;
        }
        return null;
    }

    private static String jsonEscape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else result.append(c);
            }
        }
        return result.toString();
    }

    private static final class Pending {
        final Object listener; final String product; final boolean legacy; final WeakReference<Activity> activity;
        Pending(Object listener, String product, boolean legacy, Activity activity) {
            this.listener = listener; this.product = product; this.legacy = legacy;
            this.activity = new WeakReference<>(activity);
        }
    }

    private static final class Redirect {
        final Object listener;
        final String product;
        final String developerPayload;
        final boolean inapp;

        Redirect(Object listener, String product, String developerPayload, boolean inapp) {
            this.listener = listener;
            this.product = valid(product) ? normalize(product) : "morphe_fake";
            this.developerPayload = developerPayload;
            this.inapp = inapp;
        }
    }
}
