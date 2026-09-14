package unipatch.overlaycore.modules;

import android.app.Activity;

/** Activity-scoped context exposed only to Advanced Modules. */
public final class AdvancedModuleContext {
    private final Activity activity;

    public AdvancedModuleContext(Activity activity) { this.activity = activity; }
    public Activity activity() { return activity; }
}
