package unipatch.overlaycore;

import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded catalog identity tracker used by opt-in inventory emulation. */
final class CatalogProductTracker {
    private static final int MAX_PRODUCTS = 128;
    private final Set<String> products = new LinkedHashSet<>();

    void remember(String productId) {
        if (productId == null) return;
        String normalized = productId.trim();
        if (!normalized.isEmpty() && normalized.length() <= 256 && products.size() < MAX_PRODUCTS) {
            products.add(normalized);
        }
    }

    String onlyProduct() { return products.size() == 1 ? products.iterator().next() : ""; }

    void clear() { products.clear(); }
}
