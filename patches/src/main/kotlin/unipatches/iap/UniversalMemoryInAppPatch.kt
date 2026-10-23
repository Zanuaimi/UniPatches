package unipatches.iap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.startup.StartupHooks
import unipatches.overlay.universalOverlayPatch

/** Combines the existing purchase adapters with a GG-style in-process int32 editor. */
@Suppress("unused")
val universalMemoryInAppPatch = bytecodePatch(
    name = "Universal InApp + Memory Editor (Experimental)",
    description = "Patch common purchase paths and add an in-app int32 memory search/edit tool to Universal Overlay. " +
        "The memory tool searches only the patched app process. Store catalogs and server-verified entitlements remain app-specific.",
    default = false,
) {
    try { category("InApp Emulation") } catch (_: NoSuchMethodError) {}
    dependsOn(emulateInAppPatch)
    dependsOn(universalOverlayPatch)

    execute {
        val targets = listOfNotNull(
            StartupHooks.overlayApplicationBridgeOwner,
            StartupHooks.resolvedLauncherActivityDescriptor,
        ).distinct()
        var installed = false
        for (descriptor in targets) {
            val owner = mutableClassDefByOrNull(descriptor) ?: continue
            val onCreate = owner.methods.firstOrNull { method ->
                method.name == "onCreate" && method.returnType == "V" &&
                    (method.parameterTypes.isEmpty() ||
                        method.parameterTypes == listOf("Landroid/os/Bundle;")) &&
                    method.implementation?.instructions?.any { instruction ->
                        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        ref?.definingClass == "Lunipatch/overlaycore/OverlayRuntime;" &&
                            ref.name in setOf("install", "installActivity")
                    } == true
            } ?: continue
            onCreate.addInstructions(0, "invoke-static {}, Lunipatch/overlaycore/MemoryEditorPolicy;->enable()V")
            installed = true
            println("Memory Editor enabled at $descriptor->onCreate")
            break
        }
        check(installed) { "Memory Editor requires a verified Universal Overlay startup bridge" }
    }
}
