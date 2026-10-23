package unipatches.compatibility

import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.filePathOption
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.patch.stringsOption
import helpers.manifest.NS_ANDROID
import helpers.manifest.applicationOrNull
import helpers.startup.StartupHooks
import java.util.logging.Logger
import java.util.zip.ZipFile
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Manifest/resource half of Legacy App Compatibility. */
internal const val APACHE_LEGACY_LIB = "org.apache.http.legacy"
internal const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
internal const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
internal const val SCHEDULE_EXACT_ALARM = "android.permission.SCHEDULE_EXACT_ALARM"
internal const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
internal const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
internal const val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"
internal const val MIN_SUPPORTED_TARGET_SDK = 23
internal const val MAX_SUPPORTED_TARGET_SDK = 40
internal fun hasPermission(document: Document, name: String): Boolean =
    listOf("uses-permission", "uses-permission-sdk-23").any { tag ->
        val permissions = document.getElementsByTagName(tag)
        (0 until permissions.length).any {
            (permissions.item(it) as? Element)?.androidAttribute("name") == name
        }
    }

internal fun addPermission(document: Document, name: String, maxSdkVersion: Int? = null): Boolean {
    if (hasPermission(document, name)) return false
    val root = document.documentElement ?: return false
    val permission = document.createElement("uses-permission")
    permission.setAttributeNS(NS_ANDROID, "android:name", name)
    maxSdkVersion?.let { permission.setAttributeNS(NS_ANDROID, "android:maxSdkVersion", it.toString()) }
    root.appendChild(permission)
    return true
}

private fun Element.manifestLocalName(): String = localName ?: tagName.substringAfter(':')

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

private fun elementsNamed(parent: Element, name: String): List<Element> {
    val plain = parent.getElementsByTagName(name)
    val namespaced = parent.getElementsByTagNameNS("*", name)
    return ((0 until plain.length).mapNotNull { plain.item(it) as? Element } +
        (0 until namespaced.length).mapNotNull { namespaced.item(it) as? Element }).distinct()
}

private fun createManifestChild(document: Document, parent: Element, name: String): Element {
    val namespace = parent.namespaceURI
    val prefix = parent.prefix
    val qualifiedName = if (prefix.isNullOrEmpty()) name else "$prefix:$name"
    return if (namespace.isNullOrEmpty()) document.createElement(name) else document.createElementNS(namespace, qualifiedName)
}

private val packageNamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

internal fun normalizePackageVisibilityAllowlist(entries: List<String>): List<String> {
    val packages = linkedSetOf<String>()
    entries.forEach { entry ->
        val packageName = entry.trim()
        if (packageName.isEmpty()) return@forEach
        require(packageName.length <= 255 && packageNamePattern.matches(packageName)) {
            val safeName = packageName.filterNot { Character.isISOControl(it) }.take(100)
            "Legacy compatibility: invalid package name '$safeName' in Package Visibility Allowlist; enter exact names such as com.android.vending."
        }
        packages += packageName
    }
    return packages.toList()
}

internal fun hasPackageVisibilityQuery(document: Document, packageName: String): Boolean {
    val root = document.documentElement ?: return false
    return root.childElements().filter { it.manifestLocalName() == "queries" }.any { queries ->
        elementsNamed(queries, "package").any { it.androidAttribute("name") == packageName }
    }
}

internal fun addPackageVisibilityQuery(document: Document, packageName: String): Boolean {
    if (packageName.length > 255 || !packageNamePattern.matches(packageName)) return false
    val root = document.documentElement ?: return false
    val rootChildren = root.childElements()
    val queriesElements = rootChildren.filter { it.manifestLocalName() == "queries" }
    val application = rootChildren.firstOrNull { it.manifestLocalName() == "application" }
    if (application != null) {
        val applicationIndex = rootChildren.indexOf(application)
        require(queriesElements.all { rootChildren.indexOf(it) < applicationIndex }) {
            "Legacy compatibility: existing <queries> section follows <application>; fix manifest order before adding package visibility."
        }
    }
    if (queriesElements.any { queries -> elementsNamed(queries, "package").any { it.androidAttribute("name") == packageName } }) {
        return false
    }
    val queries = queriesElements.firstOrNull() ?: createManifestChild(document, root, "queries").also { element ->
        if (application == null) root.appendChild(element) else root.insertBefore(element, application)
    }
    createManifestChild(document, queries, "package").also {
        it.setAttributeNS(NS_ANDROID, "android:name", packageName)
        queries.appendChild(it)
    }
    return true
}

internal fun setApplicationAttribute(
    document: Document,
    name: String,
    value: String,
): Boolean {
    val application = document.documentElement.applicationOrNull() ?: return false
    if (application.androidAttribute(name) == value) return false
    application.setAndroidAttribute(name, value)
    return true
}

internal fun addLegacyReviver(document: Document, apache: Boolean, foreground: Boolean, alarms: Boolean, bluetooth: Boolean): Int {
    var changed = 0
    val application = document.documentElement.applicationOrNull()
    if (apache && application != null) {
        val libraries = application.getElementsByTagName("uses-library")
        val exists = (0 until libraries.length).any {
            (libraries.item(it) as? Element)?.androidAttribute("name") == APACHE_LEGACY_LIB
        }
        if (!exists) {
            document.createElement("uses-library").also {
                it.setAttributeNS(NS_ANDROID, "android:name", APACHE_LEGACY_LIB)
                it.setAttributeNS(NS_ANDROID, "android:required", "false")
                application.appendChild(it)
            }
            changed++
        }
    }
    if (foreground && addPermission(document, FOREGROUND_SERVICE)) changed++
    if (alarms && addPermission(document, SCHEDULE_EXACT_ALARM)) changed++
    if (bluetooth) {
        if (addPermission(document, BLUETOOTH_CONNECT)) changed++
        if (addPermission(document, BLUETOOTH_SCAN)) changed++
    }
    return changed
}

/** Returns the explicitly declared SDK attribute text, or null when absent. */
private fun originalSdkValue(document: Document, attribute: String): String? {
    val usesSdk = document.documentElement
        ?.childElements()
        ?.firstOrNull { it.manifestLocalName() == "uses-sdk" }
        ?: return null
    return when {
        usesSdk.hasAttributeNS(NS_ANDROID, attribute) ->
            usesSdk.getAttributeNS(NS_ANDROID, attribute)
        usesSdk.hasAttribute("android:$attribute") ->
            usesSdk.getAttribute("android:$attribute")
        usesSdk.hasAttribute(attribute) ->
            usesSdk.getAttribute(attribute)
        else -> null
    }
}

/** Returns the explicitly declared targetSdkVersion text, or null when absent. */
private fun originalTargetSdkValue(document: Document): String? =
    originalSdkValue(document, "targetSdkVersion")

/** Returns the original targetSdkVersion from the manifest, or null when absent or invalid. */
internal fun originalTargetSdk(document: Document): Int? = originalTargetSdkValue(document)?.toIntOrNull()

internal fun updateTargetSdk(document: Document, target: Int): Boolean =
    updateSdkVersion(document, "targetSdkVersion", target)

/**
 * Writes android:minSdkVersion. This is the attribute the package installer
 * gates on: PackageManager rejects the APK with INSTALL_FAILED_OLDER_SDK when
 * minSdkVersion is above the device's API level, regardless of targetSdkVersion.
 */
internal fun updateMinSdk(document: Document, min: Int): Boolean =
    updateSdkVersion(document, "minSdkVersion", min)

private fun updateSdkVersion(document: Document, attribute: String, value: Int): Boolean {
    val root = document.documentElement ?: return false
    if (root.manifestLocalName() != "manifest") return false
    val usesSdk = root.childElements().firstOrNull { it.manifestLocalName() == "uses-sdk" }
    if (usesSdk != null) {
        if (originalSdkValue(document, attribute) == value.toString()) return false
        if (usesSdk.hasAttributeNS(NS_ANDROID, attribute)) {
            usesSdk.setAttributeNS(NS_ANDROID, "android:$attribute", value.toString())
        } else if (usesSdk.hasAttribute("android:$attribute")) {
            usesSdk.setAttribute("android:$attribute", value.toString())
        } else if (usesSdk.hasAttribute(attribute)) {
            usesSdk.setAttribute(attribute, value.toString())
        } else {
            usesSdk.setAttributeNS(NS_ANDROID, "android:$attribute", value.toString())
        }
        return true
    }
    val created = createManifestChild(document, root, "uses-sdk")
    created.setAttributeNS(NS_ANDROID, "android:$attribute", value.toString())
    val application = root.childElements().firstOrNull { it.manifestLocalName() == "application" }
    root.insertBefore(created, application)
    return true
}

internal enum class LegacyTargetSdkOutcome {
    DISABLED,
    OUT_OF_RANGE,
    ALREADY_CURRENT,
    UPDATED,
}

internal data class LegacyTargetSdkUpdateResult(
    val originalTarget: Int?,
    val selectedTarget: Int?,
    val finalTarget: Int?,
    val outcome: LegacyTargetSdkOutcome,
)

internal fun applyLegacyTargetSdk(
    document: Document,
    spoofEnabled: Boolean,
    customTarget: Int?,
): LegacyTargetSdkUpdateResult {
    val original = originalTargetSdk(document)
    if (!spoofEnabled) {
        return LegacyTargetSdkUpdateResult(original, null, original, LegacyTargetSdkOutcome.DISABLED)
    }

    val selected = customTarget ?: 24
    if (selected !in MIN_SUPPORTED_TARGET_SDK..MAX_SUPPORTED_TARGET_SDK) {
        return LegacyTargetSdkUpdateResult(original, selected, original, LegacyTargetSdkOutcome.OUT_OF_RANGE)
    }

    val changed = updateTargetSdk(document, selected)
    val final = originalTargetSdk(document)
    check(final == selected) {
        "Legacy compatibility: target SDK readback mismatch; expected " + selected + " but found " + final
    }
    return LegacyTargetSdkUpdateResult(
        original,
        selected,
        final,
        if (changed) LegacyTargetSdkOutcome.UPDATED else LegacyTargetSdkOutcome.ALREADY_CURRENT,
    )
}

