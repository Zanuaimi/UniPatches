package unipatches.iap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevenueCatRegressionTest {
    @Test
    fun revenueCatKeepsRealStoreLifecycleAndSingleSetupCallback() {
        for (label in listOf("BillingClient.startConnection", "BillingClient.isReady",
            "BillingClient.getConnectionState", "BillingClient.endConnection",
            "BillingClient.getResponseCode", "isFeatureSupported")) {
            assertTrue(preserveRevenueCatStoreLifecycle(label, true))
            assertFalse(preserveRevenueCatStoreLifecycle(label, false))
        }
        assertFalse(preserveRevenueCatStoreLifecycle("BillingClient.v9.launchBillingFlow", true))
        assertFalse(preserveRevenueCatStoreLifecycle("RC.onPurchasesUpdated", true))
    }

    private fun options(automatic: Boolean, revenueCat: Boolean, amazon: Boolean = false) = InAppPatchOptions(
        automaticMode = automatic,
        fakeStartupPurchases = false,
        legacyInventoryMode = "preserve",
        timeouts = 10 to 30,
        coverage = InAppCoverage(revenueCat = revenueCat, amazon = amazon),
    )

    @Test
    fun revenueCatRequiresExplicitOptInInBothModes() {
        for (automatic in listOf(false, true)) {
            for (label in listOf("RC.onPurchasesUpdated", "RC.purchase-unsafe", "RevenueCat")) {
                assertFalse(options(automatic, false).strategyEnabled(label, true))
                assertTrue(options(automatic, true).strategyEnabled(label, true))
            }
        }
        assertTrue(options(true, false).strategyEnabled("BillingClient.startConnection", true))
        assertFalse(options(false, false).strategyEnabled("BillingClient.startConnection", true))
    }

    @Test
    fun automaticModeDoesNotCrossDisabledSdkBoundaries() {
        val default = options(automatic = true, revenueCat = false)
        assertFalse(default.strategyEnabled("Amazon.purchase", true))
        assertEquals("RevenueCat coverage disabled",
            default.backendBoundaryReason("Lcom/revenuecat/purchases/google/BillingWrapper;"))
        assertEquals("Amazon coverage disabled",
            default.backendBoundaryReason("Lcom/amazon/device/iap/PurchasingService;"))
        assertEquals("Amazon coverage disabled",
            options(true, true).backendBoundaryReason("Lcom/revenuecat/purchases/amazon/AmazonBilling;"))
        assertEquals(null, default.backendBoundaryReason("Lcom/android/billingclient/api/BillingClient;"))
        val enabled = options(true, true, true)
        assertTrue(enabled.strategyEnabled("Amazon.purchase", true))
        assertEquals(null, enabled.backendBoundaryReason("Lcom/revenuecat/purchases/amazon/AmazonBilling;"))
    }

    @Test
    fun emittedInvokeHasAProducerForLowAndHighParameters() {
        for (registers in listOf(8, 17, 21)) {
            val method = ImmutableMethod(
                "Lcom/revenuecat/purchases/google/BillingWrapper;", "onPurchasesUpdated",
                listOf("Lcom/android/billingclient/api/BillingResult;", "Ljava/util/List;")
                    .map { ImmutableMethodParameter(it, null, null) },
                "V", 0, null, null,
                ImmutableMethodImplementation(registers,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), emptyList(), emptyList()),
            ).toMutable()
            method.addInstructions(0, revenueCatProductIdInstructions("p2", 10.coerceAtMost(registers - 4), 1))
            val instructions = method.implementation!!.instructions.toList()
            assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_VOID), instructions.map { it.opcode })
            val copy = instructions[0] as TwoRegisterInstruction
            val invoke = instructions[1] as FiveRegisterInstruction
            assertEquals(registers - 1, copy.registerB)
            assertEquals(copy.registerA, invoke.registerC)
            assertEquals(1, invoke.registerCount)
            assertTrue(invoke.registerC in 0..15)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnencodableTemporary() {
        revenueCatProductIdInstructions("p2", 16, 9)
    }
}
