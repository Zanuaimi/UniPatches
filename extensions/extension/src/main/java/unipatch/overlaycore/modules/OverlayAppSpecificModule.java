package unipatch.overlaycore.modules;

import android.app.Activity;

/**
 * Base contract for modules that target one known application's classes or behavior.
 *
 * App-specific modules use the shared overlay UI but have their own guarded lifecycle and state
 * boundary. They are kept separate from universal Activity, statistic, and hook modules so a
 * target-specific failure cannot silently change a general-purpose module's behavior.
 */
public abstract class OverlayAppSpecificModule implements OverlayModule {
    protected OverlayAppSpecificModule() { }

    /** Target-specific modules may reject an Activity that is not the intended app screen. */
    public boolean supports(Activity activity) { return activity != null; }

    public final boolean initiallyEnabled(Activity activity, int originalWindowFlags, int originalSystemUi) {
        try { return readEnabled(activity, originalWindowFlags, originalSystemUi); }
        catch (RuntimeException ignored) { return false; }
    }

    public final boolean setEnabled(Activity activity, boolean enabled, int originalWindowFlags, int originalSystemUi) {
        try {
            if (enabled) applyEnabled(activity, originalWindowFlags, originalSystemUi);
            else restoreOriginal(activity, originalWindowFlags, originalSystemUi);
            return true;
        } catch (RuntimeException ignored) {
            if (enabled) {
                try { restoreOriginal(activity, originalWindowFlags, originalSystemUi); }
                catch (RuntimeException ignoredRestore) { }
            }
            return false;
        }
    }

    public final boolean restore(Activity activity, int originalWindowFlags, int originalSystemUi) {
        try {
            restoreOriginal(activity, originalWindowFlags, originalSystemUi);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    protected abstract boolean readEnabled(Activity activity, int originalWindowFlags, int originalSystemUi);
    protected abstract void applyEnabled(Activity activity, int originalWindowFlags, int originalSystemUi);
    protected abstract void restoreOriginal(Activity activity, int originalWindowFlags, int originalSystemUi);
}
