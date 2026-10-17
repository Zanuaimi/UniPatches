package unipatches.compatibility

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction12x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class LegacyAppCompatibilityFileUrisTest {
    private fun manifest(xml: String = "") = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
    }.newDocumentBuilder().parse(
        ByteArrayInputStream(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
              <application>$xml</application>
            </manifest>
            """.trimIndent().toByteArray(),
        ),
    )

    private fun resources() = Files.createTempDirectory("legacy-fileprovider-test").toFile().also { dir ->
        File(dir, "res/values").mkdirs()
        File(dir, "res/values/public.xml").writeText(
            """<resources id="0x7f" package="com.example.app"><public id="0x7f010000" type="attr" name="existing" /></resources>""",
        )
    }

    @Test
    fun `finds provider path resource variants`() {
        assertEquals(
            listOf(
                "res/xml-v24/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml",
                "res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml",
            ),
            legacyFileProviderPathsEntries(
                listOf(
                    "res/xml-v24/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml",
                    "res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml",
                    "res/xml-v24/other.xml",
                    "res/xmlx/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml",
                ),
            ),
        )
    }

    @Test
    fun `unsafe qualified provider paths fail before base resource is created`() {
        val dir = resources()
        val paths = File(dir, "res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml")
        val qualified = File(dir, "res/xml-v24/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml").apply {
            parentFile.mkdirs()
            writeText("<paths><root-path name=\"all\" path=\".\" /></paths>")
        }
        try {
            ensureLegacyFileProvider(
                manifest(),
                paths,
                File(dir, "res/values/public.xml"),
                pathsResourceExists = false,
                additionalExistingPathsFiles = listOf(qualified),
            )
            fail("Expected unsafe qualified paths to fail closed")
        } catch (_: IllegalArgumentException) {
            assertFalse(paths.exists())
        }
    }

    @Test
    fun `recognizes a bounded file URI to Intent setter flow`() {
        val fromFile = BuilderInstruction35c(
            Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0,
            ImmutableMethodReference("Landroid/net/Uri;", "fromFile", listOf("Ljava/io/File;"), "Landroid/net/Uri;"),
        )
        val setter = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 3, 0, 3, 4, 0, 0,
            ImmutableMethodReference("Landroid/content/Intent;", "setDataAndType", listOf("Landroid/net/Uri;", "Ljava/lang/String;"), "Landroid/content/Intent;"),
        )
        val instructions = listOf(
            fromFile,
            BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
            BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                ImmutableMethodReference("Landroid/webkit/MimeTypeMap;", "getSingleton", emptyList(), "Landroid/webkit/MimeTypeMap;"),
            ),
            BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
            setter,
        )
        assertEquals(ShareConsumer(4, 0), fileUriShareConsumer(instructions, 1))
    }

    @Test
    fun `skips unrecognized URI use and register overwrite`() {
        val fromFile = BuilderInstruction35c(
            Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0,
            ImmutableMethodReference("Landroid/net/Uri;", "fromFile", listOf("Ljava/io/File;"), "Landroid/net/Uri;"),
        )
        val moveResult = BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3)
        val unrelatedUse = BuilderInstruction35c(
            Opcode.INVOKE_STATIC, 1, 3, 0, 0, 0, 0,
            ImmutableMethodReference("Lexample/Other;", "consume", listOf("Landroid/net/Uri;"), "V"),
        )
        val setter = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 2, 0, 3, 0, 0, 0,
            ImmutableMethodReference("Landroid/content/Intent;", "setData", listOf("Landroid/net/Uri;"), "Landroid/content/Intent;"),
        )
        assertEquals(null, fileUriShareConsumer(listOf(fromFile, moveResult, unrelatedUse, setter), 1))
        assertEquals(
            null,
            fileUriShareConsumer(
                listOf(fromFile, moveResult, BuilderInstruction21c(Opcode.CONST_STRING, 3, ImmutableStringReference("overwritten")), setter),
                1,
            ),
        )
    }

    @Test
    fun `EXTRA_STREAM key must remain unchanged until putExtra`() {
        val fromFile = BuilderInstruction35c(
            Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0,
            ImmutableMethodReference("Landroid/net/Uri;", "fromFile", listOf("Ljava/io/File;"), "Landroid/net/Uri;"),
        )
        val putExtra = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 3, 0, 1, 3, 0, 0,
            ImmutableMethodReference("Landroid/content/Intent;", "putExtra", listOf("Ljava/lang/String;", "Landroid/os/Parcelable;"), "Landroid/content/Intent;"),
        )
        val valid = listOf(
            fromFile,
            BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
            BuilderInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("android.intent.extra.STREAM")),
            putExtra,
        )
        assertEquals(ShareConsumer(3, 0), fileUriShareConsumer(valid, 1))
        val overwritten = valid.toMutableList().apply {
            add(3, BuilderInstruction12x(Opcode.MOVE_OBJECT, 1, 4))
        }
        assertEquals(null, fileUriShareConsumer(overwritten, 1))
    }

    @Test
    fun `adds private provider paths and public resource id then reads back`() {
        val dir = resources()
        val paths = File(dir, "res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml")
        val publicXml = File(dir, "res/values/public.xml")
        val manifest = manifest()

        assertTrue(ensureLegacyFileProvider(manifest, paths, publicXml, pathsResourceExists = false) > 0)
        assertTrue(paths.isFile)
        assertTrue(
            readSecureXml(publicXml).getElementsByTagName("public").let { entries ->
                (0 until entries.length).any { index ->
                    (entries.item(index) as org.w3c.dom.Element).let {
                        it.getAttribute("type") == "xml" && it.getAttribute("name") == LEGACY_FILE_PROVIDER_PATHS_RESOURCE
                    }
                }
            },
        )
        verifyLegacyFileProvider(manifest, paths)
        assertEquals(
            0,
            ensureLegacyFileProvider(manifest, paths, publicXml, pathsResourceExists = true),
        )
    }

    @Test
    fun `refuses duplicate public resource ids instead of guessing`() {
        val dir = resources()
        val publicXml = File(dir, "res/values/public.xml").apply {
            writeText(
                """<resources id="0x7f" package="com.example.app"><public id="0x7f080000" type="xml" name="$LEGACY_FILE_PROVIDER_PATHS_RESOURCE" /><public id="0x7f080001" type="xml" name="$LEGACY_FILE_PROVIDER_PATHS_RESOURCE" /></resources>""",
            )
        }
        try {
            ensurePublicXmlResourceId(publicXml, "xml", LEGACY_FILE_PROVIDER_PATHS_RESOURCE)
            fail("Expected duplicate public entries to fail closed")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `existing provider path resource without public id fails closed`() {
        val dir = resources()
        val paths = File(dir, "res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml").apply {
            parentFile.mkdirs()
            writeSecureXml(legacyFileProviderPathsDocument(), this)
        }
        try {
            ensureLegacyFileProvider(
                manifest(),
                paths,
                File(dir, "res/values/public.xml"),
                pathsResourceExists = true,
            )
            fail("Expected existing resource without public ID to fail closed")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `refuses duplicate public resource ids across names`() {
        val dir = resources()
        val publicXml = File(dir, "res/values/public.xml").apply {
            writeText(
                """<resources id="0x7f" package="com.example.app"><public id="0x7f010000" type="attr" name="one" /><public id="0x7f010000" type="drawable" name="two" /></resources>""",
            )
        }
        try {
            ensurePublicXmlResourceId(publicXml, "xml", LEGACY_FILE_PROVIDER_PATHS_RESOURCE)
            fail("Expected duplicate resource IDs to fail closed")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `refuses an authority collision`() {
        val document = manifest(
            """<provider android:name="com.example.OtherProvider" android:authorities="com.example.other;com.example.app.legacycompat.fileprovider" />""",
        )
        val dir = resources()
        try {
            ensureLegacyFileProvider(
                document,
                File(dir, "res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml"),
                File(dir, "res/values/public.xml"),
                pathsResourceExists = false,
            )
            fail("Expected provider authority collision to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `refuses conflicting path resource instead of replacing it`() {
        val dir = resources()
        val paths = File(dir, "res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml").apply {
            parentFile.mkdirs()
            writeText("<paths><root-path name=\"all-files\" path=\"/\" /></paths>")
        }
        try {
            ensureLegacyFileProvider(
                manifest(),
                paths,
                File(dir, "res/values/public.xml"),
                pathsResourceExists = true,
            )
            fail("Expected unsafe existing paths XML to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertTrue(paths.readText().contains("root-path"))
    }

    @Test
    fun `readback rejects exported or permission-gated provider`() {
        val dir = resources()
        val paths = File(dir, "res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml")
        val publicXml = File(dir, "res/values/public.xml")
        val document = manifest()
        ensureLegacyFileProvider(document, paths, publicXml, pathsResourceExists = false)
        val provider = document.getElementsByTagName("provider").item(0) as org.w3c.dom.Element
        provider.setAttribute("android:exported", "true")
        try {
            verifyLegacyFileProvider(document, paths)
            fail("Expected exported provider to fail readback")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertFalse(provider.getAttribute("android:exported").isEmpty())
        provider.setAttribute("android:exported", "false")
        provider.setAttribute("android:process", ":isolated")
        try {
            verifyLegacyFileProvider(document, paths)
            fail("Expected provider process isolation to fail readback")
        } catch (_: IllegalStateException) {
            // expected
        }
        provider.removeAttribute("android:process")
        (document.getElementsByTagName("application").item(0) as org.w3c.dom.Element)
            .setAttribute("android:permission", "com.example.SIGNATURE")
        try {
            verifyLegacyFileProvider(document, paths)
            fail("Expected application-level permission inheritance to fail readback")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
