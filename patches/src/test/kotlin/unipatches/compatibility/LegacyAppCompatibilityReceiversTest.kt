package unipatches.compatibility

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun appScopedActionsUsePrivateFlag() {
        assertEquals(
            RECEIVER_NOT_EXPORTED,
            receiverFlagsForActions(setOf("com.glu.gunbros2.action.STATE"), "com.glu.gunbros2"),
        )
    }

    @Test
    fun systemAndKnownExternalActionsUseExportedFlag() {
        assertEquals(RECEIVER_EXPORTED, receiverFlagsForActions(setOf("android.intent.action.SCREEN_ON"), "com.glu.gunbros2"))
        assertEquals(RECEIVER_EXPORTED, receiverFlagsForActions(setOf("com.amazon.device.ads.ACTION"), "com.glu.gunbros2"))
    }

    @Test
    fun vendorNamespaceCollisionsFailClosed() {
        assertNull(receiverFlagsForActions(setOf("com.amazon.internal.refresh"), "com.glu.gunbros2"))
        assertNull(receiverFlagsForActions(setOf("com.android.internal.refresh"), "com.glu.gunbros2"))
        assertNull(receiverFlagsForActions(setOf("com.google.android.internal.refresh"), "com.glu.gunbros2"))
    }

    @Test
    fun mixedAppAndExternalActionsPreferExportedFlag() {
        assertEquals(
            RECEIVER_EXPORTED,
            receiverFlagsForActions(
                setOf("com.glu.gunbros2.action.STATE", "android.intent.action.SCREEN_ON"),
                "com.glu.gunbros2",
            ),
        )
    }

    @Test
    fun resolvesOnlyActionsFromTheFilterPassedToEachCall() {
        val stringConstructor = ImmutableMethodReference(
            "Landroid/content/IntentFilter;",
            "<init>",
            listOf("Ljava/lang/String;"),
            "V",
        )
        val instructions = listOf(
            BuilderInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Landroid/content/IntentFilter;")),
            BuilderInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("com.example.app.ACTION_PRIVATE")),
            BuilderInstruction35c(Opcode.INVOKE_DIRECT, 2, 0, 1, 0, 0, 0, stringConstructor),
            BuilderInstruction21c(Opcode.NEW_INSTANCE, 4, ImmutableTypeReference("Landroid/content/IntentFilter;")),
            BuilderInstruction21c(Opcode.CONST_STRING, 5, ImmutableStringReference("android.intent.action.SCREEN_ON")),
            BuilderInstruction35c(Opcode.INVOKE_DIRECT, 2, 4, 5, 0, 0, 0, stringConstructor),
            BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL,
                3,
                2,
                3,
                4,
                0,
                0,
                ImmutableMethodReference(
                    "Landroid/content/Context;",
                    "registerReceiver",
                    listOf("Landroid/content/BroadcastReceiver;", "Landroid/content/IntentFilter;"),
                    "Landroid/content/Intent;",
                ),
            ),
        )


        assertEquals(setOf("android.intent.action.SCREEN_ON"), resolvedReceiverFilterActions(instructions, 6, 4))
    }


    @Test
    fun unresolvedFilterActionsAreSkipped() {
        val noArgConstructor = ImmutableMethodReference("Landroid/content/IntentFilter;", "<init>", emptyList(), "V")
        val instructions = listOf(
            BuilderInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Landroid/content/IntentFilter;")),
            BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, noArgConstructor),
            BuilderInstruction10x(Opcode.NOP),
        )


        assertNull(resolvedReceiverFilterActions(instructions, 2, 0))
    }

    @Test
    fun unknownActionNamespacesAndMissingPackageFailClosed() {
        assertNull(receiverFlagsForActions(setOf("com.unclassified.sdk.ACTION"), "com.glu.gunbros2"))
        assertNull(receiverFlagsForActions(setOf("com.glu.gunbros2.action.INTERNAL"), null))
    }
}
