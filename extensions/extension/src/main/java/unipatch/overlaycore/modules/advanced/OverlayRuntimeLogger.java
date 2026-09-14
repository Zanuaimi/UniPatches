package unipatch.overlaycore.modules.advanced;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Bounded, opt-in diagnostics owned by the Universal Overlay runtime. */
public final class OverlayRuntimeLogger {
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_MESSAGE_LENGTH = 512;
    private static final ArrayDeque<LogEntry> ENTRIES = new ArrayDeque<>(MAX_ENTRIES);
    private static boolean active;

    private OverlayRuntimeLogger() { }

    public static synchronized void setActive(boolean enabled) {
        active = enabled;
    }

    public static synchronized boolean isActive() { return active; }

    /** Does no formatting, allocation, or storage while diagnostics are inactive. */
    public static synchronized void log(String level, String source, String message) {
        if (!active) return;
        String safeLevel = normalize(level, "INFO");
        String safeSource = normalize(source, "Overlay");
        String safeMessage = redact(message == null ? "" : message);
        if (safeMessage.length() > MAX_MESSAGE_LENGTH) {
            safeMessage = safeMessage.substring(0, MAX_MESSAGE_LENGTH);
        }
        if (ENTRIES.size() >= MAX_ENTRIES) ENTRIES.removeFirst();
        ENTRIES.addLast(new LogEntry(System.currentTimeMillis(), safeLevel, safeSource, safeMessage));
    }

    public static synchronized List<LogEntry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(ENTRIES));
    }

    public static synchronized void clear() { ENTRIES.clear(); }

    private static String normalize(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        return result.isEmpty() ? fallback : result.toUpperCase(Locale.US);
    }

    private static String redact(String value) {
        return value
                .replaceAll("(?i)(token|authorization|password|secret)\\s*[:=]\\s*[^\\s,;]+", "$1=[redacted]")
                .replaceAll("(?i)https?://[^\\s]+", "[url-redacted]");
    }

    public static final class LogEntry {
        public final long timestampMillis;
        public final String level;
        public final String source;
        public final String message;

        LogEntry(long timestampMillis, String level, String source, String message) {
            this.timestampMillis = timestampMillis;
            this.level = level;
            this.source = source;
            this.message = message;
        }
    }
}
