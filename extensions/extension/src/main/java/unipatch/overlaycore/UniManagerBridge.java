package unipatch.overlaycore;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Optional, reflection-free Binder client for the independently installed UniManager app. */
public final class UniManagerBridge {
    public static final int LEGACY_PROTOCOL_VERSION = 1;
    public static final int PROTOCOL_VERSION = 2;
    public static final String ACTION_BRIDGE = "com.zanuaimi.unimanager.BRIDGE";
    private static final String TAG = "UniManagerBridge";
    private static final String MANAGER_PACKAGE = "com.zanuaimi.unimanager";
    private static final String MANAGER_SERVICE = "com.zanuaimi.unimanager.bridge.BridgeService";
    private static final int TRANSACTION_READ = 2;
    private static final int TRANSACTION_UPDATE = 3;
    private static final int TRANSACTION_PING = 4;
    private static final long INITIAL_TIMEOUT_MILLIS = 5_000L;
    private static final long RETRY_TIMEOUT_MILLIS = 2_000L;
    private static final int MAX_PAYLOAD_LENGTH = 512 * 1024;
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();

    private UniManagerBridge() { }

    public interface Callback {
        void onConfiguration(String json);
        default void onBridgeStatus(String status, String reason) { }
    }

    private interface RawCallback { void onResponse(String response, boolean transportFallback); }

    public static void initialize(final Context context, final String fallbackPolicy) {
        AdsRuntimePolicy.configure(fallbackPolicy);
        read(context, "{}", AdsRuntimePolicy::applyManagedConfiguration);
    }

    /** Pings UniManager, then reads the registry without rewriting patch-time registration data. */
    public static void read(final Context context, final String fallback, final Callback callback) {
        if (context == null || callback == null) return;
        final String payload = "{\"package_name\":\"" + context.getPackageName() + "\"}";
        request(context, TRANSACTION_PING, payload, "", true, PROTOCOL_VERSION, (ping, transportFallback) -> {
            BridgeResult pingResult = parseResult(ping);
            if (!transportFallback && pingResult != null && "unsupported_protocol".equals(pingResult.status)) {
                request(context, TRANSACTION_READ, payload, fallback, true, LEGACY_PROTOCOL_VERSION,
                        (legacy, legacyTransportFallback) -> deliverConfiguration(legacy, legacyTransportFallback, fallback, callback));
                return;
            }
            if (transportFallback || pingResult == null || !"ok".equals(pingResult.status)) {
                String status = pingResult == null ? "unavailable" : pingResult.status;
                String reason = pingResult == null ? "UniManager did not answer the ping request." : pingResult.reason;
                Log.w(TAG, "read fallback status=" + status + " reason=" + reason);
                callback.onBridgeStatus(status, reason);
                callback.onConfiguration(fallback);
                return;
            }
            request(context, TRANSACTION_READ, payload, fallback, true, PROTOCOL_VERSION,
                    (response, readTransportFallback) -> deliverConfiguration(response, readTransportFallback, fallback, callback));
        });
    }

    private static void deliverConfiguration(String response, boolean transportFallback,
                                              String fallback, Callback callback) {
        if (transportFallback) {
            callback.onBridgeStatus("unavailable", "UniManager bridge transport failed.");
            callback.onConfiguration(fallback);
            return;
        }
        BridgeResult result = parseResult(response);
        if (result == null) {
            if (response != null && response.trim().startsWith("{")) {
                callback.onBridgeStatus("ok", "Legacy raw configuration received.");
                callback.onConfiguration(response);
                return;
            }
            callback.onBridgeStatus("unavailable", "UniManager returned an invalid bridge response.");
            callback.onConfiguration(fallback);
            return;
        }
        if ("ok".equals(result.status) && result.configuration != null) {
            callback.onBridgeStatus(result.status, "Managed configuration received.");
            callback.onConfiguration(result.configuration.toString());
        } else {
            Log.w(TAG, "managed read rejected status=" + result.status + " reason=" + result.reason);
            callback.onBridgeStatus(result.status, result.reason);
            callback.onConfiguration(fallback);
        }
    }

    public static void update(final Context context, final String packageName, final String json) {
        if (context == null || json == null) return;
        final String payload = "{\"package_name\":\"" + packageName +
                "\",\"configuration\":" + json + "}";
        request(context, TRANSACTION_UPDATE, payload, "", false, PROTOCOL_VERSION, (response, ignored) -> {
            if (ignored) {
                Log.w(TAG, "managed update transport failed for package=" + packageName);
                return;
            }
            BridgeResult result = parseResult(response);
            if (result != null && "unsupported_protocol".equals(result.status)) {
                request(context, TRANSACTION_UPDATE, payload, "", false, LEGACY_PROTOCOL_VERSION,
                        (legacy, ignoredLegacy) -> Log.d(TAG, "legacy update response=" + legacy));
            } else if (result != null && !"ok".equals(result.status)) {
                Log.w(TAG, "managed update rejected status=" + result.status + " reason=" + result.reason);
            }
        });
    }

