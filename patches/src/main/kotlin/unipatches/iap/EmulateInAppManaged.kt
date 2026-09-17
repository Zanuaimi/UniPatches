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
internal fun emulateInAppManagedPatch(optionsProvider: () -> InAppPatchOptions) = bytecodePatch(
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
        val automaticTargets = mutableSetOf<String>()
        val automaticClassCounts = mutableMapOf<String, Int>()
        val automaticClassBudget = 32
        val options = optionsProvider()
        val nonOverlayTimeout = options.timeouts.first.coerceIn(1, 86400)
        val overlayTimeout = options.timeouts.second.coerceIn(1, 86400)
        val hasBillingV9Api = mutableClassDefByOrNull("Lcom/android/billingclient/api/ProductDetails;") != null

        fun strategyEnabled(label: String): Boolean = options.strategyEnabled(label, hasBillingV9Api)

        fun automaticCandidate(label: String, method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod): String? {
            if (!options.automaticMode) return null
            val labelText = label.lowercase()
            val classText = method.definingClass.lowercase()
            val explicitLabel = listOf(
                "billingclient.", "openiab.", "unityplugin.", "unity.", "cocos2d.",
                "gamemaker.", "rc.", "revenuecat.", "amazon.", "huawei.", "samsung.", "xsolla.",
                "crossplatformvalidator",
            ).any(labelText::startsWith)
            val knownNamespace = listOf(
                "com/android/billingclient/", "org/onepf/", "com/unity/", "com/revenuecat/",
                "com/amazon/", "com/huawei/", "com/samsung/", "xsolla",
            ).any(classText::contains)
            if (explicitLabel) return null
            if (!knownNamespace) return "missing billing/vendor namespace"

            val methodName = method.name.lowercase()
            val isOpenIabSkuGetter = classText == "lorg/onepf/oms/appstore/googleutils/skudetails;" &&
                methodName in setOf("getprice", "getoriginalprice", "getformattedprice", "getsku", "gettype") &&
                method.parameterTypes.isEmpty()
            if (isOpenIabSkuGetter) return null
            val parameterText = method.parameterTypes.joinToString(" ").lowercase()
            val indicators = listOf(
                classText.contains("billing") || classText.contains("purchase") || classText.contains("receipt"),
                methodName.contains("purchase") || methodName.contains("billing") || methodName.contains("receipt") || methodName.contains("price"),
                parameterText.contains("purchase") || parameterText.contains("receipt") || parameterText.contains("sku") || parameterText.contains("product"),
                method.returnType.contains("BillingResult") || method.returnType.contains("Purchase") || method.returnType.contains("Sku"),
            ).count { it }
            if (indicators < 2) return "insufficient billing indicators"
            return null
        }

        fun automaticKey(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod): String =
            "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString(",")})${method.returnType}"

        fun reserveAutomaticTarget(label: String, method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod): String? {
            val reason = automaticCandidate(label, method) ?: return null
            logger.info("FreeIAP skipped automatic candidate: reason=$reason target=${method.definingClass}->${method.name} label=$label")
            return reason
        }

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
            if (!strategyEnabled(label)) {
                logger.info("FreeIAP skipped disabled coverage strategy: $label")
                return
            }
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
                            if (reserveAutomaticTarget(label, method) != null) {
                                continue
                            }
                            val targetKey = automaticKey(method)
                            if (options.automaticMode && !automaticTargets.add(targetKey)) {
                                logger.info("FreeIAP skipped duplicate automatic target: $targetKey label=$label")
                                continue
                            }
                            if (options.automaticMode) {
                                val count = automaticClassCounts.getOrDefault(method.definingClass, 0)
                                if (count >= automaticClassBudget) {
                                    logger.info("FreeIAP skipped automatic candidate: reason=class budget target=$targetKey label=$label")
                                    continue
                                }
                                automaticClassCounts[method.definingClass] = count + 1
                            }
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
                    if (reserveAutomaticTarget(label, single) != null) {
                        return
                    }
                    val targetKey = automaticKey(single)
                    if (options.automaticMode && !automaticTargets.add(targetKey)) {
                        logger.info("FreeIAP skipped duplicate automatic target: $targetKey label=$label")
                        return
                    }
                    if (options.automaticMode) {
                        val count = automaticClassCounts.getOrDefault(single.definingClass, 0)
                        if (count >= automaticClassBudget) {
                            logger.info("FreeIAP skipped automatic candidate: reason=class budget target=$targetKey label=$label")
                            return
                        }
                        automaticClassCounts[single.definingClass] = count + 1
                    }
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
        applyBillingClientPatches(InAppManagedAdapterContext(
            patchAll = { fingerprint, label, needRegs, injector -> patchAll(fingerprint, label, needRegs, injector) },
            safeReturn = ::safeReturn,
            okBillingResult = okBillingResult,
            nonOverlayTimeout = nonOverlayTimeout,
            overlayTimeout = overlayTimeout,
            minRegs = ::minRegs,
            expandSwap = ::expandSwap,
            parameterRegister = ::parameterRegister,
            listenerIget = { className -> this@execute.resolveBillingListener(className) },
            buyGrantBlock = { listener, arguments ->
                buildBillingPurchaseBlock(listener, arguments, nonOverlayTimeout, overlayTimeout, cancelledBillingResult)
            },
            isBillingNamespace = ::isBillingNamespace,
            billingCapabilities = BillingClientCapabilities(
                billingClientPresent = true,
                productDetails = hasBillingV9Api,
                skuDetails = !hasBillingV9Api,
                offerTokens = hasBillingV9Api,
                legacyLaunchFlow = !hasBillingV9Api,
            ),
        ))

        applyOpenIabPatches(InAppManagedAdapterContext(
            patchAll = { fingerprint, label, needRegs, injector -> patchAll(fingerprint, label, needRegs, injector) },
            safeReturn = ::safeReturn,
            okBillingResult = okBillingResult,
            nonOverlayTimeout = nonOverlayTimeout,
            overlayTimeout = overlayTimeout,
            minRegs = ::minRegs,
            expandSwap = ::expandSwap,
            parameterRegister = ::parameterRegister,
        ))

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
                // The callback is followed by the stock connection body, so
                // use cloned scratch registers instead of clobbering v0/v1.
                try {
                    val owner = mutableClassDefByOrNull(it.definingClass) ?: return@patchAll
                    val allocation = it.cloneMutableAndAllocateScratchRegisters(owner, scratchRegisterCount = 4)
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
                    move-object/from16 v${scratch + 2}, v$scratch
                    move-object/from16 v${scratch + 1}, $listenerReg
                    if-eqz v${scratch + 1}, :morphe_iap_setup_done
                    invoke-interface/range {v${scratch + 1} .. v${scratch + 2}}, Lcom/android/billingclient/api/BillingClientStateListener;->onBillingSetupFinished(Lcom/android/billingclient/api/BillingResult;)V
                    :morphe_iap_setup_done
                """.trimIndent()
                    cloned.addInstructions(0, block)
                    logger.info("FreeIAP startConnection expanded frame: ${it.definingClass}->${it.name} regs=${cloned.implementation?.registerCount}")
                } catch (error: Exception) {
                    logger.warning("FreeIAP skipped startConnection: register-safe allocation failed (${error.message})")
                }
            }
            // else: leave the overload alone (see comment above)
        }

        // onPurchasesUpdated is fired by the buy-time grant in
        // launchBillingFlow above and intentionally left intact elsewhere:
        // the game grants items in its own listener.

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
        if (strategyEnabled("Cocos2d-x")) {
            val cocosLabels = this@execute.applyCocos2dPatches(cocosCandidates, ::parameterRegister, ::isFrameworkClass, ::isBillingNamespace, logger)
            patched += cocosLabels.size
            patchedMethods.addAll(cocosLabels)
        }

        // Unity and GameMaker IL2CPP builds can route BillingClient events
        // through obfuscated zz* bridge classes. Require the native methods
        // and exact callback signatures before touching a bridge.
        if (strategyEnabled("Unity IL2CPP billing bridge")) {
            val bridgeLabels = this@execute.applyIl2CppBillingPatches(nativeBridgeCandidates, ::parameterRegister, ::minRegs, ::expandSwap, logger)
            patched += bridgeLabels.size
            patchedMethods.addAll(bridgeLabels)
        }

        applyBillingClientLifecycleAndInventoryPatches(
            context = InAppManagedAdapterContext(
                patchAll = { fingerprint, label, needRegs, injector -> patchAll(fingerprint, label, needRegs, injector) },
                safeReturn = ::safeReturn,
                okBillingResult = okBillingResult,
                parameterRegister = ::parameterRegister,
            ),
            fakeStartupPurchases = options.fakeStartupPurchases,
            legacyInventoryMode = options.legacyInventoryMode,
            isBillingNamespace = ::isBillingNamespace,
        )


        // ──────────────────────────────────────────────

        applyUnityIapPatches(InAppManagedAdapterContext(
            patchAll = { fingerprint, label, needRegs, injector -> patchAll(fingerprint, label, needRegs, injector) },
            safeReturn = ::safeReturn,
            okBillingResult = okBillingResult,
        ))

        applyAlternativeStorePatches(InAppManagedAdapterContext(
            patchAll = { fingerprint, label, needRegs, injector -> patchAll(fingerprint, label, needRegs, injector) },
            safeReturn = ::safeReturn,
            okBillingResult = okBillingResult,
        ))

        // ──────────────────────────────────────────────
        // RECEIPT / SIGNATURE VERIFICATION (scoped)
        // ──────────────────────────────────────────────

        // GameMaker commonly keeps purchase validation in an app-owned class
        // with no billing-related name. Scan only non-framework, non-billing
        // classes and require the exact boolean verifyPurchase signature.
        if (strategyEnabled("GameMaker")) {
            val gameMakerLabels = applyGameMakerPatches(gameMakerCandidates, ::minRegs, logger)
            patched += gameMakerLabels.size
            patchedMethods.addAll(gameMakerLabels)
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
        val revenueCatResult = this@execute.applyRevenueCatPatches(
            context = InAppManagedAdapterContext(
                patchAll = { fingerprint, label, needRegs, injector -> patchAll(fingerprint, label, needRegs, injector) },
                safeReturn = ::safeReturn,
                okBillingResult = okBillingResult,
                parameterRegister = ::parameterRegister,
            ),
            logger = logger,
        )
        patched += revenueCatResult.first
        patchedMethods.addAll(revenueCatResult.second)


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
