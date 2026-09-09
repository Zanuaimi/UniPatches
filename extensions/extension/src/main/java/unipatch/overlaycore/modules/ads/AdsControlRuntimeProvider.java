package unipatch.overlaycore.modules.ads;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import unipatch.overlaycore.AdsRuntimePolicy;
import unipatch.overlaycore.modules.OverlayActionModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModuleProvider;

/** Runtime controls backed by methods instrumented by Control App Ads. */
public final class AdsControlRuntimeProvider implements OverlayAppSpecificModuleProvider {
    public static final String PROFILE_ID = "adsControlRuntime";

    @Override public String profileId() { return PROFILE_ID; }

    @Override public List<OverlayAppSpecificModule> create(Activity activity) {
        List<OverlayAppSpecificModule> modules = new ArrayList<>();
        if (!AdsRuntimePolicy.isIntegrated()) return modules;
        if (AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_BLOCK_ADS)) modules.add(new BlockAdsModule());
        if (AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_REWARDS)) modules.add(new RewardsModule());
        if (AdsRuntimePolicy.hasModule(AdsRuntimePolicy.MODULE_HOSTS)) modules.add(new HostsModule());
        return modules;
    }

    private static final class BlockAdsModule extends OverlayActionModule {
        private static final String[] labels = {"Interstitials", "Banners", "App-open", "MREC", "Rewarded", "Native"};
        private static final int[] bits = {1, 2, 4, 8, 16, 32};
        @Override public String key() { return "adsRuntimeBlockAds"; }
        @Override public String label() { return "Block Ads"; }
        @Override public String description() { return "Change supported ad-format blocking at runtime. Only SDK methods instrumented by Control App Ads are affected."; }
        @Override public boolean hasSettings() { return true; }
        @Override public boolean hasEnableToggle() { return false; }
        @Override public String actionLabel() { return "Settings"; }
        @Override public String valueText() { return "Blocked: " + selectedFormats(); }
        @Override protected boolean readEnabled(Activity a, int f, int u) { return true; }
        @Override protected void applyEnabled(Activity a, int f, int u) { }
        @Override protected void restoreOriginal(Activity a, int f, int u) { }
        @Override public void showSettings(Activity activity, int background, int textColor, int outline, int accent) {
            boolean[] checked = new boolean[bits.length];
            int current = AdsRuntimePolicy.blockedFormats();
            for (int i = 0; i < bits.length; i++) checked[i] = (current & bits[i]) != 0;
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Block Ads")
                    .setMultiChoiceItems(labels, checked, (d, which, value) -> checked[which] = value)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Apply", (d, which) -> {
                        int next = 0;
                        for (int i = 0; i < bits.length; i++) if (checked[i]) next |= bits[i];
                        AdsRuntimePolicy.setBlockedFormats(next);
                    }).create();
            style(dialog, background, textColor, accent);
            dialog.show();
            style(dialog, background, textColor, accent);
        }
        private String selectedFormats() {
            List<String> values = new ArrayList<>();
            int current = AdsRuntimePolicy.blockedFormats();
            for (int i = 0; i < bits.length; i++) if ((current & bits[i]) != 0) values.add(labels[i]);
            return values.isEmpty() ? "none" : join(values);
        }
    }

    private static final class RewardsModule extends OverlayActionModule {
        private static final String[] labels = {"Skip rewarded ads", "Give rewards", "Fake ad availability"};
        private final boolean[] checked = new boolean[3];
        @Override public String key() { return "adsRuntimeRewards"; }
        @Override public String label() { return "Ads Free Rewards"; }
        @Override public String description() { return "Change supported rewarded-ad policy at runtime. Availability guards are dynamic where matched; reward callbacks remain SDK-specific and may be unavailable."; }
        @Override public boolean hasSettings() { return true; }
        @Override public boolean hasEnableToggle() { return false; }
        @Override public String actionLabel() { return "Settings"; }
        @Override public String valueText() { return "Skip=" + AdsRuntimePolicy.shouldSkipRewarded() + ", reward=" + AdsRuntimePolicy.shouldGrantReward() + ", available=" + AdsRuntimePolicy.shouldFakeRewardAvailability(); }
        @Override protected boolean readEnabled(Activity a, int f, int u) { return true; }
        @Override protected void applyEnabled(Activity a, int f, int u) { }
        @Override protected void restoreOriginal(Activity a, int f, int u) { }
        @Override public void showSettings(Activity activity, int background, int textColor, int outline, int accent) {
            checked[0] = AdsRuntimePolicy.shouldSkipRewarded();
            checked[1] = AdsRuntimePolicy.shouldGrantReward();
            checked[2] = AdsRuntimePolicy.shouldFakeRewardAvailability();
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Ads Free Rewards")
                    .setMultiChoiceItems(labels, checked, (d, which, value) -> checked[which] = value)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Apply", (d, which) -> AdsRuntimePolicy.setRewardPolicy(checked[0], checked[1], checked[2]))
                    .create();
            style(dialog, background, textColor, accent);
            dialog.show();
            style(dialog, background, textColor, accent);
        }
    }

    private static final class HostsModule extends OverlayAppSpecificModule {
        @Override public String key() { return "adsRuntimeHosts"; }
        @Override public String label() { return "Block Ads / Tracking Hosts"; }
        @Override public String description() { return "Enable or disable policy-aware host blocking for literal endpoints instrumented by Control App Ads."; }
        @Override public boolean supports(Activity activity) { return activity != null; }
        @Override protected boolean readEnabled(Activity a, int f, int u) { return AdsRuntimePolicy.hostsEnabled(); }
        @Override protected void applyEnabled(Activity a, int f, int u) { AdsRuntimePolicy.setHostsEnabled(true); }
        @Override protected void restoreOriginal(Activity a, int f, int u) { AdsRuntimePolicy.setHostsEnabled(false); }
    }

    private static void style(AlertDialog dialog, int background, int textColor, int accent) {
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        android.view.View title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title instanceof TextView) ((TextView) title).setTextColor(textColor);
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent);
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent);
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
