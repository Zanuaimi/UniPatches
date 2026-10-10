package unipatches.compatibility

import helpers.manifest.NS_ANDROID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory

class LegacyAppCompatibilityPreflightTest {
    private fun newDocument(): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()

    @Test
    fun findingFormatIncludesApiConfidenceProposalAndSanitizedUncertainty() {
        val line = formatLegacyCompatibilityPreflightFinding(
            LegacyCompatibilityPreflightFinding(
                pattern = "receiver" + 1.toChar() + "filter",
                detected = true,
                proposal = "add exported flag",
                apiRange = "Android API 33+",
                confidence = "exact",
                uncertainty = "runtime action may vary",
            ),
        )
        assertTrue(line.contains("detected=true"))
        assertTrue(line.contains("api=Android API 33+"))
        assertTrue(line.contains("confidence=exact"))
        assertTrue(line.contains("proposal=add exported flag"))
        assertFalse(line.any { Character.isISOControl(it) })
    }

    @Test
    fun diagnosticsOnlyGateDisablesEveryMutation() {
        assertFalse(shouldApplyLegacyCompatibilityMutations(diagnosticsOnly = true))
        assertTrue(shouldApplyLegacyCompatibilityMutations(diagnosticsOnly = false))
    }

    @Test
    fun manifestPreflightReportsFindingsWithoutEditingTheDocument() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        manifest.setAttribute("package", "com.example.legacy")
        document.appendChild(manifest)
        val usesSdk = document.createElement("uses-sdk")
        usesSdk.setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "29")
        manifest.appendChild(usesSdk)
        val service = document.createElement("service")
        service.appendChild(document.createElement("intent-filter"))
        manifest.appendChild(service)

        val findings = legacyManifestPreflightFindings(
            document = document,
            spoofTargetSdk = true,
            targetSdkProfile = TARGET_PROFILE_AUTOMATIC,
            customTargetSdk = 34,
            repairExportFlags = true,
            exportAllActivityComponents = false,
            playStorePackageVisibility = true,
            bypassPackageVisibility = false,
            embedExpansionObb = false,
            expansionObbPath = null,
        )

        assertTrue(findings.any { it.pattern == "uses-sdk targetSdkVersion" && it.detected })
        assertTrue(findings.any { it.pattern == "filtered components missing android:exported" && it.detected })
        assertTrue(findings.any { it.pattern == "Google Play package visibility" && !it.detected })
        assertFalse(service.hasAndroidAttribute("exported"))
        assertEquals(0, manifest.childElementsForTest("queries"))
    }

    private fun org.w3c.dom.Element.childElementsForTest(name: String): Int =
        (0 until childNodes.length).count {
            val child = childNodes.item(it) as? org.w3c.dom.Element
            child?.localName == name || child?.tagName == name
        }
}
