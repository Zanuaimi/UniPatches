package unipatch.universaloverlay;

import android.graphics.Color;
import android.view.Gravity;

/**
 * Decodes and validates overlay configuration inside the extension runtime.
 * The patch-building Kotlin code supplies the current version; older payloads remain supported.
 */
final class UniversalOverlayConfig {
    private static final String DEFAULT_ACTIVITY_INSTALL_BANLIST =
            "com.google.android.gms.games.*\n" +
            "com.google.android.play.games.*\n" +
            "com.google.android.gms.auth.api.signin.*\n" +
            "com.google.android.gms.common.api.*\n" +
            "com.android.billingclient.*\n" +
            "com.android.vending.billing.*\n" +
            "com.android.vending.*\n" +
            "com.xiaomi.market.*\n" +
            "com.huawei.appmarket.*\n" +
            "ru.vk.store.*\n" +
            "ru.rustore.*\n" +
            "com.heytap.market.*\n" +
            "com.oppo.market.*\n" +
            "com.sec.android.app.samsungapps.*";
    private static final String DEFAULT_DESCRIPTION =
            "Welcome! This is the UniPatches Universal Overlay Patch Menu. " +
            "The idea and initial works of Universal Overlay Patch are from Zanuaimi / Noobite.";
    String title, description, appendDescription, descriptionAlignment, repositoryText, repositoryUrl, buttonText;
    String activityInstallBanlist;
    int background, outline, overlayTextColor, buttonTextColor, buttonBackground, buttonSize, gravity;
    int outlineWidth, iconOutlineColor, iconBackground2, iconGradientAngle, iconOutlineWidth, iconTextSize;
    int backgroundTransparency;
    float opacity;
    int shape;
    boolean iconOutline, iconBold;
    boolean gradientBackground;
    String iconType, customIconImage;
    int dragVisibilityDurationSeconds;
    boolean keepAwake, fullscreen, screenshots;
    boolean systemTime, fps, sessionTime;
    boolean batteryStatus, appMemory, networkStatus, deviceInformation, deviceTemperature;
    boolean appBrightness, rotationMode, appAudioMute, disableHaptics, disableAnimations;
    boolean activateStatisticsOnLaunch, enableMonitorsOnLaunch, showNoModulesWarning;
    int statisticMonitorPosition, monitorColumns;
    float monitorScale;
    String temperatureFormat, timeFormat;
    String controlTheme, bottomButtonStyle, bottomButtonShape, separatorStyle,
            titleIconPlacement, titleAlignment, menuCorners, menuOutlineAnimation,
            openingAnimation, closingAnimation, animationEasing;
    int controlBackground, controlForeground, bottomButtonTextColor,
            bottomButtonBackground1, bottomButtonBackground2,
            menuTextColor1, menuTextColor2, menuTextColor3, menuTextColor4, menuTextColor5, menuTextColor6,
            outlineAnimationSpeed, animationDuration, appendDescriptionColor, separatorBackgroundColor;
    boolean bottomButtonPadding, titleSeparator;

