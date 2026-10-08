package unipatches.frida

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FridaGadgetPatchTest {
    private fun assertFailure(message: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected failure containing $message")
        } catch (exception: IllegalArgumentException) {
            assertTrue(exception.message.orEmpty().contains(message))
        }
    }

    @Test
    fun scriptFailuresIdentifyMissingEmptyOversizedAndInvalidUtf8Inputs() {
        val file = File.createTempFile("frida-validation-", ".js")
        try {
            assertFailure("empty") { readUtf8Script(file) }
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertFailure("UTF-8") { readUtf8Script(file) }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
            assertFailure("limit") { readUtf8Script(file) }
        } finally {
            file.delete()
        }
        assertFailure(file.path) { readUtf8Script(file) }
    }

    @Test
    fun bundleSeparatesExpressionsAndTrailingCommentsWithoutWrappingScope() {
        val bundle = String(buildFridaBundle(
            ScriptSource("entry\nunsafe.js", "var shared = 1 // trailing comment"),
            listOf(ScriptSource("extra.js", "(function () { shared++; })()")),
        ))
        assertTrue(bundle.contains("var shared = 1 // trailing comment\n\n;\n"))
        assertTrue(bundle.contains("entry unsafe.js"))
        assertTrue(bundle.contains("(function () { shared++; })()"))
    }

    @Test
    fun selectsApkAbisAndRejectsUnsupportedDirectories() {
        assertEquals(listOf(FridaAbi.ARM), targetAbis(listOf("lib/armeabi-v7a/libgame.so")).values)
        assertEquals(FridaAbi.values().toList(), targetAbis(emptyList()).values)
        assertFailure("mips") { targetAbis(listOf("lib/mips/libgame.so")) }
    }

    @Test
    fun acceptsOnlyFinalJavaScriptExtension() {
        assertTrue(hasJavaScriptExtension("hook.js"))
        assertTrue(hasJavaScriptExtension("HOOK.JS"))
        assertFalse(hasJavaScriptExtension("jsonimage.json"))
        assertFalse(hasJavaScriptExtension("hook.js.json"))
        assertFalse(hasJavaScriptExtension("hook.jsonjs"))
        assertFalse(hasJavaScriptExtension("hook.js.txt"))
        assertFalse(hasJavaScriptExtension(".js"))
        assertFalse(hasJavaScriptExtension("directory.js/"))
    }

    @Test
    fun invalidAdditionalPathFailsWithFilename() {
        val valid = File.createTempFile("frida-extra-", ".js")
        val invalid = File.createTempFile("frida-extra-", ".json")
        try {
            valid.writeText("send('ok');")
            invalid.writeText("{}")
            assertFailure(invalid.path) { readAdditionalScripts(listOf(invalid.path, valid.path)) }
            assertEquals(listOf(valid.name), readAdditionalScripts(listOf(valid.path, valid.path)).map { it.name })
        } finally {
            valid.delete()
            invalid.delete()
        }
    }

    @Test
    fun gadgetValidationChecksElfTypeAndArchitecture() {
        val elf = ByteArray(20)
        elf[0] = 0x7f.toByte()
        elf[1] = 'E'.code.toByte()
        elf[2] = 'L'.code.toByte()
        elf[3] = 'F'.code.toByte()
        elf[4] = 2.toByte()
        elf[5] = 1.toByte()
        elf[16] = 3.toByte()
        elf[18] = 183.toByte()
        assertEquals(elf.toList(), validateGadget(elf, FridaAbi.ARM64).toList())
        assertFailure("does not match") { validateGadget(elf, FridaAbi.ARM) }
        elf[4] = 1
        assertFailure("class") { validateGadget(elf, FridaAbi.ARM64) }

        try {
            validateGadget("not an ELF".toByteArray())
            throw AssertionError("invalid Gadget should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    @Test
    fun bundleKeepsEntryBeforeAdditionalScripts() {
        val bundle = String(buildFridaBundle(
            ScriptSource("entry.js", "entry();"),
            listOf(
                ScriptSource("one.js", "one();"),
                ScriptSource("two.js", "two();"),
            ),
        ))
        assertTrue(bundle.indexOf("entry();") < bundle.indexOf("one();"))
        assertTrue(bundle.indexOf("one();") < bundle.indexOf("two();"))
    }
}
