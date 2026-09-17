package unipatches.iap

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.iface.ClassDef
import helpers.bytecode.cloneMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import java.util.logging.Logger

internal fun BytecodePatchContext.applyIl2CppBillingPatches(
    candidates: List<ClassDef>,
    parameterRegister: (MutableMethod, Int) -> String,
    minRegs: (MutableMethod) -> Int,
    expandSwap: (MutableMethod, String) -> Boolean,
    logger: Logger,
): List<String> {
    val labels = mutableListOf<String>()
    for (classDef in candidates) {
        val className = classDef.type
        if (!className.startsWith("Lcom/android/billingclient/api/zz")) continue
        val mutableClass = try { mutableClassDefBy(classDef) } catch (_: Exception) { continue }
        if (classDef.methods.none { it.name == "nativeOnPurchasesUpdated" }) continue
        val setup = mutableClass.methods.firstOrNull {
            !com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) && it.name == "onBillingSetupFinished" &&
                it.returnType == "V" && it.parameterTypes == listOf("Lcom/android/billingclient/api/BillingResult;")
        }
        val nativeSetup = mutableClass.methods.firstOrNull {
            com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) && it.name == "nativeOnBillingSetupFinished" &&
                it.returnType == "V" && it.parameterTypes == listOf("I", "Ljava/lang/String;", "J")
        }
        val nativeField = mutableClass.fields.firstOrNull { it.type == "J" }
        if (setup != null && nativeSetup != null && nativeField != null && setup.implementation != null) {
            val signature = nativeSetup.parameterTypes.joinToString("")
            val block = """
                iget-wide v0, p0, $className->${nativeField.name}:J
                const/4 v2, 0x0
                const-string v3, ""
                invoke-static {v2, v3, v0, v1}, $className->nativeOnBillingSetupFinished($signature)V
                return-void
            """.trimIndent()
            val applied = try { if (minRegs(setup) >= 4) { setup.addInstructions(0, block); true } else expandSwap(setup, block) } catch (_: Exception) { false }
            if (applied) {
                labels += "$className.onBillingSetupFinished"
                logger.info("Emulate InApp: patched $className.onBillingSetupFinished bridge")
            }
        }
        val purchases = mutableClass.methods.firstOrNull {
            !com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) && it.name == "onPurchasesUpdated" &&
                it.returnType == "V" && it.parameterTypes == listOf("Lcom/android/billingclient/api/BillingResult;", "Ljava/util/List;")
        } ?: continue
        val registers = purchases.implementation?.registerCount ?: continue
        if (registers > 14) continue
        val resultReg = parameterRegister(purchases, 0)
        val scratch = registers
        val guard = """
            move-object/from16 v$scratch, $resultReg
            invoke-virtual {v$scratch}, Lcom/android/billingclient/api/BillingResult;->getResponseCode()I
            move-result v${scratch + 1}
            if-eqz v${scratch + 1}, :morphe_iap_bridge_continue
            return-void
            :morphe_iap_bridge_continue
            nop
        """.trimIndent()
        try {
            val target = mutableClass.methods.firstOrNull { it.name == purchases.name && it.parameterTypes == purchases.parameterTypes && it.returnType == purchases.returnType } ?: continue
            val cloned = purchases.cloneMutable(additionalRegisters = 2)
            mutableClass.methods.remove(target)
            cloned.addInstructions(0, guard)
            mutableClass.methods.add(cloned)
            labels += "$className.onPurchasesUpdated"
            logger.info("Emulate InApp: guarded $className.onPurchasesUpdated bridge")
        } catch (e: Exception) {
            logger.warning("Emulate InApp: IL2CPP billing bridge skipped: ${e.message}")
        }
    }
    return labels
}
