package unipatches.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class PermissionGuardPatchTest {
    private fun newDocument(manifestPermissions: List<String>) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .newDocument().apply {
            val manifest = createElement("manifest")
            val application = createElement("application")
            manifestPermissions.forEach { name ->
                val entry = createElement("uses-permission")
                entry.setAttribute("android:name", name)
                manifest.appendChild(entry)
            }
            manifest.appendChild(application)
            appendChild(manifest)
        }

    private fun names(document: org.w3c.dom.Document): List<String> =
        document.documentElement.getElementsByTagName("uses-permission").let { entries ->
            (0 until entries.length).mapNotNull { index ->
                (entries.item(index) as? org.w3c.dom.Element)?.getAttribute("android:name")
            }
        }

    @Test
    fun noSelectionRemovesNothing() {
        val document = newDocument(listOf("android.permission.CAMERA"))
        assertEquals(0, removeGuardedPermissions(document, emptySet()))
        assertEquals(listOf("android.permission.CAMERA"), names(document))
    }

    @Test
    fun cameraGroupRemovesCameraOnly() {
        val document = newDocument(
            listOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.INTERNET",
            ),
        )
        assertEquals(1, removeGuardedPermissions(document, setOf("camera")))
        assertEquals(listOf("android.permission.RECORD_AUDIO", "android.permission.INTERNET"), names(document))
    }

    @Test
    fun locationGroupRemovesAllLocationVariants() {
        val document = newDocument(
            listOf(
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION",
                "android.permission.INTERNET",
            ),
        )
        assertEquals(3, removeGuardedPermissions(document, setOf("location")))
        assertEquals(listOf("android.permission.INTERNET"), names(document))
    }

    @Test
    fun nearbyDevicesDoesNotRemoveLegacyBluetooth() {
        val document = newDocument(
            listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH",
            ),
        )
        assertEquals(1, removeGuardedPermissions(document, setOf("nearbyDevices")))
        assertEquals(listOf("android.permission.BLUETOOTH"), names(document))
    }

    @Test
    fun unknownGroupIsIgnored() {
        val document = newDocument(listOf("android.permission.CAMERA"))
        assertEquals(0, removeGuardedPermissions(document, setOf("root")))
        assertEquals(1, names(document).size)
    }

    @Test
    fun phoneGroupIncludesVoicemailDeclaration() {
        assertTrue(permissionGroups.getValue("phone").contains("android.permission.ADD_VOICEMAIL"))
    }
}
