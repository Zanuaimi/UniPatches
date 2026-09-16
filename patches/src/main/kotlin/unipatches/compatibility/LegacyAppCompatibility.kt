package unipatches.compatibility

import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import helpers.manifest.NS_ANDROID
import helpers.manifest.applicationOrNull
import helpers.bytecode.cloneMutable
import helpers.spoof.foldStringGetterConst
import java.util.logging.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val APACHE_LEGACY_LIB = "org.apache.http.legacy"
private const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
private const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
private const val SCHEDULE_EXACT_ALARM = "android.permission.SCHEDULE_EXACT_ALARM"
private const val USE_EXACT_ALARM = "android.permission.USE_EXACT_ALARM"
private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"

private val validImei = Regex("^[0-9]{15}$")

private fun hasPermission(document: Document, name: String): Boolean =
    listOf("uses-permission", "uses-permission-sdk-23").any { tag ->
        val permissions = document.getElementsByTagName(tag)
        (0 until permissions.length).any {
            (permissions.item(it) as? Element)?.getAttributeNS(NS_ANDROID, "name") == name
        }
    }

private fun addPermission(document: Document, name: String): Boolean {
    if (hasPermission(document, name)) return false
    val root = document.documentElement ?: return false
    val permission = document.createElement("uses-permission")
    permission.setAttributeNS(NS_ANDROID, "android:name", name)
    root.appendChild(permission)
    return true
}

private fun setApplicationAttribute(
    document: Document,
    name: String,
    value: String,
): Boolean {
    val application = document.documentElement.applicationOrNull() ?: return false
    if (application.getAttributeNS(NS_ANDROID, name) == value) return false
    application.setAttributeNS(NS_ANDROID, "android:$name", value)
    return true
}

