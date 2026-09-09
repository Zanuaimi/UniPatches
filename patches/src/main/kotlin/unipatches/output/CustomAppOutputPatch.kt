package unipatches.output

import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.imageOption
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import helpers.manifest.NS_ANDROID
import helpers.manifest.applicationOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.Base64
import java.util.logging.Logger
import org.w3c.dom.Element

private const val CUSTOM_ICON_RESOURCE = "unipatches_custom_output_icon"
private const val HIDDEN_ICON_RESOURCE = "unipatches_hidden_output_icon"
private const val MAX_ICON_BYTES = 4 * 1024 * 1024

private val PACKAGE_NAME = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
private val PACKAGE_SUFFIX = Regex("^(\\.[a-z][a-z0-9_]*)+$")

private const val TRANSPARENT_ICON_XML = """
    <?xml version="1.0" encoding="utf-8"?>
    <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
        <solid android:color="#00000000" />
        <size android:width="1dp" android:height="1dp" />
    </shape>
"""

@Suppress("unused")
val customAppOutputPatch = resourcePatch(
    name = "Custom App Output Patch (Experimental, Enhanced)",
    description = """
        Customize an APK's install identity and launcher presentation in one patch. Start with the
        launcher name or icon; enable Clone only when you need a side-by-side copy. Name, icon,
        hide-icon, and target-SDK options are
        independent. This cannot preserve original-app data when a package or signing identity
        changes. Clone mode rewrites supported manifest identifiers only; it does not rewrite
        bytecode strings, explicit process names, task affinities, or arbitrary SDK configuration.
        A clone has a new Android package identity and normally a new signing identity: package- or
        certificate-bound OAuth, Firebase, Google Play Games, billing, deep links, and server
        licenses may therefore not work and cannot be repaired safely by this patch. If PairIP
        Bypass is also enabled, server/package-bound PairIP enforcement can still reject the clone.

        Inspired by Nai64Patches from Nai64: Clone, Custom App Icon, Hide App Icon, and target
        SDK customization patches.
    """.trimIndent(),
    default = false,
) {
    val cloneEnabled by booleanOption(
        title = "Advanced > Clone for side-by-side install > Enable",
        default = false,
        key = "customAppOutputCloneEnabled",
        description = "Create a side-by-side install by changing the manifest package name. Disabled by default. This does not bypass app signatures or preserve original-app data. Package/certificate-bound OAuth, Firebase, Google Play Games, billing, deep links, and server licenses may stop working; PairIP cannot reliably bypass server package checks on a clone.",
    )
    val cloneMode by stringOption(
        title = "Advanced > Clone for side-by-side install > Package name mode",
        default = "append",
        key = "customAppOutputCloneMode",
        description = "Append adds a suffix to the original package, such as com.example.app.u. Complete replace uses the Package name override exactly as entered.",
        values = linkedMapOf("Append suffix (default)" to "append", "Complete replace" to "replace"),
    )
    val cloneSuffix by stringOption(
        title = "Advanced > Clone for side-by-side install > Append package suffix",
        default = ".u",
        key = "customAppOutputCloneSuffix",
        description = "Suffix used by Append mode. Use one or more lowercase package segments, for example .u, .clone, or .patched.test. A leading dot is added automatically when omitted.",
    )
    val packageNameOverride by stringOption(
        title = "Advanced > Clone for side-by-side install > Package name override",
        default = "",
        key = "customAppOutputPackageNameOverride",
        description = "Complete replacement package name used only in Complete replace mode, for example com.example.mycopy. Leave empty when using Append mode.",
    )
    val rewriteAuthorities by booleanOption(
        title = "Advanced > Clone compatibility > Rewrite provider authorities",
        default = true,
        key = "customAppOutputRewriteAuthorities",
        description = "Update ContentProvider authorities that begin with the original package. This avoids common side-by-side install conflicts.",
    )
    val rewritePermissions by booleanOption(
        title = "Advanced > Clone compatibility > Rewrite custom permissions",
        default = true,
        key = "customAppOutputRewritePermissions",
        description = "Update app-defined permission names and matching uses-permission entries that begin with the original package.",
    )
    val expandRelativeComponents by booleanOption(
        title = "Advanced > Clone compatibility > Preserve relative components",
        default = true,
        key = "customAppOutputExpandRelativeComponents",
        description = "Expand relative Activity, Service, Receiver, Provider, and alias class names to the original package before cloning. This prevents Android from searching for classes in the new package.",
    )

    val appName by stringOption(
        title = "Quick setup > Launcher presentation > App name",
        default = "",
        key = "customAppOutputName",
        description = "Name shown in the home launcher. It updates the application label and launcher activities or aliases. Leave empty to keep the original name.",
    )

    val hideAppIcon by booleanOption(
        title = "Quick setup > Launcher presentation > Hide launcher icon",
        default = false,
        key = "customAppOutputHideIcon",
        description = "Replace launcher icons with a transparent drawable. The app remains installed and launchable, but its launcher entry appears blank. This takes priority over Custom app icon.",
    )
    val customIconEnabled by booleanOption(
        title = "Quick setup > Launcher presentation > Use custom icon",
        default = false,
        key = "customAppOutputCustomIconEnabled",
        description = "Replace the application and all Activity or Activity-alias icon attributes with your supplied image. Ignored when Hide launcher icon is enabled.",
    )
    val customIconLocal by imageOption(
        title = "Quick setup > Launcher presentation > Icon local image",
        default = "",
        key = "customAppOutputCustomIconLocal",
        allowedExtensions = listOf("png", "jpg", "jpeg", "webp"),
        recommendedSize = app.morphe.patcher.patch.ImageSize(512, 512),
        description = "Local PNG, JPG, JPEG, or WebP file. A square image with transparent padding is recommended. This source takes priority over String Handler.",
    )
    val customIconInput by stringOption(
        title = "Quick setup > Launcher presentation > Icon Base64 or HTTPS image",
        default = "",
        key = "customAppOutputCustomIconInput",
        description = "Fallback icon source when Local image is empty: a raw Base64 image string, data:image/...;base64,..., or an HTTPS image URL. Example Base64 input: <base64 string here>. You can encode an image at https://base64.guru/converter/encode/image.",
    )

    val targetSdkEnabled by booleanOption(
        title = "Advanced > Android compatibility > Override target SDK",
        default = false,
        key = "customAppOutputTargetSdkEnabled",
        description = "Write a targetSdkVersion into the manifest. Enable only when you need to address an installer compatibility issue, because changing it can alter Android behavior.",
    )
    val targetSdk by intOption(
        title = "Advanced > Android compatibility > Target SDK version",
        default = 35,
        key = "customAppOutputTargetSdk",
        description = "Target SDK used when Override target SDK is enabled. Common current values are 34 or 35. Valid range: 1 to 100.",
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val requestedName = appName.orEmpty().trim().take(200)
        val shouldHideIcon = hideAppIcon == true
        val shouldReplaceIcon = !shouldHideIcon && customIconEnabled == true
        val customIconBytes = if (shouldReplaceIcon) {
            resolveIconBytes(customIconLocal.orEmpty(), customIconInput.orEmpty(), logger)
        } else {
            null
        }

        if (shouldReplaceIcon && customIconBytes == null) {
            logger.warning("Custom App Output: custom icon was enabled but no valid image was supplied; original icons remain unchanged.")
        }

        if (shouldReplaceIcon && customIconBytes != null) {
            // resolveIconBytes accepts only formats recognized by iconExtension.
            val extension = checkNotNull(iconExtension(customIconBytes))
            val drawableDir = get("res/drawable-nodpi", true).also { it.mkdirs() }
            File(drawableDir, "$CUSTOM_ICON_RESOURCE.$extension").writeBytes(customIconBytes)
            logger.info("Custom App Output: wrote custom icon ($extension, ${customIconBytes.size} bytes)")
        }
        if (shouldHideIcon) {
            val drawableDir = get("res/drawable", true).also { it.mkdirs() }
            File(drawableDir, "$HIDDEN_ICON_RESOURCE.xml").writeText(TRANSPARENT_ICON_XML.trimIndent() + "\n")
        }

        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement ?: run {
                logger.warning("Custom App Output: AndroidManifest.xml has no root element.")
                return@use
            }
            val originalPackage = root.getAttribute("package").trim()
            val clonedPackage = if (cloneEnabled == true) {
                resolveClonePackage(originalPackage, cloneMode.orEmpty(), cloneSuffix.orEmpty(), packageNameOverride.orEmpty(), logger)
            } else {
                null
            }

            if (clonedPackage != null && clonedPackage != originalPackage) {
                root.setAttribute("package", clonedPackage)
                rewriteCloneReferences(
                    manifest = manifest,
                    originalPackage = originalPackage,
                    newPackage = clonedPackage,
                    authorities = rewriteAuthorities == true,
                    permissions = rewritePermissions == true,
                    components = expandRelativeComponents == true,
                    logger = logger,
                )
                logger.info("Custom App Output: package renamed $originalPackage -> $clonedPackage")
            }

            root.applicationOrNull()?.let { application ->
                if (requestedName.isNotEmpty()) {
                    application.setAttributeNS(NS_ANDROID, "android:label", requestedName)
                    var launcherLabels = 0
                    for (tag in listOf("activity", "activity-alias")) {
                        val components = manifest.getElementsByTagName(tag)
                        for (index in 0 until components.length) {
                            val component = components.item(index) as? Element ?: continue
                            if (!isLauncherComponent(component)) continue
                            component.setAttributeNS(NS_ANDROID, "android:label", requestedName)
                            launcherLabels++
                        }
                    }
                    logger.info("Custom App Output: launcher name set to $requestedName ($launcherLabels launcher component(s))")
                }

                val iconReference = when {
                    shouldHideIcon -> "@drawable/$HIDDEN_ICON_RESOURCE"
                    customIconBytes != null && shouldReplaceIcon -> "@drawable/$CUSTOM_ICON_RESOURCE"
                    else -> null
                }
                if (iconReference != null) {
                    var iconReferences = 0
                    fun Element.replaceIcon() {
                        setAttributeNS(NS_ANDROID, "android:icon", iconReference)
                        setAttributeNS(NS_ANDROID, "android:roundIcon", iconReference)
                        iconReferences += 2
                    }
                    application.replaceIcon()
                    for (tag in listOf("activity", "activity-alias")) {
                        val components = manifest.getElementsByTagName(tag)
                        for (index in 0 until components.length) {
                            (components.item(index) as? Element)?.replaceIcon()
                        }
                    }
                    logger.info("Custom App Output: updated $iconReferences icon reference(s)")
                }
            } ?: logger.warning("Custom App Output: no <application> element found; name and icon changes were skipped.")

            if (targetSdkEnabled == true) {
                val requestedTarget = (targetSdk ?: 35).coerceIn(1, 100)
                val usesSdk = root.getElementsByTagName("uses-sdk").item(0) as? Element
                val minSdk = usesSdk?.getAttributeNS(NS_ANDROID, "minSdkVersion")?.toIntOrNull()
                val target = maxOf(requestedTarget, minSdk ?: 1)
                if (target != requestedTarget) logger.warning("Custom App Output: requested target SDK $requestedTarget is below minSdkVersion $minSdk; using $target instead.")
                if (usesSdk != null) {
                    usesSdk.setAttributeNS(NS_ANDROID, "android:targetSdkVersion", target.toString())
                } else {
                    val created = manifest.createElement("uses-sdk")
                    created.setAttributeNS(NS_ANDROID, "android:targetSdkVersion", target.toString())
                    root.insertBefore(created, root.applicationOrNull())
                }
                logger.info("Custom App Output: targetSdkVersion set to $target")
                logger.info("Custom App Output compatibility: test runtime overlay installation and display overrides after changing target SDK, because Android window and compatibility behavior can vary by target level.")
            }
        }
    }
}

