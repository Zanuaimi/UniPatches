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

class LegacyAppCompatibilityExpansionTest {
    @Test
    fun validatesConventionalObbNameAgainstPackage() {
        assertTrue(isExpansionFileForPackage("main.123.com.glu.gunbros2.obb", "com.glu.gunbros2"))
        assertFalse(isExpansionFileForPackage("main.123.other.app.obb", "com.glu.gunbros2"))
        assertFalse(isExpansionFileForPackage("main.123.com.glu.gunbros2.obb", null))
        assertFalse(isExpansionFileForPackage("expansion.obb", null))
    }

    @Test
    fun mapsOnlyFlatNativeLibraryEntries() {
        assertEquals("lib/armeabi-v7a/libunity.so", expansionNativeDestination("assets/libs/armeabi-v7a/libunity.so"))
        assertNull(expansionNativeDestination("assets/libs/../libunity.so"))
        assertNull(expansionNativeDestination("assets/libs/armeabi-v7a/subdir/libunity.so"))
        assertNull(expansionNativeDestination("assets/bin/Data/game.dat"))
    }

    @Test
    fun writesExpansionUnderAssetDirectory() {
        assertEquals(
            "assets/unipatch-legacy-expansion/main.123.com.glu.gunbros2.obb",
            expansionAssetPath("main.123.com.glu.gunbros2.obb"),
        )
    }

    @Test
    fun movesAllDownloaderLaunchersWithoutDuplicatingUnityLauncher() {
        val document = launcherDocument(unityHasLauncher = false, downloaderFilters = 2)

        assertEquals(1, moveExpansionDownloaderLauncher(document, Logger.getLogger("test")))
        assertEquals(0, launcherFilterCount(activity(document, "com.google.android.vending.expansion.downloader_impl.DownloaderActivity")))
        assertEquals(1, launcherFilterCount(activity(document, "com.glu.plugins.AUnityInstaller.UnityLauncherActivity")))
    }

    @Test
    fun keepsExistingUnityLauncherFilter() {
        val document = launcherDocument(unityHasLauncher = true, downloaderFilters = 1)

        assertEquals(1, moveExpansionDownloaderLauncher(document, Logger.getLogger("test")))
        assertEquals(1, launcherFilterCount(activity(document, "com.glu.plugins.AUnityInstaller.UnityLauncherActivity")))
    }

    private fun launcherDocument(unityHasLauncher: Boolean, downloaderFilters: Int): Document {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()
        val manifest = document.createElement("manifest")
        val application = document.createElement("application")
        val downloader = document.createElement("activity")
        val unity = document.createElement("activity")
        downloader.setAttributeNS(NS_ANDROID, "android:name", "com.google.android.vending.expansion.downloader_impl.DownloaderActivity")
        unity.setAttributeNS(NS_ANDROID, "android:name", "com.glu.plugins.AUnityInstaller.UnityLauncherActivity")
        manifest.appendChild(application)
        application.appendChild(downloader)
        application.appendChild(unity)
        document.appendChild(manifest)
        repeat(downloaderFilters) { addLauncherFilter(document, downloader) }
        if (unityHasLauncher) addLauncherFilter(document, unity)
        return document
    }

    private fun addLauncherFilter(document: Document, activity: Element) {
        val filter = document.createElement("intent-filter")
        document.createElement("action").also {
            it.setAttributeNS(NS_ANDROID, "android:name", "android.intent.action.MAIN")
            filter.appendChild(it)
        }
        document.createElement("category").also {
            it.setAttributeNS(NS_ANDROID, "android:name", "android.intent.category.LAUNCHER")
            filter.appendChild(it)
        }
        activity.appendChild(filter)
    }

    private fun activity(document: Document, name: String): Element =
        (0 until document.getElementsByTagName("activity").length)
            .mapNotNull { document.getElementsByTagName("activity").item(it) as? Element }
            .first { it.getAttributeNS(NS_ANDROID, "name") == name }

    private fun launcherFilterCount(activity: Element): Int =
        (0 until activity.getElementsByTagName("intent-filter").length).count { index ->
            val filter = activity.getElementsByTagName("intent-filter").item(index) as? Element ?: return@count false
            val actions = filter.getElementsByTagName("action")
            val categories = filter.getElementsByTagName("category")
            val main = (0 until actions.length).any { (actions.item(it) as? Element)?.getAttributeNS(NS_ANDROID, "name") == "android.intent.action.MAIN" }
            val launcher = (0 until categories.length).any { (categories.item(it) as? Element)?.getAttributeNS(NS_ANDROID, "name") == "android.intent.category.LAUNCHER" }
            main && launcher
        }
}
