package unipatch.overlaycore.modules.example;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import unipatch.overlaycore.modules.OverlayActionModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModuleProvider;

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
        private int number;
        private boolean[] selected;
        private String lastAction = "Not previewed this session";

        DemoModule(String key, String label, String description, Kind kind, String[] choices) {
            this.key = key; this.label = label; this.description = description;
            this.kind = kind; this.choices = choices;
            this.selected = new boolean[choices.length];
            if (kind == Kind.VEHICLES && selected.length > 0) selected[0] = true;
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
            if (kind == Kind.NUMBER) return "Preview amount: " + number + " | " + lastAction;
            if (kind == Kind.GARAGE) return "Preview state: " + (selected[0] ? "Unlocked" : "Locked") + " | " + lastAction;
            return "Preview selection: " + selectionText() + " | " + lastAction;
        }

        @Override protected boolean readEnabled(Activity activity, int flags, int systemUi) { return false; }
        @Override protected void applyEnabled(Activity activity, int flags, int systemUi) { }
        @Override protected void restoreOriginal(Activity activity, int flags, int systemUi) { }

        @Override public void showSettings(Activity activity, int background, int textColor, int outline, int accent) {
            if (kind == Kind.NUMBER) showNumberSettings(activity, background, textColor, accent);
            else showChoiceSettings(activity, background, textColor, accent);
        }

        private void showNumberSettings(Activity activity, int background, int textColor, int accent) {
            EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            input.setText(Integer.toString(number));
            input.setTextColor(textColor);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(label + " settings (mock)")
                    .setMessage("Signed 32-bit preview range: -2147483648 to 2147483647")
                    .setView(input)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Set", null)
                    .create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try { number = Integer.parseInt(input.getText().toString().trim()); }
                catch (NumberFormatException ignoredNumber) { return; }
                styleDialog(dialog, background, textColor, accent);
                dialog.dismiss();
            }));
            dialog.show();
            styleDialog(dialog, background, textColor, accent);
        }

        private void showChoiceSettings(Activity activity, int background, int textColor, int accent) {
            boolean[] draft = selected.clone();
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(label + " settings (mock)")
                    .setMultiChoiceItems(choices, draft, (ignored, which, checked) -> draft[which] = checked)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Set", (ignored, which) -> selected = draft)
                    .create();
            dialog.show();
            styleDialog(dialog, background, textColor, accent);
        }

        private void styleDialog(AlertDialog dialog, int background, int textColor, int accent) {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
            }
            int titleId = activityResourceId(dialog, "alertTitle");
            android.view.View title = titleId == 0 ? null : dialog.findViewById(titleId);
            if (title instanceof android.widget.TextView) ((android.widget.TextView) title).setTextColor(textColor);
            android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            android.widget.Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (positive != null) positive.setTextColor(accent);
            if (negative != null) negative.setTextColor(accent);
        }

        private int activityResourceId(AlertDialog dialog, String name) {
            return dialog.getContext().getResources().getIdentifier(name, "id", "android");
        }

        @Override public boolean performAction(Activity activity) {
            lastAction = "Preview applied at " + System.currentTimeMillis();
            return true;
        }

        private String selectionText() {
            List<String> enabled = new ArrayList<>();
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
    }
}