private fun resolveClonePackage(
    originalPackage: String,
    mode: String,
    suffixInput: String,
    overrideInput: String,
    logger: Logger,
): String? {
    if (!originalPackage.matches(PACKAGE_NAME)) {
        logger.warning("Custom App Output: manifest package '$originalPackage' is not a supported package name; clone skipped.")
        return null
    }
    val candidate = if (mode == "replace") {
        overrideInput.trim()
    } else {
        val suffix = suffixInput.trim().let { if (it.startsWith('.')) it else ".$it" }
        if (!suffix.matches(PACKAGE_SUFFIX)) {
            logger.warning("Custom App Output: append suffix '$suffixInput' is invalid; clone skipped.")
            return null
        }
        originalPackage + suffix
    }
    if (!candidate.matches(PACKAGE_NAME)) {
        logger.warning("Custom App Output: package '$candidate' is invalid; clone skipped.")
        return null
    }
    if (candidate == originalPackage) {
        logger.warning("Custom App Output: clone package equals the original package; clone skipped.")
        return null
    }
    return candidate
}

private fun rewriteCloneReferences(
    manifest: org.w3c.dom.Document,
    originalPackage: String,
    newPackage: String,
    authorities: Boolean,
    permissions: Boolean,
    components: Boolean,
    logger: Logger,
) {
    fun androidAttribute(element: Element, name: String): String =
        element.getAttributeNS(NS_ANDROID, name).takeIf { it.isNotEmpty() }
            ?: element.getAttribute("android:$name")

    fun replacePackagePrefix(value: String): String = when {
        value == originalPackage -> newPackage
        value.startsWith("$originalPackage.") -> newPackage + value.removePrefix(originalPackage)
        else -> value
    }

    var authorityChanges = 0
    if (authorities) {
        val providers = manifest.getElementsByTagName("provider")
        for (index in 0 until providers.length) {
            val provider = providers.item(index) as? Element ?: continue
            val source = androidAttribute(provider, "authorities")
            if (source.isEmpty()) continue
            val rewritten = source.split(';').joinToString(";") { item -> replacePackagePrefix(item.trim()) }
            if (rewritten != source) {
                provider.setAttributeNS(NS_ANDROID, "android:authorities", rewritten)
                authorityChanges++
            }
        }
    }

    var permissionChanges = 0
    if (permissions) {
        val allElements = manifest.getElementsByTagName("*")
        for (index in 0 until allElements.length) {
            val entry = allElements.item(index) as? Element ?: continue
            if (entry.tagName.startsWith("permission") || entry.tagName.startsWith("uses-permission")) {
                val source = androidAttribute(entry, "name")
                val rewritten = replacePackagePrefix(source)
                if (rewritten != source) {
                    entry.setAttributeNS(NS_ANDROID, "android:name", rewritten)
                    permissionChanges++
                }
            }
            for (attribute in listOf("permission", "readPermission", "writePermission")) {
                val source = androidAttribute(entry, attribute)
                val rewritten = replacePackagePrefix(source)
                if (rewritten != source) {
                    entry.setAttributeNS(NS_ANDROID, "android:$attribute", rewritten)
                    permissionChanges++
                }
            }
        }
    }

    var componentChanges = 0
    if (components) {
        fun expandRelative(name: String): String? = when {
            name.startsWith('.') -> originalPackage + name
            name.isNotEmpty() && !name.contains('.') -> "$originalPackage.$name"
            else -> null
        }
        manifest.documentElement.applicationOrNull()?.let { application ->
            expandRelative(androidAttribute(application, "name"))?.let {
                application.setAttributeNS(NS_ANDROID, "android:name", it)
                componentChanges++
            }
        }
        for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
            val entries = manifest.getElementsByTagName(tag)
            for (index in 0 until entries.length) {
                val entry = entries.item(index) as? Element ?: continue
                expandRelative(androidAttribute(entry, "name"))?.let {
                    entry.setAttributeNS(NS_ANDROID, "android:name", it)
                    componentChanges++
                }
                if (tag == "activity-alias") {
                    expandRelative(androidAttribute(entry, "targetActivity"))?.let {
                        entry.setAttributeNS(NS_ANDROID, "android:targetActivity", it)
                        componentChanges++
                    }
                }
            }
        }
        val metadata = manifest.getElementsByTagName("meta-data")
        for (index in 0 until metadata.length) {
            val entry = metadata.item(index) as? Element ?: continue
            val value = androidAttribute(entry, "value")
            if (value.startsWith('.')) {
                entry.setAttributeNS(NS_ANDROID, "android:value", originalPackage + value)
                componentChanges++
            }
        }
    }
    logger.info("Custom App Output: clone compatibility updates: authorities=$authorityChanges permissions=$permissionChanges components=$componentChanges")
    logger.warning("Custom App Output: clone mode is manifest-only. Review explicit process, taskAffinity, deep-link, SDK, and bytecode package references if the cloned app does not start.")
}

