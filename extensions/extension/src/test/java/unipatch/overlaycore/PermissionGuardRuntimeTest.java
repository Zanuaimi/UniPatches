package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import unipatch.overlaycore.modules.permission.PermissionGuardRuntimeProvider;

public final class PermissionGuardRuntimeTest {
    @Test
    public void providerExposesOverlayModule() {
        PermissionGuardRuntimeProvider provider = new PermissionGuardRuntimeProvider();

        assertEquals("permissionGuardRuntime", provider.profileId());
        assertEquals(1, provider.create(null).size());
    }

    @Test
    public void runtimeToggleCanAllowPatchTimeDefault() {
        PermissionGuardRuntime.initialize(null, "camera");
        assertTrue(PermissionGuardRuntime.isBlocked("camera"));

        PermissionGuardRuntime.setRuntimeBlocked("camera", false);

        assertFalse(PermissionGuardRuntime.isBlocked("camera"));
    }
}
