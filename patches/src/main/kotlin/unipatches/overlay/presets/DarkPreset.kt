package unipatches.overlay.presets

internal val darkPreset = OverlayPresetDefinition(
    "dark", "Dark", "Dark UI preset with light controls.",
    explicitOverlayPreset(
        buttonText = "D", background = "#101010", backgroundTransparency = 88, outline = "#B0B0B0",
        overlayTextColor = "#FFFFFF", buttonTextColor = "#FFFFFF", gradientBackground = true,
        buttonBackground = "#202020", iconBackground2 = "#404040", iconGradientAngle = 0,
        iconOutlineColor = "#FFFFFF", controlBackground = "#101010", controlForeground = "#FFFFFF",
        bottomButtonStyle = "solid", bottomButtonTextColor = "#101010",
        bottomButtonBackground1 = "#FFFFFF", bottomButtonBackground2 = "#FFFFFF",
    ),
)
