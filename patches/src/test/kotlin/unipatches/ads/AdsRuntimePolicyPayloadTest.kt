package unipatches.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class AdsRuntimePolicyPayloadTest {
    @Test
    fun moduleMasksUseExpectedBits() {
        assertEquals(1, buildAdsRuntimeModuleMask(true, true, false, false))
        assertEquals(2, buildAdsRuntimeModuleMask(true, false, true, false))
        assertEquals(3, buildAdsRuntimeModuleMask(true, true, true, false))
        assertEquals(4, buildAdsRuntimeModuleMask(true, false, false, true))
        assertEquals(7, buildAdsRuntimeModuleMask(true, true, true, true))
    }

    @Test
    fun disabledRuntimeControlsProduceNoModuleMask() {
        assertEquals(0, buildAdsRuntimeModuleMask(false, true, true, true))
        assertEquals(0, buildAdsRuntimeModuleMask(true, false, false, false))
    }

    @Test
    fun disabledRewardsAddonDoesNotEnableRuntimeRewards() {
        assertEquals(false, isAdsRuntimeRewardsEnabled(true, false))
        assertEquals(false, isAdsRuntimeRewardsEnabled(false, true))
        assertEquals(true, isAdsRuntimeRewardsEnabled(true, true))
    }

    @Test
    fun rewardsAddonSerializesModuleBitTwo() {
        assertEquals("1|2|0|0|0|0|0|0|", serializeAdsRuntimePolicy(2, 0, false, true, true, false, emptyList()))
    }

    @Test
    fun hostListSerializationIsStableAndSorted() {
        assertEquals("1|7|16|1|1|1|0|1|a.example,z.example", serializeAdsRuntimePolicy(7, 16, true, true, true, true, listOf("z.example", "a.example")))
    }
}
