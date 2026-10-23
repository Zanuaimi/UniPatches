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
private val nativeEntryPattern = Regex("assets/libs/([A-Za-z0-9_-]+)/([A-Za-z0-9_.+-]+\\.so)")
private val apkNativeEntryPattern = Regex("lib/([A-Za-z0-9_-]+)/([A-Za-z0-9_.+-]+\\.so)")

internal data class LegacyExpansionOptions(
    val obbPath: String,
    val relocateNativeLibraries: Boolean,
    val embedExpansionObb: Boolean,
    val removeRelocatedNativeLibrariesFromObb: Boolean,
)

internal fun expansionAssetPath(fileName: String): String = "$EXPANSION_ASSET_DIRECTORY/$fileName"

internal fun isSafeApkNativeLibraryPath(path: String): Boolean = apkNativeEntryPattern.matches(path)

internal fun isSafeExpansionNativeDestination(source: String, destination: String): Boolean =
    (expansionNativeDestination(source) == destination && isSafeApkNativeLibraryPath(destination)) ||
        (source == destination && expansionNativeDestination(source) != null)

private fun safeExpansionText(value: String): String =
    value.map { if (Character.isISOControl(it)) ' ' else it }.joinToString("").take(240)

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
    return sha256Hex(digest.digest())
}

