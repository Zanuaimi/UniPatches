package unipatches.compatibility

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class LegacySdkVersionManifestTest {
    /** The patcher parses AndroidManifest.xml namespace-aware, so android:* attributes
     *  are only reachable through getAttributeNS. Mirror that here. */
    private fun parse(manifest: String): Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(manifest.toByteArray()))

    private fun usesSdk(document: Document): Element =
        document.documentElement.getElementsByTagName("uses-sdk").item(0) as Element

    private fun Element.attribute(name: String): String =
        getAttributeNS(helpers.manifest.NS_ANDROID, name)

    @Test
    fun lowersMinSdkOnExistingUsesSdk() {
        val document = parse(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-sdk android:minSdkVersion="32" android:targetSdkVersion="34" />
                <application />
            </manifest>
            """.trimIndent(),
        )

        assertTrue(updateMinSdk(document, 28))
        val sdk = usesSdk(document)
        assertEquals("28", sdk.attribute("minSdkVersion"))
        assertEquals("34", sdk.attribute("targetSdkVersion"))
    }

    @Test
    fun createsUsesSdkWhenManifestHasNone() {
        val document = parse(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application />
            </manifest>
            """.trimIndent(),
        )

        assertTrue(updateMinSdk(document, 28))
        assertEquals("28", usesSdk(document).attribute("minSdkVersion"))
    }

    @Test
    fun minSdkAndTargetSdkAreWrittenIndependently() {
        val document = parse(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-sdk android:minSdkVersion="32" android:targetSdkVersion="32" />
                <application />
            </manifest>
            """.trimIndent(),
        )

        assertTrue(updateMinSdk(document, 28))
        assertTrue(updateTargetSdk(document, 28))
        val sdk = usesSdk(document)
        assertEquals("28", sdk.attribute("minSdkVersion"))
        assertEquals("28", sdk.attribute("targetSdkVersion"))
    }

    @Test
    fun reportsNoChangeWhenMinSdkAlreadyMatches() {
        val document = parse(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="34" />
                <application />
            </manifest>
            """.trimIndent(),
        )

        assertFalse(updateMinSdk(document, 28))
    }
}