    private static void request(final Context context, final int transaction,
                                final String payload, final String fallback,
                                final boolean retryTransportFailure, final int protocolVersion,
                                final RawCallback callback) {
        request(context, transaction, payload, fallback, retryTransportFailure, protocolVersion,
                INITIAL_TIMEOUT_MILLIS, callback);
    }

    private static void request(final Context context, final int transaction,
                                final String payload, final String fallback,
                                final boolean retryTransportFailure, final int protocolVersion,
                                final long timeoutMillis, final RawCallback callback) {
        EXECUTOR.execute(() -> {
            final long deadline = SystemClock.uptimeMillis() + timeoutMillis;
            try {
                Intent query = new Intent(ACTION_BRIDGE).setPackage(MANAGER_PACKAGE);
                List<ResolveInfo> services = context.getPackageManager().queryIntentServices(query, 0);
                ComponentName component = null;
                if (services != null && !services.isEmpty()) {
                    ResolveInfo info = services.get(0);
                    if (info.serviceInfo != null && MANAGER_PACKAGE.equals(info.serviceInfo.packageName)) {
                        component = new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
                    }
                }
                if (component == null) component = new ComponentName(MANAGER_PACKAGE, MANAGER_SERVICE);
                Intent explicit = new Intent(query).setComponent(component);
                final Object lock = new Object();
                final IBinder[] result = new IBinder[1];
                ServiceConnection connection = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder service) {
                        synchronized (lock) { result[0] = service; lock.notifyAll(); }
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        synchronized (lock) { lock.notifyAll(); }
                    }
                };
                if (!context.bindService(explicit, connection, Context.BIND_AUTO_CREATE)) {
                    retryOrFallback(context, transaction, payload, fallback, retryTransportFailure, protocolVersion, callback);
                    return;
                }
                try {
                    synchronized (lock) {
                        while (result[0] == null && SystemClock.uptimeMillis() < deadline) {
                            lock.wait(Math.max(1L, deadline - SystemClock.uptimeMillis()));
                        }
                    }
                    if (result[0] == null) {
                        retryOrFallback(context, transaction, payload, fallback, retryTransportFailure, protocolVersion, callback);
                        return;
                    }
                    String response = transact(result[0], transaction, payload, protocolVersion);
                    callback.onResponse(response == null ? fallback : response, response == null);
                } finally {
                    context.unbindService(connection);
                }
            } catch (Exception error) {
                Log.w(TAG, "transport failure operation=" + transaction + " protocol=" + protocolVersion, error);
                retryOrFallback(context, transaction, payload, fallback, retryTransportFailure, protocolVersion, callback);
            }
        });
    }

    private static void retryOrFallback(final Context context, final int transaction,
                                        final String payload, final String fallback,
                                        final boolean retryTransportFailure, final int protocolVersion,
                                        final RawCallback callback) {
        if ((transaction == TRANSACTION_PING || transaction == TRANSACTION_READ) && retryTransportFailure) {
            EXECUTOR.execute(() -> {
                SystemClock.sleep(250L);
                request(context, transaction, payload, fallback, false, protocolVersion,
                        RETRY_TIMEOUT_MILLIS, callback);
            });
        } else {
            callback.onResponse(fallback, true);
        }
    }

    private static String transact(IBinder binder, int code, String payload, int protocolVersion) throws RemoteException {
        if (payload != null && payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_LENGTH) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(protocolVersion);
            data.writeString(payload == null ? "" : payload);
            binder.transact(code, data, reply, 0);
            reply.readException();
            String response = reply.readString();
            if (response != null && response.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_LENGTH) return null;
            return response;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static BridgeResult parseResult(String response) {
        if (response == null || response.trim().isEmpty()) return null;
        try {
            JSONObject object = new JSONObject(response);
            if (!object.has("status")) return null;
            return new BridgeResult(object.optString("status"), object.optString("fallback_reason"), object.optJSONObject("configuration"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class BridgeResult {
        final String status;
        final String reason;
        final JSONObject configuration;

        BridgeResult(String status, String reason, JSONObject configuration) {
            this.status = status;
            this.reason = reason;
            this.configuration = configuration;
        }
    }
}