    static UniversalOverlayConfig decode(String encoded) {
        UniversalOverlayConfig c = new UniversalOverlayConfig();
        String[] values = encoded == null ? new String[0] : encoded.split("\\|", -1);
        // Version 1 through 16 prepends a version field. Keep accepting the original 14-field format so an
        // older generated patch remains safe when paired with this newer extension.
        String[] v = new String[67];
        for (int i = 0; i < v.length; i++) v[i] = i < values.length ? decodePart(values[i]) : "";
        int offset = ("1".equals(v[0]) || "2".equals(v[0]) || "3".equals(v[0]) || "4".equals(v[0]) || "5".equals(v[0]) || "6".equals(v[0]) || "7".equals(v[0]) || "8".equals(v[0]) || "9".equals(v[0]) || "10".equals(v[0]) || "11".equals(v[0]) || "12".equals(v[0]) || "13".equals(v[0]) || "14".equals(v[0]) || "15".equals(v[0]) || "16".equals(v[0])) ? 1 : 0;
        c.title = limit(field(v, offset, 0), 80, "UniPatches Universal Overlay Patch");
        c.description = limit(field(v, offset, 1), 500, DEFAULT_DESCRIPTION);
        c.appendDescription = limit(field(v, offset, 58), 500, "");
        c.descriptionAlignment = choice(field(v, offset, 59), "center", "left", "center", "right");
        c.repositoryText = empty(field(v, offset, 2), "UniPatches repository");
        c.repositoryUrl = validUrl(field(v, offset, 3));
        boolean currentColorFormat = "1".equals(v[0]) || "11".equals(v[0]) || "12".equals(v[0]) || "13".equals(v[0]) || "14".equals(v[0]) || "15".equals(v[0]) || "16".equals(v[0]);
        int rawBackground = color(field(v, offset, 4), currentColorFormat ? 0xFF300000 : 0x80FF0000);
        c.backgroundTransparency = currentColorFormat ? integer(field(v, offset, 30), 80, 0, 100) : Math.round(Color.alpha(rawBackground) * 100f / 255f);
        c.background = currentColorFormat ? withAlpha(rawBackground, c.backgroundTransparency) : rawBackground;
        c.outline = color(field(v, offset, 5), 0xFFFF5656);
        c.overlayTextColor = color(currentColorFormat ? field(v, offset, 31) : "", c.outline);
        c.buttonText = limit(empty(field(v, offset, 6), "U"), 3, "U");
        c.buttonTextColor = color(field(v, offset, 7), 0xFFFFFFFF);
        c.buttonBackground = color(field(v, offset, 8), 0xFF500000);
        String shape = field(v, offset, 9);
        c.shape = "square".equals(shape) ? 0 : ("squircle".equals(shape) ? 2 : 1);
        c.buttonSize = integer(field(v, offset, 10), 56, 32, 128);
        c.opacity = integer(field(v, offset, 11), 50, 10, 100) / 100f;
        c.gravity = gravity(field(v, offset, 12));
        String controls = field(v, offset, 13);
        c.keepAwake = hasToken(controls, "keep");
        c.fullscreen = hasToken(controls, "fullscreen");
        c.screenshots = hasToken(controls, "screenshots");
        c.systemTime = hasToken(controls, "systemTime");
        c.fps = hasToken(controls, "fps");
        c.sessionTime = hasToken(controls, "sessionTime");
        c.batteryStatus = hasToken(controls, "batteryStatus");
        c.appMemory = hasToken(controls, "appMemory");
        c.networkStatus = hasToken(controls, "networkStatus");
        c.deviceInformation = hasToken(controls, "deviceInformation");
        c.deviceTemperature = hasToken(controls, "deviceTemperature");
        c.appBrightness = hasToken(controls, "appBrightness");
        c.rotationMode = hasToken(controls, "rotationMode");
        c.appAudioMute = hasToken(controls, "appAudioMute");
        c.disableHaptics = hasToken(controls, "disableHaptics");
        c.disableAnimations = hasToken(controls, "disableAnimations");
        c.activateStatisticsOnLaunch = "1".equals(field(v, offset, 14));
        boolean currentFormat = "1".equals(v[0]) || "5".equals(v[0]) || "6".equals(v[0]) || "7".equals(v[0]) || "8".equals(v[0]) || "9".equals(v[0]) || "10".equals(v[0]) || "11".equals(v[0]) || "12".equals(v[0]) || "13".equals(v[0]) || "14".equals(v[0]) || "15".equals(v[0]) || "16".equals(v[0]);
        c.enableMonitorsOnLaunch = currentFormat && "1".equals(field(v, offset, 15));
        int monitorPositionIndex = currentFormat ? 16 : 15;
        int monitorScaleIndex = currentFormat ? 17 : 16;
        int monitorColumnsIndex = currentFormat ? 18 : 17;
        String monitorPosition = field(v, offset, monitorPositionIndex);
        c.statisticMonitorPosition = "top".equals(monitorPosition) ? 1
                : ("bottom".equals(monitorPosition) ? 2 : 0);
        c.monitorScale = floatValue(field(v, offset, monitorScaleIndex), 1f, .5f, 2f);
        c.monitorColumns = integer(field(v, offset, monitorColumnsIndex), 2, 1, 3);
        boolean extendedFormat = "1".equals(v[0]) || "6".equals(v[0]) || "7".equals(v[0]) || "8".equals(v[0]) || "9".equals(v[0]) || "10".equals(v[0]) || "11".equals(v[0]) || "12".equals(v[0]) || "13".equals(v[0]) || "14".equals(v[0]) || "15".equals(v[0]) || "16".equals(v[0]);
        c.temperatureFormat = extendedFormat && "fahrenheit".equals(field(v, offset, 19)) ? "fahrenheit"
                : (extendedFormat && "kelvin".equals(field(v, offset, 19)) ? "kelvin" : "celsius");
        c.timeFormat = extendedFormat && "24".equals(field(v, offset, 20)) ? "24" : "12";
        boolean customizationFormat = "1".equals(v[0]) || "7".equals(v[0]) || "8".equals(v[0]) || "9".equals(v[0]) || "10".equals(v[0]) || "11".equals(v[0]) || "12".equals(v[0]) || "13".equals(v[0]) || "14".equals(v[0]) || "15".equals(v[0]) || "16".equals(v[0]);
        boolean automaticIconFormat = "1".equals(v[0]) || "10".equals(v[0]) || "11".equals(v[0]) || "12".equals(v[0]) || "13".equals(v[0]) || "14".equals(v[0]) || "15".equals(v[0]) || "16".equals(v[0]);
        c.outlineWidth = customizationFormat ? integer(field(v, offset, 21), 1, 1, 8) : 1;
        c.iconOutline = customizationFormat && "1".equals(field(v, offset, 22));
        c.iconOutlineColor = color(customizationFormat ? field(v, offset, 23) : "", 0xFFFFFFFF);
        int iconBoldIndex = automaticIconFormat ? 24 : 25;
        int iconBackgroundIndex = automaticIconFormat ? 25 : 26;
        int iconGradientIndex = automaticIconFormat ? 26 : 27;
        int customIconIndex = automaticIconFormat ? 27 : 28;
        int dragDurationIndex = automaticIconFormat ? 28 : 29;
        int gradientToggleIndex = automaticIconFormat ? 29 : 30;
        c.iconBold = !customizationFormat || "1".equals(field(v, offset, iconBoldIndex));
        c.iconBackground2 = color(customizationFormat ? field(v, offset, iconBackgroundIndex) : "", 0xFFAA0000);
        c.iconGradientAngle = customizationFormat ? integer(field(v, offset, iconGradientIndex), 0, 0, 360) : 0;
        c.customIconImage = customizationFormat ? field(v, offset, customIconIndex) : "";
        c.iconType = c.customIconImage.isEmpty() ? "legacy" : "image";
        c.dragVisibilityDurationSeconds = customizationFormat
                ? integer(field(v, offset, dragDurationIndex), 2, 1, 10) : 2;
        // v8 had no gradient toggle, v9 stores the legacy-format toggle, and v10-v12 store the
        // automatic-icon-format toggle after the image payload.
        c.gradientBackground = automaticIconFormat
                ? "1".equals(field(v, offset, gradientToggleIndex))
                : (!"9".equals(v[0]) || "1".equals(field(v, offset, gradientToggleIndex)));
        c.iconOutlineWidth = currentColorFormat ? integer(field(v, offset, 32), 3, 1, 8) : Math.min(8, Math.max(2, c.outlineWidth + 1));
        c.iconTextSize = ("1".equals(v[0]) || "12".equals(v[0]) || "13".equals(v[0]) || "14".equals(v[0]) || "15".equals(v[0]) || "16".equals(v[0])) ? integer(field(v, offset, 33), 18, 8, 48) : 18;
        c.controlTheme = choice(field(v, offset, 34), "modern", "legacy", "modern", "monet");
        c.controlBackground = color(field(v, offset, 35), 0xFF300000);
        c.controlForeground = color(field(v, offset, 36), 0xFFFF5656);
        c.bottomButtonStyle = choice(field(v, offset, 37), "text", "text", "solid", "gradient");
        c.bottomButtonShape = choice(field(v, offset, 38), "square", "square", "squircle");
        c.bottomButtonPadding = "1".equals(field(v, offset, 39));
        c.bottomButtonTextColor = color(field(v, offset, 40), 0xFFFFFFFF);
        c.bottomButtonBackground1 = color(field(v, offset, 41), 0xFF500000);
        c.bottomButtonBackground2 = color(field(v, offset, 42), 0xFFAA0000);
        c.menuTextColor1 = color(field(v, offset, 43), c.overlayTextColor);
        c.menuTextColor2 = color(field(v, offset, 44), c.overlayTextColor);
        c.menuTextColor3 = color(field(v, offset, 45), c.overlayTextColor);
        c.menuTextColor4 = color(field(v, offset, 46), c.overlayTextColor);
        c.menuTextColor5 = color(field(v, offset, 47), c.overlayTextColor);
        c.menuTextColor6 = color(field(v, offset, 63), c.menuTextColor2);
        c.separatorBackgroundColor = color(field(v, offset, 64), c.background);
        c.activityInstallBanlist = empty(field(v, offset, 65), DEFAULT_ACTIVITY_INSTALL_BANLIST);
        c.appendDescriptionColor = color(field(v, offset, 60), c.menuTextColor3);
        c.showNoModulesWarning = !"0".equals(field(v, offset, 61));
        c.separatorStyle = choice(field(v, offset, 48), "ascii", "ascii", "doubleLine", "background", "singleLine", "inline");
        c.titleIconPlacement = choice(field(v, offset, 49), "none", "none", "left", "right", "both");
        c.titleAlignment = choice(field(v, offset, 50), "left", "left", "center", "right");
        c.titleSeparator = "1".equals(field(v, offset, 51));
        c.menuCorners = choice(field(v, offset, 52), "rounded", "rounded", "square");
        c.menuOutlineAnimation = choice(field(v, offset, 53), "static", "static", "gradient", "rainbow");
        c.outlineAnimationSpeed = integer(field(v, offset, 54), 1, 0, 10);
        c.openingAnimation = choice(field(v, offset, 55), "fade", "fade", "scale", "disabled", "appearRight", "appearTop", "appearBottom", "appearLeft");
        c.animationDuration = integer(field(v, offset, 56), 180, 0, 5000);
        c.animationEasing = choice(field(v, offset, 57), "linear", "linear", "logarithmic");
        c.closingAnimation = choice(field(v, offset, 62), c.openingAnimation,
                "fade", "scale", "disabled", "disappearUp", "disappearDown", "disappearLeft", "disappearRight");
        int descriptionRemaining = Math.max(0, 500 - c.description.length());
        if (c.appendDescription.length() > descriptionRemaining) {
            c.appendDescription = c.appendDescription.substring(0, descriptionRemaining);
        }
        return c;
    }

