package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class PurchaseTimeoutSchedulerTest {
    @Test
    public void capturesTaskAndConfiguredDelay() {
        final long[] delay = {-1L};
        final Runnable[] task = {null};
        PurchaseTimeoutScheduler scheduler = (value, milliseconds) -> {
            task[0] = value;
            delay[0] = milliseconds;
        };

        scheduler.schedule(() -> { }, 10_000L);
        assertEquals(10_000L, delay[0]);
        assertNotNull(task[0]);
    }
}
