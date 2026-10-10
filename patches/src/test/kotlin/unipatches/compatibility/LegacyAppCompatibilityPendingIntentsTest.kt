package unipatches.compatibility

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21ih
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAppCompatibilityPendingIntentsTest {
    @Test
    fun immutableFlagPreservesUpdateCurrent() {
        assertEquals(0x0C000000, immutablePendingIntentFlags(FLAG_UPDATE_CURRENT))
    }

    @Test
    fun downloaderFlagsRejectMutableOrAlreadyImmutableButAllowExtraImmutableSafeFlags() {
        assertTrue(isFixedDownloaderPendingIntentFlags(FLAG_UPDATE_CURRENT))
        assertTrue(isFixedDownloaderPendingIntentFlags(FLAG_UPDATE_CURRENT or 0x10000000))
        assertFalse(isFixedDownloaderPendingIntentFlags(FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE))
        assertFalse(isFixedDownloaderPendingIntentFlags(FLAG_UPDATE_CURRENT or FLAG_MUTABLE))
    }

    @Test
    fun targetedRewriteRequiresExpectedConstantOpcodeRegisterAndFlags() {
        assertEquals(0x0C000000, replacementDownloaderPendingIntentFlags(Opcode.CONST_HIGH16, true, FLAG_UPDATE_CURRENT))
        assertNull(replacementDownloaderPendingIntentFlags(Opcode.CONST, true, FLAG_UPDATE_CURRENT))
        assertNull(replacementDownloaderPendingIntentFlags(Opcode.CONST_HIGH16, false, FLAG_UPDATE_CURRENT))
        assertEquals(0x1C000000, replacementDownloaderPendingIntentFlags(Opcode.CONST_HIGH16, true, FLAG_UPDATE_CURRENT or 0x10000000))
        assertNull(replacementDownloaderPendingIntentFlags(Opcode.CONST_HIGH16, true, FLAG_UPDATE_CURRENT or FLAG_MUTABLE))
    }

    @Test
    fun instructionRewriteAddsImmutableBitToRealConstHigh16AndPreservesRegister() {
        val original = BuilderInstruction21ih(Opcode.CONST_HIGH16, 3, FLAG_UPDATE_CURRENT)

        val replacement = replacementDownloaderPendingIntentInstruction(original, flagsRegister = 3)

        assertEquals(Opcode.CONST_HIGH16, replacement?.opcode)
        assertEquals(3, replacement?.registerA)
        assertEquals(FLAG_UPDATE_CURRENT, (original as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(
            FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
            (replacement as? NarrowLiteralInstruction)?.narrowLiteral,
        )
    }

    @Test
    fun immutableMergePreservesUnrelatedFlags() {
        val unrelatedFlags = 0x00000001 or 0x00000002
        assertEquals(unrelatedFlags or FLAG_IMMUTABLE, immutablePendingIntentFlags(unrelatedFlags))
    }

    @Test
    fun apkpureLicenseCheckerActionFixtureMatchesAndFailsClosedOnDrift() {
        val originalAction = BuilderInstruction21c(
            Opcode.CONST_STRING,
            4,
            ImmutableStringReference(APKPURE_LICENSE_ACTION_BASE64),
        )
        val changedAction = BuilderInstruction21c(
            Opcode.CONST_STRING,
            4,
            ImmutableStringReference("com.example.other.Service"),
        )

        assertTrue(hasApkPureLicenseAction(listOf(originalAction)))
        assertFalse(hasApkPureLicenseAction(listOf(changedAction)))
    }

    @Test
    fun licenseServiceIntentIsScopedToPlayWithoutReplacingLicenseAction() {
        assertEquals(
            """
                invoke-direct {v2, v3}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
                const-string v9, "com.android.vending"
                invoke-virtual {v2, v9}, Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;
            """.trimIndent(),
            licenseServiceIntentPackageBlock(intentRegister = 2, actionRegister = 3, packageRegister = 9),
        )
    }

    @Test
    fun licenseServicePackageBlockRejectsRegistersOutsideInvokeEncoding() {
        assertNull(licenseServiceIntentPackageBlock(intentRegister = 2, actionRegister = 3, packageRegister = 16))
    }
}
