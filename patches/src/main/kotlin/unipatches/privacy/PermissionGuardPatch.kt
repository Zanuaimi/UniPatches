package unipatches.privacy

import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.resourcePatch
import helpers.manifest.NS_ANDROID
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.resourcePatch
import helpers.manifest.NS_ANDROID
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.logging.Logger

private val logger = Logger.getLogger("unipatches.privacy.PermissionGuardPatch")

internal val permissionGroups = linkedMapOf(
    "camera" to setOf("android.permission.CAMERA"),
    "microphone" to setOf("android.permission.RECORD_AUDIO"),
    "location" to setOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
    ),
    "contacts" to setOf(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
    ),
    "phone" to setOf(
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.CALL_PHONE",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.ADD_VOICEMAIL",
        "android.permission.USE_SIP",
    ),
    "sms" to setOf(
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.RECEIVE_MMS",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_WAP_PUSH",
    ),
    "calendar" to setOf(
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
    ),
    "storage" to setOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
    ),
    "media" to setOf(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    ),
    "notifications" to setOf("android.permission.POST_NOTIFICATIONS"),
    "nearbyDevices" to setOf(
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.NEARBY_WIFI_DEVICES",
    ),
    "bluetooth" to setOf(
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_ADMIN",
    ),
)

internal fun removeGuardedPermissions(document: Document, enabledGroups: Set<String>): Int {
    val blocked = enabledGroups.flatMap { permissionGroups[it].orEmpty() }.toSet()
    if (blocked.isEmpty()) return 0
    val root = document.documentElement ?: return 0
    val permissions = root.getElementsByTagName("uses-permission")
    var removed = 0
    for (index in permissions.length - 1 downTo 0) {
        val permission = permissions.item(index) as? Element ?: continue
        // Namespace-aware parsers expose the android:name attribute through the Android
        // namespace; plain parsers keep the literal prefixed name. Accept both forms.
        val declared = permission.getAttributeNS(NS_ANDROID, "name")
            .ifBlank { permission.getAttribute("android:name") }
        if (declared in blocked) {
            permission.parentNode?.removeChild(permission)
            removed++
        }
    }
    return removed
}
@Suppress("unused")
val permissionGuardPatch = resourcePatch(
    name = "Permission Guard Patch (Experimental)",
    description = """
        Remove selected dangerous permission declarations from the patched APK manifest. All
        permission controls are disabled by default; select each permission group before patching.

        This is a static manifest patch, not a runtime permission manager. It does not revoke
        permissions already granted to an installed app, stop native or privileged access, or
        prevent an app from requesting a permission at runtime. Android may still deny requests,
        and the app may lose features or fail if it requires a selected permission.

        Storage covers legacy external-storage permissions. Media covers Android 13+ photo, video,
        audio, and selected-photo permissions. Nearby devices covers modern Bluetooth and nearby
        Wi-Fi declarations; Bluetooth covers legacy Bluetooth declarations.
    """.trimIndent(),
    default = false,
) {
    try { category("Privacy") } catch (_: NoSuchMethodError) {}

    val camera by booleanOption(key = "permissionGuardCamera", title = "Permission Guard > Camera", default = false, description = "Remove CAMERA permission declaration.")
    val microphone by booleanOption(key = "permissionGuardMicrophone", title = "Permission Guard > Microphone", default = false, description = "Remove RECORD_AUDIO permission declaration.")
    val location by booleanOption(key = "permissionGuardLocation", title = "Permission Guard > Location", default = false, description = "Remove coarse, fine, and background location declarations.")
    val contacts by booleanOption(key = "permissionGuardContacts", title = "Permission Guard > Contacts", default = false, description = "Remove contacts and account access declarations.")
    val phone by booleanOption(key = "permissionGuardPhone", title = "Permission Guard > Phone", default = false, description = "Remove phone-state, call, voicemail, and SIP declarations.")
    val sms by booleanOption(key = "permissionGuardSms", title = "Permission Guard > SMS", default = false, description = "Remove SMS, MMS, and WAP push declarations.")
    val calendar by booleanOption(key = "permissionGuardCalendar", title = "Permission Guard > Calendar", default = false, description = "Remove calendar read and write declarations.")
    val storage by booleanOption(key = "permissionGuardStorage", title = "Permission Guard > Storage", default = false, description = "Remove legacy and broad external-storage declarations.")
    val media by booleanOption(key = "permissionGuardMedia", title = "Permission Guard > Media (Photos, Music)", default = false, description = "Remove modern photo, video, audio, and selected-photo declarations.")
    val notifications by booleanOption(key = "permissionGuardNotifications", title = "Permission Guard > Notifications", default = false, description = "Remove POST_NOTIFICATIONS declaration.")
    val nearbyDevices by booleanOption(key = "permissionGuardNearbyDevices", title = "Permission Guard > Nearby devices", default = false, description = "Remove modern nearby-device and Bluetooth scan/connect declarations.")
    val bluetooth by booleanOption(key = "permissionGuardBluetooth", title = "Permission Guard > Bluetooth", default = false, description = "Remove legacy Bluetooth declarations.")

    execute {
        val enabled = buildSet {
            if (camera == true) add("camera")
            if (microphone == true) add("microphone")
            if (location == true) add("location")
            if (contacts == true) add("contacts")
            if (phone == true) add("phone")
            if (sms == true) add("sms")
            if (calendar == true) add("calendar")
            if (storage == true) add("storage")
            if (media == true) add("media")
            if (notifications == true) add("notifications")
            if (nearbyDevices == true) add("nearbyDevices")
            if (bluetooth == true) add("bluetooth")
        }
        val removed = removeGuardedPermissions(document("AndroidManifest.xml"), enabled)
        logger.info("Permission Guard: removed $removed permission declaration(s)")
    }
}
