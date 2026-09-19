package unipatches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import helpers.bytecode.cloneMutable
import helpers.bytecode.numberOfParameterRegisters
import helpers.bytecode.p0Register
import helpers.startup.StartupHooks

private const val MANAGER_BRIDGE = "Lunipatch/overlaycore/UniManagerBridge;"

/** Injects the optional manager startup read into the manifest Application or launcher Activity. */
internal fun injectUniManagerStartup(
    owner: MutableClass,
    method: MutableMethod,
    fallbackPolicy: String,
): MutableMethod {
    if (method.implementation?.instructions?.any { it.toString().contains("UniManagerBridge;->initialize") } == true) {
        return method
    }
    val base = method.implementation?.registerCount ?: error("Cannot inject manager startup without method implementation")
    val cloned = method.cloneMutable(additionalRegisters = method.numberOfParameterRegisters + 2)
    val receiver = cloned.p0Register
    val instructions = cloned.implementation?.instructions.orEmpty()
    val superIndex = instructions.indexOfFirst { instruction ->
        instruction.toString().contains("invoke-super") && instruction.toString().contains("->onCreate(")
    }
    val index = if (superIndex >= 0) superIndex + 1 else 0
    cloned.addInstructionsWithLabels(index, """
        move-object/from16 v$base, v$receiver
        const-string v${base + 1}, "${StartupHooks.escapeSmali(fallbackPolicy)}"
        invoke-static/range {v$base .. v${base + 1}}, $MANAGER_BRIDGE->initialize(Landroid/content/Context;Ljava/lang/String;)V
    """.trimIndent())
    owner.methods.remove(method)
    owner.methods.add(cloned)
    return cloned
}
