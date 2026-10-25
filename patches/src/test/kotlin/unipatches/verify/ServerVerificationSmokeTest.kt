package unipatches.verify

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherResult
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Server verification preflight: applies the patch to a real APK and proves the
 * injected code actually resolves.
 *
 * The policy injection runs on every APK with a resolvable startup entry point, so
 * "at least one injected reference" is a hard requirement here: it covers both the
 * startup policy write and the extension DEX merge. Everything else (okhttp hooks,
 * Gson hooks, verifier forcing) is app-specific and reported, not required.
 *
 *   ./gradlew :patches:test --offline -PunipatchApk=path/to/game.apk
 *
 * Skipped unless an APK is supplied through UNIPATCHES_SMOKE_APK or -PunipatchApk,
 * so CI never needs a game binary.
 */
class ServerVerificationSmokeTest {
    private val runtimeType = "Lunipatch/verify/VerificationRuntime;"

    @Test
    fun injectsResolvableRuntimeCallsIntoAnyApk() {
        val apk = resolveApk()
        val temp = File(System.getProperty("java.io.tmpdir"), "unipatches-verify-${System.nanoTime()}")
        val patcher = Patcher(PatcherConfig(apk, temp))
        val results = mutableListOf<String>()
        val scan = try {
            patcher += setOf(serverVerificationBypassPatch)
            runBlocking {
                patcher().collect { result ->
                    results += "${result.patch.name}=${result.exception?.message ?: "ok"}"
                }
            }
            scanPatched(patcher.get().dexFiles)
        } finally {
            patcher.close()
        }

        val patchName = serverVerificationBypassPatch.name!!
        println("verify preflight: $apk")
        println(results.joinToString(prefix = "patch results: ", separator = "\n  "))
        println("call sites: ${scan.callSites.size} in ${scan.callSiteClasses.size} classes")
        println("runtime definitions merged: ${scan.definedTypes.contains(runtimeType)}")
        scan.callSites.take(12).forEach { println("  $it") }

        assertTrue(
            "Server verification patch did not complete cleanly on $apk: $results",
            results.any { it.startsWith(patchName) && it.endsWith("=ok") },
        )
        // Injected blocks resolve against the extension DEX; an unmerged extension means
        // NoClassDefFoundError on the first patched call, not a silent miss.
        assertTrue(
            "Injected code references $runtimeType, which the patched APK does not define " +
                "(extendWith(\"extensions/extension.mpe\") did not merge)",
            scan.definedTypes.contains(runtimeType),
        )
        assertTrue(
            "No call into $runtimeType was injected into $apk - the patch is a no-op here",
            scan.callSites.isNotEmpty(),
        )
        // Anything the injected blocks name must also be present, or the first call throws.
        val missing = scan.referencedRuntimeTypes - scan.definedTypes
        assertTrue("Injected code references missing runtime classes: $missing", missing.isEmpty())
    }

    private data class PatchScan(
        val definedTypes: Set<String>,
        val referencedRuntimeTypes: Set<String>,
        val callSites: List<String>,
        val callSiteClasses: Set<String>,
    )

    private fun scanPatched(dexFiles: Set<PatcherResult.PatchedDexFile>): PatchScan {
        val defined = mutableSetOf<String>()
        val referenced = mutableSetOf<String>()
        val callSites = mutableListOf<String>()
        val callSiteClasses = mutableSetOf<String>()
        for (dexFile in dexFiles) {
            val dex = DexBackedDexFile.fromInputStream(null, BufferedInputStream(dexFile.stream))
            for (cls in dex.classes) {
                defined += cls.type
                for (method in cls.methods) {
                    val implementation = method.implementation ?: continue
                    for (instruction in implementation.instructions) {
                        if (instruction !is ReferenceInstruction) continue
                        val reference = instruction.reference
                        if (reference is MethodReference && reference.definingClass == runtimeType) {
                            callSites += "${cls.type}->${method.name}():${reference.name}"
                            callSiteClasses += cls.type
                        }
                        val type = (reference as? MethodReference)?.definingClass
                            ?: (reference as? com.android.tools.smali.dexlib2.iface.reference.TypeReference)?.type
                        if (type != null && type.startsWith("Lunipatch/")) referenced += type
                    }
                }
            }
        }
        return PatchScan(defined, referenced, callSites, callSiteClasses)
    }

    private fun resolveApk(): File {
        val path = System.getenv("UNIPATCHES_SMOKE_APK")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("unipatchApk")?.takeIf { it.isNotBlank() }
        assumeTrue("no APK supplied - set UNIPATCHES_SMOKE_APK or pass -PunipatchApk=<apk>", path != null)
        val apk = File(path!!)
        assumeTrue("APK not found: $apk", apk.isFile)
        return apk
    }
}
