package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CallbackInvokerTest {
    @Test public void classifiesMissingCallback() {
        assertFalse(CallbackInvoker.invoke(false, () -> { }).located);
    }

    @Test public void classifiesThrowingCallbackWithoutRetry() {
        CallbackDeliveryResult result = CallbackInvoker.invoke(true, () -> { throw new IllegalStateException(); });
        assertTrue(result.started);
        assertTrue(result.threw);
        assertFalse(result.succeeded());
    }

    @Test public void classifiesReturningCallback() {
        assertTrue(CallbackInvoker.invoke(true, () -> { }).succeeded());
    }
}