private fun isLauncherComponent(component: Element): Boolean {
    val filters = component.getElementsByTagName("intent-filter")
    for (filterIndex in 0 until filters.length) {
        val filter = filters.item(filterIndex) as? Element ?: continue
        var main = false
        var launcher = false
        val actions = filter.getElementsByTagName("action")
        for (index in 0 until actions.length) {
            val action = actions.item(index) as? Element ?: continue
            if (action.getAttributeNS(NS_ANDROID, "name") == "android.intent.action.MAIN") main = true
        }
        val categories = filter.getElementsByTagName("category")
        for (index in 0 until categories.length) {
            val category = categories.item(index) as? Element ?: continue
            if (category.getAttributeNS(NS_ANDROID, "name") == "android.intent.category.LAUNCHER") launcher = true
        }
        if (main && launcher) return true
    }
    return false
}

private fun resolveIconBytes(localSource: String, inputSource: String, logger: Logger): ByteArray? {
    fun acceptable(bytes: ByteArray?): ByteArray? = bytes?.takeIf {
        it.isNotEmpty() && it.size <= MAX_ICON_BYTES && iconExtension(it) != null
    }
    val localPath = localSource.trim().takeIf { it.isNotEmpty() }
    val localFile = localPath?.let(::File)
    val local = localFile?.let { file ->
        runCatching {
            file.takeIf { it.isFile && it.length() <= MAX_ICON_BYTES }?.readBytes()
        }.getOrNull()
    }
    acceptable(local)?.let { return it }
    if (localPath != null) logger.warning(
        if (localFile?.isFile == true && localFile.length() > MAX_ICON_BYTES) "Custom App Output: local icon exceeds ${MAX_ICON_BYTES / 1024 / 1024} MiB and was ignored."
        else "Custom App Output: local icon is missing or is not a supported PNG, JPG, or WebP image."
    )

    val source = inputSource.trim()
    if (source.isEmpty()) return null
    val bytes = runCatching {
        when {
            source.startsWith("data:", ignoreCase = true) -> {
                val separator = source.indexOf(',')
                require(separator > 0 && source.substring(0, separator).contains("base64", ignoreCase = true))
                val encoded = source.substring(separator + 1).replace(Regex("\\s"), "")
                require(encoded.length <= MAX_ICON_BYTES * 2)
                Base64.getDecoder().decode(encoded)
            }
            source.startsWith("https://", ignoreCase = true) -> URI(source).toURL().openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }.getInputStream().use(::readIconAtMost)
            else -> {
                val encoded = source.replace(Regex("\\s"), "")
                require(encoded.length <= MAX_ICON_BYTES * 2)
                Base64.getDecoder().decode(encoded)
            }
        }
    }.getOrNull()
    if (bytes == null) logger.warning("Custom App Output: String Handler icon input is not a valid Base64 image or HTTPS URL.")
    return acceptable(bytes)
}

private fun readIconAtMost(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (output.size() + read > MAX_ICON_BYTES) error("image exceeds ${MAX_ICON_BYTES / 1024 / 1024} MiB")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun iconExtension(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
        byteArrayOf((-119).toByte(), 80.toByte(), 78.toByte(), 71.toByte(), 13.toByte(), 10.toByte(), 26.toByte(), 10.toByte()),
    ) -> "png"
    bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "jpg"
    bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "webp"
    else -> null
}
