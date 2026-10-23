package unipatches.frida

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.filePathOption
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.stringsOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.google.gson.JsonParser
import helpers.bytecode.cloneMutable
import helpers.bytecode.numberOfParameterRegisters
import helpers.bytecode.p0Register
import helpers.startup.StartupHooks
import helpers.startup.resolveStartupEntryPoint
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.logging.Logger

private const val FRIDA_RUNTIME_CLASS = "Lunipatch/overlaycore/FridaGadgetRuntime;"
private const val FRIDA_ASSET_ROOT = "assets/unipatch/frida"
private const val FRIDA_BUNDLE_ASSET = "$FRIDA_ASSET_ROOT/bundle.js"
private const val MAX_SCRIPT_BYTES = 8 * 1024 * 1024
private const val MAX_BUNDLE_BYTES = 32 * 1024 * 1024
private const val MAX_GADGET_BYTES = 96 * 1024 * 1024
private const val MAX_DOWNLOAD_BYTES = 128 * 1024 * 1024
private const val RELEASES_API = "https://api.github.com/repos/frida/frida/releases/latest"

private val logger = Logger.getLogger("unipatches.frida.FridaGadgetPatch")

internal enum class FridaAbi(
    val apkDirectory: String,
    val releaseName: String,
    val assetToken: String,
    val elfMachine: Int,
) {
    ARM64("arm64-v8a", "arm64", "arm64", 183),
    ARM("armeabi-v7a", "arm", "arm", 40),
    X86_64("x86_64", "x86_64", "x86_64", 62),
    X86("x86", "x86", "x86", 3),
}

internal data class ScriptSource(val name: String, val source: String)
private data class GadgetPayload(val gadgets: Map<String, ByteArray>, val bundle: ByteArray)
internal data class TargetAbis(val values: List<FridaAbi>, val discovered: Boolean)

/** Exact final-extension check. `.jsonimage.json` and `hook.js.json` are not JavaScript files. */
internal fun hasJavaScriptExtension(path: String): Boolean {
    val name = path.trim().substringAfterLast('/').substringAfterLast('\\')
    if (name.length <= 3 || name == ".js") return false
    return name.substringAfterLast('.', "").equals("js", ignoreCase = true)
}

private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (output.size() + read > maxBytes) error("Input exceeds the $maxBytes-byte limit")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun readUtf8Script(file: File): String {
    require(file.isFile && file.canRead()) { "JavaScript file is missing or unreadable: ${file.path}" }
    require(file.length() > 0L) { "JavaScript file is empty: ${file.path}" }
    require(file.length() <= MAX_SCRIPT_BYTES) { "JavaScript file exceeds the $MAX_SCRIPT_BYTES-byte limit: ${file.path}" }
    val bytes = try {
        FileInputStream(file).use { readBounded(it, MAX_SCRIPT_BYTES) }
    } catch (exception: Exception) {
        throw IllegalArgumentException("Could not read JavaScript file ${file.path}: ${exception.message}", exception)
    }
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (exception: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("JavaScript file is not valid UTF-8: ${file.path}", exception)
    }
}

private fun readEntryScript(path: String): ScriptSource {
    val file = runCatching { File(path.trim()).canonicalFile }.getOrNull()
        ?: error("Entry JavaScript path cannot be resolved")
    require(hasJavaScriptExtension(file.name)) {
        "Entry JavaScript file must end with .js: ${file.name}"
    }
    require(file.isFile && file.canRead()) {
        "Entry JavaScript file is missing or unreadable: ${file.path}"
    }
    val source = readUtf8Script(file)
    return ScriptSource(file.name, source)
}

internal fun readAdditionalScripts(paths: List<String>): List<ScriptSource> {
    val result = mutableListOf<ScriptSource>()
    val seen = mutableSetOf<String>()
    paths.asSequence()
        .flatMap { it.split('\n', '\r').asSequence() }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach { sourcePath ->
            val file = runCatching { File(sourcePath).canonicalFile }.getOrNull()
            requireNotNull(file) { "Additional JavaScript path cannot be resolved: $sourcePath" }
            require(hasJavaScriptExtension(file.name)) { "Additional JavaScript file must end with .js: $sourcePath" }
            if (seen.add(file.path)) result += ScriptSource(file.name, readUtf8Script(file))
        }
    return result
}

