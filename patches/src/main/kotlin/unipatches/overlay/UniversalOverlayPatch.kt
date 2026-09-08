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

private const val RUNTIME_CLASS = "Lunipatch/universaloverlay/UniversalOverlayRuntime;"
private const val CONFIG_VERSION = "1"
private const val PRESET_SCHEMA_VERSION = 5
private const val MAX_CUSTOM_ICON_BYTES = 1024 * 1024
private const val MAX_TITLE_CHARACTERS = 80
private const val MAX_DESCRIPTION_CHARACTERS = 500
private const val CUSTOM_SEPARATOR_BACKGROUND_DEFAULT = "#210000"
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

private fun encode(value: String): String =
    Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

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
            buttonTextColor = rgbColor("iconTextColor", fallback.buttonTextColor),
            gradientBackground = flag("gradientBackground", fallback.gradientBackground),
            buttonBackground = rgbColor("iconBackground1", fallback.buttonBackground),
            iconBackground2 = rgbColor("iconBackground2", fallback.iconBackground2),
            iconGradientAngle = number("iconGradientAngle", fallback.iconGradientAngle, 0..360),
            iconOutline = flag("iconOutline", fallback.iconOutline),
            iconOutlineWidth = number("iconOutlineWidth", fallback.iconOutlineWidth, 1..8),
            iconOutlineColor = rgbColor("iconOutlineColor", fallback.iconOutlineColor),
            iconStyle = choice("iconStyle", fallback.iconStyle, setOf("text", "shape", "multi")),
            iconShape = choice("iconShape", fallback.iconShape, setOf("triangle", "chevron", "smile", "circle", "z")),
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

private fun injectMethod(owner: MutableClass, method: MutableMethod, config: String, application: Boolean) {
    /*
     * Keep the injected bridge independent of the target method's register layout. cloneMutable
     * moves the original parameters into the expanded register frame, leaving the registers at
     * the old registerCount free for our temporaries. Copy the receiver into the first temporary
     * and place the configuration immediately after it so the bridge can use invoke-static/range.
     *
     * An ordinary invoke-static has a five-register, four-bit register-list encoding. It can
     * therefore fail when a Unity or other game Activity has a large register frame and the new
     * temporary register is above v15. The range form is valid in the supported Morphe inline
     * compiler and addresses the high registers safely.
     */
    val temporaryBase = method.implementation?.registerCount
        ?: error("Cannot inject into ${owner.type}->${method.name} without an implementation")
    // cloneMutable shifts parameters upward by the number of added registers. Reserve those
    // parameter slots first, then reserve two registers for the receiver and configuration so
    // neither temporary can alias p0/p1 after cloning.
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 2)
    val originalReceiver = cloned.p0Register
    // The runtime API accepts the platform base type. The injected receiver may be any concrete
    // Activity subclass; using owner.type here would generate a method descriptor that does not
    // exist in UniversalOverlayRuntime and fail with NoSuchMethodError at launch.
    val type = if (application) "Landroid/app/Application;" else "Landroid/app/Activity;"
    val injectionIndex = if (application) {
        0
    } else {
        // Activity views cannot be attached reliably until the framework superclass has completed
        // onCreate. Place the fallback bridge after invoke-super so it works with AppCompat,
        // Unity, Godot, and ordinary platform Activity subclasses.
        val instructions = cloned.implementation?.instructions
        val superIndex = instructions?.indexOfFirst {
            val text = it.toString()
            text.contains("invoke-super") && text.contains("->onCreate(")
        } ?: -1
        if (superIndex >= 0) {
            superIndex + 1
        } else {
            // A non-standard Activity may omit invoke-super. Run at the end of onCreate so the
            // host still has a chance to initialize its content before overlay attachment.
            // Use the final existing instruction rather than relying on return-void text: some
            // dex instruction proxy implementations do not expose that text consistently.
            maxOf(0, (instructions?.size ?: 0) - 1)
        }
    }
    // Use the label-aware compiler entry point. Morphe Manager versions in the wild have
    // rejected range instructions through addInstructions even though the same Smali is valid
    // when compiled through addInstructionsWithLabels.
    cloned.addInstructionsWithLabels(
        injectionIndex,
        """
        move-object/from16 v$temporaryBase, v$originalReceiver
        const-string v${temporaryBase + 1}, "${StartupHooks.escapeSmali(config)}"
        invoke-static/range {v$temporaryBase .. v${temporaryBase + 1}}, $RUNTIME_CLASS->${if (application) "install" else "installActivity"}(${type}Ljava/lang/String;)V
        """.trimIndent(),
    )
    owner.methods.remove(method)
    owner.methods.add(cloned)
}

