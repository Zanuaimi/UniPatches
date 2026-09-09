package unipatch.overlaycore.modules;

import java.util.HashMap;
import java.util.Map;

/** Process-local module values. They survive overlay/controller recreation across Activities only. */
public final class OverlaySessionState {
    private static final Map<String, Object> VALUES = new HashMap<>();
    private OverlaySessionState() { }

    private static String key(String module, String field) { return module + "\u0000" + field; }

    public static synchronized int integer(String module, String field, int fallback) {
        Object value = VALUES.get(key(module, field));
        return value instanceof Integer ? (Integer) value : fallback;
    }
    public static synchronized void putInteger(String module, String field, int value) {
        VALUES.put(key(module, field), value);
    }
    public static synchronized String string(String module, String field, String fallback) {
        Object value = VALUES.get(key(module, field));
        return value instanceof String ? (String) value : fallback;
    }
    public static synchronized void putString(String module, String field, String value) {
        VALUES.put(key(module, field), value == null ? "" : value);
    }
    public static synchronized boolean[] booleans(String module, String field, boolean[] fallback) {
        Object value = VALUES.get(key(module, field));
        return value instanceof boolean[] ? ((boolean[]) value).clone() : fallback.clone();
    }
    public static synchronized void putBooleans(String module, String field, boolean[] value) {
        VALUES.put(key(module, field), value == null ? new boolean[0] : value.clone());
    }
}
