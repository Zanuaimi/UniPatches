package unipatches.iap

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherResult
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * InApp preflight: patches a real APK and reports every billing entry point the
 * patch injected into.
 *
 * Nothing is hardcoded to a reference game. Entry points are discovered from
 * the APK under test and judged by growth, because every strategy in this patch
 * prepends its block - an injected method can only be longer than the stock
 * one. Point it at a game before shipping the bundle to the device:
 *
 *   ./gradlew :patches:test --offline -PunipatchApk=path/to/game.apk
 *
 * Skipped unless an APK is supplied through UNIPATCHES_SMOKE_APK or
 * -PunipatchApk, so CI never needs a game binary.
 */
class RealApkInAppSmokeTest {
    /** Entry points the patch targets. Discovery scans only these names. */
    private val watched = setOf(
        "launchBillingFlow", "startConnection", "endConnection", "isReady",
        "getConnectionState", "isFeatureSupported", "consumeAsync", "acknowledgePurchase",
        "getResponseCode", "queryProductDetailsAsync", "querySkuDetailsAsync",
    )

    private data class Entry(val instructions: Int, val firstOpcode: Opcode?)

    @Test
    fun injectionLandsOnTheBillingEntryPointOfAnyApk() {
        val apk = resolveApk()

        val stock = stockEntries(apk)

        val patchResults = mutableListOf<String>()
        val temp = File(System.getProperty("java.io.tmpdir"), "unipatches-smoke-${System.nanoTime()}")
        val patcher = Patcher(PatcherConfig(apk, temp))
        val scan = try {
            patcher += setOf(emulateInAppPatch)
            runBlocking {
                patcher().collect { result ->
                    patchResults += "${result.patch.name}=${result.exception?.message ?: "ok"}"
                }
            }
            scanPatched(patcher.get().dexFiles)
        } finally {
            patcher.close()
        }
        val patched = scan.entries

        val inAppName = emulateInAppPatch.name!!
        println("preflight: $apk")
        println(patchResults.joinToString(prefix = "patch results: ", separator = "\n  "))
        val report = stock.keys.sorted().joinToString(separator = "\n  ") { key ->
            val before = stock.getValue(key).instructions
            val after = patched[key]?.instructions
            val patchedEntry = patched[key]
            val verdict = when {
                patchedEntry == null -> "MISSING from patched dex"
                patchedEntry.instructions > before -> "injected first=${patchedEntry.firstOpcode}"
                patchedEntry.instructions < before -> "shrunk (unexpected)"
                else -> "untouched"
            }
            "$key stock=$before patched=${patchedEntry?.instructions ?: "-"} $verdict"
        }
        if (report.isNotBlank()) println("entry points:\n  $report")

        assertTrue(
            "InApp patch did not complete cleanly on $apk: $patchResults",
            patchResults.any { it.startsWith(inAppName) && it.endsWith("=ok") },
        )
        // The injected blocks resolve against the Overlay Core runtime (extension DEX). If the
        // extension never merged, the host throws NoClassDefFoundError on the first patched
        // billing call and aborts on the pending exception - so every referenced runtime class
        // has to be defined in the patched output, not merely referenced.
        val missingRuntime = scan.referencedRuntimeTypes - scan.definedTypes
        assertTrue(
            "Injected code references Overlay Core classes missing from the patched APK " +
                "(extendWith(\"extensions/extension.mpe\") did not merge): $missingRuntime",
            missingRuntime.isEmpty(),
        )
        if (scan.referencedRuntimeTypes.isNotEmpty()) {
            println("runtime closure: ${scan.referencedRuntimeTypes.size} referenced, all defined")
        }
        // Negative case: an APK without a single billing class must still take the
        // patch. The bundle is meant to be applied blindly, so a no-op run is a
        // pass as long as nothing in the patch exploded.
        if (stock.isEmpty()) {
            println("no watched billing entry point in $apk - patch applied cleanly")
            return
        }
        val injected = stock.keys.filter { key ->
            (patched[key]?.instructions ?: 0) > stock.getValue(key).instructions
        }
        assertTrue(
            "No billing entry point was injected into $apk - it will not be " +
                "patched by InApp Emulation. Entry points:\n  $report",
            injected.isNotEmpty(),
        )
    }

    private fun resolveApk(): File {
        val path = System.getenv("UNIPATCHES_SMOKE_APK")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("unipatchApk")?.takeIf { it.isNotBlank() }
        assumeTrue("no APK supplied - set UNIPATCHES_SMOKE_APK or pass -PunipatchApk=<apk>", path != null)
        val apk = File(path!!)
        assumeTrue("APK not found: $apk", apk.isFile)
        return apk
    }

    /** Reads the watched entry points from every dex inside the stock APK. */
    private fun stockEntries(apk: File): Map<String, Entry> {
        val found = mutableMapOf<String, Entry>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.matches(Regex("""classes\d*\.dex"""))) continue
                val dex = DexBackedDexFile.fromInputStream(null, BufferedInputStream(zip.getInputStream(entry)))
                collectInto(dex.classes, found)
            }
        }
        return found
    }

    /** Entry points plus the Overlay Core runtime closure of the patched output. */
    private data class PatchScan(
        val entries: Map<String, Entry>,
        val definedTypes: Set<String>,
        val referencedRuntimeTypes: Set<String>,
    )

    private fun scanPatched(dexFiles: Set<PatcherResult.PatchedDexFile>): PatchScan {
        val entries = mutableMapOf<String, Entry>()
        val defined = mutableSetOf<String>()
        val referenced = mutableSetOf<String>()
        for (dexFile in dexFiles) {
            val dex = DexBackedDexFile.fromInputStream(null, BufferedInputStream(dexFile.stream))
            collectInto(dex.classes, entries)
            for (cls in dex.classes) {
                defined += cls.type
                for (method in cls.methods) {
                    val implementation = method.implementation ?: continue
                    for (instruction in implementation.instructions) {
                        if (instruction !is ReferenceInstruction) continue
                        val type = when (val reference = instruction.reference) {
                            is MethodReference -> reference.definingClass
                            is FieldReference -> reference.definingClass
                            is TypeReference -> reference.type
                            else -> null
                        } ?: continue
                        if (type.startsWith("Lunipatch/")) referenced += type
                    }
                }
            }
        }
        return PatchScan(entries, defined, referenced)
    }

    private fun collectInto(classes: Iterable<ClassDef>, into: MutableMap<String, Entry>) {
        for (cls in classes) {
            for (method in cls.methods) {
                if (method.name !in watched) continue
                // Interface and abstract declarations have no body to inject into;
                // only concrete implementations can become patch targets.
                val implementation = method.implementation ?: continue
                val instructions = implementation.instructions
                into["${cls.type}->${method.name}"] = Entry(
                    instructions.count(),
                    instructions.firstOrNull()?.opcode,
                )
            }
        }
    }
}
