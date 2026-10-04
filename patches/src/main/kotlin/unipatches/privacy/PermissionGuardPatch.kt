package unipatches.privacy

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import helpers.manager.UNI_MANAGER_PERMISSION_GUARD_METADATA_NAME
import helpers.manager.encodeUniManagerMetadata
import helpers.manager.uniManagerMetadataPatch
import unipatches.overlay.PermissionGuardOverlayIntegration
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.bytecode.cloneMutable
import helpers.bytecode.numberOfParameterRegisters
import helpers.bytecode.p0Register
import helpers.startup.StartupHooks
import helpers.startup.resolveStartupEntryPoint
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import java.util.logging.Logger

private val logger = Logger.getLogger("unipatches.privacy.PermissionGuardPatch")

internal const val PERMISSION_GUARD_PROFILE = "permissionGuardRuntime"
internal const val PERMISSION_GUARD_CAPABILITY = "permission.guard.v1"

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
    "calendar" to setOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
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
    "internet" to setOf("android.permission.INTERNET"),
    "nearbyDevices" to setOf(
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.NEARBY_WIFI_DEVICES",
    ),
    "bluetooth" to setOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN"),
)

internal fun permissionGuardGroups(blocked: Map<String, Boolean>): List<String> =
    permissionGroups.keys.filter { blocked[it] == true }

private fun permissionGroupLabel(group: String): String = when (group) {
    "sms" -> "SMS"
    "nearbyDevices" -> "Nearby devices"
    "bluetooth" -> "Legacy Bluetooth"
    "internet" -> "Internet"
    else -> group.replaceFirstChar(Char::uppercase)
}

private data class GuardTarget(val wrapper: String)

private fun permissionGuardMetadata(blockedGroups: List<String>): String {
    val root = JsonObject().apply {
        addProperty("format", "unipatches-unimanager-registration-v1")
        addProperty("protocol_version", 2)
        addProperty("source_version", "unipatches-dev")
        add("patches", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("id", "permission-guard")
                addProperty("version", "1")
                add("configuration_prefixes", JsonArray().apply { add("permissionGuard") })
            })
        })
        add("capabilities", JsonArray().apply { add(PERMISSION_GUARD_CAPABILITY) })
        add("configuration", JsonObject().apply {
            permissionGroups.keys.forEach { group ->
                addProperty("permissionGuard${group.replaceFirstChar(Char::uppercase)}", group in blockedGroups)
            }
        })
        add("configuration_schema", JsonArray().apply {
            permissionGroups.keys.forEach { group ->
                add(JsonObject().apply {
                    addProperty("key", "permissionGuard${group.replaceFirstChar(Char::uppercase)}")
                    val label = permissionGroupLabel(group)
                    val optionLabel = if (group == "internet") "Block INTERNET Permission Checks" else "Block $label"
                    addProperty("label", "Permission groups > $label > $optionLabel")
                    addProperty("type", "boolean")
                })
            }
        })
    }
    return encodeUniManagerMetadata(root.toString())
}

private val guardTargets = mapOf(
    "Landroid/content/Context;#checkSelfPermission" to GuardTarget(
        "Lunipatch/overlaycore/PermissionGuardRuntime;->checkSelfPermission(Landroid/content/Context;Ljava/lang/String;)I",
    ),
    "Landroid/content/Context;#checkCallingPermission" to GuardTarget(
        "Lunipatch/overlaycore/PermissionGuardRuntime;->checkCallingPermission(Landroid/content/Context;Ljava/lang/String;)I",
    ),
    "Landroid/content/Context;#checkCallingOrSelfPermission" to GuardTarget(
        "Lunipatch/overlaycore/PermissionGuardRuntime;->checkCallingOrSelfPermission(Landroid/content/Context;Ljava/lang/String;)I",
    ),
    "Landroidx/core/content/ContextCompat;#checkSelfPermission" to GuardTarget(
        "Lunipatch/overlaycore/PermissionGuardRuntime;->checkSelfPermissionCompat(Landroid/content/Context;Ljava/lang/String;)I",
    ),
    "Landroid/app/Activity;#requestPermissions" to GuardTarget(
        "Lunipatch/overlaycore/PermissionGuardRuntime;->requestPermissions(Landroid/app/Activity;[Ljava/lang/String;I)V",
    ),
    "Landroidx/core/app/ActivityCompat;#requestPermissions" to GuardTarget(
        "Lunipatch/overlaycore/PermissionGuardRuntime;->requestPermissionsCompat(Landroid/app/Activity;[Ljava/lang/String;I)V",
    ),
)

