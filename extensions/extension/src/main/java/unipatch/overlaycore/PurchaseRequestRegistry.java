package unipatch.overlaycore;

import java.util.HashSet;
import java.util.Set;

/** Small deterministic duplicate guard for entry-point and proxy handoffs. */
public final class PurchaseRequestRegistry {
    private final Set<String> active = new HashSet<>();

    public synchronized boolean claim(String productId) {
        String key = productId == null ? "" : productId.trim();
        if (key.isEmpty() || active.contains(key)) return false;
        active.add(key);
        return true;
    }

    public synchronized boolean release(String productId) {
        return active.remove(productId == null ? "" : productId.trim());
    }

    public synchronized boolean contains(String productId) {
        return active.contains(productId == null ? "" : productId.trim());
    }
}
