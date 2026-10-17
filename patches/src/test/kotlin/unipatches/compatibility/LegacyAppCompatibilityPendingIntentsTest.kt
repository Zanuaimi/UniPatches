package unipatches.compatibility

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21ih
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
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
    fun googleExpansionDownloaderPendingIntentTargetsStayExact() {
        assertEquals(
            listOf(
                "Lcom/google/android/vending/expansion/downloader_impl/DownloaderActivity;" to "finishOnCreate",
                "Lcom/google/android/vending/expansion/downloader_impl/DownloaderActivity\$UnpackingNotificationListener;" to "<init>",
            ),
            downloaderPendingIntentTargets.map { it.classType to it.methodName },
        )
        assertEquals(listOf("Lcom/google/android/vending/expansion/downloader_impl/DownloaderActivity;", "I", "I"), downloaderPendingIntentTargets[1].parameterTypes)
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
    fun explicitPlayPackageVerifierChecksSameIntentAndPreservesLicenseAction() {
        val decode = BuilderInstruction35c(
            Opcode.INVOKE_STATIC, 1, 4, 0, 0, 0, 0,
            ImmutableMethodReference("Lcom/google/android/vending/licensing/util/Base64;", "decode", listOf("Ljava/lang/String;"), "[B"),
        )
        val decodedBytes = BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 5)
        val stringConstructor = BuilderInstruction35c(
            Opcode.INVOKE_DIRECT, 2, 3, 5, 0, 0, 0,
            ImmutableMethodReference("Ljava/lang/String;", "<init>", listOf("[B"), "V"),
        )
        val constructor = BuilderInstruction35c(
            Opcode.INVOKE_DIRECT, 2, 2, 3, 0, 0, 0,
            ImmutableMethodReference("Landroid/content/Intent;", "<init>", listOf("Ljava/lang/String;"), "V"),
        )
        val packageString = BuilderInstruction21c(Opcode.CONST_STRING, 9, ImmutableStringReference("com.android.vending"))
        val setPackage = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 2, 2, 9, 0, 0, 0,
            ImmutableMethodReference("Landroid/content/Intent;", "setPackage", listOf("Ljava/lang/String;"), "Landroid/content/Intent;"),
        )
        val licenseAction = BuilderInstruction21c(Opcode.CONST_STRING, 4, ImmutableStringReference(APKPURE_LICENSE_ACTION_BASE64))
        val bindService = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 4, 1, 2, 6, 7, 0,
            ImmutableMethodReference(
                "Landroid/content/Context;", "bindService",
                listOf("Landroid/content/Intent;", "Landroid/content/ServiceConnection;", "I"), "Z",
            ),
        )
        val wrongIntentBind = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 4, 1, 5, 6, 7, 0,
            ImmutableMethodReference(
                "Landroid/content/Context;", "bindService",
                listOf("Landroid/content/Intent;", "Landroid/content/ServiceConnection;", "I"), "Z",
            ),
        )

        val otherIntentSetPackage = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 2, 5, 9, 0, 0, 0,
            ImmutableMethodReference("Landroid/content/Intent;", "setPackage", listOf("Ljava/lang/String;"), "Landroid/content/Intent;"),
        )
        val validBinding = listOf(licenseAction, decode, decodedBytes, stringConstructor, constructor, packageString, setPackage, bindService)
        assertTrue(hasPlayScopedLicenseServiceIntent(validBinding))
        assertFalse(hasExplicitPlayPackage(listOf(constructor, packageString, otherIntentSetPackage)))
        assertFalse(hasPlayScopedLicenseServiceIntent(validBinding.dropLast(1) + wrongIntentBind))
        assertFalse(hasPlayScopedLicenseServiceIntent(listOf(licenseAction, decode, decodedBytes, stringConstructor, constructor, bindService, packageString, setPackage)))
        assertFalse(hasPlayScopedLicenseServiceIntent(listOf(licenseAction, decode, decodedBytes, stringConstructor, constructor, packageString, setPackage)))
        assertFalse(hasPlayScopedLicenseServiceIntent(listOf(constructor, packageString, setPackage, bindService)))
    }

    @Test
    fun licenseServicePackageBlockRejectsRegistersOutsideInvokeEncoding() {
        assertNull(licenseServiceIntentPackageBlock(intentRegister = 2, actionRegister = 3, packageRegister = 16))
    }
}
