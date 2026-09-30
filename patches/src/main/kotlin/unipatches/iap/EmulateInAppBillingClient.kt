package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import helpers.bytecode.fitsBelowParameters

/** BillingClient entry-point adapter shared by Billing v3 and v9 runtimes. */
internal fun applyBillingClientCorePatches(
    context: InAppManagedAdapterContext,
    flowSpec: BillingClientFlowSpec,
) {
    val patchAll = context.patchAll
    val listenerIget = context.listenerIget
    val buyGrantBlock = context.buyGrantBlock
    val parameterRegister = context.parameterRegister
    val expandSwap = context.expandSwap
    val okBillingResult = context.okBillingResult
    val isBillingNamespace = context.isBillingNamespace

    patchAll(Fingerprint(name = "launchBillingFlow", custom = { method, clazz ->
        method.returnType.contains("BillingResult") &&
            (context.billingAdapterKey == "unknown" || isBillingNamespace(clazz.type))
    }), "BillingClient.${context.billingAdapterKey}.launchBillingFlow", 2) {
        val isStatic = try { com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) } catch (_: Exception) { true }
        val field = if (!isStatic) listenerIget(it.definingClass) else null
        if (field != null) {
            val productArguments = flowSpec.productArguments(it, parameterRegister)
            val block = buyGrantBlock(field, productArguments)
            var granted = false
            if (it.fitsBelowParameters(block)) try { it.addInstructions(0, block); granted = true } catch (_: Exception) {}
            if (!granted) granted = expandSwap(it, block)
            if (!granted) try { it.addInstructions(0, okBillingResult) } catch (_: Exception) {}
        } else try { it.addInstructions(0, okBillingResult) } catch (_: Exception) {}
    }

    patchAll(Fingerprint(name = "launchBillingFlowCpp", custom = { _, clazz -> isBillingNamespace(clazz.type) }), "Unity IL2CPP.launchBillingFlowCpp", 2) {
        val isStatic = try { com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) } catch (_: Exception) { true }
        val field = if (!isStatic) listenerIget(it.definingClass) else null
        if (field != null && it.returnType.contains("BillingResult")) {
            // Positional slots, not "first two object arguments": the same
            // [Activity, flowParams] contract the standard path uses.
            val args = flowSpec.argumentIndices(it.parameterTypes).map { index ->
                if (index >= 0) parameterRegister(it, index) else null
            }
            val block = buyGrantBlock(field, args)
            var granted = false
            if (it.fitsBelowParameters(block)) try { it.addInstructions(0, block); granted = true } catch (_: Exception) {}
            if (!granted) granted = expandSwap(it, block)
            if (granted) return@patchAll
        }
        when {
            it.returnType.contains("BillingResult") -> try { it.addInstructions(0, okBillingResult) } catch (_: Exception) {}
            it.returnType == "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
            it.returnType == "V" -> it.addInstructions(0, "return-void")
        }
    }

    // isReady must track the emulated connection instead of staying true.
    // A permanently-ready client short-circuits connection-gated games
    // (b0()-style ready checks) before they ever reach startConnection, so
    // their setup listener never fires and their own billing gate stays shut.
    patchAll(Fingerprint(name = "isReady", returnType = "Z", custom = { _, clazz -> isBillingNamespace(clazz.type) }), "BillingClient.isReady", 1) {
        it.addInstructions(0, "invoke-static {}, Lunipatch/overlaycore/InAppRuntimePolicy;->isConnectionReady()Z\nmove-result v0\nreturn v0")
    }
    patchAll(Fingerprint(name = "endConnection", custom = { method, clazz -> method.returnType == "V" && isBillingNamespace(clazz.type) }), "BillingClient.endConnection", 1) {
        it.addInstructions(0, "return-void")
    }
}
