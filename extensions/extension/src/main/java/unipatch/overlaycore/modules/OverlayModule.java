package unipatch.overlaycore.modules;

/** Common identity contract for every optional Universal Overlay module. */
public interface OverlayModule {
    String key();
    String label();
    String description();

    /** Stable family name used by the common menu presentation layer. */
    default String category() { return "custom"; }
}
