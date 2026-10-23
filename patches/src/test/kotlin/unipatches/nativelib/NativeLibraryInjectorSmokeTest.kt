package unipatches.nativelib

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Real-APK smoke test for the native library injector.
 *
 * Runs only when both inputs are supplied:
 *
 *   $env:UNIPATCHES_SMOKE_APK = "path/to/app.apk"
 *   $env:UNIPATCHES_NATIVE_LIB = "path/to/libTool.so"
 *   .\gradlew.bat :patches:test --tests unipatches.nativelib.NativeLibraryInjectorSmokeTest
 *
 * Verifies the staged APK-root file, the injected `System.loadLibrary` call, and
 * the final APK entry compression. It does not install the APK; a device test is
 * still required to prove JNI_OnLoad runs.
 *
 * Set `UNIPATCHES_EXPECT_STORED=true` for an APK whose manifest has
 * `android:extractNativeLibs="false"`; the test then requires the new `.so` to be
 * STORED rather than DEFLATED.
 */
class NativeLibraryInjectorSmokeTest {
    @Test
    fun stagesLibraryAndInjectsLoadCall() {
        val apk = resolveInput("UNIPATCHES_SMOKE_APK", "unipatchApk")
        val library = resolveInput("UNIPATCHES_NATIVE_LIB", "unipatchNativeLib")
        val abi = requireNotNull(detectElfAbi(library)) {
            "${library.name} is not a supported ELF shared object"
        }
        val libraryName = resolveLibraryName(library, null)
        val expectedEntry = libraryEntryName(abi, libraryName)
        val expectStored = System.getenv("UNIPATCHES_EXPECT_STORED")
            ?.equals("true", ignoreCase = true) == true

        val temp = File(System.getProperty("java.io.tmpdir"), "unipatches-native-smoke-${System.nanoTime()}")
        val outputApk = File(temp, "patched.apk")
        val patcher = Patcher(PatcherConfig(apk, temp))
        val results = mutableListOf<String>()

        try {
            nativeLibraryInjectorPatch.options["nativeLibraryFile"] = library.absolutePath
            nativeLibraryInjectorPatch.options["nativeLibraryName"] = ""
            nativeLibraryInjectorPatch.options["nativeLibraryLoadTarget"] = "auto"
            nativeLibraryInjectorPatch.options["nativeLibraryRequireAbi"] = true

            patcher += setOf(nativeLibraryInjectorPatch)
            runBlocking {
                patcher().collect { result ->
                    results += "${result.patch.name}=${result.exception?.message ?: "ok"}"
                }
            }

            println("native injector results: ${results.joinToString()}")
            assertTrue(
                "Native Library Injector did not complete cleanly on $apk: $results",
                results.any { it.startsWith("${nativeLibraryInjectorPatch.name}=") && it.endsWith("=ok") },
            )

            val patched = patcher.get()
            val otherResources = patched.resources.otherResources
            val staged = otherResources?.resolve(expectedEntry)
            assertTrue(
                "Missing staged library $expectedEntry under ${otherResources ?: "<no other resources>"}",
                staged != null && staged.isFile,
            )
            assertEquals("Staged library size changed", library.length(), staged!!.length())
            val uncompressed = patched.resources.doNotCompress.contains(expectedEntry)
            println("staged: $expectedEntry (${staged.length()} bytes)")
            println("doNotCompress: $uncompressed (total ${patched.resources.doNotCompress.size})")

            with(ApkUtils) {
                patched.applyTo(outputApk)
            }
            ZipFile(outputApk).use { zip ->
                val entry = zip.getEntry(expectedEntry)
                if (entry == null) {
                    fail("Final APK is missing $expectedEntry")
                }
                assertEquals("Final APK entry size changed", library.length(), entry.size)
                println("final APK: $expectedEntry method=${entry.method} size=${entry.size}")
                if (expectStored) {
                    assertTrue(
                        "extractNativeLibs=false requires $expectedEntry to be STORED, " +
                            "but it was method ${entry.method}",
                        uncompressed && entry.method == ZipEntry.STORED,
                    )
                }

                val loaded = zip.entries().asSequence()
                    .filter { it.name.matches(Regex("""classes\d*\.dex""")) }
                    .any { dexEntry ->
                        zip.getInputStream(dexEntry).use { containsLoadCall(it, libraryName) }
                    }
                assertTrue(
                    "No System.loadLibrary(\"$libraryName\") call found in the final APK dex files",
                    loaded,
                )
                println("injected load call: System.loadLibrary(\"$libraryName\")")
            }
        } finally {
            nativeLibraryInjectorPatch.options.values.forEach { it.reset() }
            patcher.close()
            outputApk.delete()
        }
    }

    private fun containsLoadCall(stream: InputStream, libraryName: String): Boolean {
        val dex = DexBackedDexFile.fromInputStream(null, BufferedInputStream(stream))
        for (classDef in dex.classes) {
            for (method in classDef.methods) {
                if (method.name != "onCreate") continue
                val instructions = method.implementation?.instructions?.toList() ?: continue
                if (hasLoadSequence(instructions, libraryName)) return true
            }
        }
        return false
    }

    private fun hasLoadSequence(instructions: List<Instruction>, libraryName: String): Boolean =
        instructions.indices.any { index ->
            val constString = instructions[index] as? ReferenceInstruction ?: return@any false
            if (constString.opcode != Opcode.CONST_STRING) return@any false
            if ((constString as OneRegisterInstruction).registerA != 0) return@any false
            val value = (constString.reference as? StringReference)?.string ?: return@any false
            if (value != libraryName) return@any false

            val load = instructions.getOrNull(index + 1) as? ReferenceInstruction ?: return@any false
            if (load.opcode != Opcode.INVOKE_STATIC) return@any false
            val target = load.reference as? MethodReference ?: return@any false
            target.definingClass == "Ljava/lang/System;" && target.name == "loadLibrary"
        }

    private fun resolveInput(env: String, property: String): File {
        val path = System.getenv(env)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(property)?.takeIf { it.isNotBlank() }
        assumeTrue("set $env or -P$property=<file>", path != null)
        val file = File(path!!)
        assumeTrue("file not found: $file", file.isFile)
        return file
    }
}
