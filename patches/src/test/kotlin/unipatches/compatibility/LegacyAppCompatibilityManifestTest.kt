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
    fun exportAllActivitiesFillsMissingAndOverridesFalse() {
        val document = manifestWith(null, "false", "true")
        val changed = exportAllActivities(document, logger)
        assertEquals(2, changed)
        assertEquals(listOf("true", "true", "true"), exportedValues(document))
    }

    @Test
    fun exportAllActivitiesLeavesAlreadyExportedUntouched() {
        val document = manifestWith("true", "true")
        assertEquals(0, exportAllActivities(document, logger))
        assertEquals(listOf("true", "true"), exportedValues(document))
    }
}
