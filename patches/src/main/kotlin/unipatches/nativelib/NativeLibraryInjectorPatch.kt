package unipatches.nativelib

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.filePathOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import helpers.manifest.NS_ANDROID
import helpers.manifest.applicationOrNull
import helpers.startup.StartupHooks
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

internal data class NativeLibraryStageOptions(
    val sourcePath: String,
    val libraryNameOverride: String,
    val requireMatchingAbi: Boolean,
)

/**
 * Native library injector.
 *
 * The injector stages a selected `.so` under the ABI directory it declares in
 * its ELF header, then loads it from the resolved Application or launcher
 * Activity before app code runs. `JNI_OnLoad` runs as part of
 * `System.loadLibrary`, so the selected library needs no Java bridge.
 */
@Suppress("unused")
val nativeLibraryInjectorPatch = bytecodePatch(
    name = "Native Library Injector",
    description = """
        Stage an ELF shared library into the APK and load it at startup with
        System.loadLibrary. The library's JNI_OnLoad runs automatically, so
        IL2CPP/Unity mod menus and other JNI libraries need no extra Java class.

        The ABI is read from the ELF header: arm64-v8a, armeabi-v7a, x86, or
        x86_64. By default the APK must already contain lib/<abi>/; adding a new
        ABI to an APK that already ships native libraries can change which
        libraries Android selects. Selecting libTool.so stages
        lib/<abi>/libTool.so and injects System.loadLibrary("Tool").

        Load target Auto uses a custom Application when the manifest declares
        one, otherwise the launcher Activity. Application loads in every app
        process; the launcher Activity loads only in the main process. Injection
        happens before the target's super.onCreate, so the library starts before
        application code.
    """.trimIndent(),
    default = false,
) {
    // Guarded: morphe-patcher < 1.13.0 has no category() and keeps the patch ungrouped.
    try { category("Native Library Injection") } catch (_: NoSuchMethodError) {}

    val nativeLibraryFile by filePathOption(
        key = "nativeLibraryFile",
        title = "Library > Shared library (.so)",
        description = "ELF shared object to add to the APK. Its machine field selects the ABI. The APK must already contain lib/<abi>/ unless Require matching ABI directory is disabled. Example: libTool.so.",
        allowedExtensions = listOf("so"),
        required = true,
    )
    val nativeLibraryName by stringOption(
        key = "nativeLibraryName",
        title = "Library > Load name override",
        description = "Bare name passed to System.loadLibrary. Leave empty to derive it from the file: libTool.so becomes Tool. Do not include the lib prefix, the .so suffix, or a path.",
        default = "",
    )
    val nativeLibraryRequireAbi by booleanOption(
        key = "nativeLibraryRequireAbi",
        title = "Library > Require matching ABI directory",
        description = "Fail instead of adding a new ABI to an APK that has no lib/<abi>/ entry. Recommended: adding an ABI can change which native libraries Android loads.",
        default = true,
    )
    val nativeLibraryLoadTarget by stringOption(
        key = "nativeLibraryLoadTarget",
        title = "Startup > Load target",
        description = "Auto uses Application when the manifest declares one, otherwise the launcher Activity. Application loads in every app process. Launcher loads only in the main process.",
        values = linkedMapOf(
            "Auto (Application, else launcher)" to "auto",
            "Application (all processes)" to "application",
            "Launcher activity (main process)" to "launcher",
        ),
        default = "auto",
    )

    dependsOn(
        nativeLibraryStagePatch {
            NativeLibraryStageOptions(
                sourcePath = nativeLibraryFile.orEmpty(),
                libraryNameOverride = nativeLibraryName.orEmpty(),
                requireMatchingAbi = nativeLibraryRequireAbi == true,
            )
        },
        StartupHooks.resolveRealApplicationPatch,
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val plan = NativeLibraryPlanHolder.plan
            ?: throw PatchException("Native Library Injector: the .so was not staged. Select a valid shared library.")

        val target = nativeLibraryLoadTarget.orEmpty().ifEmpty { "auto" }
        val descriptors = when (target) {
            "application" -> listOfNotNull(StartupHooks.resolvedApplicationDescriptor)
            "launcher" -> listOfNotNull(StartupHooks.resolvedLauncherActivityDescriptor)
            else -> listOfNotNull(
                StartupHooks.resolvedApplicationDescriptor,
                StartupHooks.resolvedLauncherActivityDescriptor,
            )
        }.distinct()

        if (descriptors.isEmpty()) {
            throw PatchException(
                "Native Library Injector: the manifest does not declare a usable Application or launcher Activity.",
            )
        }

        var injected = false
        for (descriptor in descriptors) {
            val owner = mutableClassDefByOrNull(descriptor)
            if (owner == null) {
                logger.warning("Native Library Injector: $descriptor is not present in the app dex.")
                continue
            }
            val onCreate = owner.methods.firstOrNull { method ->
                isStartupOnCreate(
                    method,
                    requireBundle = descriptor != StartupHooks.resolvedApplicationDescriptor,
                )
            }
            if (onCreate == null) {
                logger.warning("Native Library Injector: $descriptor has no injectable onCreate().")
                continue
            }
            if (injectNativeLibraryLoad(owner, onCreate, plan.libraryName)) {
                logger.info(
                    "Native Library Injector: injected System.loadLibrary(\"${plan.libraryName}\") into $descriptor->onCreate.",
                )
                injected = true
                break
            }
        }

        if (!injected) {
            throw PatchException(
                "Native Library Injector: could not find a usable onCreate() in " +
                    descriptors.joinToString(", ") +
                    ". Use a target APK whose Application or launcher Activity overrides onCreate().",
            )
        }

        logger.info("Native Library Injector: ${plan.entryName} will load as ${plan.libraryName}.")
    }
}

