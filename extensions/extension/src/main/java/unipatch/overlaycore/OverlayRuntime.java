package unipatch.overlaycore;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;

import unipatch.overlaycore.modules.activity.AppAudioMuteModule;
import unipatch.overlaycore.modules.activity.AppBrightnessModule;
import unipatch.overlaycore.modules.activity.FullscreenModule;
import unipatch.overlaycore.modules.activity.KeepAwakeModule;
import unipatch.overlaycore.modules.activity.RotationModeModule;
import unipatch.overlaycore.modules.activity.ScreenshotsModule;
import unipatch.overlaycore.modules.hook.DisableAnimationsModule;
import unipatch.overlaycore.modules.hook.DisableHapticsModule;
import unipatch.overlaycore.modules.statistic.AppMemoryModule;
import unipatch.overlaycore.modules.statistic.BatteryStatusModule;
import unipatch.overlaycore.modules.statistic.DeviceInformationModule;
import unipatch.overlaycore.modules.statistic.DeviceTemperatureModule;
import unipatch.overlaycore.modules.statistic.FpsModule;
import unipatch.overlaycore.modules.statistic.NetworkStatusModule;
import unipatch.overlaycore.modules.statistic.SessionTimeModule;
import unipatch.overlaycore.modules.statistic.SystemTimeModule;
import unipatch.overlaycore.modules.OverlayActivityModule;
import unipatch.overlaycore.modules.OverlayHookModule;
import unipatch.overlaycore.modules.OverlayModule;
import unipatch.overlaycore.modules.OverlayStatisticModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModuleProvider;
import unipatch.overlaycore.modules.OverlayActionModule;
import unipatch.overlaycore.modules.example.HillClimbRacingExampleProvider;
import unipatch.overlaycore.modules.ads.AdsControlRuntimeProvider;
import unipatch.overlaycore.AdsRuntimePolicy;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Runtime implementation for the UniPatches overlay.
 *
 * This class is compiled into the extension DEX. The patch generator only injects a bridge call;
 * changes to this file therefore do not alter host Activity fields, listener interfaces, or large
 * generated Smali methods. The implementation deliberately uses platform Views only so it can run
 * in ordinary Android apps, Unity/Godot hosts, and game Activities without AppCompat coupling.
 */
public final class OverlayRuntime {
    private static final Map<Activity, Controller> CONTROLLERS = new WeakHashMap<>();
    private static boolean callbacksRegistered;
    private static boolean globallyClosed;
    private static Application installedApplication;
    private static OverlayLifecycle lifecycleCallbacks;
    private static OverlayConfig configuration;
    private static String installedConfigurationPayload;
    private static Boolean keepAwakeState;
    private static Boolean fullscreenState;
    private static Boolean screenshotsState;
    private static final Map<String, Boolean> MODULE_STATES = new java.util.HashMap<>();
    private static final Map<String, Boolean> MONITOR_STATES = new java.util.HashMap<>();
    private static final Map<String, Boolean> HOOK_STATES = new java.util.HashMap<>();
    private static final Map<String, Boolean> APP_SPECIFIC_STATES = new java.util.HashMap<>();
    private static long sessionStartElapsed;
    private static boolean sharedButtonPositionInitialized;
    private static int sharedButtonX;
    private static int sharedButtonY;
    private static Float appBrightnessState;
    private static Integer rotationModeState;
    private static boolean fullyClosedToastShown;
    private static final List<OverlayAppSpecificModuleProvider> APP_SPECIFIC_PROVIDERS = new ArrayList<>();

    static {
        registerAppSpecificProvider(new HillClimbRacingExampleProvider());
        registerAppSpecificProvider(new AdsControlRuntimeProvider());
    }

    private OverlayRuntime() { }

    /** Registers a target-specific module provider. Duplicate profile IDs are ignored. */
    public static synchronized void registerAppSpecificProvider(OverlayAppSpecificModuleProvider provider) {
        if (provider == null) return;
        final String profileId;
        try {
            profileId = provider.profileId();
        } catch (RuntimeException ignored) {
            return;
        }
        if (profileId == null || profileId.trim().isEmpty()) return;
        for (OverlayAppSpecificModuleProvider existing : APP_SPECIFIC_PROVIDERS) {
            try {
                if (profileId.equals(existing.profileId())) return;
            } catch (RuntimeException ignored) {
                // A broken provider must not prevent another provider from registering.
            }
        }
        APP_SPECIFIC_PROVIDERS.add(provider);
    }

    /** Primary entry point, called once from Application.onCreate(). */
    public static synchronized void install(Application application, String encodedConfig) {
        if (application == null || globallyClosed) return;
        if (installedConfigurationPayload != null && !installedConfigurationPayload.equals(encodedConfig)) {
            // Two overlay patches in one APK cannot safely own the same process-wide runtime.
            // Keep the first complete configuration instead of silently replacing a live menu.
            return;
        }
        configuration = OverlayConfig.decode(encodedConfig);
        installedConfigurationPayload = encodedConfig;
        if (sessionStartElapsed == 0) sessionStartElapsed = SystemClock.elapsedRealtime();
        if (!callbacksRegistered) {
            installedApplication = application;
            lifecycleCallbacks = new OverlayLifecycle();
            application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
            callbacksRegistered = true;
        }
    }

    /** Compatibility fallback for APKs where Application.onCreate cannot be resolved. */
    public static synchronized void installActivity(Activity activity, String encodedConfig) {
        if (activity == null) return;
        if (sessionStartElapsed == 0) sessionStartElapsed = SystemClock.elapsedRealtime();
        try {
            Application application = activity.getApplication();
            if (application != null) {
                install(application, encodedConfig);
            } else {
                if (installedConfigurationPayload == null || installedConfigurationPayload.equals(encodedConfig)) {
                    configuration = OverlayConfig.decode(encodedConfig);
                    installedConfigurationPayload = encodedConfig;
                }
            }
        } catch (RuntimeException ignored) {
            if (installedConfigurationPayload == null || installedConfigurationPayload.equals(encodedConfig)) {
                configuration = OverlayConfig.decode(encodedConfig);
                installedConfigurationPayload = encodedConfig;
            }
        }
        showActivity(activity);
    }

