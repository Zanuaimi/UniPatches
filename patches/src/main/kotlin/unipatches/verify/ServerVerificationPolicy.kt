package unipatches.verify

import java.util.Locale

/**
 * Pure policy/name logic for the server-side purchase verification patch.
 *
 * Kept free of patcher types so the matching rules can be unit tested without an
 * APK: a change here decides which host methods get forced, so it needs pins.
 */
internal data class ServerVerificationPolicy(
    val enabled: Boolean = true,
    val rewritePayloads: Boolean = true,
    val requireVerificationRequest: Boolean = false,
    val expiryDays: Int = 3650,
    val activeStatus: String = "active",
    val urlTokens: List<String> = DEFAULT_URL_TOKENS,
    val flagKeys: List<String> = DEFAULT_FLAG_KEYS,
    val statusKeys: List<String> = DEFAULT_STATUS_KEYS,
    val expiryKeys: List<String> = DEFAULT_EXPIRY_KEYS,
    val remoteKeys: List<String> = DEFAULT_REMOTE_KEYS,
    val deadStatuses: List<String> = DEFAULT_DEAD_STATUSES,
) {
    /** Runtime policy payload injected at startup; shape is pinned by the runtime tests. */
    fun toJson(): String = buildString {
        append("{\"enabled\":").append(enabled)
        append(",\"rewrite\":").append(rewritePayloads)
        append(",\"requireRequest\":").append(requireVerificationRequest)
        append(",\"expiryDays\":").append(expiryDays.coerceIn(1, 36500))
        append(",\"activeStatus\":\"").append(jsonEscape(activeStatus)).append('"')
        append(",\"urlTokens\":").append(stringArray(urlTokens))
        append(",\"flagKeys\":").append(stringArray(flagKeys))
        append(",\"statusKeys\":").append(stringArray(statusKeys))
        append(",\"expiryKeys\":").append(stringArray(expiryKeys))
        append(",\"remoteKeys\":").append(stringArray(remoteKeys))
        append(",\"deadStatuses\":").append(stringArray(deadStatuses))
        append('}')
    }
}

internal val DEFAULT_URL_TOKENS = listOf(
    "verify", "validatereceipt", "receipt", "entitlement", "entitlements",
    "purchases/verify", "purchase/verify", "iap/verify", "androidpublisher",
    "purchases/products", "purchases/subscriptions", "restore", "checkpurchase",
    "validatepurchase",
)

internal val DEFAULT_FLAG_KEYS = listOf(
    "verified", "isverified", "valid", "isvalid", "success", "ok",
    "premium", "ispremium", "premiumuser", "purchased", "ispurchased",
    "entitled", "isentitled", "subscribed", "issubscribed", "subscriptionactive",
    "active", "unlocked", "isunlocked", "paid", "ispaid", "pro", "ispro",
    "vip", "isvip", "licensevalid",
)

internal val DEFAULT_STATUS_KEYS = listOf(
    "status", "state", "licensestatus", "subscriptionstatus", "receiptstatus",
    "purchasestatus", "entitlementstatus", "verificationstatus",
)

internal val DEFAULT_EXPIRY_KEYS = listOf(
    "expiry", "expires", "expiresat", "expirydate", "expireson", "validuntil",
    "validto", "renewaldate", "nextbillingdate", "subscriptionexpiry", "premiumexpiry",
)

internal val DEFAULT_REMOTE_KEYS = listOf(
    "premium", "ispremium", "premiumenabled", "premiumunlocked", "pro", "ispro",
    "proenabled", "subscribed", "subscriptionactive", "adsremoved", "noads",
    "paywalldisabled", "unlocked", "vip",
)

internal val DEFAULT_DEAD_STATUSES = listOf(
    "expired", "invalid", "inactive", "unpaid", "none", "free", "canceled",
    "cancelled", "refunded", "revoked", "notactive", "notentitled", "unsubscribed",
    "pending", "failed", "false", "0",
)

/** Class namespaces that must never have a verification method forced. */
internal val EXCLUDED_TYPE_PREFIXES = listOf(
    "Landroid/", "Landroidx/", "Lkotlin/", "Lkotlinx/", "Ljava/", "Ljavax/",
    "Lokhttp3/", "Lokio/", "Lretrofit2/", "Lorg/json/", "Lcom/squareup/",
    "Lcom/google/gson/", "Lcom/google/android/gms/", "Lcom/google/protobuf/",
    "Lcom/google/android/material/", "Lcom/android/billingclient/",
    "Lcom/android/vending/", "Lcom/facebook/", "Lio/reactivex/", "Lunipatch/",
)

internal fun normalizeVerificationKey(value: String?): String =
    (value ?: "").lowercase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "").replace(" ", "")

/** True when a URL carries a receipt/entitlement verification call. */
internal fun matchesVerificationUrl(url: String?, tokens: List<String> = DEFAULT_URL_TOKENS): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase(Locale.ROOT)
    return tokens.any { token -> token.isNotEmpty() && lower.contains(token) }
}

/**
 * Method-name rule for receipt/signature verifiers.
 *
 * Requires a verification verb *and* a purchase subject so helpers such as
 * `verifyChecksum` or `isValidEmail` never match. Boolean getters named
 * `isPurchaseSignatureValid` are covered by the `valid` verb, which is why the
 * subject list is the real gate here.
 */
internal fun isReceiptVerifierName(name: String?): Boolean {
    val normalized = normalizeVerificationKey(name)
    if (normalized.isEmpty()) return false
    // markPurchaseInvalid / isLicenseInvalid are the opposite signal.
    if (normalized.contains("invalid")) return false
    if (!VERIFICATION_VERBS.any(normalized::contains)) return false
    return PURCHASE_SUBJECTS.any(normalized::contains)
}

private val VERIFICATION_VERBS = listOf("verify", "validate", "check", "valid", "signed")

private val PURCHASE_SUBJECTS = listOf(
    "purchase", "receipt", "signature", "license", "entitlement", "transaction",
    "billing", "invoice", "subscription", "sku",
)

/** Method-name rule for the client call site that consumes a verification result. */
internal fun isVerificationCallSiteName(name: String?): Boolean {
    val normalized = normalizeVerificationKey(name)
    if (normalized.isEmpty()) return false
    val verb = listOf(
        "verify", "validate", "check", "confirm", "restore", "grant", "sync",
    ).any(normalized::startsWith)
    if (!verb) return false
    val subject = listOf(
        "purchase", "receipt", "entitlement", "subscription", "premium", "license",
        "unlock", "sku", "transaction", "billing", "iap",
    ).any(normalized::contains)
    return subject
}

/** True when a string literal in a method body is a verification endpoint/marker. */
internal fun isVerificationLiteral(value: String?, tokens: List<String> = DEFAULT_URL_TOKENS): Boolean {
    if (value.isNullOrBlank() || value.length > 512) return false
    val lower = value.lowercase(Locale.ROOT)
    if (!lower.contains('/') && !lower.contains('.')) return false
    return tokens.any { token -> token.isNotEmpty() && lower.contains(token) }
}

internal fun isExcludedVerificationType(type: String?): Boolean {
    val value = type ?: return true
    return EXCLUDED_TYPE_PREFIXES.any(value::startsWith)
}

private fun stringArray(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"${jsonEscape(it)}\"" }

private fun jsonEscape(value: String): String = buildString(value.length + 8) {
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}
