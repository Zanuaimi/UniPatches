package unipatch.overlaycore;

/** Immutable outcome of one callback delivery attempt. */
public final class CallbackDeliveryResult {
    public final boolean located;
    public final boolean started;
    public final boolean returned;
    public final boolean threw;

    private CallbackDeliveryResult(boolean located, boolean started, boolean returned, boolean threw) {
        this.located = located;
        this.started = started;
        this.returned = returned;
        this.threw = threw;
    }

    public static CallbackDeliveryResult unavailable() { return new CallbackDeliveryResult(false, false, false, false); }
    public static CallbackDeliveryResult located() { return new CallbackDeliveryResult(true, false, false, false); }
    public static CallbackDeliveryResult started() { return new CallbackDeliveryResult(true, true, false, false); }
    public static CallbackDeliveryResult returned() { return new CallbackDeliveryResult(true, true, true, false); }
    public static CallbackDeliveryResult threw() { return new CallbackDeliveryResult(true, true, false, true); }
    public boolean succeeded() { return returned && !threw; }
}
