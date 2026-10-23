package unipatches.services

import org.junit.Assert.assertTrue
import org.junit.Test

class ControlEmbeddedAuthStoresManagedTest {
    @Test
    fun rejectsMainActivityOverrideOutsideGmsCoreMode() {
        val errors = validateControlEmbeddedOptions(
            ControlEmbeddedOptions(
                providerMode = "real",
                gmsCorePackage = "app.morphe.android.gms",
                mainActivity = "com.example.MainActivity",
                storeAvailability = "real",
                bypassLicenseVerification = false,
                showPlayServicesAvailable = false,
                bypassPlayServicesCheck = false,
                bypassPlayLicenseCheck = false,
                disableFirebasePerformance = false,
                disableGooglePay = false,
                disableRemoteConfig = false,
                forceSignedOut = false,
                suppressPlayGamesSignIn = false,
                suppressPlayGamesSignInUi = false,
                fixMaps = false,
                nullLocation = false,
                silenceErrors = false,
                spoofVersion = false,
            ),
        )

        assertTrue(errors.any { it.contains("mainActivity", ignoreCase = true) })
    }
}
