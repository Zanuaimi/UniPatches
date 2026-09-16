package unipatch.overlaycore.modules.iap;

import android.app.Activity;
import java.util.Arrays;
import java.util.List;
import unipatch.overlaycore.InAppRuntimePolicy;
import unipatch.overlaycore.modules.OverlayActionModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModuleProvider;

/** Universal runtime module for optional InApp Emulation purchase controls. */
public final class InAppEmulationRuntimeProvider implements OverlayAppSpecificModuleProvider {
    public static final String PROFILE_ID = "inAppEmulation";

    @Override public String profileId() { return PROFILE_ID; }

    @Override public List<OverlayAppSpecificModule> create(Activity activity) {
        if (!InAppRuntimePolicy.isConfigured() || activity == null) return java.util.Collections.emptyList();
        InAppRuntimePolicy.registerActivity(activity);
        return Arrays.asList(new Module());
    }

    private static final class Module extends OverlayActionModule {
        @Override public String key() { return PROFILE_ID; }
        @Override public String label() { return "InApp Emulation"; }
        @Override public String description() { return "Control purchase confirmation popups and manage saved products for this app session. Credits to Nai64 for the original IAP patch functionality; enhancement inspiration from MiguelNinja19's billing patches."; }
        @Override public boolean hasSettings() { return true; }
        @Override public boolean hasActionButton() { return false; }
        @Override protected boolean readEnabled(Activity a, int f, int u) { return InAppRuntimePolicy.popupEnabled(); }
        @Override protected void applyEnabled(Activity a, int f, int u) { InAppRuntimePolicy.setPopupEnabled(true); }
        @Override protected void restoreOriginal(Activity a, int f, int u) { InAppRuntimePolicy.setPopupEnabled(false); }
        @Override public String settingsTitle() { return "InApp Emulation settings"; }
        @Override public String[] settingsChoices() { return InAppRuntimePolicy.savedPurchases(); }
        @Override public boolean[] settingsValues() {
            String[] values = settingsChoices();
            boolean[] result = new boolean[values.length];
            Arrays.fill(result, true);
            return result;
        }
        @Override public void applySettings(boolean[] values) {
            InAppRuntimePolicy.removeUnsaved(values, settingsChoices());
        }
        @Override public String valueText() {
            return "Popups: " + (InAppRuntimePolicy.popupEnabled() ? "enabled" : "disabled") + ", saved products: " + InAppRuntimePolicy.savedPurchases().length;
        }
        @Override public boolean supports(Activity activity) { return activity != null; }
    }
}
