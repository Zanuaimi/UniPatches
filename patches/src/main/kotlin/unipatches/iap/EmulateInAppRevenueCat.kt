package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import helpers.bytecode.cloneMutable
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger

internal fun BytecodePatchContext.applyRevenueCatPatches(
    context: InAppManagedAdapterContext,
    logger: Logger,
): Pair<Int, List<String>> {
    val patchAllRaw = context.patchAll
    fun patchAll(fingerprint: Fingerprint, label: String, needRegs: Int = 1, injector: (app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) -> Unit) =
        patchAllRaw(fingerprint, label, needRegs, injector)
    val parameterRegister = context.parameterRegister
    var patched = 0
    val patchedMethods = mutableListOf<String>()

    // Rizz 2.2.3 can receive a null LiveData while stopping its paywall.
    // Guard only that observed lifecycle method; keep its normal body and all
    // RevenueCat error callbacks when the model is initialized.
    patchAll(Fingerprint(
        name = "onScreenStopped",
        definingClass = "Lcom/rizzlabs/rizz/viewmodels/PaywallImmediateViewModel;",
        returnType = "V",
        custom = { m, _ -> m.parameterTypes.isEmpty() },
    ), "RC.Rizz.paywallLifecycle", 3) { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@patchAll
        val state = instructions.asSequence().filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .firstOrNull { it.definingClass == method.definingClass && it.name == "state" } ?: return@patchAll
        val getters = listOf("getBypassNotification", "getAddCloseToPaywallImmediate").map { name ->
            instructions.asSequence().filter { it.opcode == Opcode.INVOKE_VIRTUAL }
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .firstOrNull { it.definingClass == state.type && it.name == name &&
                    it.parameterTypes.isEmpty() && it.returnType in setOf(
                        "Landroidx/lifecycle/LiveData;", "Landroidx/lifecycle/MutableLiveData;",
                    ) }
        }
        if (getters.any { it == null }) return@patchAll
        val owner = mutableClassDefByOrNull(method.definingClass) ?: return@patchAll
        val target = owner.methods.firstOrNull {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        } ?: return@patchAll
        val guard = buildString {
            appendLine("move-object/from16 v1, p0")
            for ((index, getter) in getters.withIndex()) {
                appendLine("iget-object v0, v1, ${state.definingClass}->${state.name}:${state.type}")
                appendLine("if-eqz v0, :morphe_rizz_stop_null_$index")
                appendLine("invoke-virtual {v0}, ${getter!!.definingClass}->${getter.name}()${getter.returnType}")
                appendLine("move-result-object v0")
                appendLine("if-nez v0, :morphe_rizz_stop_next_$index")
                appendLine(":morphe_rizz_stop_null_$index")
                appendLine("return-void")
                appendLine(":morphe_rizz_stop_next_$index")
            }
        }
        try {
            val cloned = method.cloneMutable()
            cloned.addInstructions(0, guard)
            owner.methods.remove(target)
            owner.methods.add(cloned)
            patched++
            patchedMethods.add("RC.Rizz.paywallLifecycle")
        } catch (error: Exception) {
            logger.warning("Emulate InApp: Rizz lifecycle guard skipped: ${error.message}")
        }
    }

        // REVENUECAT (server receipt validation cannot be faked;
        // these make the app run its bought-path locally instead)
        // ──────────────────────────────────────────────

        val missingAmazonTypes = revenueCatAmazonRequiredTypes.filter {
            classDefByOrNull(it) == null
        }
        if (missingAmazonTypes.isNotEmpty()) {
            patchAll(Fingerprint(
                name = "createBilling",
                definingClass = "Lcom/revenuecat/purchases/BillingFactory;",
                returnType = "Lcom/revenuecat/purchases/common/BillingAbstract;",
                custom = { m, _ -> m.parameterTypes.firstOrNull() == "Lcom/revenuecat/purchases/Store;" },
            ), "RC.BillingFactory.missingAmazonSdk") { method ->
                val owner = mutableClassDefByOrNull(method.definingClass) ?: return@patchAll
                val target = owner.methods.firstOrNull {
                    it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
                } ?: return@patchAll
                val cloned = method.cloneMutable()
                if (isolateMissingRevenueCatAmazonBranch(cloned)) {
                    owner.methods.remove(target)
                    owner.methods.add(cloned)
                    patched++
                    patchedMethods.add("RC.BillingFactory.missingAmazonSdk")
                    logger.info("Emulate InApp: isolated unavailable Amazon factory branch; Play/test dispatch preserved")
                } else {
                    logger.warning("Emulate InApp: Amazon dependency compatibility skipped (unsupported factory layout): $missingAmazonTypes")
                }
            }
        }

        val rcPurchases = "Lcom/revenuecat/purchases/Purchases;"
        val rcPurchaseCb = "Lcom/revenuecat/purchases/interfaces/PurchaseCallback;"
        val rcInfo = "Lcom/revenuecat/purchases/EntitlementInfo;"
        val rcInfos = "Lcom/revenuecat/purchases/EntitlementInfos;"
        val rcTx = "Lcom/revenuecat/purchases/models/StoreTransaction;"
        val rcCust = "Lcom/revenuecat/purchases/CustomerInfo;"

        // 1b) Same fake via sun.misc.Unsafe allocation (no constructors, no
        // range invokes): allocate + populate fields resolved at patch time.
        fun rcField(className: String, name: String, type: String? = null): String? {
            val cls = try { mutableClassDefByOrNull(className) } catch (_: Exception) { return null } ?: return null
            val f = cls.fields.firstOrNull { it.name == name && (type == null || it.type == type) } ?: return null
            return "$className->${f.name}:${f.type}"
        }
        fun rcFirstFieldOfType(className: String, type: String): String? {
            val cls = try { mutableClassDefByOrNull(className) } catch (_: Exception) { return null } ?: return null
            val f = cls.fields.firstOrNull { it.type == type } ?: return null
            return "$className->${f.name}:${f.type}"
        }
        val rcInfoIdF = rcField(rcInfo, "identifier", "Ljava/lang/String;")
            ?: rcFirstFieldOfType(rcInfo, "Ljava/lang/String;")
        val rcInfoActiveF = rcField(rcInfo, "isActive", "Z")
            ?: rcFirstFieldOfType(rcInfo, "Z")
        val rcInfosCtor1 = try {
            mutableClassDefByOrNull(rcInfos)?.methods
                ?.firstOrNull { it.name == "<init>" && it.parameterTypes == listOf("Ljava/util/Map;") }
        } catch (_: Exception) { null }
        val rcTxOrderF = rcField(rcTx, "orderId", "Ljava/lang/String;")
        val rcTxTokenF = rcField(rcTx, "purchaseToken", "Ljava/lang/String;")
        val rcCustInfosF = rcFirstFieldOfType(rcCust, rcInfos)
        if (rcInfoIdF != null && rcInfoActiveF != null && rcInfosCtor1 != null && rcCustInfosF != null) {
            // fixed regs v0-v9 (4-bit-safe throughout, no range, no clone-window math beyond +12)
            val uCb = 0
            val uId = 1
            val uInfo = 2
            val uMap = 3
            val uInfos = 4
            val uTx = 5
            val uCust = 6
            val uUnsafe = 7
            val uField = 8
            val uTmp = 9
            for (pn in listOf("purchase", "purchasePackage", "purchaseProduct")) {
                patchAll(Fingerprint(name = pn, definingClass = rcPurchases, returnType = "V",
                    custom = { m, _ -> m.parameterTypes.lastOrNull() == rcPurchaseCb }), "RC.$pn-unsafe") { method ->
                    try {
                        val cbReg = parameterRegister(method, method.parameterTypes.lastIndex)
                        val productArg = method.parameterTypes.indexOfFirst {
                            it != rcPurchaseCb && (it.startsWith("L") || it.startsWith("["))
                        }.takeIf { it >= 0 }?.let { parameterRegister(method, it) }
                        val owner = try {
                            Fingerprint(name = pn, definingClass = rcPurchases, returnType = "V",
                                custom = { m, _ -> m.parameterTypes.lastOrNull() == rcPurchaseCb }).classDefOrNull
                        } catch (_: Exception) { null } ?: return@patchAll
                        val cloned = method.cloneMutable(additionalRegisters = 12)
                        val target = owner.methods.firstOrNull {
                            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
                        } ?: return@patchAll
                        val sb = StringBuilder()
                        fun emit(s: String) {
                            sb.append(s).append('\n')
                        }
                        emit("move-object/from16 v$uCb, $cbReg")
                        if (productArg == null) {
                            logger.warning("Emulate InApp: RC.$pn skipped (no product-bearing argument)")
                            return@patchAll
                        }
                        emit("move-object/from16 v$uTmp, $productArg")
                        emit("invoke-static {v$uTmp}, Lunipatch/overlaycore/InAppRuntimePolicy;->productIdFrom(Ljava/lang/Object;)Ljava/lang/String;")
                        emit("move-result-object v$uId")
                        emit("if-eqz v$uId, :morphe_rc_${pn}_original")
                        // Unsafe handle
                        emit("const-string v$uTmp, \"theUnsafe\"")
                        emit("const-class v$uUnsafe, Lsun/misc/Unsafe;")
                        emit("invoke-virtual {v$uUnsafe, v$uTmp}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;")
                        emit("move-result-object v$uField")
                        emit("const/4 v$uTmp, 0x1")
                        emit("invoke-virtual {v$uField, v$uTmp}, Ljava/lang/reflect/Field;->setAccessible(Z)V")
                        emit("const/4 v$uTmp, 0x0")
                        emit("invoke-virtual {v$uField, v$uTmp}, Ljava/lang/reflect/Field;->get(Ljava/lang/Object;)Ljava/lang/Object;")
                        emit("move-result-object v$uUnsafe")
                        emit("check-cast v$uUnsafe, Lsun/misc/Unsafe;")
                        // EntitlementInfo + active flag + id
                        emit("const-class v$uTmp, $rcInfo")
                        emit("invoke-virtual {v$uUnsafe, v$uTmp}, Lsun/misc/Unsafe;->allocateInstance(Ljava/lang/Class;)Ljava/lang/Object;")
                        emit("move-result-object v$uInfo")
                        emit("check-cast v$uInfo, $rcInfo")
                        emit("const/4 v$uTmp, 0x1")
                        emit("iput-boolean v$uTmp, v$uInfo, $rcInfoActiveF")
                        emit("iput-object v$uId, v$uInfo, $rcInfoIdF")
                        // EntitlementInfos via real 1-arg ctor over singleton map
                        emit("invoke-static {v$uId, v$uInfo}, Ljava/util/Collections;->singletonMap(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;")
                        emit("move-result-object v$uMap")
                        emit("new-instance v$uInfos, $rcInfos")
                        emit("invoke-direct {v$uInfos, v$uMap}, $rcInfos-><init>(Ljava/util/Map;)V")
                        // StoreTransaction allocated, best-effort id fields
                        emit("const-class v$uTmp, $rcTx")
                        emit("invoke-virtual {v$uUnsafe, v$uTmp}, Lsun/misc/Unsafe;->allocateInstance(Ljava/lang/Class;)Ljava/lang/Object;")
                        emit("move-result-object v$uTx")
                        emit("check-cast v$uTx, $rcTx")
                        if (rcTxOrderF != null) emit("iput-object v$uId, v$uTx, $rcTxOrderF")
                        if (rcTxTokenF != null) emit("iput-object v$uId, v$uTx, $rcTxTokenF")
                        // CustomerInfo allocated + infos field
                        emit("const-class v$uTmp, $rcCust")
                        emit("invoke-virtual {v$uUnsafe, v$uTmp}, Lsun/misc/Unsafe;->allocateInstance(Ljava/lang/Class;)Ljava/lang/Object;")
                        emit("move-result-object v$uCust")
                        emit("check-cast v$uCust, $rcCust")
                        emit("iput-object v$uInfos, v$uCust, $rcCustInfosF")
                        emit("invoke-interface {v$uCb, v$uTx, v$uCust}, $rcPurchaseCb->onCompleted($rcTx$rcCust)V")
                        emit("return-void")
                        emit(":morphe_rc_${pn}_original")
                        cloned.addInstructions(0, sb.toString().trimIndent())
                        owner.methods.remove(target)
                        owner.methods.add(cloned)
                        patched++
                        patchedMethods.add("RC.$pn-unsafe")
                        logger.info("Emulate InApp: faked RevenueCat $pn success callback (unsafe)")
                    } catch (e: Exception) {
                        logger.warning("Emulate InApp: RC.$pn unsafe fake skipped: ${e.message}")
                    }
                }
            }
        } else {
            logger.warning("Emulate InApp: RevenueCat unsafe fake skipped (fields not found)")
        }

        // 2) RevenueCat BillingWrapper.onPurchasesUpdated -> append a fake
        // PURCHASED Google purchase to a list copy, rebind the param, fall through.
        patchAll(Fingerprint(name = "onPurchasesUpdated",
            definingClass = "Lcom/revenuecat/purchases/google/BillingWrapper;",
            returnType = "V",
            custom = { m, _ -> m.parameterTypes.size == 2 && m.parameterTypes[1] == "Ljava/util/List;" }),
            "RC.onPurchasesUpdated") { method ->
        // Restored in v1.31.1 with a low-register copy. Coverage requires explicit
        // opt-in in both modes; this workaround does not repair the general emitter.
        logger.info("Emulate InApp: RC.onPurchasesUpdated fake enabled (unconditional low-register copy)")
        run {
            try {
                val origCount = method.implementation!!.registerCount
                // High regs only: low regs are Undefined at entry (reading them
                // fails verification), and 35c needs regs <= 15. Temps must also
                // stay BELOW the param slots at the top of the frame.
                if (origCount > 13) {
                    logger.warning("Emulate InApp: RC.onPurchasesUpdated fake skipped (frame too large)")
                    return@patchAll
                }
                val vH = origCount
                val owner = try {
                    Fingerprint(name = "onPurchasesUpdated",
                        definingClass = "Lcom/revenuecat/purchases/google/BillingWrapper;",
                        returnType = "V",
                        custom = { m, _ -> m.parameterTypes.size == 2 && m.parameterTypes[1] == "Ljava/util/List;" }).classDefOrNull
                } catch (_: Exception) { null } ?: return@patchAll
                // +8: temps (3) must end up strictly below the param slots.
                val cloned = method.cloneMutable(additionalRegisters = 8)
                val target = owner.methods.firstOrNull {
                    it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
                } ?: return@patchAll
                val sb = StringBuilder()
                val purchasesReg = parameterRegister(method, 1)
                fun emit(s: String) {
                    sb.append(s).append('\n')
                }
                // invoke-static (format 35c) encodes argument registers in 4 bits, so it cannot
                // address v16 and above, and this patcher's parser silently DROPS an
                // out-of-range 35c invoke rather than failing. That strands the following
                // move-result-object and fails dex verification.
                //
                // Always copy the parameter through a low temp instead of branching on whether
                // the register looks high. A guard that parses the register name is unreliable
                // here: parameterRegister can hand back a param-style spelling whose numeric
                // suffix does not parse, so the check silently fails and the raw high-register
                // invoke is emitted and dropped. move-object/from16 takes a full 16-bit register,
                // so the copy is always legal, and when the source register is already low the
                // extra move is a harmless no-op. vH + 1 is below 16 because origCount <= 13.
                emit(revenueCatProductIdInstructions(purchasesReg, vH + 1, vH))
                emit("if-eqz v$vH, :morphe_rc_purchases_original")
                emit("const-string v${vH + 1}, \"{}\"")
                emit("new-instance v${vH + 2}, Lcom/android/billingclient/api/Purchase;")
                emit("invoke-direct {v${vH + 2}, v$vH, v${vH + 1}}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V")
                emit("move-object/from16 v$vH, $purchasesReg")
                emit("new-instance v${vH + 1}, Ljava/util/ArrayList;")
                emit("invoke-direct {v${vH + 1}, v$vH}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V")
                emit("invoke-virtual {v${vH + 1}, v${vH + 2}}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z")
                emit("move-object/from16 $purchasesReg, v${vH + 1}")
                emit(":morphe_rc_purchases_original")
                cloned.addInstructions(0, sb.toString().trimIndent())
                owner.methods.remove(target)
                owner.methods.add(cloned)
                patched++
                patchedMethods.add("RC.onPurchasesUpdated")
                logger.info("Emulate InApp: faked purchase into RevenueCat BillingWrapper")
            } catch (e: Exception) {
                logger.warning("Emulate InApp: RC.onPurchasesUpdated fake skipped: ${e.message}")
            }
        }
        }

        // Leave application PurchasesError callbacks intact. Swallowing them can
        // skip UI/model cleanup and strand fields that lifecycle handlers read.

        // ──────────────────────────────────────────────


    return patched to patchedMethods
}

internal fun revenueCatProductIdInstructions(source: String, temporary: Int, result: Int): String {
    require(temporary in 0..15 && result in 0..15 && temporary != result)
    return """
        move-object/from16 v$temporary, $source
        invoke-static {v$temporary}, Lunipatch/overlaycore/InAppRuntimePolicy;->productIdFrom(Ljava/lang/Object;)Ljava/lang/String;
        move-result-object v$result
    """.trimIndent()
}
