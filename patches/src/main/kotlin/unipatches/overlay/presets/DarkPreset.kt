package unipatches.overlay.presets

internal val darkPreset = OverlayPresetDefinition(
    "dark", "Dark", "Dark UI preset with light controls.",
    explicitOverlayPreset(
        buttonText = "D", background = "#101010", backgroundTransparency = 88, outline = "#B0B0B0",
        overlayTextColor = "#FFFFFF", buttonTextColor = "#FFFFFF", gradientBackground = true,
        buttonBackground = "#202020", iconBackground2 = "#404040", iconGradientAngle = 0,
        iconOutlineColor = "#FFFFFF", controlBackground = "#B0B0B0", controlForeground = "#FFFFFF",
        bottomButtonStyle = "text", bottomButtonTextColor = "#B0B0B0",
        bottomButtonBackground1 = "#FFFFFF", bottomButtonBackground2 = "#FFFFFF",
        menuTextColor7 = "#B0B0B0",
    ),
)
