package unipatch.overlaycore;

/** Small dependency-free JSON helper for emulated purchase payloads. */
final class PurchaseJson {
    private PurchaseJson() { }

    static String escape(String value) {
        StringBuilder result = new StringBuilder((value == null ? "" : value).length() + 8);
        String source = value == null ? "" : value;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            switch (c) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else result.append(c);
            }
        }
        return result.toString();
    }
}
