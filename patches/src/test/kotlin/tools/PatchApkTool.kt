package tools

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.patch.Patch
import kotlinx.coroutines.runBlocking
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
        System.err.println("usage: patchLocalApk <in.apk> <out.apk> [patch,patch,...]")
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
    output.parentFile?.mkdirs()

    val workspace = Files.createTempDirectory("unipatches-local-").toFile()
    val patcher = Patcher(PatcherConfig(input, workspace))
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
    val dexByName = result.dexFiles.associateBy { it.name }
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
                val dex = dexByName[name]
                if (dex != null) {
                    writeBytes(sink, name, dex.stream.readBytes(), compress(name))
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
            for ((name, dex) in dexByName) {
                if (name in handled) continue
                writeBytes(sink, name, dex.stream.readBytes(), compress(name))
            }
        }
    }
}

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
