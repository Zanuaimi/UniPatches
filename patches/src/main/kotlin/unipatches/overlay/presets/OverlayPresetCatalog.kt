package unipatches.overlay.presets

/** Central registry used to generate the Morphe preset dropdown. */
internal object OverlayPresetCatalog {
    val definitions: List<OverlayPresetDefinition> = listOf(
        uniPatchesPreset,
        morpheBluePreset,
        darkPreset,
        lightPreset,
        zArchiverPreset,
    )

    fun valuesFor(id: String, custom: OverlayUiPreset): OverlayUiPreset =
        definitions.firstOrNull { it.id == id }?.values ?: custom
}
