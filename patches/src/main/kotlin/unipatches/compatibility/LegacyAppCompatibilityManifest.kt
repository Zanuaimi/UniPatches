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

internal fun addPackageVisibilityQuery(document: Document, packageName: String): Boolean {
    val root = document.documentElement ?: return false
    val queries = (0 until root.childNodes.length)
        .mapNotNull { root.childNodes.item(it) as? Element }
        .firstOrNull { it.tagName == "queries" }
        ?: document.createElement("queries").also {
            root.insertBefore(it, root.firstChild)
        }
    val exists = queries.getElementsByTagName("package").let { packages ->
        (0 until packages.length).any {
            (packages.item(it) as? Element)?.getAttributeNS(NS_ANDROID, "name") == packageName
        }
    }
    if (exists) return false
    document.createElement("package").also {
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

internal fun Element.androidAttribute(name: String): String =
    getAttributeNS(NS_ANDROID, name).ifEmpty { getAttribute("android:$name") }

internal fun Element.hasAndroidAttribute(name: String): Boolean =
    hasAttributeNS(NS_ANDROID, name) || getAttribute("android:$name").isNotEmpty()

internal fun Element.intentFilters(): List<Element> {
    val plain = getElementsByTagName("intent-filter")
    if (plain.length > 0) {
        return (0 until plain.length).mapNotNull { plain.item(it) as? Element }
    }
    val namespaced = getElementsByTagNameNS("*", "intent-filter")
    return (0 until namespaced.length).mapNotNull { namespaced.item(it) as? Element }
}

internal fun Element.hasIntentFilter(): Boolean = intentFilters().isNotEmpty()

internal fun Element.isLauncherComponent(): Boolean {
    for (filter in intentFilters()) {
        val actions = filter.getElementsByTagName("action")
        val namespacedActions = if (actions.length == 0) filter.getElementsByTagNameNS("*", "action") else null
        val hasMainAction = (0 until (namespacedActions?.length ?: actions.length)).any { index ->
            val action = (namespacedActions?.item(index) ?: actions.item(index)) as? Element
            action?.androidAttribute("name") == "android.intent.action.MAIN"
        }
        if (!hasMainAction) continue

        val categories = filter.getElementsByTagName("category")
        val namespacedCategories = if (categories.length == 0) filter.getElementsByTagNameNS("*", "category") else null
        val hasLauncherCategory = (0 until (namespacedCategories?.length ?: categories.length)).any { index ->
            val category = (namespacedCategories?.item(index) ?: categories.item(index)) as? Element
            category?.androidAttribute("name") == "android.intent.category.LAUNCHER"
        }
        if (hasLauncherCategory) {
            return true
        }
    }
    return false
}

internal fun repairMissingComponentExportFlags(document: Document, logger: Logger): Int {
    var repaired = 0
    for (tagName in exportedComponentTags) {
        val components = document.getElementsByTagName(tagName)
        for (index in 0 until components.length) {
            val component = components.item(index) as? Element ?: continue
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
    var changed = 0
    var preservedFalseCount = 0
    for (tagName in listOf("activity", "activity-alias")) {
        val activities = document.getElementsByTagName(tagName)
        for (index in 0 until activities.length) {
            val activity = activities.item(index) as? Element ?: continue
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

    val spoofTargetSdk by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Spoof Target SDK",
        default = false,
        key = "legacyCompatibilitySpoofTargetSdk",
        description = "Try this if Android blocks an older app from installing or launching because its target SDK is too old. Lowering the target can restore older Android behavior and reduce platform protections; it cannot fix incompatible app code. Leave off unless you have this specific problem.",
    )
    val targetSdk by intOption(
        title = "Legacy App Compatibility > Installation and manifest > Target SDK version",
        default = 34,
        key = "legacyCompatibilityTargetSdk",
        description = "Choose the target SDK number only when the Custom profile is selected. Android accepts values 23–40 here; other values are rejected. Lower values can restore legacy behavior but may weaken security and privacy protections. Default: 34.",
    )
    val targetSdkProfile by stringOption(
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
    val legacyReviver by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Legacy App Reviver",
        default = true,
        key = "legacyCompatibilityReviver",
        description = "Turn this on to add the compatibility declarations selected below. Leave it off if the app does not need them; enabling declarations does not grant runtime permissions or guarantee the app will work.",
    )
    val apacheLegacy by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Apache HTTP legacy library",
        default = true,
        key = "legacyCompatibilityApacheLegacy",
        description = "Use when the app crashes or fails to start because it uses the removed Apache HttpClient library. Adds that library as optional; it will not help if the app needs other missing libraries. Requires Legacy App Reviver.",
    )
    val foregroundService by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Foreground service permission",
        default = true,
        key = "legacyCompatibilityForegroundService",
        description = "Use when an older app starts a foreground service and fails because its manifest lacks this permission. This only declares permission; Android may require additional service permissions or user-visible behavior. Requires Legacy App Reviver.",
    )
    val exactAlarms by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Exact alarm permission",
        default = false,
        key = "legacyCompatibilityExactAlarms",
        description = "Use only if the app's reminders or scheduled actions must run at an exact time and are blocked by the missing declaration. Android can still require user approval, and this does not grant that approval. Requires Legacy App Reviver.",
    )
    val bluetooth by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Bluetooth permissions",
        default = true,
        key = "legacyCompatibilityBluetooth",
        description = "Use when the app needs Bluetooth discovery or connections on newer Android. The manifest declarations do not grant runtime access; Android may still show permission prompts. Requires Legacy App Reviver.",
    )
    val openIabReceiverRegistrationMode by stringOption(
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
    val bypassHiddenApi by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Bypass Hidden API Restrictions",
        default = false,
        key = "legacyCompatibilityHiddenApi",
        description = "Try this only if the app fails because it uses hidden Android framework APIs through reflection. It bypasses the non-SDK API restriction for this app's process on Android 9+, which can reduce stability and security; leave off otherwise. Takes effect at app startup.",
    )
    val trustCertificates by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Trust All Certificates",
        default = false,
        key = "legacyCompatibilityTrustCertificates",
        description = "Last-resort workaround if this app cannot connect because its server uses an expired or self-signed certificate. It disables certificate and hostname checks for HttpsURLConnection, so attackers on the network could read or alter traffic. Do not use for sensitive accounts or payments. Does not affect WebView; requires the separate risk acknowledgement.",
    )
    val acknowledgeTrustCertificates by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Acknowledge Trust-All TLS Risk",
        default = false,
        key = "legacyCompatibilityAcknowledgeTrustCertificates",
        description = "Enable only after understanding that Trust All Certificates allows network interception by disabling TLS checks. It is required for that option to take effect; leave both off unless diagnosing a certificate problem.",
    )
    val redirectLegacyStorage by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Redirect Legacy External Storage Paths",
        default = false,
        key = "legacyCompatibilityRedirectLegacyStorage",
        description = "Try this if the app crashes or loses saves because it writes directly to the shared-storage root. Those API calls are redirected to app-specific storage, which can break shared-file access and OBB paths. Leave off if the app relies on files visible to other apps or uses conventional OBB directories.",
    )
    val receiverFixAppWide by booleanOption(
        title = "Legacy App Compatibility > Runtime compatibility > Fix Dynamic Receiver Registrations (App Wide)",
        default = true,
        key = "legacyCompatibilityReceiverFixAppWide",
        description = "Keep enabled for older apps that crash while registering a broadcast receiver on Android 13+. The patch marks affected app receivers as private to this app; broadcasts sent by other apps may stop arriving, and some broadcasts from privileged system apps may also be unavailable. OpenIAB is handled by its separate option. Turn off if this breaks a receiver you rely on.",
    )
    val repairExportFlags by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Repair Missing Component Export Flags",
        default = false,
        key = "legacyCompatibilityRepairExportFlags",
        description = "Use if Android refuses to install the APK because a component with an intent filter has no exported setting. The patch fills only missing values; exported components can be launched by other apps, so enable only to fix that install error. Do not combine with Export All Activities; if both are on, that option takes precedence.",
    )
    val exportAllActivityComponents by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Export All Activities",
        default = false,
        key = "legacyCompatibilityExportAllActivities",
        description = "Use only if a launcher activity is not visible or Android rejects the APK because an activity or alias with an intent filter lacks an exported setting. It exposes those matching components to other apps; existing explicit values are preserved. Do not combine with Repair Missing Component Export Flags.",
    )
    val allowCleartext by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Allow Cleartext Traffic",
        default = false,
        key = "legacyCompatibilityAllowCleartext",
        description = "Try this if the app cannot connect to a server that supports only plain HTTP. Unencrypted traffic can be read or changed on the network, so do not enable for logins, payments, or other sensitive data. An existing network security configuration may still block HTTP.",
    )
    val relaxLibraries by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Relax Shared Libraries",
        default = false,
        key = "legacyCompatibilityRelaxLibraries",
        description = "Use if Android will not install the app because an optional device library is missing. This lets installation continue, but the app may crash or lose features if it truly needs that library.",
    )
    val playStorePackageVisibility by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Play Store Package Visibility",
        default = false,
        key = "legacyCompatibilityPlayStoreVisibility",
        description = "Use if the app incorrectly reports that Google Play is not installed on Android 11+. It allows checks for Google Play only, not other apps, and is preferable to broad package visibility when Play is the only check needed.",
    )
    val bypassPackageVisibility by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Bypass Package Visibility (Broad)",
        default = false,
        key = "legacyCompatibilityPackageVisibility",
        description = "Use only if the app must check for many different installed apps and those checks fail on Android 11+. This requests visibility into all installed packages, which exposes more information and may be restricted by app-store policy. Prefer Play Store Package Visibility if only Google Play is needed.",
    )
    val manifestCompatAttributes by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Extra Manifest Compatibility Attributes",
        default = false,
        key = "legacyCompatibilityManifestAttributes",
        description = "Try if the app runs out of memory or shows a blank screen: enables a larger heap and hardware-accelerated drawing. The larger heap can increase memory pressure, and GPU drawing may break apps that depend on software rendering; leave off if the app already displays and runs correctly.",
    )
    val legacyStorage by booleanOption(
        title = "Legacy App Compatibility > Storage and display > Legacy External Storage",
        default = false,
        key = "legacyCompatibilityLegacyStorage",
        description = "Use if an older app cannot read or save files because it expects the Android 10 shared-storage model. Android 11+ may ignore this request, and it does not bypass newer storage restrictions.",
    )
    val expansionObbPath by filePathOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Expansion OBB file",
        default = "",
        key = "legacyCompatibilityExpansionObbPath",
        allowedExtensions = listOf("obb"),
        description = "Select the app's matching main or patch .obb file only when it fails to start because its Play expansion data is missing. Use the exact version and package naming expected by the app; leave empty if it does not need an OBB.",
    )
    val relocateExpansionNativeLibraries by booleanOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Relocate native libraries from OBB",
        default = false,
        key = "legacyCompatibilityRelocateExpansionNativeLibraries",
        description = "Use if the app's native .so libraries are incorrectly stored inside the selected OBB and the game cannot load them. Copies them into the APK's matching ABI library folders; leave off if the OBB has no such libraries or the app already works.",
    )
    val removeRelocatedNativeLibrariesFromObb by booleanOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Remove relocated native libraries from embedded OBB",
        default = false,
        key = "legacyCompatibilityRemoveRelocatedNativeLibrariesFromObb",
        description = "Use together with library relocation and OBB embedding to avoid storing the same native libraries twice. It removes those copied .so entries from the APK's embedded OBB only; the selected source OBB file is not changed.",
    )
    val embedExpansionObb by booleanOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Embed and stage expansion OBB",
        default = false,
        key = "legacyCompatibilityEmbedExpansionObb",
        description = "Use when the app needs an OBB at first launch and no downloader can provide it. The file is bundled inside the APK and copied to the expected OBB folder; this can greatly increase APK size and may hit storage or distribution limits.",
    )
    val bypassExpansionDownloader by booleanOption(
        title = "Legacy App Compatibility > Expansion data and OBB > Bypass known Unity expansion downloader",
        default = false,
        key = "legacyCompatibilityBypassExpansionDownloader",
        description = "Use only for the recognized Unity expansion downloader when it blocks startup despite bundling the OBB. It redirects launcher handling to Unity's known launcher; it is not a general downloader repair and requires Embed and stage expansion OBB.",
    )
    val allScreens by booleanOption(
        title = "Legacy App Compatibility > Storage and display > Support All Screens",
        default = true,
        key = "legacyCompatibilityAllScreens",
        description = "Keep enabled so Android can install the app on phones and tablets whose screen size or density is not listed in its original manifest. This changes compatibility declarations, not the app's layout; some screens may still display poorly.",
    )
    val extractNativeLibs by booleanOption(
        title = "Legacy App Compatibility > Native runtime > Extract native libraries",
        default = false,
        key = "legacyCompatibilityExtractNativeLibs",
        description = "Use if an older game cannot load its native .so files from the APK. Android will extract those libraries during installation, using more device storage; leave off if native libraries already load normally.",
    )
    val disableHeapTagging by booleanOption(
        title = "Legacy App Compatibility > Native runtime > Disable Heap Pointer Tagging",
        default = false,
        key = "legacyCompatibilityDisableHeapTagging",
        description = "Try this if an older native game crashes on startup with a memory/tagging-related error on newer Android. It changes native memory behavior for compatibility; leave off if there is no such crash.",
    )
    val vmSafeMode by booleanOption(
        title = "Legacy App Compatibility > Native runtime > VM Safe Mode",
        default = false,
        key = "legacyCompatibilityVmSafeMode",
        description = "Try this only if an older app crashes or misbehaves because of Android runtime compilation. Disabling selected optimizations can make the app slower; leave off unless testing shows it fixes the problem.",
    )
    val spoofImei by booleanOption(
        title = "Legacy App Compatibility > Device compatibility > Spoof IMEI",
        default = false,
        key = "legacyCompatibilitySpoofImei",
        description = "Use only if the app refuses to run because it expects an IMEI value. Replaces recognized IMEI getter results with the value below; only matching calls are changed, and apps may use other identifiers instead. Avoid using an identifier that belongs to another device.",
    )
    val imei by stringOption(
        title = "Legacy App Compatibility > Device compatibility > IMEI value",
        default = "000000000000000",
        key = "legacyCompatibilityImei",
        description = "Enter exactly 15 digits to return from the IMEI calls affected by Spoof IMEI. Invalid values are rejected. This is an app-compatibility override, not a real device identifier.",
    )

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

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val obbPathForLog = expansionObbPath.orEmpty().trim()
        fun optionLogValue(value: Any?): String =
            value?.toString()?.filterNot { Character.isISOControl(it) }?.take(300) ?: "<unset>"

        logger.info(
            buildString {
                appendLine("Legacy App Compatibility options (selected values):")
                appendLine("  patchEnabled=true")
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
        var changed = 0
        var noApplication = false
        document("AndroidManifest.xml").use { manifest ->
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
            if (bypassExpansionDownloader == true) {
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
            if (extractNativeLibs == true && setApplicationAttribute(manifest, "extractNativeLibs", "true")) changed++
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
