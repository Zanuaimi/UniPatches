package unipatch.overlaycore;

/** Billing implementation that owns extraction and result construction for a purchase request. */
public enum PurchaseBackend {
    BILLING_V3,
    BILLING_V9,
    OPEN_IAB
}
