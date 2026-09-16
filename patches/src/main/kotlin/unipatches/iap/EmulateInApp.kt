package unipatches.iap

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.stringOption
import java.util.logging.Logger

@Suppress("unused")
val emulateInAppPatch = rawResourcePatch(
    name = "Emulate InApp Patch ( Experimental, Enhanced, Has Overlay Addon )",
    description = """
        InApp emulation modifies in app buying behavior to make pressing buy grant items without charging! Best for offline games.
        
        Warning : This DOES NOT solve server-side purchase verification!
        
        InApp Emulation Overlay Module : This patch as an overlay addon involves an InApp Emulation hook module being added to overlay menu, that has a settings button, which shows a popup showing a checkbox whether to enable InApp Emulation popup or not at buy time, and then if it's enabled, it list of saved purchases, that are saved at buy time if user chooses to save the purchase. Saved purchases are so popup windows don't appear again on buy time. The settings popup list of item of saved purchases also allows for management of them, by deleting saved purchases items.
        
        Experimental : This patch may not work on all apps, as it modifies internal app / game behavior.
        
        Credits to Nai64Patches from Nai64 for original IAP patch functionality, and credits to MiguelNinja19's billing patches, as it was used to enhance original IAP patch with Cocos2D and GameMaker and Native ILL2CPP Hex Patch.
        
        UniPatches enhances this patch by improving compatibility, stability, and adding patch strategy via Auto Mode ( default ) or Managed or Native mode. UniPatches also adds an optional overlay addon for use with Universal Overlay Patch.
    """.trimIndent(),
    default = false,
) {
    // Guarded: morphe-patcher < 1.13.0 has no category() and keeps the patch ungrouped.
    try { category("InApp Emulation") } catch (_: NoSuchMethodError) {}

    val nativeMode by stringOption(
        title = "Native IL2CPP mode",
        default = "auto",
        key = "nativeIl2CppMode",
        description = "Select automatic, managed-only, or native enhancement behavior for supported libil2cpp.so files.",
        values = linkedMapOf(
            "Automatic (recommended)" to "auto",
            "Managed compatibility only" to "managed",
            "Native enhancement" to "native",
        ),
    )

    val fakeStartupPurchases by booleanOption(
        title = "Fake owned purchases at startup",
        default = false,
        key = "fakeStartupPurchases",
        description = "Deliver a fake owned purchase on every inventory query. Helps games that only grant at boot, but can stall strict Unity titles. Leave off if a game hangs on loading.",
    )

    dependsOn(emulateInAppManagedPatch { fakeStartupPurchases == true })

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val nativeResults = applyNativeIl2CppPhase(this, nativeMode ?: "auto", logger)
        val patched = nativeResults.count { it.status == NativeStatus.PATCHED }
        val skipped = nativeResults.count { it.status == NativeStatus.SKIPPED }
        logger.info("Emulate InApp native summary: $patched ABI target(s) patched, $skipped skipped")
        nativeResults.forEach { result ->
            logger.info("Emulate InApp native: ${result.abi}: ${result.message}")
        }
    }
}