internal fun buildFridaBundle(entry: ScriptSource, extras: List<ScriptSource>): ByteArray {
    val output = buildString {
        append("// UniPatches Frida Gadget entry script: ").append(entry.name.replace(Regex("[\\r\\n\\u2028\\u2029]"), " ")).append('\n')
        append(entry.source)
        if (!endsWith("\n")) append('\n')
        extras.forEachIndexed { index, script ->
            append("\n;\n// UniPatches Frida Gadget additional script ").append(index + 1)
                .append(": ").append(script.name.replace(Regex("[\\r\\n\\u2028\\u2029]"), " ")).append('\n')
            append(script.source)
            if (!endsWith("\n")) append('\n')
        }
    }
    val bytes = output.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_BUNDLE_BYTES) {
        "Combined Frida JavaScript bundle exceeds the $MAX_BUNDLE_BYTES-byte limit"
    }
    return bytes
}

private fun readXz(bytes: ByteArray): ByteArray =
    XZInputStream(ByteArrayInputStream(bytes)).use { readBounded(it, MAX_GADGET_BYTES) }

private fun readUnsignedShort(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
    val first = bytes[offset].toInt() and 0xff
    val second = bytes[offset + 1].toInt() and 0xff
    return if (littleEndian) first or (second shl 8) else (first shl 8) or second
}

internal fun validateGadget(bytes: ByteArray, expected: FridaAbi? = null): ByteArray {
    require(bytes.size >= 20 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
        bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
        "Frida Gadget is not an ELF shared library"
    }
    val littleEndian = when (bytes[5].toInt() and 0xff) {
        1 -> true
        2 -> false
        else -> error("Frida Gadget ELF has unsupported byte order")
    }
    require(readUnsignedShort(bytes, 16, littleEndian) == 3) {
        "Frida Gadget ELF is not a shared object"
    }
    val machine = readUnsignedShort(bytes, 18, littleEndian)
    val abi = FridaAbi.values().firstOrNull { it.elfMachine == machine }
    val expectedClass = if (abi == FridaAbi.ARM64 || abi == FridaAbi.X86_64) 2 else 1
    require(abi != null && (bytes[4].toInt() and 0xff) == expectedClass) {
        "Frida Gadget ELF class does not match supported architecture $machine"
    }
    if (expected != null) {
        require(machine == expected.elfMachine) {
            "Frida Gadget architecture $machine does not match ${expected.apkDirectory}"
        }
    } else {
        require(FridaAbi.values().any { it.elfMachine == machine }) {
            "Frida Gadget ELF architecture $machine is unsupported"
        }
    }
    return bytes
}

private fun readGadgetFile(path: String, expected: FridaAbi?): ByteArray {
    val file = runCatching { File(path.trim()).canonicalFile }.getOrNull()
        ?: error("Frida Gadget file path cannot be resolved")
    require(file.isFile && file.canRead()) { "Frida Gadget file is missing or unreadable: ${file.path}" }
    val name = file.name.lowercase(Locale.ROOT)
    require(name.endsWith(".so") || name.endsWith(".so.xz")) {
        "Frida Gadget file must end with .so or .so.xz: ${file.name}"
    }
    require(file.length() > 0L && file.length() <= MAX_DOWNLOAD_BYTES) {
        "Frida Gadget file is empty or too large: ${file.path}"
    }
    return try {
        val source = FileInputStream(file).use { readBounded(it, MAX_DOWNLOAD_BYTES) }
        val decompressed = if (name.endsWith(".so.xz")) readXz(source) else source
        require(decompressed.size <= MAX_GADGET_BYTES) { "Frida Gadget exceeds the $MAX_GADGET_BYTES-byte limit" }
        validateGadget(decompressed, expected)
    } catch (exception: Exception) {
        throw IllegalArgumentException("Could not prepare Frida Gadget ${file.path}: ${exception.message}", exception)
    }
}

