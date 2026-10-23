package unipatches.iap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.Method
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

// Broad, name-driven premium state forcing. Independent of the InApp emulation patch:
// this one rewrites app-side getters and persisted flags, not the billing client.
//
// Deliberately excludes their React Native bridge and RevenueCat sections. Those
// overlap BillingWrapper/EntitlementInfo, which EmulateInAppRevenueCat also rewrites;
// running both would double-patch the same methods in undefined order.
@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock Premium",
    description = "Unlock premium features and remove paywalls by forcing app-side entitlement checks.",
    default = false,
) {
    category("Featured")
    val extraKeys by stringOption(
        title = "Extra keys",
        default = "",
        key = "premiumCustomKeys",
        description = "Comma-separated extra SharedPreferences/DataStore keys to spoof (e.g. my_premium,my_pro). Leave empty for default list.",
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        var patched = 0
        val patchedMethods = mutableSetOf<String>()
        val extraSet = (extraKeys ?: "").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        val premiumSubstrings = listOf(
            "purchased", "has_receipt", "hasreceipt", "bought",
            "premium", "is_premium", "ispremium", "premium_unlocked", "premium_status", "premium_expiry", "premiumaccess", "haspremiumaccess",
            "vip", "is_vip", "vip_status", "vip_level", "vip_expiry",
            "no_ads", "noads", "ads_removed", "adsremoved", "ad_free", "adfree", "remove_ads", "removeads",
            "full_version", "fullversion", "unlocked",
            "subscribed", "is_subscribed", "subscription_active", "subscription_expires", "has_subscription", "has_active_purchase",
            "lifetime", "is_lifetime", "annual", "monthly", "trial",
            "entitlement", "entitlements", "is_entitled", "has_entitlement",
            "paid", "is_paid", "member", "pro_version", "is_pro", "pro_member",
            "subscription_expiry", "premium_expiry", "key_subs", "key_sub", "subs",
        )

        // "ignore/disregard X" flags mean "disregard the premium state", so forcing them
        // true inverts the intent and locks the user out instead of unlocking.
        fun isPremiumKey(lower: String): Boolean {
            if (extraSet.any { it.isNotEmpty() && lower == it }) return true
            if (lower.contains("ignore") || lower.contains("disregard")) return false
            if (lower.contains("provider") || lower.contains("process") ||
                lower.contains("progress") || lower.contains("project") || lower.contains("proceed")
            ) return false
            for (k in premiumSubstrings) if (lower.contains(k)) return true
            if (lower == "pro" || lower == "vip") return true
            if (lower.contains("_pro_") || lower.endsWith("_pro") || lower.startsWith("pro_")) return true
            return false
        }

        // Framework / support-library prefixes are never app entitlement code.
        val FRAMEWORK_PREFIXES = listOf(
            "lcom/google/android/material/",
            "landroidx/",
            "landroid/",
            "lkotlin/",
            "lkotlinx/",
            "lcom/google/common/",
            "lcom/google/protobuf/",
            "lcom/google/gson/",
            "lcom/google/firebase/",
            "lcom/google/android/gms/",
            "lorg/jetbrains/",
        )

        // Payment SDK internals: state flags here describe a payment session, not an
        // entitlement. Forcing them true breaks the checkout flow it is meant to unlock.
        val PAYMENT_PREFIXES = listOf(
            "com/stripe/",
            "lcom/stripe/",
            "com/google/android/gms/wallet",
            "com/google/android/gms/internal/",
            "com/adyen/",
            "com/braintreepaymentsapi/",
            "com/paypal/",
            "com/squareup/",
        )

        // Substring matching on "ispro" catches isProjected(), isProductValid(), isProgress... .
        // Require the token to sit on a camelCase boundary: whole word, or an accessor prefix
        // followed by exactly "pro" (isPro, hasPro, isProUser) but never "projected".
        val PREMIUM_WORDS = setOf(
            "premium", "vip", "pro", "subscribed", "subscription", "entitlement", "entitled",
            "purchased", "purchase", "paid", "unlocked", "lifetime", "bought", "receipt", "member",
        )
        val ACCESSOR_PREFIXES = setOf("is", "has", "was", "get", "should", "can", "use", "needs")

        fun hasPremiumWord(name: String): Boolean {
            val words = name.split('_', '-', ' ').flatMap { part ->
                val out = mutableListOf<String>()
                var current = StringBuilder()
                for (ch in part) {
                    if (ch.isUpperCase() && current.isNotEmpty()) { out.add(current.toString()); current = StringBuilder() }
                    current.append(ch)
                }
                if (current.isNotEmpty()) out.add(current.toString())
                out
            }.map { it.lowercase() }
            for (w in words) if (PREMIUM_WORDS.contains(w)) return true
            // "isProUser" -> [is, pro, user] matches; "isProjected" -> [is, projected] does not.
            for (i in 1 until words.size) {
                if (words[i] == "pro" && words[i - 1] in ACCESSOR_PREFIXES) return true
            }
            return false
        }

        // Upstream calls mutableClassDefByOrNull(classDef.type).mutableMethodOf(method); this repo only
        // exposes the OrNull form and has no findMutableMethodOf, so match by signature instead.
        fun MutableClass.mutableMethodOf(method: Method): MutableMethod? = methods.firstOrNull {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }

        // Mirrors the InApp patch's own matcher so both report consistently, but this
        // patch owns no automatic-target budget: every match here is an intentional force.
        fun patchAll(fp: Fingerprint, label: String, injector: (app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) -> Unit) {
            try {
                val matches: List<app.morphe.patcher.Match> = try { with(this@execute) { fp.matchAll() } } catch (_: Exception) { emptyList() }
                if (matches.isNotEmpty()) {
                    for (m in matches) {
                        try {
                            val method = m.method
                            if (method.implementation == null) continue
                            injector(method)
                            patched++
                            patchedMethods.add(label)
                        } catch (_: Exception) {}
                    }
                    return
                }
            } catch (_: Exception) {}
            val single = try { fp.methodOrNull } catch (_: Exception) { null }
            if (single?.implementation != null) {
                try {
                    injector(single)
                    patched++
                    patchedMethods.add(label)
                } catch (_: Exception) {}
            }
        }

        // const/4 cannot encode every register; promote when the target is high.
        fun constTrue(mm: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, r: Int) {
            when {
                r <= 0xf -> mm.replaceInstruction(0, "const/4 v$r, 0x1")
                r <= 0xff -> mm.replaceInstruction(0, "const/16 v$r, 0x1")
                else -> {
                    mm.replaceInstruction(0, "const/4 v0, 0x1")
                    mm.replaceInstruction(0, "move v$r, v0")
                }
            }
        }

        for (checkName in listOf(
            "isPurchased", "isOwned", "isPremium", "hasPremium", "hasPremiumAccess", "isPremiumAccess",
            "isSubscribed", "hasSubscription", "isVip", "hasVip",
            "isBought", "hasBought", "wasPurchased", "hasPurchased",
            "isPro", "hasPro", "isProUser", "hasProUser", "isFullVersion", "hasFullVersion",
            "isUnlocked", "hasUnlocked", "isActive", "hasActive",
            "isLifetime", "hasLifetime", "isAnnual", "hasAnnual",
            "hasEntitlement", "isEntitled", "checkPremium", "verifyPremium",
            "isPremiumUser", "hasAdFree", "isPaidUser", "checkVip",
            "hasSubscriptionActive", "hasActivePurchase", "isProMember", "isVipUser", "hasPremiumAccessChanged",
            "hasProFeatures", "hasProAccess", "hasActiveSubscription",
        )) {
            val isGenericActive = checkName == "isActive" || checkName == "hasActive" ||
                checkName == "isPro" || checkName == "hasPro"
            patchAll(
                Fingerprint(
                    name = checkName,
                    returnType = "Z",
                    custom = if (isGenericActive) { _, c ->
                        val t = c.type.lowercase()
                        !t.contains("okhttp") && !t.contains("ssl") && !t.contains("network") &&
                            (t.contains("premium") || t.contains("purchase") || t.contains("billing") ||
                                t.contains("subscription") || t.contains("user") || t.contains("entitle") ||
                                t.contains("vip") || t.contains("pro"))
                    } else null,
                ), checkName,
            ) { it.addInstructions(0, "const/4 v0, 0x1\nreturn v0") }
        }

        for (negName in listOf("isExpired", "isCancelled", "isTrialExpired", "isLocked", "isPremiumLocked", "isContentLocked", "isHardPaywall")) {
            patchAll(Fingerprint(name = negName, returnType = "Z", custom = { _, c ->
                val t = c.type.lowercase()
                t.contains("premium") || t.contains("subscription") || t.contains("entitle") ||
                    t.contains("vip") || t.contains("billing") || t.contains("purchase") ||
                    t.contains("content") || t.contains("station") || t.contains("paywall")
            }), negName) { it.addInstructions(0, "const/4 v0, 0x0\nreturn v0") }
        }

        for (optName in listOf("getSupportsLifetimeSwitch", "getSupportsPromo", "getDoesOfferTrial")) {
            patchAll(Fingerprint(name = optName, returnType = "Z", custom = { _, c ->
                val t = c.type.lowercase()
                t.contains("paywall") || t.contains("billing") || t.contains("purchase") ||
                    t.contains("subscription") || t.contains("offer") || t.contains("product")
            }), optName) { it.addInstructions(0, "const/4 v0, 0x1\nreturn v0") }
        }

        for (intName in listOf("getPremiumState", "getVipLevel", "getSubscriptionStatus", "getProState", "getVipStatus", "getUserType", "getPremiumStatusInt", "getEntitlementState")) {
            patchAll(Fingerprint(name = intName, returnType = "I"), intName) {
                it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
            }
        }

        for (longName in listOf("getExpiryTime", "getExpireDate", "getSubscriptionExpiry", "getPremiumExpiry", "getVipExpiry", "getEntitlementExpiry")) {
            patchAll(Fingerprint(name = longName, returnType = "J", custom = { _, c ->
                val t = c.type.lowercase()
                t.contains("premium") || t.contains("subscription") || t.contains("entitle") ||
                    t.contains("vip") || t.contains("billing") || t.contains("purchase") || t.contains("pro")
            }), longName) { it.addInstructions(0, "const-wide v0, 0x17d2d0c0000L\nreturn-wide v0") }
        }

        for (listName in listOf("getEntitlements", "getActivePurchases", "getActiveEntitlements")) {
            patchAll(Fingerprint(name = listName, custom = { m, _ -> m.returnType.contains("List") || m.returnType.contains("Collection") }), listName) {
                it.addInstructions(
                    0,
                    "const-string v0, \"premium\"\n" +
                        "invoke-static {v0}, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;\n" +
                        "move-result-object v0\nreturn-object v0",
                )
            }
        }

        for (strName in listOf("getPremiumStatus", "getVipStatus", "getSubscriptionStatus", "getUserTypeString")) {
            patchAll(Fingerprint(name = strName, returnType = "Ljava/lang/String;"), strName) {
                it.addInstructions(0, "const-string v0, \"premium\"\nreturn-object v0")
            }
        }

        for (receiptName in listOf("hasReceipt", "getHasReceipt", "hasValidReceipt", "isReceiptValid", "hasActiveReceipt", "getReceipt")) {
            patchAll(Fingerprint(name = receiptName, returnType = "Z"), receiptName) {
                it.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
            }
        }
        for (receiptName in listOf("hasReceipt", "getHasReceipt", "getReceipt")) {
            patchAll(Fingerprint(name = receiptName, returnType = "Ljava/lang/String;"), "$receiptName:String") {
                it.addInstructions(0, "const-string v0, \"fake_receipt_data\"\nreturn-object v0")
            }
        }

        // Two-state status enums (PremiumStatus-style). Structural only: a strict
        // two-constant enum with an active/inactive name pair gets its no-arg getters
        // redirected to the active constant and getValue()I redirected to its int field.
        try {
            val activeNames = setOf("active", "enabled", "premium", "pro", "unlocked", "valid", "licensed", "subscribed", "entitled", "paid")
            val inactiveNames = setOf("not_active", "inactive", "notactive", "disabled", "free", "locked", "invalid", "unlicensed", "unsubscribed", "not_entitled", "expired")
            val activeConst = mutableMapOf<String, String>()
            val constIntField = mutableMapOf<String, String>()
            classDefForEach { classDef ->
                try {
                    if (classDef.superclass != "Ljava/lang/Enum;") return@classDefForEach
                    val consts = classDef.fields.filter { f ->
                        f.type == classDef.type && !f.name.startsWith("$") && f.accessFlags and 0x8 != 0
                    }
                    if (consts.size != 2) return@classDefForEach
                    val active = consts.firstOrNull { it.name.lowercase() in activeNames } ?: return@classDefForEach
                    if (consts.none { it.name.lowercase() in inactiveNames }) return@classDefForEach
                    activeConst[classDef.type] = active.name
                    outer@ for (m in classDef.methods) {
                        if (m.name != "getValue" || m.returnType != "I") continue
                        val impl = m.implementation ?: continue
                        for (insn in impl.instructions) {
                            if (insn.opcode != Opcode.IGET) continue
                            val ref = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                            if (ref.definingClass == classDef.type && ref.type == "I") {
                                constIntField[classDef.type] = ref.name
                                break@outer
                            }
                        }
                    }
                    logger.info("Unlock Premium: two-state status enum ${classDef.type} active=${active.name}")
                } catch (_: Exception) {}
            }
            if (activeConst.isNotEmpty()) {
                classDefForEach { classDef ->
                    val hasCandidate = classDef.methods.any { m ->
                        m.parameterTypes.isEmpty() && (activeConst.containsKey(m.returnType) ||
                            (m.name == "getValue" && m.returnType == "I" && activeConst.containsKey(classDef.type)))
                    }
                    if (!hasCandidate) return@classDefForEach
                    val mutableClass by lazy { try { mutableClassDefByOrNull(classDef.type) } catch (_: Exception) { null } }
                    for (method in classDef.methods) {
                        try {
                            if (method.implementation == null || method.parameterTypes.isNotEmpty()) continue
                            val ret = method.returnType
                            val activeField = activeConst[ret]
                            val mc = mutableClass ?: continue
                            val mutableMethod = mc.mutableMethodOf(method) ?: continue
                            if (activeField != null) {
                                if (method.name == "values" || method.name == "valueOf" || method.name == "getEntries") continue
                                (mutableMethod ?: continue).addInstructions(0, "sget-object v0, $ret->$activeField:$ret\nreturn-object v0")
                                patched++
                                patchedMethods.add("EnumStatus:${method.name}->$activeField")
                            } else if (method.name == "getValue" && ret == "I" && activeConst.containsKey(classDef.type)) {
                                val intField = constIntField[classDef.type] ?: continue
                                val enumType = classDef.type
                                val field = activeConst[enumType] ?: continue
                                mutableMethod.addInstructions(
                                    0,
                                    "sget-object v0, $enumType->$field:$enumType\n" +
                                        "iget v0, v0, $enumType->$intField:I\nreturn v0",
                                )
                                patched++
                                patchedMethods.add("EnumStatus:getValue->$field")
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            logger.warning("Unlock Premium: two-state enum strategy skipped: ${e.message}")
        }

        // Prefs/DataStore key scanning. Single pass: walks each method's instructions
        // looking for a premium-named key loaded into a prefs getter, then rewrites the
        // getter (or the matching put) rather than replacing whole methods.
        classDefForEach { classDef ->
            val typeLower = classDef.type.lowercase()
            // Framework and support-library packages are never the app's own entitlement
            // logic. isProjected() in FocusRingDrawable matched upstream's "isPro" substring
            // and got forced true, which is an animation flag, not a purchase gate.
            if (FRAMEWORK_PREFIXES.any { typeLower.startsWith(it) }) return@classDefForEach
            if (typeLower.contains("okhttp") || typeLower.contains("ssl") || typeLower.contains("network")) return@classDefForEach
            // Payment SDK internals. Stripe's namespace contains "payment", so a
            // premium-looking class forced component1(), equals(), isFilledOut() and
            // getCollectEmail() to true across 80 methods on Simply Guitar.
            if (PAYMENT_PREFIXES.any { typeLower.startsWith(it) || typeLower.contains(it) }) return@classDefForEach

            val mutableClass by lazy { try { mutableClassDefByOrNull(classDef.type) } catch (_: Exception) { null } }

            for (method in classDef.methods) {
                val n = method.name.lowercase()
                // The method name must look premium on its own. Gating on a
                // premium-looking CLASS alone forces every Z method in it, which is
                // how Stripe internals got swept up. App entitlement getters are
                // named for what they check; being in a PremiumManager is not enough.
                if (method.returnType == "Z" && n.length in 3..40 && hasPremiumWord(n)) {
                    if (n.contains("provider") || n.contains("product") || n.contains("progress") || n.contains("probableprime")) continue
                    try {
                        if (method.implementation == null) continue
                        val mc = mutableClass ?: continue
                        mc.mutableMethodOf(method)?.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                        patched++
                        patchedMethods.add("Generic:${method.name}")
                    } catch (_: Exception) {}
                }

                val mutableMethod by lazy { mutableClass?.mutableMethodOf(method) }
                val impl = method.implementation ?: continue
                val instructions = impl.instructions.toList()
                for ((index, insn) in instructions.withIndex()) {
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    val mname = ref.name
                    val def = ref.definingClass
                    val isPlayerPrefs = def.contains("PlayerPrefs")
                    val isSharedPrefs = def == "Landroid/content/SharedPreferences;"
                    val isDataStore = def.contains("DataStore") || def.contains("MMKV") ||
                        def.contains("EncryptedSharedPreferences") || def.contains("Preferences")
                    val isEditor = def == "Landroid/content/SharedPreferences\$Editor;"
                    if (!isPlayerPrefs && !isSharedPrefs && !isDataStore && !isEditor) continue

                    val isGetBoolean = (mname == "GetBoolean" || mname == "getBoolean" || mname == "getValue") && ref.returnType == "Z"
                    val isGetInt = (mname == "GetInt" || mname == "getInt" || mname == "getValue") && ref.returnType == "I" &&
                        ref.parameterTypes.firstOrNull() == "Ljava/lang/String;"
                    val isGetLong = (mname == "GetLong" || mname == "getLong") && ref.returnType == "J"
                    val isGetString = (mname == "GetString" || mname == "getString" || mname == "getValue") && ref.returnType == "Ljava/lang/String;"
                    val isHasKey = (mname == "HasKey" || mname == "contains" || mname == "containsKey" || mname == "hasKey") && ref.returnType == "Z"
                    val isDataStoreGet = mname == "get" && isDataStore && ref.parameterTypes.firstOrNull()?.contains("Key") == true
                    val isPutBoolean = isEditor && mname == "putBoolean" && ref.parameterTypes.size >= 2 && ref.parameterTypes[0] == "Ljava/lang/String;"
                    val isPutString = isEditor && mname == "putString" && ref.parameterTypes.size >= 2 && ref.parameterTypes[0] == "Ljava/lang/String;"
                    if (!isGetBoolean && !isGetInt && !isGetLong && !isGetString && !isHasKey && !isDataStoreGet && !isPutBoolean && !isPutString) continue

                    val keyRegister = when (insn) {
                        is BuilderInstruction35c -> when (insn.registerCount) {
                            1, 2 -> insn.registerC
                            else -> insn.registerD
                        }
                        is BuilderInstruction3rc -> insn.startRegister + 1
                        else -> continue
                    }

                    var keyValue: String? = null
                    for (j in index - 1 downTo maxOf(0, index - 6)) {
                        val prev = instructions[j]
                        if (prev.opcode != Opcode.CONST_STRING) continue
                        val reg = (prev as? OneRegisterInstruction)?.registerA ?: continue
                        if (reg != keyRegister) continue
                        keyValue = ((prev as? ReferenceInstruction)?.reference as? StringReference)?.string
                        break
                    }
                    if (keyValue == null) {
                        for (j in index - 1 downTo maxOf(0, index - 8)) {
                            val prev = instructions[j]
                            if (prev.opcode != Opcode.CONST_STRING) continue
                            val s = ((prev as? ReferenceInstruction)?.reference as? StringReference)?.string ?: continue
                            if (isPremiumKey(s.lowercase())) { keyValue = s; break }
                        }
                    }
                    if (keyValue == null || !isPremiumKey(keyValue.lowercase())) continue

                    val mm = mutableMethod ?: continue
                    // Prefer rewriting the getter result. v0 is never used here as a
                    // scratch when the real destination is low, so constTrue stays safe.
                    val next = instructions.getOrNull(index + 1)
                    when {
                        isGetBoolean && next?.opcode == Opcode.MOVE_RESULT ->
                            ((next as OneRegisterInstruction).registerA).let { r ->
                                if (r <= 0xf) {
                                    mm.replaceInstruction(index, "const/4 v$r, 0x1"); mm.replaceInstruction(index + 1, "nop")
                                } else if (r <= 0xff) {
                                    mm.replaceInstruction(index, "const/16 v$r, 0x1"); mm.replaceInstruction(index + 1, "nop")
                                } else {
                                    mm.replaceInstruction(index, "const/4 v0, 0x1"); mm.replaceInstruction(index + 1, "move v$r, v0")
                                }
                                patchedMethods.add("Prefs:$keyValue:getBoolean"); patched++
                            }
                        isHasKey && next?.opcode == Opcode.MOVE_RESULT ->
                            ((next as OneRegisterInstruction).registerA).let { r ->
                                if (r <= 0xf) {
                                    mm.replaceInstruction(index, "const/4 v$r, 0x1"); mm.replaceInstruction(index + 1, "nop")
                                } else if (r <= 0xff) {
                                    mm.replaceInstruction(index, "const/16 v$r, 0x1"); mm.replaceInstruction(index + 1, "nop")
                                } else {
                                    mm.replaceInstruction(index, "const/4 v0, 0x1"); mm.replaceInstruction(index + 1, "move v$r, v0")
                                }
                                patchedMethods.add("Prefs:$keyValue:contains"); patched++
                            }
                        isGetInt && next?.opcode == Opcode.MOVE_RESULT ->
                            ((next as OneRegisterInstruction).registerA).let { r ->
                                if (r <= 0xf) {
                                    mm.replaceInstruction(index, "const/4 v$r, 0x1"); mm.replaceInstruction(index + 1, "nop")
                                } else if (r <= 0xff) {
                                    mm.replaceInstruction(index, "const/16 v$r, 0x1"); mm.replaceInstruction(index + 1, "nop")
                                } else {
                                    mm.replaceInstruction(index, "const/4 v0, 0x1"); mm.replaceInstruction(index + 1, "move v$r, v0")
                                }
                                patchedMethods.add("Prefs:$keyValue:getInt"); patched++
                            }
                        isGetLong && next?.opcode == Opcode.MOVE_RESULT_WIDE ->
                            ((next as OneRegisterInstruction).registerA).let { r ->
                                if (r <= 0xff) {
                                    mm.replaceInstruction(index, "const-wide/16 v$r, 0x1"); mm.replaceInstruction(index + 1, "nop")
                                } else {
                                    mm.replaceInstruction(index, "const-wide/16 v0, 0x1"); mm.replaceInstruction(index + 1, "move-wide v$r, v0")
                                }
                                patchedMethods.add("Prefs:$keyValue:getLong"); patched++
                            }
                        isGetString && next?.opcode == Opcode.MOVE_RESULT_OBJECT ->
                            ((next as OneRegisterInstruction).registerA).let { r ->
                                if (r <= 0xff) {
                                    mm.replaceInstruction(index, "const-string v$r, \"premium\""); mm.replaceInstruction(index + 1, "nop")
                                } else if (r <= 0xffff) {
                                    mm.replaceInstruction(index, "const-string/jumbo v$r, \"premium\""); mm.replaceInstruction(index + 1, "nop")
                                } else {
                                    mm.replaceInstruction(index, "const-string v0, \"premium\""); mm.replaceInstruction(index + 1, "move-object v$r, v0")
                                }
                                patchedMethods.add("Prefs:$keyValue:getString"); patched++
                            }
                        isPutBoolean -> {
                            val valueReg = when (insn) {
                                is BuilderInstruction35c -> insn.registerE
                                is BuilderInstruction3rc -> insn.startRegister + 2
                                else -> null
                            } ?: continue
                            try {
                                mm.addInstructions(index, "const/4 v$valueReg, 0x1")
                                patchedMethods.add("Prefs:$keyValue:putBoolean->true"); patched++
                            } catch (_: Exception) {}
                        }
                        isPutString -> {
                            val valueReg = when (insn) {
                                is BuilderInstruction35c -> insn.registerE
                                is BuilderInstruction3rc -> insn.startRegister + 2
                                else -> null
                            } ?: continue
                            try {
                                val fakePrice = if (keyValue.contains("price")) "9.99" else "premium"
                                if (valueReg <= 0xff) mm.addInstructions(index, "const-string v$valueReg, \"$fakePrice\"")
                                else mm.addInstructions(index, "const-string v0, \"$fakePrice\"\nmove-object v$valueReg, v0")
                                patchedMethods.add("Prefs:$keyValue:putString"); patched++
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        if (patched > 0) {
            logger.info("Unlock Premium: patched $patched check(s)")
            logger.info("Patched methods: ${patchedMethods.sorted().joinToString(", ")}")
        } else {
            logger.warning("Unlock Premium: no premium/ownership checks found. No changes applied.")
        }
    }
}
