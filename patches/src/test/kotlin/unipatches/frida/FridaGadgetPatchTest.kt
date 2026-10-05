package unipatches.frida

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FridaGadgetPatchTest {
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
    fun invalidAdditionalPathIsSkippedAndNextPathIsRead() {
        val valid = File.createTempFile("frida-extra-", ".js")
        val invalid = File.createTempFile("frida-extra-", ".json")
        try {
            valid.writeText("send('ok');")
            invalid.writeText("{}")
            val scripts = readAdditionalScripts(listOf(invalid.path, valid.path))
            assertEquals(listOf(valid.name), scripts.map { it.name })
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