    static synchronized void showActivity(Activity activity) {
        if (configuration == null || globallyClosed) return;
        if (isActivityInstallBanned(activity)) return;
        if (activity.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
        Controller existing = CONTROLLERS.get(activity);
        if (existing != null) {
            existing.applyRememberedStates();
            return;
        }
        Controller controller = null;
        try {
            controller = new Controller(activity, configuration);
            CONTROLLERS.put(activity, controller);
            controller.attach();
        } catch (RuntimeException ignored) {
            if (controller != null) controller.detach();
            // Never let overlay setup failure crash the host application.
            CONTROLLERS.remove(activity);
        }
    }

    private static boolean isActivityInstallBanned(Activity activity) {
        String className = activity.getClass().getName();
        String banlist = configuration == null ? "" : configuration.activityInstallBanlist;
        if (banlist == null || banlist.trim().isEmpty()) return false;
        if ("none".equalsIgnoreCase(banlist.trim())) return false;
        for (String rawEntry : banlist.split("[,;\\r\\n]+")) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) continue;
            boolean wildcard = entry.endsWith("*");
            String value = wildcard ? entry.substring(0, entry.length() - 1).trim() : entry;
            if (value.isEmpty()) continue;
            if ((wildcard && className.startsWith(value))
                    || (!wildcard && (className.equals(value) || className.startsWith(value + ".")))) {
                return true;
            }
        }
        return false;
    }

    static synchronized void removeActivity(Activity activity) {
        Controller controller = CONTROLLERS.remove(activity);
        if (controller != null) controller.detach();
    }

    static synchronized void pauseActivity(Activity activity) {
        Controller controller = CONTROLLERS.get(activity);
        if (controller != null) controller.pause();
    }

    private static synchronized void closeGlobally() {
        if (globallyClosed) return;
        globallyClosed = true;
        for (Controller controller : new ArrayList<>(CONTROLLERS.values())) {
            if (controller != null) controller.detach();
        }
        CONTROLLERS.clear();
        MODULE_STATES.clear();
        MONITOR_STATES.clear();
        HOOK_STATES.clear();
        APP_SPECIFIC_STATES.clear();
        if (installedApplication != null && lifecycleCallbacks != null) {
            try { installedApplication.unregisterActivityLifecycleCallbacks(lifecycleCallbacks); }
            catch (RuntimeException ignored) { }
        }
        installedApplication = null;
        lifecycleCallbacks = null;
        callbacksRegistered = false;
        configuration = null;
        installedConfigurationPayload = null;
        sessionStartElapsed = 0;
        sharedButtonPositionInitialized = false;
        appBrightnessState = null;
        rotationModeState = null;
    }

    private static Boolean rememberedState(String key) {
        if ("keepAwake".equals(key)) return keepAwakeState;
        if ("fullscreen".equals(key)) return fullscreenState;
        if ("screenshots".equals(key)) return screenshotsState;
        return null;
    }

    private static void rememberState(String key, boolean enabled) {
        if ("keepAwake".equals(key)) keepAwakeState = enabled;
        else if ("fullscreen".equals(key)) fullscreenState = enabled;
        else if ("screenshots".equals(key)) screenshotsState = enabled;
    }

    private static Boolean rememberedModuleState(String key) { return MODULE_STATES.get(key); }
    private static void rememberModuleState(String key, boolean enabled) { MODULE_STATES.put(key, enabled); }

    /** Owns all views and state for exactly one Activity. */
    private static final class Controller {
        private enum MenuState { CLOSED, OPENING, OPEN, CLOSING }
        private final Activity activity;
        private final Context overlayContext;
        private final OverlayConfig config;
        private final FrameLayout root;
        private final TextView floatingButton;
        private final FrameLayout menuLayer;
        private final View menuScrim;
        private final LinearLayout panel;
        private final OverlayViews.AnimatedOutline menuOutline;
        private final FrameLayout confirmationLayer;
        private final View brightnessDimLayer;
        private final List<OverlayActivityModule> activityModules = new ArrayList<>();
        private final List<OverlayHookModule> hookModules = new ArrayList<>();
        private final List<OverlayAppSpecificModule> appSpecificModules = new ArrayList<>();
        private final List<OverlayStatisticModule> statistics = new ArrayList<>();
        private final Map<String, CheckBox> featureControls = new java.util.HashMap<>();
        private final Map<String, List<TextView>> statisticMonitors = new java.util.HashMap<>();
        private final int monitorWidth;
        private final int monitorHeight;
        private final int originalWindowFlags;
        private final int originalSystemUi;
        private boolean menuVisible;
        private MenuState menuState = MenuState.CLOSED;
        private boolean fullyClosed;
        private boolean attached;
        private boolean detached;
        private float downX;
        private float downY;
        private float startX;
        private float startY;
        private boolean dragged;
        private String pendingInlineSectionLabel;
        private final Runnable dragVisibilityFade;

        Controller(Activity activity, OverlayConfig config) {
            this.activity = activity;
            int overlayTheme = android.os.Build.VERSION.SDK_INT >= 21
                    ? android.R.style.Theme_Material_Light_NoActionBar
                    : android.R.style.Theme_Holo_Light_NoActionBar;
            overlayContext = new ContextThemeWrapper(activity, overlayTheme);
            this.config = config;
            Window window = activity.getWindow();
            originalWindowFlags = window.getAttributes().flags;
            originalSystemUi = window.getDecorView().getSystemUiVisibility();
            root = new FrameLayout(overlayContext);
            root.setClipChildren(false);
            root.setFocusableInTouchMode(true);
            root.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP && menuVisible) {
                    closeMenu();
                    return true;
                }
                return false;
            });
            brightnessDimLayer = new View(overlayContext);
            brightnessDimLayer.setBackgroundColor(Color.BLACK);
            brightnessDimLayer.setClickable(false);
            brightnessDimLayer.setFocusable(false);
            brightnessDimLayer.setAlpha(0f);
            brightnessDimLayer.setOnTouchListener((v, event) -> false);
            root.addView(brightnessDimLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Paint monitorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            monitorPaint.setTextSize(dp(12));
            Paint.FontMetrics metrics = monitorPaint.getFontMetrics();
            String timeExample = "24".equals(config.timeFormat) ? "ST: 00:00" : "ST: 00:00 AM";
            String temperatureSuffix = "fahrenheit".equals(config.temperatureFormat) ? "F"
                    : ("kelvin".equals(config.temperatureFormat) ? "K" : "C");
            String[] monitorExamples = {
                    timeExample, "FPS: ~999", "AST: 00:00:00", "BAT: 100%", "MEM: 9999 MB",
                    "↓IT: 999.9 MB", "↑OT: 999.9 MB", "TMP: 99.9 " + temperatureSuffix,
            };
            float widestMonitor = 0f;
            for (String example : monitorExamples) widestMonitor = Math.max(widestMonitor, monitorPaint.measureText(example));
            monitorWidth = Math.round(((int) Math.ceil(widestMonitor) + dp(24)) * config.monitorScale);
            monitorHeight = Math.round(((int) Math.ceil(metrics.bottom - metrics.top) + dp(12)) * config.monitorScale);
            floatingButton = createFloatingButton();
            dragVisibilityFade = () -> {
                if (!detached && !menuVisible) {
                    floatingButton.animate().alpha(config.opacity).setDuration(180).start();
                    setMonitorAlpha(config.opacity);
                }
            };
            menuLayer = new FrameLayout(overlayContext);
            menuScrim = createMenuScrim();
            menuOutline = "static".equals(config.menuOutlineAnimation) || config.outlineAnimationSpeed == 0 ? null
                    : OverlayViews.animatedOutline(
                            config.background,
                            config.outline,
                            config.menuTextColor1,
                            "vertical".equals(config.menuOutlineAnimation),
                            "rainbow".equals(config.menuOutlineAnimation),
                            config.outlineWidth,
                            !"square".equals(config.menuCorners),
                            config.outlineAnimationSpeed);
            panel = createMenuPanel();
            confirmationLayer = createConfirmationLayer();
        }


        void attach() {
            if (attached || detached) return;
            try {
                root.addView(floatingButton, buttonParams());
                root.addView(menuLayer, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                menuLayer.addView(menuScrim, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                menuLayer.addView(panel, panel.getLayoutParams());
                root.addView(confirmationLayer, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                menuLayer.setVisibility(View.GONE);
                confirmationLayer.setVisibility(View.GONE);
                activity.addContentView(root, contentLayoutParams());
                attached = true;
                root.post(this::updateMonitorLayout);
            } catch (RuntimeException failure) {
                removeRoot();
                throw failure;
            }
        }

        void detach() {
            if (detached) return;
            detached = true;
            root.removeCallbacks(dragVisibilityFade);
            if (menuOutline != null) menuOutline.stop();
            for (OverlayStatisticModule module : statistics) module.stopSafely();
            restoreActivityModules();
            removeRoot();
        }

        void pause() {
            if (detached) return;
            root.removeCallbacks(dragVisibilityFade);
            menuVisible = false;
            menuState = MenuState.CLOSED;
            menuScrim.animate().cancel();
            panel.animate().cancel();
            if (menuOutline != null) menuOutline.stop();
            menuLayer.setVisibility(View.GONE);
            confirmationLayer.setVisibility(View.GONE);
            floatingButton.setAlpha(config.opacity);
            for (OverlayStatisticModule module : statistics) {
                module.setMenuVisible(false);
                module.setEnabled(module.isEnabled(), false);
            }
            setMonitorAlphaImmediate(config.opacity);
        }

        private void removeRoot() {
            try {
                if (root.getParent() instanceof ViewGroup) {
                    ((ViewGroup) root.getParent()).removeView(root);
                }
            } catch (RuntimeException ignored) {
                // Cleanup must not propagate a host-specific view hierarchy failure.
            }
            attached = false;
        }

        private FrameLayout.LayoutParams contentLayoutParams() {
            return new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        private void restoreActivityModules() {
            for (OverlayActivityModule feature : activityModules) {
                try {
                    feature.restore(activity, originalWindowFlags, originalSystemUi);
                } catch (RuntimeException ignored) {
                    // A single incompatible Activity module must not prevent other modules or host cleanup.
                }
            }
            for (OverlayHookModule hook : hookModules) {
                try {
                    hook.restore(activity, originalWindowFlags, originalSystemUi);
                } catch (RuntimeException ignored) {
                    // Hook cleanup is independent from Activity module cleanup.
                }
            }
            for (OverlayAppSpecificModule module : appSpecificModules) {
                try {
                    module.restore(activity, originalWindowFlags, originalSystemUi);
                } catch (RuntimeException ignored) {
                    // App-specific cleanup is independent from universal module cleanup.
                }
            }
            activityModules.clear();
            hookModules.clear();
            appSpecificModules.clear();
            statistics.clear();
            featureControls.clear();
            statisticMonitors.clear();
        }

        private FrameLayout.LayoutParams buttonParams() {
            int size = dp(config.buttonSize);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
            int margin = dp(16);
            if (sharedButtonPositionInitialized) {
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.setMargins(sharedButtonX, sharedButtonY, 0, 0);
            } else {
                params.gravity = config.gravity;
                params.setMargins(margin, margin, margin, margin);
            }
            return params;
        }

        private TextView createFloatingButton() {
            TextView button = new TextView(overlayContext);
            button.setTextColor(config.buttonTextColor);
            button.setGravity(Gravity.CENTER);
            button.setAlpha(config.opacity);
            button.setContentDescription(config.buttonText);
            Bitmap customIcon = config.iconType.equals("image") ? decodeCustomIcon(config.customIconImage) : null;
            if (customIcon != null) {
                button.setText("");
                BitmapDrawable image = new BitmapDrawable(overlayContext.getResources(), fitCustomIcon(customIcon));
                image.setGravity(Gravity.CENTER);
                image.setAntiAlias(true);
                button.setBackground(image);
            } else {
                // A supplied image owns icon rendering completely. Do not silently draw a legacy
                // symbol behind an invalid image payload, because that makes the selected icon
                // mode ambiguous.
                if (config.iconType.equals("image")) {
                    button.setText("");
                    button.setBackground(OverlayViews.gradientBackground(
                            config.buttonBackground, config.gradientBackground ? config.iconBackground2 : config.buttonBackground,
                            config.iconGradientAngle, Color.TRANSPARENT, 0, config.shape == 1));
                } else if ("parts".equals(config.iconStyle)) {
                    button.setText("");
                    button.setBackground(legacyIconDrawable());
                    button.setContentDescription("Multi-parts overlay icon");
                } else {
                    button.setText(config.buttonText);
                    button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, config.iconTextSize);
                    button.setTypeface(Typeface.DEFAULT, config.iconBold ? Typeface.BOLD : Typeface.NORMAL);
                    button.setBackground(OverlayViews.gradientBackground(
                            config.buttonBackground,
                            config.gradientBackground ? config.iconBackground2 : config.buttonBackground,
                            config.iconGradientAngle,
                            config.iconOutline ? config.iconOutlineColor : Color.TRANSPARENT,
                            config.iconOutline ? config.iconOutlineWidth : 0, config.shape == 1));
                }
            }
            button.setOnClickListener(v -> toggleMenu());
            button.setOnTouchListener(this::onButtonTouch);
            return button;
        }

        private android.graphics.drawable.Drawable legacyIconDrawable() {
            return OverlayViews.icon(
                    config.buttonBackground,
                    config.gradientBackground ? config.iconBackground2 : config.buttonBackground,
                    config.iconGradientAngle,
                    config.gradientBackground,
                    config.iconOutline ? config.iconOutlineColor : Color.TRANSPARENT,
                    config.iconOutline ? config.iconOutlineColor2 : Color.TRANSPARENT,
                    config.iconOutlineGradientAngle,
                    config.iconOutline && config.iconOutlineGradient,
                    config.iconOutline ? dp(config.iconOutlineWidth) : 0,
                    config.shape == 1,
                    config.iconStyle,
                    config.iconShape,
                    config.iconShapeColor1,
                    config.iconShapeColor2,
                    config.iconShapeGradient,
                    config.iconShapeGradientAngle,
                    dp(config.iconShapeStrokeWidth),
                    config.iconShapeScale / 100f,
                    config.iconHighlight,
                    config.iconShadow,
                    config.iconBackgroundStyle,
                    config.iconBackgroundColor3,
                    config.iconBackgroundColor4,
                    config.iconParts);
        }

        /** Decodes the image embedded by the patch; user-supplied paths are never needed at runtime. */
        private Bitmap decodeCustomIcon(String encoded) {
            if (encoded == null || encoded.trim().isEmpty() || encoded.length() > 4 * 1024 * 1024) return null;
            try {
                String value = encoded.trim();
                int comma = value.indexOf(',');
                if (!value.startsWith("data:") || comma < 0) return null;
                value = value.substring(comma + 1).trim();
                byte[] bytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT);
                if (bytes.length == 0) return null;
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private Bitmap fitCustomIcon(Bitmap bitmap) {
            return fitCustomIcon(bitmap, Math.max(1, dp(config.buttonSize) - dp(8)));
        }

        private Bitmap fitCustomIcon(Bitmap bitmap, int target) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= 0 || height <= 0) return bitmap;
            float scale = Math.min((float) target / width, (float) target / height);
            if (scale == 1f) return bitmap;
            int scaledWidth = Math.max(1, Math.round(width * scale));
            int scaledHeight = Math.max(1, Math.round(height * scale));
            try {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
                if (scaled != bitmap) bitmap.recycle();
                return scaled;
            } catch (RuntimeException ignored) {
                return bitmap;
            }
        }

        private void createStatisticMonitors(OverlayStatisticModule module) {
            List<TextView> monitors = new ArrayList<>();
            for (int i = 0; i < module.monitorCount(); i++) {
                TextView monitor = text("", 12, config.menuTextColor4);
                monitor.setGravity(Gravity.CENTER);
                monitor.setPadding(dp(3), 0, dp(3), 0);
                monitor.setBackground(OverlayViews.background(config.background, config.outline, false, config.outlineWidth));
                monitor.setClickable(false);
                monitor.setFocusable(false);
                monitor.setFocusableInTouchMode(false);
                monitor.setContentDescription(module.label());
                monitor.setVisibility(View.GONE);
                monitor.setOnTouchListener((v, event) -> false);
                root.addView(monitor, new FrameLayout.LayoutParams(monitorWidth, monitorHeight));
                monitors.add(monitor);
            }
            statisticMonitors.put(module.key(), monitors);
            module.bindMonitors(monitors);
        }

        private boolean shouldStatisticsRun(OverlayStatisticModule module) {
            // Menu-only statistics sample only while visible. Monitor statistics continue only
            // when their monitor is enabled, avoiding background work for hidden modules.
            return menuVisible || (module.isMonitorEnabled() && module.monitorCount() > 0);
        }

        private void syncStatisticExecution() {
            for (OverlayStatisticModule module : statistics) {
                module.setEnabled(module.isEnabled(), shouldStatisticsRun(module));
            }
        }

        private void updateStatisticMonitor(OverlayStatisticModule module) {
            List<TextView> monitors = statisticMonitors.get(module.key());
            if (monitors == null) return;
            for (TextView monitor : monitors) {
                monitor.setVisibility(config.statisticMonitorPosition != 0
                        && module.isMonitorEnabled() && module.isEnabled()
                        ? View.VISIBLE : View.GONE);
                monitor.setAlpha(menuVisible ? 0f : config.opacity);
            }
        }

        private void updateMonitorLayout() {
            int buttonX = Math.round(floatingButton.getX());
            int buttonY = Math.round(floatingButton.getY());
            int count = 0;
            for (OverlayStatisticModule module : statistics) {
                List<TextView> monitors = statisticMonitors.get(module.key());
                if (monitors != null) for (TextView monitor : monitors) {
                    if (monitor.getVisibility() == View.VISIBLE) count++;
                }
            }
            int columns = Math.max(1, config.monitorColumns);
            int spacing = dp(4);
            int rows = (count + columns - 1) / columns;
            int stackHeight = rows * monitorHeight + Math.max(0, rows - 1) * spacing;
            int monitorStartY = config.statisticMonitorPosition == 1
                    ? buttonY - spacing - stackHeight
                    : buttonY + floatingButton.getHeight() + spacing;
            monitorStartY = Math.max(0, Math.min(monitorStartY, Math.max(0, root.getHeight() - stackHeight)));
            int gridWidth = columns * monitorWidth + Math.max(0, columns - 1) * spacing;
            int gridStartX = buttonX + (floatingButton.getWidth() - gridWidth) / 2;
            gridStartX = Math.max(0, Math.min(gridStartX, Math.max(0, root.getWidth() - gridWidth)));
            // Follow the same stable order used by addModules.
            int slot = 0;
            for (OverlayStatisticModule module : statistics) {
                List<TextView> monitors = statisticMonitors.get(module.key());
                if (monitors == null) continue;
                for (TextView monitor : monitors) {
                    if (monitor.getVisibility() != View.VISIBLE) continue;
                    int row = slot / columns;
                    int column = slot % columns;
                    int itemsInRow = Math.min(columns, count - row * columns);
                    int rowWidth = itemsInRow * monitorWidth + Math.max(0, itemsInRow - 1) * spacing;
                    int rowStartX = buttonX + (floatingButton.getWidth() - rowWidth) / 2;
                    rowStartX = Math.max(0, Math.min(rowStartX, Math.max(0, root.getWidth() - rowWidth)));
                    monitor.setX(rowStartX + column * (monitorWidth + spacing));
                    monitor.setY(monitorStartY + row * (monitorHeight + spacing));
                    slot++;
                }
            }
        }

        private void setMonitorAlpha(float alpha) {
            for (List<TextView> monitors : statisticMonitors.values()) {
                for (TextView monitor : monitors) if (monitor.getVisibility() == View.VISIBLE) {
                    monitor.animate().alpha(alpha).setDuration(180).start();
                }
            }
        }

        private void setMonitorAlphaImmediate(float alpha) {
            for (List<TextView> monitors : statisticMonitors.values()) {
                for (TextView monitor : monitors) if (monitor.getVisibility() == View.VISIBLE) {
                    monitor.animate().cancel();
                    monitor.setAlpha(alpha);
                }
            }
        }

        private void showButtonFullyVisibleAfterDrag() {
            root.removeCallbacks(dragVisibilityFade);
            floatingButton.animate().cancel();
            floatingButton.setAlpha(1f);
            setMonitorAlphaImmediate(1f);
            root.postDelayed(dragVisibilityFade, config.dragVisibilityDurationSeconds * 1000L);
        }

        private FrameLayout createMenuLayer() {
            FrameLayout layer = new FrameLayout(overlayContext);
            // This full-screen container remains touchable while the menu is open. Its children
            // consume all background touches so Unity/host content cannot receive game input.
            layer.setClickable(true);
            layer.setFocusable(true);
            return layer;
        }

        private View createMenuScrim() {
            View scrim = new View(overlayContext);
            scrim.setBackgroundColor(0x55000000);
            scrim.setClickable(true);
            scrim.setFocusable(true);
            scrim.setOnClickListener(v -> closeMenu());
            // The clickable View consumes the gesture and still delivers its click callback;
            // returning true here would bypass View.onTouchEvent and prevent dismissal.
            scrim.setOnTouchListener((v, event) -> false);
            return scrim;
        }

        private LinearLayout createMenuPanel() {
            LinearLayout menu = new LinearLayout(overlayContext);
            menu.setOrientation(LinearLayout.VERTICAL);
            menu.setClickable(true);
            menu.setFocusable(true);
            // Consume unused panel area without preventing its child controls from receiving taps.
            menu.setOnTouchListener((v, event) -> true);
            menu.setPadding(dp(20), dp(18), dp(20), dp(12));
            menu.setBackground(menuOutline != null ? menuOutline : OverlayViews.background(
                    config.background, config.outline, false, config.outlineWidth,
                    !"square".equals(config.menuCorners)));
            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                    Math.max(dp(1), Math.min(dp(560), activity.getResources().getDisplayMetrics().widthPixels - dp(40))),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            panelParams.setMargins(dp(20), dp(20), dp(20), dp(20));
            menu.setLayoutParams(panelParams);

            LinearLayout titleRow = new LinearLayout(overlayContext);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            boolean leftTitleIcon = "left".equals(config.titleIconPlacement) || "both".equals(config.titleIconPlacement);
            boolean rightTitleIcon = "right".equals(config.titleIconPlacement) || "both".equals(config.titleIconPlacement);
            if (leftTitleIcon) titleRow.addView(createMenuTitleIcon(), titleIconParams());
            TextView title = text(config.title, 20, config.menuTextColor1);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setGravity("center".equals(config.titleAlignment) ? Gravity.CENTER
                    : ("right".equals(config.titleAlignment) ? Gravity.RIGHT : Gravity.LEFT));
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
            titleParams.leftMargin = leftTitleIcon ? dp(8) : 0;
            titleParams.rightMargin = rightTitleIcon ? dp(8) : 0;
            titleRow.addView(title, titleParams);
            if (rightTitleIcon) titleRow.addView(createMenuTitleIcon(), titleIconParams());
            final TextView leftIconView = leftTitleIcon ? (TextView) titleRow.getChildAt(0) : null;
            final TextView rightIconView = rightTitleIcon
                    ? (TextView) titleRow.getChildAt(titleRow.getChildCount() - 1) : null;
            titleRow.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                int iconSize = Math.max(dp(32), title.getHeight() - dp(4));
                resizeMenuTitleIcon(leftIconView, iconSize);
                resizeMenuTitleIcon(rightIconView, iconSize);
            });
            menu.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
            if (config.titleSeparator) {
                View titleLine = new View(overlayContext);
                titleLine.setBackgroundColor(config.menuTextColor1);
                LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
                lineParams.topMargin = dp(6);
                menu.addView(titleLine, lineParams);
            }

            int descriptionGravity = "left".equals(config.descriptionAlignment) ? Gravity.LEFT
                    : ("right".equals(config.descriptionAlignment) ? Gravity.RIGHT : Gravity.CENTER);
            TextView description = text(config.description, 14, config.menuTextColor3);
            description.setGravity(descriptionGravity);
            LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
            descriptionParams.topMargin = dp(8);
            menu.addView(description, descriptionParams);
            if (config.appendDescription != null && !config.appendDescription.isEmpty()) {
                TextView appendedDescription = text(config.appendDescription, 14, config.appendDescriptionColor);
                appendedDescription.setGravity(descriptionGravity);
                LinearLayout.LayoutParams appendedParams = new LinearLayout.LayoutParams(-1, -2);
                appendedParams.topMargin = dp(2);
                menu.addView(appendedDescription, appendedParams);
            }

            int maxControlHeight = Math.max(dp(120), Math.min(dp(280),
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * .45f)) - dp(8));
            ScrollView scroll = new BoundedScrollView(overlayContext, maxControlHeight);
            scroll.setFillViewport(true);
            styleModuleScrollBar(scroll);
            LinearLayout modules = new LinearLayout(overlayContext);
            modules.setOrientation(LinearLayout.VERTICAL);
            addModules(modules);
            scroll.addView(modules, new ScrollView.LayoutParams(-1, -2));
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, -2);
            scrollParams.topMargin = dp(12);
            menu.addView(scroll, scrollParams);

            LinearLayout actions = new LinearLayout(overlayContext);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            menu.addView(actions, new LinearLayout.LayoutParams(-1, -2));
            addAction(actions, config.repositoryText, v -> openRepository());
            addAction(actions, "Close menu", v -> closeMenu());
            addAction(actions, "Fully close", v -> showCloseConfirmation());
            return menu;
        }

        private void styleModuleScrollBar(ScrollView scroll) {
            scroll.setVerticalScrollBarEnabled(true);
            scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
            scroll.setScrollbarFadingEnabled(true);
            scroll.setScrollBarFadeDuration(220);
            scroll.setScrollBarDefaultDelayBeforeFade(350);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                scroll.setVerticalScrollbarThumbDrawable(new android.graphics.drawable.ColorDrawable(config.menuTextColor1));
                scroll.setVerticalScrollbarTrackDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        }

        private LinearLayout.LayoutParams titleIconParams() {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
            params.gravity = Gravity.CENTER_VERTICAL;
            return params;
        }

        private TextView createMenuTitleIcon() {
            TextView icon = new TextView(overlayContext);
            icon.setGravity(Gravity.CENTER);
            icon.setTextColor(config.buttonTextColor);
            icon.setText(config.buttonText);
            icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(10, config.iconTextSize - 4));
            icon.setTypeface(Typeface.DEFAULT, config.iconBold ? Typeface.BOLD : Typeface.NORMAL);
            Bitmap customIcon = "image".equals(config.iconType) ? decodeCustomIcon(config.customIconImage) : null;
            if (customIcon != null) {
                icon.setText("");
                BitmapDrawable image = new BitmapDrawable(overlayContext.getResources(), fitCustomIcon(customIcon, dp(24)));
                image.setGravity(Gravity.CENTER);
                icon.setBackground(image);
            } else {
                if ("image".equals(config.iconType)) {
                    icon.setText("");
                    icon.setBackground(OverlayViews.gradientBackground(
                            config.buttonBackground, config.gradientBackground ? config.iconBackground2 : config.buttonBackground,
                            config.iconGradientAngle, Color.TRANSPARENT, 0, config.shape == 1));
                } else if ("parts".equals(config.iconStyle)) {
                    icon.setText("");
                    icon.setBackground(legacyIconDrawable());
                } else {
                    icon.setBackground(OverlayViews.gradientBackground(
                            config.buttonBackground,
                            config.gradientBackground ? config.iconBackground2 : config.buttonBackground,
                            config.iconGradientAngle, Color.TRANSPARENT, 0, config.shape == 1));
                }
            }
            icon.setClickable(false);
            icon.setFocusable(false);
            icon.setContentDescription("Overlay menu icon");
            return icon;
        }

        private void resizeMenuTitleIcon(TextView icon, int size) {
            if (icon == null || icon.getLayoutParams() == null) return;
            ViewGroup.LayoutParams params = icon.getLayoutParams();
            if (params.width == size && params.height == size) return;
            params.width = size;
            params.height = size;
            icon.setLayoutParams(params);
            if (!"image".equals(config.iconType)) return;
            Bitmap customIcon = decodeCustomIcon(config.customIconImage);
            if (customIcon == null) return;
            BitmapDrawable image = new BitmapDrawable(overlayContext.getResources(),
                    fitCustomIcon(customIcon, Math.max(1, size - dp(6))));
            image.setGravity(Gravity.CENTER);
            image.setAntiAlias(true);
            icon.setBackground(image);
        }

        private FrameLayout createConfirmationLayer() {
            FrameLayout layer = new FrameLayout(overlayContext);
            layer.setBackgroundColor(0xB3000000);
            layer.setClickable(true);
            layer.setFocusable(true);
            layer.setOnClickListener(v -> hideCloseConfirmation());

            LinearLayout card = new LinearLayout(overlayContext);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(20), dp(18), dp(20), dp(12));
            card.setBackground(OverlayViews.background(config.background, config.outline, false,
                    config.outlineWidth, !"square".equals(config.menuCorners)));
            card.setClickable(true);
            card.setOnClickListener(v -> { });

            TextView title = text("Close overlay?", 20, config.menuTextColor1);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(title, new LinearLayout.LayoutParams(-1, -2));

            TextView message = text("The overlay will close for this app process until the app is restarted.", 14, config.menuTextColor3);
            LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
            messageParams.topMargin = dp(8);
            card.addView(message, messageParams);

            LinearLayout actions = new LinearLayout(overlayContext);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
            actionsParams.topMargin = dp(8);
            card.addView(actions, actionsParams);
            addAction(actions, "Cancel", v -> hideCloseConfirmation());
            addAction(actions, "Fully close", v -> fullyClose());

            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            cardParams.setMargins(dp(20), dp(20), dp(20), dp(20));
            layer.addView(card, cardParams);
            return layer;
        }

        private void addModules(LinearLayout modules) {
            boolean hasStatistics = config.systemTime || config.fps || config.sessionTime
                    || config.batteryStatus || config.appMemory || config.networkStatus
                    || config.deviceInformation || config.deviceTemperature;
            boolean hasActivity = config.keepAwake || config.fullscreen || config.screenshots
                    || config.appBrightness || config.rotationMode || config.appAudioMute;
            boolean hasHooks = config.disableHaptics || config.disableAnimations;
            boolean hasAppSpecific = hasRegisteredAppSpecificProvider() || hasIntegratedModules();
            if (!hasStatistics && !hasActivity && !hasHooks && !hasAppSpecific && config.showNoModulesWarning) {
                TextView warning = text(
                        "\n No Runtime Modules Selected. Select modules in patch settings before patching APK if you want to have runtime modules in this app. \n",
                        14,
                        config.menuTextColor3);
                warning.setGravity(Gravity.CENTER);
                warning.setPadding(dp(4), dp(8), dp(4), dp(8));
                modules.addView(warning, new LinearLayout.LayoutParams(-1, -2));
                return;
            }
            if (hasStatistics) {
                addSectionLabel(modules, "Statistic modules");
                if (config.deviceInformation) addStatisticSafely(modules, () -> new DeviceInformationModule(activity));
                if (config.fps) addStatisticSafely(modules, FpsModule::new);
                if (config.deviceTemperature) addStatisticSafely(modules, () -> new DeviceTemperatureModule(activity, config.temperatureFormat));
                if (config.systemTime) addStatisticSafely(modules, () -> new SystemTimeModule(config.timeFormat));
                if (config.sessionTime) addStatisticSafely(modules, () -> new SessionTimeModule(sessionStartElapsed));
                if (config.batteryStatus) addStatisticSafely(modules, () -> new BatteryStatusModule(activity));
                if (config.appMemory) addStatisticSafely(modules, () -> new AppMemoryModule(activity));
                if (config.networkStatus) addStatisticSafely(modules, () -> new NetworkStatusModule(activity));
            }
            if (hasActivity) {
                addSectionLabel(modules, "Activity modules");
                if (config.keepAwake) addActivityModuleSafely(modules, KeepAwakeModule::new);
                if (config.fullscreen) addActivityModuleSafely(modules, FullscreenModule::new);
                if (config.screenshots) addActivityModuleSafely(modules, ScreenshotsModule::new);
                if (config.appBrightness) addActivityModuleSafely(modules, AppBrightnessModule::new);
                if (config.rotationMode) addActivityModuleSafely(modules, RotationModeModule::new);
                if (config.appAudioMute) addActivityModuleSafely(modules, AppAudioMuteModule::new);
            }
            if (hasHooks) {
                addSectionLabel(modules, "Hook modules");
                if (config.disableHaptics) addHookModuleSafely(modules, DisableHapticsModule::new);
                if (config.disableAnimations) addHookModuleSafely(modules, DisableAnimationsModule::new);
            }
            addAppSpecificModules(modules);
            addIntegratedModules(modules);
        }

        private boolean hasRegisteredAppSpecificProvider() {
            if (config.appSpecificProfile == null || config.appSpecificProfile.isEmpty()) return false;
            if (config.appSpecificModules == null || config.appSpecificModules.trim().isEmpty()) return false;
            for (OverlayAppSpecificModuleProvider provider : APP_SPECIFIC_PROVIDERS) {
                try {
                    if (config.appSpecificProfile.equals(provider.profileId())) return true;
                } catch (RuntimeException ignored) {
                    // Ignore broken providers and continue looking for the selected profile.
                }
            }
            return false;
        }

        private boolean hasIntegratedModules() {
            return AdsRuntimePolicy.hasAnyModule();
        }

        private void addAppSpecificModules(LinearLayout parent) {
            if (config.appSpecificProfile == null || config.appSpecificProfile.isEmpty()) return;
            for (OverlayAppSpecificModuleProvider provider : APP_SPECIFIC_PROVIDERS) {
                try {
                    if (!config.appSpecificProfile.equals(provider.profileId())) continue;
                    List<OverlayAppSpecificModule> targetModules = provider.create(activity);
                    if (targetModules == null || targetModules.isEmpty()) return;
                    List<OverlayAppSpecificModule> supportedModules = new ArrayList<>();
                    for (OverlayAppSpecificModule module : targetModules) {
                        if (module == null) continue;
                        try {
                            if (module.supports(activity)) supportedModules.add(module);
                        } catch (RuntimeException ignored) {
                            // One target mismatch must not hide other app-specific modules.
                        }
                    }
                    List<OverlayAppSpecificModule> selectedModules = new ArrayList<>();
                    for (OverlayAppSpecificModule module : supportedModules) {
                        if (isAppSpecificModuleSelected(module.key())) selectedModules.add(module);
                    }
                    if (selectedModules.isEmpty()) return;
                    addSectionLabel(parent, "App-specific modules");
                    for (OverlayAppSpecificModule module : selectedModules) {
                        addAppSpecificModuleSafely(parent, () -> module);
                    }
                } catch (RuntimeException ignored) {
                    // Target-specific code must not prevent universal modules from rendering.
                }
                return;
            }
        }

        private void addActivityModuleSafely(LinearLayout parent, ActivityModuleFactory factory) {
            try {
                addActivityModule(parent, factory.create());
            } catch (RuntimeException ignored) {
                // A module constructor or UI setup failure must not hide other modules.
            }
        }

        private void addStatisticSafely(LinearLayout parent, StatisticModuleFactory factory) {
            try {
                addStatistic(parent, factory.create());
            } catch (RuntimeException ignored) {
                // A module constructor or UI setup failure must not hide other modules.
            }
        }

        private void addHookModuleSafely(LinearLayout parent, HookModuleFactory factory) {
            try {
                addHookModule(parent, factory.create());
            } catch (RuntimeException ignored) {
                // A hook constructor or UI setup failure must not hide other modules.
            }
        }

        private void addAppSpecificModuleSafely(LinearLayout parent, AppSpecificModuleFactory factory) {
            try {
                addAppSpecificModule(parent, factory.create());
            } catch (RuntimeException ignored) {
                // A target-specific constructor or UI setup failure must not hide other modules.
            }
        }

        private void addAppSpecificModule(LinearLayout controls, OverlayAppSpecificModule module) {
            final boolean initial;
            try {
                Boolean remembered = APP_SPECIFIC_STATES.get(module.key());
                initial = remembered != null ? remembered
                        : module.initiallyEnabled(activity, originalWindowFlags, originalSystemUi);
                if (remembered != null && !module.setEnabled(activity, remembered, originalWindowFlags, originalSystemUi)) {
                    APP_SPECIFIC_STATES.put(module.key(), false);
                    return;
                }
            } catch (RuntimeException ignored) {
                return;
            }
            appSpecificModules.add(module);
            if (module instanceof OverlayActionModule) {
                addAppSpecificActionModule(controls, (OverlayActionModule) module, initial);
                return;
            }
            addControlRow(controls, module, initial, checked -> {
                try {
                    boolean applied = module.setEnabled(activity, checked, originalWindowFlags, originalSystemUi);
                    APP_SPECIFIC_STATES.put(module.key(), applied && checked);
                    return applied;
                } catch (RuntimeException ignored) {
                    APP_SPECIFIC_STATES.put(module.key(), false);
                    return false;
                }
            });
        }

        private boolean isAppSpecificModuleSelected(String key) {
            if (config.appSpecificModules == null || config.appSpecificModules.isEmpty()) return false;
            for (String selected : config.appSpecificModules.split(",")) {
                if (key.equals(selected.trim())) return true;
            }
            return false;
        }

        private void addAppSpecificActionModule(LinearLayout parent, OverlayActionModule module, boolean initial) {
            LinearLayout row = new LinearLayout(overlayContext);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            LinearLayout header = new LinearLayout(overlayContext);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setBaselineAligned(false);
            TextView title = text(moduleTitleText(module.label()), 16, config.menuTextColor2);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

            final CheckBox enabled;
            if (module.hasEnableToggle()) {
                enabled = new CheckBox(overlayContext);
                enabled.setChecked(initial);
                styleCheckBox(enabled);
                enabled.setContentDescription(module.label() + " enabled");
                featureControls.put(module.key(), enabled);
                enabled.setOnCheckedChangeListener((button, checked) -> {
                    boolean applied = module.setEnabled(activity, checked, originalWindowFlags, originalSystemUi);
                    APP_SPECIFIC_STATES.put(module.key(), applied && checked);
                    if (!applied) {
                        button.setOnCheckedChangeListener(null);
                        button.setChecked(!checked);
                        button.setOnCheckedChangeListener((b, value) -> {
                            boolean retry = module.setEnabled(activity, value, originalWindowFlags, originalSystemUi);
                            APP_SPECIFIC_STATES.put(module.key(), retry && value);
                        });
                        Toast.makeText(activity, module.label() + " could not be changed", Toast.LENGTH_SHORT).show();
                    }
                });
                header.addView(enabled, new LinearLayout.LayoutParams(-2, -2));
            } else {
                enabled = null;
            }

            TextView settings = null;
            if (module.hasSettings()) {
                settings = moduleButton("Settings");
                settings.setContentDescription(module.label() + " settings");
                header.addView(settings, moduleButtonParams());
            }
            TextView action = null;
            if (module.hasActionButton()) {
                action = moduleButton(module.actionLabel());
                action.setContentDescription(module.label() + " action");
                header.addView(action, moduleButtonParams());
            }
            row.addView(header, new LinearLayout.LayoutParams(-1, -2));

            TextView description = text(module.description(), 13, config.menuTextColor3);
            description.setAlpha(.82f);
            row.addView(description, new LinearLayout.LayoutParams(-1, -2));
            TextView value = text(module.valueText(), 13, config.menuTextColor4);
            value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(value, new LinearLayout.LayoutParams(-1, -2));
            if (settings != null) {
                settings.setOnClickListener(v -> showModuleSettingsPopup(module, () -> {
                    try { value.setText(module.valueText()); } catch (RuntimeException ignored) { }
                }));
            }
            if (action != null) {
                action.setOnClickListener(v -> {
                    if (enabled != null && !enabled.isChecked()) {
                        Toast.makeText(activity, "Enable " + module.label() + " first", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        if (module.performAction(activity)) {
                            value.setText(module.valueText());
                            Toast.makeText(activity, module.actionLabel() + " completed; refresh the scene if needed", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(activity, module.actionLabel() + " failed", Toast.LENGTH_SHORT).show();
                        }
                    } catch (RuntimeException ignored) {
                        Toast.makeText(activity, module.actionLabel() + " failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        private LinearLayout.LayoutParams moduleButtonParams() {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.gravity = Gravity.CENTER_VERTICAL;
            params.leftMargin = dp(4);
            return params;
        }

        private void showModuleSettingsPopup(OverlayActionModule module, Runnable onApplied) {
            if (root == null) return;
            final FrameLayout layer = new FrameLayout(overlayContext);
            layer.setBackgroundColor(0xB3000000);
            layer.setClickable(true);
            layer.setFocusable(true);
            layer.setOnClickListener(v -> root.removeView(layer));

            LinearLayout card = new LinearLayout(overlayContext);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(20), dp(18), dp(20), dp(12));
            card.setBackground(OverlayViews.background(config.background, config.outline, false,
                    config.outlineWidth, !"square".equals(config.menuCorners)));
            card.setClickable(true);
            card.setOnClickListener(v -> { });
            TextView title = text(module.settingsTitle(), 20, config.menuTextColor1);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(title, new LinearLayout.LayoutParams(-1, -2));

            final String textValue = module.settingsTextValue();
            final EditText input;
            if (textValue != null) {
                TextView hint = text(module.settingsTextHint(), 13, config.menuTextColor3);
                LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
                hintParams.topMargin = dp(8);
                card.addView(hint, hintParams);
                input = new EditText(overlayContext);
                input.setSingleLine(true);
                input.setInputType(module.settingsInputType());
                input.setText(textValue);
                input.setTextColor(config.menuTextColor1);
                input.setHintTextColor(config.menuTextColor3);
                input.setSelectAllOnFocus(false);
                input.setBackground(OverlayViews.background(config.background, config.outline, false,
                        Math.max(1, config.outlineWidth), !"square".equals(config.menuCorners)));
                LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, -2);
                inputParams.topMargin = dp(10);
                card.addView(input, inputParams);
            } else {
                input = null;
                String[] choices = module.settingsChoices();
                if (choices == null) choices = new String[0];
                boolean[] values = module.settingsValues();
                if (values == null) values = new boolean[0];
                ScrollView scroll = new ScrollView(overlayContext);
                LinearLayout choicesLayout = new LinearLayout(overlayContext);
                choicesLayout.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < choices.length; i++) {
                    CheckBox check = new CheckBox(overlayContext);
                    check.setText(choices[i]);
                    check.setTextColor(config.menuTextColor2);
                    check.setChecked(i < values.length && values[i]);
                    check.setTag(Integer.valueOf(i));
                    styleCheckBox(check);
                    choicesLayout.addView(check, new LinearLayout.LayoutParams(-1, -2));
                }
                scroll.addView(choicesLayout, new ScrollView.LayoutParams(-1, -2));
                // The card is wrap-content, so a weighted zero-height child can measure as zero
                // on some OEM layouts. A bounded explicit height keeps long lists scrollable.
                LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, dp(260));
                scrollParams.topMargin = dp(8);
                card.addView(scroll, scrollParams);
                card.setTag(choicesLayout);
            }

            LinearLayout actions = new LinearLayout(overlayContext);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
            actionsParams.topMargin = dp(8);
            card.addView(actions, actionsParams);
            addAction(actions, "Cancel", v -> root.removeView(layer));
            addAction(actions, module.settingsConfirmationLabel(), v -> {
                boolean applied;
                try {
                    if (input != null) {
                        applied = module.applySettingsText(input.getText().toString());
                    } else {
                        LinearLayout choicesLayout = (LinearLayout) card.getTag();
                        boolean[] values = new boolean[choicesLayout.getChildCount()];
                        for (int i = 0; i < values.length; i++) values[i] = ((CheckBox) choicesLayout.getChildAt(i)).isChecked();
                        module.applySettings(values);
                        applied = true;
                    }
                    if (applied && module.appliesSettingsOnConfirm()) {
                        applied = module.applySavedSettings(activity);
                    }
                } catch (RuntimeException ignored) {
                    applied = false;
                }
                if (!applied) {
                    Toast.makeText(activity, "Enter a valid value", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (onApplied != null) onApplied.run();
                root.removeView(layer);
            });
            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
            cardParams.setMargins(dp(20), dp(20), dp(20), dp(20));
            layer.addView(card, cardParams);
            root.addView(layer);
        }

        private void addIntegratedModules(LinearLayout parent) {
            if (!hasIntegratedModules()) return;
            for (OverlayAppSpecificModuleProvider provider : APP_SPECIFIC_PROVIDERS) {
                try {
                    if (!AdsControlRuntimeProvider.PROFILE_ID.equals(provider.profileId())) continue;
                    List<OverlayAppSpecificModule> modules = provider.create(activity);
                    if (modules == null || modules.isEmpty()) return;
                    addSectionLabel(parent, "Ad control hook modules");
                    for (OverlayAppSpecificModule module : modules) addAppSpecificModuleSafely(parent, () -> module);
                } catch (RuntimeException ignored) { }
                return;
            }
        }

        private TextView moduleButton(String label) {
            TextView button = text(label, 12, config.outline);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(8), dp(4), dp(8), dp(4));
            button.setBackground(OverlayViews.themedControlBackground(
                    config.background, config.outline, config.outlineWidth, config.controlTheme));
            button.setClickable(true);
            return button;
        }

        private void addSectionLabel(LinearLayout parent, String label) {
            int separatorColor = config.menuTextColor6;
            if ("inline".equals(config.separatorStyle)) {
                pendingInlineSectionLabel = label;
                return;
            }
            if ("doubleLine".equals(config.separatorStyle)) {
                LinearLayout wrapper = new LinearLayout(overlayContext);
                wrapper.setOrientation(LinearLayout.VERTICAL);
                View topLine = new View(overlayContext);
                topLine.setBackgroundColor(config.menuTextColor1);
                wrapper.addView(topLine, new LinearLayout.LayoutParams(-1, dp(1)));
                TextView lineLabel = text(label, 13, separatorColor);
                lineLabel.setGravity(Gravity.CENTER);
                wrapper.addView(lineLabel, new LinearLayout.LayoutParams(-1, dp(26)));
                View bottomLine = new View(overlayContext);
                bottomLine.setBackgroundColor(config.menuTextColor1);
                wrapper.addView(bottomLine, new LinearLayout.LayoutParams(-1, dp(1)));
                LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(-1, dp(34));
                wrapperParams.topMargin = dp(4);
                parent.addView(wrapper, wrapperParams);
                return;
            }
            String separatorText = "background".equals(config.separatorStyle) ? label : "---  " + label + "  ---";
            TextView separator = text(separatorText, 13, separatorColor);
            separator.setAlpha("inline".equals(config.separatorStyle) ? .9f : .65f);
            separator.setGravity(Gravity.CENTER);
            if ("singleLine".equals(config.separatorStyle)) {
                separatorText = label;
                separator.setText(separatorText);
                View line = new View(overlayContext);
                line.setBackgroundColor(config.menuTextColor1);
                LinearLayout wrapper = new LinearLayout(overlayContext);
                wrapper.setOrientation(LinearLayout.VERTICAL);
                wrapper.addView(separator, new LinearLayout.LayoutParams(-1, dp(26)));
                wrapper.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
                LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(-1, dp(30));
                wrapperParams.topMargin = dp(4);
                parent.addView(wrapper, wrapperParams);
                return;
            }
            if ("background".equals(config.separatorStyle)) {
                separator.setBackgroundColor(config.separatorBackgroundColor);
                separator.setPadding(dp(4), 0, dp(4), 0);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(32));
            params.topMargin = dp(4);
            parent.addView(separator, params);
        }

        private void addActivityModule(LinearLayout controls, OverlayActivityModule feature) {
            if (feature instanceof AppBrightnessModule) {
                addBrightnessModule(controls, (AppBrightnessModule) feature);
                return;
            }
            if (feature instanceof RotationModeModule) {
                addRotationModule(controls, (RotationModeModule) feature);
                return;
            }
            final boolean initial;
            try {
                Boolean remembered = rememberedState(feature.key());
                initial = remembered != null ? remembered
                        : feature.initiallyEnabled(activity, originalWindowFlags, originalSystemUi);
                if (remembered != null) {
                    if (!feature.setEnabled(activity, remembered, originalWindowFlags, originalSystemUi)) return;
                }
            } catch (RuntimeException ignored) {
                return;
            }
            activityModules.add(feature);
            addControlRow(controls, feature, initial, checked -> {
                try {
                    boolean applied = feature.setEnabled(activity, checked, originalWindowFlags, originalSystemUi);
                    rememberState(feature.key(), applied && checked);
                    return applied;
                } catch (RuntimeException ignored) {
                    // Feature controls are independent; a failure here must not crash the host.
                    rememberState(feature.key(), false);
                    return false;
                }
            });
        }

        private void addHookModule(LinearLayout controls, OverlayHookModule hook) {
            final boolean initial;
            try {
                Boolean remembered = HOOK_STATES.get(hook.key());
                initial = remembered != null ? remembered
                        : hook.initiallyEnabled(activity, originalWindowFlags, originalSystemUi);
                if (remembered != null && !hook.setEnabled(activity, remembered, originalWindowFlags, originalSystemUi)) {
                    HOOK_STATES.put(hook.key(), false);
                    return;
                }
            } catch (RuntimeException ignored) {
                return;
            }
            hookModules.add(hook);
            addControlRow(controls, hook, initial, checked -> {
                try {
                    boolean applied = hook.setEnabled(activity, checked, originalWindowFlags, originalSystemUi);
                    HOOK_STATES.put(hook.key(), applied && checked);
                    return applied;
                } catch (RuntimeException ignored) {
                    HOOK_STATES.put(hook.key(), false);
                    return false;
                }
            });
        }

        private void addBrightnessModule(LinearLayout parent, AppBrightnessModule module) {
            activityModules.add(module);
            module.initiallyEnabled(activity, originalWindowFlags, originalSystemUi);
            module.bindDimLayer(brightnessDimLayer);
            Float remembered = appBrightnessState;
            if (remembered != null) module.apply(activity, remembered);
            LinearLayout row = moduleRow(module.label(), module.description());
            SeekBar slider = new SeekBar(overlayContext);
            slider.setMax(100);
            slider.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            styleSlider(slider);
            slider.setProgress(Math.round((remembered == null ? module.current(activity) : remembered) * 100f));
            slider.setContentDescription(module.label());
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    try {
                        float brightness = progress / 100f;
                        appBrightnessState = brightness;
                        module.apply(activity, brightness);
                    }
                    catch (RuntimeException ignored) { }
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            row.addView(slider, new LinearLayout.LayoutParams(-1, -2));
            parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        private void addRotationModule(LinearLayout parent, RotationModeModule module) {
            activityModules.add(module);
            module.initiallyEnabled(activity, originalWindowFlags, originalSystemUi);
            Integer remembered = rotationModeState;
            if (remembered != null) module.apply(activity, remembered);
            LinearLayout row = moduleRow(module.label(), module.description());
            Spinner spinner = new Spinner(overlayContext);
            String[] labels = {"System", "Portrait", "Landscape"};
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(overlayContext, android.R.layout.simple_spinner_item, labels) {
                @Override public View getView(int position, View convertView, android.view.ViewGroup parentView) {
                    TextView view = (TextView) super.getView(position, convertView, parentView);
                    view.setTextColor(config.outline);
                    view.setBackgroundColor(config.background);
                    view.setPadding(dp(12), dp(8), dp(12), dp(8));
                    return view;
                }
                @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parentView) {
                    TextView view = (TextView) super.getDropDownView(position, convertView, parentView);
                    view.setTextColor(config.outline);
                    view.setBackgroundColor(config.background);
                    view.setPadding(dp(12), dp(10), dp(12), dp(10));
                    return view;
                }
            };
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            // Keep the collapsed control and popup visually attached to the same menu surface.
            // The platform Spinner outline can otherwise become a large black rectangle outside
            // the panel, especially when a preset uses a dark control background.
            spinner.setBackground(OverlayViews.themedControlBackground(
                    config.background, config.outline, config.outlineWidth, config.controlTheme));
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                spinner.setPopupBackgroundDrawable(OverlayViews.themedControlBackground(
                        config.background, config.outline, config.outlineWidth, config.controlTheme));
            }
            int current = remembered == null ? module.current(activity) : remembered;
            spinner.setSelection(current == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? 1
                    : (current == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ? 2 : 0));
            spinner.setContentDescription(module.label());
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parentView, View view, int position, long id) {
                    int mode = position == 1 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            : (position == 2 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    try {
                        module.apply(activity, mode);
                        rotationModeState = mode;
                    } catch (RuntimeException ignored) { }
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parentView) { }
            });
            row.addView(spinner, new LinearLayout.LayoutParams(-1, -2));
            parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        private LinearLayout moduleRow(String label, String details) {
            LinearLayout row = new LinearLayout(overlayContext);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView title = text(moduleTitleText(label), 16, config.menuTextColor2);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView description = text(details, 13, config.menuTextColor3);
            description.setAlpha(.82f);
            row.addView(description, new LinearLayout.LayoutParams(-1, -2));
            return row;
        }

        private void addStatistic(LinearLayout parent, OverlayStatisticModule module) {
            String key = module.key();
            String label = module.label();
            String description = module.description();
            statistics.add(module);
            LinearLayout row = new LinearLayout(overlayContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            row.setMinimumHeight(dp(64));

            LinearLayout copy = new LinearLayout(overlayContext);
            copy.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(moduleTitleText(label), 16, config.menuTextColor2);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView details = text(description, 13, config.menuTextColor3);
            details.setAlpha(.82f);
            copy.addView(details, new LinearLayout.LayoutParams(-1, -2));
            TextView valueView = text("Disabled", 12, config.menuTextColor4);
            valueView.setAlpha(.72f);
            copy.addView(valueView, new LinearLayout.LayoutParams(-1, -2));
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

            CheckBox monitorControl = null;
            if (module.monitorCount() > 0) {
                monitorControl = new CheckBox(overlayContext);
                Boolean rememberedMonitor = MONITOR_STATES.get(key);
                monitorControl.setChecked(rememberedMonitor != null ? rememberedMonitor : config.enableMonitorsOnLaunch);
                monitorControl.setText("Monitor");
                styleCheckBox(monitorControl);
                monitorControl.setTextColor(config.menuTextColor4);
                monitorControl.setContentDescription(label + " monitor");
                module.setMonitorEnabled(monitorControl.isChecked());
                monitorControl.setOnCheckedChangeListener((button, checked) -> {
                    MONITOR_STATES.put(key, checked);
                    module.setMonitorEnabled(checked);
                    updateStatisticMonitor(module);
                    root.post(this::updateMonitorLayout);
                    Toast.makeText(activity, label + " monitor is " + (checked ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
                });
            } else {
                module.setMonitorEnabled(false);
            }

            CheckBox control = new CheckBox(overlayContext);
            Boolean remembered = rememberedModuleState(key);
            control.setChecked(remembered != null ? remembered : config.activateStatisticsOnLaunch);
            control.setText("Active");
            styleCheckBox(control);
            control.setTextColor(config.menuTextColor5);
            control.setContentDescription(label + " active");
            module.bind(valueView, control);
            createStatisticMonitors(module);
            boolean requested = control.isChecked();
            module.setMenuVisible(menuVisible);
            boolean applied = module.setEnabled(requested, shouldStatisticsRun(module));
            if (requested && !applied) {
                control.setChecked(false);
                rememberModuleState(key, false);
                module.setChecked(false);
            }
            updateStatisticMonitor(module);
            control.setOnCheckedChangeListener((button, checked) -> {
                rememberModuleState(key, checked);
                boolean toggleApplied = module.setEnabled(checked, shouldStatisticsRun(module));
                if (!toggleApplied && checked) {
                    rememberModuleState(key, false);
                    module.setChecked(false);
                    Toast.makeText(activity, label + " could not be enabled", Toast.LENGTH_SHORT).show();
                } else {
                    updateStatisticMonitor(module);
                    root.post(this::updateMonitorLayout);
                    Toast.makeText(activity, label + " is " + (checked ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
                }
            });
            if (monitorControl != null) row.addView(monitorControl, new LinearLayout.LayoutParams(-2, -2));
            row.addView(control, new LinearLayout.LayoutParams(-2, -2));
            parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        private void applyRememberedStates() {
            if (detached) return;
            for (OverlayActivityModule feature : activityModules) {
                Boolean remembered = rememberedState(feature.key());
                if (remembered == null) continue;
                try {
                    CheckBox control = featureControls.get(feature.key());
                    if (!feature.setEnabled(activity, remembered, originalWindowFlags, originalSystemUi)) {
                        rememberState(feature.key(), false);
                        if (control != null) control.setChecked(false);
                        continue;
                    }
                    if (control != null && control.isChecked() != remembered) {
                        control.setChecked(remembered);
                    }
                } catch (RuntimeException ignored) {
                    // A failed feature must not prevent the remaining controls from syncing.
                }
            }
            for (OverlayStatisticModule module : statistics) {
                Boolean rememberedMonitor = MONITOR_STATES.get(module.key());
                if (rememberedMonitor != null) module.setMonitorEnabled(rememberedMonitor);
                Boolean remembered = rememberedModuleState(module.key());
                if (remembered == null) continue;
                module.setChecked(remembered);
                if (!module.setEnabled(remembered, shouldStatisticsRun(module))) {
                    rememberModuleState(module.key(), false);
                    module.setChecked(false);
                }
                updateStatisticMonitor(module);
            }
            for (OverlayHookModule hook : hookModules) {
                Boolean remembered = HOOK_STATES.get(hook.key());
                if (remembered == null) continue;
                try {
                    if (!hook.setEnabled(activity, remembered, originalWindowFlags, originalSystemUi)) {
                        HOOK_STATES.put(hook.key(), false);
                        continue;
                    }
                    CheckBox control = featureControls.get(hook.key());
                    if (control != null && control.isChecked() != remembered) control.setChecked(remembered);
                } catch (RuntimeException ignored) {
                    HOOK_STATES.put(hook.key(), false);
                }
            }
            for (OverlayAppSpecificModule module : appSpecificModules) {
                Boolean remembered = APP_SPECIFIC_STATES.get(module.key());
                if (remembered == null) continue;
                try {
                    if (!module.setEnabled(activity, remembered, originalWindowFlags, originalSystemUi)) {
                        APP_SPECIFIC_STATES.put(module.key(), false);
                        continue;
                    }
                    CheckBox control = featureControls.get(module.key());
                    if (control != null && control.isChecked() != remembered) control.setChecked(remembered);
                } catch (RuntimeException ignored) {
                    APP_SPECIFIC_STATES.put(module.key(), false);
                }
            }
        }

        private void addControlRow(LinearLayout parent, OverlayModule feature, boolean initial, final Toggle toggle) {
            LinearLayout row = new LinearLayout(overlayContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            row.setMinimumHeight(dp(64));

            LinearLayout copy = new LinearLayout(overlayContext);
            copy.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(moduleTitleText(feature.label()), 16, config.menuTextColor2);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView description = text(feature.description(), 13, config.menuTextColor3);
            description.setAlpha(.82f);
            LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
            descriptionParams.topMargin = dp(2);
            copy.addView(description, descriptionParams);
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

            CheckBox control = new CheckBox(overlayContext);
            control.setChecked(initial);
            styleCheckBox(control);
            control.setContentDescription(feature.label());
            final android.widget.CompoundButton.OnCheckedChangeListener[] listener = new android.widget.CompoundButton.OnCheckedChangeListener[1];
            listener[0] = (button, checked) -> {
                boolean applied = toggle.changed(checked);
                if (!applied) {
                    control.setOnCheckedChangeListener(null);
                    control.setChecked(!checked);
                    control.setOnCheckedChangeListener(listener[0]);
                    Toast.makeText(activity, feature.label() + " could not be " + (checked ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, feature.label() + " is " + (checked ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
                }
            };
            control.setOnCheckedChangeListener(listener[0]);
            featureControls.put(feature.key(), control);
            row.addView(control, new LinearLayout.LayoutParams(-2, -2));
            row.setClickable(true);
            row.setOnClickListener(v -> control.setChecked(!control.isChecked()));
            parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        private CharSequence moduleTitleText(String label) {
            if (pendingInlineSectionLabel == null) return label;
            String suffix = "  •  " + pendingInlineSectionLabel;
            SpannableString result = new SpannableString(label + suffix);
            result.setSpan(new ForegroundColorSpan(config.menuTextColor6), label.length(), result.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pendingInlineSectionLabel = null;
            return result;
        }

        private TextView text(CharSequence value, float size, int color) {
            TextView view = new TextView(overlayContext);
            view.setText(value);
            view.setTextSize(size);
            view.setTextColor(color);
            return view;
        }

        private TextView text(String value, float size, int color) {
            return text((CharSequence) value, size, color);
        }

        private void styleCheckBox(CheckBox control) {
            control.setTextColor(config.controlForeground);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                if ("legacy".equals(config.controlTheme)) {
                    control.setButtonTintList(ColorStateList.valueOf(config.controlForeground));
                } else {
                    control.setButtonTintList(new ColorStateList(
                            new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                            new int[] { config.controlForeground, config.controlBackground }));
                }
            }
        }

        private void styleSlider(SeekBar slider) {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                int foreground = "legacy".equals(config.controlTheme) ? config.menuTextColor1 : config.controlForeground;
                int background = "legacy".equals(config.controlTheme) ? config.background : config.controlBackground;
                slider.setProgressTintList(ColorStateList.valueOf(foreground));
                slider.setThumbTintList(ColorStateList.valueOf(foreground));
                slider.setProgressBackgroundTintList(ColorStateList.valueOf(background));
            }
        }

        private void addAction(LinearLayout row, String label, View.OnClickListener listener) {
            TextView action = text(label, 14, config.bottomButtonTextColor);
            action.setGravity(Gravity.CENTER);
            action.setContentDescription(label);
            action.setOnClickListener(listener);
            boolean hasBackground = !"text".equals(config.bottomButtonStyle);
            if (hasBackground) {
                action.setBackground(OverlayViews.solidOrGradientBackground(
                        config.bottomButtonBackground1, config.bottomButtonBackground2, 0f,
                        Color.TRANSPARENT, 0, !"square".equals(config.bottomButtonShape),
                        "gradient".equals(config.bottomButtonStyle)));
            } else {
                action.setBackground(OverlayViews.selectableBackground(overlayContext));
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(56), 1f);
            if (config.bottomButtonPadding) params.setMargins(dp(3), 0, dp(3), 0);
            row.addView(action, params);
        }

        private void toggleMenu() {
            if (fullyClosed) return;
            boolean opening = menuState == MenuState.CLOSED || menuState == MenuState.CLOSING;
            menuVisible = opening;
            menuState = opening ? MenuState.OPENING : MenuState.CLOSING;
            for (OverlayStatisticModule module : statistics) module.setMenuVisible(menuVisible);
            if (menuVisible) {
                root.requestFocus();
                menuLayer.setVisibility(View.VISIBLE);
                if (menuOutline != null) menuOutline.start();
                panel.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                menuLayer.setAlpha(1f);
                panel.setAlpha(0f);
                prepareOpeningAnimation();
                for (OverlayStatisticModule module : statistics) {
                    if (module.isEnabled() && !module.startSafely()) {
                        rememberModuleState(module.key(), false);
                        module.setChecked(false);
                        Toast.makeText(activity, module.label() + " is unavailable", Toast.LENGTH_SHORT).show();
                    }
                    updateStatisticMonitor(module);
                }
                menuScrim.animate().cancel();
                menuScrim.setAlpha(0f);
                panel.animate().cancel();
                menuScrim.animate().alpha(1f).setDuration(animationDuration(true)).setInterpolator(menuInterpolator(true)).start();
                android.view.ViewPropertyAnimator panelAnimation = panel.animate().alpha(1f)
                        .setDuration(animationDuration(true)).setInterpolator(menuInterpolator(true));
                applyOpeningAnimation(panelAnimation);
                panelAnimation.withEndAction(() -> {
                    if (menuVisible) menuState = MenuState.OPEN;
                }).start();
            } else {
                root.clearFocus();
                hideMenuLayer();
            }
            syncStatisticExecution();
            floatingButton.animate().alpha(menuVisible ? 0f : config.opacity).setDuration(180).start();
            setMonitorAlpha(menuVisible ? 0f : config.opacity);
            root.post(this::updateMonitorLayout);
        }

        private void closeMenu() {
            boolean wasVisible = menuVisible;
            menuVisible = false;
            menuState = MenuState.CLOSING;
            root.clearFocus();
            for (OverlayStatisticModule module : statistics) module.setMenuVisible(false);
            hideMenuLayer();
            syncStatisticExecution();
            floatingButton.animate().alpha(config.opacity).setDuration(180).start();
            setMonitorAlpha(config.opacity);
            root.post(this::updateMonitorLayout);
            if (wasVisible) {
                Toast.makeText(activity, "Press the overlay button to open the menu again", Toast.LENGTH_SHORT).show();
            }
        }

        private void hideMenuLayer() {
            menuScrim.animate().cancel();
            panel.animate().cancel();
            android.view.ViewPropertyAnimator panelAnimation = panel.animate().alpha(0f)
                    .setDuration(animationDuration(false)).setInterpolator(menuInterpolator(false));
            applyClosingAnimation(panelAnimation);
            panelAnimation.start();
            menuScrim.animate().alpha(0f).setDuration(animationDuration(false)).setInterpolator(menuInterpolator(false)).withEndAction(() -> {
                if (!menuVisible) {
                    menuLayer.setVisibility(View.GONE);
                    panel.setAlpha(1f);
                    panel.setScaleX(1f);
                    panel.setScaleY(1f);
                    panel.setTranslationX(0f);
                    panel.setTranslationY(0f);
                    panel.setLayerType(View.LAYER_TYPE_NONE, null);
                    menuState = MenuState.CLOSED;
                    if (menuOutline != null) menuOutline.stop();
                    for (OverlayStatisticModule module : statistics) updateStatisticMonitor(module);
                }
            }).start();
        }

        private long animationDuration(boolean opening) {
            String animation = opening ? config.openingAnimation : config.closingAnimation;
            return "disabled".equals(animation) ? 0L : Math.max(0, config.animationDuration);
        }

        private void prepareOpeningAnimation() {
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            panel.setTranslationX(0f);
            panel.setTranslationY(0f);
            String animation = config.openingAnimation;
            if ("scale".equals(animation)) {
                panel.setScaleX(.01f);
                panel.setScaleY(.01f);
            } else if ("appearRight".equals(animation)) {
                panel.setTranslationX(horizontalAnimationDistance());
            } else if ("appearLeft".equals(animation)) {
                panel.setTranslationX(-horizontalAnimationDistance());
            } else if ("appearTop".equals(animation)) {
                panel.setTranslationY(-verticalAnimationDistance());
            } else if ("appearBottom".equals(animation)) {
                panel.setTranslationY(verticalAnimationDistance());
            }
        }

        private void applyOpeningAnimation(android.view.ViewPropertyAnimator animation) {
            animation.translationX(0f).translationY(0f);
            if ("scale".equals(config.openingAnimation)) animation.scaleX(1f).scaleY(1f);
        }

        private void applyClosingAnimation(android.view.ViewPropertyAnimator animation) {
            animation.scaleX(1f).scaleY(1f).translationX(0f).translationY(0f);
            if ("disappearUp".equals(config.closingAnimation)) animation.translationY(-verticalAnimationDistance());
            else if ("disappearDown".equals(config.closingAnimation)) animation.translationY(verticalAnimationDistance());
            else if ("disappearLeft".equals(config.closingAnimation)) animation.translationX(-horizontalAnimationDistance());
            else if ("disappearRight".equals(config.closingAnimation)) animation.translationX(horizontalAnimationDistance());
            else if ("scale".equals(config.closingAnimation)) animation.scaleX(.01f).scaleY(.01f);
        }

        private float horizontalAnimationDistance() {
            return animationOffset(panel.getWidth(), root.getWidth());
        }

        private float verticalAnimationDistance() {
            return animationOffset(panel.getHeight(), root.getHeight());
        }

        /**
         * Keep directional menu motion visible without sending the whole card off-screen.
         * The cap is important in landscape, where the available width can be much larger
         * than the centered menu panel.
         */
        private float animationOffset(int panelSize, int screenSize) {
            float proportionalOffset = panelSize * .35f;
            float screenOffset = screenSize * .22f;
            return Math.max(dp(24), Math.min(proportionalOffset, screenOffset));
        }

        private android.view.animation.Interpolator menuInterpolator(boolean opening) {
            if (!"logarithmic".equals(config.animationEasing)) {
                return new android.view.animation.LinearInterpolator();
            }
            return opening ? new android.view.animation.DecelerateInterpolator(2.5f)
                    : new android.view.animation.AccelerateInterpolator(2.5f);
        }

        private void showCloseConfirmation() {
            confirmationLayer.setAlpha(0f);
            confirmationLayer.setVisibility(View.VISIBLE);
            confirmationLayer.animate().alpha(1f).setDuration(180).start();
        }

        private void hideCloseConfirmation() {
            confirmationLayer.animate().alpha(0f).setDuration(160).withEndAction(() ->
                    confirmationLayer.setVisibility(View.GONE)).start();
        }

        private void fullyClose() {
            fullyClosed = true;
            if (!fullyClosedToastShown) {
                fullyClosedToastShown = true;
                Toast.makeText(activity,
                        "Universal Overlay is fully closed. Re-open the app to get it again",
                        Toast.LENGTH_LONG).show();
            }
            closeGlobally();
        }

        private void openRepository() {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(config.repositoryUrl));
                activity.startActivity(intent);
            } catch (RuntimeException ignored) {
                Toast.makeText(activity, "No app is available to open the repository link.", Toast.LENGTH_SHORT).show();
            }
        }

        private boolean onButtonTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX(); downY = event.getRawY();
                    startX = view.getX(); startY = view.getY(); dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) dragged = true;
                    if (dragged) {
                        view.setX(clamp(startX + dx, 0, root.getWidth() - view.getWidth()));
                        view.setY(clamp(startY + dy, 0, root.getHeight() - view.getHeight()));
                        // Keep the control fully visible while dragging and reset the fade timer
                        // for every movement so repeated dragging never fades mid-drag.
                        showButtonFullyVisibleAfterDrag();
                        updateMonitorLayout();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged) view.performClick();
                    else {
                        sharedButtonPositionInitialized = true;
                        sharedButtonX = Math.max(0, (int) view.getX());
                        sharedButtonY = Math.max(0, (int) view.getY());
                        // Start the full-visibility countdown after the finger is released.
                        showButtonFullyVisibleAfterDrag();
                        updateMonitorLayout();
                    }
                    return true;
                default: return true;
            }
        }

        private int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density + .5f); }

    }

    private interface Toggle { boolean changed(boolean checked); }

    private interface ActivityModuleFactory {
        OverlayActivityModule create();
    }

    private interface StatisticModuleFactory {
        OverlayStatisticModule create();
    }

    private interface HookModuleFactory {
        OverlayHookModule create();
    }

    private interface AppSpecificModuleFactory {
        OverlayAppSpecificModule create();
    }

    private static final class BoundedScrollView extends ScrollView {
        private final int maxHeight;

        BoundedScrollView(android.content.Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(getMeasuredWidth(), Math.min(getMeasuredHeight(), maxHeight));
        }
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(value, max)); }

}
