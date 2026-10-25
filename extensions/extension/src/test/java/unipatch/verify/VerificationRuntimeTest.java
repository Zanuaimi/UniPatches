package unipatch.verify;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Pins the policy surface the injected call sites depend on. */
public class VerificationRuntimeTest {
    @Test
    public void premiumRemoteKeysAreOverridden() {
        assertTrue(VerificationRuntime.isPremiumRemoteKey("premium_enabled"));
        assertTrue(VerificationRuntime.isPremiumRemoteKey("billing.paywall_disabled"));
        assertTrue(VerificationRuntime.isPremiumRemoteKey("subscription_active"));
        assertFalse(VerificationRuntime.isPremiumRemoteKey("rollout_percentage"));
        assertFalse(VerificationRuntime.isPremiumRemoteKey(null));
    }

    @Test
    public void observedVerificationUrlUnlocksRewriting() {
        VerificationRuntime.observeUrl("https://api.example.com/v1/receipts/verify?token=abc");
        String payload = "{\"verified\":false,\"unknown\":\"kept\"}";
        String rewritten = VerificationRuntime.rewritePayload(payload);
        assertEquals("{\"verified\":true,\"unknown\":\"kept\"}", rewritten);
    }

    @Test
    public void unrelatedPayloadIsReturnedByInstance() {
        String payload = "{\"name\":\"sam\",\"count\":2}";
        assertSame(payload, VerificationRuntime.rewritePayload(payload));
    }

    @Test
    public void malformedPayloadFallsBackInsteadOfThrowing() {
        String payload = "{\"verified\":";
        assertSame(payload, VerificationRuntime.rewritePayload(payload));
        assertEquals(null, VerificationRuntime.rewritePayload(null));
        assertSame("not json", VerificationRuntime.rewritePayload("not json"));
    }

    @Test
    public void configureAcceptsInjectedPolicyAndSurvivesGarbage() {
        VerificationRuntime.configure("{\"enabled\":true,\"expiryDays\":30," +
                "\"urlTokens\":[\"verify\"],\"flagKeys\":[\"verified\",\"premium\"]," +
                "\"remoteKeys\":[\"premium\"],\"activeStatus\":\"active\"}");
        VerificationRuntime.configure("not a policy");
        VerificationRuntime.configure(null);
        assertTrue(VerificationRuntime.describe().contains("verifyRuntime"));
        assertTrue(VerificationRuntime.isPremiumRemoteKey("premium"));
    }
}
