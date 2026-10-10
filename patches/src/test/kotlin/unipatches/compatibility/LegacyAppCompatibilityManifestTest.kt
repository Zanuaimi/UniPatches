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
        val usesSdk = document.createElement("uses-sdk")
        usesSdk.setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "21")
        document.appendChild(usesSdk)
        assertEquals(21, originalTargetSdk(document))
    }

    @Test
    fun originalTargetSdkReturnsNullWhenAbsentOrInvalid() {
        assertNull(originalTargetSdk(newDocument()))
        val document = newDocument()
        val usesSdk = document.createElement("uses-sdk")
        usesSdk.setAttributeNS(NS_ANDROID, "android:targetSdkVersion", "abc")
        document.appendChild(usesSdk)
        assertNull(originalTargetSdk(document))
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
    fun automaticTargetProfilePreservesKnownTargetAndUsesConservativeFallback() {
        assertEquals(16, selectLegacyTargetSdk(16, TARGET_PROFILE_AUTOMATIC, 34))
        assertEquals(30, selectLegacyTargetSdk(30, TARGET_PROFILE_AUTOMATIC, 34))
        assertEquals(34, selectLegacyTargetSdk(34, TARGET_PROFILE_AUTOMATIC, 34))
        assertEquals(27, selectLegacyTargetSdk(null, TARGET_PROFILE_AUTOMATIC, 34))
    }

    @Test
    fun explicitTargetProfilesOverrideCustomValue() {
        assertEquals(27, selectLegacyTargetSdk(16, TARGET_PROFILE_27, 34))
        assertEquals(29, selectLegacyTargetSdk(16, TARGET_PROFILE_29, 34))
        assertEquals(34, selectLegacyTargetSdk(16, TARGET_PROFILE_CUSTOM, 34))
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
        assertFalse(hasPermission(document, QUERY_ALL_PACKAGES))
    }
}
