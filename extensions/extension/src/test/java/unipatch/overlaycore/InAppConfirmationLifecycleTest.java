package unipatch.overlaycore;

import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises cancellation/confirmation against real runtime request state, without an Android UI. */
public class InAppConfirmationLifecycleTest {
    private Field pendingField;

    @Before public void setUp() throws Exception {
        InAppRuntimePolicy.reset();
        pendingField = InAppRuntimePolicy.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
    }

    @After public void tearDown() { InAppRuntimePolicy.reset(); }

    private PurchaseRequest waiting(String product) throws Exception {
        PurchaseRequest request = new PurchaseRequest(product, "inapp", "", new Object(), null,
                PurchaseBackend.BILLING_V9, true, 30_000L);
        request.transition(PurchaseRequest.State.RECEIVED, PurchaseRequest.State.VALIDATED);
        request.transition(PurchaseRequest.State.VALIDATED, PurchaseRequest.State.WAITING_FOR_POPUP);
        pendingField.set(null, request);
        return request;
    }

    @Test public void staleCancellationCannotCancelANewerRequestForTheSameProduct() throws Exception {
        PurchaseRequest previous = waiting("coins_100");
        previous.finish(PurchaseRequest.State.TIMED_OUT);
        PurchaseRequest current = waiting("coins_100");

        assertFalse(InAppRuntimePolicy.cancelPending(previous.requestId));
        assertFalse(InAppRuntimePolicy.timeoutPending(previous.requestId));
        assertEquals(PurchaseRequest.State.WAITING_FOR_POPUP, current.state());
        assertFalse(current.callbackAttempted());
        assertSame(current, pendingField.get(null));
    }

    @Test public void staleConfirmationCannotCompleteANewerRequest() throws Exception {
        PurchaseRequest previous = waiting("coins_100");
        previous.finish(PurchaseRequest.State.CANCELLED);
        PurchaseRequest current = waiting("coins_100");

        InAppRuntimePolicy.complete(previous.requestId, true);
        assertEquals(PurchaseRequest.State.WAITING_FOR_POPUP, current.state());
        assertFalse(current.callbackAttempted());
        assertEquals(0, InAppRuntimePolicy.savedPurchases().length);
    }

    @Test public void explicitCancellationIsTerminalAndDoesNotSaveAProduct() throws Exception {
        PurchaseRequest request = waiting("coins_100");

        assertTrue(InAppRuntimePolicy.cancelPending(request.requestId));
        assertEquals(PurchaseRequest.State.CANCELLED, request.state());
        assertTrue(request.callbackAttempted());
        assertNull(pendingField.get(null));
        assertEquals(0, InAppRuntimePolicy.savedPurchases().length);
        assertFalse(InAppRuntimePolicy.cancelPending(request.requestId));
    }

    @Test public void expiredRequestKeepsTheTimeoutTerminalState() throws Exception {
        PurchaseRequest request = waiting("coins_100");

        assertTrue(InAppRuntimePolicy.timeoutPending(request.requestId));
        assertEquals(PurchaseRequest.State.TIMED_OUT, request.state());
        assertTrue(InAppRuntimePolicy.lastEvent().startsWith("Purchase timed out:"));
        assertFalse(InAppRuntimePolicy.timeoutPending(request.requestId));
    }

    @Test public void failedConfirmationCallbackDoesNotSaveTheProduct() throws Exception {
        PurchaseRequest request = waiting("coins_100");
        // No Play Billing classes exist on this test JVM: construction must fail cleanly.
        InAppRuntimePolicy.complete(request.requestId, true);

        assertEquals(PurchaseRequest.State.CANCELLED, request.state());
        assertEquals(0, InAppRuntimePolicy.savedPurchases().length);
        assertTrue(InAppRuntimePolicy.lastEvent().startsWith("Purchase callback failed:"));
        assertNull(pendingField.get(null));
    }

    @Test public void cancellationCannotInterruptAnAlreadyDeliveringRequest() throws Exception {
        PurchaseRequest request = waiting("coins_100");
        request.transition(PurchaseRequest.State.WAITING_FOR_POPUP, PurchaseRequest.State.DELIVERING);

        assertFalse(InAppRuntimePolicy.cancelPending(request.requestId));
        assertFalse(InAppRuntimePolicy.timeoutPending(request.requestId));
        assertFalse(request.callbackAttempted());
        assertSame(request, pendingField.get(null));
    }

    @Test public void confirmationIdentityRejectsWrongProductsAndFinishedRequests() throws Exception {
        PurchaseRequest request = waiting("coins_100");

        assertEquals(request.requestId, InAppRuntimePolicy.pendingConfirmationId("coins_100"));
        assertEquals(0L, InAppRuntimePolicy.pendingConfirmationId("another_product"));
        assertTrue(InAppRuntimePolicy.isPendingConfirmation(request.requestId));
        request.finish(PurchaseRequest.State.TIMED_OUT);
        assertFalse(InAppRuntimePolicy.isPendingConfirmation(request.requestId));
        assertEquals(0L, InAppRuntimePolicy.pendingConfirmationId("coins_100"));
    }
}