private fun addLegacyReviver(document: Document, apache: Boolean, foreground: Boolean, alarms: Boolean, bluetooth: Boolean): Int {
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

private fun updateTargetSdk(document: Document, target: Int): Boolean {
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

private fun supportAllScreens(document: Document): Pair<Int, Boolean> {
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

private fun relaxSharedLibraries(document: Document): Int {
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

private val exportedComponentTags = listOf("activity", "activity-alias", "service", "receiver")

private fun Element.androidAttribute(name: String): String =
    getAttributeNS(NS_ANDROID, name).ifEmpty { getAttribute("android:$name") }

private fun Element.hasAndroidAttribute(name: String): Boolean =
    hasAttributeNS(NS_ANDROID, name) || getAttribute("android:$name").isNotEmpty()

private fun Element.intentFilters(): List<Element> {
    val plain = getElementsByTagName("intent-filter")
    if (plain.length > 0) {
        return (0 until plain.length).mapNotNull { plain.item(it) as? Element }
    }
    val namespaced = getElementsByTagNameNS("*", "intent-filter")
    return (0 until namespaced.length).mapNotNull { namespaced.item(it) as? Element }
}

private fun Element.hasIntentFilter(): Boolean = intentFilters().isNotEmpty()

private fun Element.isLauncherComponent(): Boolean {
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

private fun repairMissingComponentExportFlags(document: Document, logger: Logger): Int {
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

private fun exportAllActivities(document: Document): Int {
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

private const val OPEN_IAB_UNITY_PLUGIN = "Lorg/onepf/openiab/UnityPlugin;"
private const val REGISTER_RECEIVER = "registerReceiver"
private const val RECEIVER_FLAGS_API = 33
private const val RECEIVER_EXPORTED = 0x2
private const val RECEIVER_NOT_EXPORTED = 0x4
private val frameworkContextOwners = setOf(
    "Landroid/content/Context;",
    "Landroid/content/ContextWrapper;",
    "Landroid/app/Activity;",
)
private val externalStoreActionPrefixes = listOf(
    "com.android.vending.", "com.amazon.", "com.sec.", "com.samsung.",
    "com.yandex.", "com.nokia.", "com.slideme.", "com.appland.",
    "com.aptoide.", "com.appmall.",
)

private fun receiverCallRegisters(instruction: ReferenceInstruction): List<Int>? = when (instruction) {
    is BuilderInstruction35c -> if (instruction.registerCount == 3) {
        listOf(instruction.registerC, instruction.registerD, instruction.registerE)
    } else null
    is BuilderInstruction3rc -> if (instruction.registerCount == 3) {
        (instruction.startRegister until instruction.startRegister + 3).toList()
    } else null
    else -> null
}

private const val OPEN_IAB_AUTOMATIC = "automatic"
private const val OPEN_IAB_DISABLED = "disabled"
private const val OPEN_IAB_FORCE = "force"

private fun intentFilterActionArgumentRegister(instruction: ReferenceInstruction): Int? = when (instruction) {
    is BuilderInstruction35c -> if (instruction.registerCount == 2) instruction.registerD else null
    is BuilderInstruction3rc -> if (instruction.registerCount == 2) instruction.startRegister + 1 else null
    else -> null
}

private fun resolvedOpenIabActions(
    instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>,
    endIndex: Int,
): Set<String>? {
    val actions = mutableSetOf<String>()
    for (index in 0..endIndex) {
        val instruction = instructions[index]
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (reference.definingClass != "Landroid/content/IntentFilter;" ||
            reference.name !in setOf("<init>", "addAction") ||
            reference.returnType != "V" ||
            reference.parameterTypes != listOf("Ljava/lang/String;")
        ) continue

        val argumentRegister = intentFilterActionArgumentRegister(instruction) ?: return null
        val candidates = instructions.subList(0, index).mapNotNull { previous ->
            val string = (previous as? ReferenceInstruction)?.reference as? StringReference ?: return@mapNotNull null
            if (previous.opcode != Opcode.CONST_STRING && previous.opcode != Opcode.CONST_STRING_JUMBO) return@mapNotNull null
            val register = (previous as? OneRegisterInstruction)?.registerA ?: return@mapNotNull null
            if (register == argumentRegister) string.string else null
        }.toSet()
        if (candidates.size != 1) return null
        actions += candidates.single()
    }
    return actions.takeIf { it.isNotEmpty() }
}

private fun isSafeOpenIabAction(action: String): Boolean {
    if (action.startsWith("android.")) return true
    if (action.startsWith("org.onepf.openiab.")) return true
    return false
}

private fun isExternalStoreAction(action: String): Boolean =
    externalStoreActionPrefixes.any(action::startsWith)

private fun receiverFlagsForOpenIabActions(actions: Set<String>): Int? = when {
    actions.isEmpty() -> null
    actions.all(::isSafeOpenIabAction) -> RECEIVER_NOT_EXPORTED
    actions.all(::isExternalStoreAction) -> RECEIVER_EXPORTED
    else -> null
}

private fun isVerifiedContextOwner(
    type: String,
    parents: Map<String, String>,
    seen: MutableSet<String> = mutableSetOf(),
): Boolean = when {
    type in frameworkContextOwners -> true
    type == "Ljava/lang/Object;" || !seen.add(type) -> false
    else -> parents[type]?.let { isVerifiedContextOwner(it, parents, seen) } == true
}

private fun isOpenIabReceiverValue(
    instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>,
    endIndex: Int,
    receiverRegister: Int,
): Boolean {
    var register = receiverRegister
    for (index in endIndex - 1 downTo 0) {
        val instruction = instructions[index]
        when (instruction.opcode) {
            Opcode.NEW_INSTANCE -> {
                if ((instruction as? OneRegisterInstruction)?.registerA != register) continue
                val type = (instruction as? ReferenceInstruction)?.reference as? TypeReference
                return type?.type?.startsWith("Lorg/onepf/openiab/") == true ||
                    type?.type?.startsWith("Lorg/onepf/oms/") == true
            }
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                val move = instruction as? TwoRegisterInstruction ?: continue
                if (move.registerA == register) register = move.registerB
            }
            Opcode.MOVE_RESULT_OBJECT -> {
                if ((instruction as? OneRegisterInstruction)?.registerA != register) continue
                val producer = instructions.getOrNull(index - 1)
                val reference = (producer as? ReferenceInstruction)?.reference as? MethodReference
                return reference?.definingClass?.startsWith("Lorg/onepf/openiab/") == true ||
                    reference?.definingClass?.startsWith("Lorg/onepf/oms/") == true
            }
            Opcode.IGET_OBJECT, Opcode.SGET_OBJECT -> {
                val destination = (instruction as? TwoRegisterInstruction)?.registerA
                    ?: (instruction as? OneRegisterInstruction)?.registerA
                if (destination != register) continue
                val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                return field?.definingClass?.startsWith("Lorg/onepf/openiab/") == true ||
                    field?.definingClass?.startsWith("Lorg/onepf/oms/") == true
            }
            else -> Unit
        }
    }
    return false
}

private fun openIabReceiverFlagsPatch(enabledProvider: () -> String) = bytecodePatch(
    name = null,
    description = "Internal OpenIAB dynamic receiver compatibility phase.",
    default = false,
) {
    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val mode = enabledProvider().trim().lowercase()
        if (mode == OPEN_IAB_DISABLED) return@execute
        if (mode !in setOf(OPEN_IAB_AUTOMATIC, OPEN_IAB_FORCE)) {
            logger.warning("Legacy compatibility: unknown OpenIAB receiver mode '$mode'; fix skipped.")
            return@execute
        }
        val openIab = mutableClassDefByOrNull(OPEN_IAB_UNITY_PLUGIN)
        if (openIab == null) {
            logger.info("Legacy compatibility: OpenIAB UnityPlugin was not found; dynamic receiver fix skipped.")
            return@execute
        }

        var patched = 0
        var skipped = 0
        var methodCount = 0
        val parents = mutableMapOf<String, String>()
        classDefForEach { classDef -> classDef.superclass?.let { parents[classDef.type] = it } }
        for (method in openIab.methods.filter { it.name == "createBroadcasts" }) {
            val implementation = method.implementation ?: continue
            methodCount++
            val calls = implementation.instructions.mapIndexedNotNull { index, instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapIndexedNotNull null
                if (!isVerifiedContextOwner(reference.definingClass, parents) ||
                    reference.name != REGISTER_RECEIVER ||
                    reference.returnType != "Landroid/content/Intent;" ||
                    reference.parameterTypes != listOf(
                        "Landroid/content/BroadcastReceiver;",
                        "Landroid/content/IntentFilter;",
                    )
                ) return@mapIndexedNotNull null
                index to (instruction as? ReferenceInstruction)
            }
            if (calls.isEmpty()) {
                logger.info("Legacy compatibility: OpenIAB ${openIab.type}->${method.name} has no exact legacy receiver calls.")
                continue
            }
            logger.info("Legacy compatibility: inspecting ${openIab.type}->${method.name}; receiver owner(s)=${calls.map { (it.second!!.reference as? MethodReference)?.definingClass }.distinct()}")

            if (mode == OPEN_IAB_AUTOMATIC) {
                val unverifiedCall = calls.firstOrNull { call ->
                    val receiverRegisters = receiverCallRegisters(call.second!!)
                    val owned = receiverRegisters != null && isOpenIabReceiverValue(
                        implementation.instructions,
                        call.first,
                        receiverRegisters[1],
                    )
                    if (!owned) logger.info("Legacy compatibility: skipped OpenIAB receiver call at instruction ${call.first}; receiver ownership was not proven.")
                    receiverRegisters == null || !owned
                }
                if (unverifiedCall != null) {
                    skipped += calls.size
                    logger.info("Legacy compatibility: automatic OpenIAB receiver fix skipped ${method.name}; receiver ownership was not proven for every call.")
                    continue
                }
                val actions = resolvedOpenIabActions(implementation.instructions, calls.last().first)
                if (actions == null) {
                    skipped += calls.size
                    logger.info("Legacy compatibility: automatic OpenIAB receiver fix skipped ${method.name}; IntentFilter actions were not uniquely resolved.")
                    continue
                }
                if (receiverFlagsForOpenIabActions(actions) == null) {
                    skipped += calls.size
                    logger.info("Legacy compatibility: automatic OpenIAB receiver fix skipped ${method.name}; actions were not a verified internal or external-store set: $actions")
                    continue
                }
            }

            val actions = resolvedOpenIabActions(implementation.instructions, calls.last().first)
            val receiverFlags = receiverFlagsForOpenIabActions(actions ?: emptySet()) ?: RECEIVER_NOT_EXPORTED
            logger.info("Legacy compatibility: ${openIab.type}->${method.name}; actions=${actions ?: "unknown"}; selected receiver flag=${if (receiverFlags == RECEIVER_EXPORTED) "RECEIVER_EXPORTED" else "RECEIVER_NOT_EXPORTED"}; calls=${calls.size}")

            val originalRegisterCount = implementation.registerCount
            val scratchBase = originalRegisterCount
            // if-lt is the only API-level branch used here and its 22t format
            // accepts only v0..v15. Skip larger frames rather than emitting
            // malformed bytecode; the original call remains untouched.
            if (scratchBase + 5 > 15) {
                skipped += calls.size
                logger.warning("Legacy compatibility: skipped OpenIAB receiver fix in ${method.name}; scratch registers cannot fit the branch-safe v0..v15 range.")
                continue
            }

            val cloned = method.cloneMutable(additionalRegisters = 6)
            val prologueSize = cloned.implementation!!.instructions.size - implementation.instructions.size
            for ((ordinal, call) in calls.asReversed().withIndex()) {
                val index = call.first + prologueSize
                val registers = receiverCallRegisters(call.second!!) ?: run {
                    skipped++
                    logger.info("Legacy compatibility: skipped OpenIAB receiver call at instruction ${call.first}; unsupported register form.")
                    continue
                }
                val oldOwner = ((call.second!!.reference as? MethodReference)?.definingClass)
                    ?: "Landroid/content/Context;"
                val oldReference = "$oldOwner->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;"
                val oldLabel = ":morphe_openiab_receiver_old_${ordinal}"
                val doneLabel = ":morphe_openiab_receiver_done_${ordinal}"
                val block = """
                    move-object/from16 v$scratchBase, v${registers[0]}
                    move-object/from16 v${scratchBase + 1}, v${registers[1]}
                    move-object/from16 v${scratchBase + 2}, v${registers[2]}
                    sget v${scratchBase + 5}, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                    const/16 v${scratchBase + 4}, $RECEIVER_FLAGS_API
                    if-lt v${scratchBase + 5}, v${scratchBase + 4}, $oldLabel
                    const/16 v${scratchBase + 3}, $receiverFlags
                    invoke-virtual/range {v$scratchBase .. v${scratchBase + 3}}, Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;
                    goto $doneLabel
                    $oldLabel
                    invoke-virtual/range {v$scratchBase .. v${scratchBase + 2}}, $oldReference
                    $doneLabel
                """.trimIndent()
                cloned.replaceInstruction(index, block)
                patched++
                logger.info("Legacy compatibility: patched OpenIAB receiver call at instruction ${call.first} with ${if (receiverFlags == RECEIVER_EXPORTED) "RECEIVER_EXPORTED" else "RECEIVER_NOT_EXPORTED"} for owner $oldOwner and actions=${actions ?: "unknown"}.")
            }
            val mutableClass = mutableClassDefByOrNull(OPEN_IAB_UNITY_PLUGIN) ?: continue
            mutableClass.methods.remove(method)
            mutableClass.methods.add(cloned)
        }

        if (patched == 0) {
            logger.warning("Legacy compatibility: no exact OpenIAB Context.registerReceiver calls were patched in mode=$mode; methods=$methodCount; skipped=$skipped.")
        } else {
            logger.info("Legacy compatibility: patched $patched OpenIAB dynamic receiver registration call(s) in mode=$mode; skipped=$skipped.")
        }
    }
}

private fun legacyImeiPatch(imeiProvider: () -> Pair<Boolean, String>) = bytecodePatch(
    name = null,
    description = "Internal legacy device compatibility phase.",
    default = false,
) {
    execute {
        val (enabled, imei) = imeiProvider()
        if (!enabled) return@execute
        val logger = Logger.getLogger(this::class.java.name)
        if (!validImei.matches(imei)) {
            logger.warning("Legacy compatibility: IMEI must contain exactly 15 digits. No IMEI changes applied.")
            return@execute
        }
        val patched = foldStringGetterConst(
            definingClass = "Landroid/telephony/TelephonyManager;",
            methodNames = setOf("getDeviceId", "getImei"),
            value = imei,
        )
        if (patched == 0) {
            logger.warning("Legacy compatibility: no recognized TelephonyManager IMEI calls found.")
        } else {
            logger.info("Legacy compatibility: spoofed IMEI at $patched call site(s).")
        }
    }
}

@Suppress("unused")
val legacyAppCompatibilityPatch = rawResourcePatch(
    name = "Improve Legacy App / Game Compatibility for Modern Android Patch ( Enhanced )",
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

        Credits: Nai64Patches from Nai64 for the original legacy compatibility functionality. UniPatches
        provides the merged settings, validation, manifest safeguards, and compatibility organization.
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
