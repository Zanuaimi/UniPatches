package tools

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.Patch
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KType
import unipatches.iap.emulateInAppPatch
import unipatches.verify.serverVerificationBypassPatch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Local APK runner for this bundle.
 *
 * Applies a named subset of patches to one APK and writes an unsigned, re-zipped
 * result: patched `classes*.dex` replace the originals, everything the resource
 * pipeline produced (manifest, resources.arsc, res entries) replaces its counterpart,
 * `deleteResources` entries are dropped, old META-INF signatures are stripped so
 * apksigner can sign the output cleanly.
 *
 *   ./gradlew :patches:patchLocalApk -Papk=<in.apk> -Pout=<out.apk> -Ppatches=inapp,verify
 *
 * Alignment and signing stay outside this tool: the same zipalign -> apksigner
 * chain the other local mods use, so the output is a normal installable APK.
 */
private val catalog: Map<String, Patch<*>> = mapOf(
    "inapp" to emulateInAppPatch,
    "verify" to serverVerificationBypassPatch,
)

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: patchLocalApk <in.apk> <out.apk> [patch,patch,...] [option=value;option=value]")
        kotlin.system.exitProcess(2)
    }
    val input = File(args[0])
    val output = File(args[1])
    val selection = if (args.size > 2) {
        args[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
    } else {
        listOf("inapp", "verify")
    }
    require(input.isFile) { "input APK not found: $input" }

    val patches = selection.map { key ->
        catalog[key] ?: error("unknown patch '$key'; known: ${catalog.keys.joinToString()}")
    }
    // Local runs need to flip patch options that a default build keeps off, such as the
    // verbose payload log. Values are parsed against each option's declared type; a key no
    // selected patch declares is a typo, so it fails before the patch runs.
    val overrides = parseOptionOverrides(if (args.size > 3) args[3] else null)
    for ((optionKey, _) in overrides) {
        require(patches.any { it.options.containsKey(optionKey) }) {
            "no selected patch declares option '$optionKey'; declared: " +
                patches.flatMap { it.options.keys }.distinct().joinToString()
        }
    }
    for (patch in patches) {
        for ((optionKey, raw) in overrides) applyOption(patch, optionKey, raw)
    }
    output.parentFile?.mkdirs()

    val workspace = Files.createTempDirectory("unipatches-local-").toFile()
        .also { dir -> Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() }) }
    // STRIP_FAST mutates the input dex headers in place and leaves class_defs_off stale when
    // a file loses every class; ART rejects that with "Offset(N) should be zero when size is
    // zero for class-defs" and drops the whole dex. FULL recompiles every class into fresh
    // dex files, which is slower and hungrier but produces headers that cannot be malformed.
    val patcher = Patcher(PatcherConfig(input, workspace, useBytecodeMode = BytecodeMode.FULL))
    val results = mutableListOf<String>()
    val failures = mutableListOf<String>()
    val result: PatcherResult
    try {
        patcher += patches.toSet()
        runBlocking {
            patcher().collect { patchResult ->
                val name = patchResult.patch.name ?: "(unnamed)"
                val error = patchResult.exception
                results += "$name=${error?.message ?: "ok"}"
                if (error != null) failures += "$name: ${error.message}"
            }
        }
        result = patcher.get()
    } finally {
        patcher.close()
    }

    println("patched: ${input.name} (${input.length() / 1024 / 1024} MB)")
    results.forEach { println("  $it") }

    val staging = Files.createTempDirectory("unipatches-stage-").toFile()
        .also { dir -> Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() }) }
    val replacements = stageResources(result, staging)
    val dexNames = result.dexFiles.map { it.name }.sorted()
    println("dex files: ${dexNames.joinToString()}")
    println("replacement resources: ${replacements.keys.sorted().take(20).joinToString()}" +
        if (replacements.size > 20) " (+${replacements.size - 20} more)" else "")
    println("deleted resources: ${result.resources.deleteResources.size}")

    assemble(input, result, replacements, output)
    println("wrote: ${output.absolutePath} (${output.length() / 1024 / 1024} MB)")

    staging.deleteRecursively()
    workspace.deleteRecursively()
    if (failures.isNotEmpty()) {
        System.err.println("patch failures:")
        failures.forEach { System.err.println("  $it") }
        kotlin.system.exitProcess(1)
    }
}

