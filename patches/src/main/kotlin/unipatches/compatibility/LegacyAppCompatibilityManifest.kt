package unipatches.compatibility

import app.morphe.patcher.patch.booleanOption
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
internal const val USE_EXACT_ALARM = "android.permission.USE_EXACT_ALARM"
internal const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
internal const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"

internal fun hasPermission(document: Document, name: String): Boolean =
    listOf("uses-permission", "uses-permission-sdk-23").any { tag ->
        val permissions = document.getElementsByTagName(tag)
        (0 until permissions.length).any {
            (permissions.item(it) as? Element)?.getAttributeNS(NS_ANDROID, "name") == name
        }
    }

internal fun addPermission(document: Document, name: String): Boolean {
    if (hasPermission(document, name)) return false
    val root = document.documentElement ?: return false
    val permission = document.createElement("uses-permission")
    permission.setAttributeNS(NS_ANDROID, "android:name", name)
    root.appendChild(permission)
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
    if (alarms) {
        if (addPermission(document, SCHEDULE_EXACT_ALARM)) changed++
        if (addPermission(document, USE_EXACT_ALARM)) changed++
    }
    if (bluetooth) {
        if (addPermission(document, BLUETOOTH_CONNECT)) changed++
        if (addPermission(document, BLUETOOTH_SCAN)) changed++
    }
    return changed
}

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

internal fun exportAllActivities(document: Document): Int {
    var changed = 0
    for (tagName in listOf("activity", "activity-alias")) {
        val activities = document.getElementsByTagName(tagName)
        for (index in 0 until activities.length) {
            val activity = activities.item(index) as? Element ?: continue
            if (activity.getAttributeNS(NS_ANDROID, "exported") == "true") continue
            activity.setAttributeNS(NS_ANDROID, "android:exported", "true")
            changed++
        }
    }
    return changed
}

