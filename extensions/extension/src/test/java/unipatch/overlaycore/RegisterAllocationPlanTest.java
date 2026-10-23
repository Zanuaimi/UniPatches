package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RegisterAllocationPlanTest {
    @Test public void reservesScratchAfterOriginalFrameAndParameters() {
        RegisterAllocationPlan plan = RegisterAllocationPlan.create(24, 2, 4);
        assertEquals(24, plan.firstScratchRegister);
        assertEquals(30, plan.expandedRegisterCount);
    }
}
