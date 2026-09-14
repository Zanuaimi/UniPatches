package unipatch.overlaycore.modules;

import android.app.Activity;

/** Activity-scoped context exposed only to System Modules. */
public final class SystemModuleContext {
    private final Activity activity;

    public SystemModuleContext(Activity activity) { this.activity = activity; }
    public Activity activity() { return activity; }
}
