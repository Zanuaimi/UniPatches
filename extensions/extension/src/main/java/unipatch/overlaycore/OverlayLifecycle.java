package unipatch.overlaycore;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import unipatch.overlaycore.modules.advanced.OverlayRuntimeLogger;

/** Process lifecycle adapter that delegates Activity ownership to the runtime entry point. */
final class OverlayLifecycle implements Application.ActivityLifecycleCallbacks {
    @Override public void onActivityCreated(Activity activity, Bundle state) {
        OverlayRuntimeLogger.log("INFO", "Lifecycle", "Activity created: " + activity.getClass().getName());
    }
    @Override public void onActivityStarted(Activity activity) {
        OverlayRuntimeLogger.log("INFO", "Lifecycle", "Activity started: " + activity.getClass().getName());
    }
    @Override public void onActivityResumed(Activity activity) {
        OverlayRuntimeLogger.log("INFO", "Lifecycle", "Activity resumed: " + activity.getClass().getName());
        OverlayRuntime.showActivity(activity);
    }
    @Override public void onActivityPaused(Activity activity) {
        OverlayRuntimeLogger.log("INFO", "Lifecycle", "Activity paused: " + activity.getClass().getName());
        OverlayRuntime.pauseActivity(activity);
    }
    @Override public void onActivityStopped(Activity activity) {
        OverlayRuntimeLogger.log("INFO", "Lifecycle", "Activity stopped: " + activity.getClass().getName());
        OverlayRuntime.pauseActivity(activity);
    }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        OverlayRuntimeLogger.log("INFO", "Lifecycle", "Activity state saved: " + activity.getClass().getName());
    }
    @Override public void onActivityDestroyed(Activity activity) {
        OverlayRuntimeLogger.log("INFO", "Lifecycle", "Activity destroyed: " + activity.getClass().getName());
        OverlayRuntime.removeActivity(activity);
    }
}
