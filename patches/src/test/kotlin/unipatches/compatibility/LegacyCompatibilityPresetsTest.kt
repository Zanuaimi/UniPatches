package unipatches.compatibility

import org.junit.Assert.*
import org.junit.Test

class LegacyCompatibilityPresetsTest {
    @Test fun customPreservesManualChoices() {
        assertTrue(LegacyCompatibilityPresets.boolean("custom", "allowCleartext", true)!!)
        assertEquals(35, LegacyCompatibilityPresets.target("custom", 35))
    }
    @Test fun presetsDisableEveryExplicitOptIn() {
        for (id in LegacyCompatibilityPresets.ids - "custom") {
            for (key in listOf("trustCertificates", "acknowledgeTrustCertificates", "spoofImei", "bypassPackageVisibility", "exportAllActivityComponents", "allowCleartext", "bypassHiddenApi"))
                assertFalse("$id/$key", LegacyCompatibilityPresets.boolean(id, key, true)!!)
            assertFalse(LegacyCompatibilityPresets.boolean(id, "embedExpansionObb", true)!!)
        }
    }
    @Test fun eraMappingDoesNotBlindlyDowngradeTarget() {
        assertEquals(listOf(28, 29, 30, 32, 33, 34), LegacyCompatibilityPresets.eras.values.toList())
        assertFalse(LegacyCompatibilityPresets.boolean("android_11", "apacheLegacy", true)!!)
        assertTrue(LegacyCompatibilityPresets.boolean("android_7_9", "apacheLegacy", false)!!)
        assertTrue(LegacyCompatibilityPresets.boolean("android_7_9", "legacyReviver", false)!!)
        assertTrue(LegacyCompatibilityPresets.boolean("android_10", "legacyReviver", false)!!)
        assertFalse(LegacyCompatibilityPresets.boolean("unity", "legacyReviver", true)!!)
        assertFalse(LegacyCompatibilityPresets.boolean("android_14_plus", "bluetooth", true)!!)
        for (id in LegacyCompatibilityPresets.ids - "custom")
            assertFalse(LegacyCompatibilityPresets.boolean(id, "spoofTargetSdk", true)!!)
    }
    @Test fun openIabDetectionIsIndependentOfUnity() {
        val engines = detectLegacyEngines(setOf("Lorg/onepf/oms/OpenIabHelper;"))
        assertTrue(engines.openIab)
        assertFalse(engines.unity)
        assertFalse(detectLegacyEngines(setOf("Lorg/onepf/openiab/UnityPlugin;")).openIab)
        assertFalse(detectLegacyEngines(emptySet()).openIab)
    }
    @Test fun openIabPresetRequiresDetectedEngineButCustomChoiceDoesNot() {
        assertFalse(LegacyCompatibilityPresets.playStorePackageVisibility("openiab", true, false)!!)
        assertTrue(LegacyCompatibilityPresets.playStorePackageVisibility("openiab", false, true)!!)
        assertTrue(LegacyCompatibilityPresets.playStorePackageVisibility("openiab", true, true)!!)
        assertTrue(LegacyCompatibilityPresets.playStorePackageVisibility("custom", true, false)!!)
        assertFalse(LegacyCompatibilityPresets.playStorePackageVisibility("custom", false, true)!!)
    }
    @Test fun unityWithoutOpenIabDoesNotEnableOpenIabPresetQuery() {
        val engines = detectLegacyEngines(
            setOf("Lcom/unity3d/player/UnityPlayer;", "Lcom/unity3d/player/UnityPlayerActivity;"),
        )
        assertTrue(engines.unity)
        assertFalse(engines.openIab)
        assertFalse(LegacyCompatibilityPresets.playStorePackageVisibility("unity_openiab", true, engines.openIab)!!)
    }
    @Test fun unityDetectionRequiresExactPlayerPair() {
        assertFalse(detectLegacyEngines(setOf("Lcom/unity3d/player/UnityPlayer;")).unity)
        assertTrue(detectLegacyEngines(setOf("Lcom/unity3d/player/UnityPlayer;", "Lcom/unity3d/player/UnityPlayerActivity;")).unity)
    }
    @Test fun invalidPresetFailsClosed() {
        try { LegacyCompatibilityPresets.validate("typo"); fail("invalid preset accepted") } catch (_: IllegalArgumentException) {}
    }
}
