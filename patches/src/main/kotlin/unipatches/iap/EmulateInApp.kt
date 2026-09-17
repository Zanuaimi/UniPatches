package unipatches.iap

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.stringOption
import java.util.logging.Logger

@Suppress("unused")
val emulateInAppPatch = rawResourcePatch(
    name = "InApp Emulation Patch ( Experimental, Enhanced, Has Overlay Addon )",
    description = """
        InApp emulation modifies in app buying behavior to make pressing buy grant items without charging! Best for offline games.
        
        Warning : This DOES NOT solve server-side purchase verification!
        
        InApp Emulation Overlay Module : This patch as an overlay addon involves an InApp Emulation hook module being added to overlay menu, that has a settings button, which shows a popup showing a checkbox whether to enable InApp Emulation popup or not at buy time, and then if it's enabled, it list of saved purchases, that are saved at buy time if user chooses to save the purchase. Saved purchases are so popup windows don't appear again on buy time. The settings popup list of item of saved purchases also allows for management of them, by deleting saved purchases items.
        
        Experimental : This patch may not work on all apps, as it modifies internal app / game behavior.
        
        Credits to Nai64Patches from Nai64 for original IAP patch functionality, and credits to MiguelNinja19's billing patches, as it was used to enhance original IAP patch with Cocos2D and GameMaker and Native ILL2CPP Hex Patch.
        
        UniPatches enhances this patch by improving compatibility, stability, and adding patch strategy via Auto Mode ( default ) or Managed or Native mode. UniPatches also adds an optional overlay addon for use with Universal Overlay Patch.

        Compatibility: the overlay addon requires Universal Overlay in the same patch operation. If using
        Control Embedded Auth / Stores, keep its licensing and Google Play Services controls separate
        from this patch's purchase emulation controls. If using Custom App Display, avoid applying broad
        Activity changes to third-party billing or sign-in screens unless required.
    """.trimIndent(),
    default = false,
) {
    // Guarded: morphe-patcher < 1.13.0 has no category() and keeps the patch ungrouped.
    try { category("InApp Emulation") } catch (_: NoSuchMethodError) {}

    val nativeMode by stringOption(
        title = "InApp Emulation > Patch behavior > Patch Mode",
        default = "auto",
        key = "nativeIl2CppMode",
        description = "Automatic runs all managed compatibility patches first, then safely attempts verified native IL2CPP patches for supported libraries. Managed compatibility only runs bytecode patches and disables native patching, which is useful for diagnosing native-related crashes. Native enhancement runs the managed patches and attempts the same validated native phase. Native patching is fail-closed and only supported ABI signatures are used.",
        values = linkedMapOf(
            "Automatic (recommended)" to "auto",
            "Managed compatibility only" to "managed",
            "Native enhancement" to "native",
        ),
    )

    val fakeStartupPurchases by booleanOption(
        title = "InApp Emulation > Patch behavior > Fake owned purchases at startup",
        default = false,
        key = "fakeStartupPurchases",
        description = "Deliver a fake owned purchase through modern callback inventory queries. Helps games that only grant at boot, but can stall strict Unity titles. Leave off if a game hangs on loading.",
    )

    val legacyInventoryMode by stringOption(
        title = "InApp Emulation > Patch behavior > Legacy inventory behavior",
        default = "preserve",
        key = "legacyInventoryMode",
        description = "Preserve catalog and owned-purchase queries keeps legacy billing behavior unchanged and is recommended for compatibility. Return empty owned purchases suppresses restored purchases while leaving catalog lookup available. Return a fake owned purchase injects a synthetic purchase for older wrappers that grant content only from startup inventory. These legacy modes affect owned-inventory responses, not SKU catalog discovery.",
        values = linkedMapOf(
            "Preserve catalog and owned purchases (default)" to "preserve",
            "Return empty owned purchases" to "empty",
            "Return a fake owned purchase" to "fake",
        ),
    )

    val enableOverlayModule by booleanOption(
        title = "InApp Emulation > Overlay addon > Enable Overlay Module",
        default = false,
        key = "inAppEnableOverlayModule",
        description = "Add the session-only InApp Emulation module to Universal Overlay. Requires Universal Overlay in the same patch operation.",
    )
    val initiallyEnablePopups by booleanOption(
        title = "InApp Emulation > Overlay addon > Initially enable popups before buying products",
        default = true,
        key = "inAppInitiallyEnablePopups",
        description = "Set the initial state of purchase confirmation popups in the overlay module. You can change it later from the module settings. Ignored when Enable Overlay Module is disabled.",
    )
    val nonOverlayPurchaseTimeout by intOption(
        title = "InApp Emulation > Patch behavior > Non-overlay purchase timeout (seconds)",
        default = 10,
        key = "inAppNonOverlayPurchaseTimeoutSeconds",
        description = "Maximum time to wait for a non-overlay emulated purchase callback. Values are clamped to 1-86400 seconds.",
    )
    val overlayPurchaseTimeout by intOption(
        title = "InApp Emulation > Overlay addon > Overlay purchase timeout (seconds)",
        default = 30,
        key = "inAppOverlayPurchaseTimeoutSeconds",
        description = "Maximum time to wait for Universal Overlay purchase confirmation. Values are clamped to 1-86400 seconds.",
    )

    val timeoutValues = Pair(nonOverlayPurchaseTimeout ?: 10, overlayPurchaseTimeout ?: 30)
    dependsOn(emulateInAppManagedPatch { Triple(fakeStartupPurchases == true, legacyInventoryMode ?: "preserve", timeoutValues) })
    dependsOn(emulateInAppOverlayBridgePatch { Triple(enableOverlayModule == true, initiallyEnablePopups == true, timeoutValues) })

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
