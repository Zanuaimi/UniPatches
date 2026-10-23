package unipatches.iap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import helpers.bytecode.numberOfParameterRegisters

internal val revenueCatAmazonRequiredTypes = setOf(
    "Lcom/amazon/device/iap/PurchasingService;",
    "Lcom/amazon/device/iap/PurchasingListener;",
    "Lcom/amazon/device/iap/model/ProductDataResponse;",
    "Lcom/amazon/device/iap/model/PurchaseResponse;",
    "Lcom/amazon/device/iap/model/PurchaseUpdatesResponse;",
    "Lcom/amazon/device/iap/model/UserDataResponse;",
)

/** Remove unavailable Amazon construction without changing the factory's store dispatch. */
internal fun isolateMissingRevenueCatAmazonBranch(method: MutableMethod): Boolean {
    val implementation = method.implementation ?: return false
    val instructions = implementation.instructions.toList()
    val amazon = "Lcom/revenuecat/purchases/amazon/AmazonBilling;"
    fun referencesAmazon(index: Int): Boolean {
        val reference = (instructions[index] as? ReferenceInstruction)?.reference
        return when (reference) {
            is TypeReference -> reference.type == amazon
            is MethodReference -> reference.definingClass == amazon
            else -> false
        }
    }
    val starts = instructions.indices.filter {
        instructions[it].opcode == Opcode.NEW_INSTANCE && referencesAmazon(it)
    }
    if (starts.size != 1) return false
    val start = starts.single()
    val end = (start + 1 until instructions.size).firstOrNull {
        instructions[it].opcode == Opcode.RETURN_OBJECT
    } ?: return false
    // Only the straight-line construction shape is supported. Do not rewrite
    // an unfamiliar SDK layout or references outside this branch.
    val body = instructions.subList(start + 1, end)
    if (body.any {
        val mnemonic = it.opcode.name.lowercase()
        mnemonic.startsWith("if-") || mnemonic.startsWith("goto") ||
            mnemonic.contains("switch") || it.opcode == Opcode.THROW ||
            mnemonic.startsWith("return")
    }) return false
    if (instructions.indices.any { referencesAmazon(it) && it !in start..end }) return false
    if (body.none {
        val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
        reference?.definingClass == amazon && reference.name == "<init>"
    }) return false
    val exceptionRegister = (instructions[start] as OneRegisterInstruction).registerA
    val messageRegister = exceptionRegister + 1
    val parameterStart = implementation.registerCount - method.numberOfParameterRegisters
    if (messageRegister > 15 || messageRegister >= parameterStart) return false
    // Retain instruction locations so dispatch and existing catch labels remain
    // attached. The terminal throw prevents fall-through into the old handler.
    method.replaceInstruction(start, "new-instance v$exceptionRegister, Ljava/lang/IllegalStateException;")
    for (index in start + 1..end) method.replaceInstruction(index, "nop")
    method.addInstructions(start + 1, """
        const-string v$messageRegister, "RevenueCat Amazon IAP dependencies are missing. Use a complete Amazon build; the configured store was not changed."
        invoke-direct {v$exceptionRegister, v$messageRegister}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V
        throw v$exceptionRegister
    """.trimIndent())
    return true
}