    private static String choice(String value, String fallback, String... allowed) {
        for (String item : allowed) if (item.equals(value)) return value;
        return fallback;
    }

    private static boolean hasToken(String values, String token) {
        for (String value : values.split(",")) if (token.equals(value)) return true;
        return false;
    }

    private static String field(String[] values, int offset, int index) {
        int position = offset + index;
        return position < values.length ? values[position] : "";
    }

    private static String decodePart(String value) {
        try { return new String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), java.nio.charset.Charset.forName("UTF-8")); }
        catch (RuntimeException ignored) { return ""; }
    }

    private static String empty(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private static String limit(String value, int max, String fallback) { String result = empty(value, fallback); return result.substring(0, Math.min(max, result.length())); }
    private static String validUrl(String value) { return value.startsWith("http://") || value.startsWith("https://") ? value : "https://github.com/Zanuaimi/UniPatches"; }
    private static int integer(String value, int fallback, int min, int max) { try { return Math.max(min, Math.min(max, Integer.parseInt(value))); } catch (RuntimeException ignored) { return fallback; } }
    private static float floatValue(String value, float fallback, float min, float max) {
        try {
            float parsed = Float.parseFloat(value);
            return Float.isNaN(parsed) || Float.isInfinite(parsed) ? fallback : Math.max(min, Math.min(max, parsed));
        } catch (RuntimeException ignored) { return fallback; }
    }
    private static int color(String value, int fallback) {
        try {
            String v = value.startsWith("#") ? value.substring(1) : value;
            if (v.length() == 6) v = "FF" + v;
            if (v.length() != 8) return fallback;
            return (int) Long.parseLong(v, 16);
        } catch (RuntimeException ignored) { return fallback; }
    }
    private static int withAlpha(int color, int transparencyPercent) {
        int alpha = Math.round(255f * Math.max(0, Math.min(100, transparencyPercent)) / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
    private static int gravity(String value) {
        if ("topLeft".equals(value)) return Gravity.TOP | Gravity.LEFT;
        if ("topMiddle".equals(value)) return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if ("centerLeft".equals(value)) return Gravity.CENTER_VERTICAL | Gravity.LEFT;
        if ("centerRight".equals(value)) return Gravity.CENTER_VERTICAL | Gravity.RIGHT;
        if ("bottomLeft".equals(value)) return Gravity.BOTTOM | Gravity.LEFT;
        if ("bottomMiddle".equals(value)) return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        if ("bottomRight".equals(value)) return Gravity.BOTTOM | Gravity.RIGHT;
        return Gravity.TOP | Gravity.RIGHT;
    }
}
