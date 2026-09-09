package unipatches.overlay

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import helpers.startup.StartupHooks
import java.util.logging.Logger

private const val HCR_PROFILE = "hillClimbRacingExample"

/**
 * Safe shared-core example for contributors building an app-specific overlay. The runtime
 * modules are mock previews only; this patch never searches for or changes game data.
 */
@Suppress("unused")
val hillClimbRacingOverlayExamplePatch = bytecodePatch(
    name = "Hill Climb Racing Overlay Example (Experimental)",
    description = "Safe app-specific overlay example for Hill Climb Racing (com.fingersoft.hillclimb). Select one or more preview modules, patch, then use their Settings and Preview controls in the shared overlay UI. Values are session-only and reset when the app process restarts. This contributor example demonstrates explicit launcher injection, styled settings popups, checkbox lists, and one-shot actions; it never alters currencies, unlocks, purchases, saves, or game bytecode. Do not combine with Universal Overlay Patch, because both patches install the same shared overlay runtime.",
    default = false,
) {
    extendWith("extensions/extension.mpe")
    compatibleWith(
        Compatibility(
            packageName = "com.fingersoft.hillclimb",
            name = "Hill Climb Racing",
            description = "Safe shared-overlay example for the Hill Climb Racing APK.",
        ),
    )
    dependsOn(StartupHooks.resolveRealApplicationPatch)

    val addCoins by booleanOption(
        title = "Quick setup > Overlay modules > App-specific previews > Add Coins preview",
        default = false,
        key = "hcrDemoAddCoins",
        description = "After patching, show a mock signed 32-bit value editor and Preview button in the overlay. Set a value, then preview it; the value is session-only and never reads or changes Hill Climb Racing coins.",
    )
    val addGems by booleanOption(
        title = "Quick setup > Overlay modules > App-specific previews > Add Gems preview",
        default = false,
        key = "hcrDemoAddGems",
        description = "After patching, show a mock signed 32-bit value editor and Preview button in the overlay. Set a value, then preview it; the value is session-only and never reads or changes Hill Climb Racing gems.",
    )
    val addPaints by booleanOption(
        title = "Quick setup > Overlay modules > App-specific previews > Add Paints preview",
        default = false,
        key = "hcrDemoAddPaints",
        description = "After patching, show a mock signed 32-bit value editor and Preview button in the overlay. Set a value, then preview it; the value is session-only and never reads or changes Hill Climb Racing paints.",
    )
    val vehicles by booleanOption(
        title = "Quick setup > Overlay modules > App-specific previews > Vehicle selection preview",
        default = false,
        key = "hcrDemoVehicles",
        description = "After patching, show a mock scrollable vehicle checkbox list and Preview action. It demonstrates the shared settings popup but never unlocks, locks, or inspects vehicles.",
    )
    val stages by booleanOption(
        title = "Quick setup > Overlay modules > App-specific previews > Stage selection preview",
        default = false,
        key = "hcrDemoStages",
        description = "After patching, show a mock scrollable stage checkbox list and Preview action. It demonstrates the shared settings popup but never unlocks, locks, or inspects stages.",
    )
    val garage by booleanOption(
        title = "Quick setup > Overlay modules > App-specific previews > Garage selection preview",
        default = false,
        key = "hcrDemoGarage",
        description = "After patching, show a mock Lock/Unlock checkbox and Preview action. It demonstrates the shared settings popup but never changes or inspects the garage.",
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        if (StartupHooks.resolvedPackageName != "com.fingersoft.hillclimb") {
            logger.warning("Hill Climb Racing overlay example: expected com.fingersoft.hillclimb, found ${StartupHooks.resolvedPackageName ?: "unknown"}; no changes applied.")
            return@execute
        }
        val selectedModules = buildList {
            if (addCoins == true) add("hcrDemoAddCoins")
            if (addGems == true) add("hcrDemoAddGems")
            if (addPaints == true) add("hcrDemoAddPaints")
            if (vehicles == true) add("hcrDemoVehicles")
            if (stages == true) add("hcrDemoStages")
            if (garage == true) add("hcrDemoGarage")
        }.joinToString(",")

        val config = OverlayConfigPayload.serialize(
            commonFields = hillClimbRacingDefaults(),
            profileId = HCR_PROFILE,
            injectionMode = OverlayConfigPayload.EXPLICIT_ACTIVITY_INJECTION_MODE,
            trailingFields = listOf("", selectedModules),
        )
        val adsRuntimePolicy = OverlayAdsRuntimeIntegration.pendingPolicy()
        val target = StartupHooks.resolvedLauncherActivityDescriptor?.let(::mutableClassDefByOrNull)
            ?: findOverlayFallbackActivity()
        val method = target?.methods?.firstOrNull {
            it.name == "onCreate" && it.returnType == "V" &&
                it.parameterTypes == listOf("Landroid/os/Bundle;")
        }
        if (target == null || method == null) {
            logger.warning("Hill Climb Racing overlay example: no suitable explicit launcher or controlled Activity fallback was found; no changes applied.")
            return@execute
        }
        injectOverlayBridge(this, target, method, config, application = false, adsRuntimePolicy = adsRuntimePolicy)
        if (adsRuntimePolicy != null) {
            OverlayAdsRuntimeIntegration.markInjected("Hill Climb Racing Overlay Example")
            logger.info("Control App Ads runtime policy was injected beside the app-specific overlay bridge.")
        }
        logger.info("Installed the safe Hill Climb Racing app-specific overlay example into ${target.type}. Runtime modules are mock-only and session-local.")
    }
}

private fun hillClimbRacingDefaults(): List<String> = listOf(
    OverlayConfigPayload.VERSION,
    "Hill Climb Racing Overlay Example", // title
    "Safe shared-core overlay example. All app-specific modules are mock previews and never alter game data.",
    "UniPatches overlay documentation",
    "https://github.com/Zanuaimi/UniPatches",
    "#20252B", "#78C8FF", "H", "#FFFFFF", "#35414C", "circle", "56", "55", "topRight",
    "", "0", "0", "bottom", "1", "2", "celsius", "12",
    "2", "0", "#FFFFFF", "1", "#35414C", "0", "", "2", "1", "80", "#FFFFFF", "3", "18",
    "modern", "#26313A", "#78C8FF", "text", "square", "0", "#FFFFFF", "#26313A", "#35414C",
    "#FFFFFF", "#CFEAFF", "#A9D8F5", "#78C8FF", "#8FD3FF",
    "ascii", "none", "left", "0", "rounded", "static", "1", "fade", "180", "linear",
    "", "center", "#CFEAFF", "1", "fade", "#CFEAFF", "#40515E", "none",
    "text", "triangle", "#FFFFFF", "#FFFFFF", "0", "0", "3", "70", "0", "0", "0", "#FFFFFF", "0", "flat", "#35414C", "#26313A",
).also { require(it.size == 82) { "Overlay example defaults must contain 82 common fields" } }
