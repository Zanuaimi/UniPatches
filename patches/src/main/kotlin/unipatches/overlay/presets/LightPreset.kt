package unipatches.overlay.presets

internal val lightPreset = OverlayPresetDefinition(
    "light", "Light", "Light UI preset with dark controls.",
    explicitOverlayPreset(
        buttonText = "L", background = "#F5F5F5", backgroundTransparency = 92, outline = "#202020",
        overlayTextColor = "#202020", buttonTextColor = "#000000", gradientBackground = true,
        buttonBackground = "#FFFFFF", iconBackground2 = "#DADADA", iconGradientAngle = 0,
        iconOutlineColor = "#000000", controlBackground = "#202020", controlForeground = "#202020",
        bottomButtonStyle = "text", bottomButtonTextColor = "#202020",
        bottomButtonBackground1 = "#202020", bottomButtonBackground2 = "#202020",
        menuTextColor7 = "#202020",
    ),
)
