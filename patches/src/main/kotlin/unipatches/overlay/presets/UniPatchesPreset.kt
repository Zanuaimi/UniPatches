package unipatches.overlay.presets

internal val uniPatchesPreset = OverlayPresetDefinition(
    "unipatches", "UniPatches", "UniPatches red UI preset.",
    explicitOverlayPreset(
        buttonText = "U", background = "#300000", backgroundTransparency = 80, outline = "#FF5656",
        overlayTextColor = "#FF5656", buttonTextColor = "#FFFFFF", gradientBackground = true,
        buttonBackground = "#500000", iconBackground2 = "#AA0000", iconGradientAngle = 0,
        iconOutlineColor = "#FFFFFF", controlBackground = "#FF5656", controlForeground = "#FF5656",
        bottomButtonStyle = "text", bottomButtonTextColor = "#FF5656",
        bottomButtonBackground1 = "#FF5656", bottomButtonBackground2 = "#FF5656",
        menuTextColor7 = "#FF5656",
    ),
)
