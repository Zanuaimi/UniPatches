package unipatch.overlaycore.modules.permission;

import android.app.Activity;
import java.util.ArrayList;
import java.util.List;
import unipatch.overlaycore.PermissionGuardRuntime;
import unipatch.overlaycore.modules.OverlayActionModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModuleProvider;
import unipatch.overlaycore.modules.OverlaySessionState;

/** Universal Overlay controls for Permission Guard's instrumented permission paths. */
public final class PermissionGuardRuntimeProvider implements OverlayAppSpecificModuleProvider {
    public static final String PROFILE_ID = "permissionGuardRuntime";
    private static final String MODULE_KEY = "permissionGuardRuntime";
    private static final String[] GROUPS = {
        "camera", "microphone", "location", "contacts", "phone", "sms",
        "calendar", "storage", "media", "notifications", "nearbyDevices", "bluetooth"
    };
    private static final String[] LABELS = {
        "Camera", "Microphone", "Location", "Contacts", "Phone", "SMS",
        "Calendar", "Storage", "Media", "Notifications", "Nearby devices", "Legacy Bluetooth"
    };

    @Override public String profileId() { return PROFILE_ID; }

    @Override public List<OverlayAppSpecificModule> create(Activity activity) {
        List<OverlayAppSpecificModule> modules = new ArrayList<>();
        if (activity != null) modules.add(new PermissionModule());
        return modules;
    }

    private static final class PermissionModule extends OverlayActionModule {
        @Override public String key() { return MODULE_KEY; }
        @Override public String label() { return "Permission Guard"; }
        @Override public String description() { return "Block or allow common permission checks and requests. Native and privileged access is outside this runtime guard."; }
        @Override public boolean hasSettings() { return true; }
        @Override public boolean hasEnableToggle() { return false; }
        @Override public boolean hasActionButton() { return false; }
        @Override public String valueText() { return "Blocked: " + selected(); }
        @Override protected boolean readEnabled(Activity activity, int flags, int systemUi) { return true; }
        @Override protected void applyEnabled(Activity activity, int flags, int systemUi) { }
        @Override protected void restoreOriginal(Activity activity, int flags, int systemUi) { }
        @Override public String[] settingsChoices() { return LABELS.clone(); }
        @Override public String[] settingsDescriptions() {
            String[] values = new String[GROUPS.length];
            for (int i = 0; i < values.length; i++) values[i] = "Enabled: block " + LABELS[i] + " checks and requests. Disabled: keep normal behavior.";
            return values;
        }
        @Override public boolean[] settingsValues() {
            boolean[] values = new boolean[GROUPS.length];
            for (int i = 0; i < values.length; i++) values[i] = PermissionGuardRuntime.isBlocked(GROUPS[i]);
            return OverlaySessionState.booleans(key(), "settings", values);
        }
        @Override public void applySettings(boolean[] values) {
            OverlaySessionState.putBooleans(key(), "settings", values);
        }
        @Override public boolean appliesSettingsOnConfirm() { return true; }
        @Override public boolean applySavedSettings(Activity activity) {
            boolean[] values = OverlaySessionState.booleans(key(), "settings", new boolean[GROUPS.length]);
            for (int i = 0; i < GROUPS.length && i < values.length; i++) PermissionGuardRuntime.setRuntimeBlocked(GROUPS[i], values[i]);
            return true;
        }
        private String selected() {
            List<String> values = new ArrayList<>();
            for (int i = 0; i < GROUPS.length; i++) if (PermissionGuardRuntime.isBlocked(GROUPS[i])) values.add(LABELS[i]);
            return values.isEmpty() ? "none" : join(values);
        }
        private String join(List<String> values) {
            StringBuilder result = new StringBuilder();
            for (String value : values) {
                if (result.length() > 0) result.append(", ");
                result.append(value);
            }
            return result.toString();
        }
    }
}
