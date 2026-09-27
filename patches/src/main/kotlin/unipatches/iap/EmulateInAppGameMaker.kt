package unipatches.iap

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.iface.ClassDef
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import java.util.logging.Logger

internal fun BytecodePatchContext.applyGameMakerPatches(
    candidates: List<ClassDef>,
    minRegs: (MutableMethod) -> Int,
    logger: Logger,
): List<String> {
    val patched = mutableListOf<String>()
    for (classDef in candidates) {
        val className = classDef.type
        val verify = classDef.methods.firstOrNull {
            it.name.equals("verifyPurchase", ignoreCase = true) && it.returnType == "Z" && it.implementation != null
        } ?: continue
        try {
            val mutableClass = mutableClassDefBy(classDef)
            val method = mutableClass.methods.firstOrNull {
                it.name.equals(verify.name, ignoreCase = true) && it.returnType == "Z"
            } ?: continue
            if (minRegs(method) < 1) continue
            method.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
            val label = "GameMaker.$className->${method.name}"
            patched += label
            logger.info("Emulate InApp: patched $label")
        } catch (e: Exception) {
            logger.warning("Emulate InApp: GameMaker verifyPurchase skipped: ${e.message}")
        }
    }
    return patched
}
