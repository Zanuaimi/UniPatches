package unipatch.overlaycore.modules.advanced;

import android.app.Activity;
import android.text.format.DateFormat;

import java.util.List;

import unipatch.overlaycore.modules.OverlayAdvancedModule;
import unipatch.overlaycore.modules.AdvancedModuleContext;

/** Advanced module showing only Universal Overlay-owned runtime diagnostics. */
public final class OverlayRuntimeLogsModule extends OverlayAdvancedModule {
    private static final String KEY = "overlayRuntimeLogs";
    private boolean active;

    @Override public String key() { return KEY; }
    @Override public String label() { return "Overlay Runtime Logs"; }
    @Override public String description() {
        return "Collect diagnostics emitted by Universal Overlay and its runtime modules.";
    }

    @Override protected boolean onAdvancedModuleStart(AdvancedModuleContext context) {
        active = true;
        OverlayRuntimeLogger.setActive(true);
        OverlayRuntimeLogger.log("INFO", label(), "Runtime log collection activated");
        return true;
    }

    @Override public void onAdvancedModuleStop() {
        if (!active) return;
        OverlayRuntimeLogger.log("INFO", label(), "Runtime log collection deactivated");
        active = false;
        OverlayRuntimeLogger.setActive(false);
    }

    public boolean isActive() { return active; }
    public void clearLogs() { OverlayRuntimeLogger.clear(); }
    public List<OverlayRuntimeLogger.LogEntry> snapshot() { return OverlayRuntimeLogger.snapshot(); }

    public String formattedSnapshot() {
        StringBuilder result = new StringBuilder();
        for (OverlayRuntimeLogger.LogEntry entry : snapshot()) {
            if (result.length() > 0) result.append('\n');
            result.append(DateFormat.format("HH:mm:ss", entry.timestampMillis))
                    .append(' ').append(entry.level).append(' ')
                    .append(entry.source).append(": ").append(entry.message);
        }
        return result.toString();
    }
}
