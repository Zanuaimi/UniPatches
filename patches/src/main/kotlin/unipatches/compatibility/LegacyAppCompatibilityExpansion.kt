package unipatches.compatibility

import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import helpers.manifest.NS_ANDROID
import helpers.startup.StartupHooks
import java.util.logging.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val EXPANSION_ASSET_DIRECTORY = "assets/unipatch-legacy-expansion"
private val expansionFilePattern = Regex("(?:main|patch)\\.(\\d+)\\.([A-Za-z0-9_.]+)\\.obb")
private val nativeEntryPattern = Regex("assets/libs/([^/]+)/([^/]+\\.so)")

internal data class LegacyExpansionOptions(
    val obbPath: String,
    val relocateNativeLibraries: Boolean,
    val embedExpansionObb: Boolean,
    val removeRelocatedNativeLibrariesFromObb: Boolean,
)

internal fun expansionAssetPath(fileName: String): String = "$EXPANSION_ASSET_DIRECTORY/$fileName"

internal fun expansionChecksumAssetPath(fileName: String): String = expansionAssetPath("$fileName.sha256")

internal fun expansionChecksumText(file: File): String = "${file.length()}\n${expansionSha256(file)}\n"

internal fun expansionSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_COPY_BUFFER)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val bytes = digest.digest()
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

internal fun expansionNativeDestination(entryName: String): String? =
    nativeEntryPattern.matchEntire(entryName)?.let { match ->
        val abi = match.groupValues[1]
        val fileName = match.groupValues[2]
        if (abi == "." || abi == ".." || abi.contains('\\') || fileName == "." || fileName == ".." || fileName.contains('\\')) {
            return@let null
        }
        "lib/$abi/$fileName"
    }

internal fun isExpansionFileForPackage(
    fileName: String,
    packageName: String?,
    expectedVersionCode: String? = null,
): Boolean {
    val match = expansionFilePattern.matchEntire(fileName) ?: return false
    return packageName?.takeIf { it.isNotBlank() } == match.groupValues[2] &&
        (expectedVersionCode.isNullOrBlank() || expectedVersionCode == match.groupValues[1])
}

internal data class ExpansionObbLimits(
    val maxArchiveBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxEntries: Int = 100_000,
    val maxEntryBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxTotalExpandedBytes: Long = 4L * 1024 * 1024 * 1024,
)

