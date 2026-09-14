package unipatch.overlaycore.modules;

import android.app.Activity;

/**
 * Base boundary for modules that control an Android system capability.
 * System state and permission failures stay inside the module.
 */
public abstract class OverlaySystemModule implements OverlayModule {
    protected OverlaySystemModule() { }

    @Override public final String category() { return "system"; }

    public final boolean initiallyEnabled(Activity activity) {
        try { return readInitialState(activity); }
        catch (RuntimeException ignored) { return false; }
    }

    public final boolean setEnabled(Activity activity, boolean enabled) {
        try {
            return enabled ? enable(activity) : disable(activity);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public final void stopSafely() {
        try { onSystemModuleStop(); }
        catch (RuntimeException ignored) { }
    }

    protected abstract boolean readInitialState(Activity activity);
    protected abstract boolean enable(Activity activity);
    protected abstract boolean disable(Activity activity);
    public final boolean startSafely(Activity activity) {
        try {
            onSystemModuleStart(new SystemModuleContext(activity));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    protected abstract void onSystemModuleStart(SystemModuleContext context);
    public abstract void onSystemModuleStop();
}
