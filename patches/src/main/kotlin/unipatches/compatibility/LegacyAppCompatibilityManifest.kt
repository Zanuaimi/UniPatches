package unipatches.compatibility

import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.filePathOption
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import helpers.manifest.NS_ANDROID
import helpers.manifest.applicationOrNull
import java.util.logging.Logger
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
internal const val QUERY_ALL_PACKAGES = "android.permission.QUERY_ALL_PACKAGES"
internal const val TARGET_PROFILE_CUSTOM = "custom"
internal const val TARGET_PROFILE_AUTOMATIC = "automatic"
internal const val TARGET_PROFILE_27 = "27"
internal const val TARGET_PROFILE_29 = "29"

internal fun selectLegacyTargetSdk(original: Int?, profile: String?, custom: Int): Int = when (profile) {
    TARGET_PROFILE_AUTOMATIC -> original ?: 27
    TARGET_PROFILE_27 -> 27
    TARGET_PROFILE_29 -> 29
    else -> custom
}

internal fun hasPermission(document: Document, name: String): Boolean =
    listOf("uses-permission", "uses-permission-sdk-23").any { tag ->
        val permissions = document.getElementsByTagName(tag)
        (0 until permissions.length).any {
            (permissions.item(it) as? Element)?.getAttributeNS(NS_ANDROID, "name") == name
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

internal fun addPackageVisibilityQuery(document: Document, packageName: String): Boolean {
    if (packageName.isBlank() || packageName.any { it.isWhitespace() || Character.isISOControl(it) }) return false
    val root = document.documentElement ?: return false
    val queriesElements = root.childElements().filter { it.manifestLocalName() == "queries" }
    if (queriesElements.any { queries -> elementsNamed(queries, "package").any { it.androidAttribute("name") == packageName } }) {
        return false
    }
    val queries = queriesElements.firstOrNull() ?: createManifestChild(document, root, "queries").also { element ->
        val application = root.childElements().firstOrNull { it.manifestLocalName() == "application" }
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
    if (application.getAttributeNS(NS_ANDROID, name) == value) return false
    application.setAttributeNS(NS_ANDROID, "android:$name", value)
    return true
}

internal fun addLegacyReviver(document: Document, apache: Boolean, foreground: Boolean, alarms: Boolean, bluetooth: Boolean): Int {
    var changed = 0
    val application = document.documentElement.applicationOrNull()
    if (apache && application != null) {
        val libraries = application.getElementsByTagName("uses-library")
        val exists = (0 until libraries.length).any {
            (libraries.item(it) as? Element)?.getAttributeNS(NS_ANDROID, "name") == APACHE_LEGACY_LIB
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

/** Returns the original targetSdkVersion from the manifest, or null when absent. */
internal fun originalTargetSdk(document: Document): Int? =
    (document.getElementsByTagName("uses-sdk").item(0) as? Element)
        ?.getAttributeNS(NS_ANDROID, "targetSdkVersion")
        ?.takeIf { it.isNotEmpty() }
        ?.toIntOrNull()

internal fun updateTargetSdk(document: Document, target: Int): Boolean {
    val root = document.documentElement ?: return false
    if (root.tagName != "manifest") return false
    val usesSdk = root.getElementsByTagName("uses-sdk").item(0) as? Element
    if (usesSdk != null) {
        if (usesSdk.getAttributeNS(NS_ANDROID, "targetSdkVersion") == target.toString()) return false
        usesSdk.setAttributeNS(NS_ANDROID, "android:targetSdkVersion", target.toString())
        return true
    }
    val created = document.createElement("uses-sdk")
    created.setAttributeNS(NS_ANDROID, "android:targetSdkVersion", target.toString())
    root.insertBefore(created, root.applicationOrNull())
    return true
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
        if (element.getAttributeNS(NS_ANDROID, size) != "true") {
            element.setAttributeNS(NS_ANDROID, "android:$size", "true")
            changed = true
        }
    }
    if (element.getAttributeNS(NS_ANDROID, "anyDensity") != "true") {
        element.setAttributeNS(NS_ANDROID, "android:anyDensity", "true")
        changed = true
    }
    return removed to (changed || supports.length == 0)
}

internal fun relaxSharedLibraries(document: Document): Int {
    var changed = 0
    val libraries = document.getElementsByTagName("uses-library")
    for (index in 0 until libraries.length) {
        val library = libraries.item(index) as? Element ?: continue
        if (library.getAttributeNS(NS_ANDROID, "required") == "true") {
            library.setAttributeNS(NS_ANDROID, "android:required", "false")
            changed++
        }
    }
    return changed
}

internal val exportedComponentTags = listOf("activity", "activity-alias", "service", "receiver")

private fun Element.androidAttributeValue(name: String): String? {
    getAttributeNodeNS(NS_ANDROID, name)?.let { return it.nodeValue }
    getAttributeNode("android:$name")?.let { return it.nodeValue }
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        val qualifiedName = attribute.nodeName
        val localName = attribute.localName ?: qualifiedName.substringAfter(':')
        val prefix = attribute.prefix ?: qualifiedName.substringBefore(':', "")
        if (localName == name && (attribute.namespaceURI == NS_ANDROID ||
                (prefix.isNotEmpty() && lookupNamespaceURI(prefix) == NS_ANDROID))
        ) return attribute.nodeValue
    }
    return null
}

internal fun Element.androidAttribute(name: String): String = androidAttributeValue(name).orEmpty()

internal fun Element.hasAndroidAttribute(name: String): Boolean = androidAttributeValue(name) != null

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

internal fun repairMissingComponentExportFlags(document: Document, logger: Logger): Int {
    val root = document.documentElement ?: return 0
    var repaired = 0
    for (tagName in exportedComponentTags) {
        for (component in elementsNamed(root, tagName)) {
            if (component.hasAndroidAttribute("exported") || !component.hasIntentFilter()) continue

            val exported = component.isLauncherComponent().toString()
            component.setAttributeNS(NS_ANDROID, "android:exported", exported)
            repaired++
            logger.info("Legacy compatibility: repaired ${component.androidAttribute("name").ifEmpty { "<unnamed>" }} with android:exported=$exported.")
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
    targetSdkProfile: String?,
    customTargetSdk: Int?,
    repairExportFlags: Boolean,
    exportAllActivityComponents: Boolean,
    playStorePackageVisibility: Boolean,
    bypassPackageVisibility: Boolean,
    embedExpansionObb: Boolean,
    expansionObbPath: String?,
): List<LegacyCompatibilityPreflightFinding> {
    val root = document.documentElement ?: return listOf(
        LegacyCompatibilityPreflightFinding("AndroidManifest.xml", false, "leave unchanged", "all", "unknown", "manifest root is missing"),
    )
    val packageName = root.getAttribute("package")
    val findings = mutableListOf<LegacyCompatibilityPreflightFinding>()
    val originalTarget = originalTargetSdk(document)
    if (spoofTargetSdk || originalTarget != null) {
        val proposedTarget = if (spoofTargetSdk) selectLegacyTargetSdk(originalTarget, targetSdkProfile, customTargetSdk ?: 34) else originalTarget
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "uses-sdk targetSdkVersion",
            detected = originalTarget != null,
            proposal = if (spoofTargetSdk) "set targetSdkVersion to " + proposedTarget else "preserve the APK targetSdkVersion",
            apiRange = "Android API 23-40 supported by this option",
            confidence = if (originalTarget == null) "fallback" else "exact manifest attribute",
            uncertainty = if (originalTarget == null) "original target is absent; fallback changes platform security behavior" else "target changes compatibility behavior and may reduce protections",
        )
    }
    val missingExported = exportedComponentTags.flatMap { elementsNamed(root, it) }
        .filter { it.hasIntentFilter() && !it.hasAndroidAttribute("exported") }
    if (missingExported.isNotEmpty() || repairExportFlags || exportAllActivityComponents) {
        val launcherCount = missingExported.count { it.isLauncherComponent() }
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "filtered components missing android:exported",
            detected = missingExported.isNotEmpty(),
            proposal = when {
                exportAllActivityComponents -> "set missing activity/alias flags true; preserve explicit values"
                repairExportFlags -> "set launcher components true and other filtered components false"
                else -> "enable safe missing-export repair only if installation requires it"
            },
            apiRange = "Android 12/API 31+ when targetSdkVersion is 31+",
            confidence = "exact manifest element/filter/attribute scan",
            uncertainty = launcherCount.toString() + " launcher components among " + missingExported.size + " missing flags; external caller needs are not inferred",
        )
    }
    val existingPlayQuery = root.childElements().filter { it.manifestLocalName() == "queries" }.any { queries ->
        elementsNamed(queries, "package").any { it.androidAttribute("name") == "com.android.vending" }
    }
    if (playStorePackageVisibility || existingPlayQuery) {
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "Google Play package visibility",
            detected = existingPlayQuery,
            proposal = if (playStorePackageVisibility && !existingPlayQuery) "add a scoped queries/package entry for com.android.vending" else "preserve the existing scoped query",
            apiRange = "Android 11/API 30+",
            confidence = "exact manifest query scan",
            uncertainty = "only Google Play visibility is covered; other installed packages remain hidden",
        )
    }
    if (bypassPackageVisibility) {
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "broad package visibility",
            detected = hasPermission(document, QUERY_ALL_PACKAGES),
            proposal = "add QUERY_ALL_PACKAGES (broad visibility explicitly requested)",
            apiRange = "Android 11/API 30+",
            confidence = "explicit high-impact option",
            uncertainty = "exposes installed-app visibility and may violate store policy",
        )
    }
    val obbPath = expansionObbPath.orEmpty().trim()
    if (embedExpansionObb || obbPath.isNotEmpty()) {
        val obb = java.io.File(obbPath)
        val valid = obb.isFile && obb.canRead() && isExpansionFileForPackage(obb.name, packageName)
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "embedded expansion OBB",
            detected = valid,
            proposal = if (embedExpansionObb && valid) "embed and stage " + obb.name + " with runtime checksum verification" else "select a readable main/patch OBB matching the APK package",
            apiRange = "all supported Android versions",
            confidence = if (valid) "exact path, filename, package, and readability checks" else "uncertain or absent input",
            uncertainty = "archive contents and device OBB publication are verified in apply/runtime phases",
        )
    }
    return findings
}

@Suppress("unused")
val legacyAppCompatibilityPatch = resourcePatch(
    name = "Legacy App Compatibility Patch ( Experimental, Enhanced )",
    description = """
        Improves older app and game compatibility on modern Android with manifest, storage, screen,
        native runtime, network, shared-library, device-identity, and OpenIAB receiver controls.
        Spoof Target SDK may help installation or launch, but cannot repair incompatible code.

        Use Control Embedded Auth / Stores Patch for Google Play license or Google Play Services checks;
        these options stay separate to prevent overlapping injections.

        This patch cannot restore shut-down servers, missing CPU architecture support, server licensing,
        Play Integrity, or unsupported native code. Conservative compatibility features are enabled by
        default; more invasive native, network, storage, library, and identity options remain disabled.

        Experimental: Its functionalities are not guaranteed to work in all apps.

        Credits: Nai64Patches from Nai64 for the original legacy compatibility functionality. Credits to
        LSPosed for the Hidden API bypass approach and AndroidHiddenApiBypass implementation (Apache-2.0).
        UniPatches adds the merged settings, validation, manifest safeguards, compatibility organization,
        and the Suppress GPlay Login UI patch option.
    """.trimIndent(),
    default = false,
) {
    try { category("Legacy App Compatibility") } catch (_: NoSuchMethodError) {}

    val preset by stringOption(
        title = "Legacy App Compatibility > Preset",
        key = "legacyCompatibilityPreset",
        default = LegacyCompatibilityPresets.CUSTOM,
        description = "Custom (default) uses your individual choices and conservative defaults. Android 7–9 (API 24–28), Android 10 (29), Android 11 (30), Android 12/12L (31–32), Android 13 (33), and Android 14+ (34+) describe the APK's original target era, not your current device. Era profiles keep the original target SDK, repair missing exported flags safely, repair exact receiver calls, and add Apache HTTP/foreground-service/Bluetooth compatibility only for eras before those APIs changed. Unity enables native-library extraction only when exact Unity classes are detected; OpenIAB enables only recognized OpenIAB receiver repair and a scoped Play Store query; Unity + OpenIAB combines both independently detected repairs. Every non-Custom preset overrides all ordinary options and disables trust-all certificates, IMEI spoofing, QUERY_ALL_PACKAGES, export-all, cleartext and hidden-API bypass. Those six choices are dormant outside Custom; ordinary Custom choices need not restore. Presets never embed OBBs, bypass downloaders, lower target SDK, force all receivers exported, or enable uncertain engine mutations.",
        values = linkedMapOf(
            "Custom" to "custom",
            "Android 7–9 origin" to "android_7_9",
            "Android 10 origin" to "android_10",
            "Android 11 origin" to "android_11",
            "Android 12/12L origin" to "android_12",
            "Android 13 origin" to "android_13",
            "Android 14+ origin" to "android_14_plus",
            "Unity" to "unity",
            "OpenIAB" to "openiab",
            "Unity + OpenIAB" to "unity_openiab",
        ),
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
        default = 34,
        key = "legacyCompatibilityTargetSdk",
        description = "Choose the target SDK number only when the Custom profile is selected. Android accepts values 23–40 here; other values are rejected. Lower values can restore legacy behavior but may weaken security and privacy protections. Default: 34.",
    )
    val targetSdkProfileRaw by stringOption(
        title = "Legacy App Compatibility > Installation and manifest > Target SDK compatibility profile",
        default = TARGET_PROFILE_AUTOMATIC,
        key = "legacyCompatibilityTargetSdkProfile",
        description = "Choose Custom to use the number above; Automatic to keep the APK's existing target (or use 27 if it has none); or fixed target 27/29 profiles for troubleshooting. Prefer Automatic unless you know the app needs another target: changing it alters Android's compatibility rules, and cannot repair broken code.",
        values = linkedMapOf(
            "Custom target SDK" to TARGET_PROFILE_CUSTOM,
            "Preserve original target (automatic)" to TARGET_PROFILE_AUTOMATIC,
            "Target SDK 27" to TARGET_PROFILE_27,
            "Target SDK 29" to TARGET_PROFILE_29,
        ),
    )
    val legacyReviverRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Legacy App Reviver",
        default = false,
        key = "legacyCompatibilityReviver",
        description = "Turn this on to add the compatibility declarations selected below. Leave it off if the app does not need them; enabling declarations does not grant runtime permissions or guarantee the app will work.",
    )
    val apacheLegacyRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Apache HTTP legacy library",
        default = true,
        key = "legacyCompatibilityApacheLegacy",
        description = "Use when the app crashes or fails to start because it uses the removed Apache HttpClient library. Adds that library as optional; it will not help if the app needs other missing libraries. Requires Legacy App Reviver.",
    )
    val foregroundServiceRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Foreground service permission",
        default = true,
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
        default = true,
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
    val receiverFixAppWideRaw by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Fix Dynamic Receiver Registrations (App Wide)",
        default = true,
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
        title = "Legacy App Compatibility > Installation and manifest > Allow Cleartext Traffic",
        default = false,
        key = "legacyCompatibilityAllowCleartext",
        description = "Try this if the app cannot connect to a server that supports only plain HTTP. Unencrypted traffic can be read or changed on the network, so do not enable for logins, payments, or other sensitive data. An existing network security configuration may still block HTTP.",
    )
    val relaxLibrariesRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Relax Shared Libraries",
        default = false,
        key = "legacyCompatibilityRelaxLibraries",
        description = "Use if Android will not install the app because an optional device library is missing. This lets installation continue, but the app may crash or lose features if it truly needs that library.",
    )
    val playStorePackageVisibilityRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Play Store Package Visibility",
        default = false,
        key = "legacyCompatibilityPlayStoreVisibility",
        description = "Use if the app incorrectly reports that Google Play is not installed on Android 11+. It allows checks for Google Play only, not other apps, and is preferable to broad package visibility when Play is the only check needed.",
    )
    val bypassPackageVisibilityRaw by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Bypass Package Visibility (Broad)",
        default = false,
        key = "legacyCompatibilityPackageVisibility",
        description = "Use only if the app must check for many different installed apps and those checks fail on Android 11+. This requests visibility into all installed packages, which exposes more information and may be restricted by app-store policy. Prefer Play Store Package Visibility if only Google Play is needed.",
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
        description = "Use if the app's native .so libraries are incorrectly stored inside the selected OBB and the game cannot load them. Copies them into the APK's matching ABI library folders; leave off if the OBB has no such libraries or the app already works.",
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
        description = "Use when the app needs an OBB at first launch and no downloader can provide it. The file is bundled inside the APK and copied to the expected OBB folder; this can greatly increase APK size and may hit storage or distribution limits.",
    )
    val bypassExpansionDownloaderRaw by booleanOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Bypass known Unity expansion downloader",
        default = false,
        key = "legacyCompatibilityBypassExpansionDownloader",
        description = "Use only for the recognized Unity expansion downloader when it blocks startup despite bundling the OBB. It redirects launcher handling to Unity's known launcher; it is not a general downloader repair and requires Embed and stage expansion OBB.",
    )
    val allScreensRaw by booleanOption(
        title = "Legacy App Compatibility > Storage and display > Support All Screens",
        default = false,
        key = "legacyCompatibilityAllScreens",
        description = "Keep enabled so Android can install the app on phones and tablets whose screen size or density is not listed in its original manifest. This changes compatibility declarations, not the app's layout; some screens may still display poorly.",
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

    val detectedEngines = LegacyDetectedEngines()
    dependsOn(legacyEngineDetectionPatch(detectedEngines))
    val presetId = LegacyCompatibilityPresets.validate(preset ?: LegacyCompatibilityPresets.CUSTOM)
    val diagnosticsOnly = LegacyCompatibilityPresets.boolean(presetId, "diagnosticsOnly", diagnosticsOnlyRaw)
    val spoofTargetSdk = LegacyCompatibilityPresets.boolean(presetId, "spoofTargetSdk", spoofTargetSdkRaw)
    val targetSdk = LegacyCompatibilityPresets.target(presetId, targetSdkRaw)
    val targetSdkProfile = LegacyCompatibilityPresets.targetProfile(presetId, targetSdkProfileRaw)
    val legacyReviver = LegacyCompatibilityPresets.boolean(presetId, "legacyReviver", legacyReviverRaw)
    val apacheLegacy = LegacyCompatibilityPresets.boolean(presetId, "apacheLegacy", apacheLegacyRaw)
    val foregroundService = LegacyCompatibilityPresets.boolean(presetId, "foregroundService", foregroundServiceRaw)
    val exactAlarms = LegacyCompatibilityPresets.boolean(presetId, "exactAlarms", exactAlarmsRaw)
    val bluetooth = LegacyCompatibilityPresets.boolean(presetId, "bluetooth", bluetoothRaw)
    val openIabReceiverRegistrationMode = LegacyCompatibilityPresets.openIabMode(presetId, openIabReceiverRegistrationModeRaw)
    val bypassHiddenApi = LegacyCompatibilityPresets.boolean(presetId, "bypassHiddenApi", bypassHiddenApiRaw)
    val trustCertificates = LegacyCompatibilityPresets.boolean(presetId, "trustCertificates", trustCertificatesRaw)
    val acknowledgeTrustCertificates = LegacyCompatibilityPresets.boolean(presetId, "acknowledgeTrustCertificates", acknowledgeTrustCertificatesRaw)
    val redirectLegacyStorage = LegacyCompatibilityPresets.boolean(presetId, "redirectLegacyStorage", redirectLegacyStorageRaw)
    val receiverFixAppWide = LegacyCompatibilityPresets.boolean(presetId, "receiverFixAppWide", receiverFixAppWideRaw)
    val repairExportFlags = LegacyCompatibilityPresets.boolean(presetId, "repairExportFlags", repairExportFlagsRaw)
    val exportAllActivityComponents = LegacyCompatibilityPresets.boolean(presetId, "exportAllActivityComponents", exportAllActivityComponentsRaw)
    val allowCleartext = LegacyCompatibilityPresets.boolean(presetId, "allowCleartext", allowCleartextRaw)
    val relaxLibraries = LegacyCompatibilityPresets.boolean(presetId, "relaxLibraries", relaxLibrariesRaw)
    val bypassPackageVisibility = LegacyCompatibilityPresets.boolean(presetId, "bypassPackageVisibility", bypassPackageVisibilityRaw)
    val manifestCompatAttributes = LegacyCompatibilityPresets.boolean(presetId, "manifestCompatAttributes", manifestCompatAttributesRaw)
    val legacyStorage = LegacyCompatibilityPresets.boolean(presetId, "legacyStorage", legacyStorageRaw)
    val expansionObbPath = if (presetId == LegacyCompatibilityPresets.CUSTOM) expansionObbPathRaw else ""
    val relocateExpansionNativeLibraries = LegacyCompatibilityPresets.boolean(presetId, "relocateExpansionNativeLibraries", relocateExpansionNativeLibrariesRaw)
    val removeRelocatedNativeLibrariesFromObb = LegacyCompatibilityPresets.boolean(presetId, "removeRelocatedNativeLibrariesFromObb", removeRelocatedNativeLibrariesFromObbRaw)
    val embedExpansionObb = LegacyCompatibilityPresets.boolean(presetId, "embedExpansionObb", embedExpansionObbRaw)
    val bypassExpansionDownloader = LegacyCompatibilityPresets.boolean(presetId, "bypassExpansionDownloader", bypassExpansionDownloaderRaw)
    val allScreens = LegacyCompatibilityPresets.boolean(presetId, "allScreens", allScreensRaw)
    val extractNativeLibs = LegacyCompatibilityPresets.boolean(presetId, "extractNativeLibs", extractNativeLibsRaw)
    val disableHeapTagging = LegacyCompatibilityPresets.boolean(presetId, "disableHeapTagging", disableHeapTaggingRaw)
    val vmSafeMode = LegacyCompatibilityPresets.boolean(presetId, "vmSafeMode", vmSafeModeRaw)
    val spoofImei = LegacyCompatibilityPresets.boolean(presetId, "spoofImei", spoofImeiRaw)
    val imei = if (presetId == LegacyCompatibilityPresets.CUSTOM) imeiRaw else "000000000000000"

    if (shouldApplyLegacyCompatibilityMutations(diagnosticsOnly == true)) {
        dependsOn(legacyImeiPatch { Pair(spoofImei == true, imei.orEmpty().trim()) })
        dependsOn(openIabReceiverFlagsPatch { openIabReceiverRegistrationMode ?: OPEN_IAB_AUTOMATIC })
        dependsOn(legacyRuntimeHooksPatch {
            LegacyRuntimeOptions(
                hiddenApiExemptions = bypassHiddenApi == true,
                trustCertificates = trustCertificates == true && acknowledgeTrustCertificates == true,
                storageRedirect = redirectLegacyStorage == true,
                embeddedExpansionObb = embedExpansionObb == true && expansionObbPath.orEmpty().trim().isNotEmpty(),
                expansionDownloaderBypass = bypassExpansionDownloader == true && embedExpansionObb == true && expansionObbPath.orEmpty().trim().isNotEmpty(),
            )
        })
        dependsOn(legacyExpansionFilesPatch {
            LegacyExpansionOptions(
                obbPath = expansionObbPath.orEmpty().trim(),
                relocateNativeLibraries = relocateExpansionNativeLibraries == true,
                embedExpansionObb = embedExpansionObb == true,
                removeRelocatedNativeLibrariesFromObb = removeRelocatedNativeLibrariesFromObb == true,
            )
        })
        dependsOn(legacyReceiverFlagsPatch { receiverFixAppWide == true })
        dependsOn(legacyDownloaderPendingIntentPatch { true })
        dependsOn(legacyLicenseServiceIntentPatch { true })
    } else {
        dependsOn(legacyCompatibilityBytecodePreflightPatch())
    }

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val playStorePackageVisibility = LegacyCompatibilityPresets.playStorePackageVisibility(
            presetId, playStorePackageVisibilityRaw, detectedEngines.openIab,
        )
        val obbPathForLog = expansionObbPath.orEmpty().trim()
        fun optionLogValue(value: Any?): String =
            value?.toString()?.filterNot { Character.isISOControl(it) }?.take(300) ?: "<unset>"

        logger.info(
            buildString {
                appendLine("Legacy App Compatibility options (selected values):")
                appendLine("  patchEnabled=true")
            appendLine("  diagnosticsOnly=${optionLogValue(diagnosticsOnly)}")
                appendLine("  spoofTargetSdk=${optionLogValue(spoofTargetSdk)}")
                appendLine("  targetSdk=${optionLogValue(targetSdk)}")
                appendLine("  targetSdkProfile=${optionLogValue(targetSdkProfile)}")
                appendLine("  legacyReviver=${optionLogValue(legacyReviver)}")
                appendLine("  apacheLegacy=${optionLogValue(apacheLegacy)}")
                appendLine("  foregroundService=${optionLogValue(foregroundService)}")
                appendLine("  exactAlarms=${optionLogValue(exactAlarms)}")
                appendLine("  bluetooth=${optionLogValue(bluetooth)}")
                appendLine("  openIabReceiverRegistrationMode=${optionLogValue(openIabReceiverRegistrationMode)}")
                appendLine("  bypassHiddenApi=${optionLogValue(bypassHiddenApi)}")
                appendLine("  trustCertificates=${optionLogValue(trustCertificates)}")
                appendLine("  acknowledgeTrustCertificates=${optionLogValue(acknowledgeTrustCertificates)}")
                appendLine("  redirectLegacyStorage=${optionLogValue(redirectLegacyStorage)}")
                appendLine("  receiverFixAppWide=${optionLogValue(receiverFixAppWide)}")
                appendLine("  repairExportFlags=${optionLogValue(repairExportFlags)}")
                appendLine("  exportAllActivityComponents=${optionLogValue(exportAllActivityComponents)}")
                appendLine("  allowCleartext=${optionLogValue(allowCleartext)}")
                appendLine("  relaxLibraries=${optionLogValue(relaxLibraries)}")
                appendLine("  playStorePackageVisibility=${optionLogValue(playStorePackageVisibility)}")
                appendLine("  bypassPackageVisibility=${optionLogValue(bypassPackageVisibility)}")
                appendLine("  manifestCompatAttributes=${optionLogValue(manifestCompatAttributes)}")
                appendLine("  legacyStorage=${optionLogValue(legacyStorage)}")
                appendLine("  expansionObbPath=${if (obbPathForLog.isBlank()) "<not set>" else "${optionLogValue(obbPathForLog.substringAfterLast('/'))} (directory redacted)"}")
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
                appendLine("  effectiveTrustCertificates=${trustCertificates == true && acknowledgeTrustCertificates == true}")
                appendLine("  effectiveEmbedExpansionObb=${embedExpansionObb == true && obbPathForLog.isNotBlank()}")
                appendLine("  effectiveBypassExpansionDownloader=${bypassExpansionDownloader == true && embedExpansionObb == true && obbPathForLog.isNotBlank()}")
            },
        )

        if (trustCertificates == true && acknowledgeTrustCertificates != true) {
            logger.warning("Legacy compatibility: Trust All Certificates was requested without the required high-risk acknowledgement; trust-all TLS remains disabled.")
        }
        fun reportManifestPreflight(manifest: Document) {
            legacyManifestPreflightFindings(
                document = manifest,
                spoofTargetSdk = spoofTargetSdk == true,
                targetSdkProfile = targetSdkProfile,
                customTargetSdk = targetSdk,
                repairExportFlags = repairExportFlags == true,
                exportAllActivityComponents = exportAllActivityComponents == true,
                playStorePackageVisibility = playStorePackageVisibility == true,
                bypassPackageVisibility = bypassPackageVisibility == true,
                embedExpansionObb = embedExpansionObb == true,
                expansionObbPath = obbPathForLog,
            ).forEach { logger.info(formatLegacyCompatibilityPreflightFinding(it)) }
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
        document("AndroidManifest.xml").use { manifest ->
            reportManifestPreflight(manifest)
            noApplication = manifest.documentElement.applicationOrNull() == null
            val originalTarget = originalTargetSdk(manifest)
            if (spoofTargetSdk == true) {
                val target = selectLegacyTargetSdk(originalTarget, targetSdkProfile, targetSdk ?: 34)
                if (target !in 23..40) {
                    logger.warning("Legacy compatibility: target SDK $target is outside the supported range 23..40.")
                } else if (updateTargetSdk(manifest, target)) {
                    changed++
                    logger.info("Legacy compatibility: target SDK set to $target using profile ${targetSdkProfile ?: TARGET_PROFILE_CUSTOM}.")
                    if (originalTarget != null && originalTarget > target) {
                        logger.warning("Legacy compatibility: original target SDK $originalTarget was higher than $target; platform behavior was downgraded to match target SDK $target.")
                    }
                }
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
                repairExportFlags == true -> {
                    val repaired = repairMissingComponentExportFlags(manifest, logger)
                    changed += repaired
                    if (repaired == 0) {
                        logger.info("Legacy compatibility: no filtered components with missing android:exported were found.")
                    } else {
                        logger.info("Legacy compatibility: repaired $repaired component exported flag(s).")
                    }
                }
            }
            if (allowCleartext == true) {
                if (setApplicationAttribute(manifest, "usesCleartextTraffic", "true")) changed++
                if (manifest.documentElement.applicationOrNull()?.hasAttributeNS(NS_ANDROID, "networkSecurityConfig") == true) {
                    logger.warning("Legacy compatibility: preserved networkSecurityConfig; it may still restrict cleartext traffic.")
                }
            }
            if (legacyStorage == true) {
                if (setApplicationAttribute(manifest, "requestLegacyExternalStorage", "true")) changed++
                if (addPermission(manifest, WRITE_EXTERNAL_STORAGE, maxSdkVersion = 32)) changed++
                val effectiveTarget = if (spoofTargetSdk == true) {
                    selectLegacyTargetSdk(originalTarget, targetSdkProfile, targetSdk ?: 34)
                } else {
                    originalTarget
                }
                if (effectiveTarget != null && effectiveTarget >= 30) {
                    logger.warning("Legacy compatibility: target SDK $effectiveTarget is 30 or higher; requestLegacyExternalStorage is ignored on Android 11 and newer.")
                }
            }
            if (bypassExpansionDownloader == true && detectedEngines.unity) {
                if (embedExpansionObb == true && expansionObbPath.orEmpty().trim().isNotEmpty()) {
                    changed += moveExpansionDownloaderLauncher(manifest, logger)
                } else {
                    logger.warning("Legacy compatibility: expansion downloader bypass requires Embed and stage expansion OBB plus a selected OBB file; launcher unchanged.")
                }
            }
            if (allScreens == true) {
                val (removed, updated) = supportAllScreens(manifest)
                changed += removed
                if (updated) changed++
            }
            if (relaxLibraries == true) changed += relaxSharedLibraries(manifest)
            if (playStorePackageVisibility == true && addPackageVisibilityQuery(manifest, "com.android.vending")) {
                changed++
                logger.info("Legacy compatibility: added Google Play package visibility query.")
            }
            if (bypassPackageVisibility == true) {
                if (addPermission(manifest, QUERY_ALL_PACKAGES)) {
                    changed++
                    logger.info("Legacy compatibility: added QUERY_ALL_PACKAGES for full package visibility.")
                }
            }
            if (manifestCompatAttributes == true) {
                if (setApplicationAttribute(manifest, "largeHeap", "true")) changed++
                if (setApplicationAttribute(manifest, "hardwareAccelerated", "true")) changed++
            }
            if (extractNativeLibs == true && (presetId == LegacyCompatibilityPresets.CUSTOM || detectedEngines.unity) && setApplicationAttribute(manifest, "extractNativeLibs", "true")) changed++
            if (disableHeapTagging == true && setApplicationAttribute(manifest, "allowNativeHeapPointerTagging", "false")) changed++
            if (vmSafeMode == true && setApplicationAttribute(manifest, "vmSafeMode", "true")) changed++
        }
        if (noApplication) {
            logger.warning("Legacy compatibility: no application element was found. Application-level options were skipped.")
        }
        if (changed == 0) logger.info("Legacy compatibility: no manifest changes were required.")
        else logger.info("Legacy compatibility: applied $changed manifest change(s).")
    }
}