internal fun validateExpansionObbInput(
    path: String,
    packageName: String,
    expectedVersionCode: String? = null,
    limits: ExpansionObbLimits = ExpansionObbLimits(),
): File {
    val selectedPath = path.trim()
    require(selectedPath.isNotEmpty()) {
        "Legacy compatibility: OBB path is required when expansion OBB options are selected."
    }
    require(packageName.isNotBlank()) {
        "Legacy compatibility: cannot validate expansion OBB because APK package name could not be resolved."
    }
    val source = File(selectedPath)
    require(source.isFile && source.canRead()) {
        "Legacy compatibility: expansion OBB is missing or unreadable: ${source.absolutePath}"
    }
    require(isExpansionFileForPackage(source.name, packageName, expectedVersionCode)) {
        val version = expectedVersionCode?.takeIf { it.isNotBlank() }?.let { " versionCode $it" }.orEmpty()
        "Legacy compatibility: OBB filename does not match APK package $packageName$version: ${source.name}"
    }
    require(limits.maxArchiveBytes > 0 && limits.maxEntries > 0 && limits.maxEntryBytes >= 0 && limits.maxTotalExpandedBytes >= 0) {
        "Legacy compatibility: invalid expansion OBB validation limits."
    }
    require(source.length() <= limits.maxArchiveBytes) {
        "Legacy compatibility: expansion OBB exceeds the ${limits.maxArchiveBytes}-byte archive limit: ${source.name}"
    }
    try {
        ZipFile(source).use { zip ->
            require(zip.entries().hasMoreElements()) {
                "Legacy compatibility: expansion OBB contains no entries: ${source.name}"
            }
            var entryCount = 0
            var fileCount = 0
            var totalExpandedBytes = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount++
                require(entryCount <= limits.maxEntries) {
                    "Legacy compatibility: expansion OBB exceeds the ${limits.maxEntries}-entry limit: ${source.name}"
                }
                if (entry.isDirectory) continue
                fileCount++
                require(entry.size >= 0 && entry.crc >= 0) {
                    "Legacy compatibility: expansion OBB ZIP integrity metadata is incomplete for ${entry.name}."
                }
                require(entry.size <= limits.maxEntryBytes) {
                    "Legacy compatibility: expansion OBB entry ${entry.name} exceeds the ${limits.maxEntryBytes}-byte expanded limit."
                }
                require(entry.size <= limits.maxTotalExpandedBytes - totalExpandedBytes) {
                    "Legacy compatibility: expansion OBB exceeds the ${limits.maxTotalExpandedBytes}-byte total expanded limit."
                }
                val crc = java.util.zip.CRC32()
                var bytesRead = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_COPY_BUFFER)
                    while (true) {
                        val count = try {
                            input.read(buffer)
                        } catch (error: IllegalArgumentException) {
                            throw IOException("Malformed ZIP entry data in ${entry.name}.", error)
                        }
                        if (count < 0) break
                        require(count.toLong() <= limits.maxEntryBytes - bytesRead) {
                            "Legacy compatibility: expansion OBB entry ${entry.name} exceeds the ${limits.maxEntryBytes}-byte expanded limit."
                        }
                        require(count.toLong() <= limits.maxTotalExpandedBytes - totalExpandedBytes - bytesRead) {
                            "Legacy compatibility: expansion OBB exceeds the ${limits.maxTotalExpandedBytes}-byte total expanded limit."
                        }
                        crc.update(buffer, 0, count)
                        bytesRead += count
                    }
                }
                require(bytesRead == entry.size && crc.value == entry.crc) {
                    "Legacy compatibility: expansion OBB ZIP integrity check failed for ${entry.name}."
                }
                totalExpandedBytes += bytesRead
            }
            require(fileCount > 0) {
                "Legacy compatibility: expansion OBB contains no files: ${source.name}"
            }
        }
    } catch (error: IOException) {
        throw IllegalArgumentException(
            "Legacy compatibility: expansion OBB is not a readable ZIP archive or failed ZIP integrity check: ${source.name}",
            error,
        )
    }
    return source
}

internal fun expansionObbInstallInstruction(packageName: String, fileName: String, embedded: Boolean): String =
    if (embedded) {
        "Embedded OBB will be staged on first launch to Android/obb/$packageName/$fileName; no separate OBB install is required."
    } else {
        "Install $fileName at <external-storage>/Android/obb/$packageName/$fileName alongside the patched APK."
    }

/** Copies an OBB while omitting only entries explicitly marked as relocated. */
internal fun rewriteExpansionObb(source: File, output: File, removedEntries: Set<String>) {
    output.parentFile?.mkdirs()
    ZipFile(source).use { input ->
        output.outputStream().buffered().use { fileOutput ->
            ZipOutputStream(fileOutput).use { outputZip ->
                input.entries().asSequence().forEach { entry ->
                    if (entry.name in removedEntries) return@forEach
                    val copy = ZipEntry(entry.name).apply {
                        time = entry.time
                        comment = entry.comment
                        extra = entry.extra
                        when (entry.method) {
                            ZipEntry.STORED -> {
                                method = ZipEntry.STORED
                                size = entry.size
                                crc = entry.crc
                            }
                            ZipEntry.DEFLATED -> method = ZipEntry.DEFLATED
                        }
                    }
                    outputZip.putNextEntry(copy)
                    if (!entry.isDirectory) {
                        input.getInputStream(entry).use { stream -> stream.copyTo(outputZip, DEFAULT_COPY_BUFFER) }
                    }
                    outputZip.closeEntry()
                }
            }
        }
    }
}

internal fun stageExpansionNativeLibraries(
    zip: ZipFile,
    nativeEntries: List<Pair<String, String>>,
    outputForDestination: (String) -> File,
) {
    for ((entryName, destination) in nativeEntries) {
        val output = outputForDestination(destination)
        output.parentFile?.mkdirs()
        zip.getInputStream(zip.getEntry(entryName)).use { input ->
            output.outputStream().use { outputStream -> input.copyTo(outputStream, DEFAULT_COPY_BUFFER) }
        }
    }
}

/**
 * Stages an optional main or patch expansion OBB, relocates full native libraries stored
 * under the conventional assets/libs/<abi>/ path, and can omit them from embedded output.
 * All operations happen at patch time; no host path is retained.
 */
