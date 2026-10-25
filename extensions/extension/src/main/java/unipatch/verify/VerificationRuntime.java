package unipatch.verify;

import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime half of the Server Purchase Verification Bypass patch.
 *
 * <p>Injected call sites reach this class through reflection-free static calls:
 * <ul>
 *   <li>{@code okhttp3.Request.Builder.url(String)} reports every request URL, so the
 *       runtime knows a receipt/entitlement call actually happened.</li>
 *   <li>JSON entry points (Gson, Moshi, and app-side {@code new JSONObject(String)}
 *       call sites) pass the raw response body through {@link #rewritePayload(String)}
 *       before the host app parses it.</li>
 *   <li>Server-driven flags read from Firebase Remote Config consult
 *       {@link #isPremiumRemoteKey(String)}.</li>
 * </ul>
 *
 * <p>Every public method is exception-safe. A patched app must behave exactly like the
 * unpatched one whenever this class cannot make sense of a payload, a policy, or an
 * Android API it touches.
 */
public final class VerificationRuntime {
    private static final String TAG = "UniPatchVerify";
    private static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
    private static final long REQUEST_MEMORY_MILLIS = 120_000L;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private static final String[] DEFAULT_URL_TOKENS = {
            "verify", "verifypurchase", "validatereceipt", "receipt", "entitlement",
            "entitlements", "purchase/verify", "purchases/verify", "iap/verify",
            "androidpublisher", "/purchases/products", "/purchases/subscriptions",
            "restore", "grant", "checkpurchase", "validatepurchase",
    };

    private static final String[] DEFAULT_FLAG_KEYS = {
            "verified", "isverified", "valid", "isvalid", "success", "ok",
            "premium", "ispremium", "premiumuser", "ispremiumuser",
            "purchased", "ispurchased", "entitled", "isentitled",
            "subscribed", "issubscribed", "subscriptionactive", "active",
            "unlocked", "isunlocked", "paid", "ispaid", "pro", "ispro",
            "vip", "isvip", "licensevalid", "islicensevalid",
    };

    private static final String[] DEFAULT_STATUS_KEYS = {
            "status", "state", "licensestatus", "subscriptionstatus",
            "receiptstatus", "purchasestatus", "entitlementstatus", "verificationstatus",
    };

    private static final String[] DEFAULT_EXPIRY_KEYS = {
            "expiry", "expires", "expiresat", "expirydate", "expireson", "validuntil",
            "validto", "renewaldate", "nextbillingdate", "subscriptionexpiry",
            "premiumexpiry",
    };

    private static final String[] DEFAULT_REMOTE_KEYS = {
            "premium", "ispremium", "premiumenabled", "premiumunlocked",
            "pro", "ispro", "proenabled", "subscribed", "subscriptionactive",
            "adsremoved", "noads", "paywalldisabled", "unlocked", "vip",
    };

    private static final String[] DEFAULT_DEAD_STATUSES = {
            "expired", "invalid", "inactive", "unpaid", "none", "free", "canceled",
            "cancelled", "refunded", "revoked", "notactive", "notentitled",
            "unsubscribed", "pending", "failed", "false", "0",
    };

    private static volatile boolean enabled = true;
    private static volatile boolean rewritePayloads = true;
    private static volatile boolean requireRecentRequest = false;
    private static volatile long expiryEpochMillis = now() + 3650L * DAY_MILLIS;
    private static volatile boolean expiryInSeconds = false;
    private static volatile String activeStatus = "active";
    private static volatile Set<String> urlTokens = normalizeAll(DEFAULT_URL_TOKENS);
    private static volatile Set<String> flagKeys = normalizeAll(DEFAULT_FLAG_KEYS);
    private static volatile Set<String> statusKeys = normalizeAll(DEFAULT_STATUS_KEYS);
    private static volatile Set<String> expiryKeys = normalizeAll(DEFAULT_EXPIRY_KEYS);
    private static volatile Set<String> remoteKeys = normalizeAll(DEFAULT_REMOTE_KEYS);
    private static volatile Set<String> deadStatuses = normalizeAll(DEFAULT_DEAD_STATUSES);

    private static final AtomicLong lastVerificationRequestAt = new AtomicLong(0L);
    private static final AtomicInteger observedRequests = new AtomicInteger();
    private static final AtomicInteger rewrittenPayloads = new AtomicInteger();
    private static final AtomicInteger remoteOverrides = new AtomicInteger();

    private VerificationRuntime() { }

    /** Applies the policy string injected at startup. Malformed input keeps the defaults. */
    public static void configure(String policyJson) {
        try {
            Map<String, Object> policy = VerificationJson.readFlatObject(policyJson);
            if (policy == null || policy.isEmpty()) {
                log("configure: ignoring unreadable policy");
                return;
            }
            if (policy.containsKey("enabled")) enabled = bool(policy.get("enabled"), enabled);
            if (policy.containsKey("rewrite")) rewritePayloads = bool(policy.get("rewrite"), rewritePayloads);
            if (policy.containsKey("requireRequest")) {
                requireRecentRequest = bool(policy.get("requireRequest"), requireRecentRequest);
            }
            if (policy.containsKey("expiryDays")) {
                long days = number(policy.get("expiryDays"), 3650L);
                expiryEpochMillis = now() + Math.max(1L, Math.min(days, 36500L)) * DAY_MILLIS;
            }
            if (policy.containsKey("expirySeconds")) {
                expiryInSeconds = bool(policy.get("expirySeconds"), expiryInSeconds);
            }
            if (policy.containsKey("activeStatus")) {
                String value = string(policy.get("activeStatus"));
                if (value != null && !value.isEmpty()) activeStatus = value;
            }
            Set<String> tokens = normalizeList(policy.get("urlTokens"));
            if (!tokens.isEmpty()) urlTokens = tokens;
            Set<String> flags = normalizeList(policy.get("flagKeys"));
            if (!flags.isEmpty()) flagKeys = flags;
            Set<String> statuses = normalizeList(policy.get("statusKeys"));
            if (!statuses.isEmpty()) statusKeys = statuses;
            Set<String> expiries = normalizeList(policy.get("expiryKeys"));
            if (!expiries.isEmpty()) expiryKeys = expiries;
            Set<String> remote = normalizeList(policy.get("remoteKeys"));
            if (!remote.isEmpty()) remoteKeys = remote;
            Set<String> dead = normalizeList(policy.get("deadStatuses"));
            if (!dead.isEmpty()) deadStatuses = dead;
            log("configure: enabled=" + enabled + " rewrite=" + rewritePayloads +
                    " urls=" + urlTokens.size() + " flags=" + flagKeys.size() +
                    " remote=" + remoteKeys.size());
        } catch (Throwable error) {
            log("configure failed: " + error);
        }
    }

    /** Records a request URL; matching URLs mark a verification call as recently seen. */
    public static void observeUrl(String url) {
        try {
            if (url == null || url.isEmpty()) return;
            int http = url.indexOf("://");
            String probe = (http > 0 ? url.substring(http + 3) : url).toLowerCase(Locale.ROOT);
            if (probe.length() > 2048) probe = probe.substring(0, 2048);
            observedRequests.incrementAndGet();
            for (String token : urlTokens) {
                if (probe.contains(token)) {
                    lastVerificationRequestAt.set(now());
                    log("verification request observed: " + trimUrl(url));
                    return;
                }
            }
        } catch (Throwable error) {
            log("observeUrl failed: " + error);
        }
    }

    /**
     * Rewrites a JSON payload so receipt/entitlement fields report a purchased state.
     *
     * @return the rewritten payload, or the original instance when no rule applies.
     */
    public static String rewritePayload(String json) {
        try {
            if (!enabled || !rewritePayloads) return json;
            if (json == null) return null;
            int length = json.length();
            if (length < 4 || length > MAX_PAYLOAD_BYTES) return json;
            char first = firstStructuralChar(json);
            if (first != '{' && first != '[') return json;
            if (requireRecentRequest && !recentVerificationRequest()) return json;
            String lower = json.toLowerCase(Locale.ROOT);
            if (!looksLikeVerificationPayload(lower)) return json;
            String rewritten = VerificationJson.rewrite(json, rules());
            if (rewritten == null) return json;
            int count = rewrittenPayloads.incrementAndGet();
            if (count <= 8) log("rewrote verification payload (" + count + ")");
            return rewritten;
        } catch (Throwable error) {
            log("rewritePayload failed: " + error);
            return json;
        }
    }

    /** True when a server-driven boolean flag names an entitlement this patch unlocks. */
    public static boolean isPremiumRemoteKey(String key) {
        try {
            if (!enabled || key == null || key.isEmpty()) return false;
            String normalized = VerificationJson.normalize(key);
            if (!containsAny(normalized)) return false;
            remoteOverrides.incrementAndGet();
            log("remote config entitlement forced: " + key);
            return true;
        } catch (Throwable error) {
            log("isPremiumRemoteKey failed: " + error);
            return false;
        }
    }

    /** Diagnostics for the patch log and the overlay log view. */
    public static String describe() {
        return "verifyRuntime(v1) enabled=" + enabled
                + " rewrite=" + rewritePayloads
                + " requests=" + observedRequests.get()
                + " rewrites=" + rewrittenPayloads.get()
                + " remote=" + remoteOverrides.get()
                + " lastRequest=" + (recentVerificationRequest() ? "recent" : "none");
    }

    private static VerificationJson.Rules rules() {
        return new VerificationJson.Rules(
                flagKeys,
                statusKeys,
                expiryKeys,
                deadStatuses,
                activeStatus,
                expiryEpochMillis,
                expiryInSeconds);
    }

    private static boolean containsAny(String normalized) {
        for (String key : remoteKeys) {
            if (normalized.equals(key)) return true;
        }
        // Server-driven paywalls name their flags after the feature, not the SDK.
        return normalized.contains("premium") || normalized.contains("paywall")
                || normalized.contains("subscription") || normalized.contains("entitlement");
    }

    private static boolean looksLikeVerificationPayload(String lower) {
        return lower.contains("verified") || lower.contains("entitlement")
                || lower.contains("receipt") || lower.contains("purchasetoken")
                || lower.contains("ispremium") || lower.contains("\"premium\"")
                || lower.contains("\"subscribed\"") || lower.contains("\"ispurchased\"")
                || lower.contains("\"license\"") || lower.contains("\"valid\"")
                || recentVerificationRequest();
    }

    private static boolean recentVerificationRequest() {
        long last = lastVerificationRequestAt.get();
        if (last == 0L) return false;
        return now() - last <= REQUEST_MEMORY_MILLIS;
    }

    private static char firstStructuralChar(String value) {
        for (int i = 0; i < value.length() && i < 64; i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') continue;
            return c;
        }
        return 0;
    }

    private static String trimUrl(String url) {
        int query = url.indexOf('?');
        String trimmed = query > 0 ? url.substring(0, query) : url;
        return trimmed.length() > 120 ? trimmed.substring(0, 120) + "…" : trimmed;
    }

    private static Set<String> normalizeAll(String[] values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = VerificationJson.normalize(value);
            if (!normalized.isEmpty()) out.add(normalized);
        }
        return Collections.unmodifiableSet(out);
    }

    private static Set<String> normalizeList(Object value) {
        if (!(value instanceof List)) return Collections.emptySet();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object entry : (List<?>) value) {
            String normalized = VerificationJson.normalize(entry);
            if (!normalized.isEmpty()) out.add(normalized);
        }
        return Collections.unmodifiableSet(out);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            String text = ((String) value).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(text) || "1".equals(text)) return true;
            if ("false".equals(text) || "0".equals(text)) return false;
        }
        if (value instanceof Long) return (Long) value != 0L;
        return fallback;
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Boolean) return ((Boolean) value) ? 1L : 0L;
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException bad) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void log(String message) {
        try {
            Log.d(TAG, message);
        } catch (Throwable ignored) {
            // Unit tests and stripped builds have no Log implementation.
        }
    }
}
