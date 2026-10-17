package unipatches.compatibility

import org.w3c.dom.Document
import org.w3c.dom.Element

internal fun currentAndroidApiLevel(): Int? = runCatching {
    Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null)
}.getOrNull()?.takeIf { it > 0 }

private val runtimePermissionNames = setOf(
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
    "android.permission.CAMERA",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.GET_ACCOUNTS",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_PHONE_STATE",
    "android.permission.CALL_PHONE",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.ADD_VOICEMAIL",
    "android.permission.USE_SIP",
    "android.permission.PROCESS_OUTGOING_CALLS",
    "android.permission.BODY_SENSORS",
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_WAP_PUSH",
    "android.permission.RECEIVE_MMS",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
)
private val mediaPermissionNames = setOf(
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
)

internal fun legacyApiCompatibilityFindings(
    document: Document,
    deviceApi: Int?,
    targetSdk: Int?,
    plannedTargetSdk: Int? = targetSdk,
): List<LegacyCompatibilityPreflightFinding> {
    val root = document.documentElement ?: return emptyList()
    val app = root.childElements().firstOrNull { it.localNameOrTag() == "application" }
    val permissions = buildSet {
        listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-29").forEach { tag ->
            val entries = document.getElementsByTagName(tag)
            for (index in 0 until entries.length) {
                (entries.item(index) as? Element)?.androidAttribute("name")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }
    val services = app?.childElements()?.filter { it.localNameOrTag() == "service" }.orEmpty()
    val receivers = app?.childElements()?.filter { it.localNameOrTag() == "receiver" }.orEmpty()
    val context = buildString {
        append("device API ${deviceApi ?: "unknown"}; manifest targetSdk ${targetSdk ?: "unknown"}")
        if (plannedTargetSdk != targetSdk) append("; planned targetSdk ${plannedTargetSdk ?: "unknown"} (not applied in diagnostics-only mode)")
    }
    val findings = mutableListOf<LegacyCompatibilityPreflightFinding>()

    if (deviceApi == null) {
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "Android runtime API context",
            detected = false,
            proposal = "run diagnostics from Morphe Manager on target Android device for API-specific findings",
            apiRange = context,
            confidence = "runtime API unavailable",
            uncertainty = "API-specific compatibility checks were not evaluated",
        )
        return findings
    }

    if (deviceApi >= 23) {
        val requested = permissions.intersect(runtimePermissionNames).sorted()
        if (requested.isNotEmpty()) {
            findings += LegacyCompatibilityPreflightFinding(
                pattern = "runtime permissions",
                detected = true,
                proposal = if (targetSdk != null && targetSdk >= 23) {
                    "verify app requests and handles denial for: ${requested.joinToString()}"
                } else {
                    "current legacy target may retain install-time grants; update app code to request permissions before raising targetSdk"
                },
                apiRange = "device API 23+; runtime model applies to targetSdk 23+; $context",
                confidence = "exact declared permission scan",
                uncertainty = "manifest declarations do not prove runtime request or denial-handling code exists",
            )
        }
    }

    if (deviceApi >= 26 && (services.isNotEmpty() || receivers.isNotEmpty())) {
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "background execution",
            detected = true,
            proposal = if (targetSdk != null && targetSdk >= 26) {
                "test background service starts and implicit broadcasts; move deferrable work to JobScheduler/WorkManager"
            } else {
                "raising targetSdk to 26+ activates stricter background limits; test service and broadcast flows first"
            },
            apiRange = "device API 26+; targetSdk 26+ activates major limits; $context",
            confidence = "service/receiver declarations found",
            uncertainty = "static manifest cannot prove which components perform background work or receive implicit broadcasts",
        )
    }

    if (deviceApi >= 31 && targetSdk != null && targetSdk >= 31 && services.isNotEmpty()) {
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "background foreground-service starts",
            detected = true,
            proposal = "audit calls to startForeground from background; Android 12+ blocks most starts without a documented exemption, so use a permitted flow or scheduled work",
            apiRange = "device API 31+ and manifest targetSdk 31+; $context",
            confidence = "service declarations found",
            uncertainty = "static manifest cannot determine start points, caller state, or whether a documented exemption applies",
        )
    }

    val storagePermissions = permissions.intersect(
        setOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
        ),
    )
    val requestsLegacyStorage = app?.hasAndroidAttribute("requestLegacyExternalStorage") == true
    if (deviceApi >= 29 && (targetSdk == null || targetSdk >= 29 || storagePermissions.isNotEmpty() || requestsLegacyStorage)) {
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "scoped storage",
            detected = storagePermissions.isNotEmpty() || requestsLegacyStorage,
            proposal = if (targetSdk != null && targetSdk >= 30) {
                "test shared files and OBB access under scoped storage; requestLegacyExternalStorage is ignored for targetSdk 30+"
            } else {
                "test shared-file and OBB access before raising targetSdk to 29/30; migrate to app-specific storage, SAF, or MediaStore as needed"
            },
            apiRange = "device API 29+; targetSdk 29/30 boundaries; $context",
            confidence = if (storagePermissions.isNotEmpty() || requestsLegacyStorage) "exact storage permission/attribute scan" else "API and targetSdk boundary only",
            uncertainty = "static manifest cannot detect direct filesystem paths or validate OBB/storage runtime access",
        )
    }

    if (deviceApi >= 33 && (targetSdk == null || targetSdk >= 33 || "android.permission.POST_NOTIFICATIONS" in permissions)) {
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "notification permission",
            detected = "android.permission.POST_NOTIFICATIONS" in permissions,
            proposal = if (targetSdk != null && targetSdk >= 33) {
                "declare POST_NOTIFICATIONS only if needed and request it at runtime; handle denial"
            } else {
                "Android controls notification prompt timing for targetSdk 32 and lower; test notification behavior before raising targetSdk 33+"
            },
            apiRange = "device API 33+; targetSdk 33+ runtime request; $context",
            confidence = "exact permission declaration and API/target boundary",
            uncertainty = "manifest does not reveal notification channel setup or runtime permission request code",
        )
    }

    val mediaPermissions = permissions.intersect(mediaPermissionNames).sorted()
    if (deviceApi >= 33 && (mediaPermissions.isNotEmpty() || (targetSdk != null && targetSdk >= 33))) {
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "media permissions and partial photo access",
            detected = mediaPermissions.isNotEmpty(),
            proposal = if (deviceApi >= 34 && targetSdk != null && targetSdk >= 34) {
                "verify image/video permission requests and handle Android 14 partial photo access via READ_MEDIA_VISUAL_USER_SELECTED or the system picker"
            } else {
                "request only required READ_MEDIA_* permissions at runtime and handle denial; avoid broad storage assumptions"
            },
            apiRange = "device API 33+; Android 14/API 34 partial photo access; $context",
            confidence = if (mediaPermissions.isNotEmpty()) "exact media permission declaration scan" else "API and targetSdk boundary only",
            uncertainty = "static manifest cannot prove which media types or access flows the app uses",
        )
    }

    if (deviceApi >= 33 && (receivers.isNotEmpty() || (targetSdk != null && targetSdk >= 33))) {
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "broadcast receiver registration",
            detected = receivers.isNotEmpty(),
            proposal = "verify dynamic registerReceiver calls specify RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED where required; keep ambiguous registrations unchanged",
            apiRange = "device API 33+; targetSdk 33+ dynamic receiver flag enforcement; $context",
            confidence = if (receivers.isNotEmpty()) "static receiver declarations found; DEX call scan reported separately" else "API and targetSdk boundary only",
            uncertainty = "manifest cannot show dynamic registration flags or whether receiver filters contain system broadcasts",
        )
    }

    if (deviceApi >= 28 && services.isNotEmpty() && (targetSdk == null || targetSdk >= 28)) {
        val untypedServices = services.filter { it.androidAttribute("foregroundServiceType").isBlank() }
            .map { it.androidAttribute("name").ifBlank { "unnamed service" } }
        val missingPermission = "android.permission.FOREGROUND_SERVICE" !in permissions
        findings += LegacyCompatibilityPreflightFinding(
            pattern = "foreground-service declarations",
            detected = services.any { it.androidAttribute("foregroundServiceType").isNotBlank() } || missingPermission,
            proposal = when {
                deviceApi >= 34 && targetSdk != null && targetSdk >= 34 && untypedServices.isNotEmpty() ->
                    "inspect startForeground call sites, map only proven services to exact types, and add required type-specific permissions; untyped: ${untypedServices.take(10).joinToString()}"
                missingPermission -> "if any service calls startForeground, declare FOREGROUND_SERVICE and test its notification and start flow"
                else -> "verify service types and type-specific permissions against actual startForeground call sites; no type inferred"
            },
            apiRange = "device API 28+ permission; API 34+ and targetSdk 34+ require type declarations; $context",
            confidence = "exact service/type/permission manifest scan; FGS use is not inferred",
            uncertainty = "manifest cannot prove that a service calls startForeground or identify its required type/permission",
        )
    }

    return findings
}

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

private fun Element.localNameOrTag(): String = localName ?: tagName.substringAfter(':')
