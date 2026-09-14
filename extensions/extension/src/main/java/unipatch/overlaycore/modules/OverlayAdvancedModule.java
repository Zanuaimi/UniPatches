package unipatch.overlaycore.modules;

import android.app.Activity;

/**
 * Base boundary for optional diagnostic and advanced runtime features.
 * Resource ownership and failures remain local to the Advanced Module.
 */
public abstract class OverlayAdvancedModule implements OverlayModule {
    protected OverlayAdvancedModule() { }

    @Override public final String category() { return "advanced"; }

    public final boolean startSafely(Activity activity) {
        try { return onAdvancedModuleStart(new AdvancedModuleContext(activity)); }
        catch (RuntimeException ignored) { return false; }
    }

    public final void stopSafely() {
        try { onAdvancedModuleStop(); }
        catch (RuntimeException ignored) { }
    }

    protected abstract boolean onAdvancedModuleStart(AdvancedModuleContext context);
    public abstract void onAdvancedModuleStop();
}
