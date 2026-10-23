package unipatch.overlaycore;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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
    /**
     * Response codes this module assigned to results it built. The patched
     * BillingResult.getResponseCode keeps a stamped code and reports 0 for
     * every other result, so a stock SERVICE_UNAVAILABLE / NETWORK_ERROR
     * failure cannot flip a game's own billing gate to "unavailable" while
     * every emulated call succeeds. Weak keys: results die with their game.
     */
    private static final Map<Object, Integer> STAMPED_CODES =
            Collections.synchronizedMap(new WeakHashMap<Object, Integer>());
    /**
     * Latched the first time a patched BillingClient.startConnection runs.
     * isReady/getConnectionState must report DISCONNECTED before that point:
     * always-on readiness short-circuits connection-gated games (the
     * b0()-style ready checks) before they ever call startConnection, so
     * their setup listener never fires and their own billing gate stays shut
     * forever. Process-lifetime on purpose: endConnection is a no-op, so the
     * emulated connection never drops and must not be revoked mid-session.
     */
    private static boolean connectionReady;


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

    /** Product of the last successful emulated delivery, or null. */
    private static volatile String LAST_DELIVERED_PRODUCT;

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

    /**
     * The purchase most recently delivered to the game, as an inventory object.
     *
     * A wrapper that grants a purchase re-reads its own inventory to resolve
     * that purchase; an empty query answer makes it report "purchase not
     * found" and the grant is dropped even though the callback arrived. This
     * answers the query with the purchase that was just delivered, and stays
     * silent when no purchase has happened, so a cold start still reports an
     * empty inventory instead of a fabricated one.
     */
    public static Object deliveredPurchase(Class<?> purchaseClass) {
        PurchaseRequest request = pending;
        if (purchaseClass == null) return null;
        String id = LAST_DELIVERED_PRODUCT;
        Log.i("UnipatchSeed", "deliveredPurchase: product=" + id +
                " class=" + purchaseClass.getName());
        if (!valid(id)) return null;
        try {
            Constructor<?> constructor = purchaseClass.getConstructor(String.class, String.class);
            String token = emulatedPurchaseToken(id);
            Object purchase = constructor.newInstance(emulatedPurchaseJson(id), token);
            Log.i("UnipatchSeed", "deliveredPurchase built: product=" + id);
            return purchase;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("UnipatchSeed", "delivered purchase not representable: " + error);
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
        LAST_DELIVERED_PRODUCT = null;
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
        cancelPending(detachedRequest.requestId);
    }

    public static synchronized String[] savedPurchases() { return SAVED.toArray(new String[0]); }

    public static synchronized void removeUnsaved(boolean[] values, String[] identifiers) {
        if (values == null || identifiers == null) return;
        for (int i = 0; i < identifiers.length; i++) {
            if (i >= values.length || !values[i]) SAVED.remove(identifiers[i]);
        }
    }

    public static boolean dispatch(Object listener, Object purchaseActivity, Object flowParams) {
        Log.i("UnipatchSeed", "dispatch enter: listener=" +
                (listener == null ? "null" : listener.getClass().getName()) +
                " isPurchasesUpdatedListener=" + isPurchasesUpdatedListener(listener) +
                " stack=" + dispatchCaller());
        if (listener == null) return false;
        Activity target = purchaseActivity instanceof Activity ? (Activity) purchaseActivity : null;
        String product = productId(flowParams, null);
        PurchaseBackend backend = modernBackend(flowParams);
        PurchaseRequest immediate = null;
        PurchaseRequest confirmation = null;
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
                confirmation = pending;
                lastEvent = "Waiting for confirmation: " + product;
                OverlayRuntimeLogger.log("INFO", "InApp", "Purchase request received: listener=" + listener.getClass().getName() +
                        ", activity=" + (target == null ? "null" : target.getClass().getName()) +
                        ", product=" + product + ", mode=overlay-confirmation");
            }
        }
        if (reject) {
            Log.w("UnipatchSeed", "dispatch rejected overlapping request: product=" + product +
                    " pending=" + describePending());
            boolean delivered = deliver(listener, product, 1, false, backend);
            OverlayRuntimeLogger.log(delivered ? "INFO" : "WARN", "InApp",
                    "Modern cancellation result " + (delivered ? "returned" : "failed") + ": product=" + product);
            return false;
        }
        if (immediate != null) {
            final PurchaseRequest immediateRequest = immediate;
            scheduleTimeout(immediateRequest);
            boolean enteredDelivery = immediateRequest.transition(PurchaseRequest.State.VALIDATED, PurchaseRequest.State.DELIVERING);
            boolean claimed = enteredDelivery && immediateRequest.claimCallback();
            boolean delivered = claimed && deliver(listener, product, 0, true, backend, target);
            finishImmediate(immediateRequest, delivered);
            Log.i("UnipatchSeed", "immediate dispatch: product=" + product +
                    " claimed=" + claimed + " delivered=" + delivered + " backend=" + backend);
            if (!delivered) {
                Log.w("UnipatchSeed", "immediate delivery failed: product=" + product +
                        " claimed=" + claimed + " listener=" + listener.getClass().getName());
                OverlayRuntimeLogger.log("WARN", "InApp", "Modern purchase callback failed: product=" + product);
            }
            return delivered;
        }
        final PurchaseRequest request = confirmation;
        scheduleTimeout(request);
        if (target != null) OverlayRuntime.ensureActivity(target);
        if (!OverlayRuntime.showInAppPurchaseConfirmation(target, product, request.requestId)) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Purchase confirmation popup could not attach; cancelling purchase: product=" + product);
            cancelPending(request.requestId);
            return false;
        }
        return true;
    }

    /** Whether the object really is a billing purchase listener. */
    private static boolean isPurchasesUpdatedListener(Object listener) {
        if (listener == null) return false;
        for (Class<?> type = listener.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> face : type.getInterfaces()) {
                if (face.getName().contains("PurchasesUpdatedListener")) return true;
            }
        }
        return false;
    }

    /** First patched frame above this one, so the log names the call site. */
    private static String dispatchCaller() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if (InAppRuntimePolicy.class.getName().equals(frame.getClassName())) continue;
            return frame.getClassName() + "." + frame.getMethodName();
        }
        return "unknown";
    }

    /** What the in-flight request looks like when a second purchase is refused. */
    private static String describePending() {        PurchaseRequest request = pending;
        if (request == null) return "null";
        return request.state() + " product=" + request.productId + " backend=" + request.backend +
                " finished=" + request.isFinished();
    }

    private static PurchaseBackend modernBackend(Object flowParams) {        if (flowParams != null) {
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

    /**
     * Returns a BillingClient-compatible response code and completes rejected callbacks.
     * {@code purchaseActivity} is carried only to keep the injected call shape stable;
     * validation does not require it to be an Activity.
     */
    public static int validateModernPurchase(Object billingClient, Object listener,
                                             Object purchaseActivity, Object flowParams) {
        String product = productId(flowParams, null);
        int responseCode;
        if (billingClient == null || listener == null || flowParams == null) {
            responseCode = 5;
        } else if (!valid(product)) {
            // Named because the id is the only thing standing between a rejected
            // purchase and a granted one, and reflection on billing internals
            // fails silently everywhere else in this class.
            Log.w("UnipatchSeed", "validate rejected: no product id from " +
                    (flowParams == null ? "null" : flowParams.getClass().getName()));
            responseCode = 4;
        } else if (!listenerHolderMatches(billingClient, listener)) {
            responseCode = 5;
        } else {
            // purchaseActivity is advisory: backends that pass only flow parameters
            // fall through to the registered overlay activity in dispatch(), so a
            // non-Activity (or absent) target must not veto a valid purchase. The
            // private connection-state field is also not consulted -- the patched
            // client reports ready regardless of Play Services availability, and
            // gating on that field rejected purchases the patch had already accepted.
            responseCode = validateFlowParams(flowParams);
        }
        if (responseCode != 0) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Modern purchase validation failed: product=" + productId(flowParams, null) +
                    ", responseCode=" + responseCode);
            deliver(listener, productId(flowParams, null), responseCode, false);
        }
        return responseCode;
    }

    /** Latches the emulated connection; called by the patched startConnection. */
    public static synchronized void markConnectionReady() { connectionReady = true; }

    public static synchronized boolean isConnectionReady() { return connectionReady; }

    /** DISCONNECTED (0) until startConnection runs, then CONNECTED (2). */
    public static synchronized int connectionState() { return connectionReady ? 2 : 0; }

    /**
     * Response code a patched BillingResult reports. Stamped results keep
     * their real code, because cancel and validation failures must still
     * read as failures; any unstamped result came from the stock store and
     * reads as OK while billing is emulated.
     */
    public static int billingResponseCode(Object result) {
        Integer stamped = result == null ? null : STAMPED_CODES.get(result);
        return stamped == null ? 0 : stamped;
    }

    /** Records the real response code of a result this module built. */
    public static void stampResponseCode(Object result, int responseCode) {
        if (result != null) STAMPED_CODES.put(result, responseCode);
    }

    /** Builds BillingResult without linking the extension against a BillingClient version. */
    public static Object billingResult(int responseCode) {
        try {
            Class<?> resultClass = Class.forName("com.android.billingclient.api.BillingResult");
            Object builder = resultClass.getMethod("newBuilder").invoke(null);
            builder = builder.getClass().getMethod("setResponseCode", int.class).invoke(builder, responseCode);
            Object result = builder.getClass().getMethod("build").invoke(builder);
            stampResponseCode(result, responseCode);
            return result;
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

    /** Resolve private BillingClient listener holders without injecting illegal field access. */
    public static Object purchaseListener(Object billingClient) {
        if (billingClient == null) return null;
        final String listenerType = "com.android.billingclient.api.PurchasesUpdatedListener";
        for (Class<?> type = billingClient.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                String fieldType = field.getType().getName();
                if (!listenerType.equals(fieldType) && !fieldType.startsWith("com.android.billingclient.api.")) continue;
                try {
                    field.setAccessible(true);
                    Object holder = field.get(billingClient);
                    if (holder == null) continue;
                    if (listenerType.equals(fieldType)) return holder;
                    for (Class<?> holderType = holder.getClass(); holderType != null && holderType != Object.class; holderType = holderType.getSuperclass()) {
                        for (Field inner : holderType.getDeclaredFields()) {
                            if (!listenerType.equals(inner.getType().getName())) continue;
                            inner.setAccessible(true);
                            Object listener = inner.get(holder);
                            if (listener != null) return listener;
                        }
                    }
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
        PurchaseRequest confirmation = null;
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
                confirmation = pending;
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
            boolean enteredDelivery = immediate.transition(PurchaseRequest.State.VALIDATED, PurchaseRequest.State.DELIVERING);
            if (!enteredDelivery) {
                OverlayRuntimeLogger.log("WARN", "InApp", "Legacy request transition rejected: VALIDATED -> DELIVERING, product=" + immediate.productId);
            }
            if (enteredDelivery) {
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
        final PurchaseRequest request = confirmation;
        scheduleTimeout(request);
        if (target != null) OverlayRuntime.ensureActivity(target);
        if (!OverlayRuntime.showInAppPurchaseConfirmation(target, normalized, request.requestId)) {
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
            // The hook already owns this entry point.  Do not fall through to
            // UnityPlugin's real proxy/billing path when listener discovery
            // fails; that path can wait forever on devices without Play.
            dispatchLegacy(null, currentActivity(), product, developerPayload, inapp);
            return true;
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

    /** Identifies the current confirmation without binding stale popup work to a newer request. */
    public static synchronized long pendingConfirmationId(String productId) {
        return pending != null && pending.state() == PurchaseRequest.State.WAITING_FOR_POPUP
                && pending.productId.equals(productId) ? pending.requestId : 0L;
    }

    public static synchronized boolean isPendingConfirmation(long requestId) {
        return pending != null && pending.requestId == requestId
                && pending.state() == PurchaseRequest.State.WAITING_FOR_POPUP && !pending.isFinished();
    }

    /** Retries only the request that was waiting when this Activity resumed. */
    public static void retryPendingConfirmation(Activity target) {
        PurchaseRequest request;
        synchronized (InAppRuntimePolicy.class) {
            request = pending;
            if (request == null || request.state() != PurchaseRequest.State.WAITING_FOR_POPUP) return;
        }
        if (target != null) OverlayRuntime.ensureActivity(target);
        OverlayRuntime.showInAppPurchaseConfirmation(target, request.productId, request.requestId);
    }

    private static void timeout(PurchaseRequest request) {
        if (request == null || !timeoutPending(request.requestId)) return;
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

    private static long timeoutMillis(PurchaseRequest request) { return request.timeoutMillis; }

    private static void scheduleTimeout(PurchaseRequest request) {
        if (request == null) return;
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

    /** Compatibility entry point for callers that explicitly mean the current request. */
    public static void complete(boolean save) {
        long requestId;
        synchronized (InAppRuntimePolicy.class) { requestId = pending == null ? 0L : pending.requestId; }
        complete(requestId, save);
    }

    /** Completes only the confirmation that produced this UI action. */
    public static void complete(long requestId, boolean save) {
        PurchaseRequest request;
        synchronized (InAppRuntimePolicy.class) {
            request = pending;
            if (request == null || request.requestId != requestId ||
                    !request.transition(PurchaseRequest.State.WAITING_FOR_POPUP, PurchaseRequest.State.DELIVERING)) return;
        }
        boolean delivered = request.claimCallback() && (request.backend == PurchaseBackend.OPEN_IAB
                ? deliverLegacy(request.listener, request.productId, true, request.developerPayload, request.productType.equals("inapp"), request.sourceActivity.get())
                : deliver(request.listener, request.productId, 0, true, request.backend, request.sourceActivity.get()));
        synchronized (InAppRuntimePolicy.class) {
            if (pending == request) {
                request.finish(delivered ? PurchaseRequest.State.COMPLETED : PurchaseRequest.State.CANCELLED);
                // A failed callback must never turn future taps into automatic confirmation.
                if (delivered && save && SAVED.size() < MAX_SAVED_PURCHASES) SAVED.add(request.productId);
                lastEvent = delivered ? "Emulated purchase delivered: " + request.productId
                        : "Purchase callback failed: " + request.productId;
                pending = null;
            }
        }
        if (!delivered) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Purchase success callback failed; no second callback attempted: product=" + request.productId);
        }
        if (request.backend == PurchaseBackend.OPEN_IAB) finishLegacyProxy(request.sourceActivity.get());
    }

    public static boolean cancelPending() {
        long requestId;
        synchronized (InAppRuntimePolicy.class) { requestId = pending == null ? 0L : pending.requestId; }
        return cancelPending(requestId);
    }

    public static boolean cancelPending(long requestId) {
        return cancelRequest(requestId, PurchaseRequest.State.CANCELLED);
    }

    public static boolean timeoutPending(long requestId) {
        return cancelRequest(requestId, PurchaseRequest.State.TIMED_OUT);
    }

    /** Checks identity and claims cancellation in the same critical section. */
    private static boolean cancelRequest(long requestId, PurchaseRequest.State terminalState) {
        PurchaseRequest request;
        synchronized (InAppRuntimePolicy.class) {
            request = pending;
            if (request == null || request.requestId != requestId ||
                    request.state() == PurchaseRequest.State.DELIVERING || request.isFinished()) return false;
            if (!request.transition(request.state(), PurchaseRequest.State.DELIVERING)) return false;
            lastEvent = (terminalState == PurchaseRequest.State.TIMED_OUT ? "Purchase timed out: " : "Purchase cancelled: ") + request.productId;
        }
        boolean delivered = request.claimCallback() && (request.backend == PurchaseBackend.OPEN_IAB
                ? deliverLegacy(request.listener, request.productId, false, request.developerPayload, request.productType.equals("inapp"), request.sourceActivity.get())
                : deliver(request.listener, request.productId, 1, false, request.backend, request.sourceActivity.get()));
        synchronized (InAppRuntimePolicy.class) {
            if (pending == request) {
                request.finish(terminalState);
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
            // Getters alone are not enough. BillingFlowParams keeps the product
            // details in a private field and exposes no accessor for it, so a
            // params object offered nothing but getClass/hashCode/newBuilder and
            // every purchase came back ITEM_UNAVAILABLE. Fields are read by
            // declared type, which survives the obfuscation.
            found = inspectFields(value, 0);
            if (valid(found)) return normalize(found);
        }
        return "";
    }

    /** Product/sku bearing fields, private or not, one or two levels deep. */
    private static String inspectFields(Object value, int depth) {
        if (value == null || depth > 2) return null;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Class<?> shape = field.getType();
                if (shape.isPrimitive() && shape != long.class && shape != double.class) continue;
                Object held;
                try {
                    field.setAccessible(true);
                    held = field.get(value);
                } catch (ReflectiveOperationException | RuntimeException unreachable) {
                    continue;
                }
                if (held == null) continue;
                if (held instanceof String && valid((String) held)) return (String) held;
                if (held instanceof Iterable<?>) {
                    for (Object item : (Iterable<?>) held) {
                        String found = inspect(item, depth + 1);
                        if (valid(found)) return found;
                        found = inspectFields(item, depth + 1);
                        if (valid(found)) return found;
                    }
                    continue;
                }
                if (shape.getName().startsWith("com.android.billingclient.")) {
                    String found = inspect(held, depth + 1);
                    if (valid(found)) return found;
                    found = inspectFields(held, depth + 1);
                    if (valid(found)) return found;
                }
            }
        }
        return null;
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
            // Cancel (code 1) must keep reading as cancel once getResponseCode
            // coerces unstamped stock results to OK.
            stampResponseCode(result, responseCode);
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
            // Recorded before the callback runs, not after: a wrapper grants
            // inside onPurchasesUpdated and re-reads its own inventory from
            // there, so the query that resolves this purchase happens while the
            // callback is still on the stack. Recording afterwards left that
            // query empty and the grant was dropped as "purchase not found".
            if (responseCode == 0 && includePurchase && valid(product)) {
                LAST_DELIVERED_PRODUCT = normalize(product);
            }
            CallbackDeliveryResult outcome = CallbackInvoker.invoke(true, () -> callback.invoke(listener, result, purchases));
            LAST_DELIVERY.set(outcome);
            if (!outcome.succeeded()) {
                // The callback is the game's own code; a throw here is the usual
                // cause and it is otherwise invisible on a release build.
                Log.w("UnipatchSeed", "callback threw: product=" + product +
                        " callback=" + callback.getDeclaringClass().getName() + "->" + callback.getName() +
                        " located=" + outcome.located + " started=" + outcome.started +
                        " returned=" + outcome.returned + " threw=" + outcome.threw);
                OverlayRuntimeLogger.log("WARN", "InApp", "Purchase callback did not return successfully: product=" + product);
                LAST_DELIVERED_PRODUCT = null;
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

    // ─────────────────────────────────────────────────────────────────────
    // Empty-catalog synthesis for queryProductDetailsAsync /
    // querySkuDetailsAsync. A device without a Play connection answers
    // those queries with OK and zero products; the game caches an empty
    // map and then refuses every purchase ("not available"). The injected
    // wrap*Listener entry points swap the game's listener for a proxy that
    // forwards a populated stock catalog untouched and fabricates entries
    // for the exact IDs the game requested when the callback comes back
    // empty. The extension compiles against android.jar only, so every
    // billing type resolves lazily through Class.forName. Nothing here may
    // throw: a wrap failure returns the original listener, a synthesis
    // failure forwards the original callback arguments.
    // ─────────────────────────────────────────────────────────────────────

    /** Entry point injected at the head of queryProductDetailsAsync; never throws. */
    public static Object wrapProductDetailsListener(Object params, Object listener) {
        return wrapCatalogListener(params, listener, false);
    }

    /** Entry point injected at the head of querySkuDetailsAsync; never throws. */
    public static Object wrapSkuDetailsListener(Object params, Object listener) {
        return wrapCatalogListener(params, listener, true);
    }

    private static Object wrapCatalogListener(Object params, Object listener, boolean skus) {
        if (listener == null) return null;
        try {
            List<String[]> wanted = requestedProducts(params);
            if (wanted.isEmpty()) return listener;
            Class<?>[] interfaces = listenerInterfaces(listener.getClass());
            if (interfaces.length == 0) return listener;
            ClassLoader loader = listener.getClass().getClassLoader();
            if (loader == null) loader = InAppRuntimePolicy.class.getClassLoader();
            InvocationHandler handler = skus
                    ? new SkuDetailsHandler(listener, wanted)
                    : new ProductDetailsHandler(listener, wanted);
            return Proxy.newProxyInstance(loader, interfaces, handler);
        } catch (Throwable error) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Catalog listener wrap failed: " +
                    error.getClass().getSimpleName());
            return listener;
        }
    }

    private static final class ProductDetailsHandler implements InvocationHandler {
        private final Object listener;
        private final List<String[]> wanted;

        ProductDetailsHandler(Object listener, List<String[]> wanted) {
            this.listener = listener;
            this.wanted = wanted;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                return listener.toString();
            }
            if ("onProductDetailsResponse".equals(method.getName()) && args != null && args.length == 2
                    && isEmptyProductCallback(args[1], method.getParameterTypes()[1])) {
                ArrayList<Object> built = new ArrayList<>();
                for (String[] product : wanted) {
                    Object details = newProductDetails(product[0], product[1]);
                    if (details != null) built.add(details);
                }
                Object okResult = args[0] != null ? args[0] : billingResult(0);
                Object replacement = built.isEmpty()
                        ? null : synthesizedProductResult(built, method.getParameterTypes()[1]);
                if (replacement != null && okResult != null) {
                    OverlayRuntimeLogger.log("INFO", "InApp",
                            "Empty ProductDetails response synthesized: products=" + built.size());
                    return invokeOriginal(listener, method, new Object[] { okResult, replacement });
                }
            }
            return invokeOriginal(listener, method, args);
        }
    }

    private static final class SkuDetailsHandler implements InvocationHandler {
        private final Object listener;
        private final List<String[]> wanted;

        SkuDetailsHandler(Object listener, List<String[]> wanted) {
            this.listener = listener;
            this.wanted = wanted;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                return listener.toString();
            }
            if ("onSkuDetailsResponse".equals(method.getName()) && args != null && args.length == 2
                    && (args[1] == null || (args[1] instanceof List && ((List<?>) args[1]).isEmpty()))) {
                ArrayList<Object> built = new ArrayList<>();
                for (String[] product : wanted) {
                    Object sku = newSkuDetails(product[0], product[1]);
                    if (sku != null) built.add(sku);
                }
                Object okResult = args[0] != null ? args[0] : billingResult(0);
                if (!built.isEmpty() && okResult != null) {
                    OverlayRuntimeLogger.log("INFO", "InApp",
                            "Empty SkuDetails response synthesized: skus=" + built.size());
                    return invokeOriginal(listener, method, new Object[] { okResult, built });
                }
            }
            return invokeOriginal(listener, method, args);
        }
    }

    /** True when the stock callback carried no catalog entries for this shape. */
    private static boolean isEmptyProductCallback(Object result, Class<?> expected) {
        if (expected != null && List.class.isAssignableFrom(expected)) {
            return !(result instanceof List) || ((List<?>) result).isEmpty();
        }
        if (result == null) return true;
        Method listGetter = productDetailsListGetter(result.getClass());
        if (listGetter == null) return false;
        Object value = invokeQuietly(listGetter, result);
        return !(value instanceof List) || ((List<?>) value).isEmpty();
    }

    /**
     * Resolves the stock result's product list getter: readable builds expose
     * getProductDetailsList, obfuscated builds fall back to the first non-
     * unfetched zero-arg List getter in declaration order.
     */
    private static Method productDetailsListGetter(Class<?> type) {
        Method firstCandidate = null;
        for (Method method : zeroArgMethods(type)) {
            if (!List.class.isAssignableFrom(method.getReturnType())) continue;
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (name.contains("unfetched")) continue;
            if (name.contains("productdetail")) return method;
            if (firstCandidate == null) firstCandidate = method;
        }
        return firstCandidate;
    }

    /**
     * Builds the callback's result argument: the List itself when the game
     * declared a raw List callback, otherwise QueryProductDetailsResult.
     * Constructors come before create(): obfuscation renames methods but
     * never the (List, List) constructor shape.
     */
    private static Object synthesizedProductResult(List<Object> products, Class<?> expected) {
        if (expected != null && List.class.isAssignableFrom(expected)) return new ArrayList<>(products);
        try {
            Class<?> resultClass = Class.forName("com.android.billingclient.api.QueryProductDetailsResult");
            try {
                Constructor<?> constructor = resultClass.getDeclaredConstructor(List.class, List.class);
                constructor.setAccessible(true);
                return constructor.newInstance(new ArrayList<>(products), new ArrayList<Object>());
            } catch (NoSuchMethodException ignored) { }
            try {
                Constructor<?> constructor = resultClass.getDeclaredConstructor(List.class);
                constructor.setAccessible(true);
                return constructor.newInstance(new ArrayList<>(products));
            } catch (NoSuchMethodException ignored) { }
            for (Method method : resultClass.getDeclaredMethods()) {
                if (!"create".equals(method.getName())) continue;
                Class<?>[] signature = method.getParameterTypes();
                if (signature.length == 2 && signature[0] == List.class && signature[1] == List.class) {
                    method.setAccessible(true);
                    return method.invoke(null, products, new ArrayList<Object>());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            OverlayRuntimeLogger.log("WARN", "InApp", "QueryProductDetailsResult construction failed: " +
                    error.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * ProductDetails via its JSON constructor, or null when billing is absent.
     * Billing v6+ and every R8 pass keep that constructor package private, and
     * getConstructor only reports public ones, so a wrapped build always came
     * back null here. getDeclaredConstructor sees the real signature; setAccessible
     * is the fallback for a hidden-API restriction.
     */
    private static Object newProductDetails(String productId, String productType) {
        try {
            Class<?> detailsClass = Class.forName("com.android.billingclient.api.ProductDetails");
            java.lang.reflect.Constructor<?> constructor = declaredStringConstructor(detailsClass);
            if (constructor == null) return null;
            return constructor.newInstance(productDetailsJson(productId, productType));
        } catch (ClassNotFoundException error) {
            Log.w("UnipatchSeed", "ProductDetails class absent: " + error);
            return null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                    ? error.getCause() : error;
            Log.w("UnipatchSeed", "ProductDetails ctor threw for " + productId + ": " + cause);
            return null;
        }
    }

    /** The (String) constructor whether or not billing published it. */
    private static java.lang.reflect.Constructor<?> declaredStringConstructor(Class<?> owner) {
        java.lang.reflect.Constructor<?> constructor;
        try {
            constructor = owner.getDeclaredConstructor(String.class);
        } catch (NoSuchMethodException absent) {
            Log.w("UnipatchSeed", owner.getName() + " has no (String) constructor");
            return null;
        }
        // Billing v6+ keeps it package private, so an accessible flag is the
        // difference between a synthetic catalog and a silent null.
        try {
            constructor.setAccessible(true);
        } catch (RuntimeException restricted) {
            // Hidden-API enforcement: the call still works when the platform
            // only warns, and the failure surfaces below if it truly cannot.
            Log.w("UnipatchSeed", "setAccessible refused on " + owner.getName() + ": " + restricted);
        }
        return constructor;
    }

    /** SkuDetails via its JSON constructor, or null when billing is absent. */
    private static Object newSkuDetails(String productId, String productType) {
        try {
            Class<?> skuClass = Class.forName("com.android.billingclient.api.SkuDetails");
            java.lang.reflect.Constructor<?> constructor = declaredStringConstructor(skuClass);
            if (constructor == null) return null;
            return constructor.newInstance(skuDetailsJson(productId, productType));
        } catch (ClassNotFoundException error) {
            Log.w("UnipatchSeed", "SkuDetails class absent: " + error);
            return null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                    ? error.getCause() : error;
            Log.w("UnipatchSeed", "SkuDetails ctor threw for " + productId + ": " + cause);
            return null;
        }
    }

    /**
     * Head-of-gate product seed for managed IAP wrappers. The obfuscated
     * GoogleIapManager shape keeps a private product map that only a
     * native-driven requestProductsData ever fills, and that call only happens
     * after the engine's own onSetupFinished runs. On most cold runs the engine
     * never makes it, so purchase dies at the map lookup with
     * "&lt;sku&gt;: not available" before the wrapper's connection check runs.
     * Injected ahead of that lookup with the gate's map in the receiver and the
     * requested SKU as the argument. Never touches the pending-request map the
     * same wrapper guards with isEmpty(), and never reaches native: Java-forced
     * setup ends in the engine's unguarded [this+0x18] listener dereference.
     */
    public static void seedManagedProduct(Object catalog, String productId) {
        seedManagedEntry(catalog, productId, managedProductType(productId), false);
    }

    /** Same gate on legacy wrappers whose lookup casts the result to SkuDetails. */
    public static void seedManagedSku(Object catalog, String productId) {
        seedManagedEntry(catalog, productId, managedProductType(productId), true);
    }

    /**
     * The gate hands over a bare SKU, so the type has to come from the id.
     * Building a subscription as an inapp product leaves it with no offer at
     * all and the purchase comes back ITEM_UNAVAILABLE, which is exactly what
     * a hardcoded "inapp" default produced. Ids that say nothing are treated as
     * one-off purchases, the common case.
     */
    private static String managedProductType(String productId) {
        if (productId == null) return "inapp";
        String lower = productId.toLowerCase(Locale.ROOT);
        if (lower.contains("subscription") || lower.contains("_subs") ||
                lower.startsWith("subs_") || lower.endsWith("_sub") || lower.contains(".sub.")) {
            return "subs";
        }
        return "inapp";
    }

    @SuppressWarnings("unchecked")
    private static void seedManagedEntry(Object catalog, String productId, String productType, boolean legacy) {
        // The extension's own log file is unreadable on a release-signed build,
        // and a silent no-op here looks identical to a gate that never ran, so
        // the injected call traces to logcat where the game's failure does too.
        try {
            Log.i("UnipatchSeed", "seed id=" + productId + " catalog=" +
                    (catalog == null ? "null" : catalog.getClass().getName()) +
                    " type=" + productType);
        } catch (Throwable ignored) {
        }
        if (productId == null || !(catalog instanceof Map)) {
            Log.w("UnipatchSeed", "rejected: catalog is not a Map for id=" + productId);
            return;
        }
        String id = productId.trim();
        if (id.isEmpty() || id.length() > 256) {
            Log.w("UnipatchSeed", "rejected: unusable id=" + productId);
            return;
        }
        try {
            Map<Object, Object> map = (Map<Object, Object>) catalog;
            if (map.containsKey(id)) {
                Log.i("UnipatchSeed", "already present: id=" + id);
                return;
            }
            Object details = legacy ? newSkuDetails(id, productType) : newProductDetails(id, productType);
            if (details == null) {
                Log.w("UnipatchSeed", "no factory result: id=" + id);
                OverlayRuntimeLogger.log("WARN", "InApp",
                        "Product catalog seed unavailable: id=" + id);
                return;
            }
            map.put(id, details);
            Log.i("UnipatchSeed", "seeded: id=" + id + " type=" + productType + " size=" + map.size());
            OverlayRuntimeLogger.log("INFO", "InApp",
                    "Product catalog seeded: id=" + id + " type=" + productType);
        } catch (Throwable error) {
            Log.w("UnipatchSeed", "failed: id=" + productId + " error=" + error);
            OverlayRuntimeLogger.log("WARN", "InApp",
                    "Product catalog seed failed: id=" + id +
                            " error=" + error.getClass().getSimpleName());
        }
    }

    /**
     * Pulls the requested product IDs out of QueryProductDetailsParams or
     * SkuDetailsParams: every zero-arg String getter contributes a possible
     * params-level type token, every Iterable getter contributes elements.
     * Returns deduped {id, type} pairs in request order.
     */
    private static List<String[]> requestedProducts(Object params) {
        ArrayList<String[]> wanted = new ArrayList<>();
        if (params == null) return wanted;
        String declaredType = null;
        ArrayList<Object> elements = new ArrayList<>();
        for (Method getter : zeroArgMethods(params.getClass())) {
            Object value = invokeQuietly(getter, params);
            if (value instanceof String) {
                String token = productTypeToken((String) value);
                if (token != null) declaredType = token;
            } else {
                collectElements(value, elements);
            }
        }
        if (elements.isEmpty()) {
            // R8 can rename the list accessor away entirely; the holder field
            // (billing's internal zzbt List, or a plain array) still has it.
            for (Field field : instanceFields(params.getClass())) {
                Object value = readField(field, params);
                if (value instanceof String) {
                    String token = productTypeToken((String) value);
                    if (token != null && declaredType == null) declaredType = token;
                    continue;
                }
                collectElements(value, elements);
                if (!elements.isEmpty()) break;
            }
        }
        for (Object element : elements) {
            String[] pair = classifyRequestElement(element, declaredType);
            if (pair == null) continue;
            boolean duplicate = false;
            for (String[] seen : wanted) {
                if (seen[0].equals(pair[0])) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) wanted.add(pair);
        }
        return wanted;
    }

    /** Collects non-null elements of an Iterable or array holder; anything else is ignored. */
    private static void collectElements(Object value, List<Object> sink) {
        if (value instanceof Iterable<?>) {
            for (Object element : (Iterable<?>) value) {
                if (element != null) sink.add(element);
            }
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object element = java.lang.reflect.Array.get(value, i);
                if (element != null) sink.add(element);
            }
        }
    }

    /** Declared non-static fields along the class chain, dex declaration order, accessible. */
    private static List<Field> instanceFields(Class<?> type) {
        ArrayList<Field> found = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Field[] declared;
            try {
                declared = current.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : declared) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    // still worth attempting the read; failure returns null
                }
                found.add(field);
            }
        }
        return found;
    }

    private static Object readField(Field field, Object target) {
        try {
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    /**
     * Classifies one requested element (String id or Product/SkuDetails
     * object) into {id, type}. Named getters win over positional ones so
     * obfuscated zz* fields only matter when no readable name exists.
     */
    private static String[] classifyRequestElement(Object element, String fallbackType) {
        if (element instanceof String) {
            String id = normalize((String) element);
            if (fallbackType == null || !valid(id) || id.indexOf(' ') >= 0) return null;
            return new String[] { id, fallbackType };
        }
        if (element == null) return null;
        String type = null;
        String namedId = null;
        String plainId = null;
        for (Method getter : zeroArgMethods(element.getClass())) {
            if (getter.getReturnType() != String.class) continue;
            String name = getter.getName().toLowerCase(Locale.ROOT);
            if (name.equals("tostring")) continue;
            Object value = invokeQuietly(getter, element);
            if (!(value instanceof String)) continue;
            String text = ((String) value).trim();
            if (text.isEmpty()) continue;
            String token = productTypeToken(text);
            if (token != null) {
                type = token;
                continue;
            }
            if (!valid(text) || text.indexOf(' ') >= 0) continue;
            if (name.contains("productid") || name.contains("sku")) {
                if (namedId == null) namedId = text;
            } else if (plainId == null) {
                plainId = text;
            }
        }
        if ((namedId == null && plainId == null) || type == null) {
            // Production R8 strips QueryProductDetailsParams$Product down to
            // two private String fields (productId, productType) with no
            // accessors at all; classify the field values the same way, so
            // the request IDs are still reachable. Values decide, names only
            // disambiguate: the type token identifies itself.
            for (Field field : instanceFields(element.getClass())) {
                if (field.getType() != String.class) continue;
                Object value = readField(field, element);
                if (!(value instanceof String)) continue;
                String text = ((String) value).trim();
                if (text.isEmpty()) continue;
                String token = productTypeToken(text);
                if (token != null) {
                    type = token;
                    continue;
                }
                if (!valid(text) || text.indexOf(' ') >= 0) continue;
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (name.contains("productid") || name.contains("sku")) {
                    if (namedId == null) namedId = text;
                } else if (plainId == null) {
                    plainId = text;
                }
            }
        }
        String id = namedId != null ? namedId : plainId;
        if (id == null) return null;
        if (type == null) type = fallbackType;
        if (type == null) return null;
        return new String[] { id, type };
    }

    /** Interface closure of the listener, breadth-first, capped for safety. */
    private static Class<?>[] listenerInterfaces(Class<?> listenerType) {
        ArrayList<Class<?>> found = new ArrayList<>();
        collectInterfaces(listenerType, found, 0);
        return found.toArray(new Class<?>[0]);
    }

    private static void collectInterfaces(Class<?> type, List<Class<?>> found, int depth) {
        if (type == null || type == Object.class || depth > 8 || found.size() >= 16) return;
        for (Class<?> candidate : type.getInterfaces()) {
            if (found.contains(candidate)) continue;
            found.add(candidate);
            collectInterfaces(candidate, found, depth + 1);
        }
        collectInterfaces(type.getSuperclass(), found, depth + 1);
    }

    /** Forwards to the game's listener; a proxy-side failure must not crash the callback. */
    private static Object invokeOriginal(Object listener, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(listener, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw cause != null ? cause : error;
        } catch (ReflectiveOperationException | RuntimeException error) {
            OverlayRuntimeLogger.log("WARN", "InApp", "Proxy could not reach original listener: " +
                    error.getClass().getSimpleName());
            return null;
        }
    }

    private static Object invokeQuietly(Method getter, Object target) {
        try {
            return getter.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    /** Declared zero-arg instance getters along the class chain, Object excluded. */
    private static List<Method> zeroArgMethods(Class<?> type) {
        ArrayList<Method> found = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Method[] declared;
            try {
                declared = current.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : declared) {
                if (method.getParameterTypes().length != 0) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (method.isSynthetic() || method.isBridge()) continue;
                if (method.getReturnType() == void.class) continue;
                try { method.setAccessible(true); } catch (Throwable ignored) { }
                found.add(method);
            }
        }
        return found;
    }

    /** Normalizes a product type token; play pass subs map onto subs. */
    private static String productTypeToken(String raw) {
        if (raw == null) return null;
        String token = raw.trim().toLowerCase(Locale.ROOT);
        if ("inapp".equals(token) || "subs".equals(token)) return token;
        if ("play_pass_subs".equals(token)) return "subs";
        return null;
    }

    /**
     * ProductDetails JSON for the ProductDetails(String) constructor.
     * Subs carry a full pricing phase so getBaseOffer sees a buyable offer;
     * inapp entries expose both the object and the list form modern builds
     * read. Prices read 0.00 because the grant is emulated at buy time.
     */
    public static String productDetailsJson(String productId, String productType) {
        String rawId = productId == null ? "" : productId.trim();
        if (rawId.length() > 256) rawId = rawId.substring(0, 256);
        String type = productTypeToken(productType);
        if (type == null) type = "inapp";
        String id = PurchaseJson.escape(rawId);
        StringBuilder json = new StringBuilder(rawId.length() + 384);
        json.append("{\"productId\":\"").append(id)
            .append("\",\"type\":\"").append(type)
            .append("\",\"title\":\"").append(id)
            .append("\",\"name\":\"").append(id)
            .append("\",\"description\":\"\",")
            .append("\"packageDisplayName\":\"").append(id).append('"');
        if ("subs".equals(type)) {
            String token = PurchaseJson.escape(
                    ("unipatch." + rawId).substring(0, Math.min(("unipatch." + rawId).length(), 256)));
            json.append(",\"subscriptionOfferDetails\":[{\"basePlanId\":\"base\",\"offerId\":\"\",\"offerIdToken\":\"")
                .append(token)
                .append("\",\"pricingPhases\":[{\"billingPeriod\":\"P1M\",\"priceCurrencyCode\":\"USD\",")
                .append("\"formattedPrice\":\"0.00\",\"priceAmountMicros\":0,\"recurrenceMode\":1,\"billingCycleCount\":0}],")
                .append("\"offerTags\":[]}]");
        } else {
            json.append(",\"oneTimePurchaseOfferDetails\":{\"formattedPrice\":\"0.00\",\"priceAmountMicros\":0,")
                .append("\"priceCurrencyCode\":\"USD\"}")
                .append(",\"oneTimePurchaseOfferDetailsList\":[{\"formattedPrice\":\"0.00\",\"priceAmountMicros\":0,")
                .append("\"priceCurrencyCode\":\"USD\"}]");
        }
        return json.append('}').toString();
    }

    /** SkuDetails JSON for the SkuDetails(String) constructor, snake_case keys. */
    public static String skuDetailsJson(String productId, String productType) {
        String rawId = productId == null ? "" : productId.trim();
        if (rawId.length() > 256) rawId = rawId.substring(0, 256);
        String type = productTypeToken(productType);
        if (type == null) type = "inapp";
        String id = PurchaseJson.escape(rawId);
        StringBuilder json = new StringBuilder(rawId.length() + 320);
        json.append("{\"productId\":\"").append(id)
            .append("\",\"type\":\"").append(type)
            .append("\",\"title\":\"").append(id)
            .append("\",\"description\":\"").append(id)
            .append("\",\"price\":\"0.00\",\"price_amount_micros\":0")
            .append(",\"price_currency_code\":\"USD\",\"original_price\":\"0.00\"")
            .append(",\"original_price_amount_micros\":0,\"skuDetailsToken\":\"\"");
        if ("subs".equals(type)) json.append(",\"subscriptionPeriod\":\"P1M\"");
        return json.append('}').toString();
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
