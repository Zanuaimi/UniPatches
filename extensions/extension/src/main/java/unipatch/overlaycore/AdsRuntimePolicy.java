package unipatch.overlaycore;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import unipatch.overlaycore.modules.OverlaySessionState;

/** Session-local policy shared by Control App Ads and overlay runtime modules. */
public final class AdsRuntimePolicy {
    public static final int MODULE_BLOCK_ADS = 1;
    public static final int MODULE_REWARDS = 2;
    public static final int MODULE_HOSTS = 4;

    private static boolean integrated;
    private static int modules;
    private static int blockedFormats;
    private static boolean skipRewarded;
    private static boolean grantReward;
    private static boolean fakeAvailability;
    private static boolean hostsEnabled;
    private static boolean hostsAllowed;
    private static boolean wildcardHosts;
    private static final Set<String> hosts = new HashSet<>();
    private static final Map<String, Integer> instantRewardRequests = new HashMap<>();
    private static final Map<String, Integer> armedInstantRewards = new HashMap<>();

    private AdsRuntimePolicy() { }

    /** Config format: version|moduleMask|blockedFormats|skip|grant|fake|hostsEnabled|wildcard|hosts[|hostsAllowed]. */
    public static synchronized void configure(String encoded) {
        // The bridge can be reused by recreated Activities. Clear only Ads Control's saved
        // overlay values so a new patch policy cannot inherit checkbox state from an older app
        // process/session.
        OverlaySessionState.clearModule("adsRuntimeBlockAds");
        OverlaySessionState.clearModule("adsRuntimeRewards");
        integrated = false;
        modules = 0;
        blockedFormats = 0;
        skipRewarded = false;
        grantReward = false;
        fakeAvailability = false;
        hostsEnabled = false;
        hostsAllowed = false;
        wildcardHosts = false;
        hosts.clear();
        instantRewardRequests.clear();
        armedInstantRewards.clear();
        if (encoded == null) return;
        String[] values = encoded.split("\\|", -1);
        if (values.length < 9 || !"1".equals(values[0])) return;
        try {
            int parsedModules = Integer.parseInt(values[1]);
            int parsedBlockedFormats = Integer.parseInt(values[2]);
            if (parsedModules < 0 || (parsedModules & ~7) != 0 ||
                    parsedBlockedFormats < 0 || (parsedBlockedFormats & ~63) != 0) return;
            if (!isBooleanField(values[3]) || !isBooleanField(values[4]) ||
                    !isBooleanField(values[5]) || !isBooleanField(values[6]) ||
                    !isBooleanField(values[7])) return;
            // Field 9 is an optional capability boundary. The original nine-field payload
            // remains valid and treats its initial host state as the capability.
            if (values.length >= 10 && !isBooleanField(values[9])) return;
            modules = parsedModules;
            blockedFormats = parsedBlockedFormats;
            skipRewarded = "1".equals(values[3]);
            grantReward = "1".equals(values[4]);
            fakeAvailability = "1".equals(values[5]);
            hostsEnabled = "1".equals(values[6]);
            hostsAllowed = values.length >= 10 ? "1".equals(values[9]) : hostsEnabled;
            wildcardHosts = "1".equals(values[7]);
            for (String host : values[8].split(",")) {
                String normalized = normalizeHost(host);
                if (!normalized.isEmpty()) hosts.add(normalized);
            }
            integrated = true;
        } catch (RuntimeException ignored) {
            integrated = false;
        }
    }

    private static boolean isBooleanField(String value) {
        return "0".equals(value) || "1".equals(value);
    }

