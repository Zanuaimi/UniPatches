package unipatches.display

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import helpers.bytecode.*
import helpers.graphics.UnityPlayerActivityOnCreateFingerprint
import helpers.manifest.NS_ANDROID
import helpers.manifest.applicationOrNull
import helpers.startup.StartupHooks
import java.util.logging.Logger
import org.w3c.dom.Element

private const val ANGLE_DRIVER_METADATA = "com.android.graphics.driver.prefer_angle"

private val frameRateBits = mapOf(
    "24" to "0x41c00000", "30" to "0x41f00000", "40" to "0x42200000",
    "45" to "0x42340000", "48" to "0x42400000", "50" to "0x42480000",
    "60" to "0x42700000", "72" to "0x42900000", "90" to "0x42b40000",
    "96" to "0x42c00000", "120" to "0x42f00000", "144" to "0x43100000",
    "165" to "0x43250000", "180" to "0x43340000", "185" to "0x43390000",
)

private val frameRateValues = linkedMapOf(
    "24 FPS" to "24", "30 FPS" to "30", "40 FPS" to "40", "45 FPS" to "45",
    "48 FPS" to "48", "50 FPS" to "50", "60 FPS" to "60", "72 FPS" to "72",
    "90 FPS" to "90", "96 FPS" to "96", "120 FPS" to "120", "144 FPS" to "144",
    "165 FPS" to "165", "180 FPS" to "180", "185 FPS" to "185",
)

private fun Element.removeAspectRestrictions(): Int {
    var removed = 0
    if (hasAttributeNS(NS_ANDROID, "maxAspectRatio")) {
        removeAttributeNS(NS_ANDROID, "maxAspectRatio")
        removed++
    }
    return removed
}