private fun targetFor(reference: MethodReference): GuardTarget? {
    val key = "${reference.definingClass}#${reference.name}"
    val target = guardTargets[key] ?: return null
    val valid = when (key) {
        "Landroidx/core/content/ContextCompat;#checkSelfPermission" ->
            reference.parameterTypes == listOf("Landroid/content/Context;", "Ljava/lang/String;") && reference.returnType == "I"
        "Landroid/app/Activity;#requestPermissions" ->
            reference.parameterTypes == listOf("[Ljava/lang/String;", "I") && reference.returnType == "V"
        "Landroidx/core/app/ActivityCompat;#requestPermissions" ->
            reference.parameterTypes == listOf("Landroid/app/Activity;", "[Ljava/lang/String;", "I") && reference.returnType == "V"
        else -> reference.parameterTypes == listOf("Ljava/lang/String;") && reference.returnType == "I"
    }
    return target.takeIf { valid }
}

internal fun permissionGuardInvokeOpcode(instruction: String): String =
    if (instruction.trimStart().substringBefore(' ').endsWith("/range")) "invoke-static/range" else "invoke-static"

private fun registers(instruction: String): String? {
    val start = instruction.indexOf('{')
    val end = instruction.indexOf('}', start + 1)
    return if (start >= 0 && end > start) instruction.substring(start + 1, end) else null
}

internal fun BytecodePatchContext.guardPermissionCalls(): Int {
    var patched = 0
    classDefForEach { classDef ->
        val mutableClass = mutableClassDefBy(classDef)
        for (method in mutableClass.methods) {
            val instructions = method.implementation?.instructions ?: continue
            for (instruction in instructions) {
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                val target = targetFor(reference) ?: continue
                val registerList = registers(instruction.toString()) ?: continue
                method.replaceInstruction(
                    instructions.indexOf(instruction),
                    "${permissionGuardInvokeOpcode(instruction.toString())} {$registerList}, ${target.wrapper}",
                )
                patched++
            }
        }
    }
    return patched
}

private fun injectPermissionGuardStartup(
    owner: MutableClass,
    method: MutableMethod,
    blockedGroups: String,
): MutableMethod {
    if (method.implementation?.instructions?.any { it.toString().contains("PermissionGuardRuntime;->initialize") } == true) return method
    val base = method.implementation?.registerCount ?: error("Cannot inject permission guard without method implementation")
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 2)
    val receiver = cloned.p0Register
    val index = cloned.implementation?.instructions.orEmpty().indexOfFirst {
        it.toString().contains("invoke-super") && it.toString().contains("->onCreate(")
    }.let { if (it >= 0) it + 1 else 0 }
    cloned.addInstructionsWithLabels(index, """
        move-object/from16 v$base, v$receiver
        const-string v${base + 1}, "${StartupHooks.escapeSmali(blockedGroups)}"
        invoke-static/range {v$base .. v${base + 1}}, Lunipatch/overlaycore/PermissionGuardRuntime;->initialize(Landroid/content/Context;Ljava/lang/String;)V
    """.trimIndent())
    owner.methods.remove(method)
    owner.methods.add(cloned)
    return cloned
}

