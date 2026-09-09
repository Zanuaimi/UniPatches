package unipatch.overlaycore.modules;

import android.app.Activity;

/**
 * Optional app-specific module with a session value, settings action, and one-shot action.
 * Implementations must keep these actions reversible and scoped to their target app.
 */
public abstract class OverlayActionModule extends OverlayAppSpecificModule {
    protected OverlayActionModule() { }

    public String valueText() { return ""; }
    public String actionLabel() { return "Run once"; }
    public boolean hasSettings() { return false; }
    public boolean hasEnableToggle() { return true; }

    public void showSettings(Activity activity, int background, int textColor, int outline, int accent) { }

    /** Returns false when the action could not be completed. */
    public boolean performAction(Activity activity) { return true; }
}
