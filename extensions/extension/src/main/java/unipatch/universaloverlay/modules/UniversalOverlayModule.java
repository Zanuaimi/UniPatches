package unipatch.universaloverlay.modules;

/** Common identity contract for every optional Universal Overlay module. */
public interface UniversalOverlayModule {
    String key();
    String label();
    String description();
}
