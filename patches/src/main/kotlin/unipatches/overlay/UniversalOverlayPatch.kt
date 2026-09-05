package unipatches.overlay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.imageOption
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.stringOption
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
private const val PRESET_SCHEMA_VERSION = 1
private const val MAX_CUSTOM_ICON_BYTES = 1024 * 1024
private const val MAX_TITLE_CHARACTERS = 80
private const val MAX_DESCRIPTION_CHARACTERS = 500
private val DEFAULT_DESCRIPTION =
    """
    Welcome! This is the UniPatches Universal Overlay Patch Menu.
    You will find modules below the description if you enabled some modules
    in this patch settings before patching this APK.
    The idea and initial works of Universal Overlay Patch are from Zanuaimi / Noobite.
    """.trimIndent()

private fun encode(value: String): String =
    Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun OverlayUiPreset.toJson(): JsonObject = JsonObject().apply {
    addProperty("format", "unipatches-universal-overlay-preset")
    addProperty("version", PRESET_SCHEMA_VERSION)
    add("settings", JsonObject().apply {
        addProperty("title", title)
        addProperty("description", description)
        addProperty("repositoryText", repositoryText)
        addProperty("repositoryUrl", repositoryUrl)
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
        addProperty("customIconImageLocal", customIconImageLocal)
        addProperty("customIconImageInput", customIconImageInput)
        addProperty("buttonShape", buttonShape)
        addProperty("buttonSize", buttonSize)
        addProperty("buttonOpacity", buttonOpacity)
        addProperty("dragVisibilityDuration", dragVisibilityDuration)
        addProperty("buttonPosition", buttonPosition)
        addProperty("activityOverride", activityOverride)
        addProperty("iconTextSize", iconTextSize)
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
            return value?.takeIf { it in range } ?: current
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
        val backgroundSource = values.get("backgroundColor")?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString
        val migratedBackgroundTransparency = backgroundSource
            ?.takeIf { it.matches(Regex("#[0-9a-fA-F]{8}")) }
            ?.substring(1, 3)
            ?.toIntOrNull(16)
            ?.let { ((it * 100f) / 255f).roundToInt() }
        fallback.copy(
            title = text("title", fallback.title) { it.isNotBlank() && it.length <= MAX_TITLE_CHARACTERS },
            description = text("description", fallback.description) { it.isNotBlank() && it.length <= MAX_DESCRIPTION_CHARACTERS },
            repositoryText = text("repositoryText", fallback.repositoryText) { it.isNotBlank() },
            repositoryUrl = text("repositoryUrl", fallback.repositoryUrl) { it.startsWith("http://") || it.startsWith("https://") },
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
    name = "UniPatches Universal Overlay Patch v1.2 (Experimental)",
    description = """
        Universal in-app overlay for Android apps and games. Optional modules include System Time, FPS,
        fullscreen, app brightness, and haptic controls. Modules are excluded and disabled by default;
        select them in Morphe settings before patching. Statistic modules show information, Activity modules
        control the current Activity, and Hook modules control internal app behavior, such as disabling
        animations, through best-effort runtime changes. A selected local image automatically replaces
        the legacy icon; empty or invalid image input falls back to the legacy icon. This is experimental
        and may not work on all apps. UI presets can save and reuse every General, UI, and Advanced
        setting, but intentionally exclude Modules and Settings to Modules because hook and module
        combinations can be app-specific.

        The idea and initial works of this Universal Overlay Patch are from Zanuaimi / Noobite.
    """.trimIndent(),
    default = false,
) {
    // Keep the extension DEX as the runtime boundary; generated Smali should only start it and
    // must not contain overlay UI or feature implementation.
    extendWith("extensions/extension.mpe")
    dependsOn(StartupHooks.resolveRealApplicationPatch)

    val selectedPreset by stringOption(
        title = "Presets - Selected preset",
        default = "custom",
        key = "runtimeOverlaySelectedPreset",
        description = "Choose Custom to use the visible settings. UniPatches, Morphe Blue, Dark, Light, and ZArchiver presets replace the UI settings with readable predefined values. Presets never change Modules or Settings to Modules.",
        values = linkedMapOf("Custom" to "custom").apply {
            OverlayPresetCatalog.definitions.forEach { put(it.displayName, it.id) }
        },
    )
    val importUiPreset by stringOption(
        title = "Presets - Import UI preset",
        default = "",
        key = "runtimeOverlayImportUiPreset",
        description = "Optional path to a Universal Overlay .json preset. Only Custom mode uses it. A valid JSON preset overrides the visible UI settings during patching; an empty, unreadable, malformed, or unsupported file falls back to the visible Morphe settings. The Manager controls do not change visually.",
    )
    val exportUiPreset by stringOption(
        title = "Presets - Export UI preset",
        default = "",
        key = "runtimeOverlayExportUiPreset",
        description = "Optional existing folder where Custom mode exports the final UI preset as JSON after patching. Leave empty to disable. Export errors are logged and never cancel APK patching. Protected system/root locations are rejected.",
    )
    val exportedUiPresetOutputName by stringOption(
        title = "Presets - Exported UI preset output name",
        default = "UniversalOverlay.json",
        key = "runtimeOverlayExportedUiPresetOutputName",
        description = "Output name for exported JSON. Defaults to UniversalOverlay.json; .json is added automatically and duplicate names receive -1, -2, and so on.",
    )

    val title by stringOption(
        title = "General - Overlay title",
        default = "UniPatches Universal Overlay Patch",
        key = "runtimeOverlayTitle",
        description = "Title shown in the overlay menu. Limited to 80 characters.",
    )
    val descriptionText by stringOption(
        title = "General - Overlay description",
        default = DEFAULT_DESCRIPTION,
        key = "runtimeOverlayDescription",
        description = "Description below the title. Limited to 500 characters.",
    )
    val repositoryText by stringOption(
        title = "General - Repository button text",
        default = "UniPatches repository",
        key = "runtimeOverlayRepositoryText",
        description = "Text of the always-present repository button.",
    )
    val repositoryUrl by stringOption(
        title = "General - Repository button URL",
        default = "https://github.com/Zanuaimi/UniPatches",
        key = "runtimeOverlayRepositoryUrl",
        description = "URL opened by the repository button.",
    )
    val backgroundColor by stringOption(
        title = "General - Overlay background color",
        default = "#300000",
        key = "runtimeOverlayBackgroundColor",
        description = "Overlay background color as #RRGGBB. Transparency is controlled separately.",
    )
    val backgroundTransparency by intOption(
        title = "General - Overlay Background Transparency (%)",
        default = 80,
        key = "runtimeOverlayBackgroundTransparency",
        description = "Transparency of the overlay background from 0% to 100%. 80% matches the default Morphe-style background alpha.",
    )
    val outlineColor by stringOption(
        title = "General - Overlay outline color",
        default = "#FF5656",
        key = "runtimeOverlayOutlineColor",
        description = "Overlay outline color as #RRGGBB.",
    )
    val overlayTextColor by stringOption(
        title = "General - Overlay text color",
        default = "#FF5656",
        key = "runtimeOverlayTextColor",
        description = "Color of text and controls inside the overlay menu as #RRGGBB.",
    )
    val outlineWidth by intOption(
        title = "UI - Menu outline width (dp)",
        default = 2,
        key = "runtimeOverlayOutlineWidthDp",
        description = "Width of the menu, monitor, and confirmation outlines, from 1 to 8dp.",
    )
    val buttonText by stringOption(
        title = "UI - Legacy icon text",
        default = "U",
        key = "runtimeOverlayButtonText",
        description = "Text shown by the legacy icon. Maximum three characters.",
    )
    val iconBold by booleanOption(
        title = "UI - Legacy icon bold text",
        default = true,
        key = "runtimeOverlayIconBold",
        description = "Use bold text in the legacy icon. Enabled by default.",
    )
    val buttonTextColor by stringOption(
        title = "UI - Legacy icon text color",
        default = "#FFFFFF",
        key = "runtimeOverlayButtonTextColor",
        description = "Text color used by the legacy icon.",
    )
    val iconTextSize by intOption(
        title = "UI - Legacy icon text size (sp)",
        default = 18,
        key = "runtimeOverlayIconTextSizeSp",
        description = "Text size of the legacy icon in scaled pixels, from 8 to 48sp. The default is slightly larger than the pre-v1.2 fixed size.",
    )
    val gradientBackground by booleanOption(
        title = "UI - Gradient background",
        default = true,
        key = "runtimeOverlayIconGradientBackground",
        description = "Blend legacy icon background 1 into background 2. When disabled, only background 1 is used.",
    )
    val buttonBackgroundColor by stringOption(
        title = "UI - Legacy icon background 1",
        default = "#500000",
        key = "runtimeOverlayButtonBackgroundColor",
        description = "First color of the legacy icon gradient.",
    )
    val iconBackground2 by stringOption(
        title = "UI - Legacy icon background 2",
        default = "#AA0000",
        key = "runtimeOverlayIconBackgroundColor2",
        description = "Second color of the legacy icon gradient as #RRGGBB.",
    )
    val iconGradientAngle by intOption(
        title = "UI - Legacy icon gradient angle (degrees)",
        default = 0,
        key = "runtimeOverlayIconGradientAngle",
        description = "Gradient direction: 0 degrees runs top to bottom and 90 runs left to right. Values wrap through 360 degrees.",
    )
    val iconOutline by booleanOption(
        title = "UI - Icon outline",
        default = false,
        key = "runtimeOverlayIconOutline",
        description = "Add a separate outline around the legacy text icon. Disabled by default.",
    )
    val iconOutlineWidth by intOption(
        title = "UI - Icon outline width (dp)",
        default = 3,
        key = "runtimeOverlayIconOutlineWidthDp",
        description = "Width of the legacy icon outline from 1 to 8dp. This is independent from the overlay menu outline width.",
    )
    val iconOutlineColor by stringOption(
        title = "UI - Icon outline color",
        default = "#FFFFFF",
        key = "runtimeOverlayIconOutlineColor",
        description = "Color used only when the icon outline is enabled.",
    )
    val customIconImage by imageOption(
        title = "UI - Custom Overlay Button Icon ( Local Image )",
        default = "",
        key = "runtimeOverlayCustomIconImage",
        allowedExtensions = listOf("png", "jpg", "jpeg", "webp"),
        recommendedSize = app.morphe.patcher.patch.ImageSize(128, 128),
        description = "Select a local PNG, JPG, JPEG, or WebP image file. This input has priority over the String Handler input when valid. Images are embedded during patching and scaled proportionally. Leave blank or use an invalid file to try the String Handler input, then fall back to the legacy icon.",
    )
    val customIconImageInput by stringOption(
        title = "UI - Custom Overlay Button Icon Input ( String Handler )",
        default = "",
        key = "runtimeOverlayCustomIconImageInput",
        description = "Optional non-local image input: file URI, data URI, raw Base64, URL-safe Base64, or HTTPS image URL. Used only when the Local Image input is empty or invalid. Invalid input falls back to the legacy icon.",
    )
    val buttonShape by stringOption(
        title = "UI - Overlay button shape",
        default = "circle",
        key = "runtimeOverlayButtonShape",
        description = "Shape of the legacy text icon background.",
        values = linkedMapOf("Circle" to "circle", "Squircle" to "squircle", "Square" to "square"),
    )
    val buttonSizeDp by intOption(
        title = "UI - Overlay button size (dp)",
        default = 56,
        key = "runtimeOverlayButtonSizeDp",
        description = "Button size in density-independent pixels.",
    )
    val buttonOpacity by intOption(
        title = "UI - Overlay button idle opacity (%)",
        default = 50,
        key = "runtimeOverlayButtonIdleOpacityPercent",
        description = "Idle opacity from 10 to 100 percent. Higher values make the button less transparent.",
    )
    val buttonDragVisibilityDurationSeconds by intOption(
        title = "UI - Overlay button fully visible duration (seconds)",
        default = 2,
        key = "runtimeOverlayButtonDragVisibilityDurationSeconds",
        description = "How long the overlay button stays fully visible after dragging before fading to its idle opacity. The timer resets while dragging and starts again when the finger is released. Use a value from 1 to 10 seconds.",
    )
    val buttonPosition by stringOption(
        title = "UI - Overlay button position",
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
        title = "Advanced - Overlay Activity name override",
        default = "",
        key = "runtimeOverlayActivityNameOverride",
        description = "Optional fallback Activity class used only when Application startup cannot be found. Leave blank for universal automatic discovery. Example: com.example.MainActivity or Lcom/example/MainActivity;.",
    )
    val activateStatisticsOnLaunch by booleanOption(
        title = "Settings to Modules - Activate statistic modules on launch",
        default = false,
        key = "runtimeOverlayActivateStatisticsOnLaunch",
        description = "Start selected statistic modules as soon as the app launches. Active is disabled by default.",
    )
    val enableMonitorsOnLaunch by booleanOption(
        title = "Settings to Modules - Enable monitors for statistic modules on launch",
        default = false,
        key = "runtimeOverlayEnableMonitorsOnLaunch",
        description = "Show selected statistic monitors as soon as the app launches. Monitor is disabled by default.",
    )
    val statisticMonitorPosition by stringOption(
        title = "Settings to Modules - Statistic monitor position",
        default = "bottom",
        key = "runtimeOverlayStatisticMonitorPosition",
        description = "Show enabled statistic monitors from statistic modules above or below the overlay button.",
        values = linkedMapOf("No stat monitors" to "none", "Above overlay button" to "top", "Below overlay button" to "bottom"),
    )
    val monitorScale by stringOption(
        title = "Settings to Modules - Monitor panel size",
        default = "1",
        key = "runtimeOverlayMonitorScale",
        description = "Size multiplier for statistic monitor panels.",
        values = linkedMapOf("0.75x" to "0.75", "1x" to "1", "1.25x" to "1.25", "1.5x" to "1.5", "2x" to "2"),
    )
    val monitorColumns by stringOption(
        title = "Settings to Modules - Monitor columns",
        default = "2",
        key = "runtimeOverlayMonitorColumns",
        description = "Number of statistic monitor columns.",
        values = linkedMapOf("1 column" to "1", "2 columns" to "2", "3 columns" to "3"),
    )
    val temperatureFormat by stringOption(
        title = "Settings to Modules - Temperature stat format",
        default = "celsius",
        key = "runtimeOverlayTemperatureFormat",
        description = "Temperature unit used by the Device Temperature menu value and monitor.",
        values = linkedMapOf("Celsius" to "celsius", "Fahrenheit" to "fahrenheit", "Kelvin" to "kelvin"),
    )
    val timeFormat by stringOption(
        title = "Settings to Modules - System time format",
        default = "12",
        key = "runtimeOverlayTimeFormat",
        description = "Clock format used by the System Time menu value and monitor. Timezone is shown in the menu value.",
        values = linkedMapOf("12-hour clock" to "12", "24-hour clock" to "24"),
    )
    val includeDeviceInformation by booleanOption(
        title = "Statistic modules - Device Information",
        default = false,
        key = "runtimeOverlayIncludeDeviceInformation",
        description = "Include read-only phone and Android device information.",
    )
    val includeFps by booleanOption(
        title = "Statistic modules - FPS",
        default = false,
        key = "runtimeOverlayIncludeFps",
        description = "Include the approximate display frame-rate statistic module.",
    )
    val includeDeviceTemperature by booleanOption(
        title = "Statistic modules - Device Temperature",
        default = false,
        key = "runtimeOverlayIncludeDeviceTemperature",
        description = "Include battery-reported temperature in the selected temperature format.",
    )
    val includeSystemTime by booleanOption(
        title = "Statistic modules - System Time",
        default = false,
        key = "runtimeOverlayIncludeSystemTime",
        description = "Include the phone system time statistic module.",
    )
    val includeSessionTime by booleanOption(
        title = "Statistic modules - App Session Time",
        default = false,
        key = "runtimeOverlayIncludeSessionTime",
        description = "Include the in-process overlay session timer statistic module.",
    )
    val includeBatteryStatus by booleanOption(
        title = "Statistic modules - Battery Status",
        default = false,
        key = "runtimeOverlayIncludeBatteryStatus",
        description = "Include the current battery percentage statistic module.",
    )
    val includeAppMemory by booleanOption(
        title = "Statistic modules - App Memory Usage",
        default = false,
        key = "runtimeOverlayIncludeAppMemory",
        description = "Include approximate memory used by the current app process.",
    )
    val includeNetworkStatus by booleanOption(
        title = "Statistic modules - Network Status",
        default = false,
        key = "runtimeOverlayIncludeNetworkStatus",
        description = "Include incoming and outgoing app network traffic monitors.",
    )
    val includeKeepAwake by booleanOption(
        title = "Activity modules - Keep screen awake",
        default = false,
        key = "runtimeOverlayIncludeKeepScreenAwake",
        description = "Include the keep-screen-awake activity module.",
    )
    val includeFullscreen by booleanOption(
        title = "Activity modules - Fullscreen",
        default = false,
        key = "runtimeOverlayIncludeFullscreen",
        description = "Include the fullscreen activity module.",
    )
    val includeScreenshots by booleanOption(
        title = "Activity modules - Allow screenshots",
        default = false,
        key = "runtimeOverlayIncludeScreenshots",
        description = "Include the allow-screenshots activity module.",
    )
    val includeAppBrightness by booleanOption(
        title = "Activity modules - App brightness",
        default = false,
        key = "runtimeOverlayIncludeAppBrightness",
        description = "Include a per-Activity brightness slider.",
    )
    val includeRotationMode by booleanOption(
        title = "Activity modules - Rotation mode",
        default = false,
        key = "runtimeOverlayIncludeRotationMode",
        description = "Include a per-Activity rotation mode selector.",
    )
    val includeAppAudioMute by booleanOption(
        title = "Activity modules - App audio mute",
        default = false,
        key = "runtimeOverlayIncludeAppAudioMute",
        description = "Include a best-effort app audio mute toggle.",
    )
    val includeDisableHaptics by booleanOption(
        title = "Hook modules - Disable haptic feedback / vibrations",
        default = false,
        key = "runtimeOverlayIncludeDisableHaptics",
        description = "Include a best-effort runtime haptic and vibration suppression module.",
    )
    val includeDisableAnimations by booleanOption(
        title = "Hook modules - Disable app animations",
        default = false,
        key = "runtimeOverlayIncludeDisableAnimations",
        description = "Include a best-effort runtime animation suppression module.",
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val manualPreset = OverlayUiPreset(
            title = title.orEmpty().ifBlank { "UniPatches Universal Overlay Patch" }.take(MAX_TITLE_CHARACTERS),
            description = descriptionText.orEmpty().ifBlank { DEFAULT_DESCRIPTION }.take(MAX_DESCRIPTION_CHARACTERS),
            repositoryText = repositoryText.orEmpty().ifBlank { "UniPatches repository" },
            repositoryUrl = repositoryUrl.orEmpty().ifBlank { "https://github.com/Zanuaimi/UniPatches" },
            background = backgroundColor.orEmpty().ifBlank { "#300000" },
            backgroundTransparency = (backgroundTransparency ?: 80).coerceIn(0, 100),
            outline = outlineColor.orEmpty().ifBlank { "#FF5656" },
            overlayTextColor = overlayTextColor.orEmpty().ifBlank { "#FF5656" },
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
            customIconImageLocal = customIconImage.orEmpty().trim(),
            customIconImageInput = customIconImageInput.orEmpty().trim(),
            buttonShape = buttonShape.orEmpty().ifBlank { "circle" },
            buttonSize = (buttonSizeDp ?: 56).coerceIn(32, 128),
            buttonOpacity = (buttonOpacity ?: 50).coerceIn(10, 100),
            dragVisibilityDuration = (buttonDragVisibilityDurationSeconds ?: 2).coerceIn(1, 10),
            buttonPosition = buttonPosition.orEmpty().ifBlank { "topRight" },
            activityOverride = activityOverride.orEmpty().trim(),
            iconTextSize = (iconTextSize ?: 18).coerceIn(8, 48),
        )
        val customMode = selectedPreset.orEmpty().equals("custom", ignoreCase = true)
        val selectedUiPreset = if (customMode) {
            readPresetFile(importUiPreset.orEmpty().trim(), manualPreset, logger)
        } else {
            OverlayPresetCatalog.valuesFor(selectedPreset.orEmpty(), manualPreset)
        }
        val titleValue = selectedUiPreset.title
        val descriptionValue = selectedUiPreset.description
        val labelValue = selectedUiPreset.repositoryText
        val urlValue = selectedUiPreset.repositoryUrl
        val sizeValue = selectedUiPreset.buttonSize
        val opacityValue = selectedUiPreset.buttonOpacity
        val dragVisibilityDurationValue = selectedUiPreset.dragVisibilityDuration
        val shapeValue = selectedUiPreset.buttonShape
        val positionValue = selectedUiPreset.buttonPosition
        val backgroundValue = selectedUiPreset.background
        val outlineValue = selectedUiPreset.outline
        val overlayTextColorValue = selectedUiPreset.overlayTextColor
        val buttonTextColorValue = selectedUiPreset.buttonTextColor
        val buttonBackgroundValue = selectedUiPreset.buttonBackground
        val outlineWidthValue = selectedUiPreset.outlineWidth
        val iconOutlineColorValue = selectedUiPreset.iconOutlineColor
        val iconBackground2Value = selectedUiPreset.iconBackground2
        val iconGradientAngleValue = selectedUiPreset.iconGradientAngle
        val backgroundTransparencyValue = selectedUiPreset.backgroundTransparency
        val iconOutlineWidthValue = selectedUiPreset.iconOutlineWidth
        val customIconLocalSourceValue = selectedUiPreset.customIconImageLocal
        val customIconStringSourceValue = selectedUiPreset.customIconImageInput
        val iconTextSizeValue = selectedUiPreset.iconTextSize
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
        validate(
            titleValue, descriptionValue, labelValue, urlValue,
            backgroundValue, outlineValue, overlayTextColorValue, buttonTextColorValue, buttonBackgroundValue,
            outlineWidthValue, iconOutlineColorValue, iconBackground2Value,
            iconGradientAngleValue, backgroundTransparencyValue, iconOutlineWidthValue,
            shapeValue, positionValue, sizeValue, opacityValue,
        )
        check(monitorPositionValue in setOf("none", "top", "bottom"))
        check(monitorScaleValue.toFloatOrNull() in listOf(.75f, 1f, 1.25f, 1.5f, 2f))
        check(monitorColumnsValue in setOf("1", "2", "3"))
        check(temperatureFormatValue in setOf("celsius", "fahrenheit", "kelvin"))
        check(timeFormatValue in setOf("12", "24"))
        check(selectedUiPreset.buttonText.length <= 3)

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
