package unipatch.overlaycore;

import android.content.pm.PackageManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import unipatch.overlaycore.modules.OverlayActionModule;
import unipatch.overlaycore.modules.permission.PermissionGuardRuntimeProvider;

public final class PermissionGuardRuntimeTest {
    @Test
    public void providerExposesOverlayModule() {
        PermissionGuardRuntimeProvider provider = new PermissionGuardRuntimeProvider();

        assertEquals("permissionGuardRuntime", provider.profileId());
        assertEquals(1, provider.create(null).size());
        OverlayActionModule module = (OverlayActionModule) provider.create(null).get(0);
        assertEquals("Block INTERNET Permission Checks", module.settingsChoices()[10]);
    }

    @Test
    public void runtimeToggleCanAllowPatchTimeDefault() {
        PermissionGuardRuntime.initialize(null, "camera");
        assertTrue(PermissionGuardRuntime.isBlocked("camera"));

        PermissionGuardRuntime.setRuntimeBlocked("camera", false);

        assertFalse(PermissionGuardRuntime.isBlocked("camera"));
    }

    @Test
    public void internetGroupIsAvailableToRuntimePolicy() {
        PermissionGuardRuntime.initialize(null, "internet");

        assertTrue(PermissionGuardRuntime.isBlocked("internet"));
        assertEquals(PackageManager.PERMISSION_DENIED, PermissionGuardRuntime.checkSelfPermission(null, "android.permission.INTERNET"));

        PermissionGuardRuntime.setRuntimeBlocked("internet", false);
        assertFalse(PermissionGuardRuntime.isBlocked("internet"));
    }
}