@Suppress("unused")
val legacyAppCompatibilityPatch = resourcePatch(
    name = "Improve Legacy App / Game Compatibility for Modern Android Patch ( Experimental, Enhanced )",
    description = """
        Improve compatibility for older apps and games on modern Android versions. This patch combines
        legacy manifest, storage, screen, native runtime, network, shared-library, and optional device
        identity compatibility controls, including a configurable OpenIAB dynamic receiver compatibility
        fix. Spoof Target SDK can help older apps that modern Android may
        refuse to install or launch, but changing the reported target can also enable newer platform
        behavior and cannot repair incompatible application code.

        For Google Play license checks or Google Play Services checks, use Control Embedded Auth / Stores
        Patch. Those options are intentionally kept separate to prevent overlapping injections.

        This patch cannot restore shut-down servers, missing CPU architecture support, server licensing,
        Play Integrity, or unsupported native code. Conservative compatibility features are enabled by
        default; more invasive native, network, storage, library, and identity options remain disabled.

        Experimental: Its functionalities are not guaranteed to work in all apps.

        Credits: Nai64Patches from Nai64 for the original legacy compatibility functionality. UniPatches
        provides the merged settings, validation, manifest safeguards, compatibility organization, and provides suppress GPlay Login UI patch option.
    """.trimIndent(),
    default = false,
) {
    try { category("Legacy App Compatibility") } catch (_: NoSuchMethodError) {}

    val spoofTargetSdk by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Spoof Target SDK",
        default = true,
        key = "legacyCompatibilitySpoofTargetSdk",
        description = "Report a compatible target SDK so older apps can install or launch on modern Android. This may change platform behavior and does not repair incompatible code.",
    )
    val targetSdk by intOption(
        title = "Legacy App Compatibility > Installation and manifest > Target SDK version",
        default = 34,
        key = "legacyCompatibilityTargetSdk",
        description = "Target SDK to write when Spoof Target SDK is enabled. Values below 23 are rejected because modern Android can block them. Default: 34.",
    )
    val legacyReviver by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Legacy App Reviver",
        default = true,
        key = "legacyCompatibilityReviver",
        description = "Enable selected manifest compatibility declarations for older apps. The sub-options apply only when this feature is enabled.",
    )
    val apacheLegacy by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Apache HTTP legacy library",
        default = true,
        key = "legacyCompatibilityApacheLegacy",
        description = "When Legacy App Reviver is enabled, add org.apache.http.legacy as an optional shared library for old HttpClient users.",
    )
    val foregroundService by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Foreground service permission",
        default = true,
        key = "legacyCompatibilityForegroundService",
        description = "When Legacy App Reviver is enabled, declare FOREGROUND_SERVICE for older background-service apps.",
    )
    val exactAlarms by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Exact alarm permissions",
        default = true,
        key = "legacyCompatibilityExactAlarms",
        description = "When Legacy App Reviver is enabled, declare exact-alarm permissions. Android may still require user approval.",
    )
    val bluetooth by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Bluetooth permissions",
        default = true,
        key = "legacyCompatibilityBluetooth",
        description = "When Legacy App Reviver is enabled, declare modern Bluetooth permissions. Declarations do not grant runtime access.",
    )
    val openIabReceiverRegistrationMode by stringOption(
        title = "Legacy App Compatibility > Runtime compatibility > Fix OpenIAB dynamic receiver registration",
        default = OPEN_IAB_AUTOMATIC,
        key = "legacyCompatibilityOpenIabReceiverRegistrationMode",
        description = "Automatic applies the fix only when the exact OpenIAB UnityPlugin call, receiver ownership, resolvable IntentFilter actions, recognized internal or external-store action set, and safe register layout are confirmed. It uses NOT_EXPORTED for internal receivers and EXPORTED only for verified external-store broadcasts; unknown or mixed actions are skipped. Disabled leaves OpenIAB unchanged. Force applies the exact method fix for troubleshooting and may affect external billing broadcasts. Automatic is recommended.",
        values = linkedMapOf(
            "Automatic (recommended)" to OPEN_IAB_AUTOMATIC,
            "Disabled" to OPEN_IAB_DISABLED,
            "Force OpenIAB receiver fix" to OPEN_IAB_FORCE,
        ),
    )
    val repairExportFlags by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Repair Missing Component Export Flags",
        default = true,
        key = "legacyCompatibilityRepairExportFlags",
        description = "Add missing android:exported values to activities, aliases, services, and receivers that have intent filters. Do NOT enable this together with Export All Activities because they overlap. If both are selected accidentally, Export All Activities takes precedence.",
    )
    val exportAllActivityComponents by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Export All Activities",
        default = false,
        key = "legacyCompatibilityExportAllActivities",
        description = "Set android:exported=true on every activity and activity-alias. Do NOT enable this together with Repair Missing Component Export Flags because they overlap. If both are selected accidentally, this option takes precedence.",
    )
    val allowCleartext by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Allow Cleartext Traffic",
        default = false,
        key = "legacyCompatibilityAllowCleartext",
        description = "Allow HTTP traffic through android:usesCleartextTraffic. Existing networkSecurityConfig is preserved and may continue restricting cleartext traffic.",
    )
    val relaxLibraries by booleanOption(
        title = "Legacy App Compatibility > Installation and manifest > Relax Shared Libraries",
        default = false,
        key = "legacyCompatibilityRelaxLibraries",
        description = "Mark required uses-library entries as optional so missing shared libraries do not block installation. The app may still crash if it actually requires a library.",
    )
    val legacyStorage by booleanOption(
        title = "Legacy App Compatibility > Storage and display > Legacy External Storage",
        default = false,
        key = "legacyCompatibilityLegacyStorage",
        description = "Request the Android 10 legacy shared-storage model and add WRITE_EXTERNAL_STORAGE if absent. Android 11 and newer may ignore this setting.",
    )
    val allScreens by booleanOption(
        title = "Legacy App Compatibility > Storage and display > Support All Screens",
        default = true,
        key = "legacyCompatibilityAllScreens",
        description = "Remove compatible-screens restrictions and mark common screen sizes and densities as supported.",
    )
    val disableHeapTagging by booleanOption(
        title = "Legacy App Compatibility > Native runtime > Disable Heap Pointer Tagging",
        default = false,
        key = "legacyCompatibilityDisableHeapTagging",
        description = "Disable native heap pointer tagging for older native apps that fail under newer Android memory behavior.",
    )
    val vmSafeMode by booleanOption(
        title = "Legacy App Compatibility > Native runtime > VM Safe Mode",
        default = false,
        key = "legacyCompatibilityVmSafeMode",
        description = "Disable selected VM AOT/JIT optimizations for compatibility. This can reduce performance.",
    )
    val spoofImei by booleanOption(
        title = "Legacy App Compatibility > Device compatibility > Spoof IMEI",
        default = false,
        key = "legacyCompatibilitySpoofImei",
        description = "Replace recognized TelephonyManager IMEI getter results. This is limited to matching bytecode calls.",
    )
    val imei by stringOption(
        title = "Legacy App Compatibility > Device compatibility > IMEI value",
        default = "000000000000000",
        key = "legacyCompatibilityImei",
        description = "Exactly 15 digits used when Spoof IMEI is enabled. Invalid values are rejected.",
    )

    dependsOn(legacyImeiPatch { Pair(spoofImei == true, imei.orEmpty().trim()) })
    dependsOn(openIabReceiverFlagsPatch { openIabReceiverRegistrationMode ?: OPEN_IAB_AUTOMATIC })

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        var changed = 0
        var noApplication = false
        document("AndroidManifest.xml").use { manifest ->
            noApplication = manifest.documentElement.applicationOrNull() == null
            if (spoofTargetSdk == true) {
                val target = targetSdk ?: 34
                if (target !in 23..40) {
                    logger.warning("Legacy compatibility: target SDK $target is outside the supported range 23..40.")
                } else if (updateTargetSdk(manifest, target)) {
                    changed++
                    logger.info("Legacy compatibility: target SDK set to $target.")
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
            when {
                exportAllActivityComponents == true -> {
                    if (repairExportFlags == true) {
                        logger.info("Legacy compatibility: both exported-component options selected; Export All Activities takes precedence and repair mode is skipped.")
                    } else {
                        logger.info("Legacy compatibility: Export All Activities selected.")
                    }
                    val exported = exportAllActivities(manifest)
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
                if (addPermission(manifest, WRITE_EXTERNAL_STORAGE)) changed++
            }
            if (allScreens == true) {
                val (removed, updated) = supportAllScreens(manifest)
                changed += removed
                if (updated) changed++
            }
            if (relaxLibraries == true) changed += relaxSharedLibraries(manifest)
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