internal fun legacyExpansionFilesPatch(optionsProvider: () -> LegacyExpansionOptions) = rawResourcePatch(
    name = null,
    description = "Internal legacy expansion-file compatibility phase.",
    default = false,
) {
    dependsOn(StartupHooks.resolveRealApplicationPatch)

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val options = optionsProvider()
        if (!options.relocateNativeLibraries && !options.embedExpansionObb && !options.removeRelocatedNativeLibrariesFromObb) {
            return@execute
        }
        require(
            !options.removeRelocatedNativeLibrariesFromObb || (options.relocateNativeLibraries && options.embedExpansionObb),
        ) {
            "Legacy compatibility: removing relocated OBB libraries requires both native-library relocation and OBB embedding."
        }
        val packageName = StartupHooks.resolvedPackageName
            ?: error("Legacy compatibility: cannot validate expansion OBB because APK package name could not be resolved.")
        val source = validateExpansionObbInput(options.obbPath, packageName, StartupHooks.resolvedVersionCode)

        val nativeEntries = mutableListOf<Pair<String, String>>()

        ZipFile(source).use { zip ->
            val entries = zip.entries().asSequence().toList()
            if (entries.isEmpty()) error("Legacy compatibility: expansion OBB contains no entries: ${source.name}")
            for (entry in entries) {
                val destination = expansionNativeDestination(entry.name) ?: continue
                require(!entry.isDirectory && entry.size > 0) {
                    "Legacy compatibility: invalid native library entry in OBB: ${entry.name}"
                }
                nativeEntries += entry.name to destination
            }
            require(nativeEntries.map { it.second }.toSet().size == nativeEntries.size) {
                "Legacy compatibility: expansion OBB contains duplicate native-library destinations."
            }
            if (options.relocateNativeLibraries) {
                require(nativeEntries.isNotEmpty()) {
                    "Legacy compatibility: requested native-library relocation, but OBB contains no assets/libs/<abi>/*.so entries."
                }
                stageExpansionNativeLibraries(zip, nativeEntries) { destination -> get(destination, false) }
                nativeEntries.forEach { (entryName, destination) ->
                    logger.info("Legacy compatibility: relocated OBB native library $entryName to $destination.")
                }
            }
        }

        val rewriteEmbeddedObb = options.removeRelocatedNativeLibrariesFromObb
        if (options.embedExpansionObb) {
            val output = get(expansionAssetPath(source.name), false)
            if (rewriteEmbeddedObb) {
                rewriteExpansionObb(source, output, nativeEntries.map { it.first }.toSet())
            } else {
                output.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    output.outputStream().use { outputStream -> input.copyTo(outputStream, DEFAULT_COPY_BUFFER) }
                }
            }
            validateExpansionObbInput(output.absolutePath, packageName, StartupHooks.resolvedVersionCode)
            val sourceSha256 = expansionSha256(source)
            val checksumAsset = get(expansionChecksumAssetPath(source.name), false)
            checksumAsset.parentFile?.mkdirs()
            val checksumText = expansionChecksumText(output)
            checksumAsset.writeText(checksumText)
            val outputSha256 = checksumText.trimEnd().substringAfter('\n')
            logger.info(
                "Legacy compatibility: OBB embedded source=${source.name} sourceBytes=${source.length()} sourceSha256=$sourceSha256 embeddedBytes=${output.length()} embeddedSha256=$outputSha256 relocatedNativeLibraries=${if (rewriteEmbeddedObb) nativeEntries.size else 0}; embedded ZIP entries and CRCs verified.",
            )
            logger.info("Legacy compatibility install: " + expansionObbInstallInstruction(packageName, source.name, embedded = true))
        } else {
            val sourceSha256 = expansionSha256(source)
            logger.info("Legacy compatibility: OBB validated for separate install name=${source.name} bytes=${source.length()} sha256=$sourceSha256.")
            logger.info("Legacy compatibility install: " + expansionObbInstallInstruction(packageName, source.name, embedded = false))
        }
    }
}

/**
 * Moves the standard Google expansion-downloader launcher to the GLU AUnityInstaller SDK activity.
 * This exact component fingerprint is shared by APKs using that library and is not package-gated.
 */