/**
 * Internal resource patch that copies the selected library into the APK tree.
 *
 * Runs as a dependency of [nativeLibraryInjectorPatch] so the `.so` is present
 * before the bytecode patch injects the load call. The file lands under
 * `root/lib/<abi>/`; Morphe's resource coder moves new root files into the
 * rebuilt APK. If the manifest disables native library extraction, the staging
 * step also records the new entry as uncompressed.
 */
private fun nativeLibraryStagePatch(options: () -> NativeLibraryStageOptions): ResourcePatch = resourcePatch(
    name = "Native Library Staging (internal)",
    default = false,
) {
    execute {
        val logger = Logger.getLogger(this::class.java.name)
        // A failed staging run must not leave a plan from an earlier patch session.
        NativeLibraryPlanHolder.plan = null
        val selected = options()
        val sourcePath = selected.sourcePath.trim()
        if (sourcePath.isEmpty()) {
            throw PatchException("Native Library Injector: choose a .so file in Library > Shared library.")
        }

        val source = File(sourcePath)
        if (!source.isFile) {
            throw PatchException("Native Library Injector: ${source.absolutePath} is not a file.")
        }
        if (!source.canRead()) {
            throw PatchException("Native Library Injector: ${source.absolutePath} is not readable.")
        }
        if (source.length() == 0L) {
            throw PatchException("Native Library Injector: ${source.absolutePath} is empty.")
        }

        val abi = detectElfAbi(source)
            ?: throw PatchException(
                "Native Library Injector: ${source.name} is not a supported ELF shared object. " +
                    "Expected a little-endian ET_DYN file for arm64-v8a, armeabi-v7a, x86, or x86_64.",
            )
        val libraryName = resolveLibraryName(source, selected.libraryNameOverride)
        val entryName = libraryEntryName(abi, libraryName)

        val nativeEntries = listApkEntries("lib/").filterNot { it.endsWith("/") }
        val abiEntries = nativeEntries.filter { it.startsWith("lib/$abi/") }
        val availableAbis = nativeEntries
            .mapNotNull { entry -> entry.removePrefix("lib/").substringBefore('/').takeIf { it.isNotEmpty() } }
            .toSortedSet()
            .joinToString(", ")
            .ifEmpty { "none" }

        if (abiEntries.isEmpty()) {
            if (selected.requireMatchingAbi) {
                throw PatchException(
                    "Native Library Injector: cannot add lib/$abi/ because the APK has no native library " +
                        "for $abi. Available native ABIs: $availableAbis. Use a matching APK build, or " +
                        "disable Require matching ABI directory only if you accept the ABI-selection risk.",
                )
            }
            if (nativeEntries.isEmpty()) {
                logger.info("Native Library Injector: the APK has no native libraries; adding $abi.")
            } else {
                logger.warning(
                    "Native Library Injector: adding $abi to an APK whose native ABIs are $availableAbis. " +
                        "This can change which ABI Android selects at install time.",
                )
            }
        } else {
            logger.info("Native Library Injector: found ${abiEntries.size} existing $abi native libraries.")
        }

        val destination = get(entryName, true)
        if (source.canonicalFile == destination.canonicalFile) {
            throw PatchException("Native Library Injector: source and destination are the same file: $entryName.")
        }
        destination.parentFile?.mkdirs()
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (!destination.isFile || destination.length() != source.length()) {
            throw PatchException(
                "Native Library Injector: failed to stage $entryName (expected ${source.length()} bytes, " +
                    "got ${if (destination.isFile) destination.length() else "no file"}).",
            )
        }

        ensureNativeLibraryStoredUncompressed(entryName, logger)

        NativeLibraryPlanHolder.plan = NativeLibraryPlan(source, abi, libraryName, entryName)
        logger.info(
            "Native Library Injector: staged ${source.name} as $entryName " +
                "($abi, ${source.length()} bytes).",
        )
    }
}

