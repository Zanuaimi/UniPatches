package unipatch.overlaycore;

/** Small scheduler boundary that allows deterministic timeout tests. */
public interface PurchaseTimeoutScheduler {
    void schedule(Runnable task, long delayMillis);
}
