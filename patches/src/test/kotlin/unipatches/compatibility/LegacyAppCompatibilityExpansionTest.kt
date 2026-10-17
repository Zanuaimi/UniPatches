package unipatches.compatibility

import helpers.manifest.NS_ANDROID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun validatesMainAndPatchObbNamesAgainstPackageAndVersionCode() {
        assertTrue(isExpansionFileForPackage("main.123.org.example.legacy.obb", "org.example.legacy"))
        assertTrue(isExpansionFileForPackage("patch.456.org.example.legacy.obb", "org.example.legacy"))
        assertTrue(isExpansionFileForPackage("main.123.org.example.legacy.obb", "org.example.legacy", "123"))
        assertFalse(isExpansionFileForPackage("main.123.org.example.legacy.obb", "org.example.legacy", "124"))
        assertFalse(isExpansionFileForPackage("patch.123.other.app.obb", "org.example.legacy"))
        assertFalse(isExpansionFileForPackage("main.123.org.example.legacy.obb", null))
        assertFalse(isExpansionFileForPackage("expansion.obb", null))
    }

    @Test
    fun requiresReadableObbPathMatchingApkPackage() {
        val directory = java.nio.file.Files.createTempDirectory("legacy-obb-input-").toFile()
        val valid = File(directory, "main.123.com.glu.gunbros2.obb")
        ZipOutputStream(valid.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/bin/Data/main.dat"))
            zip.write("test".toByteArray())
            zip.closeEntry()
        }
        try {
            assertEquals(valid, validateExpansionObbInput(valid.path, "com.glu.gunbros2", expectedVersionCode = "123"))
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput(valid.path, "com.glu.gunbros2", expectedVersionCode = "124")
            }.message!!.contains("versionCode 124"))
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput("", "com.glu.gunbros2")
            }.message!!.contains("OBB path is required"))
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput(File(directory, "missing.obb").path, "com.glu.gunbros2")
            }.message!!.contains("missing or unreadable"))
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput(valid.path, "com.other.game")
            }.message!!.contains("does not match APK package"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnreadableObbArchivesWithClearMessage() {
        val directory = java.nio.file.Files.createTempDirectory("legacy-corrupt-obb-").toFile()
        val corrupt = File(directory, "main.123.com.glu.gunbros2.obb").apply { writeText("not a zip") }
        try {
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput(corrupt.path, "com.glu.gunbros2")
            }.message!!.contains("not a readable ZIP archive"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun enforcesObbArchiveEntryAndExpandedByteLimits() {
        val directory = java.nio.file.Files.createTempDirectory("legacy-obb-limits-").toFile()
        val source = File(directory, "main.123.com.glu.gunbros2.obb")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/").apply { })
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/a.dat"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/b.dat"))
            zip.write(byteArrayOf(4, 5))
            zip.closeEntry()
        }
        try {
            val base = ExpansionObbLimits(
                maxArchiveBytes = source.length(),
                maxEntries = 4,
                maxEntryBytes = 3,
                maxTotalExpandedBytes = 5,
            )
            assertEquals(source, validateExpansionObbInput(source.path, "com.glu.gunbros2", "123", base))
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput(source.path, "com.glu.gunbros2", "123", base.copy(maxArchiveBytes = source.length() - 1))
            }.message!!.contains("archive limit"))
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput(source.path, "com.glu.gunbros2", "123", base.copy(maxEntries = 2))
            }.message!!.contains("entry limit"))
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput(source.path, "com.glu.gunbros2", "123", base.copy(maxEntryBytes = 2))
            }.message!!.contains("expanded limit"))
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput(source.path, "com.glu.gunbros2", "123", base.copy(maxTotalExpandedBytes = 4))
            }.message!!.contains("total expanded limit"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedObbEntryStreamUsesReadableArchiveError() {
        val directory = java.nio.file.Files.createTempDirectory("legacy-malformed-obb-").toFile()
        val source = File(directory, "main.123.com.glu.gunbros2.obb")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/data.bin"))
            zip.write("payload-that-will-be-truncated".toByteArray())
            zip.closeEntry()
        }
        val bytes = source.readBytes()
        val centralDirectory = bytes.indices.first { index ->
            index + 4 <= bytes.size && bytes[index] == 0x50.toByte() && bytes[index + 1] == 0x4b.toByte() &&
                bytes[index + 2] == 0x01.toByte() && bytes[index + 3] == 0x02.toByte()
        }
        source.writeBytes(bytes.copyOf(centralDirectory))
        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput(source.path, "com.glu.gunbros2", "123")
            }
            assertTrue(error.message!!.contains("not a readable ZIP archive"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsObbEntriesWithCorruptPayloadCrc() {
        val directory = java.nio.file.Files.createTempDirectory("legacy-corrupt-obb-crc-").toFile()
        val source = File(directory, "main.123.com.glu.gunbros2.obb")
        val payload = "crc-checked-obb-payload".toByteArray()
        val crc = java.util.zip.CRC32().apply { update(payload) }.value
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/bin/Data/main.dat").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                this.crc = crc
            })
            zip.write(payload)
            zip.closeEntry()
        }
        val bytes = source.readBytes()
        val payloadOffset = bytes.indices.first { index ->
            index + payload.size <= bytes.size && payload.indices.all { bytes[index + it] == payload[it] }
        }
        bytes[payloadOffset] = (bytes[payloadOffset].toInt() xor 1).toByte()
        source.writeBytes(bytes)
        try {
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                validateExpansionObbInput(source.path, "com.glu.gunbros2", "123")
            }.message!!.contains("ZIP integrity"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reportsWhetherObbMustShipSeparately() {
        val name = "main.123.com.glu.gunbros2.obb"
        assertTrue(expansionObbInstallInstruction("com.glu.gunbros2", name, embedded = true).contains("staged on first launch"))
        assertTrue(expansionObbInstallInstruction("com.glu.gunbros2", name, embedded = false).contains("Android/obb/com.glu.gunbros2/$name"))
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
        val validated = File(rewritten.parentFile, "main.123.com.glu.gunbros2.obb")
        rewritten.copyTo(validated, overwrite = true)
        try {
            assertEquals(validated, validateExpansionObbInput(validated.path, "com.glu.gunbros2", "123"))
        } finally {
            validated.delete()
        }
    }

    @Test
    fun downloaderBypassRequiresDetectedUnityAndEmbeddedObb() {
        validateExpansionDownloaderBypass(false, false, false, "")
        validateExpansionDownloaderBypass(true, true, true, "/tmp/main.1.example.obb")
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            validateExpansionDownloaderBypass(true, false, true, "/tmp/main.1.example.obb")
        }.message!!.contains("no supported Unity engine was detected"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            validateExpansionDownloaderBypass(true, true, false, "/tmp/main.1.example.obb")
        }.message!!.contains("requires OBB embedding"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            validateExpansionDownloaderBypass(true, true, true, "  ")
        }.message!!.contains("requires OBB embedding"))
    }

    @Test
    fun movesAllDownloaderLaunchersWithoutDuplicatingUnityLauncher() {
        val document = launcherDocument(unityHasLauncher = false, downloaderFilters = 2)

        assertEquals(2, moveExpansionDownloaderLauncher(document, Logger.getLogger("test")))
        assertEquals(0, launcherFilterCount(activity(document, "com.google.android.vending.expansion.downloader_impl.DownloaderActivity")))
        assertEquals(2, launcherFilterCount(activity(document, "com.glu.plugins.AUnityInstaller.UnityLauncherActivity")))
        verifyExpansionDownloaderLauncher(document)
    }

    @Test
    fun failsClosedWhenDownloaderLauncherCannotBeTransferred() {
        val document = launcherDocument(unityHasLauncher = false, downloaderFilters = 0)

        val failure = assertThrows(IllegalStateException::class.java) {
            moveExpansionDownloaderLauncher(document, Logger.getLogger("test"))
        }

        assertTrue(failure.message!!.contains("no MAIN/LAUNCHER filter to transfer"))
    }

    @Test
    fun keepsExistingUnityLauncherFilter() {
        val document = launcherDocument(unityHasLauncher = true, downloaderFilters = 1)

        assertEquals(1, moveExpansionDownloaderLauncher(document, Logger.getLogger("test")))
        assertEquals(1, launcherFilterCount(activity(document, "com.glu.plugins.AUnityInstaller.UnityLauncherActivity")))
        verifyExpansionDownloaderLauncher(document)
    }

    private fun launcherDocument(unityHasLauncher: Boolean, downloaderFilters: Int): Document {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()
        val manifest = document.createElement("manifest")
        manifest.setAttribute("package", "org.example.unitygame")
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
