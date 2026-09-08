package unipatches.bypass

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.Locale
import java.util.logging.Logger
import helpers.bytecode.foldBooleanReturns

private val onlineTerms = listOf(
    "checking connection",
    "check your connection",
    "connection required",
    "no internet",
    "offline",
    "server maintenance",
    "server unavailable",
    "unable to connect",
    "connection failed",
    "network unavailable",
    "online required",
)

private val positiveGateNames = setOf(
    "isonline",
    "isconnected",
    "hasconnection",
    "canconnect",
    "serveravailable",
    "networkavailable",
)

private val negativeGateNames = setOf(
    "isoffline",
    "requiresinternet",
    "requiresonline",
    "serverunavailable",
    "connectionfailed",
    "networkunavailable",
    "ismaintenance",
    "maintenanceactive",
    "forcedoffline",
)

private fun String.normalized() = lowercase(Locale.ROOT).replace("_", "").replace("-", "")

private data class EngineFlags(
    val unity: Boolean = false,
    val unreal: Boolean = false,
    val godot: Boolean = false,
)

private fun engineFlags(types: List<String>): EngineFlags = EngineFlags(
    unity = types.any { it.contains("com/unity3d/player", ignoreCase = true) },
    unreal = types.any {
        it.contains("com/epicgames/ue4", ignoreCase = true) ||
            it.contains("com/epicgames/unreal", ignoreCase = true)
    },
    godot = types.any {
        it.contains("org/godotengine/godot", ignoreCase = true) ||
            it.contains("org/godotengine", ignoreCase = true)
    },
)

private fun isKnownAdOrNetworkClass(type: String): Boolean {
    val name = type.lowercase(Locale.ROOT)
    return listOf(
        "google/android/gms/ads", "applovin", "ironsource", "unity3d/ads", "vungle",
        "facebook/ads", "bytedance", "pangle", "yandex", "mytarget", "inmobi",
        "chartboost", "startapp", "mopub", "okhttp", "retrofit",
    ).any(name::contains)
}

@Suppress("unused")
val disableForcedOnlineChecksPatch = bytecodePatch(
    name = "Disable Forced Online Checks (Experimental)",
    description = """
        Try to bypass high-confidence client-side offline gates. It cannot bypass server-side login,
        entitlement, or game-state checks.

        Compatibility: Control App Ads can intentionally block ad hosts. Keep “Exclude ad SDK and
        networking code” enabled when both patches are selected so blocked ads are not falsely told
        the device is online and repeatedly retried. This does not bypass server-enforced online play.
    """.trimIndent(),
    default = false,
) {
    val autoMode by booleanOption(
        key = "autoMode",
        title = "Auto mode",
        description = "Automatically run all strategies supported by the detected APK",
        default = true,
    )
    val commonAndroidNetwork by booleanOption(
        key = "commonAndroidNetwork",
        title = "Common Android/network API",
        description = "Make common Android connectivity checks report an active connection",
        default = true,
    )
    val unityStrategy by booleanOption(
        key = "unityStrategy",
        title = "Unity strategy",
        description = "Apply generic high-confidence online-gate detection when Unity is detected",
        default = true,
    )
    val unrealStrategy by booleanOption(
        key = "unrealStrategy",
        title = "Unreal strategy",
        description = "Apply generic high-confidence online-gate detection when Unreal is detected",
        default = true,
    )
    val godotStrategy by booleanOption(
        key = "godotStrategy",
        title = "Godot strategy",
        description = "Apply generic high-confidence online-gate detection when Godot is detected",
        default = true,
    )
    val genericBytecodeStrategy by booleanOption(
        key = "genericBytecodeStrategy",
        title = "Generic bytecode strategy",
        description = "Scan app bytecode for high-confidence online gate methods without engine detection",
        default = false,
    )
    val excludeAdAndNetworkCode by booleanOption(
        key = "excludeAdAndNetworkCode",
        title = "Compatibility > Exclude ad SDK and networking code",
        description = "Skip known advertising SDK and common HTTP-client classes when changing connectivity checks. Keep enabled to avoid making blocked ads retry as if the device were online.",
        default = true,
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val types = mutableListOf<String>()
        classDefForEach { classDef -> types += classDef.type }
        val engines = engineFlags(types)
        val auto = autoMode == true
        val useCommon = auto || commonAndroidNetwork == true
        val useUnity = engines.unity && (auto || unityStrategy == true)
        val useUnreal = engines.unreal && (auto || unrealStrategy == true)
        val useGodot = engines.godot && (auto || godotStrategy == true)
        val anyEngineDetected = engines.unity || engines.unreal || engines.godot
        val useGeneric = if (auto) !anyEngineDetected else genericBytecodeStrategy == true

        var patched = 0
        if (useCommon) {
            patched += foldBooleanReturns(
                mapOf(
                    "Landroid/net/wifi/WifiManager;" to mapOf("isWifiEnabled" to "0x1"),
                    "Landroid/net/NetworkInfo;" to mapOf(
                        "isConnected" to "0x1",
                        "isAvailable" to "0x1",
                        "isConnectedOrConnecting" to "0x1",
                    ),
                ),
                excludeClass = { type -> excludeAdAndNetworkCode == true && isKnownAdOrNetworkClass(type) },
            )
        }

        val enabledEngineStrategies = buildSet {
            if (useUnity) add("unity")
            if (useUnreal) add("unreal")
            if (useGodot) add("godot")
        }
        if (useGeneric || enabledEngineStrategies.isNotEmpty()) {
            classDefForEach { classDef ->
                if (excludeAdAndNetworkCode == true && isKnownAdOrNetworkClass(classDef.type)) return@classDefForEach
                val mutableClass = mutableClassDefBy(classDef)
                for (method in mutableClass.methods) {
                    if (method.returnType != "Z") continue
                    val implementation = method.implementation ?: continue
                    val methodName = method.name.normalized()
                    val strings = implementation.instructions.mapNotNull { instruction ->
                        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
                    }.map(String::normalized)
                    val hasOnlineText = strings.any { value -> onlineTerms.any(value::contains) }
                    val positive = methodName in positiveGateNames
                    val negative = methodName in negativeGateNames
                    if (!positive && !negative) continue
                    if (!hasOnlineText && !methodName.contains("online") && !methodName.contains("connect")) continue
                    if (implementation.registerCount < 1) continue

                    val value = if (positive) "0x1" else "0x0"
                    method.addInstructions(0, "const/4 v0, $value\nreturn v0")
                    patched++
                }
            }
        }

        if (patched > 0) {
            logger.info(
                "Disable Forced Online Checks: patched $patched check(s); " +
                    "detected engines=" + listOfNotNull(
                        "Unity".takeIf { engines.unity },
                        "Unreal".takeIf { engines.unreal },
                        "Godot".takeIf { engines.godot },
                    ).ifEmpty { listOf("none") }.joinToString(", "),
            )
        } else {
            logger.warning(
                "No high-confidence forced-online checks found. Server-side enforcement and " +
                    "native-only engine logic were not modified.",
            )
        }
    }
}
