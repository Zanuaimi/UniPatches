package helpers.bytecode

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectedBlockWindowTest {
    private fun frame(
        registerCount: Int,
        parameterTypes: List<String>,
        isStatic: Boolean,
    ): ImmutableMethod = ImmutableMethod(
        "Lcom/android/billingclient/api/BillingClientImpl;",
        "consumeAsync",
        parameterTypes.map { ImmutableMethodParameter(it, null, null) },
        "V",
        if (isStatic) AccessFlags.STATIC.value else 0,
        null,
        null,
        ImmutableMethodImplementation(registerCount, emptyList(), emptyList(), emptyList()),
    )

    @Test
    fun readsHighestVRegisterAndIgnoresParametersAndLabels() {
        val block = """
            move-object/from16 v2, p1
            const/4 v1, 0x0
            :morphe_iap_done
            return-void
        """.trimIndent()
        assertEquals(2, maxRegisterUsedIn(block))
    }

    @Test
    fun reportsNoRegistersForRegisterlessBlocks() {
        assertEquals(-1, maxRegisterUsedIn("return-void"))
        assertTrue(maxRegisterUsedIn("return-void") < 0)
    }

    @Test
    fun rejectsInjectionIntoATightFrame() {
        // Instance consumeAsync(ConsumeParams, ConsumeResponseListener) with no locals:
        // v0/v1/v2 ARE p0/p1/p2, so a block that writes v0/v1 before reading the
        // parameters hands getPurchaseToken() a null receiver.
        val method = frame(registerCount = 3, parameterTypes = listOf(
            "Lcom/android/billingclient/api/ConsumeParams;",
            "Lcom/android/billingclient/api/ConsumeResponseListener;",
        ), isStatic = false)
        val block = "move-result-object v0\nmove-object/from16 v1, p1\nreturn-void"
        assertFalse(method.fitsBelowParameters(block))
    }

    @Test
    fun acceptsInjectionWhenLocalsSitBelowTheParameters() {
        val method = frame(registerCount = 6, parameterTypes = listOf(
            "Lcom/android/billingclient/api/ConsumeParams;",
            "Lcom/android/billingclient/api/ConsumeResponseListener;",
        ), isStatic = false)
        val block = "move-result-object v0\nconst/4 v1, 0x0\nreturn-void"
        assertTrue(method.fitsBelowParameters(block))
    }

    @Test
    fun acceptsRegisterlessBlocksInAnyFrame() {
        val method = frame(registerCount = 1, parameterTypes = emptyList(), isStatic = true)
        assertTrue(method.fitsBelowParameters("return-void"))
    }

    @Test
    fun staticNoArgumentFrameHasRoomForItsSingleRegister() {
        // GameMaker's verifyPurchase used to be gated on minRegs() >= 1, which is 0 for
        // a static no-argument method, so the patch never applied. v0 is what matters.
        val method = frame(registerCount = 1, parameterTypes = emptyList(), isStatic = true)
        assertEquals(1, method.p0Register)
        assertTrue(method.fitsBelowParameters("const/4 v0, 0x1\nreturn v0"))
    }

    @Test
    fun cloneWindowPutsParametersAboveEveryWrittenRegister() {
        // The IL2CPP onPurchasesUpdated guard writes v[registerCount, registerCount + 1]
        // and used to clone with a fixed +2, which leaves the parameter region at
        // registerCount - 3 + 2 -- inside the guard's own scratch registers.
        val method = frame(registerCount = 3, parameterTypes = listOf(
            "Lcom/android/billingclient/api/BillingResult;",
            "Ljava/util/List;",
        ), isStatic = false)
        val scratch = method.implementation!!.registerCount
        val guard = """
            move-object/from16 v$scratch, p1
            invoke-virtual {v$scratch}, Lcom/android/billingclient/api/BillingResult;->getResponseCode()I
            move-result v${scratch + 1}
            if-eqz v${scratch + 1}, :morphe_iap_bridge_continue
            return-void
            :morphe_iap_bridge_continue
            nop
        """.trimIndent()

        val highest = maxRegisterUsedIn(guard)
        assertEquals(scratch + 1, highest)

        val parameterRegisters = method.numberOfParameterRegisters
        val legacyWindow = method.cloneMutable(additionalRegisters = 2)
        assertTrue(
            "fixed +2 window must overlap the guard's scratch registers",
            legacyWindow.implementation!!.registerCount - parameterRegisters <= highest,
        )

        val cloned = method.cloneMutableForInjectedBlock(guard)
        val parameterStart = cloned.implementation!!.registerCount - parameterRegisters
        assertTrue(parameterStart > highest)
        assertEquals(parameterStart, cloned.p0Register)
    }

    @Test
    fun cloneWindowCoversLargeLowRegisterBlocks() {
        // OpenIAB's launchPurchaseFlow block writes v0..v6 and falls through to the
        // original body, so the clone must keep all seven registers below p0.
        val method = frame(registerCount = 7, parameterTypes = listOf(
            "Landroid/app/Activity;",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
            "I",
            "Lorg/onepf/oms/appstore/googleUtils/IabHelper${'$'}OnIabPurchaseFinishedListener;",
            "Ljava/lang/String;",
        ), isStatic = false)
        val block = (0..6).joinToString("\n") { "const/4 v$it, 0x0" }

        assertFalse(method.fitsBelowParameters(block))

        val cloned = method.cloneMutableForInjectedBlock(block)
        val parameterRegisters = method.numberOfParameterRegisters
        val parameterStart = cloned.implementation!!.registerCount - parameterRegisters
        assertEquals(14, cloned.implementation!!.registerCount)
        assertEquals(7, parameterStart)
        assertTrue(parameterStart > maxRegisterUsedIn(block))
    }

    @Test
    fun cloneWindowNeverShrinksBelowTheSafetyFloor() {
        val method = frame(registerCount = 8, parameterTypes = listOf("Ljava/lang/String;"), isStatic = false)
        val cloned = method.cloneMutableForInjectedBlock("const/4 v0, 0x1\nreturn v0")
        assertEquals(8 + 4, cloned.implementation!!.registerCount)
        assertTrue(cloned.implementation!!.registerCount - method.numberOfParameterRegisters > 0)
    }
}
