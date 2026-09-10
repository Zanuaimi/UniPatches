package unipatches.overlay

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch

/**
 * App-specific companion to Universal Overlay. Universal owns the shared configuration and
 * runtime bridge; this patch only selects the safe, session-local HCR example modules.
 */
@Suppress("unused")
val hillClimbRacingOverlayExamplePatch = bytecodePatch(
    name = "Hill Climb Racing Example Overlay Addon",
    description = """Example overlay addon for Hill Climb Racing (com.fingersoft.hillclimb), used together with UniPatches Universal Overlay.
        It adds six safe, mock-only app-specific preview modules to the shared overlay: A
        - Add Coins
        - Add Gems
        - Add Paints 
        - Vehicle Selection
        - Stage Selection
        - Garage Selection. 
        All these six module options are enabled by default and can be disabled independently. 
        
        Module settings and Preview actions are session-only; this example addon never reads or changes currencies, vehicles, stages, garage state, purchases, save files, or game bytecode.""".trimMargin(),
    default = false,
) {
    extendWith("extensions/extension.mpe")
    compatibleWith(
        Compatibility(
            packageName = "com.fingersoft.hillclimb",
            name = "Hill Climb Racing",
            description = "Safe app-specific modules for the shared Universal Overlay.",
        ),
    )
    dependsOn(universalOverlayPatch)

    val addCoins by booleanOption(
        title = "App-specific modules > Add Coins preview",
        default = true,
        key = "hcrDemoAddCoins",
        description = "Expose the mock Add Coins preview module in the shared Universal Overlay.",
    )
    val addGems by booleanOption(
        title = "App-specific modules > Add Gems preview",
        default = true,
        key = "hcrDemoAddGems",
        description = "Expose the mock Add Gems preview module in the shared Universal Overlay.",
    )
    val addPaints by booleanOption(
        title = "App-specific modules > Add Paints preview",
        default = true,
        key = "hcrDemoAddPaints",
        description = "Expose the mock Add Paints preview module in the shared Universal Overlay.",
    )
    val vehicles by booleanOption(
        title = "App-specific modules > Vehicle selection preview",
        default = true,
        key = "hcrDemoVehicles",
        description = "Expose the mock vehicle selection preview module in the shared Universal Overlay.",
    )
    val stages by booleanOption(
        title = "App-specific modules > Stage selection preview",
        default = true,
        key = "hcrDemoStages",
        description = "Expose the mock stage selection preview module in the shared Universal Overlay.",
    )
    val garage by booleanOption(
        title = "App-specific modules > Garage selection preview",
        default = true,
        key = "hcrDemoGarage",
        description = "Expose the mock garage selection preview module in the shared Universal Overlay.",
    )

    execute {
        val bridge = OverlayPatchRunMarker.take(this)
        check(bridge != null) {
            "Hill Climb Racing Example Overlay Addon requires Universal Overlay to inject a bridge first."
        }
        val selectedModules = buildList {
            if (addCoins == true) add("hcrDemoAddCoins")
            if (addGems == true) add("hcrDemoAddGems")
            if (addPaints == true) add("hcrDemoAddPaints")
            if (vehicles == true) add("hcrDemoVehicles")
            if (stages == true) add("hcrDemoStages")
            if (garage == true) add("hcrDemoGarage")
        }.joinToString(",")
        check(injectAppSpecificModules(bridge, "hillClimbRacingExample", selectedModules)) {
            "Universal Overlay bridge was not available for Hill Climb Racing app-specific modules."
        }
    }
}