private fun Element.isExpansionLauncherFilter(): Boolean {
    if ((localName ?: tagName.substringAfter(':')) != "intent-filter") return false
    val actions = getElementsByTagName("action")
    val categories = getElementsByTagName("category")
    val main = (0 until actions.length).any { (actions.item(it) as? Element)?.androidAttribute("name") == "android.intent.action.MAIN" }
    val launcher = (0 until categories.length).any { (categories.item(it) as? Element)?.androidAttribute("name") == "android.intent.category.LAUNCHER" }
    return main && launcher
}

internal fun validateExpansionDownloaderBypass(
    selected: Boolean,
    unityDetected: Boolean,
    obbEmbeddingSelected: Boolean,
    obbPath: String,
) {
    if (!selected) return
    require(unityDetected) {
        "Legacy compatibility: expansion downloader bypass was selected, but no supported Unity engine was detected."
    }
    require(obbEmbeddingSelected && obbPath.isNotBlank()) {
        "Legacy compatibility: expansion downloader bypass requires OBB embedding and a valid selected OBB file."
    }
}

internal fun verifyExpansionDownloaderLauncher(document: Document) {
    val application = document.documentElement?.getElementsByTagName("application")?.item(0) as? Element
        ?: error("Legacy compatibility: downloader bypass readback failed; manifest application is missing.")
    fun activity(name: String) = (0 until application.childNodes.length)
        .mapNotNull { application.childNodes.item(it) as? Element }
        .firstOrNull { it.tagName == "activity" && it.androidAttribute("name") == name }
    val downloader = activity("com.google.android.vending.expansion.downloader_impl.DownloaderActivity")
        ?: error("Legacy compatibility: downloader bypass readback failed; DownloaderActivity is missing.")
    val unity = activity("com.glu.plugins.AUnityInstaller.UnityLauncherActivity")
        ?: error("Legacy compatibility: downloader bypass readback failed; UnityLauncherActivity is missing.")
    check(!downloader.isLauncherComponent() && unity.isLauncherComponent()) {
        "Legacy compatibility: downloader bypass readback failed; expected DownloaderActivity without MAIN/LAUNCHER and UnityLauncherActivity with MAIN/LAUNCHER."
    }
}

internal fun moveExpansionDownloaderLauncher(document: Document, logger: Logger): Int {
    val application = document.documentElement?.getElementsByTagName("application")?.item(0) as? Element
        ?: error("Legacy compatibility: cannot bypass downloader; manifest application is missing.")
    fun activity(name: String) = (0 until application.childNodes.length)
        .mapNotNull { application.childNodes.item(it) as? Element }
        .firstOrNull { it.tagName == "activity" && it.androidAttribute("name") == name }
    val activityNames = (0 until application.childNodes.length)
        .mapNotNull { application.childNodes.item(it) as? Element }
        .filter { it.tagName == "activity" }
        .map { it.androidAttribute("name") }
    val downloader = activity("com.google.android.vending.expansion.downloader_impl.DownloaderActivity")
        ?: error("Legacy compatibility: cannot bypass downloader; DownloaderActivity is missing. Activities=$activityNames")
    val unity = activity("com.glu.plugins.AUnityInstaller.UnityLauncherActivity")
        ?: error("Legacy compatibility: cannot bypass downloader; UnityLauncherActivity is missing.")
    val launcherFilters = (0 until downloader.childNodes.length)
        .mapNotNull { downloader.childNodes.item(it) as? Element }
        .filter(Element::isExpansionLauncherFilter)
    check(launcherFilters.isNotEmpty() || unity.isLauncherComponent()) {
        "Legacy compatibility: cannot bypass downloader; DownloaderActivity has no MAIN/LAUNCHER filter to transfer and UnityLauncherActivity is not already the launcher."
    }
    if (!unity.isLauncherComponent()) {
        launcherFilters.forEach { filter ->
            downloader.removeChild(filter)
            unity.appendChild(filter)
        }
    } else {
        launcherFilters.forEach(downloader::removeChild)
    }
    unity.setAttributeNS(NS_ANDROID, "android:exported", "true")
    verifyExpansionDownloaderLauncher(document)
    logger.info("Legacy compatibility: transferred downloader launcher filter and verified manifest readback; UnityLauncherActivity is launcher.")
    return launcherFilters.size
}

private const val DEFAULT_COPY_BUFFER = 64 * 1024
