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
    fun missingTargetSdkIsReportedWhenSpoofingIsDisabled() {
        val manifest = newDocument().apply { appendChild(createElement("manifest")) }

        val finding = legacyManifestPreflightFindings(
            document = manifest,
            spoofTargetSdk = false,
            customTargetSdk = 34,
            repairExportFlags = false,
            exportAllActivityComponents = false,
            playStorePackageVisibility = false,
            embedExpansionObb = false,
            expansionObbPath = null,
        ).single { it.pattern == "uses-sdk targetSdkVersion" }

        assertFalse(finding.detected)
        assertTrue(finding.proposal.contains("effective Spoof Target SDK value is disabled"))
        assertTrue(finding.uncertainty.contains("effective spoofing is disabled"))
    }

    @Test
    fun numericTargetSdkReportsExactSelectedValue() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        val usesSdk = document.createElement("uses-sdk").apply {
            setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "16")
        }
        manifest.appendChild(usesSdk)
        document.appendChild(manifest)

        val finding = legacyManifestPreflightFindings(
            document = document,
            spoofTargetSdk = true,
            customTargetSdk = 34,
            repairExportFlags = false,
            exportAllActivityComponents = false,
            playStorePackageVisibility = false,
            embedExpansionObb = false,
            expansionObbPath = null,
        ).single { it.pattern == "uses-sdk targetSdkVersion" }

        assertTrue(finding.proposal.contains("set targetSdkVersion to 34"))
    }

    @Test
    fun malformedTargetSdkIsReportedAndOnlyReplacedWhenSpoofingIsEnabled() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        val usesSdk = document.createElement("uses-sdk").apply {
            setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "not-a-number")
        }
        manifest.appendChild(usesSdk)
        document.appendChild(manifest)

        fun targetFinding(spoof: Boolean) = legacyManifestPreflightFindings(
            document = document,
            spoofTargetSdk = spoof,
            customTargetSdk = 34,
            repairExportFlags = false,
            exportAllActivityComponents = false,
            playStorePackageVisibility = false,
            embedExpansionObb = false,
            expansionObbPath = null,
        ).single { it.pattern == "uses-sdk targetSdkVersion" }

        val disabled = targetFinding(spoof = false)
        assertTrue(disabled.detected)
        assertTrue(disabled.uncertainty.contains("not a valid integer"))
        assertTrue(disabled.uncertainty.contains("preserved"))

        val enabled = targetFinding(spoof = true)
        assertTrue(enabled.proposal.contains("set targetSdkVersion to 34"))
        assertTrue(enabled.uncertainty.contains("not a valid integer"))
        assertEquals("not-a-number", usesSdk.getAttributeNS(NS_ANDROID, "targetSdkVersion"))
    }

    @Test
    fun obbPreflightRequiresPackageAndManifestVersionCodeMatch() {
        val directory = java.nio.file.Files.createTempDirectory("legacy-obb-preflight-").toFile()
        val correct = java.io.File(directory, "main.123.com.glu.gunbros2.obb")
        val wrongVersion = java.io.File(directory, "main.122.com.glu.gunbros2.obb")
        writeTestObb(correct)
        writeTestObb(wrongVersion)
        val document = newDocument()
        val manifest = document.createElement("manifest").apply {
            setAttribute("package", "com.glu.gunbros2")
            setAttributeNS(NS_ANDROID, "android:versionCode", "123")
        }
        document.appendChild(manifest)
        try {
            fun finding(path: String) = legacyManifestPreflightFindings(
                document = document,
                spoofTargetSdk = false,
                customTargetSdk = 34,
                repairExportFlags = false,
                exportAllActivityComponents = false,
                playStorePackageVisibility = false,
                    embedExpansionObb = true,
                expansionObbPath = path,
            ).single { it.pattern == "embedded expansion OBB" }

            assertTrue(finding(correct.path).detected)
            assertFalse(finding(wrongVersion.path).detected)
            assertTrue(finding(wrongVersion.path).proposal.contains("versionCode 123"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun obbPreflightRejectsMalformedAndEmptyArchives() {
        val directory = java.nio.file.Files.createTempDirectory("legacy-obb-invalid-preflight-").toFile()
        val malformed = java.io.File(directory, "main.123.com.glu.gunbros2.obb").apply { writeText("not a zip") }
        val empty = java.io.File(directory, "patch.123.com.glu.gunbros2.obb")
        writeTestObb(empty, includeEntry = false)
        val document = newDocument()
        val manifest = document.createElement("manifest").apply {
            setAttribute("package", "com.glu.gunbros2")
            setAttributeNS(NS_ANDROID, "android:versionCode", "123")
        }
        document.appendChild(manifest)
        try {
            fun finding(path: String) = legacyManifestPreflightFindings(
                document = document,
                spoofTargetSdk = false,
                customTargetSdk = 34,
                repairExportFlags = false,
                exportAllActivityComponents = false,
                playStorePackageVisibility = false,
                    embedExpansionObb = true,
                expansionObbPath = path,
            ).single { it.pattern == "embedded expansion OBB" }

            val malformedFinding = finding(malformed.path)
            assertFalse(malformedFinding.detected)
            assertTrue(malformedFinding.proposal.contains("not a readable ZIP archive"))
            val emptyFinding = finding(empty.path)
            assertFalse(emptyFinding.detected)
            assertTrue(emptyFinding.proposal.contains("contains no entries"))
        } finally {
            directory.deleteRecursively()
        }
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
            customTargetSdk = 34,
            repairExportFlags = true,
            exportAllActivityComponents = false,
            playStorePackageVisibility = true,
            embedExpansionObb = false,
            expansionObbPath = null,
        )

        assertTrue(findings.any { it.pattern == "uses-sdk targetSdkVersion" && it.detected })
        assertTrue(findings.any { it.pattern == "filtered components missing android:exported" && it.detected })
        assertTrue(findings.any { it.pattern == "package visibility allowlist" && !it.detected })
        assertFalse(service.hasAndroidAttribute("exported"))
        assertEquals(0, manifest.childElementsForTest("queries"))
    }

    @Test
    fun manifestPreflightExplainsWhyMissingExportRepairIsSkippedBelowApi31Target() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        document.appendChild(manifest)
        manifest.appendChild(document.createElement("uses-sdk").apply {
            setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "29")
        })
        manifest.appendChild(document.createElement("activity").apply {
            appendChild(document.createElement("intent-filter"))
        })

        val finding = legacyManifestPreflightFindings(
            document = document,
            spoofTargetSdk = false,
            customTargetSdk = 24,
            repairExportFlags = true,
            exportAllActivityComponents = false,
            playStorePackageVisibility = false,
            embedExpansionObb = false,
            expansionObbPath = null,
        ).single { it.pattern == "filtered components missing android:exported" }

        assertTrue(finding.detected)
        assertTrue(finding.proposal.contains("targetSdkVersion 29 is below 31"))
    }

    private fun writeTestObb(file: java.io.File, includeEntry: Boolean = true) {
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            if (includeEntry) {
                zip.putNextEntry(java.util.zip.ZipEntry("assets/bin/Data/main.dat"))
                zip.write("fixture".toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun org.w3c.dom.Element.childElementsForTest(name: String): Int =
        (0 until childNodes.length).count {
            val child = childNodes.item(it) as? org.w3c.dom.Element
            child?.localName == name || child?.tagName == name
        }
}
