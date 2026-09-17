package unipatch.overlaycore;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
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
    private static final int DEFAULT_NON_OVERLAY_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_OVERLAY_TIMEOUT_SECONDS = 30;
    private static final Set<String> SAVED = new LinkedHashSet<>();
    private static final CatalogProductTracker CATALOG_PRODUCTS = new CatalogProductTracker();
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static PurchaseRequest pending;
    private static Redirect redirect;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static PurchaseTimeoutScheduler timeoutScheduler = (task, delay) -> MAIN.postDelayed(task, delay);
    private static final ThreadLocal<CallbackDeliveryResult> LAST_DELIVERY = new ThreadLocal<>();
    private static boolean configured;
    private static boolean popupEnabled = true;
    private static long nonOverlayTimeoutMs = DEFAULT_NON_OVERLAY_TIMEOUT_SECONDS * 1000L;
    private static long overlayTimeoutMs = DEFAULT_OVERLAY_TIMEOUT_SECONDS * 1000L;
    private static String lastEvent = "No purchase request this session";

    private InAppRuntimePolicy() { }

    public static synchronized void configure(String encoded) {
        configured = false;
        popupEnabled = true;
        pending = null;
        redirect = null;
        nonOverlayTimeoutMs = DEFAULT_NON_OVERLAY_TIMEOUT_SECONDS * 1000L;
        overlayTimeoutMs = DEFAULT_OVERLAY_TIMEOUT_SECONDS * 1000L;
        SAVED.clear();
        CATALOG_PRODUCTS.clear();
        OverlaySessionState.clearModule(MODULE);
        if (encoded == null) return;
        String[] values = encoded.split("\\|", -1);
        if ((values.length != 4 && values.length != 6) || !"1".equals(values[0]) || !MODULE.equals(values[1]) || !MODULE.equals(values[2])) return;
        if (!"0".equals(values[3]) && !"1".equals(values[3])) return;
        popupEnabled = "1".equals(values[3]);
        if (values.length == 6) configureTimeouts(parseSeconds(values[4], DEFAULT_NON_OVERLAY_TIMEOUT_SECONDS), parseSeconds(values[5], DEFAULT_OVERLAY_TIMEOUT_SECONDS));
        configured = true;
    }

    public static synchronized void configureTimeouts(int nonOverlaySeconds, int overlaySeconds) {
        nonOverlayTimeoutMs = parseSeconds(nonOverlaySeconds, DEFAULT_NON_OVERLAY_TIMEOUT_SECONDS) * 1000L;
        overlayTimeoutMs = parseSeconds(overlaySeconds, DEFAULT_OVERLAY_TIMEOUT_SECONDS) * 1000L;
    }

    public static synchronized boolean isConfigured() { return configured; }
    public static synchronized boolean popupEnabled() { return popupEnabled; }
    public static synchronized int overlayTimeoutSeconds() { return (int) Math.max(1L, overlayTimeoutMs / 1000L); }
    public static synchronized void setPopupEnabled(boolean enabled) { popupEnabled = enabled; }
    public static synchronized String lastEvent() { return lastEvent; }

    static synchronized void setTimeoutSchedulerForTests(PurchaseTimeoutScheduler scheduler) {
        timeoutScheduler = scheduler == null ? (task, delay) -> MAIN.postDelayed(task, delay) : scheduler;
    }

    static CallbackDeliveryResult lastDeliveryResultForTests() { return LAST_DELIVERY.get(); }

    /** Records a catalog or purchase object for later opt-in inventory emulation. */
    public static synchronized void rememberCatalogProduct(Object value) {
        String found = productId(value, null);
        if (valid(found)) CATALOG_PRODUCTS.remember(found);
    }

    /** Returns the only known product, or empty when inventory would be ambiguous. */
    public static synchronized String catalogProductId() {
        return CATALOG_PRODUCTS.onlyProduct();
    }

    public static String emulatedPurchaseJson(String productId) {
        String id = valid(productId) ? normalize(productId) : "";
        String token = "unipatches-inventory-token-" + Integer.toHexString(id.hashCode());
        return "{\"orderId\":\"unipatches-inventory-order-" + Integer.toHexString(id.hashCode()) +
                "\",\"productId\":\"" + PurchaseJson.escape(id) +
                "\",\"purchaseTime\":" + System.currentTimeMillis() +
                ",\"purchaseState\":1,\"purchaseToken\":\"" + token + "\",\"quantity\":1}";
    }

    public static String emulatedPurchaseToken(String productId) {
        String id = valid(productId) ? normalize(productId) : "";
        return "unipatches-inventory-token-" + Integer.toHexString(id.hashCode());
    }

    /** Builds an inventory object only when a single catalog product was observed. */
    public static Object emulatedInventoryPurchase(Class<?> purchaseClass) {
        String id = catalogProductId();
        if (!valid(id) || purchaseClass == null) return null;
        try {
            Constructor<?> constructor = purchaseClass.getConstructor(String.class, String.class);
            String token = emulatedPurchaseToken(id);
            return constructor.newInstance(emulatedPurchaseJson(id), token);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /** Extracts a product identifier from a RevenueCat/Billing object or list. */
    public static String productIdFrom(Object value) { return productId(value, null); }

    public static synchronized void reset() {
        configured = false;
        popupEnabled = true;
        nonOverlayTimeoutMs = DEFAULT_NON_OVERLAY_TIMEOUT_SECONDS * 1000L;
        overlayTimeoutMs = DEFAULT_OVERLAY_TIMEOUT_SECONDS * 1000L;
        pending = null;
        redirect = null;
        SAVED.clear();
        CATALOG_PRODUCTS.clear();
        activity.clear();
        lastEvent = "No purchase request this session";
    }

    public static synchronized void registerActivity(Activity value) {
        if (value != null) activity = new WeakReference<>(value);
    }

    public static void onActivityDetached(Activity value) {
        PurchaseRequest detachedRequest;
        synchronized (InAppRuntimePolicy.class) {
            detachedRequest = pending;
            if (value == null || detachedRequest == null || detachedRequest.sourceActivity.get() != value) return;
            OverlayRuntimeLogger.log("WARN", "InApp", "Purchase Activity detached before confirmation: product=" + detachedRequest.productId);
        }
        cancelPending();
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
        PurchaseBackend backend = modernBackend(flowParams);
        PurchaseRequest immediate = null;
        boolean reject = false;
        synchronized (InAppRuntimePolicy.class) {
            if (pending != null) {
                lastEvent = "Purchase request rejected while another confirmation is pending: " + product;
                OverlayRuntimeLogger.log("WARN", "InApp", "Rejected overlapping modern purchase: product=" + product);
                reject = true;
            } else if (!configured || !popupEnabled || SAVED.contains(product)) {
                lastEvent = "Emulated purchase delivered: " + product;
                OverlayRuntimeLogger.log("INFO", "InApp", "Intercepted modern purchase: product=" + product + ", mode=immediate");
                immediate = new PurchaseRequest(product, productType(flowParams), "", listener, target,
                        backend, false, timeoutFor(false));
                immediate.transition(PurchaseRequest.State.RECEIVED, PurchaseRequest.State.VALIDATED);
                pending = immediate;
            } else {
                pending = new PurchaseRequest(product, productType(flowParams), "", listener, target,
                        backend, true, timeoutFor(true));
                pending.transition(PurchaseRequest.State.RECEIVED, PurchaseRequest.State.VALIDATED);
                pending.transition(PurchaseRequest.State.VALIDATED, PurchaseRequest.State.WAITING_FOR_POPUP);
                lastEvent = "Waiting for confirmation: " + product;
                OverlayRuntimeLogger.log("INFO", "InApp", "Purchase request received: listener=" + listener.getClass().getName() +
                        ", activity=" + (target == null ? "null" : target.getClass().getName()) +
                        ", product=" + product + ", mode=overlay-confirmation");
            }
        }
        if (reject) {
            boolean delivered = deliver(listener, product, 1, false, backend);
            OverlayRuntimeLogger.log(delivered ? "INFO" : "WARN", "InApp",
                    "Modern cancellation result " + (delivered ? "returned" : "failed") + ": product=" + product);
            return false;
        }
        if (immediate != null) {
            final PurchaseRequest immediateRequest = immediate;
            scheduleTimeout(immediateRequest);
            boolean delivered = immediateRequest.claimCallback() &&
                    deliver(listener, product, 0, true, backend, target);
            finishImmediate(immediateRequest, delivered);
            if (!delivered) OverlayRuntimeLogger.log("WARN", "InApp", "Modern purchase callback failed: product=" + product);
            return delivered;
        }
        final PurchaseRequest request;
        synchronized (InAppRuntimePolicy.class) { request = pending; }
        scheduleTimeout(request);
        if (target != null) OverlayRuntime.ensureActivity(target);
        if (!OverlayRuntime.showInAppPurchaseConfirmation(target, product)) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Purchase confirmation popup could not attach; cancelling purchase: product=" + product);
            cancelPending();
            return false;
        }
        return true;
    }

    private static PurchaseBackend modernBackend(Object flowParams) {
        if (flowParams != null) {
            try {
                Object list = flowParams.getClass().getMethod("getProductDetailsParamsList").invoke(flowParams);
                if (list instanceof Iterable<?>) {
                    for (Object item : (Iterable<?>) list) {
                        if (item != null) {
                            item.getClass().getMethod("getProductDetails");
                            return PurchaseBackend.BILLING_V9;
                        }
                    }
                }
            } catch (ReflectiveOperationException ignored) { }
        }
        return PurchaseBackend.BILLING_V3;
    }

    private static String productType(Object flowParams) {
        if (flowParams == null) return "inapp";
        try {
            Object list = flowParams.getClass().getMethod("getProductDetailsParamsList").invoke(flowParams);
            if (list instanceof Iterable<?>) {
                for (Object item : (Iterable<?>) list) {
                    if (item == null) continue;
                    Object details = item.getClass().getMethod("getProductDetails").invoke(item);
                    if (details != null && "subs".equals(details.getClass().getMethod("getProductType").invoke(details))) {
                        return "subs";
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return "inapp";
    }

    /** Returns a BillingClient-compatible response code and completes rejected callbacks. */
    public static int validateModernPurchase(Object billingClient, Object listener,
                                             Object purchaseActivity, Object flowParams) {
        String product = productId(flowParams, null);
        int responseCode;
        if (billingClient == null || listener == null || !(purchaseActivity instanceof Activity) || flowParams == null) {
            responseCode = 5;
        } else if (!valid(product)) {
            responseCode = 4;
        } else if (!listenerHolderMatches(billingClient, listener)) {
            responseCode = 5;
        } else {
            Integer state = connectionState(billingClient);
            responseCode = state != null && state != 2 ? 2 : validateFlowParams(flowParams);
        }
        if (responseCode != 0) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Modern purchase validation failed: product=" + productId(flowParams, null) +
                    ", responseCode=" + responseCode);
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
                                if (holderField.get(holder) == listener) return true;
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
        dispatchLegacy(listener, purchaseActivity, product, "", true);
    }

    /** Legacy bridge preserving the original developer payload and item type. */
    public static void dispatchLegacy(Object listener, Object purchaseActivity, String product,
                                      String developerPayload, boolean inapp) {
        if (listener == null) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Legacy purchase consumed without listener: product=" + product);
            finishLegacyProxy(purchaseActivity instanceof Activity ? (Activity) purchaseActivity : null);
            return;
        }
        String normalized = valid(product) ? normalize(product) : "";
        Activity target = purchaseActivity instanceof Activity
                ? (Activity) purchaseActivity : activity.get();
        if (!valid(product)) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Legacy purchase rejected: missing product ID");
            deliverLegacy(listener, "", false, developerPayload, inapp);
            finishLegacyProxy(target);
            return;
        }
        PurchaseRequest immediate = null;
        boolean reject = false;
        synchronized (InAppRuntimePolicy.class) {
            if (pending != null) {
                lastEvent = "Legacy purchase request rejected while another confirmation is pending: " + normalized;
                reject = true;
            } else if (!configured || !popupEnabled || SAVED.contains(normalized)) {
                lastEvent = "Emulated legacy purchase delivered: " + normalized;
                OverlayRuntimeLogger.log("INFO", "InApp", "Intercepted legacy purchase: product=" + normalized +
                        ", mode=immediate, inapp=" + inapp);
                immediate = new PurchaseRequest(normalized, inapp ? "inapp" : "subs", developerPayload, listener, target,
                        PurchaseBackend.OPEN_IAB, false, timeoutFor(false));
                immediate.transition(PurchaseRequest.State.RECEIVED, PurchaseRequest.State.VALIDATED);
                pending = immediate;
            } else {
                pending = new PurchaseRequest(normalized, inapp ? "inapp" : "subs", developerPayload, listener, target,
                        PurchaseBackend.OPEN_IAB, true, timeoutFor(true));
                pending.transition(PurchaseRequest.State.RECEIVED, PurchaseRequest.State.VALIDATED);
                pending.transition(PurchaseRequest.State.VALIDATED, PurchaseRequest.State.WAITING_FOR_POPUP);
                lastEvent = "Waiting for legacy confirmation: " + normalized;
                OverlayRuntimeLogger.log("INFO", "InApp", "Intercepted legacy purchase: product=" + normalized +
                        ", mode=overlay-confirmation, inapp=" + inapp);
            }
        }
        if (reject) {
            boolean delivered = deliverLegacy(listener, normalized, false, developerPayload, inapp);
            OverlayRuntimeLogger.log(delivered ? "INFO" : "WARN", "InApp",
                    "Legacy cancellation result " + (delivered ? "returned" : "failed") + ": product=" + normalized);
            finishLegacyProxy(target);
            return;
        }
        if (immediate != null) {
            if (immediate.transition(PurchaseRequest.State.RECEIVED, PurchaseRequest.State.DELIVERING)) {
                final PurchaseRequest immediateRequest = immediate;
                scheduleTimeout(immediateRequest);
                OverlayRuntimeLogger.log("INFO", "InApp", "Legacy non-overlay timeout scheduled: id=" + immediateRequest.requestId +
                        ", product=" + immediateRequest.productId + ", timeoutMs=" + immediateRequest.timeoutMillis);
                boolean delivered = immediateRequest.claimCallback() && deliverLegacy(immediateRequest.listener, immediateRequest.productId, true,
                        immediateRequest.developerPayload, immediateRequest.productType.equals("inapp"), target);
                synchronized (InAppRuntimePolicy.class) {
                    if (pending == immediateRequest) {
                        immediateRequest.finish(delivered ? PurchaseRequest.State.COMPLETED : PurchaseRequest.State.CANCELLED);
                        pending = null;
                    }
                }
                if (!delivered) {
                    lastEvent = "Legacy purchase callback failed: " + normalized;
                    OverlayRuntimeLogger.log("WARN", "InApp", "Legacy purchase callback failed: product=" + normalized);
                } else {
                    OverlayRuntimeLogger.log("INFO", "InApp", "Legacy purchase result returned: product=" + normalized);
                }
            }
            finishLegacyProxy(target);
            return;
        }
        final PurchaseRequest request;
        synchronized (InAppRuntimePolicy.class) { request = pending; }
        scheduleTimeout(request);
        if (target != null) OverlayRuntime.ensureActivity(target);
        if (!OverlayRuntime.showInAppPurchaseConfirmation(target, normalized)) {
            OverlayRuntimeLogger.log("INFO", "InApp", "Legacy purchase confirmation popup queued until an active overlay Activity is available: product=" + normalized);
        }
    }

    /** Handles UnityPlugin's early purchase entry point so non-overlay mode skips its proxy Activity. */
    public static boolean interceptLegacyPurchaseEntry(Object plugin, String product,
                                                        String developerPayload, boolean inapp,
                                                        int nonOverlaySeconds, int overlaySeconds) {
        // This hook exists only when the InApp Emulation patch is selected. An
        // unconfigured runtime therefore still means standalone non-overlay
        // emulation; requiring overlay initialization here would reintroduce
        // the proxy Activity dependency.
        if (plugin == null || (isConfigured() && popupEnabled())) return false;
        try {
            Method listenerMethod = plugin.getClass().getMethod("getPurchaseFinishedListener");
            Object listener = listenerMethod.invoke(plugin);
            if (listener == null) {
                Activity target = currentActivity();
                configureTimeouts(nonOverlaySeconds, overlaySeconds);
                OverlayRuntimeLogger.log("WARN", "InApp", "UnityPlugin listener unavailable; consuming purchase without real billing: product=" + product);
                finishLegacyProxy(target);
                return true;
            }
            Activity target = currentActivity();
            configureTimeouts(nonOverlaySeconds, overlaySeconds);
            OverlayRuntimeLogger.log("INFO", "InApp", "UnityPlugin entry intercepted: product=" + product +
                    ", mode=immediate, timeout=" + nonOverlaySeconds + "s, listener=" + listener.getClass().getName());
            dispatchLegacy(listener, target, product, developerPayload, inapp);
            OverlayRuntimeLogger.log("INFO", "InApp", "Legacy proxy skipped: product=" + product);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            OverlayRuntimeLogger.log("WARN", "InApp", "UnityPlugin entry interception failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    private static Activity currentActivity() {
        try {
            Class<?> unityPlayer = Class.forName("com.unity3d.player.UnityPlayer");
            java.lang.reflect.Field field = unityPlayer.getField("currentActivity");
            Object value = field.get(null);
            return value instanceof Activity ? (Activity) value : activity.get();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return activity.get();
        }
    }

    /** Routes an OpenIAB call while preserving the proxy Activity used by old Unity plugins. */
    public static boolean routeLegacyPurchase(Object listener, Object purchaseActivity, String product,
                                              String developerPayload, boolean inapp) {
        Activity target = purchaseActivity instanceof Activity ? (Activity) purchaseActivity : null;
        if (isProxyActivity(target)) {
            return dispatchLegacyFromProxy(listener, target, product, developerPayload, inapp);
        }
        if (listener == null) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Legacy purchase intercepted without listener: product=" + product);
            finishLegacyProxy(target);
            return true;
        }
        boolean reject = false;
        synchronized (InAppRuntimePolicy.class) {
            if (!configured || !popupEnabled || SAVED.contains(valid(product) ? normalize(product) : "")) {
                // Non-overlay mode deliberately keeps the original direct emulation behavior.
            } else {
                if (redirect != null || pending != null) {
                    reject = true;
                } else {
                    redirect = new Redirect(listener, product, developerPayload, inapp);
                }
            }
        }
        if (reject) {
            String normalized = valid(product) ? normalize(product) : "";
            boolean delivered = deliverLegacy(listener, normalized, false, developerPayload, inapp);
            OverlayRuntimeLogger.log(delivered ? "INFO" : "WARN", "InApp",
                    "Legacy cancellation result " + (delivered ? "returned" : "failed") + ": product=" + normalized);
            return true;
        }
        if (!isConfigured() || !popupEnabled() || SAVED.contains(valid(product) ? normalize(product) : "")) {
            dispatchLegacy(listener, target, product, developerPayload, inapp);
            return true;
        }
        if (!launchProxy(target, product, developerPayload, inapp)) {
            synchronized (InAppRuntimePolicy.class) {
                if (redirect != null && redirect.listener == listener) redirect = null;
            }
            deliverLegacy(listener, valid(product) ? normalize(product) : "", false, developerPayload, inapp);
        }
        return true;
    }

    private static boolean dispatchLegacyFromProxy(Object fallbackListener, Activity proxy, String product,
                                                   String fallbackPayload, boolean fallbackInapp) {
        Object listener = fallbackListener;
        String redirectedProduct = product;
        String developerPayload = fallbackPayload;
        boolean inapp = fallbackInapp;
        synchronized (InAppRuntimePolicy.class) {
            if (redirect != null) {
                listener = redirect.listener;
                redirectedProduct = redirect.product;
                developerPayload = redirect.developerPayload;
                inapp = redirect.inapp;
                redirect = null;
            }
        }
        if (listener == null) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Legacy proxy listener unavailable; consuming proxy flow: product=" + redirectedProduct);
            finishLegacyProxy(proxy);
            return true;
        }
        dispatchLegacy(listener, proxy, redirectedProduct, developerPayload, inapp);
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
                OverlayRuntimeLogger.log("INFO", "InApp", "Legacy proxy launched: product=" + product);
                source.startActivity(intent);
                return true;
            }
            return MAIN.post(() -> {
                try {
                    OverlayRuntimeLogger.log("INFO", "InApp", "Legacy proxy launched: product=" + product);
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
            return redirect != null && redirect.product.equals(valid(product) ? normalize(product) : "")
                    ? redirect.listener : null;
        }
    }

    private static void failRedirect(String product, Object listener) {
        if (listener == null) return;
        synchronized (InAppRuntimePolicy.class) {
            if (redirect == null || redirect.listener != listener) return;
            redirect = null;
        }
        deliverLegacy(listener, valid(product) ? normalize(product) : "", false);
    }

    /** Retries a pending confirmation after the target Activity has resumed and its overlay attached. */
    public static void retryPendingConfirmation(Activity target) {
        String product;
        synchronized (InAppRuntimePolicy.class) {
            if (pending == null) return;
            product = pending.productId;
        }
        if (target != null) OverlayRuntime.ensureActivity(target);
        OverlayRuntime.showInAppPurchaseConfirmation(target, product);
    }

    private static void timeout(PurchaseRequest request) {
        synchronized (InAppRuntimePolicy.class) {
            if (pending != request) return;
            if (request.state() == PurchaseRequest.State.DELIVERING) return;
        }
        OverlayRuntimeLogger.log("WARN", "InApp", "Purchase request timed out: id=" + request.requestId + ", product=" + request.productId);
        if (cancelPending()) {
            Activity target = request.sourceActivity.get();
            if (target == null) target = activity.get();
            if (target != null) {
                try {
                    Toast.makeText(target.getApplicationContext(), "Purchase timed out", Toast.LENGTH_SHORT).show();
                } catch (RuntimeException error) {
                    OverlayRuntimeLogger.log("WARN", "InApp", "Timeout toast failed: " + error.getClass().getSimpleName());
                }
            }
        }
    }

    private static long timeoutMillis(PurchaseRequest request) { return request.timeoutMillis; }

    private static void scheduleTimeout(PurchaseRequest request) {
        PurchaseTimeoutScheduler scheduler;
        synchronized (InAppRuntimePolicy.class) { scheduler = timeoutScheduler; }
        scheduler.schedule(() -> timeout(request), timeoutMillis(request));
    }

    private static long timeoutFor(boolean overlayMode) {
        synchronized (InAppRuntimePolicy.class) {
            return (overlayMode ? overlayTimeoutMs : nonOverlayTimeoutMs);
        }
    }

    private static int parseSeconds(String value, int fallback) {
        try { return parseSeconds(Integer.parseInt(value), fallback); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static int parseSeconds(int value, int fallback) {
        return value > 0 && value <= 86_400 ? value : fallback;
    }

    private static void finishImmediate(PurchaseRequest request, boolean delivered) {
        synchronized (InAppRuntimePolicy.class) {
            if (pending != request || !request.finish(delivered ? PurchaseRequest.State.COMPLETED : PurchaseRequest.State.CANCELLED)) return;
            pending = null;
        }
        if (!delivered) OverlayRuntimeLogger.log("WARN", "InApp", "Immediate purchase callback failed; no second callback attempted: product=" + request.productId);
    }

    public static void complete(boolean save) {
        PurchaseRequest request;
        synchronized (InAppRuntimePolicy.class) {
            request = pending;
            if (request == null || !request.transition(PurchaseRequest.State.WAITING_FOR_POPUP, PurchaseRequest.State.DELIVERING)) return;
            if (save && SAVED.size() < MAX_SAVED_PURCHASES) SAVED.add(request.productId);
            lastEvent = "Emulated purchase delivered: " + request.productId;
        }
        boolean delivered = request.claimCallback() && (request.backend == PurchaseBackend.OPEN_IAB
                ? deliverLegacy(request.listener, request.productId, true, request.developerPayload, request.productType.equals("inapp"), request.sourceActivity.get())
                : deliver(request.listener, request.productId, 0, true, request.backend, request.sourceActivity.get()));
        synchronized (InAppRuntimePolicy.class) {
            if (pending == request) {
                request.finish(delivered ? PurchaseRequest.State.COMPLETED : PurchaseRequest.State.CANCELLED);
                pending = null;
            }
        }
        if (!delivered) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Purchase success callback failed; no second callback attempted: product=" + request.productId);
        }
        if (request.backend == PurchaseBackend.OPEN_IAB) finishLegacyProxy(request.sourceActivity.get());
    }

    public static boolean cancelPending() {
        PurchaseRequest request;
        synchronized (InAppRuntimePolicy.class) {
            request = pending;
            if (request == null || request.state() == PurchaseRequest.State.DELIVERING || request.isFinished()) return false;
            if (!request.transition(request.state(), PurchaseRequest.State.DELIVERING)) return false;
            lastEvent = "Purchase cancelled: " + request.productId;
        }
        boolean delivered = request.claimCallback() && (request.backend == PurchaseBackend.OPEN_IAB
                ? deliverLegacy(request.listener, request.productId, false, request.developerPayload, request.productType.equals("inapp"), request.sourceActivity.get())
                : deliver(request.listener, request.productId, 1, false, request.backend, request.sourceActivity.get()));
        synchronized (InAppRuntimePolicy.class) {
            if (pending == request) {
                request.finish(PurchaseRequest.State.CANCELLED);
                pending = null;
            }
        }
        if (!delivered) OverlayRuntimeLogger.log("WARN", "InApp", "Purchase cancellation callback failed: product=" + request.productId);
        if (request.backend == PurchaseBackend.OPEN_IAB) finishLegacyProxy(request.sourceActivity.get());
        return true;
    }

    /** Mirrors UnityProxyActivity's original result-handled cleanup for emulated legacy flows. */
    private static void finishLegacyProxy(Activity target) {
        if (target == null || !LegacyProxyCleanupPolicy.shouldFinish(
                target.getClass().getName(), target.isFinishing(),
                android.os.Build.VERSION.SDK_INT >= 17 && target.isDestroyed())) return;
        OverlayRuntimeLogger.log("INFO", "InApp", "Finishing legacy purchase proxy: " + target.getClass().getName());
        MAIN.post(() -> {
            if (LegacyProxyCleanupPolicy.shouldFinish(
                    target.getClass().getName(), target.isFinishing(),
                    android.os.Build.VERSION.SDK_INT >= 17 && target.isDestroyed())) {
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
        return "";
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
        return deliver(listener, product, responseCode, includePurchase, PurchaseBackend.BILLING_V9);
    }

    private static boolean deliver(Object listener, String product, int responseCode, boolean includePurchase,
                                   PurchaseBackend backend) {
        return deliver(listener, product, responseCode, includePurchase, backend, null);
    }

    private static boolean deliver(Object listener, String product, int responseCode, boolean includePurchase,
                                   PurchaseBackend backend, Activity sourceActivity) {
        if (listener == null) {
            LAST_DELIVERY.set(CallbackDeliveryResult.unavailable());
            return false;
        }
        // Claim before constructing or invoking anything. A reflection failure must not
        // trigger a second callback attempt from a competing completion path.
        // The request-level claim is applied by callers that own a request; this guard
        // protects direct rejection paths where no request object exists.
        try {
            Class<?> resultClass = Class.forName("com.android.billingclient.api.BillingResult");
            Object builder = resultClass.getMethod("newBuilder").invoke(null);
            builder = builder.getClass().getMethod("setResponseCode", int.class).invoke(builder, responseCode);
            Object result = builder.getClass().getMethod("build").invoke(builder);
            ArrayList<Object> purchases = new ArrayList<>();
            if (includePurchase) {
                Activity target = sourceActivity != null ? sourceActivity : activity.get();
                String packageName = target == null ? "" : target.getPackageName();
                Object purchase = backend == PurchaseBackend.BILLING_V3
                        ? BillingV3PurchaseFactory.create(product, packageName)
                        : BillingV9PurchaseFactory.create(product, packageName);
                purchases.add(purchase);
            }
            Method callback = findPurchaseCallback(listener);
            if (callback == null) {
                LAST_DELIVERY.set(CallbackDeliveryResult.unavailable());
                OverlayRuntimeLogger.log("WARN", "InApp", "No compatible onPurchasesUpdated callback: listener=" + listener.getClass().getName());
                return false;
            }
            callback.setAccessible(true);
            LAST_DELIVERY.set(CallbackDeliveryResult.located());
            OverlayRuntimeLogger.log("INFO", "InApp", "Purchase callback located: " + callback.getDeclaringClass().getName() +
                    "->" + callback.getName() + ", product=" + product);
            OverlayRuntimeLogger.log("INFO", "InApp", "Purchase callback invocation started: product=" + product);
            CallbackDeliveryResult outcome = CallbackInvoker.invoke(true, () -> callback.invoke(listener, result, purchases));
            LAST_DELIVERY.set(outcome);
            if (!outcome.succeeded()) {
                OverlayRuntimeLogger.log("WARN", "InApp", "Purchase callback did not return successfully: product=" + product);
                return false;
            }
            OverlayRuntimeLogger.log("INFO", "InApp", "Purchase callback delivered: listener=" + listener.getClass().getName() +
                    ", callback=" + callback.getDeclaringClass().getName() + "->" + callback.getName());
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            LAST_DELIVERY.set(error instanceof InvocationTargetException
                    ? CallbackDeliveryResult.threw() : CallbackDeliveryResult.located());
            OverlayRuntimeLogger.log("WARN", "InApp", "Purchase callback delivery failed: listener=" +
                    (listener == null ? "null" : listener.getClass().getName()) + ", phase=" +
                    (error instanceof InvocationTargetException ? "callback-threw" : "construction-or-lookup") +
                    ", error=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean deliverLegacy(Object listener, String product, boolean includePurchase) {
        return deliverLegacy(listener, product, includePurchase, "", true);
    }

    private static boolean deliverLegacy(Object listener, String product, boolean includePurchase,
                                         String developerPayload, boolean inapp) {
        return deliverLegacy(listener, product, includePurchase, developerPayload, inapp, null);
    }

    private static boolean deliverLegacy(Object listener, String product, boolean includePurchase,
                                         String developerPayload, boolean inapp, Activity sourceActivity) {
        if (listener == null) {
            LAST_DELIVERY.set(CallbackDeliveryResult.unavailable());
            return false;
        }
        try {
            Class<?> resultClass = Class.forName("org.onepf.oms.appstore.googleUtils.IabResult");
            Object result = resultClass.getConstructor(int.class, String.class)
                    .newInstance(includePurchase ? 0 : 1, includePurchase ? "Success" : "Cancelled");
            Object purchase = null;
            if (includePurchase) {
                Activity target = sourceActivity != null ? sourceActivity : currentActivity();
                String packageName = target == null ? "" : target.getPackageName();
                purchase = OpenIabPurchaseFactory.create(product, packageName, developerPayload, inapp);
                OverlayRuntimeLogger.log("INFO", "InApp", "Legacy purchase constructed: product=" + product +
                        ", itemType=" + (inapp ? "inapp" : "subs") + ", package=" + packageName);
            }
            Class<?> purchaseClass = Class.forName("org.onepf.oms.appstore.googleUtils.Purchase");
            Method callback = findLegacyPurchaseCallback(listener, resultClass, purchaseClass);
            if (callback != null) {
                callback.setAccessible(true);
                final Object callbackPurchase = purchase;
                LAST_DELIVERY.set(CallbackDeliveryResult.located());
                OverlayRuntimeLogger.log("INFO", "InApp", "Legacy callback method: " + callback.getDeclaringClass().getName() +
                    "->" + callback.getName());
                OverlayRuntimeLogger.log("INFO", "InApp", "Legacy callback invocation started: product=" + product);
                CallbackDeliveryResult outcome = CallbackInvoker.invoke(true, () -> callback.invoke(listener, result, callbackPurchase));
                LAST_DELIVERY.set(outcome);
                if (!outcome.succeeded()) {
                    OverlayRuntimeLogger.log("WARN", "InApp", "Legacy callback did not return successfully: product=" + product);
                    return false;
                }
                OverlayRuntimeLogger.log("INFO", "InApp", "Legacy purchase callback delivered: listener=" + listener.getClass().getName() + ", inapp=" + inapp);
                return true;
            }
            OverlayRuntimeLogger.log("WARN", "InApp", "No compatible legacy purchase callback: listener=" + listener.getClass().getName());
        } catch (ReflectiveOperationException | RuntimeException error) {
            LAST_DELIVERY.set(error instanceof InvocationTargetException
                    ? CallbackDeliveryResult.threw() : CallbackDeliveryResult.located());
            OverlayRuntimeLogger.log("WARN", "InApp", "Legacy callback delivery failed: listener=" +
                    (listener == null ? "null" : listener.getClass().getName()) + ", phase=" +
                    (error instanceof InvocationTargetException ? "callback-threw" : "construction-or-lookup") +
                    ", error=" + error.getClass().getSimpleName());
        }
        return false;
    }

    private static Method findLegacyPurchaseCallback(Object listener, Class<?> resultClass, Class<?> purchaseClass) {
        try {
            Class<?> api = Class.forName("org.onepf.oms.appstore.googleUtils.IabHelper$OnIabPurchaseFinishedListener");
            if (api.isInstance(listener)) return api.getMethod("onIabPurchaseFinished", resultClass, purchaseClass);
        } catch (ReflectiveOperationException ignored) { }
        for (Method method : listener.getClass().getMethods()) {
            if ("onIabPurchaseFinished".equals(method.getName()) && method.getParameterTypes().length == 2) return method;
        }
        return null;
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

    private static final class Redirect {
        final Object listener;
        final String product;
        final String developerPayload;
        final boolean inapp;

        Redirect(Object listener, String product, String developerPayload, boolean inapp) {
            this.listener = listener;
            this.product = valid(product) ? normalize(product) : "";
            this.developerPayload = developerPayload;
            this.inapp = inapp;
        }
    }
}
