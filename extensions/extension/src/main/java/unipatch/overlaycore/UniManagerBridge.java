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

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Optional, reflection-free Binder client for the independently installed UniManager app. */
public final class UniManagerBridge {
    public static final int PROTOCOL_VERSION = 1;
    public static final String ACTION_BRIDGE = "com.zanuaimi.unimanager.BRIDGE";
    private static final String MANAGER_PACKAGE = "com.zanuaimi.unimanager";
    private static final String MANAGER_SERVICE = "com.zanuaimi.unimanager.bridge.BridgeService";
    private static final int TRANSACTION_READ = 2;
    private static final int TRANSACTION_UPDATE = 3;
    // Keep requests below Android Binder's practical transaction limit. Overlay images are
    // embedded in the APK and are intentionally not sent through this bridge.
    private static final int MAX_PAYLOAD_LENGTH = 512 * 1024;
    // Keep bridge operations ordered so rapid runtime changes cannot persist out of order.
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();

    private UniManagerBridge() { }

    public interface Callback { void onConfiguration(String json); }

    /** Initializes the local fallback first, then asynchronously reads manager overrides. */
    public static void initialize(final Context context, final String fallbackPolicy) {
        AdsRuntimePolicy.configure(fallbackPolicy);
        read(context, "{}", AdsRuntimePolicy::applyManagedConfiguration);
    }

    /** Reads the existing manager record without rewriting patch-time registration data. */
    public static void read(final Context context, final String fallback, final Callback callback) {
        if (context == null || callback == null) return;
        String payload = "{\"package_name\":\"" + context.getPackageName() + "\"}";
        request(context, TRANSACTION_READ, payload, fallback, callback);
    }

    private static void request(final Context context, final int transaction,
                                final String payload, final String fallback,
                                final Callback callback) {
        EXECUTOR.execute(() -> {
            final long deadline = SystemClock.uptimeMillis() + 1200L;
            try {
                Intent query = new Intent(ACTION_BRIDGE).setPackage(MANAGER_PACKAGE);
                List<ResolveInfo> services = context.getPackageManager().queryIntentServices(query, 0);
                ComponentName component = null;
                if (services != null && !services.isEmpty()) {
                    ResolveInfo info = services.get(0);
                    if (info.serviceInfo != null && MANAGER_PACKAGE.equals(info.serviceInfo.packageName) &&
                            "com.zanuaimi.unimanager.permission.BRIDGE".equals(info.serviceInfo.permission)) {
                        component = new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
                    }
                }
                // Explicit binding is also attempted as a package-visibility-safe fallback. This keeps
                // bridge reads working for APKs patched before the manager package was declared in queries.
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
                    callback.onConfiguration(fallback); return;
                }
                try {
                    synchronized (lock) {
                        while (result[0] == null && SystemClock.uptimeMillis() < deadline) {
                            lock.wait(Math.max(1L, deadline - SystemClock.uptimeMillis()));
                        }
                    }
                    if (result[0] == null) { callback.onConfiguration(fallback); return; }
                    String configuration = transact(result[0], transaction, payload);
                    callback.onConfiguration(configuration == null ? fallback : configuration);
                } finally {
                    context.unbindService(connection);
                }
            } catch (Exception ignored) {
                callback.onConfiguration(fallback);
            }
        });
    }

    public static void update(final Context context, final String packageName, final String json) {
        if (context == null || json == null) return;
        String payload = "{\"package_name\":\"" + packageName +
                "\",\"configuration\":" + json + "}";
        request(context, TRANSACTION_UPDATE, payload, "{}", ignored -> { });
    }

    private static String transact(IBinder binder, int code, String payload) throws RemoteException {
        if (payload != null && payload.length() > MAX_PAYLOAD_LENGTH) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(PROTOCOL_VERSION);
            data.writeString(payload == null ? "" : payload);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally { data.recycle(); reply.recycle(); }
    }
}
