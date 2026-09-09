package unipatch.overlaycore.modules;

/** Common identity contract for every optional Universal Overlay module. */
public interface OverlayModule {
    String key();
    String label();
    String description();
}
