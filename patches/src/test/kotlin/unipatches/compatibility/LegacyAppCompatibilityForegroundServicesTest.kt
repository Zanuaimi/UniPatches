package unipatches.compatibility

import helpers.manifest.NS_ANDROID
import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class LegacyAppCompatibilityForegroundServicesTest {
    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

    private fun manifest(extra: String = "") = parse(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
          $extra
          <application>
            <service android:name=".SyncService" android:foregroundServiceType="dataSync" />
          </application>
        </manifest>
        """.trimIndent(),
    )

    private fun mapping(type: String = "mediaPlayback") = ForegroundServiceTypeMapping("com.example.app.SyncService", listOf(type))

    @Test
    fun `normalizes explicit component mappings and trims deduplicates types`() {
        assertEquals(
            listOf(ForegroundServiceTypeMapping("com.example.app.SyncService", listOf("dataSync", "mediaPlayback"))),
            normalizeForegroundServiceTypeMappings(
                listOf(" .SyncService = dataSync|dataSync|mediaPlayback "),
                "com.example.app",
            ),
        )
    }

    @Test
    fun `rejects malformed or duplicate component mappings`() {
        listOf(
            listOf("=dataSync"),
            listOf("com.example.app.SyncService=dataSync=location"),
            listOf("com.example.app.SyncService=unknownType"),
            listOf("com.example.app.SyncService=dataSync", " .SyncService=location"),
            listOf("com.example.app.SyncService=specialUse"),
            listOf("com.example.app.SyncService=systemExempted"),
            listOf("x".repeat(513)),
        ).forEach { entries ->
            try {
                normalizeForegroundServiceTypeMappings(entries, "com.example.app")
                fail("Expected invalid FGS mapping to fail: $entries")
            } catch (_: IllegalArgumentException) {
                // Fail closed on unverified mappings.
            }
        }
    }

    @Test
    fun `phone call type requires declared own calls permission`() {
        try {
            normalizeForegroundServiceTypeMappings(listOf(".SyncService=phoneCall"), "com.example.app")
            fail("Expected MANAGE_OWN_CALLS requirement")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertEquals(
            listOf(mapping("phoneCall")),
            normalizeForegroundServiceTypeMappings(
                listOf(".SyncService=phoneCall"),
                "com.example.app",
                setOf("android.permission.MANAGE_OWN_CALLS"),
            ),
        )
    }

    @Test
    fun `merges selected type and required unbounded permissions`() {
        val document = manifest()
        assertEquals(3, applyForegroundServiceTypeMappings(document, listOf(mapping())))
        val service = document.getElementsByTagName("service").item(0) as org.w3c.dom.Element
        assertEquals("dataSync|mediaPlayback", service.androidAttribute("foregroundServiceType"))
        assertTrue(declaredManifestPermissionNames(document).containsAll(setOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        )))
        verifyForegroundServiceTypeMappings(document, listOf(mapping()))
        assertEquals(0, applyForegroundServiceTypeMappings(document, listOf(mapping())))
    }

    @Test
    fun `fails for missing duplicate or ambiguous services`() {
        val missing = manifest().also { (it.getElementsByTagName("service").item(0) as org.w3c.dom.Node).parentNode.removeChild(it.getElementsByTagName("service").item(0)) }
        val duplicate = manifest().also {
            val application = it.getElementsByTagName("application").item(0)
            application.appendChild(it.getElementsByTagName("service").item(0).cloneNode(true))
        }
        listOf(missing, duplicate).forEach { document ->
            try {
                applyForegroundServiceTypeMappings(document, listOf(mapping()))
                fail("Expected ambiguous component mapping to fail")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `rejects max sdk permission and preserves an existing unbounded one`() {
        val maxSdk = manifest("<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE\" android:maxSdkVersion=\"33\" />")
        try {
            applyForegroundServiceTypeMappings(maxSdk, listOf(mapping()))
            fail("Expected maxSdkVersion permission to fail closed")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        val alreadyGranted = manifest("<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE\" />")
        assertEquals(2, applyForegroundServiceTypeMappings(alreadyGranted, listOf(mapping())))
        val children = alreadyGranted.documentElement.childNodes
        val elementNames = (0 until children.length).mapNotNull { (children.item(it) as? org.w3c.dom.Element)?.tagName?.substringAfter(':') }
        assertTrue(elementNames.indexOf("uses-permission") < elementNames.indexOf("application"))
    }

    @Test
    fun `phone call mapping requires unbounded own calls permission`() {
        val document = manifest("<uses-permission android:name=\"android.permission.MANAGE_OWN_CALLS\" android:maxSdkVersion=\"33\" />")
        try {
            applyForegroundServiceTypeMappings(document, listOf(mapping("phoneCall")))
            fail("Expected maxSdkVersion MANAGE_OWN_CALLS to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `mapping readback fails if service type or permission missing`() {
        val document = manifest()
        try {
            verifyForegroundServiceTypeMappings(document, listOf(mapping()))
            fail("Expected missing final manifest output to fail")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