/**
 * Keeps the newly added `.so` uncompressed when the manifest disables native
 * library extraction.
 *
 * `android:extractNativeLibs="false"` makes the platform load libraries directly
 * from the APK, which requires STORED entries aligned to 4 KiB. The patcher
 * already aligns every `.so`; this adds the new entry to ARSCLib's
 * `uncompressed-files.json` so `ApkUtils` does not deflate it. ARSCLib keeps that
 * metadata beside the decoded APK, outside the resource tree, so the file is
 * located from the workspace rather than through the resource API.
 *
 * If the metadata cannot be found (custom workspace layout), the manifest is
 * switched to `extractNativeLibs="true"`; extraction makes compression legal and
 * keeps the injected library loadable.
 */
private fun ResourcePatchContext.ensureNativeLibraryStoredUncompressed(
    entryName: String,
    logger: Logger,
) {
    val extractNativeLibs = document("AndroidManifest.xml").use { doc ->
        val application = doc.documentElement.applicationOrNull()
        val raw = application?.getAttributeNS(NS_ANDROID, "extractNativeLibs")
            ?.ifEmpty { application.getAttribute("android:extractNativeLibs") }
        raw?.toBooleanStrictOrNull()
    }
    if (extractNativeLibs != false) return

    val jsonFile = locateUncompressedFilesJson(fileWorkspace)
    if (jsonFile == null) {
        document("AndroidManifest.xml").use { doc ->
            val application = doc.documentElement.applicationOrNull()
                ?: throw PatchException(
                    "Native Library Injector: extractNativeLibs=false but the manifest has no <application> element.",
                )
            application.setAttributeNS(NS_ANDROID, "android:extractNativeLibs", "true")
        }
        logger.warning(
            "Native Library Injector: could not find uncompressed-files.json; " +
                "set android:extractNativeLibs=true so the new library is extracted on install.",
        )
        return
    }

    val root = JsonParser.parseString(jsonFile.readText(Charsets.UTF_8)).asJsonObject
    val paths = root.getAsJsonArray("paths") ?: JsonArray().also { root.add("paths", it) }
    if (paths.none { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == entryName }) {
        paths.add(entryName)
        jsonFile.writeText(root.toString(), Charsets.UTF_8)
        logger.info(
            "Native Library Injector: marked $entryName uncompressed " +
                "(extractNativeLibs=false).",
        )
    }
}

/** Finds ARSCLib's `uncompressed-files.json` without relying on its private fields. */
private fun locateUncompressedFilesJson(workspace: File): File? {
    val candidates = listOf(
        workspace.resolve("apk/uncompressed-files.json"),
        workspace.parentFile?.resolve("apk/uncompressed-files.json"),
        workspace.parentFile?.parentFile?.resolve("apk/uncompressed-files.json"),
    )
    return candidates.filterNotNull().firstOrNull { it.isFile }
}
