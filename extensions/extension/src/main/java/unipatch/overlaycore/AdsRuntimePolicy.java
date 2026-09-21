package unipatch.overlaycore;

import android.content.Context;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;
import java.util.regex.Pattern;
import unipatch.overlaycore.modules.OverlaySessionState;

/** Session-local policy shared by Control App Ads and overlay runtime modules. */
public final class AdsRuntimePolicy {
    public static final int MODULE_BLOCK_ADS = 1;
    public static final int MODULE_HOSTS = 2;
    private static final int ALL_BLOCKED_FORMATS = 63;

    private static boolean integrated;
    private static int modules;
    private static int blockedFormats;
    private static boolean hostsEnabled;
    private static boolean hostsAllowed;
    private static boolean wildcardHosts;
    private static final Set<String> hosts = new HashSet<>();
    private static Context managerContext;
    private static boolean managerPersistence;

    private AdsRuntimePolicy() { }

    /** Config format: version|moduleMask|blockedFormats|hostsEnabled|wildcard|hosts|hostsAllowed. */
    public static synchronized void configure(String encoded) {
        // The bridge can be reused by recreated Activities. Clear only Ads Control's saved
        // overlay values so a new patch policy cannot inherit checkbox state from an older app
        // process/session.
        OverlaySessionState.clearModule("adsRuntimeBlockAds");
        integrated = false;
        modules = 0;
        blockedFormats = 0;
        hostsEnabled = false;
        hostsAllowed = false;
        wildcardHosts = false;
        hosts.clear();
        if (encoded == null) return;
        String[] values = encoded.split("\\|", -1);
        if (values.length < 7 || !"2".equals(values[0])) return;
        try {
            int parsedModules = Integer.parseInt(values[1]);
            int parsedBlockedFormats = Integer.parseInt(values[2]);
            if (parsedModules < 0 || (parsedModules & ~3) != 0 ||
                    parsedBlockedFormats < 0 || (parsedBlockedFormats & ~63) != 0) return;
            if (!isBooleanField(values[3]) || !isBooleanField(values[4]) ||
                    !isBooleanField(values[6])) return;
            modules = parsedModules;
            blockedFormats = parsedBlockedFormats;
            hostsEnabled = "1".equals(values[3]);
            wildcardHosts = "1".equals(values[4]);
            hostsAllowed = "1".equals(values[6]);
            for (String host : values[5].split(",")) {
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
    public static synchronized boolean shouldBlockRewarded() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 16) != 0; }
    public static synchronized boolean shouldBlockNative() { return hasModule(MODULE_BLOCK_ADS) && (blockedFormats & 32) != 0; }

    public static synchronized void configureManager(Context context, boolean persistChanges) {
        managerContext = context == null ? null : context.getApplicationContext();
        managerPersistence = persistChanges;
    }

    /** Applies only values explicitly supplied by UniManager; malformed or missing values are ignored. */
    public static synchronized void applyManagedConfiguration(String encoded) {
        if (encoded == null || encoded.isEmpty()) return;
        try {
            JSONObject values = new JSONObject(encoded);
            if (values.has("block_ads")) blockedFormats = values.optBoolean("block_ads") ? ALL_BLOCKED_FORMATS : 0;
            if (values.has("block_hosts")) hostsEnabled = values.optBoolean("block_hosts");
        } catch (org.json.JSONException ignored) {
            // Manager data is an optional override. The embedded patch-time values remain active.
        }
    }

    public static synchronized void setBlockedFormats(int value) { blockedFormats = value; persistIfEnabled(); }
    public static synchronized int blockedFormats() { return blockedFormats; }
    public static synchronized void setHostsEnabled(boolean enabled) { hostsEnabled = enabled; persistIfEnabled(); }
    public static synchronized boolean hostsEnabled() { return hostsEnabled; }

    public static synchronized String managerConfigurationJson() {
        try {
            JSONObject values = new JSONObject();
            values.put("block_ads", blockedFormats != 0);
            values.put("block_hosts", hostsEnabled);
            return values.toString();
        } catch (org.json.JSONException ignored) {
            return "{}";
        }
    }

    private static void persistIfEnabled() {
        if (!managerPersistence || managerContext == null) return;
        try {
            JSONObject values = new JSONObject();
            values.put("block_ads", blockedFormats != 0);
            values.put("block_hosts", hostsEnabled);
            UniManagerBridge.update(managerContext, managerContext.getPackageName(), values.toString());
        } catch (org.json.JSONException ignored) { }
    }

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
