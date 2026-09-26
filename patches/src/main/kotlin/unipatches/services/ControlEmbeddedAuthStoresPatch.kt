package unipatches.services

import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.stringOption
import helpers.manifest.NS_ANDROID
import helpers.manifest.applicationOrNull
import org.w3c.dom.Element
import java.util.logging.Logger

@Suppress("unused")
val controlEmbeddedAuthStoresPatch = rawResourcePatch(
    name = "Control Embedded Auth / Stores Patch ( Enhanced )",
    description = """
        Control embedded Google Play, Firebase, Google Pay, location, licensing, sign-in, and store availability behavior.

        Choose real Google Play Services, GmsCore, or Zero Google Play behavior. GmsCore mode includes Maps-on-MicroG compatibility and supports package and main-activity overrides.

        All controls are optional and apply only to matching methods or manifest components.

        This patch only controls embedded services, licensing, and store checks. Its optional GmsCore startup
        Activity override should not target a third-party sign-in or billing Activity. When combined
        with Universal Overlay, use the overlay's Activity override only when automatic launcher
        resolution cannot identify the app's real entry Activity.

        Credits to Nai64 for the majority of the original Google Services, Firebase, licensing, location, store, and GmsCore functionality. UniPatches provides the merged settings, compatibility guards, and patch organization.
    """.trimIndent(),
    default = false,
) {
    try { category("Embedded Auth / Stores") } catch (_: NoSuchMethodError) {}

    val providerMode by stringOption(
        title = "Embedded Auth / Stores > Provider and store behavior > Google services provider",
        default = "real",
        key = "embeddedProviderMode",
        description = "Choose real Google Play Services, GmsCore / MicroG, or Zero Google Play behavior.",
        values = linkedMapOf(
            "Real Google Play Services (default)" to "real",
            "GmsCore / MicroG" to "gmscore",
            "Zero Google Play" to "zero",
        ),
    )
    val gmsCorePackage by stringOption(
        title = "Embedded Auth / Stores > Provider and store behavior > GmsCore package",
        default = "app.morphe.android.gms",
        key = "embeddedGmsCorePackage",
        description = "Select the GmsCore package used by GmsCore mode.",
        values = linkedMapOf(
            "Morphe GmsCore (app.morphe.android.gms)" to "app.morphe.android.gms",
            "ReVanced GmsCore (app.revanced.android.gms)" to "app.revanced.android.gms",
            "Official MicroG (org.microg.gms)" to "org.microg.gms",
            "MG MicroG (com.mgoogle.android.gms)" to "com.mgoogle.android.gms",
            "Google Play Services (com.google.android.gms)" to "com.google.android.gms",
        ),
    )
    val customGmsCorePackage by stringOption(
        title = "Embedded Auth / Stores > Provider and store behavior > Custom GmsCore package (optional)",
        default = "",
        key = "embeddedCustomGmsCorePackage",
        description = "A non-empty valid package overrides the GmsCore dropdown selection.",
    )
    val mainActivityClass by stringOption(
        title = "Embedded Auth / Stores > Provider and store behavior > Main Activity class (optional)",
        default = "",
        key = "embeddedMainActivityClass",
        description = "Inject a GmsCore startup check into this Activity's onCreate method. Leave empty to disable.",
    )
    val storeAvailability by stringOption(
        title = "Embedded Auth / Stores > Provider and store behavior > Store availability",
        default = "real",
        key = "embeddedStoreAvailability",
        description = "Choose which recognized store checks should be reported as available.",
        values = linkedMapOf(
            "Leave app checks unchanged (default)" to "real",
            "Google Play Store" to "google",
            "Amazon Appstore" to "amazon",
            "No recognized store" to "none",
        ),
    )

    val bypassLicenseVerification by booleanOption(title = "Embedded Auth / Stores > Licensing > Bypass License Verification", default = false, key = "embeddedBypassLicenseVerification", description = "Force legacy LicenseChecker verification to allow.")
    val bypassPlayLicenseCheck by booleanOption(title = "Embedded Auth / Stores > Licensing > Bypass Play License Check", default = false, key = "embeddedBypassPlayLicenseCheck", description = "Answer the legacy LVL checkAccess callback as licensed.")
    val showPlayServicesAvailable by booleanOption(title = "Embedded Auth / Stores > Google Play Services > Show Google Play Services as available", default = false, key = "embeddedShowPlayServicesAvailable", description = "Report recognized Google Play Services availability checks as successful.")
    val bypassPlayServicesCheck by booleanOption(title = "Embedded Auth / Stores > Google Play Services > Bypass Google Play Services Check", default = false, key = "embeddedBypassPlayServicesCheck", description = "Bypass recognized Google Play Services availability checks.")
    val forceSignedOut by booleanOption(title = "Embedded Auth / Stores > Google Play Services > Force Google Signed Out", default = false, key = "embeddedForceSignedOut", description = "Make Google sign-in checks report signed out.")
    val suppressPlayGamesSignIn by booleanOption(title = "Embedded Auth / Stores > Google Play Games > Suppress Play Games Sign-In", default = false, key = "embeddedSuppressPlayGamesSignIn", description = "Prevent Play Games sign-in and authentication checks from starting the Google account UI; return a failed Task so the app can disable Play Games integration.")
    val suppressPlayGamesSignInUi by booleanOption(title = "Embedded Auth / Stores > Google Play Games > Suppress Play Games Sign-In UI", default = false, key = "embeddedSuppressPlayGamesSignInUi", description = "Block explicit Play Games sign-in UI requests while leaving authentication-status checks unchanged.")
    val silenceErrors by booleanOption(title = "Embedded Auth / Stores > Google Play Services > Silence Play Services Errors", default = false, key = "embeddedSilenceErrors", description = "Suppress recognized Play Services error UI and messages.")
    val spoofVersion by booleanOption(title = "Embedded Auth / Stores > Google Play Services > Spoof Play Services Version", default = false, key = "embeddedSpoofVersion", description = "Report a high Play Services version.")
    val fixMaps by booleanOption(title = "Embedded Auth / Stores > Google Play Services > Fix Maps on MicroG", default = false, key = "embeddedFixMaps", description = "Report Maps initialization success and notify its initialized callback.")
    val nullLocation by booleanOption(title = "Embedded Auth / Stores > Google Play Services > Null Play Location", default = false, key = "embeddedNullLocation", description = "Return empty Play Services location results.")
    val disableGooglePay by booleanOption(title = "Embedded Auth / Stores > Google Pay > Disable Google Pay", default = false, key = "embeddedDisableGooglePay", description = "Report Google Pay readiness as unavailable.")
    val disableFirebaseAutoInit by booleanOption(title = "Embedded Auth / Stores > Firebase > Disable Firebase Auto Init", default = false, key = "embeddedDisableFirebaseAutoInit", description = "Disable Firebase collection metadata and automatic measurement components.")
    val disableFirebasePerformance by booleanOption(title = "Embedded Auth / Stores > Firebase > Disable Firebase Performance", default = false, key = "embeddedDisableFirebasePerformance", description = "No-op Firebase Performance traces and HTTP metrics.")
    val disableRemoteConfig by booleanOption(title = "Embedded Auth / Stores > Firebase > Disable Remote Config Fetch", default = false, key = "embeddedDisableRemoteConfig", description = "Use bundled Remote Config defaults without fetching updates.")

    dependsOn(controlEmbeddedAuthStoresManagedPatch {
        val selectedProvider = providerMode ?: "real"
        val selectedPackage = customGmsCorePackage.orEmpty().trim().ifEmpty { gmsCorePackage.orEmpty().trim() }
        ControlEmbeddedOptions(
            providerMode = selectedProvider,
            gmsCorePackage = selectedPackage,
            mainActivity = mainActivityClass.orEmpty().trim(),
            storeAvailability = storeAvailability ?: "real",
            bypassLicenseVerification = bypassLicenseVerification == true,
            showPlayServicesAvailable = showPlayServicesAvailable == true,
            bypassPlayServicesCheck = bypassPlayServicesCheck == true,
            bypassPlayLicenseCheck = bypassPlayLicenseCheck == true,
            disableFirebasePerformance = disableFirebasePerformance == true,
            disableGooglePay = disableGooglePay == true,
            disableRemoteConfig = disableRemoteConfig == true,
            forceSignedOut = forceSignedOut == true || selectedProvider == "zero",
            suppressPlayGamesSignIn = suppressPlayGamesSignIn == true,
            suppressPlayGamesSignInUi = suppressPlayGamesSignInUi == true,
            fixMaps = fixMaps == true || selectedProvider == "gmscore",
            nullLocation = nullLocation == true,
            silenceErrors = silenceErrors == true || selectedProvider == "zero",
            spoofVersion = spoofVersion == true,
        )
    })

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val selectedProvider = providerMode ?: "real"
        val selectedPackage = customGmsCorePackage.orEmpty().trim().ifEmpty { gmsCorePackage.orEmpty().trim() }
        if (disableFirebaseAutoInit == true || disableFirebasePerformance == true) {
            val switches = linkedMapOf<String, String>()
            if (disableFirebaseAutoInit == true) switches += mapOf(
                "firebase_analytics_collection_enabled" to "false",
                "firebase_crashlytics_collection_enabled" to "false",
                "firebase_messaging_auto_init_enabled" to "false",
                "firebase_inapp_messaging_auto_data_collection_enabled" to "false",
                "google_analytics_adid_collection_enabled" to "false",
                "firebase_sessions_enabled" to "false",
            )
            if (disableFirebasePerformance == true) switches["firebase_performance_collection_enabled"] = "false"
            var changed = 0
            document("AndroidManifest.xml").use { manifest ->
                val app = manifest.documentElement.applicationOrNull() ?: return@use
                val metadata = app.getElementsByTagName("meta-data")
                for ((name, value) in switches) {
                    val target = (0 until metadata.length).asSequence().mapNotNull { metadata.item(it) as? Element }
                        .firstOrNull { it.getAttributeNS(NS_ANDROID, "name") == name }
                    if (target == null) {
                        manifest.createElement("meta-data").also {
                            it.setAttributeNS(NS_ANDROID, "android:name", name)
                            it.setAttributeNS(NS_ANDROID, "android:value", value)
                            app.appendChild(it)
                        }
                        changed++
                    } else if (target.getAttributeNS(NS_ANDROID, "value") != value) {
                        target.setAttributeNS(NS_ANDROID, "android:value", value)
                        changed++
                    }
                }
            }
            if (disableFirebaseAutoInit == true) {
                val components = setOf("com.google.firebase.provider.FirebaseInitProvider", "com.google.android.gms.measurement.AppMeasurementReceiver", "com.google.android.gms.measurement.AppMeasurementService", "com.google.android.gms.measurement.AppMeasurementJobService", "com.google.android.gms.measurement.AppMeasurementContentProvider")
                document("AndroidManifest.xml").use { manifest ->
                    for (tag in listOf("provider", "receiver", "service")) {
                        val nodes = manifest.getElementsByTagName(tag)
                        for (index in nodes.length - 1 downTo 0) {
                            val item = nodes.item(index) as? Element ?: continue
                            val name = item.getAttributeNS(NS_ANDROID, "name")
                            if (name in components || name.endsWith(".AppMeasurementReceiver") || name.endsWith(".AppMeasurementService")) {
                                item.parentNode?.removeChild(item)
                                changed++
                            }
                        }
                    }
                }
            }
            logger.info("Control Embedded Auth / Stores: Firebase changed $changed manifest item(s)")
        }
        if (selectedProvider == "gmscore" && Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(selectedPackage)) {
            document("AndroidManifest.xml").use { manifest ->
                val root = manifest.documentElement
                val queries = (root.getElementsByTagName("queries").item(0) as? Element)
                    ?: manifest.createElement("queries").also { root.insertBefore(it, root.applicationOrNull()) }
                val packages = queries.getElementsByTagName("package")
                if ((0 until packages.length).none { (packages.item(it) as? Element)?.getAttributeNS(NS_ANDROID, "name") == selectedPackage }) {
                    manifest.createElement("package").also {
                        it.setAttributeNS(NS_ANDROID, "android:name", selectedPackage)
                        queries.appendChild(it)
                    }
                    logger.info("Control Embedded Auth / Stores: added GmsCore query $selectedPackage")
                }
            }
        } else if (selectedProvider == "gmscore") {
            logger.warning("Control Embedded Auth / Stores: invalid GmsCore package ignored")
        }
    }
}
