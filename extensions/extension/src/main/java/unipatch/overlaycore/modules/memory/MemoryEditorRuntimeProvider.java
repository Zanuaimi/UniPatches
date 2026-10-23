package unipatch.overlaycore.modules.memory;

import android.app.Activity;
import android.text.InputType;
import java.util.Collections;
import java.util.List;
import unipatch.overlaycore.MemoryEditorPolicy;
import unipatch.overlaycore.modules.OverlayActionModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModuleProvider;

/** Overlay front end for int32 scans in the patched app process. */
public final class MemoryEditorRuntimeProvider implements OverlayAppSpecificModuleProvider {
    public static final String PROFILE_ID = "memoryEditor";

    @Override public String profileId() { return PROFILE_ID; }

    @Override public List<OverlayAppSpecificModule> create(Activity activity) {
        return MemoryEditorPolicy.isEnabled() && activity != null
                ? Collections.singletonList(new Module()) : Collections.emptyList();
    }

    private static final class Module extends OverlayActionModule {
        @Override public String key() { return PROFILE_ID; }
        @Override public String label() { return "Memory Editor"; }
        @Override public String description() { return "Search and change int32 values in this app process."; }
        @Override public boolean hasEnableToggle() { return false; }
        @Override public boolean hasSettings() { return true; }
        @Override public boolean hasActionButton() { return false; }
        @Override protected boolean readEnabled(Activity activity, int flags, int ui) { return MemoryEditorPolicy.isEnabled(); }
        @Override protected void applyEnabled(Activity activity, int flags, int ui) { }
        @Override protected void restoreOriginal(Activity activity, int flags, int ui) { }
        @Override public String settingsTitle() { return "Memory Editor command"; }
        @Override public String settingsTextValue() { return ""; }
        @Override public String settingsTextHint() {
            return "exact 123; increased; decreased; unchanged; show; write INDEX VALUE; filter libil2cpp.so; filter all; reset. Reopen to see results.";
        }
        @Override public int settingsInputType() { return InputType.TYPE_CLASS_TEXT; }
        @Override public boolean applySettingsText(String value) { return MemoryEditorPolicy.submit(value); }
        @Override public String valueText() { return MemoryEditorPolicy.status(); }
        @Override public boolean supports(Activity activity) { return activity != null; }
    }
}
