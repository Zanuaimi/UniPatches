package unipatches.verify

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.stringsOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import helpers.bytecode.cloneMutable
import helpers.bytecode.p0Register
import helpers.startup.StartupHooks
import helpers.startup.resolveStartupEntryPoint
import java.util.logging.Logger

private const val VERIFY_RUNTIME = "Lunipatch/verify/VerificationRuntime;"

/**
 * Client-side handling for receipt and entitlement *responses*.
 *
 * Billing emulation fakes the purchase; this patch covers the next hop, where the
 * app hands a receipt/token to its backend and reacts to the answer. It cannot make
 * Google's servers validate a receipt, and it cannot forge an attestation. It changes
 * what the client does with the answer it already receives.
 */
@Suppress("unused")
val serverVerificationBypassPatch = bytecodePatch(
    name = "Server Purchase Verification Bypass (Experimental)",
    description = """
        Handles the client half of server-side purchase verification: the receipt/entitlement
        response the app receives after a purchase, and the local flags it reads from that response.

        Strategies (all switchable):
        - Force receipt/signature verifier methods that take a receipt, token or signature to
          report success. Covers the Play sample `Security.verifyPurchase` shape and app-specific
          equivalents by name plus parameter evidence.
        - Rewrite JSON verification payloads before the app parses them: booleans such as
          verified/isPremium/subscribed become true, dead status strings become active, expiry
          fields move forward. Applied at Gson and Moshi entry points and at every
          `new JSONObject(String)` call site in app bytecode.
        - Observe okhttp request URLs so a real verification call confirms the rewrite path.
        - Force premium Firebase Remote Config flags used by server-driven paywalls.

        Limits: this changes client behaviour, not the server. A backend that compares a receipt
        against Google Play, rejects unknown order IDs, pins a certificate this patch does not
        touch, or never returns an entitlement to the client stays closed. Pair it with
        InApp Emulation for the purchase callback itself.

        Compatibility: pair with InApp Emulation so a purchase exists before verification runs.
        Do not select this with a real store build where purchases must charge.
    """.trimIndent(),
    default = false,
) {
    // Guarded: morphe-patcher < 1.13.0 has no category() and keeps the patch ungrouped.
    try { category("InApp Emulation") } catch (_: NoSuchMethodError) {}
    // Every injected block resolves against unipatch.verify.*; the extension DEX must
    // merge into the target before this patch runs or the first hooked call throws.
    extendWith("extensions/extension.mpe")
    dependsOn(StartupHooks.resolveRealApplicationPatch)

    val forceReceiptVerifiers by booleanOption(
        title = "Verification > Force receipt and signature verifiers",
        default = true,
        key = "verifyForceReceiptVerifiers",
        description = "Make methods named like verifyPurchase/validateReceipt/checkSignature, which take a receipt, token or signature, report success.",
    )
    val forceVerificationCallSites by booleanOption(
        title = "Verification > Force verification call sites",
        default = true,
        key = "verifyForceCallSites",
        description = "Force boolean methods that both look like a verification consumer and reference a verification endpoint or verifier call. Requires evidence inside the method body; name matching alone is not enough.",
    )
    val rewriteJsonPayloads by booleanOption(
        title = "Verification > Rewrite verification responses",
        default = true,
        key = "verifyRewritePayloads",
        description = "Rewrite JSON before the app parses it: Gson, Moshi and new JSONObject(String) call sites. Only verification-shaped payloads are changed; other responses pass through untouched.",
    )
    val observeRequestUrls by booleanOption(
        title = "Verification > Observe okhttp request URLs",
        default = true,
        key = "verifyObserveUrls",
        description = "Report okhttp request URLs to the runtime so a real receipt/entitlement call marks the session as verifying. Also lets Rewrite verification responses require a recent verification request.",
    )
    val remoteConfigFlags by booleanOption(
        title = "Verification > Force Remote Config paywall flags",
        default = true,
        key = "verifyRemoteConfig",
        description = "Make Firebase Remote Config boolean flags named premium/pro/paywall/subscription report true, for server-driven paywalls that ship their gate through Remote Config.",
    )
    val requireVerificationRequest by booleanOption(
        title = "Verification > Require an observed verification request",
        default = false,
        key = "verifyRequireRequest",
        description = "Only rewrite payloads after a receipt/entitlement URL was actually requested in this session. Stricter, but misses apps that verify through a scheme this patch does not observe.",
    )
    val injectRuntimePolicy by booleanOption(
        title = "Verification > Inject runtime policy",
        default = true,
        key = "verifyInjectPolicy",
        description = "Write the selected URL/flag/expiry policy into the app's startup entry point. Disable to keep the runtime defaults compiled into the extension.",
    )
    val expiryDays by intOption(
        title = "Verification > Entitlement expiry (days)",
        default = 3650,
        key = "verifyExpiryDays",
        description = "How far forward expiry/validUntil fields are moved. Values are clamped to 1-36500 days.",
    )
    val extraFlagKeys by stringsOption(
        title = "Verification > Extra response flag keys",
        default = emptyList(),
        key = "verifyExtraFlagKeys",
        description = "Additional JSON keys to force true, one per row, for an app with its own receipt field name.",
    )
    val extraUrlTokens by stringsOption(
        title = "Verification > Extra URL tokens",
        default = emptyList(),
        key = "verifyExtraUrlTokens",
        description = "Additional substrings that identify a verification endpoint, one per row, for a backend with custom routes.",
    )
    val extraRemoteKeys by stringsOption(
        title = "Verification > Extra Remote Config keys",
        default = emptyList(),
        key = "verifyExtraRemoteKeys",
        description = "Additional Remote Config boolean keys to force true, one per row, for a custom paywall flag.",
    )
    val verbosePayloads by booleanOption(
        title = "Verification > Log parsed JSON payloads (diagnostics)",
        default = false,
        key = "verifyVerbose",
        description = "Log the first 40 JSON payloads the rewrite hook sees, with the verdict, so a payload shape this patch does not recognise can be discovered and added as a flag/status/expiry key. Diagnostic only; leave off for normal builds.",
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val policy = ServerVerificationPolicy(
            rewritePayloads = rewriteJsonPayloads == true,
            requireVerificationRequest = requireVerificationRequest == true,
            expiryDays = (expiryDays ?: 3650).coerceIn(1, 36500),
            verbose = verbosePayloads == true,
            urlTokens = (DEFAULT_URL_TOKENS + (extraUrlTokens ?: emptyList())).distinct(),
            flagKeys = (DEFAULT_FLAG_KEYS + (extraFlagKeys ?: emptyList())).distinct(),
            remoteKeys = (DEFAULT_REMOTE_KEYS + (extraRemoteKeys ?: emptyList())).distinct(),
        )
        val patchedClasses = mutableSetOf<String>()

        /** Installs the okhttp hooks that report request URLs. */
        fun hookRequestUrls(): Int {
            val builder = try { mutableClassDefByOrNull("Lokhttp3/Request\$Builder;") } catch (_: Exception) { null }
            if (builder == null) {
                logger.info("Server verification: okhttp Request.Builder not found; URL observation skipped")
                return 0
            }
            var hooked = 0
            for (target in builder.methods.toList()) {
                if (target.name != "url") continue
                val implementation = target.implementation ?: continue
                if (implementation.instructions.any { it.toString().contains("VerificationRuntime;->observeUrl") }) {
                    hooked++
                    continue
                }
                when (target.parameterTypes) {
                    listOf("Ljava/lang/String;") -> {
                        target.addInstructions(
                            0,
                            "invoke-static/range {p1 .. p1}, $VERIFY_RUNTIME->observeUrl(Ljava/lang/String;)V",
                        )
                        hooked++
                    }
                    listOf("Lokhttp3/HttpUrl;") -> {
                        // The caller's HttpUrl is still in p1 at entry, so toString() is safe.
                        // v0 is only dead on entry while it sits below the parameter block --
                        // above p0Register it is the receiver or an argument.
                        if (target.p0Register < 1) {
                            logger.fine("Server verification: no local register for the URL hook in ${target.name}")
                            continue
                        }
                        target.addInstructions(
                            0,
                            """
                            invoke-virtual/range {p1 .. p1}, Lokhttp3/HttpUrl;->toString()Ljava/lang/String;
                            move-result-object v0
                            invoke-static {v0}, $VERIFY_RUNTIME->observeUrl(Ljava/lang/String;)V
                            """.trimIndent(),
                        )
                        hooked++
                    }
                }
            }
            return hooked
        }

        /** Rewrites the String argument of a JSON entry point before parsing. */
        fun hookJsonParser(type: String, name: String, parameters: List<String>, returnType: String): Boolean {
            val owner = try { mutableClassDefByOrNull(type) } catch (_: Exception) { null } ?: return false
            val method = owner.methods.firstOrNull {
                it.name == name && it.parameterTypes == parameters && it.returnType == returnType
            } ?: return false
            val implementation = method.implementation ?: return false
            if (implementation.instructions.any { it.toString().contains("VerificationRuntime;->rewritePayload") }) return true
            return try {
                method.addInstructions(
                    0,
                    """
                    invoke-static/range {p1 .. p1}, $VERIFY_RUNTIME->rewritePayload(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                    """.trimIndent(),
                )
                true
            } catch (error: Exception) {
                logger.warning("Server verification: JSON hook failed at $type->$name: ${error.message}")
                false
            }
        }

        /** Rewrites the argument of every `new JSONObject(String)` call in app bytecode. */
        fun hookJsonObjectCallSites(): Int {
            var sites = 0
            classDefForEach { classDef ->
                if (isExcludedVerificationType(classDef.type)) return@classDefForEach
                val mutable = try { mutableClassDefBy(classDef) } catch (_: Exception) { null } ?: return@classDefForEach
                for (method in mutable.methods.toList()) {
                    val instructions = method.implementation?.instructions?.toList() ?: continue
                    val insertions = mutableListOf<Pair<Int, Int>>()
                    for ((index, instruction) in instructions.withIndex()) {
                        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                        if (reference.definingClass != "Lorg/json/JSONObject;") continue
                        if (reference.name != "<init>") continue
                        if (reference.parameterTypes != listOf("Ljava/lang/String;")) continue
                        if (index > 0 && instructions[index - 1].toString().contains("VerificationRuntime;->rewritePayload")) continue
                        val register = when (instruction) {
                            // 35c lists registers in argument order: the receiver first, then the
                            // arguments. For `invoke-direct {vA, vB}, JSONObject-><init>(String)`
                            // vA is the uninitialised object and vB is the payload, so the rewrite
                            // has to target the LAST register, never registerC.
                            is FiveRegisterInstruction -> when (instruction.registerCount) {
                                2 -> instruction.registerD
                                3 -> instruction.registerE
                                4 -> instruction.registerF
                                5 -> instruction.registerG
                                else -> null
                            }
                            // 3rc covers {this, arg}; the argument is the last register of the range.
                            // Using startRegister here would rewrite the JSONObject being built.
                            is RegisterRangeInstruction ->
                                instruction.startRegister + instruction.registerCount - 1
                            else -> null
                        } ?: continue
                        insertions += index to register
                    }
                    // Highest index first: earlier insertions never move a site handled already.
                    for ((index, register) in insertions.reversed()) {
                        // move-result-object has no /16 form; a site whose argument lives above
                        // v255 cannot be rewritten inline without stealing a live low register.
                        if (register > 0xFF) {
                            logger.fine(
                                "Server verification: skipping JSONObject site in ${method.definingClass}" +
                                    "->${method.name} at v$register (above move-result range)",
                            )
                            continue
                        }
                        try {
                            method.addInstructions(
                                index,
                                """
                                invoke-static/range { v$register .. v$register }, $VERIFY_RUNTIME->rewritePayload(Ljava/lang/String;)Ljava/lang/String;
                                move-result-object v$register
                                """.trimIndent(),
                            )
                            sites++
                        } catch (error: Exception) {
                            logger.warning("Server verification: JSONObject rewrite failed at ${method.definingClass}->${method.name}: ${error.message}")
                        }
                    }
                }
            }
            return sites
        }

        /** Reports success from receipt/signature verifiers that take a payload argument. */
        fun forceReceiptVerifiers(): Int {
            var count = 0
            classDefForEach { classDef ->
                if (isExcludedVerificationType(classDef.type)) return@classDefForEach
                val candidates = classDef.methods.filter { method ->
                    method.returnType == "Z" &&
                        method.implementation != null &&
                        method.parameterTypes.isNotEmpty() &&
                        method.parameterTypes.any { type -> type.startsWith("L") } &&
                        isReceiptVerifierName(method.name)
                }
                if (candidates.isEmpty()) return@classDefForEach
                val mutable = try { mutableClassDefBy(classDef) } catch (_: Exception) { null } ?: return@classDefForEach
                for (candidate in candidates) {
                    val method = mutable.methods.firstOrNull {
                        it.name == candidate.name && it.parameterTypes == candidate.parameterTypes &&
                            it.returnType == candidate.returnType
                    } ?: continue
                    val implementation = method.implementation ?: continue
                    val instructions = implementation.instructions.toList()
                    if (instructions.size >= 2 &&
                        instructions[0].toString().trim() == "const/4 v0, 0x1" &&
                        instructions[1].toString().trim() == "return v0"
                    ) continue
                    if (method.p0Register < 1) continue
                    try {
                        method.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                        patchedClasses += "${method.definingClass}->${method.name}"
                        count++
                    } catch (error: Exception) {
                        logger.warning("Server verification: verifier force failed at ${method.definingClass}->${method.name}: ${error.message}")
                    }
                }
            }
            return count
        }

        /** Forces boolean consumers that prove, inside their body, that they handle a verification answer. */
        fun forceVerificationCallSites(): Int {
            var count = 0
            classDefForEach { classDef ->
                if (isExcludedVerificationType(classDef.type)) return@classDefForEach
                val mutable = try { mutableClassDefBy(classDef) } catch (_: Exception) { null } ?: return@classDefForEach
                for (method in mutable.methods.toList()) {
                    if (method.returnType != "Z") continue
                    val implementation = method.implementation ?: continue
                    if (!isVerificationCallSiteName(method.name) && !isReceiptVerifierName(method.name)) continue
                    val instructions = implementation.instructions.toList()
                    val strings = instructions.mapNotNull { instruction ->
                        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
                    }
                    val urlEvidence = strings.any { isVerificationLiteral(it) }
                    val verifierCallEvidence = instructions.any { instruction ->
                        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            ?: return@any false
                        reference.parameterTypes.any { it == "Ljava/lang/String;" } &&
                            (reference.name.contains("verify", ignoreCase = true) ||
                                reference.name.contains("validate", ignoreCase = true))
                    }
                    if (!urlEvidence && !verifierCallEvidence) continue
                    val key = "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString(",")})"
                    if (key in patchedClasses) continue
                    if (instructions.size >= 2 &&
                        instructions[0].toString().trim() == "const/4 v0, 0x1" &&
                        instructions[1].toString().trim() == "return v0"
                    ) continue
                    if (method.p0Register < 1) continue
                    try {
                        method.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
                        patchedClasses += key
                        count++
                    } catch (error: Exception) {
                        logger.warning("Server verification: call-site force failed at $key: ${error.message}")
                    }
                }
            }
            return count
        }

        /** Short-circuits premium Firebase Remote Config booleans at entry. */
        fun hookRemoteConfig(): Boolean {
            val owner = try {
                mutableClassDefByOrNull("Lcom/google/firebase/remoteconfig/FirebaseRemoteConfig;")
            } catch (_: Exception) {
                null
            } ?: return false
            val method = owner.methods.firstOrNull {
                it.name == "getBoolean" && it.parameterTypes == listOf("Ljava/lang/String;") && it.returnType == "Z"
            } ?: return false
            val implementation = method.implementation ?: return false
            if (implementation.instructions.any { it.toString().contains("isPremiumRemoteKey") }) return true
            // Inserted at entry, so v0 has to be a real local: at or above p0Register it is
            // the receiver (v0 for a static-free method is only safe below the parameter block).
            if (method.p0Register < 1) {
                logger.warning("Server verification: Remote Config hook skipped; no local register in getBoolean")
                return false
            }
            return try {
                method.addInstructionsWithLabels(
                    0,
                    """
                    invoke-static/range { p1 .. p1 }, $VERIFY_RUNTIME->isPremiumRemoteKey(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :verifyRemoteContinue
                    const/4 v0, 0x1
                    return v0
                    :verifyRemoteContinue
                    nop
                    """.trimIndent(),
                )
                true
            } catch (error: Exception) {
                logger.warning("Server verification: Remote Config hook failed: ${error.message}")
                false
            }
        }

        /** Writes the selected policy into the app's startup entry point. */
        fun injectPolicy(): Boolean {
            val entry = try { resolveStartupEntryPoint(logger) } catch (error: Exception) {
                logger.warning("Server verification: startup resolution failed: ${error.message}")
                return false
            } ?: run {
                logger.info("Server verification: no safe startup entry point; runtime defaults stay active")
                return false
            }
            val implementation = entry.onCreate.implementation ?: return false
            if (implementation.instructions.any { it.toString().contains("VerificationRuntime;->configure") }) return true
            // The block is inserted at entry, where every local below p0Register is still
            // undefined. Requesting extra registers instead would shift the parameter block up
            // and write over `this`, which the verifier rejects as a type mismatch at the
            // original invoke-super.
            if (entry.onCreate.p0Register < 1) {
                logger.warning(
                    "Server verification: policy injection skipped; ${entry.owner.type}->onCreate has no local register",
                )
                return false
            }
            return try {
                entry.onCreate.addInstructions(
                    0,
                    """
                    const-string v0, "${StartupHooks.escapeSmali(policy.toJson())}"
                    invoke-static {v0}, $VERIFY_RUNTIME->configure(Ljava/lang/String;)V
                    """.trimIndent(),
                )
                logger.info("Server verification: policy injected at ${entry.owner.type}->onCreate")
                true
            } catch (error: Exception) {
                logger.warning("Server verification: policy injection failed: ${error.message}")
                false
            }
        }

        val urlHooks = if (observeRequestUrls == true) hookRequestUrls() else 0
        var jsonHooks = 0
        var jsonCallSites = 0
        if (rewriteJsonPayloads == true) {
            if (hookJsonParser("Lcom/google/gson/Gson;", "fromJson", listOf("Ljava/lang/String;", "Ljava/lang/Class;"), "Ljava/lang/Object;")) jsonHooks++
            if (hookJsonParser("Lcom/google/gson/Gson;", "fromJson", listOf("Ljava/lang/String;", "Ljava/lang/reflect/Type;"), "Ljava/lang/Object;")) jsonHooks++
            if (hookJsonParser("Lcom/squareup/moshi/JsonAdapter;", "fromJson", listOf("Ljava/lang/String;"), "Ljava/lang/Object;")) jsonHooks++
            jsonCallSites = hookJsonObjectCallSites()
        }
        val verifiers = if (forceReceiptVerifiers == true) forceReceiptVerifiers() else 0
        val callSites = if (forceVerificationCallSites == true) forceVerificationCallSites() else 0
        val remoteHook = if (remoteConfigFlags == true) hookRemoteConfig() else false
        val policyInjected = if (injectRuntimePolicy == true) injectPolicy() else false

        logger.info(
            "Server verification: urlHooks=$urlHooks jsonEntryPoints=$jsonHooks jsonObjectSites=$jsonCallSites " +
                "verifiers=$verifiers callSites=$callSites remoteConfig=$remoteHook policy=$policyInjected",
        )
        if (urlHooks == 0 && jsonHooks == 0 && jsonCallSites == 0 && verifiers == 0 && callSites == 0 &&
            !remoteHook && !policyInjected
        ) {
            logger.warning(
                "Server verification: nothing matched. The app may verify natively, through a pinned " +
                    "connection, or with a serializer this patch does not hook.",
            )
        }
    }
}
