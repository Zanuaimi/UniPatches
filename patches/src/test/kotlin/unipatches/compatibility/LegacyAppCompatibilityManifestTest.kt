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
}
