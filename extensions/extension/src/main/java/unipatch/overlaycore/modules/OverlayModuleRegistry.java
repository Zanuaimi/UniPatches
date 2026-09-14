package unipatch.overlaycore.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Small isolated registry shared by the six strongly typed module-family registries. */
abstract class OverlayModuleRegistry<T extends OverlayModule> implements Iterable<T> {
    private final List<T> modules = new ArrayList<>();

    public final void register(T module) {
        if (module == null || module.key() == null || module.key().trim().isEmpty()) return;
        for (T existing : modules) if (module.key().equals(existing.key())) return;
        modules.add(module);
    }

    public final List<T> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(modules));
    }

    public final void clear() { modules.clear(); }
    @Override public final Iterator<T> iterator() { return snapshot().iterator(); }
}
