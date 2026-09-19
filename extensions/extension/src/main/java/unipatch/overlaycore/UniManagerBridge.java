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
    private static final int TRANSACTION_REGISTER = 1;
    private static final int TRANSACTION_READ = 2;
    private static final int TRANSACTION_UPDATE = 3;
    private static final Executor EXECUTOR = Executors.newCachedThreadPool();

    private UniManagerBridge() { }

    public interface Callback { void onConfiguration(String json); }

    /** Initializes the local fallback first, then asynchronously applies manager overrides. */
    public static void initialize(final Context context, final String fallbackPolicy) {
        AdsRuntimePolicy.configure(fallbackPolicy);
        String capabilities = AdsRuntimePolicy.hasAnyModule()
                ? "[\"block_ads.v1\",\"ads_free_rewards.v1\"]" : "[]";
        String label = String.valueOf(context.getApplicationInfo().loadLabel(context.getPackageManager()))
                .replace("\\", "\\\\").replace("\"", "\\\"");
        String registration = "{\"package_name\":\"" + context.getPackageName() +
                "\",\"app_label\":\"" + label + "\",\"protocol_version\":1,\"source_version\":\"unipatches-dev\"" +
                ",\"patches\":[{\"id\":\"control-app-ads\",\"version\":\"1\"}]" +
                ",\"capabilities\":" + capabilities +
                ",\"configuration\":" + AdsRuntimePolicy.managerConfigurationJson() + "}";
        registerAndRead(context, registration, "{}", AdsRuntimePolicy::applyManagedConfiguration);
    }

    public static void registerAndRead(final Context context, final String registration,
                                       final String fallback, final Callback callback) {
        if (context == null || callback == null) return;
        request(context, TRANSACTION_REGISTER, registration, fallback, callback);
    }

    private static void request(final Context context, final int transaction,
                                final String payload, final String fallback,
                                final Callback callback) {
        EXECUTOR.execute(() -> {
            final long deadline = SystemClock.uptimeMillis() + 1200L;
            try {
                Intent query = new Intent(ACTION_BRIDGE).setPackage(MANAGER_PACKAGE);
                List<ResolveInfo> services = context.getPackageManager().queryIntentServices(query, 0);
                if (services == null || services.isEmpty()) { callback.onConfiguration(fallback); return; }
                ResolveInfo info = services.get(0);
                if (info.serviceInfo == null || !MANAGER_PACKAGE.equals(info.serviceInfo.packageName) ||
                        !"com.zanuaimi.unimanager.permission.BRIDGE".equals(info.serviceInfo.permission)) {
                    callback.onConfiguration(fallback); return;
                }
                Intent explicit = new Intent(query).setComponent(new ComponentName(
                        info.serviceInfo.packageName, info.serviceInfo.name));
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
