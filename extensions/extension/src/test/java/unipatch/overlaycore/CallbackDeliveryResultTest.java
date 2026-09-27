package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CallbackDeliveryResultTest {
    @Test
    public void distinguishesCallbackPhases() {
        assertFalse(CallbackDeliveryResult.unavailable().located);
        assertTrue(CallbackDeliveryResult.located().located);
        assertTrue(CallbackDeliveryResult.started().started);
        assertTrue(CallbackDeliveryResult.returned().succeeded());
        assertTrue(CallbackDeliveryResult.threw().threw);
        assertFalse(CallbackDeliveryResult.threw().succeeded());
    }
}