private fun download(url: String, maxBytes: Int): ByteArray {
    require(url.startsWith("https://")) { "Refusing non-HTTPS Frida Gadget URL" }
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "UniPatches-Frida-Gadget")
    return try {
        require(connection.responseCode in 200..299) {
            "Frida Gadget download failed with HTTP ${connection.responseCode}"
        }
        connection.inputStream.use { stream -> readBounded(stream, maxBytes) }
    } finally {
        connection.disconnect()
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun downloadLatestGadget(abi: FridaAbi): ByteArray {
    val release = JsonParser.parseString(String(download(RELEASES_API, 2 * 1024 * 1024), StandardCharsets.UTF_8)).asJsonObject
    val asset = release.getAsJsonArray("assets")
        .map { it.asJsonObject }
        .firstOrNull { it.get("name")?.asString == "frida-gadget-${release.get("tag_name").asString.removePrefix("v")}-android-${abi.releaseName}.so.xz" }
        ?: error("Official Frida Gadget release has no ${abi.apkDirectory} asset")
    val digest = asset.get("digest")?.asString?.removePrefix("sha256:")?.takeIf { it.isNotBlank() }
    val compressed = download(asset.get("browser_download_url").asString, MAX_DOWNLOAD_BYTES)
    val gadget = validateGadget(readXz(compressed), abi)
    if (digest == null) {
        logger.warning("Official Frida Gadget asset has no SHA-256 digest; continuing after ELF validation")
    } else {
        require(sha256(gadget).equals(digest, ignoreCase = true) || sha256(compressed).equals(digest, ignoreCase = true)) {
            "Official Frida Gadget SHA-256 verification failed for ${abi.apkDirectory}"
        }
    }
    return gadget
}

internal fun targetAbis(entries: List<String>): TargetAbis {
    val directories = entries.mapNotNull { entry ->
        Regex("^lib/([^/]+)/").find(entry)?.groupValues?.get(1)
    }.toSet()
    val supportedDirectories = FridaAbi.values().map { it.apkDirectory }.toSet()
    val unsupported = directories - supportedDirectories
    require(unsupported.isEmpty()) {
        "APK contains unsupported native ABI directories: ${unsupported.joinToString()}. " +
            "Frida Gadget supports ${supportedDirectories.joinToString()}"
    }
    val discovered = FridaAbi.values().filter { it.apkDirectory in directories }
    return TargetAbis(discovered.ifEmpty { FridaAbi.values().toList() }, directories.isNotEmpty())
}

private fun preparePayload(
    gadgetPath: String,
    entryPath: String,
    additionalPaths: List<String>,
    resourceEntries: List<String>,
): GadgetPayload {
    val entry = readEntryScript(entryPath)
    val extras = readAdditionalScripts(additionalPaths)
    val targets = targetAbis(resourceEntries)
    val gadgets = if (gadgetPath.trim().isNotEmpty()) {
        require(!targets.discovered || targets.values.size == 1) {
            "One custom Frida Gadget file cannot cover an APK with multiple ABI directories; leave input empty to auto-fetch matching Gadgets"
        }
        mapOf("custom" to readGadgetFile(gadgetPath, targets.values.singleOrNull()))
    } else {
        targets.values.associate { abi -> abi.assetToken to downloadLatestGadget(abi) }
    }
    return GadgetPayload(gadgets, buildFridaBundle(entry, extras))
}

private fun injectStartup(
    startup: helpers.startup.StartupEntryPoint,
    minimalFootprint: Boolean,
): MutableMethod {
    val owner = startup.owner
    val method = startup.onCreate
    if (method.implementation?.instructions?.any {
            it.toString().contains("FridaGadgetRuntime;->initialize")
        } == true
    ) return method
    val base = method.implementation?.registerCount
        ?: error("Cannot inject Frida Gadget without method implementation")
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 2)
    val receiver = cloned.p0Register
    val insertionIndex = cloned.implementation?.instructions.orEmpty().indexOfFirst {
        it.toString().contains("invoke-super") && it.toString().contains("->onCreate(")
    }.let { if (it >= 0) it + 1 else 0 }
    cloned.addInstructionsWithLabels(insertionIndex, """
        move-object/from16 v$base, v$receiver
        const/4 v${base + 1}, ${if (minimalFootprint) "0x1" else "0x0"}
        invoke-static/range {v$base .. v${base + 1}}, $FRIDA_RUNTIME_CLASS->initialize(Landroid/content/Context;Z)V
    """.trimIndent())
    owner.methods.remove(method)
    owner.methods.add(cloned)
    return cloned
}

