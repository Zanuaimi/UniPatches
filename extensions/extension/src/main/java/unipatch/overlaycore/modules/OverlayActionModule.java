package unipatch.overlaycore.modules;

import android.app.Activity;
import android.text.InputType;

/**
 * Optional app-specific module with a session value, settings action, and one-shot action.
 * Implementations must keep these actions reversible and scoped to their target app.
 */
public abstract class OverlayActionModule extends OverlayAppSpecificModule {
    protected OverlayActionModule() { }

    public String valueText() { return ""; }
    public String actionLabel() { return "Run once"; }
    /** Whether this module has a second action button in addition to its Settings button. */
    public boolean hasActionButton() { return true; }
    public boolean hasSettings() { return false; }
    public boolean hasEnableToggle() { return true; }

    /**
     * Declarative settings consumed by the shared overlay-styled popup. Subclasses can expose
     * either a checkbox list or a single input. Keeping this contract here
     * prevents target modules from falling back to platform AlertDialogs with a mismatched theme.
     */
    public String settingsTitle() { return label() + " settings"; }
    public String[] settingsChoices() { return new String[0]; }
    public String[] settingsDescriptions() { return new String[0]; }
    public boolean[] settingsValues() { return new boolean[0]; }
    public void applySettings(boolean[] values) { }
    public String settingsTextValue() { return null; }
    public String settingsTextHint() { return ""; }
    /** Android input type for a text setting; signed number is the safe default for value modules. */
    public int settingsInputType() { return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED; }
    public boolean applySettingsText(String value) { return false; }
    public String settingsConfirmationLabel() { return appliesSettingsOnConfirm() ? "Apply" : "Save"; }

    /** False saves only session configuration; true also invokes [applySavedSettings] on confirm. */
    public boolean appliesSettingsOnConfirm() { return false; }
    /** Apply previously saved session values to a runtime hook. Return false to keep the popup open. */
    public boolean applySavedSettings(Activity activity) { return true; }

    /** Returns false when the action could not be completed. */
    public boolean performAction(Activity activity) { return true; }
}