    public static synchronized boolean isIntegrated() { return integrated; }
    public static synchronized boolean hasModule(int module) { return integrated && (modules & module) != 0; }
    public static synchronized boolean hasAnyModule() { return integrated && modules != 0; }
    public static synchronized boolean shouldBlockInterstitials() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 1) != 0; }
    public static synchronized boolean shouldBlockBanners() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 2) != 0; }
    public static synchronized boolean shouldBlockAppOpen() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 4) != 0; }
    public static synchronized boolean shouldBlockMrec() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 8) != 0; }
    public static synchronized boolean shouldBlockRewarded() {
        return shouldBlockRewardedFormat() || shouldSkipRewarded();
    }
    /** Format blocking only. Rewarded readiness must not be disabled merely because skipping is enabled. */
    public static synchronized boolean shouldBlockRewardedFormat() {
        return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 16) != 0;
    }
    public static synchronized boolean shouldBlockNative() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 32) != 0; }
    public static synchronized boolean shouldSkipRewarded() { return hasModule(MODULE_REWARDS) && skipRewarded; }
    public static synchronized boolean shouldGrantReward() { return hasModule(MODULE_REWARDS) && grantReward; }
    public static synchronized boolean shouldFakeRewardAvailability() { return hasModule(MODULE_REWARDS) && fakeAvailability; }

    /** Starts a one-shot native or Unity request that received an immediate reward. */
    public static synchronized void beginInstantReward(String requestId) {
        if (!hasModule(MODULE_REWARDS) || !grantReward || requestId == null || requestId.isEmpty()) return;
        if (instantRewardRequests.size() >= 32) instantRewardRequests.clear();
        instantRewardRequests.put(requestId, count(instantRewardRequests, requestId) + 1);
    }

    /** Arms suppression only after the synthetic immediate callback has been delivered. */
    public static synchronized void armInstantReward(String requestId) {
        if (requestId == null || requestId.isEmpty()) return;
        int pending = count(instantRewardRequests, requestId);
        if (pending <= 0) return;
        decrement(instantRewardRequests, requestId);
        if (armedInstantRewards.size() >= 32) armedInstantRewards.clear();
        armedInstantRewards.put(requestId, count(armedInstantRewards, requestId) + 1);
    }

    /** Consumes one later native reward callback for the matching request. */
    public static synchronized boolean consumeInstantNativeReward(Object ad) {
        if (ad == null || !hasModule(MODULE_REWARDS)) return false;
        try {
            Object value = ad.getClass().getMethod("getAdUnitId").invoke(ad);
            return consumeArmed(value instanceof String ? (String) value : null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /** Consumes one later Unity reward event for the matching ad unit. */
    public static synchronized boolean consumeInstantUnityReward(Object event) {
        if (!(event instanceof org.json.JSONObject) || !hasModule(MODULE_REWARDS)) return false;
        org.json.JSONObject json = (org.json.JSONObject) event;
        if (!"OnRewardedAdReceivedRewardEvent".equals(json.optString("name"))) return false;
        return consumeArmed(json.optString("adUnitId", null));
    }

    private static int count(Map<String, Integer> values, String key) {
        Integer value = values.get(key);
        return value == null ? 0 : value;
    }

    private static void decrement(Map<String, Integer> values, String key) {
        int next = count(values, key) - 1;
        if (next <= 0) values.remove(key); else values.put(key, next);
    }

    private static boolean consumeArmed(String requestId) {
        if (requestId == null || requestId.isEmpty()) return false;
        int armed = count(armedInstantRewards, requestId);
        if (armed <= 0) return false;
        decrement(armedInstantRewards, requestId);
        return true;
    }

    public static synchronized void setBlockedFormats(int value) { blockedFormats = value; }
    public static synchronized int blockedFormats() { return blockedFormats; }
    public static synchronized void setRewardPolicy(boolean skip, boolean grant, boolean fake) {
        skipRewarded = skip; grantReward = grant; fakeAvailability = fake;
    }
    public static synchronized void setHostsEnabled(boolean enabled) { hostsEnabled = enabled; }
    public static synchronized boolean hostsEnabled() { return hostsEnabled; }

    /** Returns the original URL or the loopback replacement according to the current policy. */
    public static synchronized String rewriteHost(String value) {
        if (!hostsAllowed || !hostsEnabled || value == null) return value;
        String host = extractHost(value);
        if (host.isEmpty()) return value;
        for (String blocked : hosts) {
            if (host.equals(blocked) || (wildcardHosts && host.endsWith("." + blocked))) {
                return value.replaceAll("(?i)" + Pattern.quote(host), "0.0.0.0");
            }
        }
        return value;
    }

    private static String extractHost(String value) {
        String candidate = value;
        int scheme = candidate.indexOf("://");
        if (scheme >= 0) candidate = candidate.substring(scheme + 3);
        int slash = candidate.indexOf('/');
        if (slash >= 0) candidate = candidate.substring(0, slash);
        int colon = candidate.indexOf(':');
        if (colon >= 0) candidate = candidate.substring(0, colon);
        return normalizeHost(candidate);
    }

    private static String normalizeHost(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.+", "").replaceFirst("\\.+$", "");
    }
}
