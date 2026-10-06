package unipatches.compatibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyAppCompatibilityRuntimeTest {
    @Test
    fun rewritesBothEnvironmentStorageGetters() {
        assertEquals(
            "$COMPAT_RUNTIME->legacyExternalStorageDirectory()Ljava/io/File;",
            legacyStorageReplacement("Landroid/os/Environment;", "getExternalStorageDirectory", emptyList(), "Ljava/io/File;"),
        )
        assertEquals(
            "$COMPAT_RUNTIME->legacyExternalStoragePublicDirectory(Ljava/lang/String;)Ljava/io/File;",
            legacyStorageReplacement("Landroid/os/Environment;", "getExternalStoragePublicDirectory", listOf("Ljava/lang/String;"), "Ljava/io/File;"),
        )
    }

    @Test
    fun leavesOtherFrameworkCallsUntouched() {
        assertNull(legacyStorageReplacement("Landroid/os/Environment;", "getDataDirectory", emptyList(), "Ljava/io/File;"))
        assertNull(legacyStorageReplacement("Landroid/os/Environment;", "getExternalStoragePublicDirectory", emptyList(), "Ljava/io/File;"))
        assertNull(legacyStorageReplacement("Lcom/example/Environment;", "getExternalStorageDirectory", emptyList(), "Ljava/io/File;"))
    }
}
