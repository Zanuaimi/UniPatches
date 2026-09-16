package unipatches.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLauncherResolutionTest {
    @Test
    fun manifestLauncherMayUseDifferentJavaNamespace() {
        assertTrue(acceptsLauncherOwnership(manifestResolved = true, packageOwned = false))
        assertTrue(acceptsLauncherOwnership(manifestResolved = true, packageOwned = true))
    }

    @Test
    fun fallbackStillRequiresApplicationPackageOwnership() {
        assertFalse(acceptsLauncherOwnership(manifestResolved = false, packageOwned = false))
        assertTrue(acceptsLauncherOwnership(manifestResolved = false, packageOwned = true))
    }

    @Test
    fun directActivityAndUnityNativeChainsAreAccepted() {
        val parents = mapOf(
            "Lcom/example/DirectActivity;" to "Landroid/app/Activity;",
            "Lcom/unity/NativeGameActivity;" to "Lcom/unity3d/player/UnityPlayerNativeActivity;",
            "Lcom/unity3d/player/UnityPlayerNativeActivity;" to "Landroid/app/NativeActivity;",
        )

        assertTrue(hasRecognizedActivityAncestor("Lcom/example/DirectActivity;", parents::get))
        assertTrue(hasRecognizedActivityAncestor("Lcom/unity/NativeGameActivity;", parents::get))
    }

    @Test
    fun absentFrameworkClassCanStillBeRecognizedByDescriptor() {
        assertTrue(
            hasRecognizedActivityAncestor(
                "Lcom/example/NativeActivity;",
            ) { type ->
                if (type == "Lcom/example/NativeActivity;") "Landroid/app/NativeActivity;" else null
            },
        )
    }

    @Test
    fun missingAncestorAndCyclesAreRejected() {
        val cyclicParents = mapOf(
            "Lcom/example/One;" to "Lcom/example/Two;",
            "Lcom/example/Two;" to "Lcom/example/One;",
        )

        assertFalse(hasRecognizedActivityAncestor("Lcom/example/Missing;", emptyMap<String, String>()::get))
        assertFalse(hasRecognizedActivityAncestor("Lcom/example/One;", cyclicParents::get))
    }
}
