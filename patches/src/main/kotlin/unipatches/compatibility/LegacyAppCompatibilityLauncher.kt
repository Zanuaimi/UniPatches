package unipatches.compatibility

import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.logging.Logger

internal const val GOOGLE_EXPANSION_DOWNLOADER_ACTIVITY = "com.google.android.vending.expansion.downloader_impl.DownloaderActivity"
internal const val GOOGLE_EXPANSION_DOWNLOADER_TYPE = "Lcom/google/android/vending/expansion/downloader_impl/DownloaderActivity;"
private const val INTENT_TYPE = "Landroid/content/Intent;"
private const val UNITY_PLAYER_PACKAGE = "Lcom/unity3d/player/"
private val ACTIVITY_START_METHODS = setOf(
    "Landroid/app/Activity;",
    "Landroid/content/Context;",
    "Landroid/content/ContextWrapper;",
)

internal class LegacyExpansionLauncherTarget {
    var activityName: String? = null
    var error: String? = null
}

private fun invocationRegisters(instruction: Instruction): List<Int>? = when (instruction) {
    is FiveRegisterInstruction -> if (instruction.registerCount in 1..5) {
        listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG)
            .take(instruction.registerCount)
    } else null
    is RegisterRangeInstruction -> if (instruction.registerCount in 1..5) {
        List(instruction.registerCount) { instruction.startRegister + it }
    } else null
    else -> null
}

internal fun intentConstructorRegisters(instruction: Instruction): List<Int>? {
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (reference.definingClass != INTENT_TYPE || reference.name != "<init>" || reference.returnType != "V" ||
        reference.parameterTypes != listOf("Landroid/content/Context;", "Ljava/lang/Class;")
    ) return null
    return invocationRegisters(instruction)?.takeIf { it.size == 3 }
}

internal fun constructedActivityType(instructions: List<Instruction>, index: Int, classRegister: Int): String? {
    val literal = instructions.getOrNull(index - 1) as? ReferenceInstruction ?: return null
    if (literal.opcode != com.android.tools.smali.dexlib2.Opcode.CONST_CLASS ||
        (literal as? OneRegisterInstruction)?.registerA != classRegister
    ) return null
    return (literal.reference as? TypeReference)?.type
}

internal fun isUnityActivityClass(type: String, superclasses: Map<String, String?>): Boolean {
    val visited = mutableSetOf<String>()
    var parent = superclasses[type]
    var hasUnityActivityBase = false
    var hasAndroidActivityBase = false
    while (parent != null && visited.add(parent)) {
        if (parent.startsWith(UNITY_PLAYER_PACKAGE) && parent.endsWith("Activity;")) {
            hasUnityActivityBase = true
        }
        if (parent == "Landroid/app/Activity;" || parent == "Landroid/app/NativeActivity;") {
            hasAndroidActivityBase = true
        }
        parent = superclasses[parent]
    }
    return hasUnityActivityBase && hasAndroidActivityBase
}

private fun isStartedIntent(instructions: List<Instruction>, constructorIndex: Int, intentRegister: Int): Boolean {
    val instruction = instructions.getOrNull(constructorIndex + 1) ?: return false
    val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (method.definingClass !in ACTIVITY_START_METHODS || method.name != "startActivity" ||
        method.parameterTypes != listOf("Landroid/content/Intent;") || method.returnType != "V"
    ) return false
    return invocationRegisters(instruction)?.lastOrNull() == intentRegister
}

internal fun unityActivityLaunchTargets(
    instructions: List<Instruction>,
    superclasses: Map<String, String?>,
): Set<String> = instructions.mapIndexedNotNull { index, instruction ->
    val registers = intentConstructorRegisters(instruction) ?: return@mapIndexedNotNull null
    val target = constructedActivityType(instructions, index, registers[2]) ?: return@mapIndexedNotNull null
    if (isUnityActivityClass(target, superclasses) && isStartedIntent(instructions, index, registers[0])) target else null
}.toSet()

internal fun unityActivitiesStartedByDownloader(classes: Collection<ClassDef>): Set<String> {
    val byType = classes.associateBy { it.type }
    val downloader = byType[GOOGLE_EXPANSION_DOWNLOADER_TYPE] ?: return emptySet()
    val superclasses = byType.mapValues { it.value.superclass }
    return downloader.methods.flatMap { method ->
        unityActivityLaunchTargets(method.implementation?.instructions?.toList().orEmpty(), superclasses)
    }.toSet()
}

internal fun legacyExpansionLauncherTargetPatch(
    target: LegacyExpansionLauncherTarget,
    enabledProvider: () -> Boolean,
) = bytecodePatch(
    name = null,
    description = "Internal Google ExpansionDownloader Unity-launch target detection.",
    default = false,
) {
    execute {
        target.activityName = null
        target.error = null
        if (!enabledProvider()) return@execute
        val classes = mutableListOf<ClassDef>()
        classDefForEach { classes += it }
        val candidates = unityActivitiesStartedByDownloader(classes)
        when (candidates.size) {
            1 -> {
                val candidate = candidates.single()
                target.activityName = candidate.removePrefix("L").removeSuffix(";").replace('/', '.')
                val safeName = target.activityName.orEmpty().filterNot { Character.isISOControl(it) }.take(255)
                Logger.getLogger("LegacyAppCompatibility").info(
                    "Legacy compatibility: detected ExpansionDownloader Unity launch activity $safeName."
                )
            }
            else -> {
                target.error = if (candidates.isEmpty()) {
                    "DownloaderActivity does not contain a recognized Intent(Class) launch to a Unity activity subclass."
                } else {
                    "DownloaderActivity launches multiple Unity activity subclasses: ${candidates.joinToString { it.filterNot { character -> Character.isISOControl(character) }.take(120) }.take(400)}."
                }
                Logger.getLogger("LegacyAppCompatibility").warning(
                    "Legacy compatibility: could not safely identify downloader Unity launch activity: ${target.error}"
                )
            }
        }
    }
}
