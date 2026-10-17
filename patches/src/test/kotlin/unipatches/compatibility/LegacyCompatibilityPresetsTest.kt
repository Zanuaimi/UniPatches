package unipatches.compatibility

import org.junit.Assert.*
import org.junit.Test

class LegacyCompatibilityPresetsTest {
    private val explicitOptions = listOf(
        "diagnosticsOnly", "spoofTargetSdk", "exactAlarms", "trustCertificates",
        "acknowledgeTrustCertificates", "spoofImei",
        "exportAllActivityComponents", "allowCleartext", "bypassHiddenApi",
        "relocateExpansionNativeLibraries", "removeRelocatedNativeLibrariesFromObb",
        "embedExpansionObb", "bypassExpansionDownloader", "extractNativeLibs",
        "redirectLegacyStorage", "allScreens",
        "relaxLibraries", "manifestCompatAttributes", "legacyStorage",
        "disableHeapTagging", "vmSafeMode",
    )

    @Test fun customPreservesManualChoices() {
        for (key in explicitOptions + listOf("legacyReviver", "apacheLegacy", "foregroundService", "bluetooth", "receiverFixAppWide", "repairExportFlags", "playStorePackageVisibility")) {
            assertTrue(key, LegacyCompatibilityPresets.boolean("custom", key, true))
            assertFalse(key, LegacyCompatibilityPresets.boolean("custom", key, false))
            assertFalse(key, LegacyCompatibilityPresets.boolean("custom", key, null))
        }
    }

    @Test fun presetsNeverModifyExplicitOptIns() {
        for (id in LegacyCompatibilityPresets.ids) {
            for (key in explicitOptions) {
                assertTrue("$id/$key", LegacyCompatibilityPresets.boolean(id, key, true))
                assertFalse("$id/$key", LegacyCompatibilityPresets.boolean(id, key, false))
            }
        }
    }

    @Test fun eraPresetsOnlyAddOrdinaryFixes() {
        assertEquals(listOf(20, 23, 28, 29, 30, 32, 33, 34), LegacyCompatibilityPresets.eras.values.toList())
        for ((id, era) in LegacyCompatibilityPresets.eras) {
            for (key in listOf("legacyReviver", "receiverFixAppWide", "repairExportFlags"))
                assertTrue("$id/$key", LegacyCompatibilityPresets.boolean(id, key, false))
            assertEquals("$id/playStorePackageVisibility", era >= 30,
                LegacyCompatibilityPresets.boolean(id, "playStorePackageVisibility", false))
            for (key in listOf("apacheLegacy", "foregroundService", "bluetooth")) {
                assertTrue("$id/$key", LegacyCompatibilityPresets.boolean(id, key, true))
                assertEquals("$id/$key", if (key == "bluetooth") era < 31 else era <= 28,
                    LegacyCompatibilityPresets.boolean(id, key, false))
            }
        }
    }

    @Test fun dropdownOnlyContainsCustomAndEras() {
        assertEquals(listOf("custom") + LegacyCompatibilityPresets.eras.keys, LegacyCompatibilityPresets.options.values.toList())
    }

    @Test fun removedPresetsMigrateToCustomWithoutLosingChoices() {
        for (id in listOf("unity", "openiab", "unity_openiab")) {
            assertEquals("custom", LegacyCompatibilityPresets.validate(id))
            assertTrue(LegacyCompatibilityPresets.boolean(id, "embedExpansionObb", true))
            assertFalse(LegacyCompatibilityPresets.boolean(id, "receiverFixAppWide", false))
        }
    }

    @Test fun openIabDetectionIsIndependentOfUnity() {
        val engines = detectLegacyEngines(setOf("Lorg/onepf/oms/OpenIabHelper;"))
        assertTrue(engines.openIab)
        assertFalse(engines.unity)
        assertFalse(detectLegacyEngines(setOf("Lorg/onepf/openiab/UnityPlugin;")).openIab)
        assertFalse(detectLegacyEngines(emptySet()).openIab)
    }

    @Test fun unityDetectionRequiresExactPlayerPair() {
        assertFalse(detectLegacyEngines(setOf("Lcom/unity3d/player/UnityPlayer;")).unity)
        val engines = detectLegacyEngines(setOf("Lcom/unity3d/player/UnityPlayer;", "Lcom/unity3d/player/UnityPlayerActivity;"))
        assertTrue(engines.unity)
        assertFalse(engines.openIab)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPresetFailsClosed() { LegacyCompatibilityPresets.validate("typo") }
}