/** Parses `key=value;key=value` into ordered overrides; blank input means none. */
private fun parseOptionOverrides(raw: String?): List<Pair<String, String>> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
        val separator = entry.indexOf('=')
        require(separator > 0) { "option override needs key=value, got '$entry'" }
        entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
    }
}

/**
 * Applies one override to a patch option by name.
 *
 * The option's declared [KType] decides the parse, so a typo fails here instead of
 * silently patching with a default: a boolean becomes true/false, a list splits on commas.
 */
@Suppress("UNCHECKED_CAST")
private fun applyOption(patch: Patch<*>, key: String, raw: String) {
    // Options.get() throws for an unknown key rather than returning null.
    if (!patch.options.containsKey(key)) return
    val option = patch.options[key]
    val target = option as Option<Any?>
    val type = target.type
    target.value = when {
        type.classifier == Boolean::class -> raw.toBooleanStrictOrNull()
            ?: error("option '$key' expects true or false, got '$raw'")
        type.classifier == Int::class -> raw.toIntOrNull()
            ?: error("option '$key' expects an integer, got '$raw'")
        type.classifier == Set::class || type.classifier == List::class ->
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        else -> raw
    }
    println("option: $key=$raw (${patch.name})")
}

/** Copies every resource artefact the pipeline produced into [staging], keyed by APK entry name. */
private fun stageResources(result: PatcherResult, staging: File): Map<String, File> {
    val staged = LinkedHashMap<String, File>()
    val resources = result.resources
    for (source in listOfNotNull(resources.resourcesApk, resources.otherResources)) {
        when {
            !source.exists() -> continue
            source.isDirectory -> source.walkTopDown().filter { it.isFile }.forEach { file ->
                staged[file.relativeTo(source).invariantSeparatorsPath] = file
            }
            else -> ZipFile(source).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val target = File(staging, entry.name)
                    if (!target.parentFile.exists() && !target.parentFile.mkdirs()) {
                        error("cannot create ${target.parentFile}")
                    }
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    staged[entry.name] = target
                }
            }
        }
    }
    return staged
}

/**
 * Rebuilds the APK around the patched artefacts.
 *
 * Entry metadata is preserved for untouched entries (compression method, timestamps,
 * CRC) so nothing the installer inspects changes by accident.
 */
private fun assemble(
    apk: File,
    result: PatcherResult,
    replacements: Map<String, File>,
    output: File,
) {
    val dexBytes = LinkedHashMap<String, ByteArray>()
    for (dex in result.dexFiles.sortedBy { it.name }) {
        val bytes = dex.stream.readBytes()
        when (val problem = dexProblem(bytes)) {
            null -> dexBytes[dex.name] = bytes
            NO_CLASSES -> println(
                "dropped dex ${dex.name}: no class definitions, ${bytes.size / 1024 / 1024} MB of dead data",
            )
            else -> error("patched dex ${dex.name} would not load: $problem")
        }
    }
    val deleted = result.resources.deleteResources
    val storedNames = result.resources.doNotCompress
    ZipFile(apk).use { source ->
        // Original compression methods, so a replacement keeps the method its entry
        // shipped with. APKs that set extractNativeLibs=false ship lib/*.so STORED and
        // 4-byte aligned; re-deflating them produces an install that dies at dlopen.
        val originalMethods = HashMap<String, Int>()
        for (entry in source.entries()) originalMethods[entry.name] = entry.method
        val compress = { name: String ->
            when {
                name in storedNames -> false
                originalMethods[name] == ZipEntry.STORED -> false
                else -> true
            }
        }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output), 1 shl 20)).use { sink ->
            val handled = mutableSetOf<String>()
            for (entry in source.entries()) {
                val name = entry.name
                if (isSignatureEntry(name)) continue
                if (name in deleted) {
                    handled += name
                    continue
                }
                val dex = dexBytes[name]
                if (dex != null) {
                    writeBytes(sink, name, dex, compress(name))
                    handled += name
                    continue
                }
                val replacement = replacements[name]
                if (replacement != null) {
                    writeBytes(sink, name, replacement.readBytes(), compress(name))
                    handled += name
                    continue
                }
                sink.putNextEntry(ZipEntry(entry))
                source.getInputStream(entry).use { it.copyTo(sink) }
                sink.closeEntry()
                handled += name
            }
            for ((name, file) in replacements) {
                if (name in handled) continue
                // The resource pipeline hands back the original APK's META-INF verbatim,
                // including the stock BNDLTOOL/v1 signature files. Signing inputs must stay
                // clean or apksigner signs around a stale manifest.
                if (isSignatureEntry(name)) continue
                writeBytes(sink, name, file.readBytes(), compress(name))
            }
            for ((name, dex) in dexBytes) {
                if (name in handled) continue
                writeBytes(sink, name, dex, compress(name))
            }
        }
    }
}

