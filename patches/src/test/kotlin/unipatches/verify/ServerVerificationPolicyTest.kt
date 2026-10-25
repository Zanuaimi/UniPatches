package unipatches.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the matching rules the Server Purchase Verification Bypass patch decides on.
 *
 * Every rule here selects host bytecode for rewriting, so a regression turns into a
 * silently unpatched app rather than a crash. These are the cheap guards.
 */
class ServerVerificationPolicyTest {
    @Test
    fun matchesReceiptAndEntitlementUrls() {
        assertTrue(matchesVerificationUrl("https://api.example.com/v1/purchases/verify?token=abc"))
        assertTrue(matchesVerificationUrl("https://api.example.com/entitlements"))
        assertTrue(matchesVerificationUrl("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/x/purchases/products/y"))
        assertTrue(matchesVerificationUrl("https://example.com/iap/verify"))
    }

    @Test
    fun ignoresUnrelatedUrlsAndBlanks() {
        assertFalse(matchesVerificationUrl("https://api.example.com/v1/feed"))
        assertFalse(matchesVerificationUrl(""))
        assertFalse(matchesVerificationUrl(null))
        // Case folding happens before matching.
        assertTrue(matchesVerificationUrl("HTTPS://API.EXAMPLE.COM/RECEIPT"))
    }

    @Test
    fun extraUrlTokensExtendTheSet() {
        val tokens = DEFAULT_URL_TOKENS + "mycustomgrant"
        assertTrue(matchesVerificationUrl("https://api.example.com/mycustomgrant", tokens))
        assertFalse(matchesVerificationUrl("https://api.example.com/mycustomgrant"))
    }

    @Test
    fun receiptVerifiersNeedAVerificationVerbAndAPurchaseSubject() {
        assertTrue(isReceiptVerifierName("verifyPurchase"))
        assertTrue(isReceiptVerifierName("validateReceipt"))
        assertTrue(isReceiptVerifierName("checkSignature"))
        assertTrue(isReceiptVerifierName("verify_license"))
        assertTrue(isReceiptVerifierName("isPurchaseSignatureValid"))
    }

    @Test
    fun ordinaryHelpersAreNotReceiptVerifiers() {
        assertFalse(isReceiptVerifierName("verifyChecksum"))
        assertFalse(isReceiptVerifierName("isValidEmail"))
        assertFalse(isReceiptVerifierName("purchaseButtonClicked"))
        assertFalse(isReceiptVerifierName(""))
        assertFalse(isReceiptVerifierName(null))
    }

    @Test
    fun verificationCallSitesNeedAVerbAndAFeatureSubject() {
        assertTrue(isVerificationCallSiteName("restorePurchases"))
        assertTrue(isVerificationCallSiteName("syncEntitlements"))
        assertTrue(isVerificationCallSiteName("checkSubscription"))
        assertTrue(isVerificationCallSiteName("grantPremium"))
    }

    @Test
    fun bareVerbsAreNotCallSites() {
        assertFalse(isVerificationCallSiteName("checkout"))
        assertFalse(isVerificationCallSiteName("confirm"))
        assertFalse(isVerificationCallSiteName("sync"))
        assertFalse(isVerificationCallSiteName(null))
    }

    @Test
    fun verificationLiteralsMustLookLikeAnEndpoint() {
        assertTrue(isVerificationLiteral("https://api.example.com/receipt/verify"))
        assertTrue(isVerificationLiteral("api.example.com/entitlements"))
        assertFalse(isVerificationLiteral("receipt"))
        assertFalse(isVerificationLiteral("premium"))
        assertFalse(isVerificationLiteral(null))
        // Long blobs are payloads, not endpoints; they stay untouched.
        assertFalse(isVerificationLiteral("x".repeat(600) + "/verify"))
    }

    @Test
    fun frameworkAndThirdPartyTypesAreExcluded() {
        assertTrue(isExcludedVerificationType("Lokhttp3/Request\$Builder;"))
        assertTrue(isExcludedVerificationType("Landroid/app/Activity;"))
        assertTrue(isExcludedVerificationType("Lcom/android/billingclient/api/Purchase;"))
        assertTrue(isExcludedVerificationType("Lunipatch/verify/VerificationRuntime;"))
        assertTrue(isExcludedVerificationType(null))
        assertFalse(isExcludedVerificationType("Lcom/example/game/BillingVerifier;"))
    }

    @Test
    fun normalizationStripsSeparators() {
        assertEquals("ispremium", normalizeVerificationKey("is_premium"))
        assertEquals("expiresat", normalizeVerificationKey("Expires.At"))
        assertEquals("", normalizeVerificationKey(null))
    }

    @Test
    fun policyJsonCarriesEveryListTheRuntimeReads() {
        val json = ServerVerificationPolicy().toJson()
        assertTrue(json.startsWith("{\"enabled\":true,\"rewrite\":true,\"requireRequest\":false"))
        assertTrue(json.contains("\"expiryDays\":3650"))
        assertTrue(json.contains("\"activeStatus\":\"active\""))
        listOf("urlTokens", "flagKeys", "statusKeys", "expiryKeys", "remoteKeys", "deadStatuses")
            .forEach { key -> assertTrue("missing $key in $json", json.contains("\"$key\":[")) }
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun policyJsonClampsExpiryAndEscapesQuotes() {
        val json = ServerVerificationPolicy(expiryDays = 99_999, activeStatus = "act\"ive").toJson()
        assertTrue(json, json.contains("\"expiryDays\":36500"))
        assertTrue(json, json.contains("\"activeStatus\":\"act\\\"ive\""))
        val floor = ServerVerificationPolicy(expiryDays = 0).toJson()
        assertTrue(floor, floor.contains("\"expiryDays\":1"))
    }
}
