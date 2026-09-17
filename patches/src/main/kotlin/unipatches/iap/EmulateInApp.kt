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
        InApp emulation Patch does a local modification of in-app buying behavior via DEX modification to make pressing buy grant items without charging! Best for offline and legacy apps and games.
        
        Warning : This DOES NOT solve server-side purchase verification, which is used by many modern apps and games!
        
        InApp Emulation Overlay Module : This patch as an overlay addon involves an InApp Emulation hook module being added to overlay menu, that has a settings button, which shows a popup showing a checkbox whether to enable InApp Emulation popup or not at buy time, and then if it's enabled, it list of saved purchases, that are saved at buy time if user chooses to save the purchase. Saved purchases are so popup windows don't appear again on buy time. The settings popup list of item of saved purchases also allows for management of them, by deleting saved purchases items.
        
        Experimental : This patch may not work on all apps, as it modifies internal app / game behavior. It's billing libraries support may extend with updates, and it's functionality is not guaranteed.
        
        Credits : Credits to Nai64Patches from Nai64 for original IAP patch core functionality, and credits to MiguelNinja19's billing patches, as it was used to enhance original IAP patch with Cocos2D and GameMaker and Native ILL2CPP Hex Patch.
        
        Enhancement : UniPatches enhances this patch by improving compatibility, stability, and adding Automatic Mode (enabled by default) with optional per-backend Patch Coverage controls. UniPatches also adds an optional overlay addon for use with Universal Overlay Patch.

        Compatibility: the overlay addon requires Universal Overlay in the same patch operation. If using
        Control Embedded Auth / Stores, keep its licensing and Google Play Services controls separate
        from this patch's purchase emulation controls. If using Custom App Display, avoid applying broad
        Activity changes to third-party billing or sign-in screens unless required.
    """.trimIndent(),
    default = false,
) {
    // Guarded: morphe-patcher < 1.13.0 has no category() and keeps the patch ungrouped.
    try { category("InApp Emulation") } catch (_: NoSuchMethodError) {}

    val automaticMode by booleanOption(
        title = "InApp Emulation > Patch Mode > Automatic Mode",
        default = true,
        key = "inAppAutomaticMode",
        description = "When enabled, Automatic Mode detects and enables supported InApp Emulation strategies. When disabled, Manual Mode is used and only the Patch Coverage strategies you select are applied.",
    )

    val fakeStartupPurchases by booleanOption(
        title = "InApp Emulation > Patch Mode > Fake Owned Items",
        default = false,
        key = "fakeStartupPurchases",
        description = "Deliver a fake owned purchase through modern callback inventory queries. Helps games that only grant at boot, but can stall strict Unity titles. Leave off if a game hangs on loading.",
    )

    val legacyInventoryMode by stringOption(
        title = "InApp Emulation > Patch Mode > Legacy Inventory Behavior",
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
        title = "InApp Emulation > Runtime > Enable Overlay Module",
        default = false,
        key = "inAppEnableOverlayModule",
        description = "Add the session-only InApp Emulation module to Universal Overlay. Requires Universal Overlay in the same patch operation.",
    )
    val initiallyEnablePopups by booleanOption(
        title = "InApp Emulation > Runtime > Initially enable popups at launch",
        default = true,
        key = "inAppInitiallyEnablePopups",
        description = "Set the initial state of purchase confirmation popups in the overlay module. You can change it later from the module settings. Ignored when Enable Overlay Module is disabled.",
    )
    val nonOverlayPurchaseTimeout by intOption(
        title = "InApp Emulation > Runtime > Non-overlay mode timeout (seconds)",
        default = 10,
        key = "inAppNonOverlayPurchaseTimeoutSeconds",
        description = "Maximum time to wait for a non-overlay emulated purchase callback. Values are clamped to 1-86400 seconds.",
    )
    val overlayPurchaseTimeout by intOption(
        title = "InApp Emulation > Runtime > Overlay mode timeout (seconds)",
        default = 30,
        key = "inAppOverlayPurchaseTimeoutSeconds",
        description = "Maximum time to wait for Universal Overlay purchase confirmation. Values are clamped to 1-86400 seconds.",
    )

    val billingClientV3 by booleanOption(title = "InApp Emulation > Patch Coverage > BillingClient v3", default = false, key = "inAppCoverageBillingV3", description = "Enable BillingClient v3 strategy when Automatic Mode is disabled.")
    val openIab by booleanOption(title = "InApp Emulation > Patch Coverage > OpenIAB", default = false, key = "inAppCoverageOpenIab", description = "Enable OpenIAB and UnityPlugin legacy billing strategy when Automatic Mode is disabled.")
    val billingClientV9 by booleanOption(title = "InApp Emulation > Patch Coverage > BillingClient v9", default = false, key = "inAppCoverageBillingV9", description = "Enable BillingClient v9/ProductDetails strategy when Automatic Mode is disabled.")
    val gameMaker by booleanOption(title = "InApp Emulation > Patch Coverage > GameMaker", default = false, key = "inAppCoverageGameMaker", description = "Enable GameMaker purchase bridge strategy when Automatic Mode is disabled.")
    val cocos2d by booleanOption(title = "InApp Emulation > Patch Coverage > Cocos2D", default = false, key = "inAppCoverageCocos2d", description = "Enable Cocos2D purchase callback strategy when Automatic Mode is disabled.")
    val revenueCat by booleanOption(title = "InApp Emulation > Patch Coverage > RevenueCat", default = false, key = "inAppCoverageRevenueCat", description = "Enable RevenueCat strategy when Automatic Mode is disabled.")
    val unityIap by booleanOption(title = "InApp Emulation > Patch Coverage > Unity IAP", default = false, key = "inAppCoverageUnityIap", description = "Enable Unity Purchasing callback strategy when Automatic Mode is disabled.")
    val unityIl2Cpp by booleanOption(title = "InApp Emulation > Patch Coverage > Unity IL2CPP", default = false, key = "inAppCoverageUnityIl2Cpp", description = "Enable Unity IL2CPP billing bridge strategy when Automatic Mode is disabled.")
    val legacyAidl by booleanOption(title = "InApp Emulation > Patch Coverage > Legacy AIDL Billing", default = false, key = "inAppCoverageLegacyAidl", description = "Enable legacy Android billing service strategy when Automatic Mode is disabled.")
    val amazon by booleanOption(title = "InApp Emulation > Patch Coverage > Amazon IAP", default = false, key = "inAppCoverageAmazon", description = "Enable Amazon IAP strategy when Automatic Mode is disabled.")
    val huawei by booleanOption(title = "InApp Emulation > Patch Coverage > Huawei IAP", default = false, key = "inAppCoverageHuawei", description = "Enable Huawei IAP strategy when Automatic Mode is disabled.")
    val samsung by booleanOption(title = "InApp Emulation > Patch Coverage > Samsung IAP", default = false, key = "inAppCoverageSamsung", description = "Enable Samsung Galaxy Store IAP strategy when Automatic Mode is disabled.")
    val xsolla by booleanOption(title = "InApp Emulation > Patch Coverage > Xsolla", default = false, key = "inAppCoverageXsolla", description = "Enable Xsolla purchase strategy when Automatic Mode is disabled.")
    val timeoutValues = Pair(nonOverlayPurchaseTimeout ?: 10, overlayPurchaseTimeout ?: 30)
    val coverage = InAppCoverage(
        billingClientV3 = billingClientV3 == true,
        openIab = openIab == true,
        billingClientV9 = billingClientV9 == true,
        gameMaker = gameMaker == true,
        cocos2d = cocos2d == true,
        revenueCat = revenueCat == true,
        unityIap = unityIap == true,
        unityIl2Cpp = unityIl2Cpp == true,
        legacyAidl = legacyAidl == true,
        amazon = amazon == true,
        huawei = huawei == true,
        samsung = samsung == true,
        xsolla = xsolla == true,
    )
    val patchOptions = InAppPatchOptions(
        automaticMode = automaticMode == true,
        fakeStartupPurchases = fakeStartupPurchases == true,
        legacyInventoryMode = legacyInventoryMode ?: "preserve",
        timeouts = timeoutValues,
        coverage = coverage,
    )
    dependsOn(emulateInAppManagedPatch { patchOptions })
    dependsOn(emulateInAppOverlayBridgePatch { Triple(enableOverlayModule == true, initiallyEnablePopups == true, timeoutValues) })

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val nativeMode = if (patchOptions.automaticMode || patchOptions.coverage.unityIl2Cpp) "auto" else "managed"
        val nativeResults = applyNativeIl2CppPhase(this, nativeMode, logger)
        val patched = nativeResults.count { it.status == NativeStatus.PATCHED }
        val skipped = nativeResults.count { it.status == NativeStatus.SKIPPED }
        logger.info("Emulate InApp native summary: $patched ABI target(s) patched, $skipped skipped")
        nativeResults.forEach { result ->
            logger.info("Emulate InApp native: ${result.abi}: ${result.message}")
        }
    }
}
