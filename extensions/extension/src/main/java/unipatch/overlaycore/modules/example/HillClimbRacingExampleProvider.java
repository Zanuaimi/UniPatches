package unipatch.overlaycore.modules.example;

import android.app.Activity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import unipatch.overlaycore.modules.OverlayActionModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModuleProvider;
import unipatch.overlaycore.modules.OverlaySessionState;

/**
 * Safe app-specific overlay example. It only previews session-local values and never reads or
 * writes Hill Climb Racing data, purchases, save files, or bytecode.
 */
public final class HillClimbRacingExampleProvider implements OverlayAppSpecificModuleProvider {
    public static final String PROFILE_ID = "hillClimbRacingExample";

    @Override public String profileId() { return PROFILE_ID; }

    @Override public List<OverlayAppSpecificModule> create(Activity activity) {
        return Arrays.asList(
                new DemoModule("hcrDemoAddCoins", "Demo: Add Coins", "Mock only; previews a signed 32-bit value without changing the game.", Kind.NUMBER, new String[0]),
                new DemoModule("hcrDemoAddGems", "Demo: Add Gems", "Mock only; previews a signed 32-bit value without changing the game.", Kind.NUMBER, new String[0]),
                new DemoModule("hcrDemoAddPaints", "Demo: Add Paints", "Mock only; previews a signed 32-bit value without changing the game.", Kind.NUMBER, new String[0]),
                new DemoModule("hcrDemoVehicles", "Demo: Vehicle selection", "Mock checkbox list. It does not unlock, lock, or inspect vehicles.", Kind.VEHICLES,
                        new String[] {"Hill Climber", "Motocross Bike", "Jeep", "Monster Truck", "UFO"}),
                new DemoModule("hcrDemoStages", "Demo: Stage selection", "Mock checkbox list. It does not unlock, lock, or inspect stages.", Kind.STAGES,
                        new String[] {"Country Side", "Arctic Cave", "Mudpool", "Highway", "Final Stage"}),
                new DemoModule("hcrDemoGarage", "Demo: Garage selection", "Mock checkbox. It does not unlock, lock, or inspect the garage.", Kind.GARAGE,
                        new String[] {"Unlocked"})
        );
    }

    private enum Kind { NUMBER, VEHICLES, STAGES, GARAGE }

    private static final class DemoModule extends OverlayActionModule {
        private final String key, label, description;
        private final Kind kind;
        private final String[] choices;
        DemoModule(String key, String label, String description, Kind kind, String[] choices) {
            this.key = key; this.label = label; this.description = description;
            this.kind = kind; this.choices = choices;
        }

        @Override public String key() { return key; }
        @Override public String label() { return label; }
        @Override public String description() {
            return description + " Settings and actions are session-only preview controls.";
        }
        @Override public boolean supports(Activity activity) {
            return activity != null && "com.fingersoft.hillclimb".equals(activity.getPackageName());
        }
        @Override public boolean hasSettings() { return true; }
        @Override public String actionLabel() { return "Preview"; }
        @Override public String valueText() {
            if (kind == Kind.NUMBER) return "Preview amount: " + number() + " | " + lastAction();
            if (kind == Kind.GARAGE) return "Preview state: " + (selected()[0] ? "Unlocked" : "Locked") + " | " + lastAction();
            return "Preview selection: " + selectionText() + " | " + lastAction();
        }

        @Override protected boolean readEnabled(Activity activity, int flags, int systemUi) { return false; }
        @Override protected void applyEnabled(Activity activity, int flags, int systemUi) { }
        @Override protected void restoreOriginal(Activity activity, int flags, int systemUi) { }

        @Override public String settingsTitle() { return label + " settings (mock)"; }
        @Override public String[] settingsChoices() { return kind == Kind.NUMBER ? new String[0] : choices.clone(); }
        @Override public boolean[] settingsValues() { return selected(); }
        @Override public void applySettings(boolean[] values) { if (kind != Kind.NUMBER) OverlaySessionState.putBooleans(key, "selected", values); }
        @Override public String settingsTextValue() { return kind == Kind.NUMBER ? Integer.toString(number()) : null; }
        @Override public String settingsTextHint() { return "Signed 32-bit preview range: -2147483648 to 2147483647"; }
        @Override public String settingsConfirmationLabel() { return kind == Kind.NUMBER ? "Set" : "Save"; }
        @Override public boolean applySettingsText(String value) {
            if (kind != Kind.NUMBER) return false;
            try { OverlaySessionState.putInteger(key, "number", Integer.parseInt(value.trim())); return true; }
            catch (NumberFormatException ignored) { return false; }
        }

        @Override public boolean performAction(Activity activity) {
            OverlaySessionState.putString(key, "lastAction", "Preview applied at " + System.currentTimeMillis());
            return true;
        }

        private String selectionText() {
            List<String> enabled = new ArrayList<>();
            boolean[] selected = selected();
            for (int i = 0; i < choices.length; i++) if (selected[i]) enabled.add(choices[i]);
            return enabled.isEmpty() ? "none enabled" : join(enabled);
        }

        private String join(List<String> values) {
            StringBuilder result = new StringBuilder();
            for (String value : values) {
                if (result.length() > 0) result.append(", ");
                result.append(value);
            }
            return result.toString();
        }

        private int number() { return OverlaySessionState.integer(key, "number", 0); }
        private String lastAction() { return OverlaySessionState.string(key, "lastAction", "Not previewed this session"); }
        private boolean[] selected() {
            boolean[] defaults = new boolean[choices.length];
            if (kind == Kind.VEHICLES && defaults.length > 0) defaults[0] = true;
            return OverlaySessionState.booleans(key, "selected", defaults);
        }
    }
}