@Suppress("unused")
val customAppDisplayPatch = bytecodePatch(
    name = "Custom App Display Patch (Experimental, Enhanced)",
    description = """
        Adjust an APK's display compatibility: screen shape, Unity window-size request, preferred
        refresh rate, Unity renderer preference, ANGLE preference, and Android game-category hint.
        Start with Display scaling. Android and the app can ignore any request.

        Resolution and renderer overrides currently target Unity activities. Scaling removes Android
        aspect-ratio restrictions where present; it cannot universally rescale every game engine.

        Custom App Resolution and game-optimization inspiration: Nai64Patches from Nai64.
        Frame-rate, graphics-driver, ANGLE, and aspect-ratio controls are UniPatches work.
    """.trimIndent(),
    default = false,
) {
    dependsOn(StartupHooks.resolveRealApplicationPatch)
    val scalingMode by stringOption(
        title = "Quick setup > Display scaling > Mode",
        default = "default",
        key = "displayScalingMode",
        description = "Default preserves the app's behavior. Remove aspect restrictions lets Android use more screen space without forcing a stretch. Stretch to screen also requests resizable activities; engines may still keep their own letterboxing.",
        values = linkedMapOf("Default (default)" to "default", "Remove aspect restrictions" to "fit", "Stretch to screen" to "stretch"),
    )
    val customResolutionEnabled by booleanOption(
        title = "Optional overrides > Resolution > Enable",
        default = false,
        key = "displayCustomResolutionEnabled",
        description = "Request this window size for supported Unity activities. Disabled by default. Fullscreen games and Android window policy may override it.",
    )
    val resolutionWidth by intOption(
        title = "Optional overrides > Resolution > Width (px)",
        default = 1920,
        key = "displayResolutionWidth",
        description = "Requested horizontal window size in pixels when Resolution override is enabled. Default: 1920.",
    )
    val resolutionHeight by intOption(
        title = "Optional overrides > Resolution > Height (px)",
        default = 1080,
        key = "displayResolutionHeight",
        description = "Requested vertical window size in pixels when Resolution override is enabled. Default: 1080. Swap width and height for portrait content.",
    )
    val fpsEnabled by booleanOption(
        title = "Optional overrides > Frame rate > Enable FPS preference",
        default = false,
        key = "displayFpsEnabled",
        description = "Request a preferred display refresh rate for the selected Activity scope. This cannot exceed the display, bypass a game FPS cap, or override battery/thermal policy.",
    )
    val targetFps by stringOption(
        title = "Optional overrides > Frame rate > Target FPS",
        default = "60",
        key = "displayTargetFps",
        description = "Preferred display refresh rate used when Enable FPS preference is on. Default: 60 FPS.",
        values = frameRateValues,
    )
    val fpsScope by stringOption(
        title = "Optional overrides > Frame rate > Activity scope",
        default = "launcher",
        key = "displayFpsScope",
        description = "Launcher activity only (recommended) avoids changing sign-in, billing, settings, and third-party screens. All app activities applies the preference broadly and may affect those flows.",
        values = linkedMapOf("Launcher activity only (recommended)" to "launcher", "All app activities" to "all"),
    )
    val graphicsApi by stringOption(
        title = "Optional overrides > Graphics > API preference",
        default = "app",
        key = "displayGraphicsApi",
        description = "Decided by app makes no renderer change. OpenGL ES and Vulkan add a Unity launch argument only when a supported Unity player activity exists. ANGLE adds Android's official GLES-driver preference metadata on supported devices.",
        values = linkedMapOf("Decided by app (default)" to "app", "Prefer OpenGL ES" to "opengl", "Prefer ANGLE for OpenGL ES" to "angle", "Prefer Vulkan" to "vulkan"),
    )
    val gameMode by stringOption(
        title = "Optional overrides > Game mode > Optimization preference",
        default = "app",
        key = "displayGameMode",
        description = "Decided by app makes no change. Mark as game adds Android's game app-category hint, which may let the device offer its own game optimizations. It cannot force an OEM Game Mode on or off.",
        values = linkedMapOf("Decided by app (default)" to "app", "Mark as game" to "game"),
    )

    val displayManifestPatch = resourcePatch(
        name = "Custom App Display Manifest (internal)",
        default = false,
    ) {
        execute {
            val logger = Logger.getLogger(this::class.java.name)
            var changes = 0
            document("AndroidManifest.xml").use { manifest ->
                val application = manifest.documentElement.applicationOrNull() ?: run {
                    logger.warning("Custom App Display: no <application> element found. No manifest changes applied.")
                    return@use
                }
                if (scalingMode != "default") {
                    changes += application.removeAspectRestrictions()
                    application.setAttributeNS(NS_ANDROID, "android:resizeableActivity", "true")
                    changes++
                    val activities = manifest.getElementsByTagName("activity")
                    for (index in 0 until activities.length) {
                        val activity = activities.item(index) as? Element ?: continue
                        changes += activity.removeAspectRestrictions()
                        if (scalingMode == "stretch") {
                            activity.setAttributeNS(NS_ANDROID, "android:resizeableActivity", "true")
                            changes++
                        }
                    }
                    val metadata = manifest.getElementsByTagName("meta-data")
                    for (index in metadata.length - 1 downTo 0) {
                        val meta = metadata.item(index) as? Element ?: continue
                        if (meta.getAttributeNS(NS_ANDROID, "name") == "android.max_aspect") {
                            meta.parentNode?.removeChild(meta)
                            changes++
                        }
                    }
                }
                if (customResolutionEnabled == true) {
                    application.setAttributeNS(NS_ANDROID, "android:resizeableActivity", "true")
                    changes++
                }
                if (graphicsApi == "angle") {
                    val metadata = application.getElementsByTagName("meta-data")
                    var angleEntry: Element? = null
                    for (index in 0 until metadata.length) {
                        val meta = metadata.item(index) as? Element ?: continue
                        if (meta.getAttributeNS(NS_ANDROID, "name") == ANGLE_DRIVER_METADATA) angleEntry = meta
                    }
                    val entry = angleEntry ?: manifest.createElement("meta-data").also { application.appendChild(it) }
                    entry.setAttributeNS(NS_ANDROID, "android:name", ANGLE_DRIVER_METADATA)
                    entry.setAttributeNS(NS_ANDROID, "android:value", "true")
                    changes++
                }
                if (gameMode == "game") {
                    application.setAttributeNS(NS_ANDROID, "android:appCategory", "game")
                    changes++
                    logger.info("Custom App Display: added Android game app-category hint.")
                }
            }
            if (changes == 0) logger.info("Custom App Display: no manifest option selected or no compatible restriction found.")
            else logger.info("Custom App Display: applied $changes manifest display compatibility change(s).")
        }
    }
    dependsOn(displayManifestPatch)

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        var applied = 0

        if (fpsEnabled == true) {
            val bits = frameRateBits[targetFps.orEmpty()]
            if (bits == null) logger.warning("Custom App Display: invalid FPS value ${targetFps.orEmpty()}. Skipping FPS preference.")
            else {
                val parents = mutableMapOf<String, String>()
                classDefForEach { classDef -> classDef.superclass?.let { parents[classDef.type] = it } }
                fun isActivity(type: String, seen: MutableSet<String> = mutableSetOf()): Boolean = when {
                    type == "Landroid/app/Activity;" -> true
                    type == "Ljava/lang/Object;" || !seen.add(type) -> false
                    else -> parents[type]?.let(::isActivity) == true
                }
                val launcher = StartupHooks.resolvedLauncherActivityDescriptor
                if (fpsScope == "launcher" && launcher == null) {
                    logger.warning("Custom App Display: launcher Activity could not be resolved; skipping the scoped FPS preference. Choose All app activities only if you accept a broader change.")
                }
                classDefForEach { classDef ->
                    if (fpsScope == "launcher" && classDef.type != launcher) return@classDefForEach
                    if (!isActivity(classDef.type)) return@classDefForEach
                    val mutableClass = mutableClassDefBy(classDef)
                    mutableClass.methods.toList().filter {
                        it.name == "onCreate" && it.returnType == "V" &&
                            it.parameterTypes == listOf("Landroid/os/Bundle;") && it.implementation != null
                    }.forEach { method ->
                        val allocation = method.cloneMutableAndAllocateScratchRegisters(mutableClass, 3)
                        val safeMethod = allocation.method
                        val r0 = allocation.firstScratchRegister
                        val r1 = r0 + 1
                        val r2 = r0 + 2
                        safeMethod.addInstructions(0, """
                            sget v$r0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                            const/16 v$r1, 0x17
                            if-lt v$r0, v$r1, :unipatches_fps_done
                            invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
                            move-result-object v$r0
                            invoke-virtual {v$r0}, Landroid/view/Window;->getAttributes()Landroid/view/WindowManager${'$'}LayoutParams;
                            move-result-object v$r1
                            const/high16 v$r2, $bits
                            iput v$r2, v$r1, Landroid/view/WindowManager${'$'}LayoutParams;->preferredRefreshRate:F
                            invoke-virtual {v$r0, v$r1}, Landroid/view/Window;->setAttributes(Landroid/view/WindowManager${'$'}LayoutParams;)V
                            :unipatches_fps_done
                        """.trimIndent())
                        applied++
                    }
                }
                logger.info("Custom App Display: requested ${targetFps.orEmpty()} FPS for $applied ${if (fpsScope == "launcher") "launcher" else "Activity"} window(s).")
            }
        }

        val unityMethod = UnityPlayerActivityOnCreateFingerprint.methodOrNull
        val unityClass = UnityPlayerActivityOnCreateFingerprint.classDefOrNull?.let(::mutableClassDefBy)
        if (customResolutionEnabled == true || graphicsApi == "opengl" || graphicsApi == "vulkan") {
            if (unityMethod == null || unityClass == null) {
                logger.warning("Custom App Display: no supported Unity activity found; selected resolution/renderer request was not applied.")
            } else {
                val allocation = unityMethod.cloneMutableAndAllocateScratchRegisters(unityClass, 3)
                val method = allocation.method
                val r0 = allocation.firstScratchRegister
                val r1 = r0 + 1
                val r2 = r0 + 2
                val instructions = buildString {
                    if (customResolutionEnabled == true) {
                        val width = resolutionWidth ?: 0
                        val height = resolutionHeight ?: 0
                        if (width > 0 && height > 0) append("""
                            invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
                            move-result-object v$r2
                            const v$r0, $width
                            const v$r1, $height
                            invoke-virtual {v$r2, v$r0, v$r1}, Landroid/view/Window;->setLayout(II)V
                        """.trimIndent()).append('\n')
                        else logger.warning("Custom App Display: resolution must be positive; skipping resolution override.")
                    }
                    when (graphicsApi) {
                        "opengl" -> append("""
                            invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
                            move-result-object v$r0
                            const-string v$r1, "unity"
                            const-string v$r2, "-force-gles20"
                            invoke-virtual {v$r0, v$r1, v$r2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
                        """.trimIndent())
                        "vulkan" -> append("""
                            invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
                            move-result-object v$r0
                            const-string v$r1, "unity"
                            const-string v$r2, "-force-vulkan"
                            invoke-virtual {v$r0, v$r1, v$r2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
                        """.trimIndent())
                    }
                }
                if (instructions.isNotBlank()) {
                    method.addInstructions(0, instructions)
                    applied++
                    logger.info("Custom App Display: applied Unity resolution/renderer request.")
                }
            }
        }
        if (graphicsApi == "angle") logger.info("Custom App Display: added the ANGLE manifest preference; Android uses it only on supported devices.")
        if (applied == 0 && scalingMode == "default" && graphicsApi == "app" && gameMode == "app") {
            logger.info("Custom App Display: no display option selected. No bytecode changes applied.")
        }
    }
}
