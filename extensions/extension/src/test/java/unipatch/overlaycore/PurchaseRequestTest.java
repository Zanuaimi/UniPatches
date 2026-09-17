package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source-level guarantees for the shared purchase state machine. */
public class PurchaseRequestTest {
    @Test
    public void callbackCanBeClaimedOnlyOnce() {
        PurchaseRequest request = new PurchaseRequest(
                "coins_100", "inapp", "payload", new Object(), null,
                PurchaseBackend.OPEN_IAB, false, 10_000L);

        assertTrue(request.claimCallback());
        assertFalse(request.claimCallback());
        assertTrue(request.callbackAttempted());
    }

    @Test
    public void terminalStateCanBeReachedOnlyOnce() {
        PurchaseRequest request = new PurchaseRequest(
                "coins_100", "inapp", "", new Object(), null,
                PurchaseBackend.BILLING_V3, false, 10_000L);

        assertTrue(request.finish(PurchaseRequest.State.COMPLETED));
        assertFalse(request.finish(PurchaseRequest.State.CANCELLED));
        assertTrue(request.isFinished());
        assertTrue(request.state() == PurchaseRequest.State.COMPLETED);
    }

    @Test
    public void deliveryStateRemainsNonTerminalUntilCallbackReturns() {
        PurchaseRequest request = new PurchaseRequest(
                "coins_100", "subs", "", new Object(), null,
                PurchaseBackend.BILLING_V9, true, 30_000L);

        assertTrue(request.transition(PurchaseRequest.State.RECEIVED, PurchaseRequest.State.DELIVERING));
        assertFalse(request.isFinished());
        assertTrue(request.finish(PurchaseRequest.State.COMPLETED));
    }
}
