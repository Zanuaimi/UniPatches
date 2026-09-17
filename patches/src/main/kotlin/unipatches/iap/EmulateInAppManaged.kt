package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.bytecode.cloneMutable
import helpers.bytecode.cloneMutableAndAllocateScratchRegisters
import java.util.logging.Logger

@Suppress("unused")
internal fun emulateInAppManagedPatch(inventoryOptionsProvider: () -> Triple<Boolean, String, Pair<Int, Int>>) = bytecodePatch(
    name = null,
    description = """
        Get paid items free: buying grants items without charging. Best for offline games.
        
        InApp Emulation Overlay Module : This patch as an overlay addon involves an InApp Emulation hook module being added to overlay menu, that has a settings button, which shows a popup showing a checkbox whether to enable InApp Emulation popup or not at buy time, and then if it's enabled, it list of saved purchases, that are saved at buy time if user chooses to save the purchase. Saved purchases are so popup windows don't appear again on buy time. The settings popup list of item of saved purchases also allows for management of them, by deleting saved purchases items.
        
        Experimental : This patch may not work on all apps, as it modifies internal app / game behavior.
        
        Credits to Nai64Patches from Nai64 for original IAP patch functionality, and enhancement process of this patch is inspired by MiguelNinja19's billing patches. 
        UniPatches enhances this patch by improving compatibility, stability, and adding an optional overlay addon.
        
        
    """.trimIndent(),
    default = false,
) {
    // Guarded: morphe-patcher < 1.13.0 has no category() and keeps the patch ungrouped.
    try { category("InApp Emulation") } catch (_: NoSuchMethodError) {}
    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val managedPhaseStart = System.nanoTime()
        var patched = 0
        val patchedMethods = mutableSetOf<String>()
        val (_, _, timeouts) = inventoryOptionsProvider()
        val nonOverlayTimeout = timeouts.first.coerceIn(1, 86400)
        val overlayTimeout = timeouts.second.coerceIn(1, 86400)

        // Minimum registers a frame provably holds: param slots (J/D count
        // double) plus this for instance methods. Injected blocks use fixed
        // low regs, and writing past the frame fails verification for the
        // whole class (frozen loading screens), so every injection below is
        // gated on the frame holding it.
        fun minRegs(m: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod): Int {
            return try {
                var slots = 0
                if (!com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(m.accessFlags)) slots += 1
                for (p in m.parameterTypes) slots += if (p == "J" || p == "D") 2 else 1
                slots
            } catch (_: Exception) { 0 }
        }
        // Frame expansion for injections needing more regs than the frame
        // holds: clone with extra registers and swap the clone in. The
        // prologue cloneMutable adds is harmless because expanded injections
        // always return before the original body runs.
        fun expandSwap(m: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, block: String): Boolean {
            return try {
                val owner = mutableClassDefByOrNull(m.definingClass) ?: return false
                val target = owner.methods.firstOrNull {
                    it.name == m.name && it.parameterTypes == m.parameterTypes && it.returnType == m.returnType
                } ?: return false
                val cloned = m.cloneMutable(additionalRegisters = 4)
                owner.methods.remove(target)
                cloned.addInstructions(0, block)
                owner.methods.add(cloned)
                logger.info("FreeIAP expanded frame: ${m.definingClass}->${m.name}")
                true
            } catch (_: Exception) { false }
        }
        fun patchAll(fp: Fingerprint, label: String, needRegs: Int = 1, injector: (app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) -> Unit) {
            // try multi-match first via context receiver
            try {
                val matches: List<app.morphe.patcher.Match> = try {
                    with(this@execute) { fp.matchAll() }
                } catch (_: Exception) {
                    emptyList()
                }
                if (matches.isNotEmpty()) {
                    for (m in matches) {
                        try {
                            val method = m.method
                            if (method.implementation == null) continue
                            if (minRegs(method) < needRegs) {
                                logger.info("FreeIAP skipped tiny frame: ${method.definingClass}->${method.name} regs=${minRegs(method)} need=$needRegs label=$label")
                                continue
                            }
                            injector(method)
                            patched++
                            patchedMethods.add(label)
                        } catch (_: Exception) {}
                    }
                    return
                }
            } catch (_: Exception) {}
            // fallback single
            val single = try { with(this@execute) { fp.matchOrNull() }?.method } catch (_: Exception) { null } ?: try { fp.methodOrNull } catch (_: Exception) { null }
            if (single?.implementation != null) {
                try {
                    if (minRegs(single) < needRegs) return
                    injector(single)
                    patched++
                    patchedMethods.add(label)
                } catch (_: Exception) {}
            }
        }

        fun parameterRegister(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, index: Int): String {
            var register = if (com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
            for (type in method.parameterTypes.take(index)) register += if (type == "J" || type == "D") 2 else 1
            return "p$register"
        }

        fun safeReturn(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, success: Boolean = false): String = when (method.returnType) {
            "V" -> "return-void"
            "Z" -> "const/4 v0, ${if (success) "0x1" else "0x0"}\nreturn v0"
            "B", "S", "C", "I" -> "const/4 v0, 0x0\nreturn v0"
            "J", "D" -> "const-wide/16 v0, 0x0\nreturn-wide v0"
            "F" -> "const/4 v0, 0x0\nreturn v0"
            else -> "const/4 v0, 0x0\nreturn-object v0"
        }

        fun isBillingNamespace(type: String): Boolean {
            val lower = type.lowercase()
            return lower.contains("billing") ||
                type.startsWith("Lcom/android/billingclient/api/") ||
                type.startsWith("Lcom/android/vending/billing/") ||
                type.startsWith("Lcom/google/android/gms/iap/")
        }

        fun isFrameworkClass(type: String): Boolean {
            return type.startsWith("Landroid/") || type.startsWith("Ljava/") ||
                type.startsWith("Lkotlin/") || type.startsWith("Lcom/google/") ||
                type.startsWith("Lcom/unity3d/")
        }

        val okBillingResult = """
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v0
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v0
            invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            return-object v0
        """.trimIndent()
        val cancelledBillingResult = """
            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v0
            const/4 v1, 0x1
            invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
            move-result-object v0
            invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
            move-result-object v0
            return-object v0
        """.trimIndent()

        // ──────────────────────────────────────────────
        // GOOGLE PLAY BILLING
        // ──────────────────────────────────────────────

        // Resolve iget chain loading the PurchasesUpdatedListener into v0
        // on a BillingClient impl. Billing 5-7 holds it directly; billing
        // 8+ buries it in a holder (e.g. zze:zzn -> zzn.zzb). Field names
        // are obfuscated per version, so resolve by TYPE at patch time.
        fun listenerIget(defClass: String): String? {
            return try {
                val cls = mutableClassDefByOrNull(defClass) ?: return null
                val direct = cls.fields.firstOrNull {
                    it.type == "Lcom/android/billingclient/api/PurchasesUpdatedListener;"
                }
                if (direct != null) {
                    return "iget-object v0, v0, $defClass->${direct.name}:${direct.type}"
                }
                for (f in cls.fields) {
                    val holder = f.type
                    if (!holder.startsWith("Lcom/android/billingclient/api/")) continue
                    if (holder.contains("Listener;")) continue
                    val holderCls = try { mutableClassDefByOrNull(holder) } catch (_: Exception) { null } ?: continue
                    val inner = holderCls.fields.firstOrNull {
                        it.type == "Lcom/android/billingclient/api/PurchasesUpdatedListener;"
                    } ?: continue
                    return "iget-object v0, v0, $defClass->${f.name}:${f.type}\n" +
                        "if-eqz v0, :morphe_iap_no_listener\n" +
                        "iget-object v0, v0, $holder->${inner.name}:${inner.type}"
                }
                null
            } catch (_: Exception) { null }
        }
        // Buy-time grant block for launchBillingFlow: dispatch the purchase
        // request through the runtime policy, then return a valid OK result.
        // The policy owns fake purchase construction and popup timing. A null
        // listener or listener-holder is rejected instead of receiving a false
        // success result. This mirrors native
        // MOD-menu behavior:
        // grant happens when the user buys, while init/query/catalog paths
        // stay stock so strict titles keep booting.
        // NOTE (morphe inline-smali quirk, verified by assembling test
        // fragments): p-regs resolving above v15 FAIL to assemble in
        // methods with big frames, and the failed line is silently
        // dropped. Every injection below therefore copies params with
        // move-*/from16 (which assembles in any frame) and otherwise
        // touches only v-regs. NEVER use a narrow opcode with a p-reg.
        fun buyGrantBlock(igetTail: String, productArguments: List<String> = emptyList()): String {
            val validationFirst = productArguments.getOrNull(0)?.let { "move-object/from16 v1, $it" } ?: "const/4 v1, 0x0"
            val validationSecond = productArguments.getOrNull(1)?.let { "move-object/from16 v2, $it" } ?: "const/4 v2, 0x0"
            return """
                move-object/from16 v4, p0
                move-object/from16 v0, p0
                $igetTail
                if-eqz v0, :morphe_iap_no_listener
                $validationFirst
                $validationSecond
                invoke-static {v4, v0, v1, v2}, Lunipatch/overlaycore/InAppRuntimePolicy;->validateModernPurchase(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)I
                move-result v3
                if-eqz v3, :morphe_iap_valid
                invoke-static {v3}, Lunipatch/overlaycore/InAppRuntimePolicy;->billingResult(I)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Lcom/android/billingclient/api/BillingResult;
                return-object v1
                :morphe_iap_valid
                $validationFirst
                $validationSecond
                const v3, $nonOverlayTimeout
                const v4, $overlayTimeout
                invoke-static {v3, v4}, Lunipatch/overlaycore/InAppRuntimePolicy;->configureTimeouts(II)V
                invoke-static {v0, v1, v2}, Lunipatch/overlaycore/InAppRuntimePolicy;->dispatch(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v3
                if-eqz v3, :morphe_iap_cancelled
                goto :morphe_iap_nocb
                :morphe_iap_cancelled
                $cancelledBillingResult
                :morphe_iap_no_listener
                $cancelledBillingResult
                :morphe_iap_nocb
                invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                move-result-object v1
                const/4 v2, 0x0
                invoke-virtual {v1, v2}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                move-result-object v1
                invoke-virtual {v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
                move-result-object v1
                return-object v1
            """.trimIndent()
        }

        patchAll(Fingerprint(name = "launchBillingFlow", custom = { m, _ -> m.returnType.contains("BillingResult") }), "launchBillingFlow", 2) {
            val isStatic = try {
                com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags)
            } catch (_: Exception) { true }
            val field = if (!isStatic) listenerIget(it.definingClass) else null
            if (field != null) {
                val productArguments = it.parameterTypes.mapIndexedNotNull { index, type ->
                    if (type.startsWith("L") || type.startsWith("[")) parameterRegister(it, index) else null
                }.take(2)
                val block = buyGrantBlock(field, productArguments)
                var granted = false
                if (minRegs(it) >= 5) {
                    try { it.addInstructions(0, block); granted = true } catch (_: Exception) {}
                }
                if (!granted) {
                    try { granted = expandSwap(it, block) } catch (_: Exception) {}
                }
                if (granted) {
                    logger.info("FreeIAP buy-time grant: ${it.definingClass}->${it.name}")
                } else try {
                    it.addInstructions(0, okBillingResult)
                } catch (_: Exception) {}
            } else try {
                it.addInstructions(0, okBillingResult)
            } catch (_: Exception) {
                try { it.addInstructions(0, "const/4 v0, 0x0\nreturn-object v0") } catch (_: Exception) {}
            }
        }

        // OpenIAB legacy purchase flow. The runtime policy is safe in both modes:
        // without the overlay it delivers immediately; with the overlay it waits
        // for the confirmation popup. Keep the interception independent from the
        // optional overlay addon so legacy non-overlay IAP is still emulated.
        run {
            val openIabHelper = "Lorg/onepf/oms/OpenIabHelper;"
            val legacyPurchaseSignatures = listOf(
                listOf(
                    "Landroid/app/Activity;", "Ljava/lang/String;", "I",
                    "Lorg/onepf/oms/appstore/googleUtils/IabHelper${'$'}OnIabPurchaseFinishedListener;",
                ),
                listOf(
                    "Landroid/app/Activity;", "Ljava/lang/String;", "I",
                    "Lorg/onepf/oms/appstore/googleUtils/IabHelper${'$'}OnIabPurchaseFinishedListener;", "Ljava/lang/String;",
                ),
                listOf(
                    "Landroid/app/Activity;", "Ljava/lang/String;", "Ljava/lang/String;", "I",
                    "Lorg/onepf/oms/appstore/googleUtils/IabHelper${'$'}OnIabPurchaseFinishedListener;", "Ljava/lang/String;",
                ),
            )
            for (flowName in listOf("launchPurchaseFlow", "launchSubscriptionPurchaseFlow")) {
                for (signature in legacyPurchaseSignatures) {
                    patchAll(Fingerprint(
                        name = flowName,
                        definingClass = openIabHelper,
                        returnType = "V",
                        custom = { method, _ -> method.parameterTypes == signature },
                    ), "OpenIAB.$flowName", 3) { method ->
                        val skuIndex = 1
                        val listenerIndex = signature.indexOfFirst { it.contains("OnIabPurchaseFinishedListener") }
                        val purchaseActivity = parameterRegister(method, 0)
                        val sku = parameterRegister(method, skuIndex)
                        val listener = parameterRegister(method, listenerIndex)
                        val developerPayload = if (signature.size >= 5) parameterRegister(method, signature.lastIndex) else "null"
                        val developerPayloadInstruction = if (developerPayload == "null") {
                            "const-string v3, \"\""
                        } else {
                            "move-object/from16 v3, $developerPayload"
                        }
                        val inappInstruction = if (signature.size == 6 && flowName == "launchPurchaseFlow") {
                            val itemType = parameterRegister(method, 2)
                            """
                            const-string v4, "subs"
                            move-object/from16 v5, $itemType
                            invoke-virtual {v5, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                            move-result v4
                            xor-int/lit8 v4, v4, 0x1
                            """.trimIndent()
                        } else {
                            "const/4 v4, ${if (flowName == "launchPurchaseFlow") "0x1" else "0x0"}"
                        }
                        val block = """
                            move-object/from16 v0, $listener
                            if-eqz v0, :morphe_openiab_original_purchase_flow
                            move-object/from16 v1, $sku
                            move-object/from16 v2, $purchaseActivity
                            $developerPayloadInstruction
                            $inappInstruction
                            const v5, $nonOverlayTimeout
                            const v6, $overlayTimeout
                            invoke-static {v5, v6}, Lunipatch/overlaycore/InAppRuntimePolicy;->configureTimeouts(II)V
                            invoke-static {v0, v2, v1, v3, v4}, Lunipatch/overlaycore/InAppRuntimePolicy;->routeLegacyPurchase(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Z)Z
                            move-result v5
                            if-eqz v5, :morphe_openiab_original_purchase_flow
                            return-void
                            :morphe_openiab_original_purchase_flow
                        """.trimIndent()
                        try {
                            method.addInstructions(0, block)
                        } catch (_: Exception) {
                            expandSwap(method, block)
                        }
                    }
                }
            }
        }

        // Unity IL2CPP native bridge (BillingClientImpl.launchBillingFlowCpp):
        // exact-name fingerprint above misses it, so cover by return type.
        patchAll(Fingerprint(name = "launchBillingFlowCpp", custom = { _, c -> isBillingNamespace(c.type) }), "launchBillingFlowCpp", 2) {
            val isStatic = try {
                com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags)
            } catch (_: Exception) { true }
            val field = if (!isStatic) listenerIget(it.definingClass) else null
            if (field != null && it.returnType.contains("BillingResult")) {
                val productArguments = it.parameterTypes.mapIndexedNotNull { index, type ->
                    if (type.startsWith("L") || type.startsWith("[")) parameterRegister(it, index) else null
                }.take(2)
                val block = buyGrantBlock(field, productArguments)
                var granted = false
                if (minRegs(it) >= 4) {
                    try { it.addInstructions(0, block); granted = true } catch (_: Exception) {}
                }
                if (!granted) {
                    try { granted = expandSwap(it, block) } catch (_: Exception) {}
                }
                if (granted) {
                    logger.info("FreeIAP buy-time grant: ${it.definingClass}->${it.name}")
                    return@patchAll
                }
                try {
                    it.addInstructions(0, okBillingResult)
                    return@patchAll
                } catch (_: Exception) {}
            }
            when {
                it.returnType.contains("BillingResult") -> try {
                    it.addInstructions(0, okBillingResult)
                } catch (_: Exception) {
                    try { it.addInstructions(0, "const/4 v0, 0x0\nreturn-object v0") } catch (_: Exception) {}
                }
                it.returnType == "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                it.returnType == "V" -> it.addInstructions(0, "return-void")
            }
        }

        patchAll(Fingerprint(name = "isReady", returnType = "Z", custom = { _, c -> c.type.contains("BillingClient") }), "BillingClient.isReady") {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }

        patchAll(Fingerprint(name = "endConnection", custom = { m, c -> m.returnType == "V" && c.type.contains("BillingClient") }), "BillingClient.endConnection") {
            it.addInstructions(0, "return-void")
        }

        // startConnection(BillingClientStateListener) -> fire
        // onBillingSetupFinished(OK) on the listener, then FALL THROUGH to
        // the real body (no return): the real connection still runs, so the
        // untouched product catalog below keeps working on devices with
        // Play, while no-Play devices boot on the early OK instead of
        // waiting for setup forever. Any other overload (e.g. the native
        // (J) bridge used by Unity IL2CPP games) is left completely
        // untouched: voiding it strands native setup with no callback and
        // freezes the app on its loading screen.
        patchAll(Fingerprint(name = "startConnection", custom = { m, c -> m.returnType == "V" && c.type.contains("BillingClient")         }), "BillingClient.startConnection", 2) {
            if (it.parameterTypes == listOf("Lcom/android/billingclient/api/BillingClientStateListener;") && it.returnType == "V") {
                val originalRegisters = it.implementation?.registerCount ?: return@patchAll
                // The callback is followed by the stock connection body, so
                // use cloned scratch registers instead of clobbering v0/v1.
                // Keep all 35c invoke registers below v16.
                if (originalRegisters > 13) return@patchAll
                val owner = mutableClassDefByOrNull(it.definingClass) ?: return@patchAll
                val allocation = it.cloneMutableAndAllocateScratchRegisters(owner, scratchRegisterCount = 3)
                val cloned = allocation.method
                val scratch = allocation.firstScratchRegister
                val listenerReg = parameterRegister(it, 0)
                val block = """
                    invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                    move-result-object v$scratch
                    const/4 v${scratch + 1}, 0x0
                    invoke-virtual {v$scratch, v${scratch + 1}}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                    move-result-object v$scratch
                    invoke-virtual {v$scratch}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
                    move-result-object v$scratch
                    move-object/from16 v${scratch + 1}, $listenerReg
                    if-eqz v${scratch + 1}, :morphe_iap_setup_done
                    invoke-interface {v${scratch + 1}, v$scratch}, Lcom/android/billingclient/api/BillingClientStateListener;->onBillingSetupFinished(Lcom/android/billingclient/api/BillingResult;)V
                    :morphe_iap_setup_done
                """.trimIndent()
                cloned.addInstructions(0, block)
            }
            // else: leave the overload alone (see comment above)
        }

        // onPurchasesUpdated is fired by the buy-time grant in
        // launchBillingFlow above and intentionally left intact elsewhere:
        // the game grants items in its own listener.

        // Cocos2d-x and similar wrappers often receive a successful billing
        // callback in app code rather than through BillingClient directly.
        // Patch only a class that both calls launchBillingFlow and contains a
        // narrowly matched String or String+boolean success method.
        val successMethodNames = listOf(
            "nativeOnSuccess", "onPurchaseSuccess", "onIAPSuccess",
            "onBillingSuccess", "onSuccess", "purchaseSuccess",
            "deliverItem", "unlockItem", "creditPurchase", "handlePurchase",
            "processPurchase", "giveItem", "addPurchase", "grantPurchase",
        )
        var cocosPatched = false
        // Build a small candidate index once. The broad compatibility phases
        // otherwise enumerate and inspect every class independently.
        val cocosCandidates = mutableListOf<ClassDef>()
        val nativeBridgeCandidates = mutableListOf<ClassDef>()
        val gameMakerCandidates = mutableListOf<ClassDef>()
        var indexedClassCount = 0
        classDefForEach { classDef ->
            indexedClassCount++
            val methods = classDef.methods.toList()
            if (classDef.type.startsWith("Lcom/android/billingclient/api/zz") &&
                methods.any { it.name == "nativeOnPurchasesUpdated" && it.implementation != null }) {
                nativeBridgeCandidates += classDef
            }
            if (!isFrameworkClass(classDef.type) && !isBillingNamespace(classDef.type) &&
                methods.any { method ->
                    method.name.equals("verifyPurchase", ignoreCase = true) &&
                        method.returnType == "Z" && method.implementation != null
                }) {
                gameMakerCandidates += classDef
            }
            if (!isFrameworkClass(classDef.type) && !isBillingNamespace(classDef.type) &&
                methods.any { method ->
                    method.returnType == "V" && method.parameterTypes.firstOrNull() == "Ljava/lang/String;" &&
                        method.implementation?.instructions?.any { instruction ->
                            instruction is ReferenceInstruction &&
                                instruction.reference is MethodReference &&
                                (instruction.reference as MethodReference).name == "launchBillingFlow"
                        } == true
                }) {
                cocosCandidates += classDef
            }
        }
        cocosCandidates.forEach classDefForEach@{ classDef ->
            if (cocosPatched) return@classDefForEach
            val className = classDef.type
            if (isFrameworkClass(className) || isBillingNamespace(className)) return@classDefForEach

            val wrapper = classDef.methods.firstOrNull { method ->
                method.returnType == "V" && method.parameterTypes.firstOrNull() == "Ljava/lang/String;" &&
                    method.implementation?.instructions?.any { instruction ->
                        instruction is ReferenceInstruction &&
                            instruction.reference is MethodReference &&
                            (instruction.reference as MethodReference).name == "launchBillingFlow"
                    } == true
            } ?: return@classDefForEach
            val success = classDef.methods.firstOrNull { method ->
                method.returnType == "V" && method.parameterTypes.size in 1..2 &&
                    method.parameterTypes.firstOrNull() == "Ljava/lang/String;" &&
                    (method.parameterTypes.size == 1 || method.parameterTypes[1] == "Z") &&
                    successMethodNames.any { name -> method.name.equals(name, ignoreCase = true) }
            } ?: return@classDefForEach

            val wrapperIsStatic = com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(wrapper.accessFlags)
            val successIsStatic = com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(success.accessFlags)
            if (wrapperIsStatic != successIsStatic) return@classDefForEach
            val originalRegisters = wrapper.implementation?.registerCount ?: return@classDefForEach
            // Two scratch registers are needed and invoke-35c registers must
            // remain below v16. The stock wrapper body must be preserved.
            if (originalRegisters > 13) return@classDefForEach

            try {
                val mutableClass = mutableClassDefBy(classDef)
                val mutableWrapper = mutableClass.methods.firstOrNull {
                    it.name == wrapper.name && it.parameterTypes == wrapper.parameterTypes && it.returnType == wrapper.returnType
                } ?: return@classDefForEach
                val target = mutableClass.methods.firstOrNull {
                    it.name == success.name && it.parameterTypes == success.parameterTypes && it.returnType == success.returnType
                } ?: return@classDefForEach
                val scratch = originalRegisters
                val productReg = parameterRegister(mutableWrapper, 0)
                val signature = success.parameterTypes.joinToString("")
                val args = if (success.parameterTypes.size == 1) {
                    "v$scratch"
                } else {
                    "v$scratch, v${scratch + 1}"
                }
                val receiver = when {
                    successIsStatic -> "invoke-static"
                    com.android.tools.smali.dexlib2.AccessFlags.PRIVATE.isSet(success.accessFlags) -> "invoke-direct"
                    else -> "invoke-virtual"
                }
                val booleanSetup = if (success.parameterTypes.size == 2) "const/4 v${scratch + 1}, 0x1\n" else ""
                val receiverPrefix = if (successIsStatic) "" else "p0, "
                val block = """
                    move-object/from16 v$scratch, $productReg
                    $booleanSetup$receiver {$receiverPrefix$args}, $className->${success.name}($signature)V
                """.trimIndent()
                val cloned = mutableWrapper.cloneMutable(additionalRegisters = 2)
                mutableClass.methods.remove(mutableWrapper)
                cloned.addInstructions(0, block)
                mutableClass.methods.add(cloned)
                cocosPatched = true
                patched++
                patchedMethods.add("Cocos2d-x.$className->${success.name}")
                logger.info("Emulate InApp: linked $className->${wrapper.name} to ${success.name}")
            } catch (e: Exception) {
                logger.warning("Emulate InApp: Cocos2d-x callback skipped: ${e.message}")
            }
        }

        // Unity and GameMaker IL2CPP builds can route BillingClient events
        // through obfuscated zz* bridge classes. Require the native methods
        // and exact callback signatures before touching a bridge.
        nativeBridgeCandidates.forEach classDefForEach@{ classDef ->
            val className = classDef.type
            if (!className.startsWith("Lcom/android/billingclient/api/zz")) return@classDefForEach
            if (classDef.methods.none { it.name == "nativeOnPurchasesUpdated" }) return@classDefForEach
            val mutableClass = try { mutableClassDefBy(classDef) } catch (_: Exception) { return@classDefForEach }

            val setup = mutableClass.methods.firstOrNull {
                !com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.name == "onBillingSetupFinished" && it.returnType == "V" &&
                    it.parameterTypes == listOf("Lcom/android/billingclient/api/BillingResult;")
            }
            val nativeSetup = mutableClass.methods.firstOrNull {
                com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.name == "nativeOnBillingSetupFinished" && it.returnType == "V" &&
                    it.parameterTypes == listOf("I", "Ljava/lang/String;", "J")
            }
            val nativeField = mutableClass.fields.firstOrNull { it.type == "J" }
            if (setup != null && nativeSetup != null && nativeField != null && setup.implementation != null) {
                val nativeSignature = nativeSetup.parameterTypes.joinToString("")
                val block = """
                    iget-wide v0, p0, $className->${nativeField.name}:J
                    const/4 v2, 0x0
                    const-string v3, ""
                    invoke-static {v2, v3, v0, v1}, $className->nativeOnBillingSetupFinished($nativeSignature)V
                    return-void
                """.trimIndent()
                val applied = try {
                    if (minRegs(setup) >= 4) {
                        setup.addInstructions(0, block)
                        true
                    } else {
                        expandSwap(setup, block)
                    }
                } catch (_: Exception) { false }
                if (applied) {
                    patched++
                    patchedMethods.add("$className.onBillingSetupFinished")
                    logger.info("Emulate InApp: patched $className.onBillingSetupFinished bridge")
                }
            }

            val purchases = mutableClass.methods.firstOrNull {
                !com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.name == "onPurchasesUpdated" && it.returnType == "V" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == "Lcom/android/billingclient/api/BillingResult;" &&
                    it.parameterTypes[1] == "Ljava/util/List;"
            } ?: return@classDefForEach
            val originalRegisters = purchases.implementation?.registerCount ?: return@classDefForEach
            if (originalRegisters > 14) return@classDefForEach
            val resultReg = parameterRegister(purchases, 0)
            val scratch = originalRegisters
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
                val target = mutableClass.methods.firstOrNull {
                    it.name == purchases.name && it.parameterTypes == purchases.parameterTypes && it.returnType == purchases.returnType
                } ?: return@classDefForEach
                val cloned = purchases.cloneMutable(additionalRegisters = 2)
                mutableClass.methods.remove(target)
                cloned.addInstructions(0, guard)
                mutableClass.methods.add(cloned)
                patched++
                patchedMethods.add("$className.onPurchasesUpdated")
                logger.info("Emulate InApp: guarded $className.onPurchasesUpdated bridge")
            } catch (e: Exception) {
                logger.warning("Emulate InApp: billing bridge skipped: ${e.message}")
            }
        }

        // Legacy getBuyIntent() must remain stock. Its BUY_INTENT value is a
        // PendingIntent, not an integer, and replacing it prevents old AIDL
        // clients from opening the billing flow.

        // isBillingSupported (AIDL) -> 0 = BILLING_RESPONSE_RESULT_OK
        patchAll(Fingerprint(name = "isBillingSupported", custom = { _, c -> isBillingNamespace(c.type) }), "isBillingSupported") {
            if (it.returnType == "I") it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
            else if (it.returnType == "Z") it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        patchAll(Fingerprint(name = "isBillingSupportedExtraParams", returnType = "I", custom = { _, c -> isBillingNamespace(c.type) }), "isBillingSupportedExtraParams") {
            it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        }

        val (fakeStartupPurchases, legacyInventoryMode, _) = inventoryOptionsProvider()
        val emulateInventory = fakeStartupPurchases || legacyInventoryMode != "preserve"

        // Preserve legacy getPurchases() and all catalog-related methods by
        // default. Empty or fake owned inventory is opt-in because old
        // OpenIAB-style wrappers commonly use this path before displaying
        // their store UI. Modern callback inventory can still be emulated
        // independently with fakeStartupPurchases.
        for (qn in listOf("getPurchases", "queryPurchases", "queryPurchasesAsync", "queryPurchaseHistory", "queryPurchaseHistoryAsync", "queryPurchasesHistory")) {
            if (!emulateInventory) continue
            patchAll(Fingerprint(name = qn, custom = { m, c -> c.type.contains("BillingClient") || m.definingClass.contains("billing") || isBillingNamespace(c.type) }), qn, 3) {
                // fakeStartupPurchases is intended for modern callback-based
                // inventory. Do not let it alter legacy Bundle APIs while
                // the dedicated legacy setting remains in preserve mode.
                if (it.returnType == "Landroid/os/Bundle;" && legacyInventoryMode == "preserve") return@patchAll
                val listenerIdx = it.parameterTypes.indexOfFirst { p -> p.contains("PurchasesResponseListener") || p.contains("PurchaseHistoryResponseListener") }
                if (listenerIdx >= 0 && it.returnType == "V") {
                    val isHistory = it.parameterTypes[listenerIdx].contains("History")
                    val listenerReg = parameterRegister(it, listenerIdx)
                    val iface = if (isHistory) "Lcom/android/billingclient/api/PurchaseHistoryResponseListener;" else "Lcom/android/billingclient/api/PurchasesResponseListener;"
                    val cb = if (isHistory) "onPurchaseHistoryResponse" else "onQueryPurchasesResponse"
                    if (!fakeStartupPurchases) {
                        it.addInstructions(0, """
                            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                            move-result-object v0
                            const/4 v1, 0x0
                            invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                            move-result-object v0
                            invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
                            move-result-object v0
                            invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                            move-result-object v1
                            move-object/from16 v2, $listenerReg
                            if-eqz v2, :morphe_iap_query_done
                            invoke-interface {v2, v0, v1}, $iface->$cb(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
                            :morphe_iap_query_done
                            return-void
                        """.trimIndent())
                    } else {
                    // v0..v3: expanded into a grown frame via expandSwap so
                    // tiny delegate frames (e.g. 3-reg BillingClientImpl
                    // methods) verify instead of killing their class.
                    val block = if (isHistory) {
                        """
                            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                            move-result-object v0
                            const/4 v1, 0x0
                            invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                            move-result-object v0
                            invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
                            move-result-object v0
                            const-string v1, "{\"productId\":\"morphe_fake\",\"purchaseToken\":\"morphe_fake\",\"purchaseTime\":0,\"quantity\":1}"
                            const-string v2, "morphe_fake"
                            new-instance v3, Lcom/android/billingclient/api/PurchaseHistoryRecord;
                            invoke-direct {v3, v1, v2}, Lcom/android/billingclient/api/PurchaseHistoryRecord;-><init>(Ljava/lang/String;Ljava/lang/String;)V
                            new-instance v1, Ljava/util/ArrayList;
                            invoke-direct {v1}, Ljava/util/ArrayList;-><init>()V
                            invoke-virtual {v1, v3}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            move-object/from16 v3, $listenerReg
                            if-eqz v3, :morphe_iap_query_done
                            invoke-interface {v3, v0, v1}, Lcom/android/billingclient/api/PurchaseHistoryResponseListener;->onPurchaseHistoryResponse(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
                            :morphe_iap_query_done
                            return-void
                        """.trimIndent()
                    } else {
                        """
                            invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                            move-result-object v0
                            const/4 v1, 0x0
                            invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                            move-result-object v0
                            const-string v1, "{\"orderId\":\"morphe_fake\",\"packageName\":\"morphe_fake\",\"productId\":\"morphe_fake\",\"purchaseTime\":0,\"purchaseState\":1,\"purchaseToken\":\"morphe_fake\",\"quantity\":1,\"acknowledged\":true}"
                            const-string v2, "morphe_fake"
                            new-instance v3, Lcom/android/billingclient/api/Purchase;
                            invoke-direct {v3, v1, v2}, Lcom/android/billingclient/api/Purchase;-><init>(Ljava/lang/String;Ljava/lang/String;)V
                            new-instance v1, Ljava/util/ArrayList;
                            invoke-direct {v1}, Ljava/util/ArrayList;-><init>()V
                            invoke-virtual {v1, v3}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            move-object/from16 v3, $listenerReg
                            if-eqz v3, :morphe_iap_query_done
                            invoke-interface {v3, v0, v1}, Lcom/android/billingclient/api/PurchasesResponseListener;->onQueryPurchasesResponse(Lcom/android/billingclient/api/BillingResult;Ljava/util/List;)V
                            :morphe_iap_query_done
                            return-void
                        """.trimIndent()
                    }
                    if (!expandSwap(it, block) && minRegs(it) >= 4) {
                        it.addInstructions(0, block)
                    }
                    }
                } else when {
                    it.returnType.contains("List") -> it.addInstructions(0, "invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;\nmove-result-object v0\nreturn-object v0")
                    it.returnType == "Landroid/os/Bundle;" -> it.addInstructions(0, """
                        new-instance v0, Landroid/os/Bundle;
                        invoke-direct {v0}, Landroid/os/Bundle;-><init>()V
                        const-string v1, "RESPONSE_CODE"
                        const/4 v2, 0x0
                        invoke-virtual {v0, v1, v2}, Landroid/os/Bundle;->putInt(Ljava/lang/String;I)V
                        const-string v1, "INAPP_PURCHASE_DATA_LIST"
                        new-instance v2, Ljava/util/ArrayList;
                        invoke-direct {v2}, Ljava/util/ArrayList;-><init>()V
                        ${if (legacyInventoryMode == "fake") "const-string v3, \"{\\\"productId\\\":\\\"morphe_fake\\\",\\\"purchaseToken\\\":\\\"morphe_fake\\\"}\"\n                        invoke-virtual {v2, v3}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z\n                        invoke-virtual {v0, v1, v2}, Landroid/os/Bundle;->putStringArrayList(Ljava/lang/String;Ljava/util/ArrayList;)V\n                        const-string v1, \"INAPP_SIGNATURE_LIST\"\n                        new-instance v2, Ljava/util/ArrayList;\n                        invoke-direct {v2}, Ljava/util/ArrayList;-><init>()V\n                        const-string v3, \"morphe_fake\"\n                        invoke-virtual {v2, v3}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z" else ""}
                        invoke-virtual {v0, v1, v2}, Landroid/os/Bundle;->putStringArrayList(Ljava/lang/String;Ljava/util/ArrayList;)V
                        return-object v0
                    """.trimIndent())
                    else -> try { it.addInstructions(0, safeReturn(it)) } catch (_: Exception) {}
                }
            }
        }

        // querySkuDetailsAsync / queryProductDetailsAsync and their sync
        // List variants are intentionally LEFT STOCK: faking an empty
        // catalog stalls Unity shop init on loading screens (native MOD
        // menus never touch the catalog either). The grant happens at buy
        // time via launchBillingFlow above.

        // Legacy getSkuDetails / getProductDetails AIDL methods are left
        // stock so their real product data reaches older app catalogs.

        // consumePurchase / consumeAsync -> fire listener callback with OK, else spoof return
        for (cn in listOf("consumePurchase", "consumeAsync", "consumePurchaseAsync")) {
            patchAll(Fingerprint(name = cn, custom = { _, c ->
                val t = c.type.lowercase()
            isBillingNamespace(c.type) || t.contains("purchase") || t.contains("iap")
            }), cn, 3) {
                val listenerIdx = it.parameterTypes.indexOfFirst { p -> p.contains("ConsumeResponseListener") }
                if (listenerIdx == 1 && it.parameterTypes.size == 2 && it.parameterTypes[0].contains("ConsumeParams") && it.returnType == "V") {
                    val listenerReg = parameterRegister(it, listenerIdx)
                    // params are (ConsumeParams, ConsumeResponseListener); token is first param -> p1
                    it.addInstructions(0, """
                        invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                        move-result-object v0
                        const/4 v1, 0x0
                        invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                        move-result-object v0
                        invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
                        move-result-object v0
                        move-object/from16 v1, ${parameterRegister(it, 0)}
                        invoke-virtual {v1}, Lcom/android/billingclient/api/ConsumeParams;->getPurchaseToken()Ljava/lang/String;
                        move-result-object v1
                        move-object/from16 v2, $listenerReg
                        if-eqz v2, :morphe_iap_consume_done
                        invoke-interface {v2, v0, v1}, Lcom/android/billingclient/api/ConsumeResponseListener;->onConsumeResponse(Lcom/android/billingclient/api/BillingResult;Ljava/lang/String;)V
                        :morphe_iap_consume_done
                        return-void
                    """.trimIndent())
                } else when {
                    it.returnType.contains("BillingResult") -> it.addInstructions(0, okBillingResult)
                    it.returnType == "Landroid/os/Bundle;" -> it.addInstructions(0, """
                        new-instance v0, Landroid/os/Bundle;
                        invoke-direct {v0}, Landroid/os/Bundle;-><init>()V
                        const-string v1, "RESPONSE_CODE"
                        const/4 v2, 0x0
                        invoke-virtual {v0, v1, v2}, Landroid/os/Bundle;->putInt(Ljava/lang/String;I)V
                        return-object v0
                    """.trimIndent())
                    it.returnType == "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                    it.returnType == "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                    else -> it.addInstructions(0, safeReturn(it))
                }
            }
        }

        patchAll(Fingerprint(name = "acknowledgePurchase", custom = { _, c ->
            val t = c.type.lowercase()
            isBillingNamespace(c.type) || t.contains("purchase")
        }), "acknowledgePurchase", 2) {
            val listenerIdx = it.parameterTypes.indexOfFirst { p -> p.contains("AcknowledgePurchaseResponseListener") }
            if (listenerIdx == 1 && it.parameterTypes.size == 2 && it.parameterTypes[0].contains("AcknowledgePurchaseParams") && it.returnType == "V") {
                val listenerReg = parameterRegister(it, listenerIdx)
                it.addInstructions(0, """
                    invoke-static {}, Lcom/android/billingclient/api/BillingResult;->newBuilder()Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                    move-result-object v0
                    const/4 v1, 0x0
                    invoke-virtual {v0, v1}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->setResponseCode(I)Lcom/android/billingclient/api/BillingResult${'$'}Builder;
                    move-result-object v0
                    invoke-virtual {v0}, Lcom/android/billingclient/api/BillingResult${'$'}Builder;->build()Lcom/android/billingclient/api/BillingResult;
                    move-result-object v0
                    move-object/from16 v1, $listenerReg
                    if-eqz v1, :morphe_iap_ack_done
                    invoke-interface {v1, v0}, Lcom/android/billingclient/api/AcknowledgePurchaseResponseListener;->onAcknowledgePurchaseResponse(Lcom/android/billingclient/api/BillingResult;)V
                    :morphe_iap_ack_done
                    return-void
                """.trimIndent())
            } else when {
                it.returnType.contains("BillingResult") -> it.addInstructions(0, okBillingResult)
                it.returnType == "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                else -> it.addInstructions(0, safeReturn(it))
            }
        }

        patchAll(Fingerprint(name = "getBillingConfig", custom = { _, c -> isBillingNamespace(c.type) }), "getBillingConfig", 2) {
            when {
                it.returnType.contains("BillingResult") -> it.addInstructions(0, okBillingResult)
                else -> it.addInstructions(0, safeReturn(it))
            }
        }

        // Prices -> "0.00" / 0
        for (pm in listOf("getPrice", "getOriginalPrice", "getFormattedPrice", "getDisplayPrice", "getPriceString")) {
            patchAll(Fingerprint(name = pm, returnType = "Ljava/lang/String;", custom = { _, c -> val t=c.type.lowercase(); t.contains("sku") || t.contains("product") || t.contains("billing") }), pm) {
                if (it.parameterTypes.isEmpty()) it.addInstructions(0, "const-string v0, \"0.00\"\nreturn-object v0")
            }
        }
        // OneTimePurchaseOfferDetails / SubscriptionOfferDetails micros
        patchAll(Fingerprint(name = "getPriceAmountMicros", returnType = "J", custom = { _, c ->
            val t = c.type.lowercase()
            t.contains("offer") || t.contains("product") || t.contains("sku") || t.contains("billing")
        }), "getPriceAmountMicros") {
            it.addInstructions(0, "const-wide/16 v0, 0x0\nreturn-wide v0")
        }
        patchAll(Fingerprint(name = "getPriceAmountMicros", custom = { _, c -> c.type.lowercase().contains("offer") }), "Offer.getPriceAmountMicros") {
            if (it.returnType == "J") it.addInstructions(0, "const-wide/16 v0, 0x0\nreturn-wide v0")
        }
        // getOriginalJson -> fake json
        patchAll(Fingerprint(name = "getOriginalJson", returnType = "Ljava/lang/String;", custom = { _, c ->
            val t = c.type.lowercase()
            t.contains("billing") || t.contains("purchase") || t.contains("sku") || t.contains("product")
        }), "getOriginalJson") {
            it.addInstructions(0, "const-string v0, \"{\\\"productId\\\":\\\"morphe_fake\\\",\\\"purchaseToken\\\":\\\"fake\\\"}\"\nreturn-object v0")
        }

        // Purchase state getters -> look owned/valid (scoped to billing/purchase classes only;
        // ProductDetails identity like getProductId is deliberately NOT spoofed so SKU lookup keeps working)
        patchAll(Fingerprint(name = "getPurchaseState", returnType = "I", custom = { _, c -> val t = c.type.lowercase(); t.contains("billing") || t.contains("purchase") }), "Purchase.getPurchaseState") {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        patchAll(Fingerprint(name = "isAcknowledged", returnType = "Z", custom = { _, c -> val t = c.type.lowercase(); t.contains("billing") || t.contains("purchase") }), "Purchase.isAcknowledged") {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        patchAll(Fingerprint(name = "getQuantity", returnType = "I", custom = { _, c -> val t = c.type.lowercase(); t.contains("billing") || t.contains("purchase") }), "Purchase.getQuantity") {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        for (ps in listOf("getPurchaseToken", "getOrderId", "getSignature")) {
            patchAll(Fingerprint(name = ps, returnType = "Ljava/lang/String;", custom = { _, c -> val t = c.type.lowercase(); t.contains("billing") || t.contains("purchase") }), "Purchase.$ps") {
                if (it.parameterTypes.isEmpty()) it.addInstructions(0, "const-string v0, \"morphe_fake\"\nreturn-object v0")
            }
        }
        patchAll(Fingerprint(name = "getProducts", custom = { m, c -> m.returnType.contains("List") && (c.type.lowercase().contains("billing") || c.type.lowercase().contains("purchase")) }), "Purchase.getProducts") {
            it.addInstructions(0, "const-string v0, \"morphe_fake\"\ninvoke-static {v0}, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;\nmove-result-object v0\nreturn-object v0")
        }
        patchAll(Fingerprint(name = "getSkus", custom = { m, c -> m.returnType.contains("List") && (c.type.lowercase().contains("billing") || c.type.lowercase().contains("purchase")) }), "Purchase.getSkus") {
            it.addInstructions(0, "const-string v0, \"morphe_fake\"\ninvoke-static {v0}, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;\nmove-result-object v0\nreturn-object v0")
        }

        patchAll(Fingerprint(name = "isFeatureSupported", custom = { _, c -> c.type.contains("BillingClient") }), "isFeatureSupported", 2) {
            when {
                it.returnType.contains("BillingResult") -> it.addInstructions(0, okBillingResult)
                it.returnType == "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                it.returnType == "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
            }
        }

        patchAll(Fingerprint(name = "getConnectionState", returnType = "I", custom = { _, c -> c.type.contains("BillingClient") }), "getConnectionState") {
            it.addInstructions(0, "const/4 v0, 0x2\nreturn v0")
        }

        // NOTE: onBillingSetupFinished is intentionally left intact: the game
        // learns billing is ready through its own listener, and startConnection
        // above already fires it with OK. Suppressing it breaks init.

        // ──────────────────────────────────────────────
        // UNITY IAP
        // ──────────────────────────────────────────────

        patchAll(Fingerprint(name = "ProcessPurchase", custom = { m, c ->
            m.returnType.contains("PurchaseProcessingResult") && c.type.lowercase().contains("purchase")
        }), "ProcessPurchase") {
            it.addInstructions(0, "sget-object v0, Lcom/unity/purchasing/PurchaseProcessingResult;->Complete:Lcom/unity/purchasing/PurchaseProcessingResult;\nreturn-object v0")
        }
        patchAll(Fingerprint(name = "OnPurchaseFailed", custom = { _, c -> c.type.lowercase().contains("purchase") || c.type.lowercase().contains("unity") }), "OnPurchaseFailed") { it.addInstructions(0, safeReturn(it)) }
        patchAll(Fingerprint(name = "OnSetupFailed", custom = { _, c -> c.type.lowercase().contains("purchase") || c.type.lowercase().contains("unity") }), "OnSetupFailed") { it.addInstructions(0, safeReturn(it)) }
        patchAll(Fingerprint(name = "OnPurchaseComplete", custom = { _, c -> c.type.lowercase().contains("purchase") || c.type.lowercase().contains("unity") }), "OnPurchaseComplete") { it.addInstructions(0, safeReturn(it)) }
        // CrossPlatformValidator
        patchAll(Fingerprint(name = "Validate", custom = { m, c -> m.returnType.contains("CrossPlatformValidator") || c.type.contains("CrossPlatformValidator") }), "CrossPlatformValidator.Validate") {
            // will be caught below anyway
        }
        for (rn in listOf("hasReceipt", "getHasReceipt")) {
            patchAll(Fingerprint(name = rn, returnType = "Z", custom = { _, c ->
                val t = c.type.lowercase()
                t.contains("receipt") || t.contains("purchase") || t.contains("billing") || t.contains("validator")
            }), rn) { it.addInstructions(0, "const/4 v0, 0x1\nreturn v0") }
        }

        // ──────────────────────────────────────────────
        // XSOLLA
        // ──────────────────────────────────────────────

        patchAll(Fingerprint(name = "launchBillingFlow", custom = { _, c -> c.type.lowercase().contains("xsolla") }), "Xsolla.launchBillingFlow") {
            val block = when {
                it.returnType.contains("BillingResult") -> okBillingResult
                it.returnType == "Z" -> "const/4 v0, 0x1\nreturn v0"
                it.returnType == "I" -> "const/4 v0, 0x0\nreturn v0"
                else -> safeReturn(it)
            }
            it.addInstructions(0, block)
        }
        for (xb in listOf("isAvailable", "isUserAvailable", "isPaymentAvailable", "isInventoryAvailable", "isStoreAvailable")) {
            patchAll(Fingerprint(name = xb, returnType = "Z", custom = { _, c -> c.type.lowercase().contains("xsolla") }), "Xsolla.$xb") {
                it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
            }
        }
        for (xg in listOf("getAmount", "getBalance", "getVirtualCurrencyBalance", "getInventory")) {
            patchAll(Fingerprint(name = xg, returnType = "I", custom = { _, c -> c.type.lowercase().contains("xsolla") }), "Xsolla.$xg") {
                it.addInstructions(0, "const v0, 0xf423f\nreturn v0")
            }
        }
        for (xs in listOf("openPayStation", "openPurchase", "createPayment", "validatePurchase", "checkOrder", "getPayStationUrl")) {
            patchAll(Fingerprint(name = xs, custom = { _, c -> c.type.lowercase().contains("xsolla") }), "Xsolla.$xs") {
                if (it.returnType == "V") it.addInstructions(0, "return-void")
                else if (it.returnType == "Z") it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                else if (it.returnType.contains("String")) it.addInstructions(0, "const-string v0, \"https://paystation.xsolla.com\"\nreturn-object v0")
            }
        }

        // ──────────────────────────────────────────────
        // AMAZON, HUAWEI, SAMSUNG
        // ──────────────────────────────────────────────

        // Amazon IAP (com.amazon.device.iap)
        for (am in listOf("purchase", "getUserData", "getProductData", "getPurchaseUpdates", "onProductDataResponse", "onPurchaseResponse", "onUserDataResponse")) {
            patchAll(Fingerprint(name = am, custom = { _, c -> c.type.lowercase().contains("amazon") || c.type.contains("amazon") }), "Amazon.$am") {
                when (it.returnType) {
                    "V" -> it.addInstructions(0, "return-void")
                    "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                    else -> if (it.returnType.contains("String")) it.addInstructions(0, "const-string v0, \"\"\nreturn-object v0") else it.addInstructions(0, safeReturn(it))
                }
            }
        }
        // Amazon PurchasingService specifically
        patchAll(Fingerprint(name = "getUserData", custom = { m, c -> m.returnType == "V" && (c.type.contains("PurchasingService") || c.type.contains("amazon")) }), "Amazon.PurchasingService.getUserData") {
            it.addInstructions(0, "return-void")
        }

        // Huawei IAP
        for (hw in listOf("isEnvReady", "obtainProductInfo", "createPurchaseIntent", "consumeOwnedPurchase", "obtainOwnedPurchases", "obtainOwnedPurchaseRecord", "isSandboxActivated")) {
            patchAll(Fingerprint(name = hw, custom = { _, c -> c.type.lowercase().contains("huawei") || c.type.contains("huawei") }), "Huawei.$hw") {
                when (it.returnType) {
                    "V" -> it.addInstructions(0, "return-void")
                    "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                    "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                    else -> it.addInstructions(0, safeReturn(it))
                }
            }
        }

        // Samsung Galaxy Store IAP
        for (sm in listOf("getProductsDetails", "startPayment", "getOwnedList", "consumePurchasedItems", "getProductDetails", "checkPurchasedItem")) {
            patchAll(Fingerprint(name = sm, custom = { _, c -> c.type.lowercase().contains("samsung") || c.type.contains("samsung") }), "Samsung.$sm") {
                when (it.returnType) {
                    "V" -> it.addInstructions(0, "return-void")
                    "Z" -> it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                    "I" -> it.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                    else -> it.addInstructions(0, safeReturn(it))
                }
            }
        }

        // ──────────────────────────────────────────────
        // RECEIPT / SIGNATURE VERIFICATION (scoped)
        // ──────────────────────────────────────────────

        // GameMaker commonly keeps purchase validation in an app-owned class
        // with no billing-related name. Scan only non-framework, non-billing
        // classes and require the exact boolean verifyPurchase signature.
        gameMakerCandidates.forEach classDefForEach@{ classDef ->
            val className = classDef.type
            if (isFrameworkClass(className) || isBillingNamespace(className)) return@classDefForEach
            val verify = classDef.methods.firstOrNull {
                it.name.equals("verifyPurchase", ignoreCase = true) && it.returnType == "Z" && it.implementation != null
            } ?: return@classDefForEach
            try {
                val mutableClass = mutableClassDefBy(classDef)
                val method = mutableClass.methods.firstOrNull {
                    it.name.equals("verifyPurchase", ignoreCase = true) && it.returnType == "Z"
                } ?: return@classDefForEach
                if (minRegs(method) < 1) return@classDefForEach
                method.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                patched++
                patchedMethods.add("GameMaker.$className->${method.name}")
                logger.info("Emulate InApp: patched $className->${method.name}")
            } catch (e: Exception) {
                logger.warning("Emulate InApp: verifyPurchase scan skipped: ${e.message}")
            }
        }

        for (vn in listOf("verifySignature", "isValidSignature", "validateReceipt", "verifyReceipt", "checkReceipt", "isReceiptValid", "validateSignature")) {
            patchAll(Fingerprint(name = vn, returnType = "Z", custom = { _, c -> val t=c.type.lowercase(); t.contains("billing") || t.contains("purchase") || t.contains("receipt") || t.contains("security") || t.contains("store") || t.contains("googleplay") || t.contains("xsolla") || t.contains("amazon") || t.contains("huawei") || t.contains("validator") }), vn) {
                it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
            }
        }
        // ultra-generic names scoped strictly
        for (vn in listOf("verify", "checkSignature", "isValid")) {
            patchAll(Fingerprint(name = vn, returnType = "Z", custom = { _, c -> val t=c.type.lowercase(); (t.contains("security") || t.contains("receipt") || t.contains("purchase") || t.contains("billing") || t.contains("validator")) && !t.contains("okhttp") && !t.contains("ssl") }), vn) {
                it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
            }
        }

        // Unity CrossPlatformValidator
        patchAll(Fingerprint(returnType = "Z", custom = { m, c -> c.type.contains("CrossPlatformValidator") || (c.type.contains("Validator") && m.name.lowercase().contains("valid")) }), "CrossPlatformValidator") {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }

        // ──────────────────────────────────────────────
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
        // REPORT
        // ──────────────────────────────────────────────

        if (patched > 0) {
            logger.info("Emulate InApp: patched $patched check(s)")
            logger.info("Patched methods: ${patchedMethods.sorted().joinToString(", ")}")
        } else {
            logger.warning("No billing/purchase checks found. No changes applied.")
        }
        logger.info("Emulate InApp managed phase completed in ${(System.nanoTime() - managedPhaseStart) / 1_000_000} ms; classes indexed=$indexedClassCount; candidates=cocos=${cocosCandidates.size}, native=${nativeBridgeCandidates.size}, gameMaker=${gameMakerCandidates.size}")
    }
}
