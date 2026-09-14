package unipatch.overlaycore.modules.system;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.provider.Settings;

import unipatch.overlaycore.modules.OverlaySystemModule;
import unipatch.overlaycore.modules.SystemModuleContext;
import unipatch.overlaycore.modules.advanced.OverlayRuntimeLogger;

/** System Module that controls DND only when Android grants notification-policy access. */
public final class DoNotDisturbModule extends OverlaySystemModule {
    public interface StateListener {
        void onStateChanged(boolean enabled);
    }

    private static final String KEY = "doNotDisturb";
    private NotificationManager notificationManager;
    private int initialFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    private boolean initialStateCaptured;
    private boolean initialState;
    private boolean changedByModule;
    private int ownedFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    private Activity registeredActivity;
    private BroadcastReceiver stateReceiver;
    private StateListener stateListener;
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private final Runnable statePoll = this::pollState;

    @Override public String key() { return KEY; }
    @Override public String label() { return "Do Not Disturb"; }
    @Override public String description() {
        return "Control Do Not Disturb when notification-policy access is granted.";
    }

    @Override protected boolean readInitialState(Activity activity) {
        notificationManager = manager(activity);
        if (notificationManager == null || Build.VERSION.SDK_INT < 23) return false;
        if (!initialStateCaptured) {
            initialFilter = notificationManager.getCurrentInterruptionFilter();
            initialState = isDndFilter(initialFilter);
            initialStateCaptured = true;
        }
        return initialState;
    }

    @Override protected boolean enable(Activity activity) {
        if (Build.VERSION.SDK_INT < 23) return false;
        NotificationManager manager = manager(activity);
        if (manager == null || !hasPolicyAccess(manager)) return false;
        try {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            changedByModule = true;
            ownedFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY;
            OverlayRuntimeLogger.log("INFO", label(), "Do Not Disturb enabled");
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    @Override protected boolean disable(Activity activity) {
        if (Build.VERSION.SDK_INT < 23) return false;
        NotificationManager manager = manager(activity);
        if (manager == null || !hasPolicyAccess(manager)) return false;
        try {
            if (changedByModule && manager.getCurrentInterruptionFilter() == ownedFilter) {
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            }
            changedByModule = false;
            ownedFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
            OverlayRuntimeLogger.log("INFO", label(), "Do Not Disturb disabled");
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    @Override protected void onSystemModuleStart(SystemModuleContext context) {
        Activity activity = context.activity();
        notificationManager = manager(activity);
        if (notificationManager != null && Build.VERSION.SDK_INT >= 23) {
            initialFilter = notificationManager.getCurrentInterruptionFilter();
            initialState = isDndFilter(initialFilter);
            initialStateCaptured = true;
        }
        registeredActivity = activity;
        if (activity != null && Build.VERSION.SDK_INT >= 23) {
            stateReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context receiverContext, Intent intent) { pollState(); }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED);
            filter.addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED);
            try { activity.registerReceiver(stateReceiver, filter); }
            catch (RuntimeException ignored) { stateReceiver = null; }
            stateHandler.postDelayed(statePoll, 5000L);
        }
    }

    @Override public void onSystemModuleStop() {
        // Do not overwrite external user changes. Only module-owned state is reversible here.
        notificationManager = null;
        initialStateCaptured = false;
        initialState = false;
        changedByModule = false;
        ownedFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        stateHandler.removeCallbacks(statePoll);
        stateListener = null;
        if (registeredActivity != null && stateReceiver != null) {
            try { registeredActivity.unregisterReceiver(stateReceiver); }
            catch (RuntimeException ignored) { }
        }
        registeredActivity = null;
        stateReceiver = null;
    }

    public boolean hasAccess(Activity activity) {
        NotificationManager manager = manager(activity);
        return manager != null && Build.VERSION.SDK_INT >= 23 && hasPolicyAccess(manager);
    }

    public void setStateListener(StateListener listener) {
        stateListener = listener;
    }

    public void openAccessSettings(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 23) return;
        try {
            activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        } catch (RuntimeException ignored) { }
    }

    private static NotificationManager manager(Activity activity) {
        if (activity == null) return null;
        return (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static boolean hasPolicyAccess(NotificationManager manager) {
        try { return manager.isNotificationPolicyAccessGranted(); }
        catch (SecurityException ignored) { return false; }
    }

    private static boolean isDndFilter(int filter) {
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL
                && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    }

    private void pollState() {
        if (registeredActivity == null || Build.VERSION.SDK_INT < 23) return;
        notificationManager = manager(registeredActivity);
        if (notificationManager == null) return;
        try {
            int filter = notificationManager.getCurrentInterruptionFilter();
            StateListener listener = stateListener;
            if (listener != null) {
                listener.onStateChanged(isDndFilter(filter));
            }
            stateHandler.postDelayed(statePoll, 5000L);
        } catch (RuntimeException ignored) { }
    }
}
