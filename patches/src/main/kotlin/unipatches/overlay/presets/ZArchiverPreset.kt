package unipatches.overlay.presets

internal val zArchiverPreset = OverlayPresetDefinition(
    "zarchiver", "ZArchiver-inspired", "ZArchiver-inspired opaque gray menu with a green outline and dark-green Z icon.",
    explicitOverlayPreset(
        buttonText = "Z", background = "#666666", backgroundTransparency = 100, outline = "#00A000",
        overlayTextColor = "#FFFFFF", buttonTextColor = "#FFFFFF", gradientBackground = false,
        buttonBackground = "#5BAA08", iconBackground2 = "#5BAA08", iconGradientAngle = 0,
        iconOutlineColor = "#FFFFFF", appendDescription = "Inspired by ZArchiver",
        buttonShape = "squircle", iconStyle = "parts",
        iconParts = listOf(
            "text|40|50|50|51|0|solid|#FFFFFF|#FFFFFF|0|100|0|Z|true|default",
            "text|60|50|50|50|0|solid|#FFFFFF|#FFFFFF|0|100|1|A|true|default",
        ),
        iconBackgroundStyle = "faceted", iconBackgroundColor3 = "#3D7806", iconBackgroundColor4 = "#69B90A",
        controlBackground = "#666666", controlForeground = "#FFFFFF",
        menuTextColor7 = "#FFFFFF",
        bottomButtonStyle = "text", bottomButtonTextColor = "#FFFFFF",
        bottomButtonBackground1 = "#FFFFFF", bottomButtonBackground2 = "#FFFFFF",
    ),
)