internal fun supportAllScreens(document: Document): Pair<Int, Boolean> {
    val root = document.documentElement ?: return 0 to false
    var removed = 0
    val compatible = document.getElementsByTagName("compatible-screens")
    for (index in compatible.length - 1 downTo 0) {
        compatible.item(index)?.parentNode?.removeChild(compatible.item(index))
        removed++
    }
    val application = root.applicationOrNull()
    val supports = document.getElementsByTagName("supports-screens")
    val element = if (supports.length > 0) {
        supports.item(0) as? Element
    } else {
        document.createElement("supports-screens").also { root.insertBefore(it, application) }
    } ?: return removed to false
    var changed = false
    for (size in listOf("smallScreens", "normalScreens", "largeScreens", "xlargeScreens")) {
        if (element.androidAttribute(size) != "true") {
            element.setAndroidAttribute(size, "true")
            changed = true
        }
    }
    if (element.androidAttribute("anyDensity") != "true") {
        element.setAndroidAttribute("anyDensity", "true")
        changed = true
    }
    return removed to (changed || supports.length == 0)
}

internal fun relaxSharedLibraries(document: Document): Int {
    var changed = 0
    val libraries = document.getElementsByTagName("uses-library")
    for (index in 0 until libraries.length) {
        val library = libraries.item(index) as? Element ?: continue
        if (library.androidAttribute("required") == "true") {
            library.setAndroidAttribute("required", "false")
            changed++
        }
    }
    return changed
}

internal val exportedComponentTags = listOf("activity", "activity-alias", "service", "receiver")

private fun Element.androidAttributeNode(name: String): org.w3c.dom.Attr? {
    getAttributeNodeNS(NS_ANDROID, name)?.let { return it }
    getAttributeNode("android:$name")?.let { return it }
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        val qualifiedName = attribute.nodeName
        val localName = attribute.localName ?: qualifiedName.substringAfter(':')
        val prefix = attribute.prefix ?: qualifiedName.substringBefore(':', "")
        if (localName == name && (attribute.namespaceURI == NS_ANDROID ||
                (prefix.isNotEmpty() && lookupNamespaceURI(prefix) == NS_ANDROID))
        ) return attribute as org.w3c.dom.Attr
    }
    return null
}

internal fun Element.androidAttribute(name: String): String = androidAttributeNode(name)?.value.orEmpty()

internal fun Element.hasAndroidAttribute(name: String): Boolean = androidAttributeNode(name) != null

private fun Element.setAndroidAttribute(name: String, value: String) {
    androidAttributeNode(name)?.let { removeAttributeNode(it) }
    setAttributeNS(NS_ANDROID, "android:$name", value)
}

internal fun Element.intentFilters(): List<Element> = elementsNamed(this, "intent-filter")

internal fun Element.hasIntentFilter(): Boolean = intentFilters().isNotEmpty()

internal fun Element.isLauncherComponent(): Boolean = intentFilters().any { filter ->
    val hasMainAction = elementsNamed(filter, "action").any {
        it.androidAttribute("name") == "android.intent.action.MAIN"
    }
    val hasLauncherCategory = elementsNamed(filter, "category").any {
        it.androidAttribute("name") == "android.intent.category.LAUNCHER"
    }
    hasMainAction && hasLauncherCategory
}

internal fun shouldRepairMissingExportFlags(enabled: Boolean, targetSdk: Int?): Boolean =
    enabled && targetSdk != null && targetSdk >= 31

internal data class MissingExportPolicy(val exported: Boolean)

internal fun missingExportPolicy(componentTag: String, isLauncher: Boolean): MissingExportPolicy =
    MissingExportPolicy(componentTag in setOf("activity", "activity-alias") && isLauncher)

internal fun repairMissingComponentExportFlags(document: Document, logger: Logger): Int {
    val root = document.documentElement ?: return 0
    var repaired = 0
    for (tagName in exportedComponentTags) {
        for (component in elementsNamed(root, tagName)) {
            if (component.hasAndroidAttribute("exported") || !component.hasIntentFilter()) continue

            val policy = missingExportPolicy(tagName, component.isLauncherComponent())
            component.setAttributeNS(NS_ANDROID, "android:exported", policy.exported.toString())
            repaired++
            logger.info(
                "Legacy compatibility: repaired ${component.androidAttribute("name").ifEmpty { "<unnamed>" }} with android:exported=${policy.exported}; unverified components fail closed.",
            )
        }
    }
    return repaired
}

internal fun exportAllActivities(document: Document, logger: Logger): Int {
    val root = document.documentElement ?: return 0
    var changed = 0
    var preservedFalseCount = 0
    for (tagName in listOf("activity", "activity-alias")) {
        for (activity in elementsNamed(root, tagName)) {
            if (!activity.hasIntentFilter()) continue
            if (activity.hasAndroidAttribute("exported")) {
                if (activity.androidAttribute("exported") == "false") preservedFalseCount++
                continue
            }
            activity.setAttributeNS(NS_ANDROID, "android:exported", "true")
            changed++
        }
    }
    if (preservedFalseCount > 0) {
        logger.warning("Legacy compatibility: Export All Activities preserved explicit android:exported=\"false\" on $preservedFalseCount component(s).")
    }
    return changed
}

internal fun legacyManifestPreflightFindings(
    document: Document,
    spoofTargetSdk: Boolean,
    customTargetSdk: Int?,
    repairExportFlags: Boolean,
    exportAllActivityComponents: Boolean,
    playStorePackageVisibility: Boolean,
    embedExpansionObb: Boolean,
    expansionObbPath: String?,
    deviceApi: Int? = currentAndroidApiLevel(),
    packageVisibilityAllowlist: List<String> = listOf("com.android.vending"),
): List<LegacyCompatibilityPreflightFinding> {
    val root = document.documentElement ?: return listOf(
        LegacyCompatibilityPreflightFinding("AndroidManifest.xml", false, "leave unchanged", "all", "unknown", "manifest root is missing"),
    )
    val packageName = root.getAttribute("package")
    val findings = mutableListOf<LegacyCompatibilityPreflightFinding>()
    val originalTargetValue = originalTargetSdkValue(document)
    val originalTarget = originalTargetValue?.toIntOrNull()
    val targetDeclared = originalTargetValue != null
    val targetInvalid = targetDeclared && originalTarget == null
    val proposedTarget = if (spoofTargetSdk) {
        customTargetSdk ?: 24
    } else {
        originalTarget
    }
    findings += LegacyCompatibilityPreflightFinding(
        pattern = "uses-sdk targetSdkVersion",
        detected = targetDeclared,
        proposal = when {
            spoofTargetSdk -> "set targetSdkVersion to $proposedTarget"
            targetDeclared -> "preserve the APK targetSdkVersion"
            else -> "leave targetSdkVersion unspecified (effective Spoof Target SDK value is disabled)"
        },
        apiRange = "Android API 23-40 supported by this option",
        confidence = if (targetDeclared) "exact manifest attribute" else "exact manifest absence",
        uncertainty = when {
            targetInvalid && !spoofTargetSdk -> "original targetSdkVersion is not a valid integer; the attribute is preserved because effective spoofing is disabled"
            targetInvalid -> "original targetSdkVersion is not a valid integer; selected numeric value replaces it with target $proposedTarget"
            !targetDeclared && !spoofTargetSdk -> "manifest has no explicit targetSdkVersion; selected target SDK was not applied because effective spoofing is disabled"
            !targetDeclared -> "original target is absent; fallback changes platform security behavior"
            else -> "target changes compatibility behavior and may reduce protections"
        },
    )
    val missingExported = exportedComponentTags.flatMap { elementsNamed(root, it) }
        .filter { it.hasIntentFilter() && !it.hasAndroidAttribute("exported") }
    if (missingExported.isNotEmpty() || repairExportFlags || exportAllActivityComponents) {
        val launcherCount = missingExported.count { it.isLauncherComponent() }
        val repairWillApply = shouldRepairMissingExportFlags(repairExportFlags, proposedTarget)
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "filtered components missing android:exported",
            detected = missingExported.isNotEmpty(),
            proposal = when {
                exportAllActivityComponents -> "set missing activity/alias flags true; preserve explicit values"
                repairWillApply -> "set launcher components true and other filtered components false for targetSdkVersion $proposedTarget"
                repairExportFlags && proposedTarget != null && proposedTarget < 31 -> "skip repair: targetSdkVersion $proposedTarget is below 31, where android:exported is not required"
                repairExportFlags -> "skip repair: targetSdkVersion is unknown; safe repair is only applied for target 31+"
                else -> "enable safe missing-export repair only if installation requires it"
            },
            apiRange = "Android 12/API 31+ when targetSdkVersion is 31+",
            confidence = "exact manifest element/filter/attribute scan",
            uncertainty = launcherCount.toString() + " launcher components among " + missingExported.size + " missing flags; external caller needs are not inferred",
        )
    }
    val existingQueries = root.childElements().filter { it.manifestLocalName() == "queries" }.flatMap { queries ->
        elementsNamed(queries, "package").mapNotNull { it.androidAttribute("name").takeIf(String::isNotBlank) }
    }.distinct()
    val selectedPackagesResult = if (playStorePackageVisibility) {
        runCatching { normalizePackageVisibilityAllowlist(packageVisibilityAllowlist) }
    } else {
        Result.success(emptyList())
    }
    val selectedPackages = selectedPackagesResult.getOrDefault(emptyList())
    if (playStorePackageVisibility || existingQueries.isNotEmpty()) {
        val invalidList = selectedPackagesResult.exceptionOrNull()?.message
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "package visibility allowlist",
            detected = existingQueries.isNotEmpty(),
            proposal = when {
                invalidList != null -> "fix invalid Package Visibility Allowlist entries before applying package queries"
                !playStorePackageVisibility -> "preserve existing scoped queries; disabled allowlist option adds none"
                proposedTarget == null || proposedTarget < 30 -> "raise effective targetSdk to 30+ before adding scoped queries"
                selectedPackages.isEmpty() -> "add at least one exact package name to Package Visibility Allowlist"
                else -> "add scoped queries for ${selectedPackages.joinToString()}"
            },
            apiRange = "Android 11/API 30+ and effective targetSdk 30+",
            confidence = "exact manifest query scan and selected allowlist",
            uncertainty = invalidList ?: "queries reveal package visibility only; they do not make implicit service intents explicit",
        )
    }
    findings += legacyApiCompatibilityFindings(document, deviceApi, originalTarget, proposedTarget)
    val obbPath = expansionObbPath.orEmpty().trim()
    if (embedExpansionObb || obbPath.isNotEmpty()) {
        val manifestVersionCode = root.androidAttribute("versionCode")
            .ifEmpty { root.getAttribute("android:versionCode") }
            .ifBlank { null }
        val validationError = try {
            validateExpansionObbInput(obbPath, packageName, manifestVersionCode)
            null
        } catch (error: IllegalArgumentException) {
            error.message ?: "Legacy compatibility: OBB validation failed."
        }
        val valid = validationError == null
        val obbName = java.io.File(obbPath).name
        val versionHint = manifestVersionCode?.let { " versionCode $it" }.orEmpty()
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "embedded expansion OBB",
            detected = valid,
            proposal = when {
                valid && embedExpansionObb -> "embed and stage " + obbName + " with runtime checksum verification"
                valid -> "OBB input validates; install separately at <external-storage>/Android/obb/$packageName/$obbName"
                else -> validationError
            },
            apiRange = "all supported Android versions",
            confidence = if (valid) "exact path, filename, package, version code, archive, and entries" else "apply-time OBB validator rejected input",
            uncertainty = if (valid) "device OBB publication is verified at runtime" else validationError.orEmpty(),
        )
    }
    return findings
}

