package unipatches.overlay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.imageOption
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.filePathOption
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.patch.stringsOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import helpers.bytecode.*
import helpers.startup.StartupHooks
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import unipatches.overlay.presets.OverlayPresetCatalog
import unipatches.overlay.presets.OverlayUiPreset
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.logging.Logger
import kotlin.math.roundToInt

private const val RUNTIME_CLASS = OVERLAY_RUNTIME_CLASS
private const val PRESET_SCHEMA_VERSION = 6
private const val MAX_CUSTOM_ICON_BYTES = 1024 * 1024
private const val MAX_TITLE_CHARACTERS = 80
private const val MAX_DESCRIPTION_CHARACTERS = 500
private const val MAX_LEGACY_ICON_JSON_CHARACTERS = 256 * 1024
private const val CUSTOM_SEPARATOR_BACKGROUND_DEFAULT = "#210000"
private val FONT_CHOICES = linkedMapOf(
    "Default (Android)" to "default",
    "Roboto" to "roboto",
    "Sans serif" to "sansSerif",
    "Serif" to "serif",
    "Monospace" to "monospace",
    "Sans condensed" to "sansCondensed",
    "Sans medium" to "sansMedium",
    "Sans black" to "sansBlack",
)
private val DEFAULT_ACTIVITY_INSTALL_BANLIST = """
com.google.android.gms.games.*
com.google.android.play.games.*
com.google.android.gms.auth.api.signin.*
com.google.android.gms.common.api.*
com.android.billingclient.*
com.android.vending.billing.*
com.android.vending.*
com.xiaomi.market.*
com.huawei.appmarket.*
ru.vk.store.*
ru.rustore.*
com.heytap.market.*
com.oppo.market.*
com.sec.android.app.samsungapps.*
""".trimIndent()
private val DEFAULT_DESCRIPTION =
    """
    Welcome! This is the UniPatches Universal Overlay Patch Menu.
    The idea and initial works of Universal Overlay Patch are from Zanuaimi / Noobite.
    """.trimIndent()

private fun OverlayUiPreset.toJson(): JsonObject = JsonObject().apply {
    addProperty("format", "unipatches-universal-overlay-preset")
    addProperty("version", PRESET_SCHEMA_VERSION)
    add("settings", JsonObject().apply {
        addProperty("appendDescription", appendDescription)
        addProperty("appendDescriptionColor", appendDescriptionColor)
        addProperty("descriptionAlignment", descriptionAlignment)
        addProperty("backgroundColor", background)
        addProperty("backgroundTransparency", backgroundTransparency)
        addProperty("outlineColor", outline)
        addProperty("textColor", overlayTextColor)
        addProperty("menuOutlineWidth", outlineWidth)
        addProperty("iconText", buttonText)
        addProperty("iconBold", iconBold)
        addProperty("iconTextFont", iconTextFont)
        addProperty("menuTextFont", menuTextFont)
        addProperty("iconTextColor", buttonTextColor)
        addProperty("gradientBackground", gradientBackground)
        addProperty("iconBackground1", buttonBackground)
        addProperty("iconBackground2", iconBackground2)
        addProperty("iconGradientAngle", iconGradientAngle)
        addProperty("iconOutline", iconOutline)
        addProperty("iconOutlineWidth", iconOutlineWidth)
        addProperty("iconOutlineColor", iconOutlineColor)
        addProperty("iconStyle", iconStyle)
        addProperty("iconShape", iconShape)
        addProperty("iconShapeColor1", iconShapeColor1)
        addProperty("iconShapeColor2", iconShapeColor2)
        addProperty("iconShapeGradient", iconShapeGradient)
        addProperty("iconShapeGradientAngle", iconShapeGradientAngle)
        addProperty("iconShapeStrokeWidth", iconShapeStrokeWidth)
        addProperty("iconShapeScale", iconShapeScale)
        addProperty("iconHighlight", iconHighlight)
        addProperty("iconShadow", iconShadow)
        addProperty("iconOutlineGradient", iconOutlineGradient)
        addProperty("iconOutlineColor2", iconOutlineColor2)
        addProperty("iconOutlineGradientAngle", iconOutlineGradientAngle)
        addProperty("iconBackgroundStyle", iconBackgroundStyle)
        addProperty("iconBackgroundColor3", iconBackgroundColor3)
        addProperty("iconBackgroundColor4", iconBackgroundColor4)
        add("iconParts", GsonBuilder().create().toJsonTree(iconParts))
        addProperty("customIconImageLocal", customIconImageLocal)
        addProperty("customIconImageInput", customIconImageInput)
        addProperty("buttonShape", buttonShape)
        addProperty("buttonSize", buttonSize)
        addProperty("buttonOpacity", buttonOpacity)
        addProperty("dragVisibilityDuration", dragVisibilityDuration)
        addProperty("buttonPosition", buttonPosition)
        addProperty("activityOverride", activityOverride)
        addProperty("iconTextSize", iconTextSize)
        addProperty("controlTheme", controlTheme)
        addProperty("controlBackground", controlBackground)
        addProperty("controlForeground", controlForeground)
        addProperty("bottomButtonStyle", bottomButtonStyle)
        addProperty("bottomButtonShape", bottomButtonShape)
        addProperty("bottomButtonPadding", bottomButtonPadding)
        addProperty("bottomButtonTextColor", bottomButtonTextColor)
        addProperty("bottomButtonBackground1", bottomButtonBackground1)
        addProperty("bottomButtonBackground2", bottomButtonBackground2)
        addProperty("menuTextColor1", menuTextColor1)
        addProperty("menuTextColor2", menuTextColor2)
        addProperty("menuTextColor3", menuTextColor3)
        addProperty("menuTextColor4", menuTextColor4)
        addProperty("menuTextColor5", menuTextColor5)
        addProperty("menuTextColor6", menuTextColor6)
        addProperty("menuTextColor7", menuTextColor7)
        addProperty("separatorBackgroundColor", separatorBackgroundColor)
        addProperty("separatorStyle", separatorStyle)
        addProperty("titleIconPlacement", titleIconPlacement)
        addProperty("titleAlignment", titleAlignment)
        addProperty("titleSeparator", titleSeparator)
        addProperty("menuCorners", menuCorners)
        addProperty("menuOutlineAnimation", menuOutlineAnimation)
        addProperty("outlineAnimationSpeed", outlineAnimationSpeed)
        addProperty("openingAnimation", openingAnimation)
        addProperty("closingAnimation", closingAnimation)
        addProperty("animationDuration", animationDuration)
        addProperty("animationEasing", animationEasing)
    })
}

