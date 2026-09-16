package unipatch.overlaycore;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import unipatch.overlaycore.modules.OverlaySessionState;

/** Session-local policy and callback bridge for the optional InApp overlay module. */
public final class InAppRuntimePolicy {
    public interface ConfirmationCallback { void complete(boolean save); }

    private static final String MODULE = "inAppEmulation";
    private static final int MAX_SAVED_PURCHASES = 128;
    private static final Set<String> SAVED = new LinkedHashSet<>();
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static Pending pending;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean configured;
    private static boolean popupEnabled = true;
    private static String lastEvent = "No purchase request this session";

    private InAppRuntimePolicy() { }

    public static synchronized void configure(String encoded) {
        configured = false;
        pending = null;
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
        SAVED.clear();
        activity.clear();
        lastEvent = "No purchase request this session";
    }

    public static synchronized void registerActivity(Activity value) {
        if (value != null) activity = new WeakReference<>(value);
    }

    public static synchronized String[] savedPurchases() { return SAVED.toArray(new String[0]); }

    public static synchronized void removeUnsaved(boolean[] values, String[] identifiers) {
        if (values == null || identifiers == null) return;
        for (int i = 0; i < identifiers.length; i++) {
            if (i >= values.length || !values[i]) SAVED.remove(identifiers[i]);
        }
    }

    public static void dispatch(Object listener, Object first, Object second) {
        if (listener == null) return;
        String product = productId(first, second);
        synchronized (InAppRuntimePolicy.class) {
            if (!configured || !popupEnabled || SAVED.contains(product) || pending != null) {
                lastEvent = "Emulated purchase delivered: " + product;
                deliver(listener, product, 0, true);
                return;
            }
            pending = new Pending(listener, product);
            lastEvent = "Waiting for confirmation: " + product;
        }
        final Pending request;
        synchronized (InAppRuntimePolicy.class) { request = pending; }
        MAIN.postDelayed(() -> timeout(request), 30000L);
        if (!OverlayRuntime.showInAppPurchaseConfirmation(product)) {
            cancelPending();
        }
    }

    private static void timeout(Pending request) {
        synchronized (InAppRuntimePolicy.class) {
            if (pending != request) return;
        }
        cancelPending();
    }

    public static synchronized void complete(boolean save) {
        Pending request = pending;
        pending = null;
        if (request == null) return;
        if (save && SAVED.size() < MAX_SAVED_PURCHASES) SAVED.add(request.product);
        lastEvent = "Emulated purchase delivered: " + request.product;
        deliver(request.listener, request.product, 0, true);
    }

    public static synchronized void cancelPending() {
        Pending request = pending;
        pending = null;
        if (request == null) return;
        lastEvent = "Purchase cancelled: " + request.product;
        deliver(request.listener, request.product, 1, false);
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

    private static void deliver(Object listener, String product, int responseCode, boolean includePurchase) {
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
            Method callback = null;
            for (Method method : listener.getClass().getMethods()) {
                if ("onPurchasesUpdated".equals(method.getName()) && method.getParameterTypes().length == 2) {
                    callback = method; break;
                }
            }
            if (callback != null) callback.invoke(listener, result, purchases);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
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
        final Object listener; final String product;
        Pending(Object listener, String product) { this.listener = listener; this.product = product; }
    }
}