@Suppress("unused")
val fridaGadgetPatch = bytecodePatch(
    name = "Embed Frida Gadget ( Advanced )",
    description = """
        Embeds Frida Gadget and selected JavaScript in an APK. Extra scripts load in order; an empty
        entry leaves the APK unchanged. Minimal Footprint Mode disables script file watching; it is not stealth.

        Warning : This patch has the potential to modify internals of patched APK at runtime depending on given JavaScript files, but is also detectable! Use this patch at your own risk.
    """.trimIndent(),
    default = false,
) {
    try { category("Frida Gadget") } catch (_: NoSuchMethodError) {}
    extendWith("extensions/extension.mpe")
    dependsOn(StartupHooks.resolveRealApplicationPatch)

    val gadgetFile by filePathOption(
        title = "Frida Gadget > Gadget file input",
        default = "",
        key = "fridaGadgetFile",
        allowedExtensions = listOf("so", "xz"),
        description = "Optional Frida Gadget .so or .so.xz file. Leave empty to fetch the latest official matching Gadget during patching with SHA-256 verification.",
    )
    val minimalFootprint by booleanOption(
        title = "Frida Gadget > Minimal Footprint Mode",
        default = false,
        key = "fridaGadgetMinimalFootprint",
        description = "Disable script file watching and keep runtime behavior minimal. This is compatibility-focused, not stealth or anti-detection behavior.",
    )
    val entryScript by filePathOption(
        title = "Frida Gadget > Scripts > Entry / First JavaScript file",
        default = "",
        key = "fridaGadgetEntryScript",
        allowedExtensions = listOf("js"),
        description = "Required UTF-8 JavaScript file selected through Morphe Manager. It is embedded and loaded first. Leave empty to skip this patch entirely.",
    )
    val additionalScriptPaths by stringsOption(
        title = "Frida Gadget > Scripts > Additional JavaScript files",
        default = emptyList(),
        key = "fridaGadgetAdditionalScripts",
        description = "Optional patch-time paths, one per row. No artificial path-count limit. Exact final .js files are embedded in order and share script scope; invalid inputs stop patching with an error.",
    )

    val payloadPatch = rawResourcePatch(
        name = "Frida Gadget payload (internal)",
        default = false,
    ) {
        execute {
            if (entryScript.orEmpty().trim().isEmpty()) {
                logger.info("Frida Gadget: entry script is empty; skipping payload and download")
                return@execute
            }
            val payload = preparePayload(
                gadgetFile.orEmpty(),
                entryScript.orEmpty(),
                additionalScriptPaths.orEmpty(),
                listApkEntries("lib/"),
            )
            get(FRIDA_BUNDLE_ASSET, false).also { it.parentFile?.mkdirs() }.writeBytes(payload.bundle)
            payload.gadgets.forEach { (token, bytes) ->
                get("$FRIDA_ASSET_ROOT/gadget/$token/libfrida-gadget.so", false)
                    .also { it.parentFile?.mkdirs() }
                    .writeBytes(bytes)
            }
            logger.info("Frida Gadget: embedded ${payload.gadgets.size} Gadget ABI asset(s) and ${additionalScriptPaths.orEmpty().size} additional path input(s)")
        }
    }
    dependsOn(payloadPatch)

    execute {
        if (entryScript.orEmpty().trim().isEmpty()) {
            logger.info("Frida Gadget: entry script is empty; no bytecode changes")
            return@execute
        }
        val startup = resolveStartupEntryPoint(logger)
            ?: error("Frida Gadget: no safe Application or Activity startup entry point was found")
        injectStartup(startup, minimalFootprint == true)
        logger.info("Frida Gadget: injected startup loader into ${startup.owner.type}")
    }
}