private fun sha256Hex(bytes: ByteArray): String {
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
        "Legacy compatibility: expansion OBB is missing or unreadable: ${safeExpansionText(source.name)}"
    }
    require(isExpansionFileForPackage(source.name, packageName, expectedVersionCode)) {
        val version = expectedVersionCode?.takeIf { it.isNotBlank() }?.let { " versionCode $it" }.orEmpty()
        "Legacy compatibility: OBB filename does not match APK package $packageName$version: ${safeExpansionText(source.name)}"
    }
    require(limits.maxArchiveBytes > 0 && limits.maxEntries > 0 && limits.maxEntryBytes >= 0 && limits.maxTotalExpandedBytes >= 0) {
        "Legacy compatibility: invalid expansion OBB validation limits."
    }
    require(source.length() <= limits.maxArchiveBytes) {
        "Legacy compatibility: expansion OBB exceeds the ${limits.maxArchiveBytes}-byte archive limit: ${safeExpansionText(source.name)}"
    }
    try {
        ZipFile(source).use { zip ->
            require(zip.entries().hasMoreElements()) {
                "Legacy compatibility: expansion OBB contains no entries: ${safeExpansionText(source.name)}"
            }
            var entryCount = 0
            var fileCount = 0
            var totalExpandedBytes = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount++
                require(entryCount <= limits.maxEntries) {
                    "Legacy compatibility: expansion OBB exceeds the ${limits.maxEntries}-entry limit: ${safeExpansionText(source.name)}"
                }
                if (entry.isDirectory) continue
                fileCount++
                require(entry.size >= 0 && entry.crc >= 0) {
                    "Legacy compatibility: expansion OBB ZIP integrity metadata is incomplete for ${safeExpansionText(entry.name)}."
                }
                require(entry.size <= limits.maxEntryBytes) {
                    "Legacy compatibility: expansion OBB entry ${safeExpansionText(entry.name)} exceeds the ${limits.maxEntryBytes}-byte expanded limit."
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
                            throw IOException("Malformed ZIP entry data in ${safeExpansionText(entry.name)}.", error)
                        }
                        if (count < 0) break
                        require(count.toLong() <= limits.maxEntryBytes - bytesRead) {
                            "Legacy compatibility: expansion OBB entry ${safeExpansionText(entry.name)} exceeds the ${limits.maxEntryBytes}-byte expanded limit."
                        }
                        require(count.toLong() <= limits.maxTotalExpandedBytes - totalExpandedBytes - bytesRead) {
                            "Legacy compatibility: expansion OBB exceeds the ${limits.maxTotalExpandedBytes}-byte total expanded limit."
                        }
                        crc.update(buffer, 0, count)
                        bytesRead += count
                    }
                }
                require(bytesRead == entry.size && crc.value == entry.crc) {
                    "Legacy compatibility: expansion OBB ZIP integrity check failed for ${safeExpansionText(entry.name)}."
                }
                totalExpandedBytes += bytesRead
            }
            require(fileCount > 0) {
                "Legacy compatibility: expansion OBB contains no files: ${safeExpansionText(source.name)}"
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

internal data class StagedExpansionNativeLibrary(
    val entryName: String,
    val destination: String,
    val sourceBytes: Long,
    val outputBytes: Long,
    val sourceSha256: String,
    val outputSha256: String,
    val file: File,
)

internal fun validateExpansionNativeLibraryDestinations(
    nativeEntries: List<Pair<String, String>>,
    existingApkEntries: Set<String>,
) {
    require(nativeEntries.all { (source, destination) -> isSafeExpansionNativeDestination(source, destination) }) {
        "Legacy compatibility: native-library relocation refused because an OBB source or destination path is unsafe."
    }
    val collisions = nativeEntries.filter { (_, destination) -> destination in existingApkEntries }
    require(collisions.isEmpty()) {
        "Legacy compatibility: native-library relocation refused because OBB entries would overwrite existing APK entries: " +
            collisions.joinToString { (source, destination) -> "${safeExpansionText(source)} -> ${safeExpansionText(destination)}" } +
            ". Original APK and OBB entries were preserved; disable relocation or use a compatible OBB."
    }
}

internal fun stageExpansionNativeLibraries(
    zip: ZipFile,
    nativeEntries: List<Pair<String, String>>,
    outputForDestination: (String) -> File,
): List<StagedExpansionNativeLibrary> {
    require(nativeEntries.all { (source, destination) -> isSafeExpansionNativeDestination(source, destination) }) {
        "Legacy compatibility: native-library staging refused because an OBB source or destination path is unsafe."
    }
    require(nativeEntries.map { it.second }.toSet().size == nativeEntries.size) {
        "Legacy compatibility: expansion OBB contains duplicate native-library destinations."
    }
    val outputs = nativeEntries.map { (entryName, destination) ->
        val entry = zip.getEntry(entryName)
            ?: throw IllegalArgumentException("Legacy compatibility: OBB native library entry is missing: ${safeExpansionText(entryName)}")
        Triple(entryName, destination, entry to outputForDestination(destination))
    }
    val existingOutputs = outputs.filter { it.third.second.exists() }
    require(existingOutputs.isEmpty()) {
        "Legacy compatibility: native-library relocation refused because staged output already exists: " +
            existingOutputs.joinToString { (entry, destination, _) -> "${safeExpansionText(entry)} -> ${safeExpansionText(destination)}" } +
            ". Existing APK or patch-provided files were preserved."
    }
    val created = mutableListOf<File>()
    try {
        return outputs.map { (entryName, destination, entryAndFile) ->
            val (entry, output) = entryAndFile
            output.parentFile?.let { parent ->
                require(parent.exists() || parent.mkdirs()) {
                    "Legacy compatibility: cannot create native-library output directory for ${safeExpansionText(destination)}."
                }
            }
            require(output.createNewFile()) {
                "Legacy compatibility: native-library relocation refused because output already exists: ${safeExpansionText(destination)}."
            }
            created += output
            require(entry.size >= 0) { "Legacy compatibility: OBB native library has unknown length: ${safeExpansionText(entryName)}" }
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            var sourceBytes = 0L
            zip.getInputStream(entry).use { input ->
                output.outputStream().buffered().use { outputStream ->
                    val buffer = ByteArray(DEFAULT_COPY_BUFFER)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(count.toLong() <= entry.size - sourceBytes) {
                            "Legacy compatibility: OBB native library exceeds declared length: ${safeExpansionText(entryName)}"
                        }
                        sourceBytes += count
                        sourceDigest.update(buffer, 0, count)
                        outputStream.write(buffer, 0, count)
                    }
                }
            }
            require(sourceBytes == entry.size && output.length() == sourceBytes) {
                "Legacy compatibility: OBB native library copy length mismatch: ${safeExpansionText(entryName)} -> ${safeExpansionText(destination)}"
            }
            val sourceSha256 = sha256Hex(sourceDigest.digest())
            val outputSha256 = expansionSha256(output)
            require(sourceSha256 == outputSha256) {
                "Legacy compatibility: OBB native library copy checksum mismatch: ${safeExpansionText(entryName)} -> ${safeExpansionText(destination)}"
            }
            StagedExpansionNativeLibrary(entryName, destination, sourceBytes, output.length(), sourceSha256, outputSha256, output)
        }
    } catch (error: Exception) {
        created.forEach { output -> output.delete() }
        throw error
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
        validateExpansionObbRelocationOptions(
            relocate = options.relocateNativeLibraries,
            embed = options.embedExpansionObb,
            removeFromObb = options.removeRelocatedNativeLibrariesFromObb,
        )
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
                    "Legacy compatibility: invalid native library entry in OBB: ${safeExpansionText(entry.name)}"
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
                val apkEntries = listApkEntries().toSet()
                val stagingPlan = nativeEntries.map { (sourceEntry, libraryDestination) ->
                    if (libraryDestination !in apkEntries) {
                        sourceEntry to libraryDestination
                    } else {
                        val apkProxyFile = get(libraryDestination, true)
                        val apkProxy = try {
                            inspectElfFile(apkProxyFile)
                        } catch (error: Exception) {
                            throw IllegalArgumentException(
                                "Legacy compatibility: cannot safely classify existing APK library ${safeExpansionText(libraryDestination)}; it will not be overwritten.",
                                error,
                            )
                        }
                        val obbEntry = zip.getEntry(sourceEntry)
                            ?: error("Legacy compatibility: OBB native library entry is missing: ${safeExpansionText(sourceEntry)}")
                        val obbLibrary = inspectElfZipEntry(zip, obbEntry)
                        require(
                            isVerifiedProxyAssetRoute(apkProxy, obbLibrary, apkProxyFile, sourceEntry),
                        ) {
                            "Legacy compatibility: native-library relocation refused because existing APK library " +
                                "${safeExpansionText(libraryDestination)} is not a verified proxy for ${safeExpansionText(sourceEntry)}; no APK library was overwritten."
                        }
                        logger.info(
                            "Legacy compatibility: verified proxy pair for ${safeExpansionText(libraryDestination)}; preserving APK proxy and staging full library at ${safeExpansionText(sourceEntry)}.",
                        )
                        sourceEntry to sourceEntry
                    }
                }
                validateExpansionNativeLibraryDestinations(stagingPlan, apkEntries)
                val stagedLibraries = stageExpansionNativeLibraries(zip, stagingPlan) { destination -> get(destination, false) }
                stagedLibraries.forEach { staged ->
                    val abi = staged.entryName.removePrefix("assets/libs/").substringBefore('/')
                    val collision = if (staged.destination == staged.entryName) "proxy-preserved" else "none"
                    logger.info(
                        "Legacy compatibility: OBB native library staged source=${staged.entryName} destination=${staged.destination} abi=$abi collision=$collision sourceBytes=${staged.sourceBytes} outputBytes=${staged.outputBytes} sourceSha256=${staged.sourceSha256} outputSha256=${staged.outputSha256}.",
                    )
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

/** Moves the standard expansion-downloader launcher to its DEX-identified Unity activity. */
private fun Element.isExpansionLauncherFilter(): Boolean {
    if ((localName ?: tagName.substringAfter(':')) != "intent-filter") return false
    val actions = getElementsByTagName("action")
    val categories = getElementsByTagName("category")
    val main = (0 until actions.length).any { (actions.item(it) as? Element)?.androidAttribute("name") == "android.intent.action.MAIN" }
    val launcher = (0 until categories.length).any { (categories.item(it) as? Element)?.androidAttribute("name") == "android.intent.category.LAUNCHER" }
    return main && launcher
}

internal fun validateEmbeddedExpansionObbSelection(selected: Boolean, obbPath: String) {
    require(!selected || obbPath.isNotBlank()) {
        "Legacy compatibility: embedding an expansion OBB requires a selected OBB file."
    }
}

internal fun validateExpansionObbRelocationOptions(relocate: Boolean, embed: Boolean, removeFromObb: Boolean) {
    require(!removeFromObb || (relocate && embed)) {
        "Legacy compatibility: removing relocated OBB libraries requires both native-library relocation and OBB embedding."
    }
}

internal fun validateExpansionDownloaderBypass(
    selected: Boolean,
    unityDetected: Boolean,
    obbEmbeddingSelected: Boolean,
    obbPath: String,
    launcherActivity: String?,
    verifiedLicenseGate: Boolean = false,
) {
    if (!selected) return
    require(unityDetected) {
        "Legacy compatibility: expansion downloader bypass was selected, but no supported Unity engine was detected."
    }
    require(obbEmbeddingSelected && obbPath.isNotBlank()) {
        "Legacy compatibility: expansion downloader bypass requires OBB embedding and a valid selected OBB file."
    }
    require(verifiedLicenseGate || !launcherActivity.isNullOrBlank()) {
        "Legacy compatibility: downloader bypass could not identify a unique Unity launch activity from the downloader DEX or verify a license gate."
    }
}

private fun manifestActivityName(name: String, packageName: String): String = when {
    name.startsWith(".") -> packageName + name
    '.' !in name -> "$packageName.$name"
    else -> name
}

internal fun verifyExpansionDownloaderLauncher(document: Document, launcherActivityName: String) {
    val application = document.documentElement?.getElementsByTagName("application")?.item(0) as? Element
        ?: error("Legacy compatibility: downloader bypass readback failed; manifest application is missing.")
    val packageName = document.documentElement.getAttribute("package")
    val targetName = manifestActivityName(launcherActivityName, packageName)
    fun activity(name: String) = (0 until application.childNodes.length)
        .mapNotNull { application.childNodes.item(it) as? Element }
        .firstOrNull { it.tagName == "activity" && manifestActivityName(it.androidAttribute("name"), packageName) == name }
    val downloader = activity(GOOGLE_EXPANSION_DOWNLOADER_ACTIVITY)
        ?: error("Legacy compatibility: downloader bypass readback failed; DownloaderActivity is missing.")
    val unity = activity(targetName)
        ?: error("Legacy compatibility: downloader bypass readback failed; detected Unity activity '$targetName' is missing.")
    check(!downloader.isLauncherComponent() && unity.isLauncherComponent()) {
        "Legacy compatibility: downloader bypass readback failed; expected DownloaderActivity without MAIN/LAUNCHER and '$targetName' with MAIN/LAUNCHER."
    }
}

internal fun verifyExpansionDownloaderLicenseLauncher(document: Document, launcherActivityName: String? = null) {
    val application = document.documentElement?.getElementsByTagName("application")?.item(0) as? Element
        ?: error("Legacy compatibility: LVL gate readback failed; manifest application is missing.")
    val packageName = document.documentElement.getAttribute("package")
    val targetName = launcherActivityName?.let { manifestActivityName(it, packageName) }
    fun activity(name: String) = (0 until application.childNodes.length)
        .mapNotNull { application.childNodes.item(it) as? Element }
        .firstOrNull { it.tagName == "activity" && manifestActivityName(it.androidAttribute("name"), packageName) == name }
    val downloader = activity(GOOGLE_EXPANSION_DOWNLOADER_ACTIVITY)
        ?: error("Legacy compatibility: LVL gate readback failed; DownloaderActivity is missing.")
    val unity = targetName?.let { name ->
        activity(name) ?: error("Legacy compatibility: LVL gate readback failed; detected Unity activity '$name' is missing.")
    }
    check(downloader.isLauncherComponent() && unity?.isLauncherComponent() != true) {
        "Legacy compatibility: LVL gate readback failed; DownloaderActivity must remain launcher and detected Unity activity must not become launcher."
    }
}

internal fun moveExpansionDownloaderLauncher(document: Document, logger: Logger, launcherActivityName: String): Int {
    val application = document.documentElement?.getElementsByTagName("application")?.item(0) as? Element
        ?: error("Legacy compatibility: cannot bypass downloader; manifest application is missing.")
    val packageName = document.documentElement.getAttribute("package")
    val targetName = manifestActivityName(launcherActivityName, packageName)
    fun activity(name: String) = (0 until application.childNodes.length)
        .mapNotNull { application.childNodes.item(it) as? Element }
        .firstOrNull { it.tagName == "activity" && manifestActivityName(it.androidAttribute("name"), packageName) == name }
    val activityNames = (0 until application.childNodes.length)
        .mapNotNull { application.childNodes.item(it) as? Element }
        .filter { it.tagName == "activity" }
        .map { it.androidAttribute("name") }
    val downloader = activity(GOOGLE_EXPANSION_DOWNLOADER_ACTIVITY)
        ?: error("Legacy compatibility: cannot bypass downloader; DownloaderActivity is missing. Activities=${activityNames.take(50)}")
    val unity = activity(targetName)
        ?: error("Legacy compatibility: cannot bypass downloader; detected Unity activity '$targetName' is not declared in the manifest.")
    val launcherFilters = (0 until downloader.childNodes.length)
        .mapNotNull { downloader.childNodes.item(it) as? Element }
        .filter(Element::isExpansionLauncherFilter)
    check(launcherFilters.isNotEmpty() || unity.isLauncherComponent()) {
        "Legacy compatibility: cannot bypass downloader; DownloaderActivity has no MAIN/LAUNCHER filter to transfer and '$targetName' is not already the launcher."
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
    verifyExpansionDownloaderLauncher(document, targetName)
    logger.info("Legacy compatibility: transferred downloader launcher filter and verified manifest readback; $targetName is launcher.")
    return launcherFilters.size
}

private const val DEFAULT_COPY_BUFFER = 64 * 1024
