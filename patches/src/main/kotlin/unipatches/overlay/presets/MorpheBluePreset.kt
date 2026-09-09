package unipatches.overlay.presets

internal val morpheBluePreset = OverlayPresetDefinition(
    "morpheBlue", "Morphe-inspired", "Morphe-inspired UI preset based on the Nai64Patches fork.",
    explicitOverlayPreset(
        buttonText = "M", background = "#101820", backgroundTransparency = 80, outline = "#55D6BE",
        overlayTextColor = "#55D6BE", buttonTextColor = "#FFFFFF", gradientBackground = true,
        buttonBackground = "#000083", iconBackground2 = "#00AF7C", iconGradientAngle = 30,
        iconOutlineColor = "#FFFFFF", appendDescription = "Inspired by Morphe",
        controlBackground = "#101820", controlForeground = "#55D6BE",
        bottomButtonStyle = "text", bottomButtonTextColor = "#55D6BE",
        bottomButtonBackground1 = "#55D6BE", bottomButtonBackground2 = "#55D6BE",
    ),
)
