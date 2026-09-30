package unipatches.iap

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.iface.ClassDef
import helpers.bytecode.fitsBelowParameters
import java.util.logging.Logger

internal fun BytecodePatchContext.applyGameMakerPatches(
    candidates: List<ClassDef>,
    logger: Logger,
): List<String> {
    val patched = mutableListOf<String>()
    val block = "const/4 v0, 0x1\nreturn v0"
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
            // A static verifyPurchase with no arguments contributes no parameter
            // registers and used to be skipped outright; what actually matters is
            // that this particular frame has room for v0 below its parameter region.
            if (!method.fitsBelowParameters(block)) continue
            method.addInstructions(0, block)
            val label = "GameMaker.$className->${method.name}"
            patched += label
            logger.info("Emulate InApp: patched $label")
        } catch (e: Exception) {
            logger.warning("Emulate InApp: GameMaker verifyPurchase skipped: ${e.message}")
        }
    }
    return patched
}
