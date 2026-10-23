package unipatches.iap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingClientFlowSpecTest {
    @Test
    fun standardLaunchBillingFlowKeepsActivityAndFlowParamsPositional() {
        val types = listOf(
            "Landroid/app/Activity;",
            "Lcom/android/billingclient/api/BillingFlowParams;",
        )
        // The pre-fix filter only matched BillingFlowParams, so it returned a
        // one-element list and the grant block fed flow parameters to
        // purchaseActivity while flowParams stayed null -> response code 5.
        assertEquals(listOf(0, 1), BillingClientFlowSpec.V9.argumentIndices(types))
        assertEquals(listOf(0, 1), BillingClientFlowSpec.V3.argumentIndices(types))
    }

    @Test
    fun v3SkuOverloadSkipsTheProductIdString() {
        val types = listOf(
            "Landroid/app/Activity;",
            "Ljava/lang/String;",
            "Lcom/android/billingclient/api/BillingFlowParams;",
        )
        assertEquals(listOf(0, 2), BillingClientFlowSpec.V3.argumentIndices(types))
    }

    @Test
    fun obfuscatedFlowParamsStillFollowTheActivity() {
        val types = listOf("Landroid/app/Activity;", "Lcom/android/billingclient/api/zzaa;")
        assertEquals(listOf(0, 1), BillingClientFlowSpec.V9.argumentIndices(types))
    }

    @Test
    fun missingActivitySlotIsReportedInsteadOfShiftingTheFlowParamsIn() {
        val types = listOf("Lcom/android/billingclient/api/BillingFlowParams;")
        assertEquals(listOf(-1, 0), BillingClientFlowSpec.V9.argumentIndices(types))
    }

    @Test
    fun grantBlockWritesBothPositionalSlots() {
        val block = buildBillingPurchaseBlock("", listOf("p1", "p3"), 10, 30, "return-void")
        assertTrue(block.contains("move-object/from16 v1, p1"))
        assertTrue(block.contains("move-object/from16 v2, p3"))
    }

    @Test
    fun grantBlockNullsAMissingSlotInsteadOfPullingLaterArgumentsForward() {
        val block = buildBillingPurchaseBlock("", listOf("p3", null), 10, 30, "return-void")
        assertTrue(block.contains("move-object/from16 v1, p3"))
        assertTrue(block.contains("const/4 v2, 0x0"))
    }
}