@Suppress("unused")
val universalOverlayPatch = bytecodePatch(
    name = "UniPatches Universal Overlay Patch v1.4.1 (Experimental)",
    description = """
        Universal in-app overlay for Android apps and games. Optional modules include System Time, FPS,
        fullscreen, app brightness, and haptic controls. Modules are excluded and disabled by default;
        select them in Morphe settings before patching. Statistic modules show information, Activity modules
        control the current Activity, and Hook modules control internal app behavior, such as disabling
        animations, through best-effort runtime changes. The legacy icon can be text, an original
        geometric shape, or a multi-part shape with configurable colors, gradients, highlights, shadows,
        and outlines. A selected local image automatically replaces the legacy icon; empty or invalid
        image input falls back to the configured legacy icon. This is experimental
        and may not work on all apps. UI presets can save and reuse supported UI and Advanced
        settings. The title, description, repository button text, and repository button URL remain
        controlled by the visible Morphe settings. Modules and Settings to Modules are excluded
        because hook and module combinations can be app-specific.

        The idea and initial works of this Universal Overlay Patch are from Zanuaimi / Noobite.
    """.trimIndent(),
    default = false,
) {
    // Keep the extension DEX as the runtime boundary; generated Smali should only start it and
    // must not contain overlay UI or feature implementation.
    extendWith("extensions/extension.mpe")
    dependsOn(StartupHooks.resolveRealApplicationPatch)

    val selectedPreset by stringOption(
        title = "Preset > Select > Preset",
        default = "custom",
        key = "runtimeOverlaySelectedPreset",
        description = "Choose Custom to use the visible settings. UniPatches, Morphe-inspired, Dark, Light, ZArchiver-inspired, LuckyPatcher-inspired, and ReVanced-inspired presets replace supported UI settings. Title, description, repository button text, and repository button URL always remain from the visible Morphe settings. Presets never change Modules or Settings to Modules.",
        values = linkedMapOf("Custom" to "custom").apply {
            OverlayPresetCatalog.definitions.forEach { put(it.displayName, it.id) }
        },
    )
    val importUiPreset by filePathOption(
        title = "Preset > Import > UI preset",
        default = "",
        key = "runtimeOverlayImportUiPreset",
        allowedExtensions = listOf("json"),
        description = "Optional path to a Universal Overlay .json preset. Only Custom mode uses it. A valid JSON preset overrides the visible UI settings during patching; an empty, unreadable, malformed, or unsupported file falls back to the visible Morphe settings. The Manager controls do not change visually.",
    )
    val exportUiPreset by stringOption(
        title = "Preset > Export > UI preset",
        default = "",
        key = "runtimeOverlayExportUiPreset",
        description = "Optional existing folder where Custom mode exports the final UI preset as JSON after patching. Leave empty to disable. Export errors are logged and never cancel APK patching. Protected system/root locations are rejected.",
    )
    val exportedUiPresetOutputName by stringOption(
        title = "Preset > Export > Output name",
        default = "UniversalOverlay.json",
        key = "runtimeOverlayExportedUiPresetOutputName",
        description = "Output name for exported JSON. Defaults to UniversalOverlay.json; .json is added automatically and duplicate names receive -1, -2, and so on.",
    )

    val controlTheme by stringOption(
        title = "UI > Controls > Theme",
        default = "modern",
        key = "runtimeOverlayControlTheme",
        description = "Theme used by buttons, sliders, checkboxes, and dropdowns.",
        values = linkedMapOf("Legacy" to "legacy", "Modern (default)" to "modern", "Monet-style" to "monet"),
    )
    val controlBackground by stringOption(
        title = "UI > Controls > Background color",
        default = "#300000",
        key = "runtimeOverlayControlBackground",
        description = "Background color for overlay controls.",
    )
    val controlForeground by stringOption(
        title = "UI > Controls > Foreground color",
        default = "#FF5656",
        key = "runtimeOverlayControlForeground",
        description = "Foreground color for control contents, such as text, slider progress, and checked checkbox state.",
    )
    val bottomButtonStyle by stringOption(
        title = "UI > Controls > Bottom action button style",
        default = "solid",
        key = "runtimeOverlayBottomButtonStyle",
        description = "Style of the repository, close-menu, and fully-close buttons.",
        values = linkedMapOf("Text only (default)" to "text", "Solid background" to "solid", "Gradient background" to "gradient"),
    )
    val bottomButtonShape by stringOption(
        title = "UI > Controls > Bottom action button shape",
        default = "square",
        key = "runtimeOverlayBottomButtonShape",
        description = "Shape of bottom action button backgrounds.",
        values = linkedMapOf("Full square" to "square", "Squircle" to "squircle"),
    )
    val bottomButtonPadding by booleanOption(
        title = "UI > Controls > Bottom action button padding",
        default = false,
        key = "runtimeOverlayBottomButtonPadding",
        description = "Add padding between the three bottom action buttons.",
    )
    val bottomButtonTextColor by stringOption(
        title = "UI > Controls > Bottom action button text color",
        default = "#300000",
        key = "runtimeOverlayBottomButtonTextColor",
        description = "Text color of the three bottom action buttons.",
    )
    val bottomButtonBackground1 by stringOption(
        title = "UI > Controls > Bottom action button background 1",
        default = "#FF5656",
        key = "runtimeOverlayBottomButtonBackground1",
        description = "First background color for solid or gradient bottom action buttons.",
    )
    val bottomButtonBackground2 by stringOption(
        title = "UI > Controls > Bottom action button background 2",
        default = "#FF5656",
        key = "runtimeOverlayBottomButtonBackground2",
        description = "Second background color for gradient bottom action buttons.",
    )
    val menuTextColor1 by stringOption(title = "UI > Menu > Text color 1 (title and lines)", default = "#FF5656", key = "runtimeOverlayMenuTextColor1", description = "Title and title/separator line color.")
    val menuTextColor2 by stringOption(title = "UI > Menu > Text color 2 (module names)", default = "#FF5656", key = "runtimeOverlayMenuTextColor2", description = "Module name color.")
    val menuTextColor3 by stringOption(title = "UI > Menu > Text color 3 (descriptions)", default = "#FF5656", key = "runtimeOverlayMenuTextColor3", description = "Overlay and module description color.")
    val menuTextColor4 by stringOption(title = "UI > Menu > Text color 4 (monitor)", default = "#FF5656", key = "runtimeOverlayMenuTextColor4", description = "Monitor label color.")
    val menuTextColor5 by stringOption(title = "UI > Menu > Text color 5 (active)", default = "#FF5656", key = "runtimeOverlayMenuTextColor5", description = "Active label color.")
    val menuTextColor6 by stringOption(title = "UI > Menu > Text color 6 (module separators)", default = "#FF5656", key = "runtimeOverlayMenuTextColor6", description = "Module separator text color.")
    val separatorBackgroundColor by stringOption(title = "UI > Menu > Separator background color", default = CUSTOM_SEPARATOR_BACKGROUND_DEFAULT, key = "runtimeOverlaySeparatorBackgroundColor", description = "Background color used by the module separator when Background behind text is selected. Custom preset defaults to a darker shade than the overlay background.")
    val separatorStyle by stringOption(
        title = "UI > Menu > Module separator style",
        default = "ascii",
        key = "runtimeOverlaySeparatorStyle",
        description = "How module type separators and hints appear.",
        values = linkedMapOf("--- Module type ---" to "ascii", "Lines above and below" to "doubleLine", "Background behind text" to "background", "Line below" to "singleLine", "Inline after module name" to "inline"),
    )
    val titleIconPlacement by stringOption(
        title = "UI > Menu > Title icon placement",
        default = "none",
        key = "runtimeOverlayTitleIconPlacement",
        description = "Show a non-clickable copy of the overlay icon beside the menu title.",
        values = linkedMapOf("No icons (default)" to "none", "Top left" to "left", "Top right" to "right", "Both sides" to "both"),
    )
    val titleAlignment by stringOption(
        title = "UI > Menu > Title alignment",
        default = "left",
        key = "runtimeOverlayTitleAlignment",
        description = "Alignment of the overlay menu title.",
        values = linkedMapOf("Left" to "left", "Center" to "center", "Right" to "right"),
    )
    val titleSeparator by booleanOption(title = "UI > Menu > Title separator", default = false, key = "runtimeOverlayTitleSeparator", description = "Show a Color 1 line below the title.")
    val menuCorners by stringOption(title = "UI > Menu > Corners", default = "rounded", key = "runtimeOverlayMenuCorners", description = "Shape of the overlay menu corners.", values = linkedMapOf("Rounded (default)" to "rounded", "Square" to "square"))
    val menuOutlineAnimation by stringOption(title = "UI > Menu > Outline animation", default = "static", key = "runtimeOverlayMenuOutlineAnimation", description = "Animation style for the overlay menu outline. Horizontal scrolling moves the gradient sideways; Vertical gradient moves it vertically.", values = linkedMapOf("Static (default)" to "static", "Horizontal scrolling gradient" to "gradient", "Vertical gradient" to "vertical", "Rainbow gradient" to "rainbow"))
    val outlineAnimationSpeed by intOption(title = "UI > Menu > Outline gradient animation speed", default = 1, key = "runtimeOverlayOutlineAnimationSpeed", description = "Animation speed from -10 to 10. Zero disables movement. For Vertical gradient, positive values move upward and negative values move downward; for example, 3 is faster upward motion and -2 is slower downward motion.")
    val openingAnimation by stringOption(
        title = "UI > Menu > Opening animation",
        default = "fade",
        key = "runtimeOverlayOpeningAnimation",
        description = "Opening animation. Directional appearances also fade in the menu.",
        values = linkedMapOf("Fade (default)" to "fade", "Appear from right" to "appearRight", "Appear from top" to "appearTop", "Appear from bottom" to "appearBottom", "Appear from left" to "appearLeft", "Scale" to "scale", "Disabled" to "disabled"),
    )
    val closingAnimation by stringOption(
        title = "UI > Menu > Closing animation",
        default = "fade",
        key = "runtimeOverlayClosingAnimation",
        description = "Closing animation. Directional disappearances also fade out the menu.",
        values = linkedMapOf("Fade (default)" to "fade", "Disappear upwards" to "disappearUp", "Disappear downwards" to "disappearDown", "Disappear leftwards" to "disappearLeft", "Disappear rightwards" to "disappearRight", "Scale" to "scale", "Disabled" to "disabled"),
    )
    val animationDuration by intOption(title = "UI > Menu > Animation duration (ms)", default = 180, key = "runtimeOverlayAnimationDuration", description = "Duration used by both opening and closing animations. Values below 0 are clamped to 0.")
    val animationEasing by stringOption(title = "UI > Menu > Animation graph", default = "linear", key = "runtimeOverlayAnimationEasing", description = "Easing used by fade, directional, and scale menu animations.", values = linkedMapOf("Linear (default)" to "linear", "Logarithmic" to "logarithmic"))
    val outlineWidth by intOption(
        title = "UI > Menu > Outline width (dp)",
        default = 2,
        key = "runtimeOverlayOutlineWidthDp",
        description = "Width of the menu, monitor, and confirmation outlines, from 1 to 8dp.",
    )

    val title by stringOption(
        title = "General > Overlay > Title",
        default = "UniPatches Universal Overlay Patch",
        key = "runtimeOverlayTitle",
        description = "Title shown in the overlay menu. Limited to 80 characters.",
    )
    val descriptionText by stringOption(
        title = "General > Overlay > Description",
        default = DEFAULT_DESCRIPTION,
        key = "runtimeOverlayDescription",
        description = "Description below the title. Limited to 500 characters.",
    )
    val appendDescriptionText by stringOption(
        title = "General > Overlay > Appended description",
        default = "",
        key = "runtimeOverlayAppendDescription",
        description = "Optional text appended below the overlay description. Useful for short inspiration or attribution notes such as Inspired by Example. The main and appended descriptions share a combined 500-character limit.",
    )
    val descriptionAlignment by stringOption(
        title = "General > Overlay > Description alignment",
        default = "center",
        key = "runtimeOverlayDescriptionAlignment",
        description = "Alignment of the main and appended overlay description text.",
        values = linkedMapOf("Left" to "left", "Center (default)" to "center", "Right" to "right"),
    )
    val appendDescriptionColor by stringOption(
        title = "General > Overlay > Appended description color",
        default = "#FF5656",
        key = "runtimeOverlayAppendDescriptionColor",
        description = "Color of appended overlay-description text. Presets default to menu text color 3.",
    )
    val backgroundColor by stringOption(
        title = "General > Overlay > Background color",
        default = "#300000",
        key = "runtimeOverlayBackgroundColor",
        description = "Overlay background color as #RRGGBB. Transparency is controlled separately.",
    )
    val backgroundTransparency by intOption(
        title = "General > Overlay > Background transparency (%)",
        default = 80,
        key = "runtimeOverlayBackgroundTransparency",
        description = "Transparency of the overlay background from 0% to 100%. 80% matches the default Morphe-style background alpha.",
    )
    val outlineColor by stringOption(
        title = "General > Overlay > Outline color",
        default = "#FF5656",
        key = "runtimeOverlayOutlineColor",
        description = "Overlay outline color as #RRGGBB.",
    )
    val repositoryText by stringOption(
        title = "General > Repository button > Text",
        default = "UniPatches repository",
        key = "runtimeOverlayRepositoryText",
        description = "Text of the always-present repository button.",
    )
    val repositoryUrl by stringOption(
        title = "General > Repository button > URL",
        default = "https://github.com/Zanuaimi/UniPatches",
        key = "runtimeOverlayRepositoryUrl",
        description = "URL opened by the repository button.",
    )
    val buttonText by stringOption(
        title = "UI > Icon > Legacy text",
        default = "U",
        key = "runtimeOverlayButtonText",
        description = "Text shown by the legacy icon. Maximum three characters.",
    )
    val iconBold by booleanOption(
        title = "UI > Icon > Legacy bold text",
        default = true,
        key = "runtimeOverlayIconBold",
        description = "Use bold text in the legacy icon. Enabled by default.",
    )
    val buttonTextColor by stringOption(
        title = "UI > Icon > Legacy text color",
        default = "#FFFFFF",
        key = "runtimeOverlayButtonTextColor",
        description = "Text color used by the legacy icon.",
    )
    val iconTextSize by intOption(
        title = "UI > Icon > Legacy text size (sp)",
        default = 18,
        key = "runtimeOverlayIconTextSizeSp",
        description = "Text size of the legacy icon in scaled pixels, from 8 to 48sp. The default is slightly larger than the pre-v1.2 fixed size.",
    )
    val iconStyle by stringOption(
        title = "UI > Icon > Icon part type",
        default = "text",
        key = "runtimeOverlayIconStyle",
        description = "Choose how the legacy icon is built. Text icon is one text part, for example U or RV. Shape icon is one geometric part, for example a Triangle. Multi-part icon uses a built-in combination of parts, currently the Smile example made from two eyes and a mouth; choose Shape = Smile, then adjust size, stroke, highlight, and shadow. Arbitrary custom part lists are not hidden in this setting and are not supported yet.",
        values = linkedMapOf("Text icon (default)" to "text", "Shape icon" to "shape", "Multi-part icon" to "multi"),
    )
    val iconShape by stringOption(
        title = "UI > Icon > Shape",
        default = "triangle",
        key = "runtimeOverlayIconShape",
        description = "Shape used by Shape and Multi-part icon styles. For a single-part icon, choose Triangle, Chevron, Circle, or Z mark. For the built-in multi-part example, choose Smile: it draws two eyes and a curved mouth as one icon. Colors, size, stroke, highlight, and shadow apply to the selected parts.",
        values = linkedMapOf("Triangle" to "triangle", "Chevron" to "chevron", "Smile" to "smile", "Circle" to "circle", "Z mark" to "z"),
    )
    val iconShapeColor1 by stringOption(
        title = "UI > Icon > Shape color 1",
        default = "#FFFFFF",
        key = "runtimeOverlayIconShapeColor1",
        description = "Primary color of the selected icon shape. Use #RRGGBB, for example #E651A0.",
    )
    val iconShapeColor2 by stringOption(
        title = "UI > Icon > Shape color 2",
        default = "#FFFFFF",
        key = "runtimeOverlayIconShapeColor2",
        description = "Second shape color when Shape gradient is enabled. Use #RRGGBB, for example #6564D3.",
    )
    val iconShapeGradient by booleanOption(
        title = "UI > Icon > Shape gradient",
        default = false,
        key = "runtimeOverlayIconShapeGradient",
        description = "Blend Shape color 1 into Shape color 2 inside the symbol. Disable it for a solid shape color.",
    )
    val iconShapeGradientAngle by intOption(
        title = "UI > Icon > Shape gradient angle (degrees)",
        default = 0,
        key = "runtimeOverlayIconShapeGradientAngle",
        description = "Direction of the shape gradient. 0 degrees runs top to bottom and 90 degrees runs left to right.",
    )
    val iconShapeStrokeWidth by intOption(
        title = "UI > Icon > Shape stroke width (dp)",
        default = 3,
        key = "runtimeOverlayIconShapeStrokeWidthDp",
        description = "Thickness of line-based shapes such as Chevron, Smile, and Circle. Use 1 to 12dp.",
    )
    val iconShapeScale by intOption(
        title = "UI > Icon > Shape size (%)",
        default = 70,
        key = "runtimeOverlayIconShapeScalePercent",
        description = "Size of the drawn symbol inside the button, from 20% to 100%. Leave room for the outline and background to stay visible.",
    )
    val iconHighlight by booleanOption(
        title = "UI > Icon > Shape highlight",
        default = false,
        key = "runtimeOverlayIconHighlight",
        description = "Add a subtle glossy highlight to the upper-left of the icon. This affects Shape and Multi-part styles.",
    )
    val iconShadow by booleanOption(
        title = "UI > Icon > Shape shadow",
        default = false,
        key = "runtimeOverlayIconShadow",
        description = "Add a small dark shadow behind the drawn shape to improve contrast on bright backgrounds.",
    )
    val gradientBackground by booleanOption(
        title = "UI > Icon > Gradient background",
        default = true,
        key = "runtimeOverlayIconGradientBackground",
        description = "Blend legacy icon background 1 into background 2. When disabled, only background 1 is used.",
    )
    val buttonBackgroundColor by stringOption(
        title = "UI > Icon > Background color 1",
        default = "#500000",
        key = "runtimeOverlayButtonBackgroundColor",
        description = "First color of the legacy icon gradient.",
    )
    val iconBackground2 by stringOption(
        title = "UI > Icon > Background color 2",
        default = "#AA0000",
        key = "runtimeOverlayIconBackgroundColor2",
        description = "Second color of the legacy icon gradient as #RRGGBB.",
    )
    val iconGradientAngle by intOption(
        title = "UI > Icon > Gradient angle (degrees)",
        default = 0,
        key = "runtimeOverlayIconGradientAngle",
        description = "Gradient direction: 0 degrees runs top to bottom and 90 runs left to right. Values wrap through 360 degrees.",
    )
    val iconBackgroundStyle by stringOption(
        title = "UI > Icon > Background style",
        default = "flat",
        key = "runtimeOverlayIconBackgroundStyle",
        description = "Choose a flat legacy-icon background or a faceted background made from angular color layers. Faceted mode is useful for geometric icons such as the Z mark.",
        values = linkedMapOf("Flat (default)" to "flat", "Faceted layers" to "faceted"),
    )
    val iconBackgroundColor3 by stringOption(
        title = "UI > Icon > Background color 3",
        default = "#3D7806",
        key = "runtimeOverlayIconBackgroundColor3",
        description = "Third color used by Faceted layers. It is ignored when Background style is Flat. Use #RRGGBB.",
    )
    val iconBackgroundColor4 by stringOption(
        title = "UI > Icon > Background color 4",
        default = "#4F9905",
        key = "runtimeOverlayIconBackgroundColor4",
        description = "Fourth color used by Faceted layers. It is ignored when Background style is Flat. Use #RRGGBB.",
    )
    val iconOutline by booleanOption(
        title = "UI > Icon > Outline",
        default = false,
        key = "runtimeOverlayIconOutline",
        description = "Add a separate outline around the legacy text icon. Disabled by default.",
    )
    val iconOutlineWidth by intOption(
        title = "UI > Icon > Outline width (dp)",
        default = 3,
        key = "runtimeOverlayIconOutlineWidthDp",
        description = "Width of the legacy icon outline from 1 to 8dp. This is independent from the overlay menu outline width.",
    )
    val iconOutlineColor by stringOption(
        title = "UI > Icon > Outline color",
        default = "#FFFFFF",
        key = "runtimeOverlayIconOutlineColor",
        description = "Color used only when the icon outline is enabled.",
    )
    val iconOutlineGradient by booleanOption(
        title = "UI > Icon > Outline gradient",
        default = false,
        key = "runtimeOverlayIconOutlineGradient",
        description = "Blend two colors around the legacy icon outline. This can create a moving-looking pink-to-blue ring when combined with a large outline width.",
    )
    val iconOutlineColor2 by stringOption(
        title = "UI > Icon > Outline color 2",
        default = "#FFFFFF",
        key = "runtimeOverlayIconOutlineColor2",
        description = "Second outline color used when Outline gradient is enabled. Use #RRGGBB.",
    )
    val iconOutlineGradientAngle by intOption(
        title = "UI > Icon > Outline gradient angle (degrees)",
        default = 0,
        key = "runtimeOverlayIconOutlineGradientAngle",
        description = "Direction of the icon outline gradient. 0 degrees runs top to bottom and 90 degrees runs left to right.",
    )
    val customIconImage by imageOption(
        title = "UI > Icon > Custom button icon (local image)",
        default = "",
        key = "runtimeOverlayCustomIconImage",
        allowedExtensions = listOf("png", "jpg", "jpeg", "webp"),
        recommendedSize = app.morphe.patcher.patch.ImageSize(128, 128),
        description = "Select a local PNG, JPG, JPEG, or WebP image file. This input has priority over the String Handler input when valid. Images are embedded during patching and scaled proportionally. Leave blank or use an invalid file to try the String Handler input, then fall back to the legacy icon.",
    )
    val customIconImageInput by stringOption(
        title = "UI > Icon > Custom button icon (String Handler)",
        default = "",
        key = "runtimeOverlayCustomIconImageInput",
        description = "Optional non-local image input. Valid examples: <base64 string here>, data:image/png;base64,<base64 string here>, or an HTTPS image URL. For image-to-Base64 conversion, use https://base64.guru/converter/encode/image. Used only when the Local Image input is empty or invalid; invalid input falls back to the legacy icon.",
    )
    val buttonShape by stringOption(
        title = "UI > Button > Shape",
        default = "circle",
        key = "runtimeOverlayButtonShape",
        description = "Shape of the legacy text icon background.",
        values = linkedMapOf("Circle" to "circle", "Squircle" to "squircle", "Square" to "square"),
    )
    val buttonSizeDp by intOption(
        title = "UI > Button > Size (dp)",
        default = 56,
        key = "runtimeOverlayButtonSizeDp",
        description = "Button size in density-independent pixels.",
    )
    val buttonOpacity by intOption(
        title = "UI > Button > Idle opacity (%)",
        default = 50,
        key = "runtimeOverlayButtonIdleOpacityPercent",
        description = "Idle opacity from 10 to 100 percent. Higher values make the button less transparent.",
    )
    val buttonDragVisibilityDurationSeconds by intOption(
        title = "UI > Button > Fully visible duration (seconds)",
        default = 2,
        key = "runtimeOverlayButtonDragVisibilityDurationSeconds",
        description = "How long the overlay button stays fully visible after dragging before fading to its idle opacity. The timer resets while dragging and starts again when the finger is released. Use a value from 1 to 10 seconds.",
    )
    val buttonPosition by stringOption(
        title = "UI > Button > Position",
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
        title = "Advanced > Activity name override",
        default = "",
        key = "runtimeOverlayActivityNameOverride",
        description = "Optional fallback Activity class used only when Application startup cannot be found. Leave blank for universal automatic discovery. Example: com.example.MainActivity or Lcom/example/MainActivity;.",
    )
    val activityInstallBanlist by stringsOption(
        title = "Advanced > Activity > Overlay install banlist",
        default = DEFAULT_ACTIVITY_INSTALL_BANLIST.lines(),
        key = "runtimeOverlayActivityInstallBanlist",
        description = "List of Activity class or package prefixes that must not receive the overlay. Add one entry per row; entries ending in * match a prefix. The default list protects common store, billing, and sign-in popups. Replace the list with a single entry none to disable it.",
    )
    val activateStatisticsOnLaunch by booleanOption(
        title = "Modules > Settings > Activate statistic modules on launch",
        default = false,
        key = "runtimeOverlayActivateStatisticsOnLaunch",
        description = "Start selected statistic modules as soon as the app launches. Active is disabled by default.",
    )
    val showNoModulesWarning by booleanOption(
        title = "Modules > Settings > Show no runtime modules warning",
        default = true,
        key = "runtimeOverlayShowNoModulesWarning",
        description = "Show a message in the overlay when no Statistic, Activity, or Hook modules are selected.",
    )
    val enableMonitorsOnLaunch by booleanOption(
        title = "Modules > Settings > Enable monitors for statistic modules on launch",
        default = false,
        key = "runtimeOverlayEnableMonitorsOnLaunch",
        description = "Show selected statistic monitors as soon as the app launches. Monitor is disabled by default.",
    )
    val statisticMonitorPosition by stringOption(
        title = "Modules > Settings > Statistic monitor position",
        default = "bottom",
        key = "runtimeOverlayStatisticMonitorPosition",
        description = "Show enabled statistic monitors from statistic modules above or below the overlay button.",
        values = linkedMapOf("No stat monitors" to "none", "Above overlay button" to "top", "Below overlay button" to "bottom"),
    )
    val monitorScale by stringOption(
        title = "Modules > Settings > Monitor panel size",
        default = "1",
        key = "runtimeOverlayMonitorScale",
        description = "Size multiplier for statistic monitor panels.",
        values = linkedMapOf("0.75x" to "0.75", "1x" to "1", "1.25x" to "1.25", "1.5x" to "1.5", "2x" to "2"),
    )
    val monitorColumns by stringOption(
        title = "Modules > Settings > Monitor columns",
        default = "2",
        key = "runtimeOverlayMonitorColumns",
        description = "Number of statistic monitor columns.",
        values = linkedMapOf("1 column" to "1", "2 columns" to "2", "3 columns" to "3"),
    )
    val temperatureFormat by stringOption(
        title = "Modules > Settings > Temperature stat format",
        default = "celsius",
        key = "runtimeOverlayTemperatureFormat",
        description = "Temperature unit used by the Device Temperature menu value and monitor.",
        values = linkedMapOf("Celsius" to "celsius", "Fahrenheit" to "fahrenheit", "Kelvin" to "kelvin"),
    )
    val timeFormat by stringOption(
        title = "Modules > Settings > System time format",
        default = "12",
        key = "runtimeOverlayTimeFormat",
        description = "Clock format used by the System Time menu value and monitor. Timezone is shown in the menu value.",
        values = linkedMapOf("12-hour clock" to "12", "24-hour clock" to "24"),
    )
    val includeDeviceInformation by booleanOption(
        title = "Modules > Statistic > Device Information",
        default = false,
        key = "runtimeOverlayIncludeDeviceInformation",
        description = "Include read-only phone and Android device information.",
    )
    val includeFps by booleanOption(
        title = "Modules > Statistic > FPS",
        default = false,
        key = "runtimeOverlayIncludeFps",
        description = "Include the approximate display frame-rate statistic module.",
    )
    val includeDeviceTemperature by booleanOption(
        title = "Modules > Statistic > Device Temperature",
        default = false,
        key = "runtimeOverlayIncludeDeviceTemperature",
        description = "Include battery-reported temperature in the selected temperature format.",
    )
    val includeSystemTime by booleanOption(
        title = "Modules > Statistic > System Time",
        default = false,
        key = "runtimeOverlayIncludeSystemTime",
        description = "Include the phone system time statistic module.",
    )
    val includeSessionTime by booleanOption(
        title = "Modules > Statistic > App Session Time",
        default = false,
        key = "runtimeOverlayIncludeSessionTime",
        description = "Include the in-process overlay session timer statistic module.",
    )
    val includeBatteryStatus by booleanOption(
        title = "Modules > Statistic > Battery Status",
        default = false,
        key = "runtimeOverlayIncludeBatteryStatus",
        description = "Include the current battery percentage statistic module.",
    )
    val includeAppMemory by booleanOption(
        title = "Modules > Statistic > App Memory Usage",
        default = false,
        key = "runtimeOverlayIncludeAppMemory",
        description = "Include approximate memory used by the current app process.",
    )
    val includeNetworkStatus by booleanOption(
        title = "Modules > Statistic > Network Status",
        default = false,
        key = "runtimeOverlayIncludeNetworkStatus",
        description = "Include incoming and outgoing app network traffic monitors.",
    )
    val includeKeepAwake by booleanOption(
        title = "Modules > Activity > Keep screen awake",
        default = false,
        key = "runtimeOverlayIncludeKeepScreenAwake",
        description = "Include the keep-screen-awake activity module.",
    )
    val includeFullscreen by booleanOption(
        title = "Modules > Activity > Fullscreen",
        default = false,
        key = "runtimeOverlayIncludeFullscreen",
        description = "Include the fullscreen activity module.",
    )
    val includeScreenshots by booleanOption(
        title = "Modules > Activity > Allow screenshots",
        default = false,
        key = "runtimeOverlayIncludeScreenshots",
        description = "Include the allow-screenshots activity module.",
    )
    val includeAppBrightness by booleanOption(
        title = "Modules > Activity > App brightness",
        default = false,
        key = "runtimeOverlayIncludeAppBrightness",
        description = "Include a per-Activity brightness slider.",
    )
    val includeRotationMode by booleanOption(
        title = "Modules > Activity > Rotation mode",
        default = false,
        key = "runtimeOverlayIncludeRotationMode",
        description = "Include a per-Activity rotation mode selector.",
    )
    val includeAppAudioMute by booleanOption(
        title = "Modules > Activity > App audio mute",
        default = false,
        key = "runtimeOverlayIncludeAppAudioMute",
        description = "Include a best-effort app audio mute toggle.",
    )
    val includeDisableHaptics by booleanOption(
        title = "Modules > Hook > Disable haptic feedback / vibrations",
        default = false,
        key = "runtimeOverlayIncludeDisableHaptics",
        description = "Include a best-effort runtime haptic and vibration suppression module.",
    )
    val includeDisableAnimations by booleanOption(
        title = "Modules > Hook > Disable app animations",
        default = false,
        key = "runtimeOverlayIncludeDisableAnimations",
        description = "Include a best-effort runtime animation suppression module.",
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
            iconStyle = iconStyle.orEmpty().ifBlank { "text" },
            iconShape = iconShape.orEmpty().ifBlank { "triangle" },
            iconShapeColor1 = iconShapeColor1.orEmpty().ifBlank { "#FFFFFF" },
            iconShapeColor2 = iconShapeColor2.orEmpty().ifBlank { iconShapeColor1.orEmpty().ifBlank { "#FFFFFF" } },
            iconShapeGradient = iconShapeGradient == true,
            iconShapeGradientAngle = ((iconShapeGradientAngle ?: 0) % 361 + 361) % 361,
            iconShapeStrokeWidth = (iconShapeStrokeWidth ?: 3).coerceIn(1, 12),
            iconShapeScale = (iconShapeScale ?: 70).coerceIn(20, 100),
            iconHighlight = iconHighlight == true,
            iconShadow = iconShadow == true,
            iconOutlineGradient = iconOutlineGradient == true,
            iconOutlineColor2 = iconOutlineColor2.orEmpty().ifBlank { "#FFFFFF" },
            iconOutlineGradientAngle = ((iconOutlineGradientAngle ?: 0) % 361 + 361) % 361,
            iconBackgroundStyle = iconBackgroundStyle.orEmpty().ifBlank { "flat" },
            iconBackgroundColor3 = iconBackgroundColor3.orEmpty().ifBlank { "#3D7806" },
            iconBackgroundColor4 = iconBackgroundColor4.orEmpty().ifBlank { "#4F9905" },
            customIconImageLocal = customIconImage.orEmpty().trim(),
            customIconImageInput = customIconImageInput.orEmpty().trim(),
            buttonShape = buttonShape.orEmpty().ifBlank { "circle" },
            buttonSize = (buttonSizeDp ?: 56).coerceIn(32, 128),
            buttonOpacity = (buttonOpacity ?: 50).coerceIn(10, 100),
            dragVisibilityDuration = (buttonDragVisibilityDurationSeconds ?: 2).coerceIn(1, 10),
            buttonPosition = buttonPosition.orEmpty().ifBlank { "topRight" },
            activityOverride = activityOverride.orEmpty().trim(),
            iconTextSize = (iconTextSize ?: 18).coerceIn(8, 48),
            controlTheme = controlTheme.orEmpty().ifBlank { "modern" },
            controlBackground = controlBackground.orEmpty().ifBlank { "#300000" },
            controlForeground = controlForeground.orEmpty().ifBlank { "#FF5656" },
            bottomButtonStyle = bottomButtonStyle.orEmpty().ifBlank { "solid" },
            bottomButtonShape = bottomButtonShape.orEmpty().ifBlank { "square" },
            bottomButtonPadding = bottomButtonPadding == true,
            bottomButtonTextColor = bottomButtonTextColor.orEmpty().ifBlank { "#300000" },
            bottomButtonBackground1 = bottomButtonBackground1.orEmpty().ifBlank { "#FF5656" },
            bottomButtonBackground2 = bottomButtonBackground2.orEmpty().ifBlank { "#FF5656" },
            menuTextColor1 = menuTextColor1.orEmpty().ifBlank { "#FF5656" },
            menuTextColor2 = menuTextColor2.orEmpty().ifBlank { "#FF5656" },
            menuTextColor3 = menuTextColor3.orEmpty().ifBlank { "#FF5656" },
            menuTextColor4 = menuTextColor4.orEmpty().ifBlank { "#FF5656" },
            menuTextColor5 = menuTextColor5.orEmpty().ifBlank { "#FF5656" },
            menuTextColor6 = menuTextColor6.orEmpty().ifBlank { menuTextColor2.orEmpty().ifBlank { "#FF5656" } },
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
        val iconStyleValue = selectedUiPreset.iconStyle
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
        val customIconLocalSourceValue = selectedUiPreset.customIconImageLocal
        val customIconStringSourceValue = selectedUiPreset.customIconImageInput
        val iconTextSizeValue = selectedUiPreset.iconTextSize
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
        check(iconStyleValue in setOf("text", "shape", "multi"))
        check(iconShapeValue in setOf("triangle", "chevron", "smile", "circle", "z"))
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
        check(separatorBackgroundColorValue.matches(Regex("#[0-9a-fA-F]{6}")))

        val config = listOf(
            CONFIG_VERSION, titleValue, descriptionValue, labelValue, urlValue,
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
        ).joinToString("|") { encode(it) }

        // Prefer the process Application entry point. The Activity path is a compatibility fallback
        // for APKs whose Application class or onCreate method cannot be resolved safely.
        val appDescriptor = StartupHooks.resolvedApplicationDescriptor
        val appClass = appDescriptor?.let { mutableClassDefByOrNull(it) }
        val appMethod = appClass?.let { findInheritedApplicationOnCreate(it) }
        var bridgeInstalled = false
        if (appMethod != null) {
            val (appOwner, appOnCreate) = appMethod
            if (appOnCreate.implementation?.instructions?.any { it.toString().contains(RUNTIME_CLASS) } == true) {
                logger.info("Runtime overlay bridge already exists in ${appOwner.type}->onCreate")
                bridgeInstalled = true
            } else {
                injectMethod(appOwner, appOnCreate, config, application = true)
                logger.info("Runtime overlay bridge injected into ${appOwner.type}->onCreate")
                bridgeInstalled = true
            }
        }

        val fallback = if (bridgeInstalled) {
            null
        } else {
            selectedUiPreset.activityOverride.trim().takeIf { it.isNotEmpty() }?.let(::descriptor)
                ?.let { target -> mutableClassDefByOrNull(target) }
                ?: findFallbackActivity()
        }
        val onCreate = fallback?.methods?.firstOrNull {
            it.name == "onCreate" && it.returnType == "V" && it.parameterTypes == listOf("Landroid/os/Bundle;")
        }
        if (fallback != null && onCreate != null) {
            if (onCreate.implementation?.instructions?.any { it.toString().contains(RUNTIME_CLASS) } == true) {
                logger.info("Runtime overlay bridge already exists in ${fallback.type}->onCreate")
                bridgeInstalled = true
            } else {
                injectMethod(fallback, onCreate, config, application = false)
                logger.warning("Runtime overlay used Activity fallback: ${fallback.type}->onCreate")
                bridgeInstalled = true
            }
        } else if (!bridgeInstalled) {
            logger.warning("No suitable Application or Activity entry point found. No changes applied.")
        }
        if (customMode) exportPreset(exportUiPreset.orEmpty().trim(), exportedUiPresetOutputName.orEmpty(), selectedUiPreset, logger)
    }
}

private fun app.morphe.patcher.patch.BytecodePatchContext.findFallbackActivity(): MutableClass? {
    val superMap = mutableMapOf<String, String>()
    classDefForEach { it.superclass?.let { parent -> superMap[it.type] = parent } }
    fun isActivity(type: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
        if (type == "Landroid/app/Activity;") return true
        if (type == "Ljava/lang/Object;" || !seen.add(type)) return false
        return superMap[type]?.let { isActivity(it, seen) } == true
    }
    val override = StartupHooks.resolvedLauncherActivityDescriptor
    val candidates = mutableListOf<MutableClass>()
    classDefForEach { classDef ->
        if (!isActivity(classDef.type)) return@classDefForEach
        val candidate = mutableClassDefBy(classDef)
        if (candidate.methods.any { it.name == "onCreate" && it.returnType == "V" && it.parameterTypes == listOf("Landroid/os/Bundle;") }) {
            candidates += candidate
        }
    }
    return candidates.firstOrNull { it.type == override } ?: candidates.firstOrNull()
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