private fun readPresetFile(source: String, fallback: OverlayUiPreset, logger: Logger): OverlayUiPreset {
    if (source.isBlank()) return fallback
    val file = runCatching { File(source).canonicalFile }.getOrNull()
    if (file == null || !file.isFile || !file.name.endsWith(".json", ignoreCase = true)) {
        logger.warning("Universal Overlay UI preset import skipped: the path is not a readable .json file. Manual Morphe settings remain active.")
        return fallback
    }
    return runCatching {
        val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        // Accept both names so early experimental exports remain usable after the schema was
        // formalized. Missing version means the original unversioned JSON shape.
        val version = root.get("version")?.asInt ?: root.get("schemaVersion")?.asInt ?: 0
        check(version in 0..PRESET_SCHEMA_VERSION) { "unsupported preset version $version" }
        val values = root.getAsJsonObject("settings") ?: root
        fun text(name: String, current: String, valid: (String) -> Boolean = { true }): String {
            val value = values.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            return value?.takeIf(valid) ?: current
        }
        fun number(name: String, current: Int, range: IntRange): Int {
            val value = values.get(name)?.takeIf { it.isJsonPrimitive }?.asInt
            if (value != null && value !in range) {
                val clamped = value.coerceIn(range)
                logger.warning("Universal Overlay UI preset value '$name' was clamped from $value to $clamped")
                return clamped
            }
            return value ?: current
        }
        fun flag(name: String, current: Boolean): Boolean =
            values.get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: current
        fun rgbColor(name: String, current: String): String {
            val value = values.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return current
            return when {
                value.matches(Regex("#[0-9a-fA-F]{6}")) -> value
                // v1.0/v1.1 experimental exports could contain #AARRGGBB. Keep their RGB
                // portion and let the background alpha migration below preserve transparency.
                value.matches(Regex("#[0-9a-fA-F]{8}")) -> "#${value.substring(3)}"
                else -> current
            }
        }
        fun choice(name: String, current: String, allowed: Set<String>): String =
            text(name, current) { it in allowed }
        fun stringList(name: String, current: List<String>, maxItems: Int = 12): List<String> {
            val element = values.get(name) ?: return current
            val values = if (element.isJsonArray) {
                element.asJsonArray.mapNotNull { item ->
                    item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }
            } else if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                element.asString.lines()
            } else {
                return current
            }
            return values.map { it.trim() }.filter { it.isNotEmpty() }.take(maxItems)
        }
        val backgroundSource = values.get("backgroundColor")?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString
        val migratedBackgroundTransparency = backgroundSource
            ?.takeIf { it.matches(Regex("#[0-9a-fA-F]{8}")) }
            ?.substring(1, 3)
            ?.toIntOrNull(16)
            ?.let { ((it * 100f) / 255f).roundToInt() }
        val imported = fallback.copy(
            appendDescription = text("appendDescription", fallback.appendDescription) { it.length <= MAX_DESCRIPTION_CHARACTERS },
            appendDescriptionColor = rgbColor("appendDescriptionColor", fallback.appendDescriptionColor),
            descriptionAlignment = choice("descriptionAlignment", fallback.descriptionAlignment, setOf("left", "center", "right")),
            background = rgbColor("backgroundColor", fallback.background),
            backgroundTransparency = if (values.has("backgroundTransparency")) {
                number("backgroundTransparency", fallback.backgroundTransparency, 0..100)
            } else {
                migratedBackgroundTransparency ?: fallback.backgroundTransparency
            },
            outline = rgbColor("outlineColor", fallback.outline),
            overlayTextColor = rgbColor("textColor", fallback.overlayTextColor),
            outlineWidth = number("menuOutlineWidth", fallback.outlineWidth, 1..8),
            buttonText = text("iconText", fallback.buttonText) { it.length <= 3 },
            iconBold = flag("iconBold", fallback.iconBold),
            iconTextFont = choice("iconTextFont", fallback.iconTextFont, FONT_CHOICES.values.toSet()),
            menuTextFont = choice("menuTextFont", fallback.menuTextFont, FONT_CHOICES.values.toSet()),
            buttonTextColor = rgbColor("iconTextColor", fallback.buttonTextColor),
            gradientBackground = flag("gradientBackground", fallback.gradientBackground),
            buttonBackground = rgbColor("iconBackground1", fallback.buttonBackground),
            iconBackground2 = rgbColor("iconBackground2", fallback.iconBackground2),
            iconGradientAngle = number("iconGradientAngle", fallback.iconGradientAngle, 0..360),
            iconOutline = flag("iconOutline", fallback.iconOutline),
            iconOutlineWidth = number("iconOutlineWidth", fallback.iconOutlineWidth, 1..8),
            iconOutlineColor = rgbColor("iconOutlineColor", fallback.iconOutlineColor),
            iconStyle = if (text("iconStyle", fallback.iconStyle) == "parts") "parts" else "text",
            iconShape = choice("iconShape", fallback.iconShape, setOf("triangle", "smile", "circle", "revanced")),
            iconShapeColor1 = rgbColor("iconShapeColor1", fallback.iconShapeColor1),
            iconShapeColor2 = rgbColor("iconShapeColor2", fallback.iconShapeColor2),
            iconShapeGradient = flag("iconShapeGradient", fallback.iconShapeGradient),
            iconShapeGradientAngle = number("iconShapeGradientAngle", fallback.iconShapeGradientAngle, 0..360),
            iconShapeStrokeWidth = number("iconShapeStrokeWidth", fallback.iconShapeStrokeWidth, 1..12),
            iconShapeScale = number("iconShapeScale", fallback.iconShapeScale, 20..100),
            iconHighlight = flag("iconHighlight", fallback.iconHighlight),
            iconShadow = flag("iconShadow", fallback.iconShadow),
            iconOutlineGradient = flag("iconOutlineGradient", fallback.iconOutlineGradient),
            iconOutlineColor2 = rgbColor("iconOutlineColor2", fallback.iconOutlineColor2),
            iconOutlineGradientAngle = number("iconOutlineGradientAngle", fallback.iconOutlineGradientAngle, 0..360),
            iconBackgroundStyle = choice("iconBackgroundStyle", fallback.iconBackgroundStyle, setOf("flat", "faceted")),
            iconBackgroundColor3 = rgbColor("iconBackgroundColor3", fallback.iconBackgroundColor3),
            iconBackgroundColor4 = rgbColor("iconBackgroundColor4", fallback.iconBackgroundColor4),
            iconParts = stringList("iconParts", fallback.iconParts),
            customIconImageLocal = text(
                "customIconImageLocal",
                text("customIconImage", fallback.customIconImageLocal),
            ),
            customIconImageInput = text("customIconImageInput", fallback.customIconImageInput),
            buttonShape = text("buttonShape", fallback.buttonShape) { it in setOf("circle", "squircle", "square") },
            buttonSize = number("buttonSize", fallback.buttonSize, 32..128),
            buttonOpacity = number("buttonOpacity", fallback.buttonOpacity, 10..100),
            dragVisibilityDuration = number("dragVisibilityDuration", fallback.dragVisibilityDuration, 1..10),
            buttonPosition = text("buttonPosition", fallback.buttonPosition) { it in setOf("topLeft", "topMiddle", "topRight", "centerLeft", "centerRight", "bottomLeft", "bottomMiddle", "bottomRight") },
            activityOverride = text("activityOverride", fallback.activityOverride),
            iconTextSize = number("iconTextSize", fallback.iconTextSize, 8..48),
            controlTheme = choice("controlTheme", fallback.controlTheme, setOf("legacy", "modern", "monet")),
            controlBackground = rgbColor("controlBackground", fallback.controlBackground),
            controlForeground = rgbColor("controlForeground", fallback.controlForeground),
            bottomButtonStyle = choice("bottomButtonStyle", fallback.bottomButtonStyle, setOf("text", "solid", "gradient")),
            bottomButtonShape = choice("bottomButtonShape", fallback.bottomButtonShape, setOf("square", "squircle")),
            bottomButtonPadding = flag("bottomButtonPadding", fallback.bottomButtonPadding),
            bottomButtonTextColor = rgbColor("bottomButtonTextColor", fallback.bottomButtonTextColor),
            bottomButtonBackground1 = rgbColor("bottomButtonBackground1", fallback.bottomButtonBackground1),
            bottomButtonBackground2 = rgbColor("bottomButtonBackground2", fallback.bottomButtonBackground2),
            menuTextColor1 = rgbColor("menuTextColor1", fallback.menuTextColor1),
            menuTextColor2 = rgbColor("menuTextColor2", fallback.menuTextColor2),
            menuTextColor3 = rgbColor("menuTextColor3", fallback.menuTextColor3),
            menuTextColor4 = rgbColor("menuTextColor4", fallback.menuTextColor4),
            menuTextColor5 = rgbColor("menuTextColor5", fallback.menuTextColor5),
            menuTextColor6 = rgbColor("menuTextColor6", fallback.menuTextColor6),
            menuTextColor7 = rgbColor("menuTextColor7", fallback.menuTextColor7),
            separatorBackgroundColor = rgbColor("separatorBackgroundColor", fallback.separatorBackgroundColor),
            separatorStyle = choice("separatorStyle", fallback.separatorStyle, setOf("ascii", "doubleLine", "background", "singleLine", "inline")),
            titleIconPlacement = choice("titleIconPlacement", fallback.titleIconPlacement, setOf("none", "left", "right", "both")),
            titleAlignment = choice("titleAlignment", fallback.titleAlignment, setOf("left", "center", "right")),
            titleSeparator = flag("titleSeparator", fallback.titleSeparator),
            menuCorners = choice("menuCorners", fallback.menuCorners, setOf("rounded", "square")),
            menuOutlineAnimation = choice("menuOutlineAnimation", fallback.menuOutlineAnimation, setOf("static", "gradient", "vertical", "rainbow")),
            outlineAnimationSpeed = number("outlineAnimationSpeed", fallback.outlineAnimationSpeed, -10..10),
            openingAnimation = choice(
                "openingAnimation",
                choice("menuAnimation", fallback.openingAnimation, setOf("fade", "scale", "disabled")),
                setOf("fade", "scale", "disabled", "appearRight", "appearTop", "appearBottom", "appearLeft"),
            ),
            closingAnimation = choice(
                "closingAnimation",
                choice("menuAnimation", fallback.closingAnimation, setOf("fade", "scale", "disabled")),
                setOf("fade", "scale", "disabled", "disappearUp", "disappearDown", "disappearLeft", "disappearRight"),
            ),
            animationDuration = number("animationDuration", fallback.animationDuration, 0..5000),
            animationEasing = choice("animationEasing", fallback.animationEasing, setOf("linear", "logarithmic")),
        )
        imported.copy(
            appendDescription = imported.appendDescription.take(
                (MAX_DESCRIPTION_CHARACTERS - imported.description.length).coerceAtLeast(0),
            ),
        )
    }.onFailure {
        logger.warning("Universal Overlay UI preset import skipped: ${it.message ?: "invalid JSON"}. Manual Morphe settings remain active.")
    }.getOrDefault(fallback)
}

private fun readLegacyIconJson(source: String, logger: Logger): String {
    if (source.isBlank()) return ""
    val file = runCatching { File(source).canonicalFile }.getOrNull()
    if (file == null || !file.isFile || !file.name.endsWith(".json", ignoreCase = true)) {
        logger.warning("Legacy icon JSON import skipped: the path is not a readable .json file.")
        return ""
    }
    return runCatching {
        val text = file.readText(Charsets.UTF_8)
        require(text.length <= MAX_LEGACY_ICON_JSON_CHARACTERS) { "legacy icon JSON is too large" }
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("format")?.asString == "unipatches-legacy-icon") { "unsupported legacy icon JSON format" }
        require(root.get("version")?.asInt == 1) { "unsupported legacy icon JSON version" }
        require(root.getAsJsonObject("settings") != null) { "legacy icon JSON has no settings" }
        root.toString()
    }.getOrElse {
        logger.warning("Legacy icon JSON import skipped: ${it.message ?: "invalid JSON"}.")
        ""
    }
}

private fun exportPreset(folder: String, outputName: String, preset: OverlayUiPreset, logger: Logger) {
    if (folder.isBlank()) return
    runCatching {
        val directory = File(folder).canonicalFile
        require(directory.isDirectory) { "target is not an existing folder: $directory" }
        protectedExportPathReason(directory)?.let { error(it) }
        val requestedName = outputName.trim()
        val base = (if (requestedName.endsWith(".json", ignoreCase = true)) requestedName.dropLast(5) else requestedName)
            .ifBlank { "UniversalOverlay" }
            .replace(Regex("[/\\\\]"), "_")
        var index = 0
        var target: File
        do {
            val suffix = if (index == 0) "" else "-$index"
            target = File(directory, "$base$suffix.json")
            index++
        } while (target.exists())
        target.writeText(GsonBuilder().setPrettyPrinting().create().toJson(preset.toJson()), Charsets.UTF_8)
        logger.info("Universal Overlay UI preset exported to $target")
    }.onFailure {
        logger.warning("Universal Overlay UI preset export failed; APK patching will continue without export: ${it.message ?: "unknown error"}")
    }
}

/**
 * Reject filesystem locations where exporting a user preset would be surprising or unsafe.
 * Canonical paths are used by the caller so symlinks cannot bypass the platform checks.
 * User folders such as /Users, /home, and C:/Users remain valid export locations.
 */
private fun protectedExportPathReason(directory: File): String? {
    val path = directory.toPath().toAbsolutePath().normalize()
    val root = path.root?.toString()?.replace('\\', '/')?.trimEnd('/')
    val normalized = path.toString().replace('\\', '/').trimEnd('/').ifBlank { "/" }
    val lower = normalized.lowercase()

    if (root != null && lower == root.lowercase()) {
        return "refusing to export to filesystem root: $directory"
    }

    // On Android, only a user directory below emulated storage is an acceptable /storage path.
    if (lower == "/storage" || lower == "/storage/emulated") {
        return "target is above Android emulated storage: $directory"
    }
    if (lower.startsWith("/storage/") && !lower.startsWith("/storage/emulated/")) {
        return "target is outside Android emulated storage: $directory"
    }

    val protectedUnixRoots = listOf(
        "/system", "/data", "/vendor", "/etc", "/usr", "/var", "/root", "/boot",
        "/dev", "/proc", "/sys", "/run", "/bin", "/sbin", "/lib", "/lib64", "/opt",
        "/private/etc", "/private/var", "/private/tmp", "/applications", "/library",
    )
    if (protectedUnixRoots.any { lower == it || lower.startsWith("$it/") }) {
        return "target is a protected system folder: $directory"
    }
    if (lower == "/volumes") {
        return "target is the macOS volumes root; choose a folder inside the volume: $directory"
    }

    // Windows system locations. Drive roots and UNC share roots were handled by the root check.
    if (Regex("^[a-z]:/", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
        val drivePrefix = lower.substringBefore(':') + ":"
        val protectedWindowsRoots = listOf(
            "/windows", "/program files", "/program files (x86)", "/programdata",
            "/\$recycle.bin", "/system volume information",
        )
        if (protectedWindowsRoots.any { lower == drivePrefix + it || lower.startsWith(drivePrefix + it + "/") }) {
            return "target is a protected Windows system folder: $directory"
        }
    }
    return null
}

private fun readBounded(input: java.io.InputStream): ByteArray {
    input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_CUSTOM_ICON_BYTES) { "icon exceeds $MAX_CUSTOM_ICON_BYTES bytes" }
        }
        return output.toByteArray()
    }
}

