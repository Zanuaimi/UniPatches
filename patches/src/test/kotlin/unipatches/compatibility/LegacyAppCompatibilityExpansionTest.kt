package unipatches.compatibility

import helpers.manifest.NS_ANDROID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class LegacyAppCompatibilityExpansionTest {
    @Test
    fun checksumMetadataDescribesTheFinalObbAsset() {
        val file = File.createTempFile("legacy-obb-checksum-", ".obb")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", expansionSha256(file))
            assertEquals("3\nba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\n", expansionChecksumText(file))
            assertEquals("assets/unipatch-legacy-expansion/main.123.com.glu.gunbros2.obb.sha256", expansionChecksumAssetPath("main.123.com.glu.gunbros2.obb"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun validatesMainAndPatchObbNamesAgainstPackage() {
        assertTrue(isExpansionFileForPackage("main.123.org.example.legacy.obb", "org.example.legacy"))
        assertTrue(isExpansionFileForPackage("patch.456.org.example.legacy.obb", "org.example.legacy"))
        assertFalse(isExpansionFileForPackage("patch.123.other.app.obb", "org.example.legacy"))
        assertFalse(isExpansionFileForPackage("main.123.org.example.legacy.obb", null))
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
            "assets/unipatch-legacy-expansion/patch.456.org.example.legacy.obb",
            expansionAssetPath("patch.456.org.example.legacy.obb"),
        )
    }

    @Test
    fun rewritesObbWithoutRelocatedEntriesAndLeavesSourceUntouched() {
        val source = File.createTempFile("legacy-source-", ".obb").apply { deleteOnExit() }
        val rewritten = File.createTempFile("legacy-rewritten-", ".obb").apply { deleteOnExit() }
        val unityLibrary = "assets/libs/armeabi-v7a/libunity.so"
        val monoLibrary = "assets/libs/armeabi-v7a/libmono.so"
        val data = "assets/bin/Data/game.dat"

        ZipOutputStream(source.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(unityLibrary))
            zip.write("unity".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(monoLibrary))
            zip.write("mono".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/bin/Data/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(data))
            zip.write("game".toByteArray())
            zip.closeEntry()
        }
        val originalSource = source.readBytes()
        val stagedApkRoot = java.nio.file.Files.createTempDirectory("legacy-apk-stage-").toFile().apply { deleteOnExit() }
        val nativeEntries = listOf(
            unityLibrary to requireNotNull(expansionNativeDestination(unityLibrary)),
            monoLibrary to requireNotNull(expansionNativeDestination(monoLibrary)),
        )
        ZipFile(source).use { zip ->
            stageExpansionNativeLibraries(zip, nativeEntries) { destination -> File(stagedApkRoot, destination) }
        }

        assertEquals("lib/armeabi-v7a/libunity.so", expansionNativeDestination(unityLibrary))
        assertEquals("lib/armeabi-v7a/libmono.so", expansionNativeDestination(monoLibrary))
        assertEquals("unity", File(stagedApkRoot, "lib/armeabi-v7a/libunity.so").readText())
        assertEquals("mono", File(stagedApkRoot, "lib/armeabi-v7a/libmono.so").readText())

        rewriteExpansionObb(source, rewritten, setOf(unityLibrary, monoLibrary))
        assertArrayEquals(originalSource, source.readBytes())
        ZipFile(rewritten).use { zip ->
            assertNull(zip.getEntry(unityLibrary))
            assertNull(zip.getEntry(monoLibrary))
            assertNotNull(zip.getEntry("assets/bin/Data/"))
            assertEquals("game", zip.getInputStream(zip.getEntry(data)).use { it.readBytes().toString(Charsets.UTF_8) })
        }
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
