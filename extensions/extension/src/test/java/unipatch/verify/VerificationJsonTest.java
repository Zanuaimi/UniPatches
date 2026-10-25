package unipatch.verify;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pins the byte-preserving JSON contract the verification hooks rely on. */
public class VerificationJsonTest {
    private static VerificationJson.Rules rules(String... statuses) {
        return new VerificationJson.Rules(
                new LinkedHashSet<>(Arrays.asList("verified", "ispremium", "subscribed", "ispurchased")),
                new LinkedHashSet<>(Collections.singletonList("status")),
                new LinkedHashSet<>(Arrays.asList("expiresat", "expiry")),
                new LinkedHashSet<>(Arrays.asList(statuses).isEmpty()
                        ? Arrays.asList("expired", "invalid", "inactive")
                        : Arrays.asList(statuses)),
                "active",
                4102444800000L,
                false);
    }

    @Test
    public void flipsBooleansAndKeepsEverythingElseByteIdentical() {
        String json = "{\"orderId\":\"GPA.1234\",\"verified\":false,\"amounts\":[1,2.5e3],\"note\":\"a \\\"b\\\" {c}\"}";
        String rewritten = VerificationJson.rewrite(json, rules());
        assertNotNull(rewritten);
        assertEquals(
                "{\"orderId\":\"GPA.1234\",\"verified\":true,\"amounts\":[1,2.5e3],\"note\":\"a \\\"b\\\" {c}\"}",
                rewritten);
    }

    @Test
    public void stringEncodedFlagsBecomeRealBooleans() {
        String json = "{\"isPremium\":\"false\",\"subscribed\":\"0\"}";
        assertEquals(
                "{\"isPremium\":true,\"subscribed\":true}",
                VerificationJson.rewrite(json, rules()));
    }

    @Test
    public void deadStatusesBecomeActive() {
        String json = "{\"status\":\"expired\",\"nested\":{\"status\":\"invalid\"}}";
        assertEquals(
                "{\"status\":\"active\",\"nested\":{\"status\":\"active\"}}",
                VerificationJson.rewrite(json, rules()));
    }

    @Test
    public void liveStatusesAreLeftAlone() {
        String json = "{\"status\":\"active\"}";
        assertNull(VerificationJson.rewrite(json, rules()));
    }

    @Test
    public void expiryTimestampsMoveForward() {
        String numeric = VerificationJson.rewrite("{\"expiresAt\":1700000000}", rules());
        assertEquals("{\"expiresAt\":4102444800000}", numeric);
        String iso = VerificationJson.rewrite("{\"expiry\":\"2020-01-01T00:00:00Z\"}", rules());
        assertEquals("{\"expiry\":\"2100-01-01T00:00:00Z\"}", iso);
    }

    @Test
    public void arraysRecurseIntoObjects() {
        String json = "{\"entitlements\":[{\"verified\":false},{\"verified\":true}]}";
        assertEquals(
                "{\"entitlements\":[{\"verified\":true},{\"verified\":true}]}",
                VerificationJson.rewrite(json, rules()));
    }

    @Test
    public void unchangedAndMalformedDocumentsReturnNull() {
        assertNull(VerificationJson.rewrite("{\"name\":\"sam\"}", rules()));
        assertNull(VerificationJson.rewrite("{\"verified\":", rules()));
        assertNull(VerificationJson.rewrite("not json", rules()));
        assertNull(VerificationJson.rewrite("", rules()));
        assertNull(VerificationJson.rewrite(null, rules()));
    }

    @Test
    public void unicodeEscapesSurviveUntouched() {
        String json = "{\"name\":\"\\u00e9\",\"verified\":false}";
        assertEquals("{\"name\":\"\\u00e9\",\"verified\":true}",
                VerificationJson.rewrite(json, rules()));
    }

    @Test
    public void flatPolicyObjectReadsBooleanIntegerAndStringArrays() {
        Map<String, Object> policy = VerificationJson.readFlatObject(
                "{\"enabled\":true,\"expiryDays\":3650,\"activeStatus\":\"active\"," +
                        "\"urlTokens\":[\"verify\",\"receipt\"],\"nested\":{\"ignored\":1}}");
        assertNotNull(policy);
        assertEquals(Boolean.TRUE, policy.get("enabled"));
        assertEquals(Long.valueOf(3650L), policy.get("expiryDays"));
        assertEquals("active", policy.get("activeStatus"));
        assertEquals(Arrays.asList("verify", "receipt"), policy.get("urlTokens"));
        assertNull(policy.get("nested"));
    }

    @Test
    public void unreadablePolicyReturnsNullInsteadOfThrowing() {
        assertNull(VerificationJson.readFlatObject("{\"enabled\":"));
        assertNull(VerificationJson.readFlatObject("[]"));
    }

    @Test
    public void normalizationStripsSeparatorsAndCase() {
        assertEquals("ispremium", VerificationJson.normalize("is_premium"));
        assertEquals("expiresat", VerificationJson.normalize("Expires-At"));
        assertEquals("", VerificationJson.normalize(null));
    }

    @Test
    public void escapeFormattingIsStable() {
        assertTrue(VerificationJson.escape("a\"b\\c\n").equals("a\\\"b\\\\c\\n"));
    }
}
