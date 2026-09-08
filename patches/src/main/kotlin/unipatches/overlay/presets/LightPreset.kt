package unipatches.overlay.presets

internal val lightPreset = OverlayPresetDefinition(
    "light", "Light", "Light UI preset with dark controls.",
    explicitOverlayPreset(
        buttonText = "L", background = "#F5F5F5", backgroundTransparency = 92, outline = "#202020",
        overlayTextColor = "#202020", buttonTextColor = "#000000", gradientBackground = true,
        buttonBackground = "#FFFFFF", iconBackground2 = "#DADADA", iconGradientAngle = 0,
        iconOutlineColor = "#000000", controlBackground = "#F5F5F5", controlForeground = "#202020",
        bottomButtonStyle = "solid", bottomButtonTextColor = "#F5F5F5",
        bottomButtonBackground1 = "#202020", bottomButtonBackground2 = "#202020",
    ),
)