@Suppress("unused")
val permissionGuardPatch = bytecodePatch(
    name = "Permission Guard Patch ( Experimental, Overlay Support, UniManager Support )",
    description = """
        Keep declared permissions while guarding common Android permission checks and requests. Selected
        groups start blocked and can be changed at runtime through the optional Universal Overlay module.

        This patch includes an optional Universal Overlay addon. To use it, patch Permission Guard Patch
        together with Universal Overlay and enable “Overlay integration > Runtime controls > Permission Guard”.
        The Permission Guard module then appears in Universal Overlay and exposes the same runtime state to
        UniManager when “Quick setup > UniManager > Enable UniManager integration” is enabled. Internet
        Access only guards instrumented INTERNET checks and requests; it does not stop socket traffic.
        Native, privileged, already-granted, and unknown permission paths remain outside this guard.
    """.trimIndent(),
    default = false,
) {
    try { category("Permission Guard") } catch (_: NoSuchMethodError) {}
    extendWith("extensions/extension.mpe")
    dependsOn(StartupHooks.resolveRealApplicationPatch)

    val blockCamera by booleanOption(key = "permissionGuardCamera", default = false, title = "Permission groups > Camera > Block Camera", description = "Block camera permission checks and requests at runtime.")
    val blockMicrophone by booleanOption(key = "permissionGuardMicrophone", default = false, title = "Permission groups > Microphone > Block Microphone", description = "Block microphone permission checks and requests at runtime.")
    val blockLocation by booleanOption(key = "permissionGuardLocation", default = false, title = "Permission groups > Location > Block Location", description = "Block location permission checks and requests at runtime.")
    val blockContacts by booleanOption(key = "permissionGuardContacts", default = false, title = "Permission groups > Contacts > Block Contacts", description = "Block contacts permission checks and requests at runtime.")
    val blockPhone by booleanOption(key = "permissionGuardPhone", default = false, title = "Permission groups > Phone > Block Phone", description = "Block phone permission checks and requests at runtime.")
    val blockSms by booleanOption(key = "permissionGuardSms", default = false, title = "Permission groups > SMS > Block SMS", description = "Block SMS permission checks and requests at runtime.")
    val blockCalendar by booleanOption(key = "permissionGuardCalendar", default = false, title = "Permission groups > Calendar > Block Calendar", description = "Block calendar permission checks and requests at runtime.")
    val blockStorage by booleanOption(key = "permissionGuardStorage", default = false, title = "Permission groups > Storage > Block Storage", description = "Block storage permission checks and requests at runtime.")
    val blockMedia by booleanOption(key = "permissionGuardMedia", default = false, title = "Permission groups > Media > Block Media", description = "Block media permission checks and requests at runtime.")
    val blockNotifications by booleanOption(key = "permissionGuardNotifications", default = false, title = "Permission groups > Notifications > Block Notifications", description = "Block notification permission checks and requests at runtime.")
    val blockInternet by booleanOption(key = "permissionGuardInternet", default = false, title = "Permission groups > Internet > Block INTERNET Permission Checks", description = "Block instrumented INTERNET permission checks and requests at runtime. This does not stop socket traffic.")
    val blockNearbyDevices by booleanOption(key = "permissionGuardNearbyDevices", default = false, title = "Permission groups > Nearby devices > Block Nearby Devices", description = "Block nearby-device permission checks and requests at runtime.")
    val blockBluetooth by booleanOption(key = "permissionGuardBluetooth", default = false, title = "Permission groups > Legacy Bluetooth > Block Legacy Bluetooth", description = "Block legacy Bluetooth permission checks and requests at runtime.")
    val enableOverlayRuntime by booleanOption(
        key = "permissionGuardRuntimeOverlay",
        default = false,
        title = "Overlay integration > Runtime controls > Permission Guard",
        description = "Add the Permission Guard settings module to Universal Overlay when Universal Overlay is selected.",
    )
    val enableUniManagerIntegration by booleanOption(
        key = "permissionGuardEnableUniManagerIntegration",
        default = true,
        title = "Quick setup > UniManager > Enable UniManager integration",
        description = "Expose Permission Guard runtime defaults to UniManager when the Universal Overlay runtime module is enabled.",
    )
    fun selectedGroups(): List<String> = permissionGuardGroups(
        mapOf(
            "camera" to (blockCamera == true),
            "microphone" to (blockMicrophone == true),
            "location" to (blockLocation == true),
            "contacts" to (blockContacts == true),
            "phone" to (blockPhone == true),
            "sms" to (blockSms == true),
            "calendar" to (blockCalendar == true),
            "storage" to (blockStorage == true),
            "media" to (blockMedia == true),
            "notifications" to (blockNotifications == true),
            "internet" to (blockInternet == true),
            "nearbyDevices" to (blockNearbyDevices == true),
            "bluetooth" to (blockBluetooth == true),
        ),
    )
    dependsOn(
        uniManagerMetadataPatch(
            name = "Permission Guard UniManager registration",
            metadataName = UNI_MANAGER_PERMISSION_GUARD_METADATA_NAME,
            provider = {
                if (enableOverlayRuntime == true && enableUniManagerIntegration == true) permissionGuardMetadata(selectedGroups()) else null
            },
        ),
    )

    execute {
        val blockedGroups = selectedGroups()
        val startup = resolveStartupEntryPoint(logger)
        if (startup == null) {
            logger.warning("Permission Guard startup initialization skipped: no safe Application or Activity entry point")
        } else {
            injectPermissionGuardStartup(startup.owner, startup.onCreate, blockedGroups.joinToString(","))
        }
        val patched = guardPermissionCalls()
        logger.info("Permission Guard runtime guards patched $patched call site(s); blocked groups=${blockedGroups.joinToString(",").ifEmpty { "none" }}")
        if (enableOverlayRuntime == true) {
            PermissionGuardOverlayIntegration.queue(this)
        }
    }
}
