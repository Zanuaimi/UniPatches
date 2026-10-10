package unipatches.compatibility

import app.morphe.patcher.patch.rawResourcePatch
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import helpers.manifest.NS_ANDROID
import helpers.startup.StartupHooks
import java.util.logging.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val EXPANSION_ASSET_DIRECTORY = "assets/unipatch-legacy-expansion"
private val expansionFilePattern = Regex("(?:main|patch)\\.\\d+\\.([A-Za-z0-9_.]+)\\.obb")
private val nativeEntryPattern = Regex("assets/libs/([^/]+)/([^/]+\\.so)")

internal data class LegacyExpansionOptions(
    val obbPath: String,
    val relocateNativeLibraries: Boolean,
    val embedExpansionObb: Boolean,
    val removeRelocatedNativeLibrariesFromObb: Boolean,
)

internal fun expansionAssetPath(fileName: String): String = "$EXPANSION_ASSET_DIRECTORY/$fileName"

internal fun expansionNativeDestination(entryName: String): String? =
    nativeEntryPattern.matchEntire(entryName)?.let { match ->
        val abi = match.groupValues[1]
        val fileName = match.groupValues[2]
        if (abi == "." || abi == ".." || abi.contains('\\') || fileName == "." || fileName == ".." || fileName.contains('\\')) {
            return@let null
        }
        "lib/$abi/$fileName"
    }

internal fun isExpansionFileForPackage(fileName: String, packageName: String?): Boolean {
    val match = expansionFilePattern.matchEntire(fileName) ?: return false
    return packageName?.takeIf { it.isNotBlank() } == match.groupValues[1]
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
        if (options.obbPath.isBlank()) {
            logger.warning("Legacy compatibility: expansion options selected without an OBB path; expansion changes skipped.")
            return@execute
        }
        val source = File(options.obbPath)
        require(source.isFile && source.canRead()) {
            "Legacy compatibility: expansion OBB is missing or unreadable: ${source.absolutePath}"
        }
        val packageName = StartupHooks.resolvedPackageName
        require(!packageName.isNullOrBlank()) {
            "Legacy compatibility: cannot validate expansion OBB because APK package name could not be resolved."
        }
        require(isExpansionFileForPackage(source.name, packageName)) {
            "Legacy compatibility: expected main.<versionCode>.<package>.obb or patch.<versionCode>.<package>.obb matching package $packageName, got ${source.name}"
        }

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

        val rewriteEmbeddedObb = options.removeRelocatedNativeLibrariesFromObb &&
            options.relocateNativeLibraries && options.embedExpansionObb
        if (options.removeRelocatedNativeLibrariesFromObb && !rewriteEmbeddedObb) {
            logger.warning("Legacy compatibility: removing relocated OBB libraries requires both native-library relocation and OBB embedding; OBB rewrite skipped.")
        }
        if (options.embedExpansionObb) {
            val output = get(expansionAssetPath(source.name), false)
            if (rewriteEmbeddedObb) {
                rewriteExpansionObb(source, output, nativeEntries.map { it.first }.toSet())
                logger.info("Legacy compatibility: embedded expansion OBB ${source.name} without ${nativeEntries.size} relocated native libraries.")
            } else {
                output.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    output.outputStream().use { outputStream -> input.copyTo(outputStream, DEFAULT_COPY_BUFFER) }
                }
                logger.info("Legacy compatibility: embedded expansion OBB ${source.name} (${source.length()} bytes).")
            }
        }
    }
}

/** Moves the expansion downloader's launcher filter to the known Unity launcher. */
internal fun moveExpansionDownloaderLauncher(document: Document, logger: Logger): Int {
    val application = document.documentElement?.let { root ->
        val nodes = root.getElementsByTagName("application")
        nodes.item(0) as? Element
    } ?: return 0
    val downloader = (0 until application.childNodes.length)
        .mapNotNull { application.childNodes.item(it) as? Element }
        .firstOrNull { it.tagName == "activity" && it.getAttributeNS(NS_ANDROID, "name") == "com.google.android.vending.expansion.downloader_impl.DownloaderActivity" }
        ?: return 0
    val unity = (0 until application.childNodes.length)
        .mapNotNull { application.childNodes.item(it) as? Element }
        .firstOrNull { it.tagName == "activity" && it.getAttributeNS(NS_ANDROID, "name") == "com.glu.plugins.AUnityInstaller.UnityLauncherActivity" }
        ?: return 0
    fun isLauncherFilter(filter: Element): Boolean {
        if (filter.tagName != "intent-filter") return false
        val actions = filter.getElementsByTagName("action")
        val categories = filter.getElementsByTagName("category")
        val main = (0 until actions.length).any { (actions.item(it) as? Element)?.getAttributeNS(NS_ANDROID, "name") == "android.intent.action.MAIN" }
        val launcher = (0 until categories.length).any { (categories.item(it) as? Element)?.getAttributeNS(NS_ANDROID, "name") == "android.intent.category.LAUNCHER" }
        return main && launcher
    }

    val launcherFilters = (0 until downloader.childNodes.length)
        .mapNotNull { downloader.childNodes.item(it) as? Element }
        .filter(::isLauncherFilter)
    if (launcherFilters.isEmpty()) return 0
    launcherFilters.forEach(downloader::removeChild)

    val unityHasLauncher = (0 until unity.childNodes.length)
        .mapNotNull { unity.childNodes.item(it) as? Element }
        .any(::isLauncherFilter)
    if (!unityHasLauncher) {
        val replacement = document.createElement("intent-filter")
        document.createElement("action").also {
            it.setAttributeNS(NS_ANDROID, "android:name", "android.intent.action.MAIN")
            replacement.appendChild(it)
        }
        document.createElement("category").also {
            it.setAttributeNS(NS_ANDROID, "android:name", "android.intent.category.LAUNCHER")
            replacement.appendChild(it)
        }
        unity.appendChild(replacement)
    }
    unity.setAttributeNS(NS_ANDROID, "android:exported", "true")
    logger.info("Legacy compatibility: moved launcher from expansion downloader to Unity launcher.")
    return 1
}

private const val DEFAULT_COPY_BUFFER = 64 * 1024
