package unipatches.iap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.bytecode.cloneMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import java.util.logging.Logger

internal fun BytecodePatchContext.applyCocos2dPatches(
    candidates: List<ClassDef>,
    parameterRegister: (MutableMethod, Int) -> String,
    isFrameworkClass: (String) -> Boolean,
    isBillingNamespace: (String) -> Boolean,
    logger: Logger,
): List<String> {
    val names = listOf("nativeOnSuccess", "onPurchaseSuccess", "onIAPSuccess", "onBillingSuccess", "onSuccess", "purchaseSuccess", "deliverItem", "unlockItem", "creditPurchase", "handlePurchase", "processPurchase", "giveItem", "addPurchase", "grantPurchase")
    for (classDef in candidates) {
        val className = classDef.type
        if (isFrameworkClass(className) || isBillingNamespace(className)) continue
        val wrapper = classDef.methods.firstOrNull { method ->
            method.returnType == "V" && method.parameterTypes.firstOrNull() == "Ljava/lang/String;" &&
                method.implementation?.instructions?.any { it is ReferenceInstruction && it.reference is MethodReference && (it.reference as MethodReference).name == "launchBillingFlow" } == true
        } ?: continue
        val success = classDef.methods.firstOrNull { method ->
            method.returnType == "V" && method.parameterTypes.size in 1..2 && method.parameterTypes.firstOrNull() == "Ljava/lang/String;" &&
                (method.parameterTypes.size == 1 || method.parameterTypes[1] == "Z") && names.any { it.equals(method.name, ignoreCase = true) }
        } ?: continue
        val wrapperStatic = com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(wrapper.accessFlags)
        if (wrapperStatic != com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(success.accessFlags)) continue
        val registers = wrapper.implementation?.registerCount ?: continue
        if (registers > 13) continue
        try {
            val mutableClass = mutableClassDefBy(classDef)
            val mutableWrapper = mutableClass.methods.firstOrNull { it.name == wrapper.name && it.parameterTypes == wrapper.parameterTypes && it.returnType == wrapper.returnType } ?: continue
            val target = mutableClass.methods.firstOrNull { it.name == success.name && it.parameterTypes == success.parameterTypes && it.returnType == success.returnType } ?: continue
            val scratch = registers
            val product = parameterRegister(mutableWrapper, 0)
            val signature = success.parameterTypes.joinToString("")
            val args = if (success.parameterTypes.size == 1) "v$scratch" else "v$scratch, v${scratch + 1}"
            val invoke = when {
                com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(success.accessFlags) -> "invoke-static"
                com.android.tools.smali.dexlib2.AccessFlags.PRIVATE.isSet(success.accessFlags) -> "invoke-direct"
                else -> "invoke-virtual"
            }
            val receiver = if (com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(success.accessFlags)) "" else "p0, "
            val bool = if (success.parameterTypes.size == 2) "const/4 v${scratch + 1}, 0x1\n" else ""
            val block = "move-object/from16 v$scratch, $product\n$bool$invoke {$receiver$args}, $className->${success.name}($signature)V"
            val cloned = mutableWrapper.cloneMutable(additionalRegisters = 2)
            mutableClass.methods.remove(mutableWrapper)
            cloned.addInstructions(0, block)
            mutableClass.methods.add(cloned)
            val label = "Cocos2D.$className->${success.name}"
            logger.info("Emulate InApp: linked $label")
            return listOf(label)
        } catch (e: Exception) {
            logger.warning("Emulate InApp: Cocos2D callback skipped: ${e.message}")
        }
    }
    return emptyList()
}
