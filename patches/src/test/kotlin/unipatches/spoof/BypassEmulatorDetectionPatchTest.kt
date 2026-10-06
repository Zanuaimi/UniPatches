package unipatches.spoof

import com.android.tools.smali.dexlib2.Opcode
import org.junit.Assert.assertFalse
import org.junit.Test

class BypassEmulatorDetectionPatchTest {
    @Test
    fun doesNotGenerateMoveResultReplacementWithoutDestinationRegister() {
        assertFalse(shouldReplaceResult(null, Opcode.MOVE_RESULT_OBJECT))
    }
}
