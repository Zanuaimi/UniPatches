package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PermissionGuardRuntimeTest {
    @Test
    public void runtimeToggleCanAllowPatchTimeDefault() {
        PermissionGuardRuntime.initialize(null, "camera");
        assertTrue(PermissionGuardRuntime.isBlocked("camera"));

        PermissionGuardRuntime.setRuntimeBlocked("camera", false);

        assertFalse(PermissionGuardRuntime.isBlocked("camera"));
    }
}
