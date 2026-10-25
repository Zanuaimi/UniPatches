package unipatch.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-free JSON scanning for purchase-verification payloads.
 *
 * <p>Two jobs, one scanner:
 * <ul>
 *   <li>{@link #rewrite(String, Rules)} copies a document byte-for-byte and only
 *       replaces the scalar values of keys named in {@link Rules}. Everything the
 *       host app does not need changed stays exactly as the server sent it, which
 *       keeps unknown fields, key order, whitespace and number formatting intact.</li>
 *   <li>{@link #readFlatObject(String)} reads the small policy object the patch
 *       injects at startup. It is deliberately shallow: nested objects are skipped
 *       so a malformed policy can never throw into an app's startup path.</li>
 * </ul>
 *
 * <p>Malformed input is reported as {@code null}, never as an exception: the caller
 * runs inside the host application and must fall back to the original bytes.
 */
final class VerificationJson {
    private static final int MAX_DEPTH = 64;

    private VerificationJson() { }

    /** Replacement rules for {@link #rewrite(String, Rules)}. Keys are normalized. */
    static final class Rules {
        final Set<String> booleanFlags;
        final Set<String> statusKeys;
        final Set<String> expiryKeys;
        final Set<String> deadStatuses;
        final String activeStatus;
        final long expiryEpochMillis;
        final boolean expiryInSeconds;

        Rules(Set<String> booleanFlags, Set<String> statusKeys, Set<String> expiryKeys,
              Set<String> deadStatuses, String activeStatus, long expiryEpochMillis,
              boolean expiryInSeconds) {
            this.booleanFlags = booleanFlags;
            this.statusKeys = statusKeys;
            this.expiryKeys = expiryKeys;
            this.deadStatuses = deadStatuses;
            this.activeStatus = activeStatus == null ? "active" : activeStatus;
            this.expiryEpochMillis = expiryEpochMillis;
            this.expiryInSeconds = expiryInSeconds;
        }
    }

    /**
     * Rewrites one JSON document.
     *
     * @return the rewritten document, or {@code null} when the input is malformed,
     *         is not a JSON container, or no rule matched.
     */
    static String rewrite(String json, Rules rules) {
        if (json == null || rules == null) return null;
        Scanner scanner = new Scanner(json);
        StringBuilder out = new StringBuilder(json.length() + 64);
        try {
            if (!scanner.copyValue(out, rules, null, 0)) return null;
            scanner.skipWhitespace();
            if (!scanner.atEnd()) return null;
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            return null;
        }
        String result = out.toString();
        return result.equals(json) ? null : result;
    }

    /**
     * Reads a shallow JSON object of booleans, integers, strings and string arrays.
     * Nested objects/arrays of non-strings are skipped rather than rejected so the
     * policy reader tolerates extra metadata written by future patch versions.
     */
    static Map<String, Object> readFlatObject(String json) {
        if (json == null) return null;
        Scanner scanner = new Scanner(json);
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            scanner.skipWhitespace();
            if (!scanner.consume('{')) return null;
            scanner.skipWhitespace();
            if (scanner.peek() == '}') return values;
            while (true) {
                scanner.skipWhitespace();
                String key = scanner.readStringValue();
                if (key == null) return null;
                scanner.skipWhitespace();
                if (!scanner.consume(':')) return null;
                scanner.skipWhitespace();
                Object value = scanner.readFlatValue(0);
                values.put(key, value);
                scanner.skipWhitespace();
                char next = scanner.peek();
                if (next == ',') {
                    scanner.index++;
                    continue;
                }
                if (next == '}') {
                    scanner.index++;
                    return values;
                }
                return null;
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            return null;
        }
    }

    /** Lowercase, stripped form used to match host-app keys against policy keys. */
    static String normalize(Object key) {
        if (key == null) return "";
        String text = String.valueOf(key).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '-' || c == ' ' || c == '.') continue;
            out.append(c);
        }
        return out.toString();
    }

    private static final class Scanner {
        private final String text;
        private int index;

        Scanner(String text) {
            this.text = text;
        }

        boolean atEnd() { return index >= text.length(); }

        char peek() {
            if (index >= text.length()) throw new IllegalArgumentException("unexpected end");
            return text.charAt(index);
        }

        void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') index++;
                else break;
            }
        }

        boolean consume(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        /**
         * Copies the next value into {@code out}. Returns true when the copy differs
         * from the input for the member named {@code key}.
         */
        boolean copyValue(StringBuilder out, Rules rules, String key, int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("nesting too deep");
            skipWhitespace();
            char c = peek();
            if (c == '{') return copyObject(out, rules, depth);
            if (c == '[') return copyArray(out, rules, depth);
            if (c == '"') return copyStringValue(out, rules, key);
            if (c == 't' || c == 'f') return copyBoolean(out, rules, key);
            if (c == 'n') {
                copyLiteral(out, "null");
                return false;
            }
            if (c == '-' || (c >= '0' && c <= '9')) return copyNumber(out, rules, key);
            throw new IllegalArgumentException("unexpected token");
        }

        private boolean copyObject(StringBuilder out, Rules rules, int depth) {
            consume('{');
            out.append('{');
            skipWhitespace();
            if (peek() == '}') {
                index++;
                out.append('}');
                return false;
            }
            boolean changed = false;
            while (true) {
                skipWhitespace();
                int keyStart = index;
                String rawKey = readRawString();
                if (rawKey == null) throw new IllegalArgumentException("bad key");
                String plainKey = decode(rawKey);
                out.append(text, keyStart, index);
                skipWhitespace();
                if (!consume(':')) throw new IllegalArgumentException("missing colon");
                out.append(':');
                skipWhitespace();
                changed |= copyValue(out, rules, normalize(plainKey), depth + 1);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    index++;
                    out.append(',');
                    continue;
                }
                if (next == '}') {
                    index++;
                    out.append('}');
                    return changed;
                }
                throw new IllegalArgumentException("bad object");
            }
        }

        private boolean copyArray(StringBuilder out, Rules rules, int depth) {
            consume('[');
            out.append('[');
            skipWhitespace();
            if (peek() == ']') {
                index++;
                out.append(']');
                return false;
            }
            boolean changed = false;
            while (true) {
                // Array elements inherit no key: only container recursion applies.
                changed |= copyValue(out, rules, null, depth + 1);
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    index++;
                    out.append(',');
                    continue;
                }
                if (next == ']') {
                    index++;
                    out.append(']');
                    return changed;
                }
                throw new IllegalArgumentException("bad array");
            }
        }

        private boolean copyBoolean(StringBuilder out, Rules rules, String key) {
            int start = index;
            if (text.startsWith("true", index)) {
                index += 4;
            } else if (text.startsWith("false", index)) {
                index += 5;
            } else {
                throw new IllegalArgumentException("bad literal");
            }
            if (key != null && rules.booleanFlags.contains(key)) {
                out.append("true");
                return true;
            }
            out.append(text, start, index);
            return false;
        }

        private boolean copyStringValue(StringBuilder out, Rules rules, String key) {
            int start = index;
            String raw = readRawString();
            if (raw == null) throw new IllegalArgumentException("bad string");
            String decoded = decode(raw);
            if (key != null) {
                if (rules.booleanFlags.contains(key)) {
                    out.append("true");
                    return true;
                }
                if (rules.statusKeys.contains(key)
                        && rules.deadStatuses.contains(normalize(decoded))) {
                    out.append('"').append(escape(rules.activeStatus)).append('"');
                    return true;
                }
                if (rules.expiryKeys.contains(key)) {
                    out.append('"').append(escape(isoTimestamp(rules.expiryEpochMillis))).append('"');
                    return true;
                }
            }
            out.append(text, start, index);
            return false;
        }

        private boolean copyNumber(StringBuilder out, Rules rules, String key) {
            int start = index;
            if (peek() == '-') index++;
            while (index < text.length()) {
                char c = text.charAt(index);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') index++;
                else break;
            }
            if (key == null) {
                out.append(text, start, index);
                return false;
            }
            if (rules.booleanFlags.contains(key)) {
                out.append("true");
                return true;
            }
            if (rules.expiryKeys.contains(key)) {
                long value = rules.expiryInSeconds
                        ? rules.expiryEpochMillis / 1000L
                        : rules.expiryEpochMillis;
                out.append(value);
                return true;
            }
            out.append(text, start, index);
            return false;
        }

        private void copyLiteral(StringBuilder out, String literal) {
            if (!text.startsWith(literal, index)) throw new IllegalArgumentException("bad literal");
            out.append(literal);
            index += literal.length();
        }

        /** Reads a raw (still escaped) string token and returns the body without quotes. */
        String readRawString() {
            if (peek() != '"') return null;
            int start = index + 1;
            index++;
            boolean escaped = false;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (escaped) {
                    escaped = false;
                    index++;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    index++;
                    continue;
                }
                if (c == '"') {
                    String raw = text.substring(start, index);
                    index++;
                    return raw;
                }
                index++;
            }
            return null;
        }

        String readStringValue() {
            String raw = readRawString();
            return raw == null ? null : decode(raw);
        }

        /** Reads a shallow policy value; containers are skipped and reported as null. */
        Object readFlatValue(int depth) {
            if (depth > MAX_DEPTH) return null;
            char c = peek();
            if (c == '"') return readStringValue();
            if (c == 't') { copyLiteral(new StringBuilder(), "true"); return Boolean.TRUE; }
            if (c == 'f') { copyLiteral(new StringBuilder(), "false"); return Boolean.FALSE; }
            if (c == 'n') { copyLiteral(new StringBuilder(), "null"); return null; }
            if (c == '-' || (c >= '0' && c <= '9')) {
                int start = index;
                if (peek() == '-') index++;
                while (index < text.length()) {
                    char n = text.charAt(index);
                    if ((n >= '0' && n <= '9') || n == '.' || n == 'e' || n == 'E' || n == '+' || n == '-') index++;
                    else break;
                }
                String raw = text.substring(start, index);
                try {
                    return Long.valueOf(raw.contains(".") || raw.contains("e") || raw.contains("E")
                            ? (long) Double.parseDouble(raw)
                            : Long.parseLong(raw));
                } catch (NumberFormatException bad) {
                    return null;
                }
            }
            if (c == '[') {
                consume('[');
                List<String> strings = new ArrayList<>();
                skipWhitespace();
                if (consume(']')) return strings;
                while (true) {
                    skipWhitespace();
                    if (peek() == '"') strings.add(readStringValue());
                    else skipValue(0);
                    skipWhitespace();
                    if (consume(',')) continue;
                    if (consume(']')) return strings;
                    throw new IllegalArgumentException("bad array");
                }
            }
            if (c == '{') {
                skipValue(0);
                return null;
            }
            throw new IllegalArgumentException("bad value");
        }

        void skipValue(int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("nesting too deep");
            skipWhitespace();
            char c = peek();
            if (c == '"') { readRawString(); return; }
            if (c == '{' || c == '[') {
                char close = c == '{' ? '}' : ']';
                int level = 0;
                while (index < text.length()) {
                    char current = text.charAt(index);
                    if (current == '"') {
                        readRawString();
                        continue;
                    }
                    if (current == '{' || current == '[') level++;
                    else if (current == '}' || current == ']') {
                        level--;
                        if (level == 0 && current == close) {
                            index++;
                            return;
                        }
                    }
                    index++;
                }
                throw new IllegalArgumentException("unterminated container");
            }
            if (c == 't') { copyLiteral(new StringBuilder(), "true"); return; }
            if (c == 'f') { copyLiteral(new StringBuilder(), "false"); return; }
            if (c == 'n') { copyLiteral(new StringBuilder(), "null"); return; }
            int start = index;
            while (index < text.length()) {
                char n = text.charAt(index);
                if ((n >= '0' && n <= '9') || n == '-' || n == '+' || n == '.' || n == 'e' || n == 'E') index++;
                else break;
            }
            if (index == start) throw new IllegalArgumentException("bad value");
        }
    }

    static String decode(String raw) {
        if (raw == null || raw.indexOf('\\') < 0) return raw == null ? "" : raw;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char escaped = raw.charAt(++i);
            switch (escaped) {
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 < raw.length()) {
                        try {
                            out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException bad) {
                            out.append("\\u");
                        }
                    } else {
                        out.append("\\u");
                    }
                    break;
                default: out.append(escaped); break;
            }
        }
        return out.toString();
    }

    static String escape(String value) {
        String source = value == null ? "" : value;
        StringBuilder out = new StringBuilder(source.length() + 8);
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: out.append(c); break;
            }
        }
        return out.toString();
    }

    static String isoTimestamp(long epochMillis) {
        java.util.Calendar calendar = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"), Locale.ROOT);
        calendar.setTimeInMillis(epochMillis);
        return String.format(
                Locale.ROOT,
                "%04d-%02d-%02dT%02d:%02d:%02dZ",
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH) + 1,
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                calendar.get(java.util.Calendar.MINUTE),
                calendar.get(java.util.Calendar.SECOND));
    }
}
