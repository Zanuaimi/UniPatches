package unipatches.compatibility

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyAppCompatibilityReceiversTest {
    @Test
    fun unsupportedInvokeDiagnosticIncludesClassMethodAndReason() {
        assertEquals(
            "Legacy compatibility: skipped receiver fix in Lexample/Receiver;->onReceive; invoke register layout is not supported (expected exactly 3 registers in 35c/3rc form).",
            receiverSkipDiagnostic(
                "Lexample/Receiver;",
                "onReceive",
                "invoke register layout is not supported (expected exactly 3 registers in 35c/3rc form).",
            ),
        )
    }

    @Test
    fun scratchRegisterDiagnosticIncludesClassMethodAndReason() {
        assertEquals(
            "Legacy compatibility: skipped receiver fix in Lexample/Receiver;->onReceive; scratch registers cannot fit the branch-safe v0..v15 range.",
            receiverSkipDiagnostic(
                "Lexample/Receiver;",
                "onReceive",
                "scratch registers cannot fit the branch-safe v0..v15 range.",
            ),
        )
    }
}
