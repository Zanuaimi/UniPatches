package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

/** BillingClient entry-point adapter shared by Billing v3 and v9 runtimes. */
internal fun applyBillingClientCorePatches(context: InAppManagedAdapterContext) {
    val patchAll = context.patchAll
    val listenerIget = context.listenerIget
    val buyGrantBlock = context.buyGrantBlock
    val parameterRegister = context.parameterRegister
    val minRegs = context.minRegs
    val expandSwap = context.expandSwap
    val okBillingResult = context.okBillingResult
    val isBillingNamespace = context.isBillingNamespace

    patchAll(Fingerprint(name = "launchBillingFlow", custom = { method, clazz ->
        method.returnType.contains("BillingResult") &&
            (context.billingAdapterKey == "unknown" || clazz.type.contains("BillingClient"))
    }), "BillingClient.${context.billingAdapterKey}.launchBillingFlow", 2) {
        val isStatic = try { com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) } catch (_: Exception) { true }
        val field = if (!isStatic) listenerIget(it.definingClass) else null
        if (field != null) {
            val productArguments = it.parameterTypes.mapIndexedNotNull { index, type ->
                if (type.startsWith("L") || type.startsWith("[")) parameterRegister(it, index) else null
            }.take(2)
            val block = buyGrantBlock(field, productArguments)
            var granted = false
            if (minRegs(it) >= 5) try { it.addInstructions(0, block); granted = true } catch (_: Exception) {}
            if (!granted) granted = expandSwap(it, block)
            if (!granted) try { it.addInstructions(0, okBillingResult) } catch (_: Exception) {}
        } else try { it.addInstructions(0, okBillingResult) } catch (_: Exception) {}
    }

    patchAll(Fingerprint(name = "launchBillingFlowCpp", custom = { _, clazz -> isBillingNamespace(clazz.type) }), "Unity IL2CPP.launchBillingFlowCpp", 2) {
        val isStatic = try { com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) } catch (_: Exception) { true }
        val field = if (!isStatic) listenerIget(it.definingClass) else null
        if (field != null && it.returnType.contains("BillingResult")) {
            val args = it.parameterTypes.mapIndexedNotNull { index, type ->
                if (type.startsWith("L") || type.startsWith("[")) parameterRegister(it, index) else null
            }.take(2)
            val block = buyGrantBlock(field, args)
            var granted = false
            if (minRegs(it) >= 4) try { it.addInstructions(0, block); granted = true } catch (_: Exception) {}
            if (!granted) granted = expandSwap(it, block)
            if (granted) return@patchAll
        }
        when {
            it.returnType.contains("BillingResult") -> try { it.addInstructions(0, okBillingResult) } catch (_: Exception) {}
            it.returnType == "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
            it.returnType == "V" -> it.addInstructions(0, "return-void")
        }
    }

    patchAll(Fingerprint(name = "isReady", returnType = "Z", custom = { _, clazz -> clazz.type.contains("BillingClient") }), "BillingClient.isReady", 1) {
        it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
    }
    patchAll(Fingerprint(name = "endConnection", custom = { method, clazz -> method.returnType == "V" && clazz.type.contains("BillingClient") }), "BillingClient.endConnection", 1) {
        it.addInstructions(0, "return-void")
    }
}
