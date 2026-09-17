package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import helpers.bytecode.cloneMutable
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

        // REVENUECAT (server receipt validation cannot be faked;
        // these make the app run its bought-path locally instead)
        // ──────────────────────────────────────────────

        val rcPurchases = "Lcom/revenuecat/purchases/Purchases;"
        val rcPurchaseCb = "Lcom/revenuecat/purchases/interfaces/PurchaseCallback;"
        val rcInfo = "Lcom/revenuecat/purchases/EntitlementInfo;"
        val rcInfos = "Lcom/revenuecat/purchases/EntitlementInfos;"
        val rcTx = "Lcom/revenuecat/purchases/models/StoreTransaction;"
        val rcCust = "Lcom/revenuecat/purchases/CustomerInfo;"
        val fakeId = "morphe_fake"

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
                        emit("const-string v10, \"MorpheRC\"")
                        emit("const-string v11, \"RC $pn buy tapped\"")
                        emit("invoke-static {v10, v11}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I")
                        emit("const-string v$uId, \"$fakeId\"")
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
                        try {
                            owner.methods.remove(target)
                        } catch (_: Exception) {}
                        cloned.addInstructions(0, sb.toString().trimIndent())
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
                emit("const-string v$vH, \"MorpheRC\"")
                emit("const-string v${vH + 1}, \"RC purchasesUpdated\"")
                emit("invoke-static {v$vH, v${vH + 1}}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I")
                emit("const-string v$vH, \"{\\\"orderId\\\":\\\"morphe_fake\\\",\\\"packageName\\\":\\\"morphe_fake\\\",\\\"productId\\\":\\\"morphe_fake\\\",\\\"purchaseTime\\\":0,\\\"purchaseState\\\":1,\\\"purchaseToken\\\":\\\"morphe_fake\\\",\\\"quantity\\\":1,\\\"acknowledged\\\":true}\"")
                emit("const-string v${vH + 1}, \"morphe_fake\"")
                emit("new-instance v${vH + 2}, Lcom/android/billingclient/api/Purchase;")
                emit("invoke-direct {v${vH + 2}, v$vH, v${vH + 1}}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V")
                emit("move-object/from16 v$vH, $purchasesReg")
                emit("new-instance v${vH + 1}, Ljava/util/ArrayList;")
                emit("invoke-direct {v${vH + 1}, v$vH}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V")
                emit("invoke-virtual {v${vH + 1}, v${vH + 2}}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z")
                emit("move-object/from16 $purchasesReg, v${vH + 1}")
                try {
                    owner.methods.remove(target)
                } catch (_: Exception) {}
                cloned.addInstructions(0, sb.toString().trimIndent())
                owner.methods.add(cloned)
                patched++
                patchedMethods.add("RC.onPurchasesUpdated")
                logger.info("Emulate InApp: faked purchase into RevenueCat BillingWrapper")
            } catch (e: Exception) {
                logger.warning("Emulate InApp: RC.onPurchasesUpdated fake skipped: ${e.message}")
            }
        }

        // 3) App-side RevenueCat error callbacks with PurchasesError -> suppress,
        // so failed server validation cannot pop error UI over the unlock.
        patchAll(Fingerprint(name = "onError", returnType = "V",
            custom = { m, c -> !c.type.contains("revenuecat") && m.parameterTypes.any { it.contains("PurchasesError") } }),
            "RC.onError") {
            it.addInstructions(0, "return-void")
        }

        // ──────────────────────────────────────────────


    return patched to patchedMethods
}