private fun resolveCustomIconImage(source: String, allowLocalPath: Boolean, logger: Logger, label: String): String {
    if (source.isBlank()) return ""
    val bytes = runCatching {
        when {
            allowLocalPath || source.startsWith("file:", ignoreCase = true) -> {
                val file = if (source.startsWith("file:", ignoreCase = true)) File(java.net.URI(source)) else File(source)
                require(file.isFile && file.length() in 1..MAX_CUSTOM_ICON_BYTES)
                file.readBytes()
            }
            source.startsWith("https://", ignoreCase = true) -> {
                val connection = java.net.URI(source).toURL().openConnection()
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                readBounded(connection.getInputStream())
            }
            source.startsWith("data:", ignoreCase = true) -> {
                val comma = source.indexOf(',')
                require(comma > 5 && source.substring(0, comma).contains(";base64", ignoreCase = true))
                decodeBase64(source.substring(comma + 1))
            }
            else -> decodeBase64(source)
        }
    }.getOrNull()
    if (bytes == null || bytes.isEmpty() || bytes.size > MAX_CUSTOM_ICON_BYTES) {
        logger.warning("Could not resolve custom overlay icon $label input; the next icon source or legacy icon will be used.")
        return ""
    }
    return "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes)
}

private fun decodeBase64(value: String): ByteArray {
    val compact = value.trim().replace(Regex("\\s"), "")
    require(compact.isNotEmpty())
    return runCatching { Base64.getDecoder().decode(compact) }
        .recoverCatching { Base64.getUrlDecoder().decode(compact) }
        .getOrThrow()
}

private fun descriptor(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("L") && trimmed.endsWith(";")) trimmed
    else "L${trimmed.replace('.', '/')};"
}

private fun validate(
    title: String,
    description: String,
    label: String,
    url: String,
    background: String,
    outline: String,
    overlayTextColor: String,
    buttonTextColor: String,
    buttonBackground: String,
    outlineWidth: Int,
    iconOutlineColor: String,
    iconBackground2: String,
    iconGradientAngle: Int,
    backgroundTransparency: Int,
    iconOutlineWidth: Int,
    shape: String,
    position: String,
    size: Int,
    opacity: Int,
) {
    check(title.isNotBlank() && title.length <= MAX_TITLE_CHARACTERS)
    check(description.isNotBlank() && description.length <= MAX_DESCRIPTION_CHARACTERS)
    check(label.isNotBlank() && label.length <= MAX_TITLE_CHARACTERS)
    check(url.startsWith("http://") || url.startsWith("https://"))
    fun validColor(value: String) = value.matches(Regex("#[0-9a-fA-F]{6}"))
    check(validColor(background) && validColor(outline))
    check(validColor(overlayTextColor))
    check(validColor(buttonTextColor) && validColor(buttonBackground))
    check(outlineWidth in 1..8)
    check(validColor(iconOutlineColor) && validColor(iconBackground2))
    check(iconGradientAngle in 0..360)
    check(backgroundTransparency in 0..100)
    check(iconOutlineWidth in 1..8)
    check(shape in setOf("circle", "squircle", "square"))
    check(position in setOf("topLeft", "topMiddle", "topRight", "centerLeft", "centerRight", "bottomLeft", "bottomMiddle", "bottomRight"))
    check(size in 32..128)
    check(opacity in 10..100)
}

