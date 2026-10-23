package unipatch.overlaycore;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Session-only in-process memory search and edit state for the optional patch. */
public final class MemoryEditorPolicy {
    private static final MemoryScanner SCANNER = new MemoryScanner();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "UniPatches-memory-scan");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean enabled;
    private static volatile boolean seeded;
    private static volatile String status = "Enter: exact <int32> to start";

    private MemoryEditorPolicy() { }

    public static void enable() { enabled = true; }
    public static boolean isEnabled() { return enabled; }
    public static String status() { return status; }

    public static boolean submit(String command) {
        if (!enabled || command == null) return false;
        final String[] parts = command.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return false;
        final String action = parts[0].toLowerCase(Locale.ROOT);
        final int value;
        final int index;
        try {
            switch (action) {
                case "exact":
                    if (parts.length != 2) return false;
                    value = Integer.parseInt(parts[1]);
                    index = -1;
                    break;
                case "write":
                    if (parts.length != 3) return false;
                    index = Integer.parseInt(parts[1]);
                    value = Integer.parseInt(parts[2]);
                    if (index < 0 || index >= MemoryScanner.MAX_RESULTS) return false;
                    break;
                case "filter":
                    if (parts.length != 2 || parts[1].length() > 96) return false;
                    index = -1;
                    value = 0;
                    break;
                case "increased": case "decreased": case "unchanged":
                case "show": case "reset":
                    if (parts.length != 1) return false;
                    index = -1;
                    value = 0;
                    break;
                default:
                    return false;
            }
        } catch (NumberFormatException invalid) {
            return false;
        }
        status = "Running " + action + "...";
        WORKER.execute(() -> {
            try {
                switch (action) {
                    case "filter":
                        SCANNER.setRegionFilter("all".equals(parts[1]) ? null : parts[1]);
                        SCANNER.reset(); seeded = false;
                        status = "Region filter: " + parts[1] + "; run exact VALUE";
                        return;
                    case "reset":
                        SCANNER.reset(); seeded = false;
                        status = "Search reset; run exact VALUE";
                        return;
                    case "exact":
                        SCANNER.reset(); seeded = true;
                        int found = SCANNER.scan(MemoryScanner.Mode.EXACT, value);
                        if (!SCANNER.wasLastScanReadable()) {
                            seeded = false;
                            status = "Memory unavailable: /proc/self/mem could not be read";
                        } else {
                            status = "Found " + found + " int32 candidates" +
                                    partialSuffix() + "; run show";
                        }
                        return;
                    case "write":
                        if (!seeded) { status = "Run exact VALUE first"; return; }
                        List<Long> targets = SCANNER.resultAddresses(index + 1);
                        if (targets.size() <= index) { status = "Result index out of range"; return; }
                        status = SCANNER.writeInt(targets.get(index), value)
                                ? "Wrote result " + index + " = " + value : "Write failed; mapping may have changed";
                        return;
                    case "show":
                        if (!seeded) { status = "Run exact VALUE first"; return; }
                        List<Long> results = SCANNER.resultAddresses(8);
                        StringBuilder summary = new StringBuilder("Candidates: ")
                                .append(SCANNER.resultCount()).append(partialSuffix());
                        for (int i = 0; i < results.size(); i++) {
                            long address = results.get(i);
                            summary.append("\n").append(i).append(": ")
                                    .append(SCANNER.describe(address)).append(" = ").append(SCANNER.valueAt(address));
                        }
                        status = summary.toString();
                        return;
                    default:
                        if (!seeded) { status = "Run exact VALUE first"; return; }
                        MemoryScanner.Mode mode = MemoryScanner.Mode.valueOf(action.toUpperCase(Locale.ROOT));
                        status = "Remaining " + SCANNER.scan(mode, 0) + " int32 candidates" +
                                partialSuffix() + "; run show";
                }
            } catch (RuntimeException error) {
                status = "Memory command failed: " + error.getClass().getSimpleName();
            }
        });
        return true;
    }

    private static String partialSuffix() {
        return SCANNER.wasLastScanTruncated() ? " (partial scan)" : "";
    }
}
