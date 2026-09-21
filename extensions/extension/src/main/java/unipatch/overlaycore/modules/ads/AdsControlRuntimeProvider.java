package unipatch.overlaycore.modules.ads;

import android.app.Activity;
import java.util.ArrayList;
import java.util.List;
import unipatch.overlaycore.AdsRuntimePolicy;
import unipatch.overlaycore.modules.OverlayActionModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModuleProvider;
import unipatch.overlaycore.modules.OverlaySessionState;

/** Runtime controls backed by methods instrumented by Ads Block Patch. */
public final class AdsControlRuntimeProvider implements OverlayAppSpecificModuleProvider {
    public static final String PROFILE_ID = "adsControlRuntime";

    @Override public String profileId() { return PROFILE_ID; }

    @Override public List<OverlayAppSpecificModule> create(Activity activity) {
        List<OverlayAppSpecificModule> modules = new ArrayList<>();
        if (!AdsRuntimePolicy.isIntegrated()) return modules;
        if (AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_BLOCK_ADS)) modules.add(new BlockAdsModule());
        if (AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_HOSTS)) modules.add(new HostsModule());
        return modules;
    }

    private static final class BlockAdsModule extends OverlayActionModule {
        private static final String[] labels = {"Interstitials", "Banners", "App-open", "MREC", "Rewarded", "Native"};
        private static final int[] bits = {1, 2, 4, 8, 16, 32};
        @Override public String key() { return "adsRuntimeBlockAds"; }
        @Override public String label() { return "Block Ads"; }
        @Override public String description() { return "Change supported ad-format blocking at runtime. Only SDK methods instrumented by Ads Block Patch are affected. Credits to Nai64 for original No Ads patch."; }
        @Override public boolean hasSettings() { return true; }
        @Override public boolean hasEnableToggle() { return false; }
        @Override public boolean hasActionButton() { return false; }
        @Override public String valueText() { return "Blocked: " + selectedFormats(); }
        @Override protected boolean readEnabled(Activity a, int f, int u) { return true; }
        @Override protected void applyEnabled(Activity a, int f, int u) { }
        @Override protected void restoreOriginal(Activity a, int f, int u) { }
        @Override public String[] settingsChoices() { return labels.clone(); }
        @Override public String[] settingsDescriptions() {
            return new String[] {
                "Enabled: block supported interstitial ads. Disabled: keep their original behavior.",
                "Enabled: block supported banner ads. Disabled: keep their original behavior.",
                "Enabled: block supported app-open ads. Disabled: keep their original behavior.",
                "Enabled: block supported MREC ads. Disabled: keep their original behavior.",
                "Enabled: block supported rewarded ads. Disabled: keep the reward flow available.",
                "Enabled: block supported native ads. Disabled: keep their original behavior."
            };
        }
        @Override public boolean[] settingsValues() {
            boolean[] checked = new boolean[bits.length];
            int current = AdsRuntimePolicy.blockedFormats();
            for (int i = 0; i < bits.length; i++) checked[i] = (current & bits[i]) != 0;
            return OverlaySessionState.booleans(key(), "settings", checked);
        }
        @Override public void applySettings(boolean[] checked) {
            OverlaySessionState.putBooleans(key(), "settings", checked);
        }
        @Override public boolean appliesSettingsOnConfirm() { return true; }
        @Override public boolean applySavedSettings(Activity activity) {
            boolean[] checked = OverlaySessionState.booleans(key(), "settings", new boolean[bits.length]);
            int next = 0;
            for (int i = 0; i < bits.length && i < checked.length; i++) if (checked[i]) next |= bits[i];
            AdsRuntimePolicy.setBlockedFormats(next);
            return true;
        }
        private String selectedFormats() {
            List<String> values = new ArrayList<>();
            int current = AdsRuntimePolicy.blockedFormats();
            for (int i = 0; i < bits.length; i++) if ((current & bits[i]) != 0) values.add(labels[i]);
            return values.isEmpty() ? "none" : join(values);
        }
    }

    private static final class HostsModule extends OverlayAppSpecificModule {
        @Override public String key() { return "adsRuntimeHosts"; }
        @Override public String label() { return "Block Ads / Tracking Hosts"; }
        @Override public String description() { return "Enable or disable policy-aware host blocking for literal endpoints instrumented by Ads Block Patch. The initial state follows the Ads patch's Enable Block Ads / Tracking Hosts setting. Credits to Adobo and Entree for the original tracking-hosts approach."; }
        @Override public boolean supports(Activity activity) { return activity != null; }
        @Override protected boolean readEnabled(Activity a, int f, int u) { return AdsRuntimePolicy.hostsEnabled(); }
        @Override protected void applyEnabled(Activity a, int f, int u) { AdsRuntimePolicy.setHostsEnabled(true); }
        @Override protected void restoreOriginal(Activity a, int f, int u) { AdsRuntimePolicy.setHostsEnabled(false); }
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }
}
