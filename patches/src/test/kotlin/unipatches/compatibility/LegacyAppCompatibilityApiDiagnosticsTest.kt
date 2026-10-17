package unipatches.compatibility

import helpers.manifest.NS_ANDROID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class LegacyAppCompatibilityApiDiagnosticsTest {
    private fun newDocument(): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()

    private fun manifest(targetSdk: Int? = null): Pair<Document, Element> {
        val document = newDocument()
        val root = document.createElement("manifest")
        document.appendChild(root)
        targetSdk?.let {
            root.appendChild(document.createElement("uses-sdk").apply {
                setAttributeNS(NS_ANDROID, "android:targetSdkVersion", it.toString())
            })
        }
        val app = document.createElement("application")
        root.appendChild(app)
        return document to app
    }

    private fun permission(document: Document, name: String) {
        document.documentElement.appendChild(document.createElement("uses-permission").apply {
            setAttributeNS(NS_ANDROID, "android:name", name)
        })
    }

    private fun findings(document: Document, api: Int, targetSdk: Int?) =
        legacyApiCompatibilityFindings(document, api, targetSdk)

    @Test
    fun runtimePermissionFindingTracksDeviceAndTargetBoundaries() {
        val (document, _) = manifest(targetSdk = 22)
        permission(document, "android.permission.READ_CONTACTS")

        assertFalse(findings(document, 22, 22).any { it.pattern == "runtime permissions" })
        val legacyTarget = findings(document, 23, 22).single { it.pattern == "runtime permissions" }
        assertTrue(legacyTarget.proposal.contains("before raising targetSdk"))
        val modernTarget = findings(document, 23, 23).single { it.pattern == "runtime permissions" }
        assertTrue(modernTarget.proposal.contains("requests and handles denial"))
        assertTrue(modernTarget.apiRange.contains("targetSdk 23"))
    }

    @Test
    fun backgroundLimitsStartAtApi26AndDependOnTarget() {
        val (document, app) = manifest(targetSdk = 25)
        app.appendChild(document.createElement("service"))

        assertFalse(findings(document, 25, 25).any { it.pattern == "background execution" })
        assertTrue(findings(document, 26, 25).single { it.pattern == "background execution" }.proposal.contains("raising targetSdk"))
        assertTrue(findings(document, 26, 26).single { it.pattern == "background execution" }.proposal.contains("implicit broadcasts"))
    }

    @Test
    fun backgroundForegroundServiceStartRuleRequiresApi31AndTarget31() {
        val (document, app) = manifest(targetSdk = 31)
        app.appendChild(document.createElement("service"))

        assertFalse(findings(document, 30, 31).any { it.pattern == "background foreground-service starts" })
        assertTrue(findings(document, 31, 30).none { it.pattern == "background foreground-service starts" })
        val finding = findings(document, 31, 31).single { it.pattern == "background foreground-service starts" }
        assertTrue(finding.proposal.contains("documented exemption"))
        assertTrue(finding.uncertainty.contains("cannot determine"))
    }

    @Test
    fun diagnosticsDistinguishManifestTargetFromPlannedTarget() {
        val (document, _) = manifest(targetSdk = 16)
        permission(document, "android.permission.READ_CONTACTS")

        val finding = legacyApiCompatibilityFindings(document, 23, 16, plannedTargetSdk = 34)
            .single { it.pattern == "runtime permissions" }
        assertTrue(finding.proposal.contains("legacy target"))
        assertTrue(finding.apiRange.contains("manifest targetSdk 16"))
        assertTrue(finding.apiRange.contains("planned targetSdk 34"))
    }

    @Test
    fun scopedStorageFindingStartsOnApi29AndWarnsAtTarget30() {
        val (document, _) = manifest(targetSdk = 29)
        permission(document, "android.permission.WRITE_EXTERNAL_STORAGE")

        assertFalse(findings(document, 28, 29).any { it.pattern == "scoped storage" })
        assertTrue(findings(document, 29, 29).any { it.pattern == "scoped storage" })
        val target30 = findings(document, 30, 30).single { it.pattern == "scoped storage" }
        assertTrue(target30.proposal.contains("ignored for targetSdk 30+"))
    }

    @Test
    fun notificationPermissionUsesApi33BoundaryAndTargetSpecificGuidance() {
        val (document, _) = manifest(targetSdk = 32)
        permission(document, "android.permission.POST_NOTIFICATIONS")

        assertFalse(findings(document, 32, 32).any { it.pattern == "notification permission" })
        assertTrue(findings(document, 33, 32).single { it.pattern == "notification permission" }.proposal.contains("Android controls notification prompt"))
        assertTrue(findings(document, 33, 33).single { it.pattern == "notification permission" }.proposal.contains("request it at runtime"))
    }

    @Test
    fun mediaFindingExplainsAndroid14PartialAccess() {
        val (document, _) = manifest(targetSdk = 34)
        permission(document, "android.permission.READ_MEDIA_IMAGES")

        val finding = findings(document, 34, 34).single { it.pattern == "media permissions and partial photo access" }
        assertTrue(finding.proposal.contains("READ_MEDIA_VISUAL_USER_SELECTED"))
        assertTrue(finding.apiRange.contains("Android 14/API 34"))
    }

    @Test
    fun receiverFindingIsKeyedToApi33AndTarget33() {
        val (document, app) = manifest(targetSdk = 33)
        app.appendChild(document.createElement("receiver"))

        assertFalse(findings(document, 32, 33).any { it.pattern == "broadcast receiver registration" })
        val finding = findings(document, 33, 33).single { it.pattern == "broadcast receiver registration" }
        assertTrue(finding.proposal.contains("RECEIVER_EXPORTED"))
        assertTrue(finding.apiRange.contains("targetSdk 33+"))
    }

    @Test
    fun foregroundServicesAreReportedWithoutGuessingType() {
        val (document, app) = manifest(targetSdk = 34)
        app.appendChild(document.createElement("service").apply {
            setAttributeNS(NS_ANDROID, "android:name", "com.example.SyncService")
        })

        val modern = findings(document, 34, 34).single { it.pattern == "foreground-service declarations" }
        assertTrue(modern.detected)
        assertTrue(modern.proposal.contains("com.example.SyncService"))
        assertTrue(modern.proposal.contains("no type inferred") || modern.uncertainty.contains("required type"))
        assertTrue(modern.uncertainty.contains("cannot prove"))

        val permissionBoundary = findings(document, 28, 28).single { it.pattern == "foreground-service declarations" }
        assertTrue(permissionBoundary.proposal.contains("FOREGROUND_SERVICE"))
    }

    @Test
    fun unknownRuntimeApiDoesNotInventApiSpecificFindings() {
        val (document, _) = manifest(targetSdk = 34)
        val findings = legacyApiCompatibilityFindings(document, null, 34)

        assertEquals(1, findings.size)
        assertEquals("Android runtime API context", findings.single().pattern)
        assertFalse(findings.single().detected)
    }
}
