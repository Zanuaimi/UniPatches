package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import helpers.bytecode.cloneMutableAndAllocateScratchRegisters
import helpers.bytecode.cloneMutableForInjectedBlock
import helpers.bytecode.fitsBelowParameters
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
    // Every injected billing block resolves against unipatch.overlaycore.*. The extension DEX
    // must merge into the target before this patch runs, or the first patched call throws
    // NoClassDefFoundError and the host aborts on the pending exception.
    extendWith("extensions/extension.mpe")
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
                methodName.contains("purchase") || methodName.contains("billing") || methodName.contains("receipt") || methodName.contains("price") ||
                    // consumeAsync returns void, so its evidence has to come from the
                    // name: the params type and the class alone never reach two.
                    methodName.contains("consume"),
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

        // Registers the frame actually holds. Injected blocks write fixed low
        // regs (v0..vN), and writing past the frame fails verification for the
        // whole class (frozen loading screens), so every injection below is
        // gated on the frame really having that many. Parameter slots are only
        // a LOWER bound on registerCount, so gating on them skipped frames that
        // do hold the register - a static no-arg ()Z verifier used to be dropped
        // here even though its v0 is perfectly valid.
        fun frameRegisters(m: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod): Int {
            return try { m.implementation?.registerCount ?: 0 } catch (_: Exception) { 0 }
        }
        // Frame expansion for injections needing more regs than the frame
        // holds: clone with a window sized for the block itself and swap the
        // clone in. cloneMutableForInjectedBlock keeps the clone's parameter
        // region strictly above every register the block writes, so the
        // parameter-copy prologue it adds always runs against untouched pN
        // values and the original body sees intact arguments on fall-through.
        fun expandSwap(m: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, block: String): Boolean {
            val owner = try {
                mutableClassDefByOrNull(m.definingClass)
            } catch (e: Exception) {
                logger.warning("FreeIAP expand failed: cannot resolve owner ${m.definingClass} error=$e")
                return false
            }
            if (owner == null) {
                logger.warning("FreeIAP expand failed: owner missing ${m.definingClass}->${m.name}")
                return false
            }
            val target = owner.methods.firstOrNull {
                it.name == m.name && it.parameterTypes == m.parameterTypes && it.returnType == m.returnType
            }
            if (target == null) {
                logger.warning("FreeIAP expand failed: target missing ${m.definingClass}->${m.name}(${m.parameterTypes.joinToString(",")})")
                return false
            }
            return try {
                val cloned = m.cloneMutableForInjectedBlock(block)
                // Inject before the swap: a smali parse failure has to leave the
                // original method in the class, not a class missing its target.
                cloned.addInstructions(0, block)
                owner.methods.remove(target)
                owner.methods.add(cloned)
                logger.info("FreeIAP expanded frame: ${m.definingClass}->${m.name}")
                true
            } catch (e: Exception) {
                logger.warning("FreeIAP expand failed: clone/inject threw for ${m.definingClass}->${m.name} error=$e")
                false
            }
        }

        // expandSwap swaps a REPLACEMENT method into the class, so the object the
        // fingerprint matched is stale afterwards. Re-resolve it to report what
        // the class really holds, otherwise a failed injection is counted as a
        // success and the strategy looks like it landed when it did not.
        fun liveInstructionCount(m: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod): Int {
            val owner = mutableClassDefByOrNull(m.definingClass) ?: return -1
            val target = owner.methods.firstOrNull {
                it.name == m.name && it.parameterTypes == m.parameterTypes && it.returnType == m.returnType
            } ?: return -1
            return try { target.implementation?.instructions?.count() ?: -1 } catch (_: Exception) { -1 }
        }

        // A clone helper can swap its replacement into the class before the
        // injected block is parsed; when that parse then throws, the target is
        // half-patched or gone outright (a missing launchBillingFlow is a
        // NoSuchMethodError, not a fallback). Put the untouched original back.
        fun restoreOriginalMethod(m: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
            try {
                val owner = mutableClassDefByOrNull(m.definingClass) ?: return
                fun same(candidate: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) =
                    candidate.name == m.name && candidate.parameterTypes == m.parameterTypes && candidate.returnType == m.returnType
                owner.methods.filter(::same).forEach { candidate -> if (candidate !== m) owner.methods.remove(candidate) }
                if (owner.methods.none(::same)) owner.methods.add(m)
            } catch (error: Exception) {
                logger.warning("FreeIAP could not restore ${m.definingClass}->${m.name}: ${error.message}")
            }
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
                            if (frameRegisters(method) < needRegs) {
                                logger.info("FreeIAP skipped tiny frame: ${method.definingClass}->${method.name} regs=${frameRegisters(method)} need=$needRegs label=$label")
                                continue
                            }
                            val beforeCount = try { method.implementation?.instructions?.count() ?: -1 } catch (_: Exception) { -1 }
                            try {
                                injector(method)
                            } catch (e: Exception) {
                                logger.warning("FreeIAP injector threw: ${method.definingClass}->${method.name} label=$label error=$e")
                                continue
                            }
                            val afterCount = liveInstructionCount(method)
                            if (afterCount == beforeCount) {
                                logger.warning("FreeIAP injection left target unchanged: ${method.definingClass}->${method.name} label=$label before=$beforeCount after=$afterCount")
                                continue
                            }
                            patched++
                            patchedMethods.add(label)
                        } catch (e: Exception) {
                            logger.warning("FreeIAP target failed: label=$label error=$e")
                        }
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
                    if (frameRegisters(single) < needRegs) return
                    val beforeCount = try { single.implementation?.instructions?.count() ?: -1 } catch (_: Exception) { -1 }
                    try {
                        injector(single)
                    } catch (e: Exception) {
                        logger.warning("FreeIAP injector threw: ${single.definingClass}->${single.name} label=$label error=$e")
                        return
                    }
                    val afterCount = liveInstructionCount(single)
                    if (afterCount == beforeCount) {
                        logger.warning("FreeIAP injection left target unchanged: ${single.definingClass}->${single.name} label=$label before=$beforeCount after=$afterCount")
                        return
                    }
                    patched++
                    patchedMethods.add(label)
                } catch (e: Exception) {
                    logger.warning("FreeIAP target failed: label=$label error=$e")
                }
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

        // Latches the emulated connection for overloads whose callback block
        // could not be expanded. The block writes no registers, so it fits
        // every frame; without it a failed expansion leaves isReady false.
        fun latchConnectionReady(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
            try {
                method.addInstructions(0, "invoke-static {}, Lunipatch/overlaycore/InAppRuntimePolicy;->markConnectionReady()V")
            } catch (error: Exception) {
                logger.warning("FreeIAP skipped connection latch: ${method.definingClass}->${method.name} error=${error.message}")
            }
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

        // Registers read by an invoke: short register-list form and /range form.
        fun invokeRegisters(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction): List<Int> = when (instruction) {
            is FiveRegisterInstruction -> listOf(
                instruction.registerC, instruction.registerD, instruction.registerE,
                instruction.registerF, instruction.registerG,
            ).take(instruction.registerCount)
            is RegisterRangeInstruction ->
                (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
            else -> emptyList()
        }

        /**
         * Resolves the Map field a managed wrapper's catalog gate reads: the
         * IGET_OBJECT of type Ljava/util/Map; whose register is an argument of
         * the Ljava/util/Map;.get that follows it. Read off the gate's own
         * instructions so the injected iget writes a field the method really
         * owns instead of a name guessed from one obfuscated build.
         */
        fun managedCatalogField(method: Method): FieldReference? {
            val instructions = method.implementation?.instructions?.toList() ?: return null
            val mapOwners = HashMap<Int, FieldReference>()
            for (instruction in instructions) {
                if (instruction.opcode == Opcode.IGET_OBJECT && instruction is ReferenceInstruction) {
                    val reference = instruction.reference as? FieldReference ?: continue
                    if (reference.type != "Ljava/util/Map;") continue
                    val register = (instruction as? OneRegisterInstruction)?.registerA ?: continue
                    mapOwners[register] = reference
                    continue
                }
                if (instruction.opcode == Opcode.INVOKE_INTERFACE && instruction is ReferenceInstruction) {
                    val reference = instruction.reference as? MethodReference ?: continue
                    if (reference.definingClass != "Ljava/util/Map;" || reference.name != "get") continue
                    val owner = invokeRegisters(instruction).firstOrNull { mapOwners.containsKey(it) } ?: continue
                    return mapOwners.getValue(owner)
                }
            }
            return null
        }

        /**
         * Accepts only the catalog gate itself: an instance method taking the
         * SKU as its FIRST argument, reading a Map field, casting the lookup to
         * a billing product type and branching on the result, on a class that
         * owns a billing client.
         *
         * Trailing parameters must not disqualify the method. One wrapper
         * carries two gates over the same product map: the product gate takes
         * the SKU alone, while the subscription gate additionally takes a log
         * StringBuilder and a failure callback, and reads the map with the SKU
         * register. Requiring exact arity left that gate unseeded and every
         * subscription purchase still died at "<sku>: not available". The map
         * read, the cast and the branch keep the match tight enough that a
         * logging helper cannot qualify.
         */
        fun isManagedCatalogGate(method: Method, clazz: ClassDef): Boolean {
            if (AccessFlags.STATIC.isSet(method.accessFlags)) return false
            if (method.returnType != "V") return false
            if (method.parameterTypes.firstOrNull() != "Ljava/lang/String;") return false
            if (managedCatalogField(method) == null) return false
            val billingShaped = clazz.fields.any { it.type.contains("billingclient") } ||
                clazz.interfaces.any { it.contains("billingclient") } ||
                clazz.methods.any { owned -> owned.returnType.contains("BillingResult") }
            if (!billingShaped) return false
            val instructions = method.implementation?.instructions?.toList() ?: return false
            for ((index, instruction) in instructions.withIndex()) {
                if (instruction.opcode != Opcode.CHECK_CAST) continue
                val castRegister = (instruction as? OneRegisterInstruction)?.registerA ?: continue
                for (offset in (index + 1) until minOf(index + 4, instructions.size)) {
                    val branch = instructions[offset]
                    if (branch.opcode != Opcode.IF_NEZ && branch.opcode != Opcode.IF_EQZ) continue
                    if ((branch as? OneRegisterInstruction)?.registerA == castRegister) return true
                }
            }
            return false
        }

        /**
         * Recognises the wrapper's purchase signature gate: a private
         * (Purchase)Z that reads the purchase JSON and signature and delegates
         * to a static verifier, on a class that owns a billing client.
         *
         * The static delegation is the part that makes this safe. The same
         * class also holds a purchase-state check that decides whether the
         * purchase is complete, and that one must keep running: forcing it true
         * would grant items for a purchase that never finished. Requiring a
         * static call out of a method that only reads those two getters cannot
         * match it.
         */
        fun isPurchaseSignatureGate(method: Method, clazz: ClassDef): Boolean {
            if (method.returnType != "Z") return false
            if (method.parameterTypes != listOf("Lcom/android/billingclient/api/Purchase;")) return false
            val billingShaped = clazz.fields.any { it.type.contains("billingclient") } ||
                clazz.interfaces.any { it.contains("billingclient") } ||
                clazz.methods.any { owned -> owned.returnType.contains("BillingResult") }
            if (!billingShaped) return false
            val instructions = method.implementation?.instructions?.toList() ?: return false
            var readsJson = false
            var readsSignature = false
            var delegatesToStatic = false
            for (instruction in instructions) {
                if (instruction is ReferenceInstruction && instruction.reference is MethodReference) {
                    val reference = instruction.reference as MethodReference
                    when (reference.name) {
                        "getOriginalJson" -> readsJson = true
                        "getSignature" -> readsSignature = true
                    }
                    if (instruction.opcode == Opcode.INVOKE_STATIC &&
                        (reference.returnType == "Z" || reference.returnType == "Ljava/lang/Boolean;")) {
                        delegatesToStatic = true
                    }
                }
            }
            return readsJson && readsSignature && delegatesToStatic
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
            invoke-static {v0, v1}, Lunipatch/overlaycore/InAppRuntimePolicy;->stampResponseCode(Ljava/lang/Object;I)V
            return-object v0
        """.trimIndent()
        // v1 still holds 1 above, so the stamp records the cancel code. Without
        // it the patched getResponseCode would report this cancel as OK and the
        // game would wait on a callback that never arrives. Keep comments out of
        // the smali strings: addInstructions rejects them and drops the method.

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
            expandSwap = ::expandSwap,
            parameterRegister = ::parameterRegister,
        ))

        // startConnection(BillingClientStateListener) -> fire
        // onBillingSetupFinished(OK) on the listener, then FALL THROUGH to
        // the real body (no return): the real connection still runs, so the
        // untouched product catalog below keeps working on devices with
        // Play, while no-Play devices boot on the early OK instead of
        // waiting for setup forever. Every overload also latches the emulated
        // connection first (markConnectionReady), because isReady and
        // getConnectionState report DISCONNECTED until startConnection runs.
        // A non-listener overload (e.g. the native (J) bridge used by Unity
        // IL2CPP games) only gets that latch: voiding it would strand native
        // setup with no callback and freeze the app on its loading screen.
        patchAll(Fingerprint(name = "startConnection", custom = { m, c -> m.returnType == "V" && isBillingNamespace(c.type) }), "BillingClient.startConnection", 2) {
            if (it.parameterTypes == listOf("Lcom/android/billingclient/api/BillingClientStateListener;") && it.returnType == "V") {
                // The callback is followed by the stock connection body, so
                // use cloned scratch registers instead of clobbering v0/v1.
                try {
                    val owner = mutableClassDefByOrNull(it.definingClass) ?: return@patchAll
                    val allocation = it.cloneMutableAndAllocateScratchRegisters(owner, scratchRegisterCount = 4)
                    val cloned = allocation.method
                    val scratch = allocation.firstScratchRegister
                    val listenerReg = parameterRegister(it, 0)
                    // markConnectionReady leads so a game reading isReady from
                    // inside the setup callback this block fires sees the
                    // connection as up. Keep comments out of the smali: the
                    // parser rejects them and the whole block is dropped.
                    val block = """
                    invoke-static {}, Lunipatch/overlaycore/InAppRuntimePolicy;->markConnectionReady()V
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
                    // The scratch-clone helper already swapped its prologue-only
                    // clone in; restore the original, then latch, so the game
                    // gets a usable (if callback-less) startConnection instead of
                    // a half-patched one and isReady is not stuck false.
                    restoreOriginalMethod(it)
                    latchConnectionReady(it)
                }
            } else {
                // Overloads without a Java listener (Unity IL2CPP's native (J)
                // bridge) still connect, so they must latch too. The block
                // writes no registers, which is why it is safe to prepend here.
                latchConnectionReady(it)
            }
        }

        // ──────────────────────────────────────────────
        // MANAGED WRAPPER PRODUCT CATALOG
        // ──────────────────────────────────────────────

        // An obfuscated IAP wrapper (GoogleIapManager and its forks) keeps a
        // private product map that only a native-driven requestProductsData
        // ever fills, and that call only runs after the engine's own
        // onSetupFinished. On most cold runs the engine never gets there, so
        // purchase() dies at the map lookup with "<sku>: not available" before
        // the wrapper's connection check even runs. Seed the map the gate
        // reads and latch the emulated connection ahead of the lookup. Both
        // calls stay on the Java side on purpose: forcing Java init() walks
        // startConnection -> onBillingSetupFinished -> Q0 into the engine's
        // unguarded [this+0x18] listener dereference and takes the process
        // down with it.
        for ((productType, seedMethod) in listOf(
            "Lcom/android/billingclient/api/ProductDetails;" to "seedManagedProduct",
            "Lcom/android/billingclient/api/SkuDetails;" to "seedManagedSku",
        )) {
            patchAll(
                Fingerprint(
                    // No name: Fingerprint.name is the method name to match,
                    // not a label. The label lives on patchAll below.
                    // No parameters either: morphe's parametersMatch rejects any
                    // arity mismatch outright, so declaring the SKU here would
                    // exclude gates that take extra context after it. Arity and
                    // the leading String are enforced in isManagedCatalogGate.
                    returnType = "V",
                    filters = listOf(
                        fieldAccess(type = "Ljava/util/Map;", opcode = Opcode.IGET_OBJECT),
                        methodCall(
                            definingClass = "Ljava/util/Map;",
                            name = "get",
                            parameters = listOf("Ljava/lang/Object;"),
                            returnType = "Ljava/lang/Object;",
                            opcode = Opcode.INVOKE_INTERFACE,
                        ),
                        checkCast(type = productType),
                    ),
                    custom = { method, clazz -> isManagedCatalogGate(method, clazz) },
                ),
                "BillingClient.$seedMethod",
                1,
            ) {
                val catalog = managedCatalogField(it) ?: return@patchAll
                val product = parameterRegister(it, 0)
                // The latch leads so a wrapper reading isReady from inside the
                // call path this gate feeds still sees the connection up.
                // FieldReference descriptors already carry their ';', so the
                // iget below is class + "->" + name + ":" + type. Keep every
                // comment outside the string: the smali parser rejects them.
                val block = """
                    invoke-static {}, Lunipatch/overlaycore/InAppRuntimePolicy;->markConnectionReady()V
                    iget-object v0, p0, ${catalog.definingClass}->${catalog.name}:${catalog.type}
                    invoke-static {v0, $product}, Lunipatch/overlaycore/InAppRuntimePolicy;->${seedMethod}(Ljava/lang/Object;Ljava/lang/String;)V
                """.trimIndent()
                var seeded = false
                if (it.fitsBelowParameters(block)) {
                    try {
                        it.addInstructions(0, block)
                        seeded = true
                    } catch (e: Exception) {
                        logger.warning("FreeIAP seed block rejected: ${it.definingClass}->${it.name} error=$e block=${block.replace('\n', '|')}")
                    }
                }
                if (!seeded) seeded = expandSwap(it, block)
                if (!seeded) {
                    logger.warning("FreeIAP seed block dropped: ${it.definingClass}->${it.name} block=${block.replace('\n', '|')}")
                    latchConnectionReady(it)
                }
            }
        }

        // onPurchasesUpdated is fired by the buy-time grant in
        // launchBillingFlow above and intentionally left intact elsewhere:
        // the game grants items in its own listener.

        // ──────────────────────────────────────────────
        // PURCHASE SIGNATURE GATE
        // ──────────────────────────────────────────────

        // Having produced a well-formed Purchase, the wrapper still runs it
        // through its own verifier before granting: it reads getOriginalJson
        // plus getSignature and hands both to a static check, and a false
        // result becomes "<sku>: invalid signature" with nothing granted. The
        // emulated purchase carries a synthetic token that no store ever
        // signed, so the real check can only ever say no.
        //
        // The signature is matched structurally rather than by name, because
        // the method is obfuscated per build: a private (Purchase)Z on a
        // billing-shaped class whose body reads exactly those two getters and
        // delegates to a static verifier. Anything looser would also match the
        // purchase-state check that guards the same grant.
        patchAll(
            Fingerprint(
                returnType = "Z",
                parameters = listOf("Lcom/android/billingclient/api/Purchase;"),
                filters = listOf(
                    methodCall(
                        name = "getOriginalJson",
                        returnType = "Ljava/lang/String;",
                        opcode = Opcode.INVOKE_VIRTUAL,
                    ),
                    methodCall(
                        name = "getSignature",
                        returnType = "Ljava/lang/String;",
                        opcode = Opcode.INVOKE_VIRTUAL,
                    ),
                ),
                custom = { method, clazz -> isPurchaseSignatureGate(method, clazz) },
            ),
            "BillingClient.verifyEmulatedPurchase",
            1,
        ) {
            it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }

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
            val bridgeLabels = this@execute.applyIl2CppBillingPatches(nativeBridgeCandidates, ::parameterRegister, ::expandSwap, logger)
            patched += bridgeLabels.size
            patchedMethods.addAll(bridgeLabels)
        }

        applyBillingClientLifecycleAndInventoryPatches(
            context = InAppManagedAdapterContext(
                patchAll = { fingerprint, label, needRegs, injector -> patchAll(fingerprint, label, needRegs, injector) },
                safeReturn = ::safeReturn,
                okBillingResult = okBillingResult,
                // Not optional: without it every tight-frame inventory/consume
                // injection silently falls back to a no-op lambda and the target
                // is left stock while the patch reports success.
                expandSwap = ::expandSwap,
                parameterRegister = ::parameterRegister,
            ),
            fakeStartupPurchases = options.fakeStartupPurchases,
            legacyInventoryMode = options.legacyInventoryMode,
            isBillingNamespace = ::isBillingNamespace,
            // Patch-time gate for the query*Async listener wrap: only a
            // declared interface can be a java.lang.reflect.Proxy at runtime,
            // so a class-typed listener (Unity proxy bridges) keeps the stock
            // path instead of receiving a check-cast that can never verify.
            isInterfaceType = { type ->
                try {
                    classDefByOrNull(type)?.let { definition ->
                        com.android.tools.smali.dexlib2.AccessFlags.INTERFACE.isSet(definition.accessFlags)
                    } == true
                } catch (_: Exception) {
                    false
                }
            },
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
            val gameMakerLabels = applyGameMakerPatches(gameMakerCandidates, logger)
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