@Suppress("unused")
val universalOverlayPatch = bytecodePatch(
    name = "UniPatches Universal Overlay Patch v2.4.0 (Experimental)",
    description = """
        A customizable in-app overlay for Android apps and games. For a quick first build: choose a visual
        preset, select the overlay modules you want, optionally supply an icon image, then patch. Modules
        are excluded and disabled by default. Monitor modules show information, Activity modules control
        the current Activity, and Hook modules make best-effort changes to app behavior. Text is the
        default legacy icon; an optional image replaces it completely, while the advanced Multi-parts editor
        supports custom drawn icons, and can be conveniently made in Icon Builder local website in UniPatches repo.
        
        This is an experimental patch and may not work on all apps. UI presets can save and reuse supported appearance and advanced icon
        settings. The title, description, repository button text, and repository button URL remain
        controlled by the visible Morphe settings. Module selections and module behavior are excluded
        because hook and module combinations can be app-specific. 
        
        If Control App Ads is patched with its optional runtime policy enabled, its selected ad-control modules appear here
        automatically; Universal Overlay does not patch ad SDKs by itself. When both patches are
        selected, Control App Ads attaches its runtime policy to this overlay's exact startup bridge,
        including an explicit Activity override, instead of selecting a separate Activity.

        Attribution: The idea and initial works of Universal Overlay Patch are from Zanuaimi / Noobite.
    """.trimIndent(),
    default = false,
) {
    // Keep the extension DEX as the runtime boundary; generated Smali should only start it and
    // must not contain overlay UI or feature implementation.
    extendWith("extensions/extension.mpe")
    dependsOn(StartupHooks.resolveRealApplicationPatch)

    val selectedPreset by stringOption(
        title = "Quick setup > Preset selection",
        default = "custom",
        key = "runtimeOverlaySelectedPreset",
        description = "Start here. Custom (UniPatches defaults) is the editable red UniPatches look. Other presets apply a complete visual style: Morphe blue, dark, light, ZArchiver-inspired, LuckyPatcher-inspired, or ReVanced-inspired. Presets never change selected modules or your title and repository details.",
        values = linkedMapOf("Custom (UniPatches defaults)" to "custom").apply {
            OverlayPresetCatalog.definitions.forEach { put(it.displayName, it.id) }
        },
    )
    val importUiPreset by filePathOption(
        title = "Quick setup > Import / export UI presets > Import UI preset",
        default = "",
        key = "runtimeOverlayImportUiPreset",
        allowedExtensions = listOf("json"),
        description = "Optional JSON UI preset path. This is for advanced reuse and works only with Custom (UniPatches defaults). A valid file overrides appearance settings for this build; an invalid file safely uses the visible settings instead.",
    )
    val exportUiPreset by stringOption(
        title = "Quick setup > Import / export UI presets > Export UI preset",
        default = "",
        key = "runtimeOverlayExportUiPreset",
        description = "Optional existing folder for exporting the final Custom UI preset as JSON. Leave blank to disable. Export errors are logged and never stop patching.",
    )
    val exportedUiPresetOutputName by stringOption(
        title = "Quick setup > Import / export UI presets > Export file name",
        default = "UniversalOverlay.json",
        key = "runtimeOverlayExportedUiPresetOutputName",
        description = "Name of the exported JSON file. The .json suffix is added when needed; duplicate names receive -1, -2, and so on.",
    )
    val includeDeviceInformation by booleanOption(
        title = "Quick setup > Module settings > Statistics > Device information",
        default = false,
        key = "runtimeOverlayIncludeDeviceInformation",
        description = "Include read-only phone and Android device information.",
    )
    val includeFps by booleanOption(
        title = "Quick setup > Module settings > Statistics > FPS",
        default = false,
        key = "runtimeOverlayIncludeFps",
        description = "Include the approximate display frame-rate statistic module.",
    )
    val includeDeviceTemperature by booleanOption(
        title = "Quick setup > Module settings > Statistics > Device temperature",
        default = false,
        key = "runtimeOverlayIncludeDeviceTemperature",
        description = "Include battery-reported temperature in the selected temperature format.",
    )
    val includeSystemTime by booleanOption(
        title = "Quick setup > Module settings > Statistics > System time",
        default = false,
        key = "runtimeOverlayIncludeSystemTime",
        description = "Include the phone system time statistic module.",
    )
    val includeSessionTime by booleanOption(
        title = "Quick setup > Module settings > Statistics > App session time",
        default = false,
        key = "runtimeOverlayIncludeSessionTime",
        description = "Include the in-process overlay session timer statistic module.",
    )
    val includeBatteryStatus by booleanOption(
        title = "Quick setup > Module settings > Statistics > Battery status",
        default = false,
        key = "runtimeOverlayIncludeBatteryStatus",
        description = "Include the current battery percentage statistic module.",
    )
    val includeAppMemory by booleanOption(
        title = "Quick setup > Module settings > Statistics > App memory usage",
        default = false,
        key = "runtimeOverlayIncludeAppMemory",
        description = "Include approximate memory used by the current app process.",
    )
    val includeNetworkStatus by booleanOption(
        title = "Quick setup > Module settings > Statistics > Network status",
        default = false,
        key = "runtimeOverlayIncludeNetworkStatus",
        description = "Include incoming and outgoing app network traffic monitors.",
    )
    val includeKeepAwake by booleanOption(
        title = "Quick setup > Module settings > Activity > Keep screen awake",
        default = false,
        key = "runtimeOverlayIncludeKeepScreenAwake",
        description = "Include the keep-screen-awake activity module.",
    )
    val includeFullscreen by booleanOption(
        title = "Quick setup > Module settings > Activity > Fullscreen",
        default = false,
        key = "runtimeOverlayIncludeFullscreen",
        description = "Include the fullscreen activity module.",
    )
    val includeScreenshots by booleanOption(
        title = "Quick setup > Module settings > Activity > Allow screenshots",
        default = false,
        key = "runtimeOverlayIncludeScreenshots",
        description = "Include the allow-screenshots activity module.",
    )
    val includeAppBrightness by booleanOption(
        title = "Quick setup > Module settings > Activity > App brightness",
        default = false,
        key = "runtimeOverlayIncludeAppBrightness",
        description = "Include a per-Activity brightness slider.",
    )
    val includeRotationMode by booleanOption(
        title = "Quick setup > Module settings > Activity > Rotation mode",
        default = false,
        key = "runtimeOverlayIncludeRotationMode",
        description = "Include a per-Activity rotation mode selector.",
    )
    val includeAppAudioMute by booleanOption(
        title = "Quick setup > Module settings > Activity > App audio mute",
        default = false,
        key = "runtimeOverlayIncludeAppAudioMute",
        description = "Include a best-effort app audio mute toggle.",
    )
    val includeDisableHaptics by booleanOption(
        title = "Quick setup > Module settings > Hook > Disable haptic feedback / vibrations",
        default = false,
        key = "runtimeOverlayIncludeDisableHaptics",
        description = "Risk: broad runtime hook. Include a best-effort haptic and vibration suppression module only if the app needs it.",
    )
    val includeDisableAnimations by booleanOption(
        title = "Quick setup > Module settings > Hook > Disable app animations",
        default = false,
        key = "runtimeOverlayIncludeDisableAnimations",
        description = "Risk: broad runtime hook. Include a best-effort animation suppression module only if the app needs it.",
    )
    val activateStatisticsOnLaunch by booleanOption(
        title = "Quick setup > Settings to modules > Monitor behavior > Activate statistics on launch",
        default = false,
        key = "runtimeOverlayActivateStatisticsOnLaunch",
        description = "Start selected statistic modules as soon as the app launches. Active is disabled by default.",
    )
    val showNoModulesWarning by booleanOption(
        title = "Quick setup > Settings to modules > General > Show empty-module message",
        default = true,
        key = "runtimeOverlayShowNoModulesWarning",
        description = "Show a message in the overlay when no Statistic, Activity, Hook, app-specific, or integrated module is selected. Control App Ads modules count only when that patch was also configured and patched successfully.",
    )
    val enableMonitorsOnLaunch by booleanOption(
        title = "Quick setup > Settings to modules > Monitor behavior > Enable monitors on launch",
        default = false,
        key = "runtimeOverlayEnableMonitorsOnLaunch",
        description = "Show selected statistic monitors as soon as the app launches. Monitor is disabled by default.",
    )
    val statisticMonitorPosition by stringOption(
        title = "Quick setup > Settings to modules > Monitor behavior > Monitor position",
        default = "bottom",
        key = "runtimeOverlayStatisticMonitorPosition",
        description = "Show enabled statistic monitors from statistic modules above or below the overlay button.",
        values = linkedMapOf("No stat monitors" to "none", "Above overlay button" to "top", "Below overlay button" to "bottom"),
    )
    val monitorScale by stringOption(
        title = "Quick setup > Settings to modules > Monitor behavior > Monitor panel size",
        default = "1",
        key = "runtimeOverlayMonitorScale",
        description = "Size multiplier for statistic monitor panels.",
        values = linkedMapOf("0.75x" to "0.75", "1x" to "1", "1.25x" to "1.25", "1.5x" to "1.5", "2x" to "2"),
    )
    val monitorColumns by stringOption(
        title = "Quick setup > Settings to modules > Monitor behavior > Monitor columns",
        default = "2",
        key = "runtimeOverlayMonitorColumns",
        description = "Number of statistic monitor columns.",
        values = linkedMapOf("1 column" to "1", "2 columns" to "2", "3 columns" to "3"),
    )
    val temperatureFormat by stringOption(
        title = "Quick setup > Settings to modules > Monitor behavior > Temperature format",
        default = "celsius",
        key = "runtimeOverlayTemperatureFormat",
        description = "Temperature unit used by the Device Temperature menu value and monitor.",
        values = linkedMapOf("Celsius" to "celsius", "Fahrenheit" to "fahrenheit", "Kelvin" to "kelvin"),
    )
    val timeFormat by stringOption(
        title = "Quick setup > Settings to modules > Monitor behavior > Time format",
        default = "12",
        key = "runtimeOverlayTimeFormat",
        description = "Clock format used by the System Time menu value and monitor. Timezone is shown in the menu value.",
        values = linkedMapOf("12-hour clock" to "12", "24-hour clock" to "24"),
    )
    val controlTheme by stringOption(
        title = "UI settings > Controls > Theme",
        default = "modern",
        key = "runtimeOverlayControlTheme",
        description = "Geometry used by overlay controls. Settings, Action, and dropdown controls use Legacy square corners, Modern rounded corners, or Monet-style pill corners. Their fill and outline colors come from the overlay menu.",
        values = linkedMapOf("Legacy" to "legacy", "Modern (default)" to "modern", "Monet-style" to "monet"),
    )
    val controlBackground by stringOption(
        title = "UI settings > Controls > Background color",
        default = "#300000",
        key = "runtimeOverlayControlBackground",
        description = "Background color for overlay controls.",
    )
    val controlForeground by stringOption(
        title = "UI settings > Controls > Foreground color",
        default = "#FF5656",
        key = "runtimeOverlayControlForeground",
        description = "Foreground color for control contents, such as text, slider progress, and checked checkbox state.",
    )
    val bottomButtonStyle by stringOption(
        title = "UI settings > Bottom buttons > Style",
        default = "text",
        key = "runtimeOverlayBottomButtonStyle",
        description = "Style of the repository, close-menu, and fully-close buttons.",
        values = linkedMapOf("Text only (default)" to "text", "Solid background" to "solid", "Gradient background" to "gradient"),
    )
    val bottomButtonShape by stringOption(
        title = "UI settings > Bottom buttons > Shape",
        default = "square",
        key = "runtimeOverlayBottomButtonShape",
        description = "Shape of bottom action button backgrounds.",
        values = linkedMapOf("Full square" to "square", "Squircle" to "squircle"),
    )
    val bottomButtonPadding by booleanOption(
        title = "UI settings > Bottom buttons > Add spacing",
        default = false,
        key = "runtimeOverlayBottomButtonPadding",
        description = "Add padding between the three bottom action buttons.",
    )
    val bottomButtonTextColor by stringOption(
        title = "UI settings > Bottom buttons > Text color",
        default = "#FFFFFF",
        key = "runtimeOverlayBottomButtonTextColor",
        description = "Text color of the three bottom action buttons.",
    )
    val bottomButtonBackground1 by stringOption(
        title = "UI settings > Bottom buttons > Background color 1",
        default = "#FF5656",
        key = "runtimeOverlayBottomButtonBackground1",
        description = "First background color for solid or gradient bottom action buttons.",
    )
    val bottomButtonBackground2 by stringOption(
        title = "UI settings > Bottom buttons > Background color 2",
        default = "#FF5656",
        key = "runtimeOverlayBottomButtonBackground2",
        description = "Second background color for gradient bottom action buttons.",
    )
    val menuTextColor1 by stringOption(title = "UI settings > Colors > Text color 1 (title, lines, scrollbar)", default = "#FF5656", key = "runtimeOverlayMenuTextColor1", description = "Color for the menu title, separator lines, and scrollbar thumb.")
    val menuTextColor2 by stringOption(title = "UI settings > Colors > Text color 2 (module names)", default = "#FF5656", key = "runtimeOverlayMenuTextColor2", description = "Color for module names.")
    val menuTextColor3 by stringOption(title = "UI settings > Colors > Text color 3 (descriptions)", default = "#FF5656", key = "runtimeOverlayMenuTextColor3", description = "Color for overlay and module descriptions.")
    val menuTextColor4 by stringOption(title = "UI settings > Colors > Text color 4 (monitor)", default = "#FF5656", key = "runtimeOverlayMenuTextColor4", description = "Color for monitor labels.")
    val menuTextColor5 by stringOption(title = "UI settings > Colors > Text color 5 (active)", default = "#FF5656", key = "runtimeOverlayMenuTextColor5", description = "Color for Active labels.")
    val menuTextColor6 by stringOption(title = "UI settings > Colors > Text color 6 (module separators)", default = "#FF5656", key = "runtimeOverlayMenuTextColor6", description = "Color for module separator text.")
    val menuTextColor7 by stringOption(title = "UI settings > Colors > Text color 7 (control outlines)", default = "#FF5656", key = "runtimeOverlayMenuTextColor7", description = "Color for Settings, Action, and dropdown control outlines. This is independent from the menu outline color.")
    val separatorBackgroundColor by stringOption(title = "UI settings > Module separators > Background color", default = CUSTOM_SEPARATOR_BACKGROUND_DEFAULT, key = "runtimeOverlaySeparatorBackgroundColor", description = "Used only with the Background behind text separator style.")
    val separatorStyle by stringOption(
        title = "UI settings > Module separators > Style",
        default = "ascii",
        key = "runtimeOverlaySeparatorStyle",
        description = "How module type separators and hints appear.",
        values = linkedMapOf("--- Module type ---" to "ascii", "Lines above and below" to "doubleLine", "Background behind text" to "background", "Line below" to "singleLine", "Inline after module name" to "inline"),
    )
    val titleIconPlacement by stringOption(
        title = "UI settings > Menu title > Icon placement",
        default = "none",
        key = "runtimeOverlayTitleIconPlacement",
        description = "Show a non-clickable copy of the overlay icon beside the menu title.",
        values = linkedMapOf("No icons (default)" to "none", "Top left" to "left", "Top right" to "right", "Both sides" to "both"),
    )
    val titleAlignment by stringOption(
        title = "UI settings > Menu title > Alignment",
        default = "left",
        key = "runtimeOverlayTitleAlignment",
        description = "Alignment of the overlay menu title.",
        values = linkedMapOf("Left" to "left", "Center" to "center", "Right" to "right"),
    )
    val titleSeparator by booleanOption(title = "UI settings > Menu title > Separator", default = false, key = "runtimeOverlayTitleSeparator", description = "Show a Text color 1 line below the title.")
    val menuCorners by stringOption(title = "UI settings > Menu layout > Corners", default = "rounded", key = "runtimeOverlayMenuCorners", description = "Choose rounded or square menu corners.", values = linkedMapOf("Rounded (default)" to "rounded", "Square" to "square"))
    val menuOutlineAnimation by stringOption(title = "UI settings > Menu outline > Animation", default = "static", key = "runtimeOverlayMenuOutlineAnimation", description = "Choose a static, horizontal, vertical, or rainbow menu outline animation.", values = linkedMapOf("Static (default)" to "static", "Horizontal scrolling gradient" to "gradient", "Vertical gradient" to "vertical", "Rainbow gradient" to "rainbow"))
    val outlineAnimationSpeed by intOption(title = "UI settings > Menu outline > Animation speed", default = 1, key = "runtimeOverlayOutlineAnimationSpeed", description = "Use -10 to 10. Zero stops movement; positive vertical values move upward and negative values move downward.")
    val openingAnimation by stringOption(
        title = "UI settings > Animations > Opening animation",
        default = "fade",
        key = "runtimeOverlayOpeningAnimation",
        description = "Opening animation. Directional appearances also fade in the menu.",
        values = linkedMapOf("Fade (default)" to "fade", "Appear from right" to "appearRight", "Appear from top" to "appearTop", "Appear from bottom" to "appearBottom", "Appear from left" to "appearLeft", "Scale" to "scale", "Disabled" to "disabled"),
    )
    val closingAnimation by stringOption(
        title = "UI settings > Animations > Closing animation",
        default = "fade",
        key = "runtimeOverlayClosingAnimation",
        description = "Closing animation. Directional disappearances also fade out the menu.",
        values = linkedMapOf("Fade (default)" to "fade", "Disappear upwards" to "disappearUp", "Disappear downwards" to "disappearDown", "Disappear leftwards" to "disappearLeft", "Disappear rightwards" to "disappearRight", "Scale" to "scale", "Disabled" to "disabled"),
    )
    val animationDuration by intOption(title = "UI settings > Animations > Duration (ms)", default = 180, key = "runtimeOverlayAnimationDuration", description = "Duration for opening and closing animations. Negative values become zero.")
    val animationEasing by stringOption(title = "UI settings > Animations > Motion curve", default = "linear", key = "runtimeOverlayAnimationEasing", description = "Motion curve for fade, directional, and scale animations.", values = linkedMapOf("Linear (default)" to "linear", "Logarithmic" to "logarithmic"))
    val outlineWidth by intOption(
        title = "UI settings > Menu outline > Width (dp)",
        default = 2,
        key = "runtimeOverlayOutlineWidthDp",
        description = "Width of the menu, monitor, and confirmation outlines, from 1 to 8dp.",
    )

    val title by stringOption(
        title = "UI settings > Menu text > Title",
        default = "UniPatches Universal Overlay Patch",
        key = "runtimeOverlayTitle",
        description = "Title shown in the overlay menu. Limited to 80 characters.",
    )
    val descriptionText by stringOption(
        title = "UI settings > Menu text > Description",
        default = DEFAULT_DESCRIPTION,
        key = "runtimeOverlayDescription",
        description = "Description below the title. Limited to 500 characters.",
    )
    val appendDescriptionText by stringOption(
        title = "UI settings > Menu text > Appended description",
        default = "",
        key = "runtimeOverlayAppendDescription",
        description = "Optional text appended below the overlay description. Useful for short inspiration or attribution notes such as Inspired by Example. The main and appended descriptions share a combined 500-character limit.",
    )
    val descriptionAlignment by stringOption(
        title = "UI settings > Menu text > Description alignment",
        default = "center",
        key = "runtimeOverlayDescriptionAlignment",
        description = "Alignment of the main and appended overlay description text.",
        values = linkedMapOf("Left" to "left", "Center (default)" to "center", "Right" to "right"),
    )
    val appendDescriptionColor by stringOption(
        title = "UI settings > Menu text > Appended description color",
        default = "#FF5656",
        key = "runtimeOverlayAppendDescriptionColor",
        description = "Color of appended overlay-description text. Presets default to menu text color 3.",
    )
    val backgroundColor by stringOption(
        title = "UI settings > Menu background > Color",
        default = "#300000",
        key = "runtimeOverlayBackgroundColor",
        description = "Overlay background color as #RRGGBB. Transparency is controlled separately.",
    )
    val backgroundTransparency by intOption(
        title = "UI settings > Menu background > Transparency (%)",
        default = 80,
        key = "runtimeOverlayBackgroundTransparency",
        description = "Transparency of the overlay background from 0% to 100%. 80% matches the default Morphe-style background alpha.",
    )
    val outlineColor by stringOption(
        title = "UI settings > Menu outline > Color",
        default = "#FF5656",
        key = "runtimeOverlayOutlineColor",
        description = "Color of the overlay menu outline and menu text. Use #RRGGBB. Animated gradient menu outlines keep their own gradient colors. Settings, Action, and dropdown control outlines use Text color 7.",
    )
    val repositoryText by stringOption(
        title = "UI settings > Bottom buttons > Repository text",
        default = "UniPatches repository",
        key = "runtimeOverlayRepositoryText",
        description = "Text of the always-present repository button.",
    )
    val repositoryUrl by stringOption(
        title = "UI settings > Bottom buttons > Repository URL",
        default = "https://github.com/Zanuaimi/UniPatches",
        key = "runtimeOverlayRepositoryUrl",
        description = "URL opened by the repository button.",
    )
    val buttonText by stringOption(
        title = "UI settings > Floating button > Text",
        default = "U",
        key = "runtimeOverlayButtonText",
        description = "Text shown on the default floating icon. Maximum three characters.",
    )
    val iconBold by booleanOption(
        title = "UI settings > Floating button > Bold text",
        default = true,
        key = "runtimeOverlayIconBold",
        description = "Make the default floating-icon text bold.",
    )
    val buttonTextColor by stringOption(
        title = "UI settings > Floating button > Text color",
        default = "#FFFFFF",
        key = "runtimeOverlayButtonTextColor",
        description = "Text color for the default floating icon.",
    )
    val iconTextSize by intOption(
        title = "UI settings > Floating button > Text size (sp)",
        default = 18,
        key = "runtimeOverlayIconTextSizeSp",
        description = "Text size for the default floating icon, from 8 to 48sp.",
    )
    val iconTextFont by stringOption(
        title = "UI settings > Floating button > Text icon font",
        default = "default",
        key = "runtimeOverlayIconTextFont",
        description = "Font used by the legacy text icon.",
        values = FONT_CHOICES,
    )
    val menuTextFont by stringOption(
        title = "UI settings > Menu > Text font",
        default = "default",
        key = "runtimeOverlayMenuTextFont",
        description = "Font used by overlay menu text, popup menus, and confirmation popup menus.",
        values = FONT_CHOICES,
    )
    val iconStyle by stringOption(
        title = "UI settings > Floating button > Icon type",
        default = "text",
        key = "runtimeOverlayIconStyle",
        description = "Text is the simple default. Choose Multi-parts only when you want to build a drawn icon in the Multi-parts icon editor.",
        values = linkedMapOf("Text icon (default)" to "text", "Multi-parts icon" to "parts"),
    )
    val iconParts by stringsOption(
        title = "UI settings > Floating button > Multi-parts icon editor > Part list",
        default = emptyList(),
        key = "runtimeOverlayIconParts",
        description = """
            Use only when Icon part type is Multi-parts. Add one string per part.

            Template:
            <shape>|<x-position>|<y-position>|<x-scale>|<y-scale>|<rotation>|<fill>|<color-1>|<color-2>|<stroke>|<opacity>|<layer>|<text>|<bold>|<font>

            Tag values:
            shape = triangle, roundedTriangle, v, circle, square, roundedRect, line, arc, diamond, star, heart, or text.
            Position X/Y and Scale W/H = 0–100 percent. Different scales make rectangles or ellipses.
            rotation = -360 to 360 degrees; for example 0, 45, or 90.
            fill = solid or gradient. Colors use #RRGGBB. stroke = 0–32. opacity = 0–100. Higher layer draws on top.
            text is optional except for a text part, and is trimmed to three characters. The optional bold field is true or false and applies only to text parts. The optional font field accepts default, roboto, sansSerif, serif, monospace, sansCondensed, sansMedium, or sansBlack.

            Valid examples:
            triangle|50|50|55|55|0|solid|#4E97F0|#4E97F0|0|100|0
            v|50|54|57|54|0|solid|#FFFFFF|#FFFFFF|16|100|0
            triangle|50|39|28|22|0|gradient|#E651A0|#6564D3|0|100|1
            text|50|50|60|50|0|solid|#FFFFFF|#FFFFFF|0|100|2|LP|true

            Maximum 12 parts.
        """.trimIndent(),
    )
    val importLegacyIconJson by filePathOption(
        title = "UI settings > Floating button > Multi-parts icon editor > Import legacy icon JSON",
        default = "",
        key = "runtimeOverlayImportLegacyIconJson",
        allowedExtensions = listOf("json"),
        description = "Optional legacy icon JSON exported by the local icon builder website in the UniPatches repository (tools/icon-builder). A valid file takes priority over legacy text/shape settings and the Multi-parts list, but remains below a valid custom icon image. It does not control overlay menu icon existence or placement.",
    )
    val iconHighlight by booleanOption(
        title = "UI settings > Floating button > Multi-parts icon editor > Add highlight",
        default = false,
        key = "runtimeOverlayIconHighlight",
        description = "Add a subtle glossy highlight to the upper-left of a Multi-parts icon.",
    )
    val gradientBackground by booleanOption(
        title = "UI settings > Floating button > Gradient background",
        default = true,
        key = "runtimeOverlayIconGradientBackground",
        description = "Blend floating-icon background color 1 into color 2. When disabled, only color 1 is used.",
    )
    val buttonBackgroundColor by stringOption(
        title = "UI settings > Floating button > Background color 1",
        default = "#500000",
        key = "runtimeOverlayButtonBackgroundColor",
        description = "First floating-icon background color.",
    )
    val iconBackground2 by stringOption(
        title = "UI settings > Floating button > Background color 2",
        default = "#AA0000",
        key = "runtimeOverlayIconBackgroundColor2",
        description = "Second floating-icon background color. Use #RRGGBB.",
    )
    val iconGradientAngle by intOption(
        title = "UI settings > Floating button > Gradient angle (degrees)",
        default = 0,
        key = "runtimeOverlayIconGradientAngle",
        description = "Gradient direction: 0 degrees runs top to bottom and 90 runs left to right. Values wrap through 360 degrees.",
    )
    val iconBackgroundStyle by stringOption(
        title = "UI settings > Floating button > Background style",
        default = "flat",
        key = "runtimeOverlayIconBackgroundStyle",
        description = "Choose a flat floating-icon background or faceted angular layers. Faceted mode suits geometric icons such as Z.",
        values = linkedMapOf("Flat (default)" to "flat", "Faceted layers" to "faceted"),
    )
    val iconBackgroundColor3 by stringOption(
        title = "UI settings > Floating button > Background color 3",
        default = "#3D7806",
        key = "runtimeOverlayIconBackgroundColor3",
        description = "Third color used by Faceted layers. It is ignored when Background style is Flat. Use #RRGGBB.",
    )
    val iconBackgroundColor4 by stringOption(
        title = "UI settings > Floating button > Background color 4",
        default = "#4F9905",
        key = "runtimeOverlayIconBackgroundColor4",
        description = "Fourth color used by Faceted layers. It is ignored when Background style is Flat. Use #RRGGBB.",
    )
    val iconOutline by booleanOption(
        title = "UI settings > Floating button > Outline",
        default = false,
        key = "runtimeOverlayIconOutline",
        description = "Add a separate outline around the floating icon.",
    )
    val iconOutlineWidth by intOption(
        title = "UI settings > Floating button > Outline width (dp)",
        default = 3,
        key = "runtimeOverlayIconOutlineWidthDp",
        description = "Floating-icon outline width from 1 to 8dp. This is separate from the menu outline.",
    )
    val iconOutlineColor by stringOption(
        title = "UI settings > Floating button > Outline color",
        default = "#FFFFFF",
        key = "runtimeOverlayIconOutlineColor",
        description = "Color used only when the icon outline is enabled.",
    )
    val iconOutlineGradient by booleanOption(
        title = "UI settings > Floating button > Outline gradient",
        default = false,
        key = "runtimeOverlayIconOutlineGradient",
        description = "Blend two colors around the floating-icon outline.",
    )
    val iconOutlineColor2 by stringOption(
        title = "UI settings > Floating button > Outline color 2",
        default = "#FFFFFF",
        key = "runtimeOverlayIconOutlineColor2",
        description = "Second outline color used when Outline gradient is enabled. Use #RRGGBB.",
    )
    val iconOutlineGradientAngle by intOption(
        title = "UI settings > Floating button > Outline gradient angle (degrees)",
        default = 0,
        key = "runtimeOverlayIconOutlineGradientAngle",
        description = "Direction of the icon outline gradient. 0 degrees runs top to bottom and 90 degrees runs left to right.",
    )
    val customIconImage by imageOption(
        title = "Quick setup > Optional icon image > Local image",
        default = "",
        key = "runtimeOverlayCustomIconImage",
        allowedExtensions = listOf("png", "jpg", "jpeg", "webp"),
        recommendedSize = app.morphe.patcher.patch.ImageSize(128, 128),
        description = "Select a local PNG, JPG, JPEG, or WebP image file. This input has priority over the String Handler input when valid. Images are embedded during patching and scaled proportionally. Any supplied image takes full precedence over Text and Multi-parts icon rendering.",
    )
    val customIconImageInput by stringOption(
        title = "Quick setup > Optional icon image > Base64 or HTTPS image",
        default = "",
        key = "runtimeOverlayCustomIconImageInput",
        description = "Optional non-local image input. Valid examples: <base64 string here>, data:image/png;base64,<base64 string here>, or an HTTPS image URL. For image-to-Base64 conversion, use https://base64.guru/converter/encode/image. Used only when the Local Image input is empty or invalid. A supplied image takes full precedence over Text and Multi-parts icon rendering.",
    )
    val buttonShape by stringOption(
        title = "UI settings > Floating button > Shape",
        default = "circle",
        key = "runtimeOverlayButtonShape",
        description = "Shape of the floating icon background.",
        values = linkedMapOf("Circle" to "circle", "Squircle" to "squircle", "Square" to "square"),
    )
    val buttonSizeDp by intOption(
        title = "UI settings > Floating button > Size (dp)",
        default = 56,
        key = "runtimeOverlayButtonSizeDp",
        description = "Button size in density-independent pixels.",
    )
    val buttonOpacity by intOption(
        title = "UI settings > Floating button > Idle opacity (%)",
        default = 50,
        key = "runtimeOverlayButtonIdleOpacityPercent",
        description = "Idle opacity from 10 to 100 percent. Higher values make the button less transparent.",
    )
    val buttonDragVisibilityDurationSeconds by intOption(
        title = "UI settings > Floating button > Fully visible duration (seconds)",
        default = 2,
        key = "runtimeOverlayButtonDragVisibilityDurationSeconds",
        description = "How long the overlay button stays fully visible after dragging before fading to its idle opacity. The timer resets while dragging and starts again when the finger is released. Use a value from 1 to 10 seconds.",
    )
    val buttonPosition by stringOption(
        title = "UI settings > Floating button > Initial position",
        default = "topRight",
        key = "runtimeOverlayButtonPosition",
        description = "Initial floating button position.",
        values = linkedMapOf(
            "Top left" to "topLeft", "Top middle" to "topMiddle", "Top right" to "topRight",
            "Center left" to "centerLeft", "Center right" to "centerRight",
            "Bottom left" to "bottomLeft", "Bottom middle" to "bottomMiddle", "Bottom right" to "bottomRight",
        ),
    )
    val activityOverride by stringOption(
        title = "Advanced > Activity injection > Target Activity name",
        default = "",
        key = "runtimeOverlayActivityNameOverride",
        description = "Risk: a wrong Activity can prevent the overlay from appearing. Leave blank for automatic discovery. Only use this with Explicit target Activity first; example: com.example.MainActivity.",
    )
    val activityInjectionMode by stringOption(
        title = "Advanced > Activity injection > Strategy",
        default = OverlayConfigPayload.UNIVERSAL_INJECTION_MODE,
        key = "runtimeOverlayActivityInjectionMode",
        description = "Risk: manual targeting can miss or break an app's startup. Keep automatic discovery unless you know the target Activity. Explicit mode tries the Activity above, then safely falls back to automatic discovery.",
        values = linkedMapOf(
            "Universal automatic discovery (default)" to OverlayConfigPayload.UNIVERSAL_INJECTION_MODE,
            "Explicit target Activity, then universal fallback" to OverlayConfigPayload.EXPLICIT_ACTIVITY_INJECTION_MODE,
        ),
    )
    val activityInstallBanlist by stringsOption(
        title = "Advanced > Activity injection > Install banlist",
        default = DEFAULT_ACTIVITY_INSTALL_BANLIST.lines(),
        key = "runtimeOverlayActivityInstallBanlist",
        description = "Risk: changing this can place the overlay on sign-in, billing, or store screens. Add one Activity class or package prefix per row; use * for a prefix. Keep the defaults unless an app needs a specific adjustment. Use only none to disable the list.",
    )
    val resetIconToText by booleanOption(
        title = "UI settings > Floating button > Reset icon to text",
        default = false,
        key = "runtimeOverlayResetIconToText",
        description = "Use the configured text icon for this patched APK and ignore image and Multi-parts icon inputs.",
    )
    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val rawAnimationDuration = animationDuration ?: 180
        if (rawAnimationDuration < 0) {
            logger.warning("Universal Overlay animation duration was clamped from $rawAnimationDuration to 0")
        }
        val manualDescription = descriptionText.orEmpty().ifBlank { DEFAULT_DESCRIPTION }.take(MAX_DESCRIPTION_CHARACTERS)
        val manualAppendDescription = appendDescriptionText.orEmpty().take(
            (MAX_DESCRIPTION_CHARACTERS - manualDescription.length).coerceAtLeast(0),
        )
        val manualBackground = backgroundColor.orEmpty().ifBlank { "#300000" }
        val manualPreset = OverlayUiPreset(
            title = title.orEmpty().ifBlank { "UniPatches Universal Overlay Patch" }.take(MAX_TITLE_CHARACTERS),
            description = manualDescription,
            appendDescription = manualAppendDescription,
            descriptionAlignment = descriptionAlignment.orEmpty().ifBlank { "center" },
            appendDescriptionColor = appendDescriptionColor.orEmpty().ifBlank { menuTextColor3.orEmpty().ifBlank { "#FF5656" } },
            repositoryText = repositoryText.orEmpty().ifBlank { "UniPatches repository" },
            repositoryUrl = repositoryUrl.orEmpty().ifBlank { "https://github.com/Zanuaimi/UniPatches" },
            background = manualBackground,
            backgroundTransparency = (backgroundTransparency ?: 80).coerceIn(0, 100),
            outline = outlineColor.orEmpty().ifBlank { "#FF5656" },
            overlayTextColor = menuTextColor1.orEmpty().ifBlank { "#FF5656" },
            outlineWidth = (outlineWidth ?: 2).coerceIn(1, 8),
            buttonText = buttonText.orEmpty().trim().take(3).ifBlank { "U" },
            iconBold = iconBold != false,
            buttonTextColor = buttonTextColor.orEmpty().ifBlank { "#FFFFFF" },
            gradientBackground = gradientBackground != false,
            buttonBackground = buttonBackgroundColor.orEmpty().ifBlank { "#500000" },
            iconBackground2 = iconBackground2.orEmpty().ifBlank { "#AA0000" },
            iconGradientAngle = ((iconGradientAngle ?: 0) % 361 + 361) % 361,
            iconOutline = iconOutline == true,
            iconOutlineWidth = (iconOutlineWidth ?: 3).coerceIn(1, 8),
            iconOutlineColor = iconOutlineColor.orEmpty().ifBlank { "#FFFFFF" },
            iconStyle = if (iconStyle == "parts") "parts" else "text",
            // Retained only in the compatible payload schema; Multi-parts owns all symbol data.
            iconShape = "triangle",
            iconShapeColor1 = "#FFFFFF",
            iconShapeColor2 = "#FFFFFF",
            iconShapeGradient = false,
            iconShapeGradientAngle = 0,
            iconShapeStrokeWidth = 3,
            iconShapeScale = 70,
            iconHighlight = iconHighlight == true,
            iconShadow = false,
            iconOutlineGradient = iconOutlineGradient == true,
            iconOutlineColor2 = iconOutlineColor2.orEmpty().ifBlank { "#FFFFFF" },
            iconOutlineGradientAngle = ((iconOutlineGradientAngle ?: 0) % 361 + 361) % 361,
            iconBackgroundStyle = iconBackgroundStyle.orEmpty().ifBlank { "flat" },
            iconBackgroundColor3 = iconBackgroundColor3.orEmpty().ifBlank { "#3D7806" },
            iconBackgroundColor4 = iconBackgroundColor4.orEmpty().ifBlank { "#4F9905" },
            iconParts = iconParts.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.take(12),
            customIconImageLocal = customIconImage.orEmpty().trim(),
            customIconImageInput = customIconImageInput.orEmpty().trim(),
            buttonShape = buttonShape.orEmpty().ifBlank { "circle" },
            buttonSize = (buttonSizeDp ?: 56).coerceIn(32, 128),
            buttonOpacity = (buttonOpacity ?: 50).coerceIn(10, 100),
            dragVisibilityDuration = (buttonDragVisibilityDurationSeconds ?: 2).coerceIn(1, 10),
            buttonPosition = buttonPosition.orEmpty().ifBlank { "topRight" },
            activityOverride = activityOverride.orEmpty().trim(),
            iconTextSize = (iconTextSize ?: 18).coerceIn(8, 48),
            iconTextFont = iconTextFont.orEmpty().ifBlank { "default" },
            menuTextFont = menuTextFont.orEmpty().ifBlank { "default" },
            controlTheme = controlTheme.orEmpty().ifBlank { "modern" },
            controlBackground = controlBackground.orEmpty().ifBlank {
                outlineColor.orEmpty().ifBlank { "#FF5656" }
            },
            controlForeground = controlForeground.orEmpty().ifBlank { "#FF5656" },
            bottomButtonStyle = bottomButtonStyle.orEmpty().ifBlank { "text" },
            bottomButtonShape = bottomButtonShape.orEmpty().ifBlank { "square" },
            bottomButtonPadding = bottomButtonPadding == true,
            bottomButtonTextColor = bottomButtonTextColor.orEmpty().ifBlank {
                menuTextColor1.orEmpty().ifBlank { "#FFFFFF" }
            },
            bottomButtonBackground1 = bottomButtonBackground1.orEmpty().ifBlank { "#FF5656" },
            bottomButtonBackground2 = bottomButtonBackground2.orEmpty().ifBlank { "#FF5656" },
            menuTextColor1 = menuTextColor1.orEmpty().ifBlank { "#FF5656" },
            menuTextColor2 = menuTextColor2.orEmpty().ifBlank { "#FF5656" },
            menuTextColor3 = menuTextColor3.orEmpty().ifBlank { "#FF5656" },
            menuTextColor4 = menuTextColor4.orEmpty().ifBlank { "#FF5656" },
            menuTextColor5 = menuTextColor5.orEmpty().ifBlank { "#FF5656" },
            menuTextColor6 = menuTextColor6.orEmpty().ifBlank { menuTextColor2.orEmpty().ifBlank { "#FF5656" } },
            menuTextColor7 = menuTextColor7.orEmpty().ifBlank { outlineColor.orEmpty().ifBlank { "#FF5656" } },
            separatorBackgroundColor = separatorBackgroundColor.orEmpty().ifBlank { CUSTOM_SEPARATOR_BACKGROUND_DEFAULT },
            separatorStyle = separatorStyle.orEmpty().ifBlank { "ascii" },
            titleIconPlacement = titleIconPlacement.orEmpty().ifBlank { "none" },
            titleAlignment = titleAlignment.orEmpty().ifBlank { "left" },
            titleSeparator = titleSeparator == true,
            menuCorners = menuCorners.orEmpty().ifBlank { "rounded" },
            menuOutlineAnimation = menuOutlineAnimation.orEmpty().ifBlank { "static" },
            outlineAnimationSpeed = (outlineAnimationSpeed ?: 1).coerceIn(-10, 10),
            openingAnimation = openingAnimation.orEmpty().ifBlank { "fade" },
            closingAnimation = closingAnimation.orEmpty().ifBlank { "fade" },
            animationDuration = (animationDuration ?: 180).coerceAtLeast(0),
            animationEasing = animationEasing.orEmpty().ifBlank { "linear" },
        )
        val resetIcon = resetIconToText == true
        val customMode = selectedPreset.orEmpty().equals("custom", ignoreCase = true)
        val selectedUiPreset = (if (customMode) {
            readPresetFile(importUiPreset.orEmpty().trim(), manualPreset, logger)
        } else {
            OverlayPresetCatalog.valuesFor(selectedPreset.orEmpty(), manualPreset)
        }).copy(
            // These General values always remain app/user-specific and are never supplied by a UI preset.
            title = manualPreset.title,
            description = manualPreset.description,
            repositoryText = manualPreset.repositoryText,
            repositoryUrl = manualPreset.repositoryUrl,
        )
        val titleValue = selectedUiPreset.title
        val descriptionValue = selectedUiPreset.description.take(MAX_DESCRIPTION_CHARACTERS)
        val appendDescriptionValue = selectedUiPreset.appendDescription.take(
            (MAX_DESCRIPTION_CHARACTERS - descriptionValue.length).coerceAtLeast(0),
        )
        val descriptionAlignmentValue = selectedUiPreset.descriptionAlignment
        val appendDescriptionColorValue = selectedUiPreset.appendDescriptionColor
        val labelValue = selectedUiPreset.repositoryText
        val urlValue = selectedUiPreset.repositoryUrl
        val sizeValue = selectedUiPreset.buttonSize
        val opacityValue = selectedUiPreset.buttonOpacity
        val dragVisibilityDurationValue = selectedUiPreset.dragVisibilityDuration
        val shapeValue = selectedUiPreset.buttonShape
        val positionValue = selectedUiPreset.buttonPosition
        val backgroundValue = selectedUiPreset.background
        val outlineValue = selectedUiPreset.outline
        val buttonTextColorValue = selectedUiPreset.buttonTextColor
        val buttonBackgroundValue = selectedUiPreset.buttonBackground
        val outlineWidthValue = selectedUiPreset.outlineWidth
        val iconOutlineColorValue = selectedUiPreset.iconOutlineColor
        val iconBackground2Value = selectedUiPreset.iconBackground2
        val iconGradientAngleValue = selectedUiPreset.iconGradientAngle
        val backgroundTransparencyValue = selectedUiPreset.backgroundTransparency
        val iconOutlineWidthValue = selectedUiPreset.iconOutlineWidth
        val iconStyleValue = if (resetIcon) "text" else selectedUiPreset.iconStyle
        val iconShapeValue = selectedUiPreset.iconShape
        val iconShapeColor1Value = selectedUiPreset.iconShapeColor1
        val iconShapeColor2Value = selectedUiPreset.iconShapeColor2
        val iconShapeGradientValue = selectedUiPreset.iconShapeGradient
        val iconShapeGradientAngleValue = selectedUiPreset.iconShapeGradientAngle
        val iconShapeStrokeWidthValue = selectedUiPreset.iconShapeStrokeWidth
        val iconShapeScaleValue = selectedUiPreset.iconShapeScale
        val iconHighlightValue = selectedUiPreset.iconHighlight
        val iconShadowValue = selectedUiPreset.iconShadow
        val iconOutlineGradientValue = selectedUiPreset.iconOutlineGradient
        val iconOutlineColor2Value = selectedUiPreset.iconOutlineColor2
        val iconOutlineGradientAngleValue = selectedUiPreset.iconOutlineGradientAngle
        val iconBackgroundStyleValue = selectedUiPreset.iconBackgroundStyle
        val iconBackgroundColor3Value = selectedUiPreset.iconBackgroundColor3
        val iconBackgroundColor4Value = selectedUiPreset.iconBackgroundColor4
        val iconPartsValue = if (resetIcon) emptyList() else selectedUiPreset.iconParts
        val customIconLocalSourceValue = if (resetIcon) "" else selectedUiPreset.customIconImageLocal
        val customIconStringSourceValue = if (resetIcon) "" else selectedUiPreset.customIconImageInput
        val iconTextSizeValue = selectedUiPreset.iconTextSize
        val iconTextFontValue = selectedUiPreset.iconTextFont
        val menuTextFontValue = selectedUiPreset.menuTextFont
        val legacyIconJsonValue = readLegacyIconJson(importLegacyIconJson.orEmpty().trim(), logger)
        val controlThemeValue = selectedUiPreset.controlTheme
        val controlBackgroundValue = selectedUiPreset.controlBackground
        val controlForegroundValue = selectedUiPreset.controlForeground
        val bottomButtonStyleValue = selectedUiPreset.bottomButtonStyle
        val bottomButtonShapeValue = selectedUiPreset.bottomButtonShape
        val bottomButtonPaddingValue = selectedUiPreset.bottomButtonPadding
        val bottomButtonTextColorValue = selectedUiPreset.bottomButtonTextColor
        val bottomButtonBackground1Value = selectedUiPreset.bottomButtonBackground1
        val bottomButtonBackground2Value = selectedUiPreset.bottomButtonBackground2
        val menuTextColor1Value = selectedUiPreset.menuTextColor1
        val overlayTextColorValue = menuTextColor1Value
        val menuTextColor2Value = selectedUiPreset.menuTextColor2
        val menuTextColor3Value = selectedUiPreset.menuTextColor3
        val menuTextColor4Value = selectedUiPreset.menuTextColor4
        val menuTextColor5Value = selectedUiPreset.menuTextColor5
        val menuTextColor6Value = selectedUiPreset.menuTextColor6
        val menuTextColor7Value = selectedUiPreset.menuTextColor7
        val separatorBackgroundColorValue = selectedUiPreset.separatorBackgroundColor
        val separatorStyleValue = selectedUiPreset.separatorStyle
        val titleIconPlacementValue = selectedUiPreset.titleIconPlacement
        val titleAlignmentValue = selectedUiPreset.titleAlignment
        val titleSeparatorValue = selectedUiPreset.titleSeparator
        val menuCornersValue = selectedUiPreset.menuCorners
        val menuOutlineAnimationValue = selectedUiPreset.menuOutlineAnimation
        val outlineAnimationSpeedValue = selectedUiPreset.outlineAnimationSpeed
        val openingAnimationValue = selectedUiPreset.openingAnimation
        val closingAnimationValue = selectedUiPreset.closingAnimation
        val animationDurationValue = selectedUiPreset.animationDuration
        val animationEasingValue = selectedUiPreset.animationEasing
        val resolvedLocalIconImage = resolveCustomIconImage(customIconLocalSourceValue, allowLocalPath = true, logger, "local")
        val resolvedStringIconImage = if (resolvedLocalIconImage.isBlank() && customIconStringSourceValue.isNotBlank()) {
            resolveCustomIconImage(customIconStringSourceValue, allowLocalPath = false, logger, "string")
        } else {
            ""
        }
        if (resolvedLocalIconImage.isNotBlank() && customIconStringSourceValue.isNotBlank()) {
            logger.info("Valid local custom overlay icon input takes priority over the String Handler input.")
        } else if (resolvedLocalIconImage.isBlank() && customIconLocalSourceValue.isNotBlank() && customIconStringSourceValue.isNotBlank()) {
            logger.warning("Local custom overlay icon input was invalid; trying the String Handler input.")
        }
        val resolvedCustomIconImage = resolvedLocalIconImage.ifBlank { resolvedStringIconImage }
        // Keep invalid non-empty input distinguishable from an intentionally blank field so the
        // runtime can use the legacy icon and show its one-time fallback notice.
        val customIconImageValue = if ((customIconLocalSourceValue.isNotBlank() || customIconStringSourceValue.isNotBlank()) && resolvedCustomIconImage.isBlank()) {
            "invalid"
        } else {
            resolvedCustomIconImage
        }
        val monitorPositionValue = statisticMonitorPosition.orEmpty().ifBlank { "bottom" }
        val monitorScaleValue = monitorScale.orEmpty().ifBlank { "1" }
        val monitorColumnsValue = monitorColumns.orEmpty().ifBlank { "2" }
        val temperatureFormatValue = temperatureFormat.orEmpty().ifBlank { "celsius" }
        val timeFormatValue = timeFormat.orEmpty().ifBlank { "12" }
        val activityInstallBanlistValue = activityInstallBanlist.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifBlank { "none" }
        validate(
            titleValue, descriptionValue, labelValue, urlValue,
            backgroundValue, outlineValue, overlayTextColorValue, buttonTextColorValue, buttonBackgroundValue,
            outlineWidthValue, iconOutlineColorValue, iconBackground2Value,
            iconGradientAngleValue, backgroundTransparencyValue, iconOutlineWidthValue,
            shapeValue, positionValue, sizeValue, opacityValue,
        )
        check(iconStyleValue in setOf("text", "parts"))
        check(iconTextFontValue in FONT_CHOICES.values)
        check(menuTextFontValue in FONT_CHOICES.values)
        check(iconShapeColor1Value.matches(Regex("#[0-9a-fA-F]{6}")))
        check(iconShapeColor2Value.matches(Regex("#[0-9a-fA-F]{6}")))
        check(iconOutlineColor2Value.matches(Regex("#[0-9a-fA-F]{6}")))
        check(iconBackgroundStyleValue in setOf("flat", "faceted"))
        check(iconBackgroundColor3Value.matches(Regex("#[0-9a-fA-F]{6}")))
        check(iconBackgroundColor4Value.matches(Regex("#[0-9a-fA-F]{6}")))
        check(iconShapeGradientAngleValue in 0..360)
        check(iconOutlineGradientAngleValue in 0..360)
        check(iconShapeStrokeWidthValue in 1..12)
        check(iconShapeScaleValue in 20..100)
        check(iconPartsValue.size <= 12)
        check(iconPartsValue.all {
            val fields = it.split('|')
            it.length <= 260 && fields.size in 12..15 && (fields.size < 14
                || fields[13].trim().equals("true", true)
                || fields[13].trim().equals("false", true)) && (fields.size < 15
                || fields[14].trim() in FONT_CHOICES.values)
        })
        check(monitorPositionValue in setOf("none", "top", "bottom"))
        check(monitorScaleValue.toFloatOrNull() in listOf(.75f, 1f, 1.25f, 1.5f, 2f))
        check(monitorColumnsValue in setOf("1", "2", "3"))
        check(temperatureFormatValue in setOf("celsius", "fahrenheit", "kelvin"))
        check(timeFormatValue in setOf("12", "24"))
        check(selectedUiPreset.buttonText.length <= 3)
        check(controlThemeValue in setOf("legacy", "modern", "monet"))
        check(bottomButtonStyleValue in setOf("text", "solid", "gradient"))
        check(bottomButtonShapeValue in setOf("square", "squircle"))
        check(separatorStyleValue in setOf("ascii", "doubleLine", "background", "singleLine", "inline"))
        check(titleIconPlacementValue in setOf("none", "left", "right", "both"))
        check(titleAlignmentValue in setOf("left", "center", "right"))
        check(menuCornersValue in setOf("rounded", "square"))
        check(menuOutlineAnimationValue in setOf("static", "gradient", "vertical", "rainbow"))
        check(openingAnimationValue in setOf("fade", "scale", "disabled", "appearRight", "appearTop", "appearBottom", "appearLeft"))
        check(closingAnimationValue in setOf("fade", "scale", "disabled", "disappearUp", "disappearDown", "disappearLeft", "disappearRight"))
        check(animationEasingValue in setOf("linear", "logarithmic"))
        check(outlineAnimationSpeedValue in -10..10)
        check(animationDurationValue >= 0)
        check(descriptionAlignmentValue in setOf("left", "center", "right"))
        check(appendDescriptionColorValue.matches(Regex("#[0-9a-fA-F]{6}")))
        check(menuTextColor6Value.matches(Regex("#[0-9a-fA-F]{6}")))
        check(menuTextColor7Value.matches(Regex("#[0-9a-fA-F]{6}")))
        check(separatorBackgroundColorValue.matches(Regex("#[0-9a-fA-F]{6}")))

        val config = OverlayConfigPayload.serialize(
            listOf(
            OverlayConfigPayload.VERSION, titleValue, descriptionValue, labelValue, urlValue,
            backgroundValue, outlineValue, selectedUiPreset.buttonText.ifBlank { "U" },
            buttonTextColorValue, buttonBackgroundValue, shapeValue,
            sizeValue.toString(), opacityValue.toString(), positionValue,
            listOf(
                if (includeSystemTime == true) "systemTime" else null,
                if (includeFps == true) "fps" else null,
                if (includeSessionTime == true) "sessionTime" else null,
                if (includeBatteryStatus == true) "batteryStatus" else null,
                if (includeAppMemory == true) "appMemory" else null,
                if (includeNetworkStatus == true) "networkStatus" else null,
                if (includeDeviceInformation == true) "deviceInformation" else null,
                if (includeDeviceTemperature == true) "deviceTemperature" else null,
                if (includeKeepAwake == true) "keep" else null,
                if (includeFullscreen == true) "fullscreen" else null,
                if (includeScreenshots == true) "screenshots" else null,
                if (includeAppBrightness == true) "appBrightness" else null,
                if (includeRotationMode == true) "rotationMode" else null,
                if (includeAppAudioMute == true) "appAudioMute" else null,
                if (includeDisableHaptics == true) "disableHaptics" else null,
                if (includeDisableAnimations == true) "disableAnimations" else null,
            ).filterNotNull().joinToString(","),
            if (activateStatisticsOnLaunch == true) "1" else "0",
            if (enableMonitorsOnLaunch == true) "1" else "0",
            monitorPositionValue,
            monitorScaleValue,
            monitorColumnsValue,
            temperatureFormatValue,
            timeFormatValue,
            outlineWidthValue.toString(),
            if (selectedUiPreset.iconOutline) "1" else "0",
            iconOutlineColorValue,
            if (selectedUiPreset.iconBold) "1" else "0",
            iconBackground2Value,
            iconGradientAngleValue.toString(),
            customIconImageValue,
            dragVisibilityDurationValue.toString(),
            if (selectedUiPreset.gradientBackground) "1" else "0",
            backgroundTransparencyValue.toString(),
            overlayTextColorValue,
            iconOutlineWidthValue.toString(),
            iconTextSizeValue.toString(),
            controlThemeValue,
            controlBackgroundValue,
            controlForegroundValue,
            bottomButtonStyleValue,
            bottomButtonShapeValue,
            if (bottomButtonPaddingValue) "1" else "0",
            bottomButtonTextColorValue,
            bottomButtonBackground1Value,
            bottomButtonBackground2Value,
            menuTextColor1Value,
            menuTextColor2Value,
            menuTextColor3Value,
            menuTextColor4Value,
            menuTextColor5Value,
            separatorStyleValue,
            titleIconPlacementValue,
            titleAlignmentValue,
            if (titleSeparatorValue) "1" else "0",
            menuCornersValue,
            menuOutlineAnimationValue,
            outlineAnimationSpeedValue.toString(),
            openingAnimationValue,
            animationDurationValue.toString(),
            animationEasingValue,
            appendDescriptionValue,
            descriptionAlignmentValue,
            appendDescriptionColorValue,
            if (showNoModulesWarning == true) "1" else "0",
            closingAnimationValue,
            menuTextColor6Value,
            separatorBackgroundColorValue,
            activityInstallBanlistValue,
            iconStyleValue,
            iconShapeValue,
            iconShapeColor1Value,
            iconShapeColor2Value,
            if (iconShapeGradientValue) "1" else "0",
            iconShapeGradientAngleValue.toString(),
            iconShapeStrokeWidthValue.toString(),
            iconShapeScaleValue.toString(),
            if (iconHighlightValue) "1" else "0",
            if (iconShadowValue) "1" else "0",
            if (iconOutlineGradientValue) "1" else "0",
            iconOutlineColor2Value,
            iconOutlineGradientAngleValue.toString(),
            iconBackgroundStyleValue,
            iconBackgroundColor3Value,
            iconBackgroundColor4Value,
            ),
            profileId = OverlayConfigPayload.UNIVERSAL_PROFILE,
            injectionMode = activityInjectionMode.orEmpty().ifBlank {
                OverlayConfigPayload.UNIVERSAL_INJECTION_MODE
            },
            trailingFields = listOf(
                iconPartsValue.joinToString("\n"), "", menuTextColor7Value,
                iconTextFontValue, menuTextFontValue, legacyIconJsonValue,
            ),
        )

        // Prefer the process Application entry point. The Activity path is a compatibility fallback
        // for APKs whose Application class or onCreate method cannot be resolved safely.
        val explicitActivityFirst = activityInjectionMode.orEmpty() == OverlayConfigPayload.EXPLICIT_ACTIVITY_INJECTION_MODE
        val appDescriptor = StartupHooks.resolvedApplicationDescriptor
        val appClass = appDescriptor?.let { mutableClassDefByOrNull(it) }
        val appMethod = appClass?.let { findInheritedApplicationOnCreate(it) }
        var bridgeInstalled = false
        var adsPolicyAttached = false
        val adsRuntimePolicy = OverlayAdsRuntimeIntegration.pendingPolicy()
        if (!explicitActivityFirst && appMethod != null) {
            val (appOwner, appOnCreate) = appMethod
            if (appOnCreate.implementation?.instructions?.any { it.toString().contains(RUNTIME_CLASS) } == true) {
                logger.info("Runtime overlay bridge already exists in ${appOwner.type}->onCreate")
                bridgeInstalled = true
            } else {
                injectOverlayBridge(this, appOwner, appOnCreate, config, application = true, adsRuntimePolicy = adsRuntimePolicy)
                logger.info("Runtime overlay bridge injected into ${appOwner.type}->onCreate")
                bridgeInstalled = true
                adsPolicyAttached = adsRuntimePolicy != null
            }
        }

        val fallback = if (bridgeInstalled) {
            null
        } else {
            selectedUiPreset.activityOverride.trim().takeIf { it.isNotEmpty() }?.let(::descriptor)
                ?.let { target -> mutableClassDefByOrNull(target) }
                ?: findOverlayFallbackActivity()
        }
        if (explicitActivityFirst && fallback == null) {
            logger.warning("Explicit Activity injection was requested but no target was found; universal fallback also failed.")
        }
        val onCreate = fallback?.methods?.firstOrNull {
            it.name == "onCreate" && it.returnType == "V" && it.parameterTypes == listOf("Landroid/os/Bundle;")
        }
        if (fallback != null && onCreate != null) {
            if (onCreate.implementation?.instructions?.any { it.toString().contains(RUNTIME_CLASS) } == true) {
                logger.info("Runtime overlay bridge already exists in ${fallback.type}->onCreate")
                bridgeInstalled = true
            } else {
                injectOverlayBridge(this, fallback, onCreate, config, application = false, adsRuntimePolicy = adsRuntimePolicy)
                logger.warning("Runtime overlay used Activity fallback: ${fallback.type}->onCreate")
                bridgeInstalled = true
                adsPolicyAttached = adsRuntimePolicy != null
            }
        } else if (!bridgeInstalled) {
            logger.warning("No suitable Application or Activity entry point found. No changes applied.")
        }
        if (adsPolicyAttached) {
            OverlayAdsRuntimeIntegration.markInjected("Universal Overlay")
            logger.info("Control App Ads runtime policy was injected beside the Universal Overlay bridge.")
        } else if (bridgeInstalled && adsRuntimePolicy != null) {
            logger.warning("Control App Ads runtime policy was not attached because this overlay bridge already existed.")
        }
        if (customMode) exportPreset(exportUiPreset.orEmpty().trim(), exportedUiPresetOutputName.orEmpty(), selectedUiPreset, logger)
    }
}

/**
 * Finds the implementation of Application.onCreate, including an implementation inherited by
 * the manifest-declared Application class. Mutating a bundled application superclass is safe here:
 * it is still the process Application entry point, whereas selecting an arbitrary Activity or SDK
 * class can leave the actual game screen without an overlay.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.findInheritedApplicationOnCreate(
    start: MutableClass,
): Pair<MutableClass, MutableMethod>? {
    val seen = mutableSetOf<String>()
    var current: MutableClass? = start
    while (current != null && seen.add(current.type)) {
        val method = current.methods.firstOrNull {
            it.name == "onCreate" && it.returnType == "V" && it.parameterTypes.isEmpty()
        }
        if (method != null) return current to method

        val superclass = current.superclass ?: return null
        if (superclass == "Landroid/app/Application;" || superclass == "Ljava/lang/Object;") return null
        current = mutableClassDefByOrNull(superclass)
    }
    return null
}