@Suppress("unused")
val legacyAppCompatibilityPatch = resourcePatch(
    name = "Legacy App Compatibility Patch ( Experimental, Enhanced )",
    description = """
        Improves older apps and games on modern Android with opt-in manifest, storage, display,
        file-sharing, network, native-library, OpenIAB, and OBB fixes. Includes target SDK controls,
        diagnostics, API-gated package visibility and foreground-service types, selected-domain
        cleartext, and FileProvider sharing for app-private files.

        Presets add ordinary fixes; Custom booleans start off. Ambiguous receiver calls are skipped.
        Native checks reject APK library collisions during relocation and DT_TEXTREL for target SDK 23+.
        With LicenseChecker, keep standard ExpansionDownloader launcher and run LVL before
        staged OBB validation. Deny/error
        blocks Unity; unsupported flows fail closed. Target SDK spoofing cannot repair code.

        Does not bypass Google licensing or Play Services. Use Control Embedded Auth / Stores Patch
        for those. It cannot restore stopped servers, unsupported ABIs, server licensing,
        Play Integrity, or incompatible native code.

        Credits: Nai64Patches from Nai64 for the original compatibility functionality. LSPosed for
        the Hidden API bypass approach and AndroidHiddenApiBypass (Apache-2.0).
    """.trimIndent(),
    default = false,
) {
    try { category("Legacy App Compatibility") } catch (_: NoSuchMethodError) {}

    val preset by stringOption(
        title = "Legacy App Compatibility > Preset",
        key = "legacyCompatibilityPreset",
        default = LegacyCompatibilityPresets.CUSTOM,
        description = "Custom (default) uses your individual settings; boolean options default off and OpenIAB defaults to automatic. Android 4.x through Android 14+ describe the APK's original era, not your device. Era presets add receiver and missing-export repairs plus relevant Apache HTTP, foreground-service and Bluetooth declarations. User-enabled choices remain enabled. Presets never change your target SDK controls, OpenIAB mode, OBB options, paths, numbers, text, or explicit high-risk opt-ins. These settings remain available with every preset.",
        values = LegacyCompatibilityPresets.options,
    )

    val diagnosticsOnlyRaw by booleanOption(
        title = "Legacy App Compatibility > Diagnostics > Preflight Only (No APK Changes)",
        default = false,
        key = "legacyCompatibilityDiagnosticsOnly",
        description = "Scan the manifest and recognized bytecode fingerprints, report proposed fixes, API ranges, and uncertainty, but do not modify the APK. Rerun with this off to apply selected fixes.",
    )

    val spoofTargetSdkRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Spoof Target SDK",
        default = false,
        key = "legacyCompatibilitySpoofTargetSdk",
        description = "Try this if Android blocks an older app from installing or launching because its target SDK is too old. Lowering the target can restore older Android behavior and reduce platform protections; it cannot fix incompatible app code. Leave off unless you have this specific problem.",
    )
    val targetSdkRaw by intOption(
        title = "Legacy App Compatibility > Installation and manifest > Target SDK version",
        default = 24,
        key = "legacyCompatibilityTargetSdk",
        description = "Changes which Android version this app declares it was designed for. This may help install older apps, but does not upgrade their code or guarantee compatibility. Higher values can activate newer Android restrictions. Examples: 24 = Android 7.0; 27 = Android 8.1; 29 = Android 10; 34 = Android 14. Use the lowest value required by your device’s installation error. Ignored when Spoof Target SDK is disabled. Accepted range: 23–40; default: 24.",
    )
    val spoofMinSdk by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Spoof Min SDK",
        default = false,
        key = "legacyCompatibilitySpoofMinSdk",
        description = "Rewrite android:minSdkVersion. This is the attribute the package installer gates on, so it is what clears INSTALL_FAILED_OLDER_SDK on an older device. Lowering it does not repair code that calls newer platform APIs.",
    )
    val minSdk by intOption(
        title = "Legacy App Compatibility > Installation and manifest > Min SDK version",
        default = 28,
        key = "legacyCompatibilityMinSdk",
        description = "Min SDK to write when Spoof Min SDK is enabled. Set this to the API level of the device you are installing on (28 = Android 9). Default: 28.",
    )
    val legacyReviverRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Legacy App Reviver",
        default = false,
        key = "legacyCompatibilityReviver",
        description = "Turn this on to add the compatibility declarations selected below. Leave it off if the app does not need them; enabling declarations does not grant runtime permissions or guarantee the app will work.",
    )
    val apacheLegacyRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Apache HTTP legacy library",
        default = false,
        key = "legacyCompatibilityApacheLegacy",
        description = "Use when the app crashes or fails to start because it uses the removed Apache HttpClient library. Adds that library as optional; it will not help if the app needs other missing libraries. Requires Legacy App Reviver.",
    )
    val foregroundServiceRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Foreground service permission",
        default = false,
        key = "legacyCompatibilityForegroundService",
        description = "Use when an older app starts a foreground service and fails because its manifest lacks this permission. This only declares permission; Android may require additional service permissions or user-visible behavior. Requires Legacy App Reviver.",
    )
    val exactAlarmsRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Exact alarm permission",
        default = false,
        key = "legacyCompatibilityExactAlarms",
        description = "Use only if the app's reminders or scheduled actions must run at an exact time and are blocked by the missing declaration. Android can still require user approval, and this does not grant that approval. Requires Legacy App Reviver.",
    )
    val bluetoothRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Bluetooth permissions",
        default = false,
        key = "legacyCompatibilityBluetooth",
        description = "Use when the app needs Bluetooth discovery or connections on newer Android. The manifest declarations do not grant runtime access; Android may still show permission prompts. Requires Legacy App Reviver.",
    )
    val openIabReceiverRegistrationModeRaw by stringOption(
        title = "Legacy App Compatibility > Runtime compatibility > Fix OpenIAB dynamic receiver registration",
        default = OPEN_IAB_AUTOMATIC,
        key = "legacyCompatibilityOpenIabReceiverRegistrationMode",
        description = "Choose Automatic (recommended) if OpenIAB billing broadcasts fail: it patches only a recognized UnityPlugin receiver setup and skips uncertain cases. Disabled leaves it unchanged. Force applies the fix even when automatic checks cannot confirm the setup; this may stop external-store billing broadcasts, so use only to diagnose a known OpenIAB issue.",
        values = linkedMapOf(
            "Automatic (recommended)" to OPEN_IAB_AUTOMATIC,
            "Disabled" to OPEN_IAB_DISABLED,
            "Force OpenIAB receiver fix" to OPEN_IAB_FORCE,
        ),
    )
    val bypassHiddenApiRaw by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Bypass Hidden API Restrictions",
        default = false,
        key = "legacyCompatibilityHiddenApi",
        description = "Try this only if the app fails because it uses hidden Android framework APIs through reflection. It bypasses the non-SDK API restriction for this app's process on Android 9+, which can reduce stability and security; leave off otherwise. Takes effect at app startup.",
    )
    val trustCertificatesRaw by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Trust All Certificates",
        default = false,
        key = "legacyCompatibilityTrustCertificates",
        description = "Last-resort workaround if this app cannot connect because its server uses an expired or self-signed certificate. It disables certificate and hostname checks for HttpsURLConnection, so attackers on the network could read or alter traffic. Do not use for sensitive accounts or payments. Does not affect WebView; requires the separate risk acknowledgement.",
    )
    val acknowledgeTrustCertificatesRaw by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Acknowledge Trust-All TLS Risk",
        default = false,
        key = "legacyCompatibilityAcknowledgeTrustCertificates",
        description = "Enable only after understanding that Trust All Certificates allows network interception by disabling TLS checks. It is required for that option to take effect; leave both off unless diagnosing a certificate problem.",
    )
    val redirectLegacyStorageRaw by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Redirect Legacy External Storage Paths",
        default = false,
        key = "legacyCompatibilityRedirectLegacyStorage",
        description = "Try this if the app crashes or loses saves because it writes directly to the shared-storage root. Those API calls are redirected to app-specific storage, which can break shared-file access and OBB paths. Leave off if the app relies on files visible to other apps or uses conventional OBB directories.",
    )
    val repairFileUriSharingRaw by booleanOption(
        title = "Legacy App Compatibility > File sharing > Repair file:// shares for modern Android",
        default = false,
        key = "legacyCompatibilityRepairFileUriSharing",
        description = "Opt in only when Android blocks an app's file:// Intent share. Rewrites only verified Uri.fromFile calls passed to Intent.setData, setDataAndType, or EXTRA_STREAM; unrecognized call sites are skipped. Shares only files from app-private storage through a non-exported provider. Provider setup or unsafe paths fail closed.",
    )
    val receiverFixAppWideRaw by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Fix Dynamic Receiver Registrations (App Wide)",
        default = false,
        key = "legacyCompatibilityReceiverFixAppWide",
        description = "Try this if receiver registration crashes on Android 13+. Each call is classified from its statically resolved IntentFilter actions: app-only calls are private, known system/store actions stay exported, and ambiguous calls are skipped with a warning. The fix can miss dynamic filters or expose exported receivers to other apps; OpenIAB is handled separately.",
    )
    val repairExportFlagsRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Repair Missing Component Export Flags",
        default = false,
        key = "legacyCompatibilityRepairExportFlags",
        description = "Use if Android refuses to install the APK because a component with an intent filter has no exported setting. The patch fills only missing values; exported components can be launched by other apps, so enable only to fix that install error. Do not combine with Export All Activities; if both are on, that option takes precedence.",
    )
    val exportAllActivityComponentsRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Export All Activities",
        default = false,
        key = "legacyCompatibilityExportAllActivities",
        description = "Use only if a launcher activity is not visible or Android rejects the APK because an activity or alias with an intent filter lacks an exported setting. It exposes those matching components to other apps; existing explicit values are preserved. Do not combine with Repair Missing Component Export Flags.",
    )
    val allowCleartextRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Allow Cleartext Traffic Everywhere",
        default = false,
        key = "legacyCompatibilityAllowCleartext",
        description = "Global fallback: allow unencrypted HTTP to every host. Traffic can be read or changed on the network. Never use for logins, payments, or other sensitive data. Mutually exclusive with selected-domain cleartext.",
    )
    val allowSelectedCleartextDomainsRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Allow Cleartext for Selected Domains",
        default = false,
        key = "legacyCompatibilityAllowSelectedCleartextDomains",
        description = "Allow unencrypted HTTP only for listed hostnames using Android network security config (API 24+). No presets enable this. Mutually exclusive with the global fallback.",
    )
    val cleartextDomainsRaw by stringsOption(
        title = "Legacy App Compatibility > Installation and manifest > Cleartext Domains",
        default = emptyList(),
        key = "legacyCompatibilityCleartextDomains",
        description = "Exact hostnames allowed to use unencrypted HTTP, one per entry. No URLs, paths, ports, or wildcards. Example: api.example.com.",
    )
    val applyForegroundServiceTypesRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Apply Foreground Service Types",
        default = false,
        key = "legacyCompatibilityApplyForegroundServiceTypes",
        description = "Opt in only when logs show Android rejects an existing foreground service because its type is missing. Add explicit Service=Type mappings; the patch adds only matching FGS permissions, never guesses a type or adds runtime permissions. Confirm runtime/user-consent prerequisites (camera, location, microphone, media projection) separately. specialUse and systemExempted are rejected because app-specific eligibility cannot be verified.",
    )
    val foregroundServiceTypeMappingsRaw by stringsOption(
        title = "Legacy App Compatibility > Installation and manifest > Foreground Service Type Mappings",
        default = emptyList(),
        key = "legacyCompatibilityForegroundServiceTypeMappings",
        description = "One mapping per service: com.example.SyncService=dataSync. Use | to combine types for one service. Relative names are resolved against the app package. phoneCall requires a predeclared MANAGE_OWN_CALLS permission; mappings are never preset-generated.",
    )
    val relaxLibrariesRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Relax Shared Libraries",
        default = false,
        key = "legacyCompatibilityRelaxLibraries",
        description = "Use if Android will not install the app because an optional device library is missing. This lets installation continue, but the app may crash or lose features if it truly needs that library.",
    )
    val playStorePackageVisibilityRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Enable Package Visibility Allowlist",
        default = false,
        key = "legacyCompatibilityPlayStoreVisibility",
        description = "Request visibility only for packages in Package Visibility Allowlist. Android 11/API 30+ presets enable this toggle; effective targetSdk must also be 30+. No broad QUERY_ALL_PACKAGES permission is added.",
    )
    val packageVisibilityAllowlistRaw by stringsOption(
        title = "Legacy App Compatibility > Installation and manifest > Package Visibility Allowlist",
        default = listOf("com.android.vending"),
        key = "legacyCompatibilityPackageVisibilityAllowlist",
        description = "Exact package names this app must query, one per entry. Add only packages the app genuinely checks, such as com.google.android.gms. Presets never change this list.",
    )
    val manifestCompatAttributesRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Extra Manifest Compatibility Attributes",
        default = false,
        key = "legacyCompatibilityManifestAttributes",
        description = "Try if the app runs out of memory or shows a blank screen: enables a larger heap and hardware-accelerated drawing. The larger heap can increase memory pressure, and GPU drawing may break apps that depend on software rendering; leave off if the app already displays and runs correctly.",
    )
    val legacyStorageRaw by booleanOption(
        title = "Legacy App Compatibility > Storage and display > Legacy External Storage",
        default = false,
        key = "legacyCompatibilityLegacyStorage",
        description = "Use if an older app cannot read or save files because it expects the Android 10 shared-storage model. Android 11+ may ignore this request, and it does not bypass newer storage restrictions.",
    )
    val expansionObbPathRaw by filePathOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Expansion OBB file",
        default = "",
        key = "legacyCompatibilityExpansionObbPath",
        allowedExtensions = listOf("obb"),
        description = "Select the app's matching main or patch .obb file only when it fails to start because its Play expansion data is missing. Use the exact version and package naming expected by the app; leave empty if it does not need an OBB.",
    )
    val relocateExpansionNativeLibrariesRaw by booleanOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Relocate native libraries from OBB",
        default = false,
        key = "legacyCompatibilityRelocateExpansionNativeLibraries",
        description = "Use if native .so files are stored inside the selected OBB. Copies full libraries into APK ABI folders when safe; for a verified *_proxy.so pair, preserves the APK proxy and stages the full library at its expected assets/libs path. Unknown collisions fail instead of overwriting existing libraries.",
    )
    val removeRelocatedNativeLibrariesFromObbRaw by booleanOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Remove relocated native libraries from embedded OBB",
        default = false,
        key = "legacyCompatibilityRemoveRelocatedNativeLibrariesFromObb",
        description = "Use together with library relocation and OBB embedding to avoid storing the same native libraries twice. It removes those copied .so entries from the APK's embedded OBB only; the selected source OBB file is not changed.",
    )
    val embedExpansionObbRaw by booleanOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Embed and stage expansion OBB",
        default = false,
        key = "legacyCompatibilityEmbedExpansionObb",
        description = "Use when the app needs an OBB at first launch. The file is bundled inside the APK and copied to the expected OBB folder; this can greatly increase APK size. If a LicenseChecker exists, only the recognized Google ExpansionDownloader flow is supported: its original LVL service check is forced before OBB validation, and deny/error blocks Unity. Unsupported flows fail closed.",
    )
    val bypassExpansionDownloaderRaw by booleanOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Bypass known Unity expansion downloader",
        default = false,
        key = "legacyCompatibilityBypassExpansionDownloader",
        description = "Use only for the standard Google ExpansionDownloader when bundled OBB data should skip its download UI. Requires Embed and stage expansion OBB. If LicenseChecker exists, DownloaderActivity remains launcher, existing LVL service check runs first, download controls stay hidden during that check, and only allow continues to OBB validation/Unity. Deny/error blocks launch; unsupported flows fail closed.",
    )
    val allScreensRaw by booleanOption(
        title = "Legacy App Compatibility > Storage and display > Support All Screens",
        default = false,
        key = "legacyCompatibilityAllScreens",
        description = "Enable so Android can install the app on phones and tablets whose screen size or density is not listed in its original manifest. This changes compatibility declarations, not the app's layout; some screens may still display poorly.",
    )
    val extractNativeLibsRaw by booleanOption(
        title = "Legacy App Compatibility > Native runtime > Extract native libraries",
        default = false,
        key = "legacyCompatibilityExtractNativeLibs",
        description = "Use if an older game cannot load its native .so files from the APK. Android will extract those libraries during installation, using more device storage; leave off if native libraries already load normally.",
    )
    val disableHeapTaggingRaw by booleanOption(
        title = "Legacy App Compatibility > Native runtime > Disable Heap Pointer Tagging",
        default = false,
        key = "legacyCompatibilityDisableHeapTagging",
        description = "Try this if an older native game crashes on startup with a memory/tagging-related error on newer Android. It changes native memory behavior for compatibility; leave off if there is no such crash.",
    )
    val vmSafeModeRaw by booleanOption(
        title = "Legacy App Compatibility > Native runtime > VM Safe Mode",
        default = false,
        key = "legacyCompatibilityVmSafeMode",
        description = "Try this only if an older app crashes or misbehaves because of Android runtime compilation. Disabling selected optimizations can make the app slower; leave off unless testing shows it fixes the problem.",
    )
    val spoofImeiRaw by booleanOption(
        title = "Legacy App Compatibility > Device compatibility > Spoof IMEI",
        default = false,
        key = "legacyCompatibilitySpoofImei",
        description = "Use only if the app refuses to run because it expects an IMEI value. Replaces recognized IMEI getter results with the value below; only matching calls are changed, and apps may use other identifiers instead. Avoid using an identifier that belongs to another device.",
    )
    val imeiRaw by stringOption(
        title = "Legacy App Compatibility > Device compatibility > IMEI value",
        default = "000000000000000",
        key = "legacyCompatibilityImei",
        description = "Enter exactly 15 digits to return from the IMEI calls affected by Spoof IMEI. Invalid values are rejected. This is an app-compatibility override, not a real device identifier.",
    )

    // Read Manager-supplied options at execution time, not while building dependencies.
    fun <T> option(read: () -> T) = kotlin.properties.ReadOnlyProperty<Any?, T> { _, _ -> read() }

    val detectedEngines = LegacyDetectedEngines()
    dependsOn(legacyEngineDetectionPatch(detectedEngines))
    val presetId by option { LegacyCompatibilityPresets.validate(preset ?: LegacyCompatibilityPresets.CUSTOM) }
    val diagnosticsOnly by option { diagnosticsOnlyRaw == true }
    val spoofTargetSdk by option { spoofTargetSdkRaw == true }
    val targetSdk by option { targetSdkRaw }
    val legacyReviver by option { LegacyCompatibilityPresets.boolean(presetId, "legacyReviver", legacyReviverRaw) }
    val apacheLegacy by option { LegacyCompatibilityPresets.boolean(presetId, "apacheLegacy", apacheLegacyRaw) }
    val foregroundService by option { LegacyCompatibilityPresets.boolean(presetId, "foregroundService", foregroundServiceRaw) }
    val exactAlarms by option { exactAlarmsRaw == true }
    val bluetooth by option { LegacyCompatibilityPresets.boolean(presetId, "bluetooth", bluetoothRaw) }
    val openIabReceiverRegistrationMode by option { openIabReceiverRegistrationModeRaw ?: OPEN_IAB_AUTOMATIC }
    val bypassHiddenApi by option { bypassHiddenApiRaw == true }
    val trustCertificates by option { trustCertificatesRaw == true }
    val acknowledgeTrustCertificates by option { acknowledgeTrustCertificatesRaw == true }
    val redirectLegacyStorage by option { redirectLegacyStorageRaw == true }
    val repairFileUriSharing by option { repairFileUriSharingRaw == true }
    val receiverFixAppWide by option { LegacyCompatibilityPresets.boolean(presetId, "receiverFixAppWide", receiverFixAppWideRaw) }
    val repairExportFlags by option { LegacyCompatibilityPresets.boolean(presetId, "repairExportFlags", repairExportFlagsRaw) }
    val exportAllActivityComponents by option { exportAllActivityComponentsRaw == true }
    val allowCleartext by option { allowCleartextRaw == true }
    val allowSelectedCleartextDomains by option { allowSelectedCleartextDomainsRaw == true }
    val cleartextDomains by option { cleartextDomainsRaw.orEmpty() }
    val applyForegroundServiceTypes by option { applyForegroundServiceTypesRaw == true }
    val foregroundServiceTypeMappings by option { foregroundServiceTypeMappingsRaw.orEmpty() }
    val relaxLibraries by option { relaxLibrariesRaw == true }
    val packageVisibilityAllowlist by option { packageVisibilityAllowlistRaw.orEmpty() }
    val manifestCompatAttributes by option { manifestCompatAttributesRaw == true }
    val legacyStorage by option { legacyStorageRaw == true }
    val expansionObbPath by option { expansionObbPathRaw }
    val relocateExpansionNativeLibraries by option { relocateExpansionNativeLibrariesRaw == true }
    val removeRelocatedNativeLibrariesFromObb by option { removeRelocatedNativeLibrariesFromObbRaw == true }
    val embedExpansionObb by option { embedExpansionObbRaw == true }
    val bypassExpansionDownloader by option { bypassExpansionDownloaderRaw == true }
    val expansionLauncherTarget = LegacyExpansionLauncherTarget()
    val expansionLicenseGate = LegacyExpansionDownloaderLicenseGateResult()
    val allScreens by option { allScreensRaw == true }
    val extractNativeLibs by option { extractNativeLibsRaw == true }
    val disableHeapTagging by option { disableHeapTaggingRaw == true }
    val vmSafeMode by option { vmSafeModeRaw == true }
    val spoofImei by option { spoofImeiRaw == true }
    val imei by option { imeiRaw }

    dependsOn(legacyImeiPatch { Pair(!diagnosticsOnly && spoofImei, imei.orEmpty().trim()) })
    dependsOn(openIabReceiverFlagsPatch { if (diagnosticsOnly) OPEN_IAB_DISABLED else openIabReceiverRegistrationMode })
    dependsOn(legacyExpansionDownloaderLicenseGatePatch(expansionLicenseGate) {
        !diagnosticsOnly && (embedExpansionObb || bypassExpansionDownloader)
    })
    dependsOn(legacyExpansionLauncherTargetPatch(expansionLauncherTarget) { bypassExpansionDownloader && !expansionLicenseGate.verified })
    dependsOn(legacyExpansionFilesPatch {
        LegacyExpansionOptions(
            obbPath = expansionObbPath.orEmpty().trim(),
            relocateNativeLibraries = !diagnosticsOnly && relocateExpansionNativeLibraries,
            embedExpansionObb = !diagnosticsOnly && embedExpansionObb,
            removeRelocatedNativeLibrariesFromObb = !diagnosticsOnly && removeRelocatedNativeLibrariesFromObb,
        )
    })
    val fileUriSharingResult = LegacyFileUriSharingResult()
    dependsOn(legacyFileUriSharingPatch(fileUriSharingResult) { !diagnosticsOnly && repairFileUriSharing })
    dependsOn(legacyRuntimeHooksPatch {
        LegacyRuntimeOptions(
            hiddenApiExemptions = !diagnosticsOnly && bypassHiddenApi,
            trustCertificates = !diagnosticsOnly && trustCertificates && acknowledgeTrustCertificates,
            storageRedirect = !diagnosticsOnly && redirectLegacyStorage,
            embeddedExpansionObb = !diagnosticsOnly && embedExpansionObb && expansionObbPath.orEmpty().trim().isNotEmpty(),
            expansionDownloaderBypass = !diagnosticsOnly && bypassExpansionDownloader && embedExpansionObb && expansionObbPath.orEmpty().trim().isNotEmpty(),
            fileUriSharing = !diagnosticsOnly && repairFileUriSharing,
        )
    })
    dependsOn(legacyReceiverFlagsPatch { !diagnosticsOnly && receiverFixAppWide })
    dependsOn(legacyDownloaderPendingIntentPatch { !diagnosticsOnly })
    dependsOn(
        legacyLicenseServiceIntentPatch(
            enabledProvider = { !diagnosticsOnly },
            bypassExpansionDownloaderProvider = {
                bypassExpansionDownloader == true && detectedEngines.unity &&
                    embedExpansionObb == true && expansionObbPath.orEmpty().trim().isNotEmpty()
            },
            embeddedExpansionObbProvider = { embedExpansionObb == true },
            verifiedExpansionLicenseGateProvider = { expansionLicenseGate.verified },
            diagnosticsOnlyProvider = { diagnosticsOnly },
        ),
    )
    dependsOn(legacyCompatibilityBytecodePreflightPatch(enabledProvider = { diagnosticsOnly }))

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val playStorePackageVisibility = LegacyCompatibilityPresets.boolean(
            presetId,
            "playStorePackageVisibility",
            playStorePackageVisibilityRaw == true,
        )
        val playStoreQueryReason = if (playStorePackageVisibility) "explicit user selection" else "not requested"
        if (preset in LegacyCompatibilityPresets.removedIds) {
            logger.warning("Legacy compatibility: removed preset $preset migrated to Custom; individual settings preserved.")
        }
        val implementationIdentity = LegacyCompatibilityBuildIdentity.current()
        val obbPathForLog = expansionObbPath.orEmpty().trim()
        fun optionLogValue(value: Any?): String =
            value?.toString()?.filterNot { Character.isISOControl(it) }?.take(300) ?: "<unset>"
        fun obbPathLogValue(value: String): String =
            value.takeIf { it.isNotBlank() }?.substringAfterLast('/')?.let(::optionLogValue) ?: "<not set>"

        logger.info("Legacy compatibility metadata: implementationFingerprintScope=${optionLogValue(implementationIdentity.scope)} implementationFingerprintSha256=${optionLogValue(implementationIdentity.sha256)} detectedUnity=${detectedEngines.unity} detectedOpenIab=${detectedEngines.openIab}")
        logger.info(
            buildString {
                appendLine("Legacy App Compatibility options:")
                appendLine("  patchEnabled=true")
                appendLine("  preset=${optionLogValue(presetId)}")
                appendLine("  diagnosticsOnly=${optionLogValue(diagnosticsOnly)}")
                appendLine("  spoofTargetSdk=${optionLogValue(spoofTargetSdk)}")
                appendLine("  targetSdk=${optionLogValue(targetSdk)}")
                appendLine("  legacyReviver=${optionLogValue(legacyReviver)}")
                appendLine("  apacheLegacy=${optionLogValue(apacheLegacy)}")
                appendLine("  foregroundService=${optionLogValue(foregroundService)}")
                appendLine("  exactAlarms=${optionLogValue(exactAlarms)}")
                appendLine("  bluetooth=${optionLogValue(bluetooth)}")
                appendLine("  openIabReceiverRegistrationMode=${optionLogValue(openIabReceiverRegistrationMode)}")
                appendLine("  bypassHiddenApi=${optionLogValue(bypassHiddenApi)}")
                appendLine("  trustCertificates=${trustCertificates && acknowledgeTrustCertificates}")
                appendLine("  acknowledgeTrustCertificates=${optionLogValue(acknowledgeTrustCertificates)}")
                appendLine("  redirectLegacyStorage=${optionLogValue(redirectLegacyStorage)}")
                appendLine("  repairFileUriSharing=${optionLogValue(repairFileUriSharing)}")
                appendLine("  receiverFixAppWide=${optionLogValue(receiverFixAppWide)}")
                appendLine("  repairExportFlags=${optionLogValue(repairExportFlags)}")
                appendLine("  exportAllActivityComponents=${optionLogValue(exportAllActivityComponents)}")
                appendLine("  allowCleartext=${optionLogValue(allowCleartext)}")
                appendLine("  relaxLibraries=${optionLogValue(relaxLibraries)}")
                appendLine("  playStorePackageVisibility=${optionLogValue(playStorePackageVisibility)} reason=${optionLogValue(playStoreQueryReason)}")
                appendLine("  allowSelectedCleartextDomains=${optionLogValue(allowSelectedCleartextDomains)}")
                appendLine("  applyForegroundServiceTypes=${optionLogValue(applyForegroundServiceTypes)} mappingCount=${foregroundServiceTypeMappings.size}")
                appendLine("  manifestCompatAttributes=${optionLogValue(manifestCompatAttributes)}")
                appendLine("  legacyStorage=${optionLogValue(legacyStorage)}")
                appendLine("  expansionObbPath=${optionLogValue(obbPathLogValue(obbPathForLog))} (directory redacted)")
                appendLine("  relocateExpansionNativeLibraries=${optionLogValue(relocateExpansionNativeLibraries)}")
                appendLine("  removeRelocatedNativeLibrariesFromObb=${optionLogValue(removeRelocatedNativeLibrariesFromObb)}")
                appendLine("  embedExpansionObb=${optionLogValue(embedExpansionObb)}")
                appendLine("  bypassExpansionDownloader=${optionLogValue(bypassExpansionDownloader)}")
                appendLine("  allScreens=${optionLogValue(allScreens)}")
                appendLine("  extractNativeLibs=${optionLogValue(extractNativeLibs)}")
                appendLine("  disableHeapTagging=${optionLogValue(disableHeapTagging)}")
                appendLine("  vmSafeMode=${optionLogValue(vmSafeMode)}")
                appendLine("  spoofImei=${optionLogValue(spoofImei)}")
                appendLine("  imei=<redacted; configured=${imei.orEmpty().trim().isNotEmpty()}>")
            },
        )

        if (trustCertificates == true && acknowledgeTrustCertificates != true) {
            logger.warning("Legacy compatibility: Trust All Certificates was requested without the required high-risk acknowledgement; trust-all TLS remains disabled.")
        }
        val selectedPackageVisibility = if (playStorePackageVisibility == true) {
            normalizePackageVisibilityAllowlist(packageVisibilityAllowlist)
        } else emptyList()
        fun reportManifestPreflight(manifest: Document) {
            legacyManifestPreflightFindings(
                document = manifest,
                spoofTargetSdk = spoofTargetSdk == true,
                customTargetSdk = targetSdk,
                repairExportFlags = repairExportFlags == true,
                exportAllActivityComponents = exportAllActivityComponents == true,
                playStorePackageVisibility = playStorePackageVisibility == true,
                embedExpansionObb = embedExpansionObb == true,
                expansionObbPath = obbPathForLog,
                deviceApi = currentAndroidApiLevel(),
                packageVisibilityAllowlist = selectedPackageVisibility,
            ).forEach { logger.info(formatLegacyCompatibilityPreflightFinding(it)) }
        }

        validateEmbeddedExpansionObbSelection(embedExpansionObb == true, obbPathForLog)
        validateExpansionObbRelocationOptions(
            relocate = relocateExpansionNativeLibraries == true,
            embed = embedExpansionObb == true,
            removeFromObb = removeRelocatedNativeLibrariesFromObb == true,
        )
        validateCleartextModes(
            globalEnabled = allowCleartext == true,
            domainScopedEnabled = allowSelectedCleartextDomains == true,
        )
        val selectedCleartextDomains = if (allowSelectedCleartextDomains == true) {
            normalizeCleartextDomains(cleartextDomains)
        } else {
            emptyList()
        }
        validateCleartextDomainSelection(allowSelectedCleartextDomains == true, selectedCleartextDomains)
        validateExpansionDownloaderBypass(
            selected = bypassExpansionDownloader == true,
            unityDetected = detectedEngines.unity,
            obbEmbeddingSelected = embedExpansionObb == true,
            obbPath = obbPathForLog,
            launcherActivity = expansionLauncherTarget.activityName,
            verifiedLicenseGate = expansionLicenseGate.verified,
        )
        val selectedForegroundMappings = if (applyForegroundServiceTypes == true) {
            get("AndroidManifest.xml", false).inputStream().use { input ->
                document(input).use { manifest ->
                    val mappings = normalizeForegroundServiceTypeMappings(
                        foregroundServiceTypeMappings,
                        manifest.documentElement.getAttribute("package"),
                        declaredManifestPermissionNames(manifest),
                    )
                    if (mappings.isNotEmpty()) {
                        applyForegroundServiceTypeMappings(manifest.cloneNode(true) as Document, mappings)
                    }
                    mappings
                }
            }
        } else emptyList()
        validateForegroundServiceMappingSelection(applyForegroundServiceTypes == true, selectedForegroundMappings)

        val nativeManifest = readSecureXml(get("AndroidManifest.xml", false))
        val visibilityTarget = if (spoofTargetSdk == true) {
            plannedNativeTargetSdk(true, targetSdk)
        } else {
            originalTargetSdkValue(nativeManifest)?.toIntOrNull()
        }
        val frameworkRequirements = buildList {
            if (playStorePackageVisibility == true && selectedPackageVisibility.isNotEmpty() &&
                visibilityTarget != null && visibilityTarget >= 30
            ) add(ManifestFrameworkRequirement("package visibility queries", 30))
            if (allowCleartext == true) add(ManifestFrameworkRequirement("global cleartext", 23))
            if (allowSelectedCleartextDomains == true) add(ManifestFrameworkRequirement("domain-scoped cleartext", 24))
            if (legacyStorage == true) add(ManifestFrameworkRequirement("requestLegacyExternalStorage", 29))
            if (disableHeapTagging == true) add(ManifestFrameworkRequirement("allowNativeHeapPointerTagging", 30))
            if (extractNativeLibs == true) add(ManifestFrameworkRequirement("extractNativeLibs", 23))
            minimumForegroundServiceFrameworkApi(selectedForegroundMappings)?.let {
                add(ManifestFrameworkRequirement("foregroundServiceType", it))
            }
        }
        inputManifestFrameworkFailure(nativeManifest, frameworkRequirements)?.let { failure ->
            if (diagnosticsOnly == true) {
                logger.warning("$failure No newer manifest attributes were applied in diagnostics-only mode.")
            } else {
                throw IllegalArgumentException(failure)
            }
        }
        val rawSourceTarget = originalTargetSdkValue(nativeManifest)
        val sourceNativeTarget = when {
            rawSourceTarget == null -> 1
            else -> rawSourceTarget.toIntOrNull()?.takeIf { it >= 1 }
        }
        val plannedNativeTarget = plannedNativeTargetSdk(spoofTargetSdk == true, targetSdk)
        val effectiveNativeTarget = nativeTargetSdkForInspection(
            sourceNativeTarget,
            targetSdk,
            spoofTargetSdk == true,
            diagnosticsOnly == true,
        )
        val apkNativePaths = listApkEntries("lib/").filter { it.endsWith(".so") }.distinct()
        require(apkNativePaths.size <= 512) {
            "Legacy compatibility: found more than 512 APK native libraries; refusing unbounded native metadata scan."
        }
        require(apkNativePaths.all(::isSafeApkNativeLibraryPath)) {
            "Legacy compatibility: APK contains a native-library path outside lib/<abi>/<name>.so; refusing ambiguous native metadata or relocation."
        }
        val apkNativePathSet = apkNativePaths.toSet()
        val apkNativeInspections = apkNativePaths.map { path ->
            inspectNativeLibrary(path, get(path, false), destination = path, collision = false)
        }
        val nativeInspections = apkNativeInspections.toMutableList()
        val inspectObbLibraries = obbPathForLog.isNotBlank() || embedExpansionObb == true || relocateExpansionNativeLibraries == true || removeRelocatedNativeLibrariesFromObb == true
        if (inspectObbLibraries) {
            val packageName = StartupHooks.resolvedPackageName
                ?: error("Legacy compatibility: cannot inspect OBB native libraries because APK package name could not be resolved.")
            val obb = validateExpansionObbInput(obbPathForLog, packageName, StartupHooks.resolvedVersionCode)
            ZipFile(obb).use { zip ->
                val nativeCandidates = zip.entries().asSequence()
                    .filter { it.name.startsWith("assets/libs/") && it.name.endsWith(".so") }
                    .toList()
                require(nativeCandidates.size <= 512) {
                    "Legacy compatibility: found more than 512 OBB native libraries; refusing unbounded native metadata scan."
                }
                val unsupportedNativePaths = nativeCandidates.filter { expansionNativeDestination(it.name) == null }
                require(relocateExpansionNativeLibraries != true || unsupportedNativePaths.isEmpty()) {
                    "Legacy compatibility: native-library relocation refused because an OBB library path is outside assets/libs/<abi>/<name>.so; resolve the OBB layout manually."
                }
                nativeInspections += unsupportedNativePaths.map { entry ->
                    NativeLibraryInspection(entry.name, null, "unsupported OBB native-library path", entry.size.takeIf { it >= 0 })
                }
                val nativeEntries = nativeCandidates.filter { expansionNativeDestination(it.name) != null }
                nativeInspections += nativeEntries.map { entry ->
                    val destination = requireNotNull(expansionNativeDestination(entry.name))
                    val inspection = inspectNativeLibrary(zip, entry, destination, destination in apkNativePathSet)
                    val apkProxy = apkNativeInspections.firstOrNull { it.path == destination }
                    if (apkProxy?.metadata != null && inspection.metadata != null &&
                        isVerifiedProxyAssetRoute(apkProxy.metadata, inspection.metadata, get(destination, false), entry.name)
                    ) {
                        inspection.copy(destination = entry.name, collision = false, proxyPairPreserved = true)
                    } else {
                        inspection
                    }
                }
            }
        }
        val dexNativeLoadAnalysis = runCatching {
            val dexEntries = listApkEntries("classes").filter { it.matches(Regex("classes[0-9]*\\.dex")) }.distinct()
            if (dexEntries.size > MAX_DEX_SCAN_ENTRIES) {
                NativeLoadAnalysis(emptySet(), listOf("APK DEX entry count exceeds the 64-file load-analysis limit"))
            } else {
                inspectDexNativeLoads(dexEntries.map { it to get(it, false) })
            }
        }.getOrElse {
            NativeLoadAnalysis(emptySet(), listOf("cannot enumerate APK DEX native-load references"))
        }
        val manifestElements = nativeManifest.getElementsByTagName("*")
        val manifestNativeActivity = (0 until manifestElements.length).any { index ->
            val component = manifestElements.item(index) as? Element ?: return@any false
            when (component.manifestLocalName()) {
                "activity" -> component.androidAttribute("name") == "android.app.NativeActivity"
                "activity-alias" -> component.androidAttribute("targetActivity") == "android.app.NativeActivity"
                "meta-data" -> component.androidAttribute("name") == "android.app.lib_name"
                else -> false
            }
        }
        val classifiedNativeInspections = classifyNativeLibraryReachability(
            nativeInspections,
            dexNativeLoadAnalysis,
            manifestNativeActivity,
        )
        classifiedNativeInspections.forEach { inspection ->
            val message = formatNativeLibraryInspection(inspection, effectiveNativeTarget)
            if (inspection.metadata == null) logger.warning(message) else logger.info(message)
        }
        fun nativeIssueSummary(issues: List<String>): String = issues.take(12).joinToString("; ") +
            if (issues.size > 12) "; and ${issues.size - 12} additional finding(s)" else ""
        val nativeIssues = nativeLibraryCompatibilityIssues(classifiedNativeInspections, effectiveNativeTarget)
        if (nativeIssues.isNotEmpty()) {
            val message = "Legacy compatibility: native libraries are not proven safe for effective targetSdk ${effectiveNativeTarget ?: "unknown"}: ${nativeIssueSummary(nativeIssues)}"
            if (diagnosticsOnly == true) logger.warning("$message. Diagnostics only; no APK changes applied.") else error(message)
        }
        val plannedNativeIssues = if (diagnosticsOnly == true && plannedNativeTarget != null && plannedNativeTarget != effectiveNativeTarget) {
            nativeLibraryCompatibilityIssues(classifiedNativeInspections, plannedNativeTarget)
        } else emptyList()
        if (plannedNativeIssues.isNotEmpty()) {
            logger.warning(
                "Legacy compatibility: native libraries would be unsafe at planned targetSdk $plannedNativeTarget (not applied in diagnostics-only mode): ${nativeIssueSummary(plannedNativeIssues)}",
            )
        }
        if (nativeIssues.isEmpty() && plannedNativeIssues.isEmpty()) {
            val unresolvedElfLibraries = classifiedNativeInspections.filter {
                it.reachability != NativeLibraryReachability.PROVEN_UNREACHABLE && it.metadata == null
            }
            val textRelocationLibraries = classifiedNativeInspections.filter {
                it.reachability != NativeLibraryReachability.PROVEN_UNREACHABLE && it.metadata?.hasTextRelocations == true
            }
            val target = effectiveNativeTarget ?: "unknown"
            val textRelocationPaths = textRelocationLibraries.take(8).joinToString(", ") { inspection ->
                inspection.path.filterNot { Character.isISOControl(it) }.take(200)
            }
            val unresolvedPaths = unresolvedElfLibraries.take(8).joinToString(", ") { inspection ->
                inspection.path.filterNot { Character.isISOControl(it) }.take(200)
            }
            val textRelocationMore = if (textRelocationLibraries.size > 8) ", and ${textRelocationLibraries.size - 8} more" else ""
            val unresolvedMore = if (unresolvedElfLibraries.size > 8) ", and ${unresolvedElfLibraries.size - 8} more" else ""
            when {
                textRelocationLibraries.isNotEmpty() && unresolvedElfLibraries.isNotEmpty() -> logger.info(
                    "Legacy compatibility: DT_TEXTREL remains in $textRelocationPaths$textRelocationMore and ELF inspection failed for $unresolvedPaths$unresolvedMore; targetSdk $target is below API 23. No metadata was stripped; runtime loader behavior remains unknown.",
                )
                textRelocationLibraries.isNotEmpty() -> logger.info(
                    "Legacy compatibility: DT_TEXTREL remains in $textRelocationPaths$textRelocationMore; effective targetSdk $target is below the API 23 rejection threshold. Relocations were not stripped; runtime loader behavior remains unknown.",
                )
                unresolvedElfLibraries.isNotEmpty() -> logger.info(
                    "Legacy compatibility: ELF inspection failed for $unresolvedPaths$unresolvedMore; effective targetSdk $target is below API 23. No binaries were changed; loader compatibility remains unknown.",
                )
                else -> logger.info(
                    "Legacy compatibility: no reachable or unresolved DT_TEXTREL/malformed ELF incompatibility detected for effective targetSdk $target; JNI entrypoints and runtime loader outcome remain unknown.",
                )
            }
        }
        if (diagnosticsOnly == true) {
            get("AndroidManifest.xml", false).inputStream().use { input ->
                document(input).use { manifest -> reportManifestPreflight(manifest) }
            }
            logger.info("Legacy compatibility preflight complete; diagnostics-only mode applied no APK changes.")
            return@execute
        }

        var changed = 0
        var noApplication = false
        var targetSdkUpdate: LegacyTargetSdkUpdateResult? = null
        var packageVisibilityApplied: List<String> = emptyList()
        var packageVisibilityTarget: Int? = null
        var foregroundServiceMappingsApplied: List<ForegroundServiceTypeMapping> = emptyList()
        document("AndroidManifest.xml").use { manifest ->
            reportManifestPreflight(manifest)
            noApplication = manifest.documentElement.applicationOrNull() == null
            val originalTarget = originalTargetSdk(manifest)
            val update = applyLegacyTargetSdk(
                manifest,
                spoofEnabled = spoofTargetSdk == true,
                customTarget = targetSdk,
            )
            targetSdkUpdate = update
            if (update.outcome == LegacyTargetSdkOutcome.UPDATED) changed++
            if (update.outcome == LegacyTargetSdkOutcome.UPDATED && update.originalTarget != null && update.selectedTarget != null && update.originalTarget > update.selectedTarget) {
                logger.warning("Legacy compatibility: original target SDK ${update.originalTarget} was higher than ${update.selectedTarget}; platform behavior was downgraded.")
            }
            if (spoofMinSdk == true) {
                val min = minSdk ?: 28
                if (min !in 1..40) {
                    logger.warning("Legacy compatibility: min SDK $min is outside the supported range 1..40.")
                } else if (updateMinSdk(manifest, min)) {
                    changed++
                    logger.info("Legacy compatibility: min SDK set to $min.")
                }
            }
            if (selectedForegroundMappings.isNotEmpty()) {
                foregroundServiceMappingsApplied = selectedForegroundMappings
            } else if (applyForegroundServiceTypes == true) {
                logger.warning("Legacy compatibility: foreground-service type mapping is enabled but no mappings were supplied; no service declarations changed.")
            }
            if (legacyReviver == true) {
                changed += addLegacyReviver(
                    manifest,
                    apache = apacheLegacy == true,
                    foreground = foregroundService == true,
                    alarms = exactAlarms == true,
                    bluetooth = bluetooth == true,
                )
            }
            if (spoofImei == true && addPermission(manifest, READ_PHONE_STATE)) {
                changed++
                logger.info("Legacy compatibility: added READ_PHONE_STATE so spoofed TelephonyManager calls can succeed.")
            }
            when {
                exportAllActivityComponents == true -> {
                    if (repairExportFlags == true) {
                        logger.info("Legacy compatibility: both exported-component options selected; Export All Activities takes precedence and repair mode is skipped.")
                    } else {
                        logger.info("Legacy compatibility: Export All Activities selected.")
                    }
                    val exported = exportAllActivities(manifest, logger)
                    changed += exported
                    if (exported == 0) {
                        logger.info("Legacy compatibility: all activities and aliases already have android:exported=true, or none were found.")
                    } else {
                        logger.info("Legacy compatibility: exported $exported activity component(s).")
                    }
                }
                shouldRepairMissingExportFlags(repairExportFlags == true, update.finalTarget) -> {
                    val repaired = repairMissingComponentExportFlags(manifest, logger)
                    changed += repaired
                    if (repaired == 0) {
                        logger.info("Legacy compatibility: no filtered components with missing android:exported were found.")
                    } else {
                        logger.info("Legacy compatibility: repaired $repaired component exported flag(s).")
                    }
                }
                repairExportFlags == true -> {
                    logger.info("Legacy compatibility: skipped missing-export repair because final targetSdkVersion ${update.finalTarget ?: "unknown"} is below 31 or unavailable; android:exported is required only for target 31+.")
                }
            }
            if (allowCleartext == true) {
                if (setApplicationAttribute(manifest, "usesCleartextTraffic", "true")) changed++
                if (manifest.documentElement.applicationOrNull()?.hasAndroidAttribute("networkSecurityConfig") == true) {
                    logger.warning("Legacy compatibility: preserved networkSecurityConfig; it may still restrict cleartext traffic.")
                }
            }
            if (allowSelectedCleartextDomains == true) {
                val domains = selectedCleartextDomains
                if (domains.isEmpty()) {
                    logger.warning("Legacy compatibility: selected-domain cleartext is enabled but Cleartext Domains is empty; no network security config changes made.")
                } else {
                    val application = manifest.documentElement.applicationOrNull()
                        ?: error("Legacy compatibility: cannot configure selected-domain cleartext; manifest application is missing.")
                    val reference = application.androidAttribute("networkSecurityConfig").trim()
                    if (reference.isEmpty()) {
                        val path = "res/xml/$CLEAR_TEXT_NETWORK_SECURITY_RESOURCE.xml"
                        val existingVariants = networkSecurityConfigPathsForResource(
                            CLEAR_TEXT_NETWORK_SECURITY_RESOURCE,
                            listApkEntries("res/xml"),
                        )
                        require(existingVariants.isEmpty()) {
                            "Legacy compatibility: generated network security resource already exists without a manifest reference; refusing to overwrite it."
                        }
                        val configFile = get(path, false)
                        require(!configFile.exists()) {
                            "Legacy compatibility: a staged network security resource exists outside the input APK; refusing to overwrite another patch's output."
                        }
                        ensurePublicXmlResourceId(get("res/values/public.xml", false), "xml", CLEAR_TEXT_NETWORK_SECURITY_RESOURCE)
                        val config = newCleartextNetworkSecurityConfig(domains)
                        writeSecureXml(config, configFile)
                        application.setAttributeNS(NS_ANDROID, "android:networkSecurityConfig", "@xml/$CLEAR_TEXT_NETWORK_SECURITY_RESOURCE")
                        changed += 2
                    } else {
                        val paths = resolveNetworkSecurityConfigPaths(reference, listApkEntries("res/xml"))
                        val configs = paths.map { path -> path to readSecureXml(get(path, false)) }
                        configs.forEach { (path, config) ->
                            val hadCleartextDomainRule = hasExistingCleartextDomainRule(config)
                            val configChanges = mergeCleartextDomains(config, domains)
                            if (configChanges > 0) writeSecureXml(config, get(path, false))
                            changed += configChanges
                            if (hadCleartextDomainRule) {
                                logger.warning("Legacy compatibility: existing cleartext domain rules in $path are preserved; selected-domain entries are additive.")
                            }
                        }
                    }
                    if (setApplicationAttribute(manifest, "usesCleartextTraffic", "false")) changed++
                    logger.info("Legacy compatibility: selected cleartext domains configured: ${domains.joinToString()}; global cleartext remains disabled.")
                }
            }
            if (legacyStorage == true) {
                if (setApplicationAttribute(manifest, "requestLegacyExternalStorage", "true")) changed++
                if (addPermission(manifest, WRITE_EXTERNAL_STORAGE, maxSdkVersion = 32)) changed++
                val effectiveTarget = if (spoofTargetSdk == true) {
                    targetSdk ?: 24
                } else {
                    originalTarget
                }
                if (effectiveTarget != null && effectiveTarget >= 30) {
                    logger.warning("Legacy compatibility: target SDK $effectiveTarget is 30 or higher; requestLegacyExternalStorage is ignored on Android 11 and newer.")
                }
            }
            if (bypassExpansionDownloader == true) {
                if (expansionLicenseGate.verified) {
                    verifyExpansionDownloaderLicenseLauncher(manifest)
                    logger.info("Legacy compatibility: retained DownloaderActivity launcher; verified LVL gate routes allowed launches through original Unity flow.")
                } else {
                    val launcherActivity = expansionLauncherTarget.activityName
                        ?: error("Legacy compatibility: downloader bypass activity detection failed: ${expansionLauncherTarget.error}")
                    changed += moveExpansionDownloaderLauncher(manifest, logger, launcherActivity)
                }
            }
            if (allScreens == true) {
                val (removed, updated) = supportAllScreens(manifest)
                changed += removed
                if (updated) changed++
            }
            if (relaxLibraries == true) changed += relaxSharedLibraries(manifest)
            if (foregroundServiceMappingsApplied.isNotEmpty()) {
                changed += applyForegroundServiceTypeMappings(manifest, foregroundServiceMappingsApplied)
            }
            packageVisibilityTarget = update.finalTarget
            if (playStorePackageVisibility == true) {
                if (update.finalTarget == null || update.finalTarget < 30) {
                    logger.warning("Legacy compatibility: package visibility queries require effective targetSdk 30+; no queries added (target=${update.finalTarget ?: "unknown"}).")
                } else {
                    packageVisibilityApplied = selectedPackageVisibility
                    if (packageVisibilityApplied.isEmpty()) {
                        logger.warning("Legacy compatibility: Package Visibility Allowlist is empty; no package queries added.")
                    } else {
                        packageVisibilityApplied.forEach { packageName ->
                            if (addPackageVisibilityQuery(manifest, packageName)) changed++
                        }
                    }
                }
            }
            if (manifestCompatAttributes == true) {
                if (setApplicationAttribute(manifest, "largeHeap", "true")) changed++
                if (setApplicationAttribute(manifest, "hardwareAccelerated", "true")) changed++
            }
            if (extractNativeLibs == true && setApplicationAttribute(manifest, "extractNativeLibs", "true")) changed++
            if (disableHeapTagging == true && setApplicationAttribute(manifest, "allowNativeHeapPointerTagging", "false")) changed++
            if (vmSafeMode == true && setApplicationAttribute(manifest, "vmSafeMode", "true")) changed++
        }
        if (foregroundServiceMappingsApplied.isNotEmpty()) {
            get("AndroidManifest.xml", false).inputStream().use { input ->
                document(input).use { parsed -> verifyForegroundServiceTypeMappings(parsed, foregroundServiceMappingsApplied) }
            }
            logger.info("Legacy compatibility: foreground-service types and required permissions verified in manifest readback: ${foregroundServiceMappingsApplied.joinToString { "${it.component}=${it.types.joinToString("|")}" }}.")
        }
        if (packageVisibilityApplied.isNotEmpty()) {
            get("AndroidManifest.xml", false).inputStream().use { input ->
                document(input).use { parsed ->
                    val missing = packageVisibilityApplied.filterNot { hasPackageVisibilityQuery(parsed, it) }
                    check(missing.isEmpty()) {
                        "Legacy compatibility: package visibility query readback failed for: ${missing.joinToString()}"
                    }
                }
            }
            logger.info("Legacy compatibility: package visibility queries verified for targetSdk ${packageVisibilityTarget}: ${packageVisibilityApplied.joinToString()}.")
        }
        if (bypassExpansionDownloader == true || expansionLicenseGate.verified) {
            val launcherActivity = expansionLauncherTarget.activityName
            if (bypassExpansionDownloader == true && !expansionLicenseGate.verified && launcherActivity == null) {
                error("Legacy compatibility: downloader bypass activity detection failed: ${expansionLauncherTarget.error}")
            }
            get("AndroidManifest.xml", false).inputStream().use { input ->
                document(input).use {
                    if (expansionLicenseGate.verified) verifyExpansionDownloaderLicenseLauncher(it, launcherActivity)
                    else verifyExpansionDownloaderLauncher(it, requireNotNull(launcherActivity))
                }
            }
            if (expansionLicenseGate.verified) {
                logger.info("Legacy compatibility: manifest readback verified DownloaderActivity remains launcher; Unity launch stays behind LVL allow.")
            } else {
                logger.info("Legacy compatibility: downloader bypass manifest verified after transformation; DownloaderActivity is not launcher and $launcherActivity is launcher.")
            }
        }
        if (repairFileUriSharing == true) {
            if (fileUriSharingResult.patchedCallSites == 0 && fileUriSharingResult.alreadyPatchedCallSites == 0) {
                logger.warning("Legacy compatibility: no supported file URI share call sites were found; provider manifest changes skipped.")
            } else {
                val paths = get("res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml", false)
                val publicXml = get("res/values/public.xml", false)
                val pathEntryNames = legacyFileProviderPathsEntries(listApkEntries("res/xml"))
                val basePathEntry = "res/xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE.xml"
                val alreadyExists = basePathEntry in pathEntryNames
                val additionalPaths = pathEntryNames.filterNot { it == basePathEntry }.map { get(it, false) }
                document("AndroidManifest.xml").use { manifest ->
                    changed += ensureLegacyFileProvider(
                        manifest,
                        paths,
                        publicXml,
                        pathsResourceExists = alreadyExists,
                        additionalExistingPathsFiles = additionalPaths,
                    )
                }
                if (paths.exists()) paths.inputStream().use { input -> verifyLegacyFileProviderPaths(document(input)) }
                get("AndroidManifest.xml", false).inputStream().use { input ->
                    document(input).use { parsed -> verifyLegacyFileProvider(parsed, paths, additionalPaths) }
                }
                logger.info("Legacy compatibility: private file-sharing provider verified; rewritten=${fileUriSharingResult.patchedCallSites}, alreadyPatched=${fileUriSharingResult.alreadyPatchedCallSites}, skipped=${fileUriSharingResult.skippedCallSites}.")
            }
        }
        targetSdkUpdate?.let { update ->
            val readback = get("AndroidManifest.xml", false).inputStream().use { input ->
                document(input).use { parsed -> originalTargetSdk(parsed) }
            }
            check(readback == update.finalTarget) {
                "Legacy compatibility: final target SDK readback mismatch; expected " + update.finalTarget + " but found " + readback
            }
            logger.info(
                "Legacy compatibility: target SDK source=${optionLogValue(update.originalTarget)} selected=${optionLogValue(update.selectedTarget)} final=${optionLogValue(readback)} outcome=${update.outcome}",
            )
            logger.info("Legacy compatibility: decoded manifest target verification passed; run bash tools/verify-legacy-compatibility-artifact.sh with the compiled APK details before release.")
            when (update.outcome) {
                LegacyTargetSdkOutcome.OUT_OF_RANGE -> logger.warning(
                    "Legacy compatibility: selected target SDK " + update.selectedTarget + " is outside the supported range 23..40; target remains " + readback + ".",
                )
                LegacyTargetSdkOutcome.DISABLED -> logger.info(
                    "Legacy compatibility: target SDK unchanged because Spoof Target SDK is disabled.",
                )
                LegacyTargetSdkOutcome.ALREADY_CURRENT -> logger.info(
                    "Legacy compatibility: target SDK already matches selected value " + readback + ".",
                )
                LegacyTargetSdkOutcome.UPDATED -> logger.info(
                    "Legacy compatibility: target SDK updated and read back as " + readback + ".",
                )
            }
        }
        if (noApplication) {
            logger.warning("Legacy compatibility: no application element was found. Application-level options were skipped.")
        }
        if (changed == 0) logger.info("Legacy compatibility: no manifest changes were required.")
        else logger.info("Legacy compatibility: applied $changed manifest change(s).")
    }
}
