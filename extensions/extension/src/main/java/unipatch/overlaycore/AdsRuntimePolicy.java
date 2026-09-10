package unipatch.overlaycore;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static boolean wildcardHosts;
    private static final Set<String> hosts = new HashSet<>();

    private AdsRuntimePolicy() { }

    /** Config format: version|moduleMask|blockedFormats|skip|grant|fake|hostsEnabled|wildcard|hosts. */
    public static synchronized void configure(String encoded) {
        integrated = false;
        modules = 0;
        blockedFormats = 0;
        skipRewarded = false;
        grantReward = false;
        fakeAvailability = false;
        hostsEnabled = false;
        wildcardHosts = false;
        hosts.clear();
        if (encoded == null) return;
        String[] values = encoded.split("\\|", -1);
        if (values.length < 9 || !"1".equals(values[0])) return;
        try {
            modules = Integer.parseInt(values[1]);
            blockedFormats = Integer.parseInt(values[2]);
            skipRewarded = "1".equals(values[3]);
            grantReward = "1".equals(values[4]);
            fakeAvailability = "1".equals(values[5]);
            hostsEnabled = "1".equals(values[6]);
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

    public static synchronized boolean isIntegrated() { return integrated; }
    public static synchronized boolean hasModule(int module) { return integrated && (modules & module) != 0; }
    public static synchronized boolean hasAnyModule() { return integrated && modules != 0; }
    public static synchronized boolean shouldBlockInterstitials() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 1) != 0; }
    public static synchronized boolean shouldBlockBanners() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 2) != 0; }
    public static synchronized boolean shouldBlockAppOpen() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 4) != 0; }
    public static synchronized boolean shouldBlockMrec() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 8) != 0; }
    public static synchronized boolean shouldBlockRewarded() {
        return (hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 16) != 0) ||
                shouldSkipRewarded();
    }
    public static synchronized boolean shouldBlockNative() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 32) != 0; }
    public static synchronized boolean shouldSkipRewarded() { return hasModule(MODULE_REWARDS) && skipRewarded; }
    public static synchronized boolean shouldGrantReward() { return hasModule(MODULE_REWARDS) && grantReward; }
    public static synchronized boolean shouldFakeRewardAvailability() { return hasModule(MODULE_REWARDS) && fakeAvailability; }

    public static synchronized void setBlockedFormats(int value) { blockedFormats = value; }
    public static synchronized int blockedFormats() { return blockedFormats; }
    public static synchronized void setRewardPolicy(boolean skip, boolean grant, boolean fake) {
        skipRewarded = skip; grantReward = grant; fakeAvailability = fake;
    }
    public static synchronized void setHostsEnabled(boolean enabled) { hostsEnabled = enabled; }
    public static synchronized boolean hostsEnabled() { return hostsEnabled; }

    /** Returns the original URL or the loopback replacement according to the current policy. */
    public static synchronized String rewriteHost(String value) {
        if (!hostsEnabled || value == null) return value;
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
