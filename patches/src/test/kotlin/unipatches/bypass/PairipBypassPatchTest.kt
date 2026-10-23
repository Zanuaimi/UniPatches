package unipatches.bypass

import org.junit.Assert.assertEquals
import org.junit.Test

class PairipBypassPatchTest {
    @Test
    fun resolvesRelativeAndUnqualifiedManifestComponents() {
        assertEquals("com.example.app.LicenseActivity", resolveManifestComponentName("com.example.app", ".LicenseActivity"))
        assertEquals("com.example.app.LicenseActivity", resolveManifestComponentName("com.example.app", "LicenseActivity"))
        assertEquals("com.pairip.licensecheck.LicenseActivity", resolveManifestComponentName("com.example.app", "com.pairip.licensecheck.LicenseActivity"))
    }
}
