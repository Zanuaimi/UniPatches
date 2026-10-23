package unipatches.iap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The managed-catalog block injects invoke-static calls into a game's own
 * billing wrapper, so nothing in the build resolves those call sites. A
 * descriptor that does not exist in the extension compiles, patches and
 * installs cleanly, then throws NoSuchMethodError the first time the game
 * buys something. That shipped once already: the block called
 * seedManagedProduct(Ljava/util/Map;Ljava/lang/String;)V while the runtime
 * declares (Ljava/lang/Object;Ljava/lang/String;)V, and dev.40 crashed on
 * the GL thread at the injected call.
 *
 * This test reads both sides off disk and checks they agree, so a signature
 * change on either side fails the build instead of the device.
 */
class ManagedCatalogSeedContractTest {
    private val repositoryRoot: File = File(System.getProperty("user.dir")).let { working ->
        // Gradle runs tests with the module directory as the working directory.
        if (File(working, "extensions").isDirectory) working else working.parentFile
    }

    private val injectorSource: File =
        File(repositoryRoot, "patches/src/main/kotlin/unipatches/iap/EmulateInAppManaged.kt")

    private val runtimeSource: File =
        File(repositoryRoot, "extensions/extension/src/main/java/unipatch/overlaycore/InAppRuntimePolicy.java")

    /** Runtime methods the injected block calls, with the descriptor each side must agree on. */
    private val injectedCalls = listOf(
        // method name, declaration descriptor as emitted by the runtime, call-site form in the block
        Triple("markConnectionReady", "()V", "markConnectionReady()V"),
        Triple("seedManagedProduct", "(Ljava/lang/Object;Ljava/lang/String;)V", "seedManagedProduct(Ljava/lang/Object;Ljava/lang/String;)V"),
        Triple("seedManagedSku", "(Ljava/lang/Object;Ljava/lang/String;)V", "seedManagedSku(Ljava/lang/Object;Ljava/lang/String;)V"),
    )

    private fun javaParameterDescriptor(name: String, returnType: String): String {
        val text = runtimeSource.readText()
        val declaration = Regex(
            """(?:public|private|protected)\s+(?:static\s+)?(?:synchronized\s+)?(?:final\s+)?\S+(?:<[^>]*>)?\s+$name\s*\(([^)]*)\)\s*\{"""
        ).find(text)?.groupValues?.get(1)
            ?: error("could not find a declaration of $name in ${runtimeSource.path}")

        val descriptors = declaration.trim()
            .removePrefix("synchronized ")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parameter ->
                val type = parameter.substringBefore(' ').trim()
                mapOf("String" to "Ljava/lang/String;", "Object" to "Ljava/lang/Object;", "Map" to "Ljava/util/Map;")[type]
                    ?: error("unmapped parameter '$parameter' on $name")
            }

        return "(" + descriptors.joinToString(separator = "") + ")" + returnType
    }

    @Test
    fun injectorCallsTheSeedEntryPointsTheExtensionActuallyDeclares() {
        val block = injectorSource.readText()
        for ((method, declaration, _) in injectedCalls) {
            assertTrue(
                "EmulateInAppManaged.kt no longer calls $method",
                block.contains(method),
            )
            assertEquals(
                "descriptor drift on $method: the runtime declares a different signature",
                declaration,
                javaParameterDescriptor(method, "V"),
            )
        }
    }

    @Test
    fun injectorEmitsTheDescriptorTheRuntimeDeclares() {
        val block = injectorSource.readText()
        // The block builds the call as a template, so the literal descriptor is
        // what lands in the game dex. A Map-typed catalog register still binds
        // to an Object parameter, so Object is the descriptor to emit.
        assertTrue(
            "the injected seed call must use the Object descriptor the runtime declares",
            block.contains("(Ljava/lang/Object;Ljava/lang/String;)V"),
        )
        assertTrue(
            "no seed call may still reference the Map descriptor",
            !block.contains("(Ljava/util/Map;Ljava/lang/String;)V"),
        )
    }
}
