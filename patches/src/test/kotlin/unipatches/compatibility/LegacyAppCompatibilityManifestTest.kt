package unipatches.compatibility

import helpers.manifest.NS_ANDROID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory

class LegacyAppCompatibilityManifestTest {
    private val logger = Logger.getLogger(this::class.java.name)

    private fun newDocument(): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()

    private fun manifestWith(vararg activityExported: String?): Document {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        document.appendChild(manifest)
        activityExported.forEach { exported ->
            val activity = document.createElement("activity")
            exported?.let { activity.setAttributeNS(NS_ANDROID, "android:exported", it) }
            activity.appendChild(document.createElement("intent-filter"))
            manifest.appendChild(activity)
        }
        return document
    }

    private fun exportedValues(document: Document): List<String> {
        val activities = document.getElementsByTagName("activity")
        return (0 until activities.length).mapNotNull { index ->
            activities.item(index) as? Element
        }.map { it.getAttributeNS(NS_ANDROID, "exported") }
    }

    @Test
    fun originalTargetSdkReadsValue() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        val usesSdk = document.createElement("uses-sdk")
        usesSdk.setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "21")
        manifest.appendChild(usesSdk)
        document.appendChild(manifest)
        assertEquals(21, originalTargetSdk(document))
    }

    @Test
    fun originalTargetSdkReadsLiteralQualifiedAttributeForPreflight() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val manifest = document.createElement("manifest")
        val usesSdk = document.createElement("uses-sdk").apply {
            setAttribute("android:targetSdkVersion", "16")
        }
        manifest.appendChild(usesSdk)
        document.appendChild(manifest)

        assertEquals(16, originalTargetSdk(document))
        assertTrue(updateTargetSdk(document, 24))
        assertEquals(24, originalTargetSdk(document))
        assertEquals("24", usesSdk.getAttribute("android:targetSdkVersion"))
    }

    @Test
    fun originalTargetSdkReturnsNullWhenAbsentOrInvalid() {
        assertNull(originalTargetSdk(newDocument()))
        val document = newDocument()
        val manifest = document.createElement("manifest")
        val usesSdk = document.createElement("uses-sdk")
        usesSdk.setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "abc")
        manifest.appendChild(usesSdk)
        document.appendChild(manifest)
        assertNull(originalTargetSdk(document))
    }

    @Test
    fun updateTargetSdkCreatesUsesSdkBeforeApplicationAndIsIdempotent() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        val application = document.createElement("application")
        manifest.appendChild(application)
        document.appendChild(manifest)

        assertTrue(updateTargetSdk(document, 27))
        assertEquals("uses-sdk", manifest.firstChild.nodeName)
        assertEquals(27, originalTargetSdk(document))
        assertFalse(updateTargetSdk(document, 27))
    }

    @Test
    fun customTargetSdkUpdateReportsAndReadsBackFinalValue() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        val usesSdk = document.createElement("uses-sdk").apply {
            setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "16")
        }
        manifest.appendChild(usesSdk)
        document.appendChild(manifest)

        val result = applyLegacyTargetSdk(document, spoofEnabled = true, customTarget = 34)

        assertEquals(16, result.originalTarget)
        assertEquals(34, result.selectedTarget)
        assertEquals(34, result.finalTarget)
        assertEquals(LegacyTargetSdkOutcome.UPDATED, result.outcome)
    }

    @Test
    fun customSpoofCheckboxPassesThroughAndAppliesExplicitSdk24() {
        val enabled = LegacyCompatibilityPresets.boolean(LegacyCompatibilityPresets.CUSTOM, "spoofTargetSdk", true)
        val document = newDocument()
        val manifest = document.createElement("manifest")
        val usesSdk = document.createElement("uses-sdk").apply {
            setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "16")
        }
        manifest.appendChild(usesSdk)
        document.appendChild(manifest)

        val result = applyLegacyTargetSdk(
            document,
            spoofEnabled = enabled == true,
            customTarget = 24,
        )

        assertEquals(16, result.originalTarget)
        assertEquals(24, result.selectedTarget)
        assertEquals(24, result.finalTarget)
        assertEquals("24", usesSdk.getAttributeNS(NS_ANDROID, "targetSdkVersion"))
    }

    @Test
    fun missingNumericTargetDefaultsToInstallFloor() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        val usesSdk = document.createElement("uses-sdk").apply {
            setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "16")
        }
        manifest.appendChild(usesSdk)
        document.appendChild(manifest)

        val result = applyLegacyTargetSdk(document, spoofEnabled = true, customTarget = null)

        assertEquals(24, result.selectedTarget)
        assertEquals(24, result.finalTarget)
        assertEquals(LegacyTargetSdkOutcome.UPDATED, result.outcome)
        assertEquals("24", usesSdk.getAttributeNS(NS_ANDROID, "targetSdkVersion"))
    }

    @Test
    fun disabledTargetSdkSpoofReportsUnchangedValue() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        val usesSdk = document.createElement("uses-sdk").apply {
            setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "16")
        }
        manifest.appendChild(usesSdk)
        document.appendChild(manifest)

        val result = applyLegacyTargetSdk(document, spoofEnabled = false, customTarget = 34)

        assertEquals(16, result.originalTarget)
        assertNull(result.selectedTarget)
        assertEquals(16, result.finalTarget)
        assertEquals(LegacyTargetSdkOutcome.DISABLED, result.outcome)
    }

    @Test
    fun targetSdkReadAndUpdateSupportPrefixedManifestAndIgnoreNestedDecoys() {
        val document = newDocument()
        val manifest = document.createElementNS("urn:manifest", "m:manifest")
        val application = document.createElementNS("urn:manifest", "m:application")
        val nestedDecoy = document.createElementNS("urn:manifest", "m:uses-sdk").apply {
            setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "16")
        }
        application.appendChild(nestedDecoy)
        manifest.appendChild(application)
        document.appendChild(manifest)

        assertNull(originalTargetSdk(document))
        assertTrue(updateTargetSdk(document, 27))
        assertEquals("m:uses-sdk", manifest.firstChild.nodeName)
        assertEquals(27, originalTargetSdk(document))
        assertEquals("16", nestedDecoy.getAttributeNS(NS_ANDROID, "targetSdkVersion"))
    }

    @Test
    fun addPermissionSetsMaxSdkVersion() {
        val document = newDocument()
        document.appendChild(document.createElement("manifest"))
        assertTrue(addPermission(document, "a.b.C", maxSdkVersion = 32))
        val permissions = document.getElementsByTagName("uses-permission")
        assertEquals(1, permissions.length)
        val permission = permissions.item(0) as Element
        assertEquals("32", permission.getAttributeNS(NS_ANDROID, "maxSdkVersion"))
        assertFalse(addPermission(document, "a.b.C", maxSdkVersion = 32))
        assertEquals(1, permissions.length)
    }

    @Test
    fun manifestHelpersHandleParsedAttributesWithoutDuplicates() {
        for (namespaceAware in listOf(false, true)) {
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = namespaceAware
            }.newDocumentBuilder().parse(
                """
                <manifest xmlns:android="$NS_ANDROID" android:versionCode="123">
                    <uses-permission android:name="$BLUETOOTH_CONNECT"/>
                    <uses-permission-sdk-23 android:name="$FOREGROUND_SERVICE"/>
                    <supports-screens android:smallScreens="false" android:normalScreens="false"
                        android:largeScreens="false" android:xlargeScreens="false" android:anyDensity="false"/>
                    <application android:usesCleartextTraffic="false" android:extractNativeLibs="false"
                        android:networkSecurityConfig="@xml/network_config">
                        <uses-library android:name="$APACHE_LEGACY_LIB" android:required="true"/>
                    </application>
                </manifest>
                """.trimIndent().byteInputStream()
            )
            assertEquals("123", document.documentElement.androidAttribute("versionCode"))
            assertFalse(addPermission(document, BLUETOOTH_CONNECT))
            assertFalse(addPermission(document, FOREGROUND_SERVICE))
            assertEquals(0, addLegacyReviver(document, apache = true, foreground = true, alarms = false, bluetooth = false))
            assertEquals(1, relaxSharedLibraries(document))
            assertEquals(0, relaxSharedLibraries(document))
            assertTrue(setApplicationAttribute(document, "usesCleartextTraffic", "true"))
            assertFalse(setApplicationAttribute(document, "usesCleartextTraffic", "true"))
            assertTrue(setApplicationAttribute(document, "extractNativeLibs", "true"))
            assertEquals(0 to true, supportAllScreens(document))
            assertEquals(0 to false, supportAllScreens(document))

            val application = document.getElementsByTagName("application").item(0) as Element
            assertTrue(application.hasAndroidAttribute("networkSecurityConfig"))
            assertEquals("true", application.androidAttribute("usesCleartextTraffic"))
            assertEquals("true", application.androidAttribute("extractNativeLibs"))
            assertEquals(3, application.attributes.length)
            val library = document.getElementsByTagName("uses-library").item(0) as Element
            assertEquals("false", library.androidAttribute("required"))
            assertEquals(2, library.attributes.length)
            val screens = document.getElementsByTagName("supports-screens").item(0) as Element
            assertEquals(5, screens.attributes.length)
            assertEquals("true", screens.androidAttribute("anyDensity"))
        }
    }

    @Test
    fun missingExportRepairIsRequiredOnlyForTargetSdk31AndNewer() {
        assertFalse(shouldRepairMissingExportFlags(false, 34))
        assertFalse(shouldRepairMissingExportFlags(true, null))
        assertFalse(shouldRepairMissingExportFlags(true, 30))
        assertTrue(shouldRepairMissingExportFlags(true, 31))
        assertTrue(shouldRepairMissingExportFlags(true, 34))
    }

    @Test
    fun repairMissingExportFlagsExposesOnlyLauncherAndPreservesExplicitValues() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        document.appendChild(manifest)

        val launcher = document.createElement("activity")
        val launcherFilter = document.createElement("intent-filter")
        launcherFilter.appendChild(document.createElement("action").apply {
            setAttributeNS(NS_ANDROID, "android:name", "android.intent.action.MAIN")
        })
        launcherFilter.appendChild(document.createElement("category").apply {
            setAttributeNS(NS_ANDROID, "android:name", "android.intent.category.LAUNCHER")
        })
        launcher.appendChild(launcherFilter)
        manifest.appendChild(launcher)

        val otherFiltered = document.createElement("activity").apply {
            appendChild(document.createElement("intent-filter"))
        }
        manifest.appendChild(otherFiltered)

        val explicitFalse = document.createElement("activity").apply {
            setAttributeNS(NS_ANDROID, "android:exported", "false")
            appendChild(document.createElement("intent-filter"))
        }
        manifest.appendChild(explicitFalse)
        manifest.appendChild(document.createElement("activity"))

        assertEquals(2, repairMissingComponentExportFlags(document, logger))
        assertEquals(listOf("true", "false", "false", ""), exportedValues(document))
    }

    @Test
    fun repairMissingFlagsHandlesPrefixedLauncherAliasServiceAndExplicitReceiver() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        document.appendChild(manifest)
        manifest.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:m", "urn:manifest")
        manifest.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:a", NS_ANDROID)

        val alias = document.createElementNS("urn:manifest", "m:activity-alias")
        alias.setAttributeNS(NS_ANDROID, "a:name", "example.LauncherAlias")
        alias.appendChild(document.createElement("intent-filter"))
        val launcherFilter = document.createElementNS("urn:manifest", "m:intent-filter")
        launcherFilter.appendChild(document.createElement("action").apply {
            setAttributeNS(NS_ANDROID, "a:name", "com.example.UNRELATED")
        })
        launcherFilter.appendChild(document.createElementNS("urn:manifest", "m:action").apply {
            setAttributeNS(NS_ANDROID, "a:name", "android.intent.action.MAIN")
        })
        launcherFilter.appendChild(document.createElement("category").apply {
            setAttributeNS(NS_ANDROID, "a:name", "com.example.UNRELATED_CATEGORY")
        })
        launcherFilter.appendChild(document.createElementNS("urn:manifest", "m:category").apply {
            setAttributeNS(NS_ANDROID, "a:name", "android.intent.category.LAUNCHER")
        })
        alias.appendChild(launcherFilter)
        manifest.appendChild(alias)

        val service = document.createElementNS("urn:manifest", "m:service")
        service.appendChild(document.createElementNS("urn:manifest", "m:intent-filter"))
        manifest.appendChild(service)

        val receiver = document.createElementNS("urn:manifest", "m:receiver")
        receiver.setAttributeNS(NS_ANDROID, "a:exported", "false")
        receiver.appendChild(document.createElementNS("urn:manifest", "m:intent-filter"))
        manifest.appendChild(receiver)

        assertEquals(2, repairMissingComponentExportFlags(document, logger))
        assertEquals("true", alias.androidAttribute("exported"))
        assertEquals("false", service.androidAttribute("exported"))
        assertEquals("false", receiver.androidAttribute("exported"))
        assertFalse(alias.hasAndroidAttribute("permission"))
        assertFalse(service.hasAndroidAttribute("permission"))
    }

    @Test
    fun missingExportPolicyFailsClosedForUnverifiedReceiverActions() {
        assertEquals(MissingExportPolicy(true), missingExportPolicy("activity", true))
        assertEquals(MissingExportPolicy(false), missingExportPolicy("activity", false))
        assertEquals(MissingExportPolicy(true), missingExportPolicy("activity-alias", true))
        assertEquals(MissingExportPolicy(false), missingExportPolicy("receiver", false))
        assertEquals(MissingExportPolicy(false), missingExportPolicy("service", false))
    }

    @Test
    fun exportAllActivitiesFindsPrefixedActivityAndPreservesAliasFalse() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        document.appendChild(manifest)
        manifest.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:m", "urn:manifest")
        manifest.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:a", NS_ANDROID)
        val activity = document.createElementNS("urn:manifest", "m:activity")
        activity.appendChild(document.createElementNS("urn:manifest", "m:intent-filter"))
        manifest.appendChild(activity)
        val alias = document.createElementNS("urn:manifest", "m:activity-alias")
        alias.setAttributeNS(NS_ANDROID, "a:exported", "false")
        alias.appendChild(document.createElementNS("urn:manifest", "m:intent-filter"))
        manifest.appendChild(alias)

        assertEquals(1, exportAllActivities(document, logger))
        assertEquals("true", activity.androidAttribute("exported"))
        assertEquals("false", alias.androidAttribute("exported"))
    }

    @Test
    fun exportAllActivitiesFillsMissingButPreservesExplicitFalse() {
        val document = manifestWith(null, "false", "true")
        val changed = exportAllActivities(document, logger)
        assertEquals(1, changed)
        assertEquals(listOf("true", "false", "true"), exportedValues(document))
    }

    @Test
    fun exportAllActivitiesLeavesAlreadyExportedUntouched() {
        val document = manifestWith("true", "true")
        assertEquals(0, exportAllActivities(document, logger))
        assertEquals(listOf("true", "true"), exportedValues(document))
    }

    @Test
    fun exportAllActivitiesDoesNotExposeComponentWithoutIntentFilter() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        document.appendChild(manifest)
        manifest.appendChild(document.createElement("activity"))

        assertEquals(0, exportAllActivities(document, logger))
        assertEquals(listOf(""), exportedValues(document))
    }

    @Test
    fun targetOutsideAcceptedRangeLeavesManifestUnchanged() {
        val document = newDocument()
        document.appendChild(document.createElement("manifest"))
        updateTargetSdk(document, 16)
        for (target in listOf(0, 22, 41)) {
            val result = applyLegacyTargetSdk(document, true, target)
            assertEquals(LegacyTargetSdkOutcome.OUT_OF_RANGE, result.outcome)
            assertEquals(16, originalTargetSdk(document))
        }
    }

    @Test
    fun packageVisibilityAllowlistTrimsAndDeduplicatesExactPackages() {
        assertEquals(
            listOf("com.android.vending", "com.google.android.gms"),
            normalizePackageVisibilityAllowlist(listOf(" com.android.vending ", "", "com.android.vending", "com.google.android.gms")),
        )
    }

    @Test
    fun packageVisibilityAllowlistRejectsUrlsWildcardsAndMalformedNames() {
        for (value in listOf("https://example.com", "com.example.*", "com..example", "single", "com.example/path", "com.example:service", "a".repeat(256))) {
            try {
                normalizePackageVisibilityAllowlist(listOf(value))
                throw AssertionError("Expected invalid package name rejection: $value")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("invalid package name"))
            }
        }
        assertFalse(addPackageVisibilityQuery(newDocument().apply { appendChild(createElement("manifest")) }, "com.example.*"))
    }

    @Test
    fun packageVisibilityQueryIsScopedAndIdempotent() {
        val document = newDocument()
        document.appendChild(document.createElement("manifest"))

        assertTrue(addPackageVisibilityQuery(document, "com.android.vending"))
        assertFalse(addPackageVisibilityQuery(document, "com.android.vending"))
        val packages = document.getElementsByTagName("package")
        assertEquals(1, packages.length)
        assertEquals("com.android.vending", (packages.item(0) as Element).getAttributeNS(NS_ANDROID, "name"))
        assertTrue(hasPackageVisibilityQuery(document, "com.android.vending"))
        assertFalse(hasPackageVisibilityQuery(document, "com.google.android.gms"))
        assertEquals(0, document.getElementsByTagName("uses-permission").length)
    }

    @Test
    fun existingPrefixedPackageQueryIsPreservedAcrossMultipleQueries() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        document.appendChild(manifest)
        manifest.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:m", "urn:manifest")
        manifest.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:a", NS_ANDROID)
        manifest.appendChild(document.createElement("queries"))
        val prefixedQueries = document.createElementNS("urn:manifest", "m:queries")
        val packageElement = document.createElementNS("urn:manifest", "m:package")
        packageElement.setAttributeNS(NS_ANDROID, "a:name", "com.android.vending")
        prefixedQueries.appendChild(packageElement)
        manifest.appendChild(prefixedQueries)

        assertFalse(addPackageVisibilityQuery(document, "com.android.vending"))
        assertEquals(1, document.getElementsByTagNameNS("*", "package").length)
        assertFalse(addPackageVisibilityQuery(document, ""))
    }

    @Test
    fun rejectsMisorderedExistingQueriesEvenWhenRequestedPackageAlreadyExists() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        document.appendChild(manifest)
        manifest.appendChild(document.createElement("application"))
        val queries = document.createElement("queries")
        queries.appendChild(document.createElement("package").apply {
            setAttributeNS(NS_ANDROID, "android:name", "com.android.vending")
        })
        manifest.appendChild(queries)
        try {
            addPackageVisibilityQuery(document, "com.android.vending")
            throw AssertionError("Expected misordered queries to fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("follows <application>"))
        }
    }

    @Test
    fun newScopedQueryIsInsertedBeforeApplicationWithoutBroadPermission() {
        val document = newDocument()
        val manifest = document.createElement("manifest")
        document.appendChild(manifest)
        manifest.appendChild(document.createElement("uses-sdk"))
        val application = document.createElement("application")
        manifest.appendChild(application)

        assertTrue(addPackageVisibilityQuery(document, "com.android.vending"))
        assertEquals("queries", (manifest.childNodes.item(1) as Element).tagName)
        assertEquals("application", (manifest.childNodes.item(2) as Element).tagName)
        assertFalse(hasPermission(document, "android.permission.QUERY_ALL_PACKAGES"))
    }
}
