package unipatches.overlay.presets

internal val uniPatchesPreset = OverlayPresetDefinition(
    "unipatches", "UniPatches", "UniPatches red UI preset.",
    explicitOverlayPreset(
        buttonText = "U", background = "#300000", backgroundTransparency = 80, outline = "#FF5656",
        overlayTextColor = "#FF5656", buttonTextColor = "#FFFFFF", gradientBackground = true,
        buttonBackground = "#500000", iconBackground2 = "#AA0000", iconGradientAngle = 0,
        iconOutlineColor = "#FFFFFF", controlBackground = "#300000", controlForeground = "#FF5656",
        bottomButtonStyle = "solid", bottomButtonTextColor = "#300000",
        bottomButtonBackground1 = "#FF5656", bottomButtonBackground2 = "#FF5656",
    ),
)