/**
 * Validates the invariants ART enforces before it will map a dex.
 *
 * Returns null for a loadable file, [NO_CLASSES] for a structurally sound file that defines
 * nothing (which can simply be dropped), or a description of the broken field.
 *
 * Mirrors DexFileVerifier: `class_defs_off` must be 0 exactly when `class_defs_size` is 0,
 * every id table must fit inside the file, and `file_size` must match the real length. A dex
 * that fails this is not skipped silently -- the engine discards the entire file at runtime,
 * so a patch that produces one ships an APK that dies at launch.
 */
private const val NO_CLASSES = "no class definitions"

private fun dexProblem(bytes: ByteArray): String? {
    if (bytes.size < 0x70) return "truncated header (${bytes.size} bytes)"
    if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() ||
        bytes[2] != 'x'.code.toByte() || bytes[3] != '\n'.code.toByte() || bytes[7] != 0.toByte()
    ) {
        return "bad magic"
    }
    fun u32(offset: Int): Long = (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    val fileSize = u32(0x20)
    if (fileSize != bytes.size.toLong()) return "file_size $fileSize != ${bytes.size}"
    for (table in DEX_TABLES) {
        val size = u32(table.sizeOffset)
        val offset = u32(table.offsetOffset)
        if (size == 0L && offset != 0L) return "${table.label}: size 0 with stale offset $offset"
        if (size != 0L && offset + size * table.itemSize > fileSize) {
            return "${table.label}: $size entries at $offset overrun the file"
        }
    }
    return if (u32(0x60) == 0L) NO_CLASSES else null
}

private class DexTable(
    val label: String,
    val sizeOffset: Int,
    val offsetOffset: Int,
    val itemSize: Int,
)

private val DEX_TABLES = listOf(
    DexTable("string_ids", 0x38, 0x3C, 4),
    DexTable("type_ids", 0x40, 0x44, 4),
    DexTable("proto_ids", 0x48, 0x4C, 12),
    DexTable("field_ids", 0x50, 0x54, 8),
    DexTable("method_ids", 0x58, 0x5C, 8),
    DexTable("class_defs", 0x60, 0x64, 32),
)

private fun isSignatureEntry(name: String): Boolean {
    if (!name.startsWith("META-INF/")) return false
    if (name.indexOf('/') != name.lastIndexOf('/')) return false
    val upper = name.uppercase()
    return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
        upper.endsWith(".EC") || upper == "META-INF/MANIFEST.MF"
}

private fun writeBytes(sink: ZipOutputStream, name: String, bytes: ByteArray, compress: Boolean) {
    val entry = ZipEntry(name)
    if (compress) {
        entry.method = ZipEntry.DEFLATED
    } else {
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        val crc = CRC32()
        crc.update(bytes)
        entry.crc = crc.value
    }
    sink.putNextEntry(entry)
    sink.write(bytes)
    sink.closeEntry()
}
