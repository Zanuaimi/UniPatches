package unipatch.overlaycore;

import android.graphics.Color;
import android.view.Gravity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes and validates overlay configuration inside the extension runtime.
 * The patch-building Kotlin code supplies the current version; older payloads remain supported.
 */
final class OverlayConfig {
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
    String appSpecificProfile, injectionMode, appSpecificModules;
    String activityInstallBanlist;
    int background, outline, overlayTextColor, buttonTextColor, buttonBackground, buttonSize, gravity;
    int outlineWidth, iconOutlineColor, iconBackground2, iconGradientAngle, iconOutlineWidth, iconTextSize,
            iconTextColor2, iconTextGradientAngle;
    int menuWidthLimitPercent, menuHeightLimitPercent;
    int backgroundTransparency;
    float opacity;
    int shape;
    boolean iconOutline, iconBold;
    boolean gradientBackground;
    String iconType, customIconImage;
    String legacyIconJson;
    String[] iconParts;
    int dragVisibilityDurationSeconds;
    boolean keepAwake, fullscreen, screenshots;
    boolean systemTime, fps, sessionTime;
    boolean batteryStatus, appMemory, networkStatus, deviceInformation, deviceTemperature;
    boolean appBrightness, rotationMode, appAudioMute, disableHaptics, disableAnimations;
    boolean includeDoNotDisturb, includeOverlayRuntimeLogs, enableOverlayRuntimeLogsOnLaunch,
            showExtraPopupHeaders;
    boolean activateStatisticsOnLaunch, enableMonitorsOnLaunch, showNoModulesWarning;
    int statisticMonitorPosition, monitorColumns;
    float monitorScale;
    String temperatureFormat, timeFormat;
    String controlTheme, bottomButtonStyle, bottomButtonShape, separatorStyle,
            titleIconPlacement, titleAlignment, menuCorners, menuOutlineAnimation,
            openingAnimation, closingAnimation, animationEasing,
            iconStyle, iconShape, iconBackgroundStyle, iconTextFont, menuTextFont;
    int controlBackground, controlForeground, controlOutlineColor, bottomButtonTextColor,
            bottomButtonBackground1, bottomButtonBackground2,
            menuTextColor1, menuTextColor2, menuTextColor3, menuTextColor4, menuTextColor5, menuTextColor6,
            outlineAnimationSpeed, animationDuration, appendDescriptionColor, separatorBackgroundColor,
            iconShapeColor1, iconShapeColor2, iconShapeGradientAngle, iconShapeStrokeWidth, iconShapeScale,
            iconOutlineColor2, iconOutlineGradientAngle, iconBackgroundColor3, iconBackgroundColor4,
            iconShadowColor, iconShadowOpacity, iconShadowOffsetX, iconShadowOffsetY,
            iconShadowBlur, iconShadowSpread;
    boolean bottomButtonPadding, titleSeparator, iconShapeGradient, iconHighlight, iconShadow, iconOutlineGradient;
    boolean managerIntegration, managerPersistence, iconTextGradient;

    static OverlayConfig decode(String encoded) {
        OverlayConfig c = new OverlayConfig();
        String[] values = encoded == null ? new String[0] : encoded.split("\\|", -1);
        // Version 1 through 16 prepends a version field. Keep accepting the original 14-field format so an
        // older generated patch remains safe when paired with this newer extension.
        String[] v = new String[110];
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
        c.controlOutlineColor = color(field(v, offset, 86), c.outline);
        c.bottomButtonStyle = choice(field(v, offset, 37), "text", "text", "solid", "gradient");
        c.bottomButtonShape = choice(field(v, offset, 38), "square", "square", "squircle");
        c.bottomButtonPadding = "1".equals(field(v, offset, 39));
        c.bottomButtonTextColor = color(field(v, offset, 40), 0xFFFF5656);
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
        // v2.1 keeps only Text and Multi-parts. Older shape/multi values safely become Text.
        c.iconStyle = "parts".equals(field(v, offset, 66)) ? "parts" : "text";
        c.iconShape = choice(field(v, offset, 67), "triangle", "triangle", "chevron", "smile", "circle", "z", "revanced");
        c.iconShapeColor1 = color(field(v, offset, 68), 0xFFFFFFFF);
        c.iconShapeColor2 = color(field(v, offset, 69), c.iconShapeColor1);
        c.iconShapeGradient = "1".equals(field(v, offset, 70));
        c.iconShapeGradientAngle = integer(field(v, offset, 71), 0, 0, 360);
        c.iconShapeStrokeWidth = integer(field(v, offset, 72), 3, 1, 12);
        c.iconShapeScale = integer(field(v, offset, 73), 70, 20, 100);
        c.iconHighlight = "1".equals(field(v, offset, 74));
        c.iconShadow = "1".equals(field(v, offset, 75));
        c.iconOutlineGradient = "1".equals(field(v, offset, 76));
        c.iconOutlineColor2 = color(field(v, offset, 77), c.iconOutlineColor);
        c.iconOutlineGradientAngle = integer(field(v, offset, 78), 0, 0, 360);
        c.iconBackgroundStyle = choice(field(v, offset, 79), "flat", "flat", "faceted");
        c.iconBackgroundColor3 = color(field(v, offset, 80), c.iconBackground2);
        c.iconBackgroundColor4 = color(field(v, offset, 81), c.background);
        c.iconShadowColor = 0xFF000000;
        c.iconShadowOpacity = 60;
        c.iconShadowOffsetX = 0;
        c.iconShadowOffsetY = 3;
        c.iconShadowBlur = 4;
        c.iconShadowSpread = 0;
        c.iconParts = field(v, offset, 84).isEmpty()
                ? new String[0]
                : field(v, offset, 84).split("[\\r\\n]+", 13);
        c.appSpecificProfile = field(v, offset, 82);
        c.injectionMode = choice(field(v, offset, 83), "universal", "universal", "explicitActivity");
        c.appSpecificModules = field(v, offset, 85);
        c.iconTextFont = choice(field(v, offset, 87), "default",
            "default", "roboto", "sansSerif", "serif", "monospace", "sansCondensed", "sansMedium", "sansBlack");
        c.menuTextFont = choice(field(v, offset, 88), "default",
            "default", "roboto", "sansSerif", "serif", "monospace", "sansCondensed", "sansMedium", "sansBlack");
        c.legacyIconJson = field(v, offset, 89);
        c.menuWidthLimitPercent = integer(field(v, offset, 94), 90, 45, 90);
        c.menuHeightLimitPercent = integer(field(v, offset, 95), 45, 45, 90);
        c.includeDoNotDisturb = "1".equals(field(v, offset, 90));
        c.includeOverlayRuntimeLogs = "1".equals(field(v, offset, 91));
        c.enableOverlayRuntimeLogsOnLaunch = "1".equals(field(v, offset, 92));
        c.showExtraPopupHeaders = "1".equals(field(v, offset, 93));
        c.managerIntegration = "1".equals(field(v, offset, 96));
        c.managerPersistence = "1".equals(field(v, offset, 97));
        c.iconTextGradient = "1".equals(field(v, offset, 98));
        c.iconTextColor2 = color(field(v, offset, 99), c.buttonTextColor);
        c.iconTextGradientAngle = integer(field(v, offset, 100), 90, 0, 360);
        applyLegacyIconJson(c);
        c.appendDescriptionColor = color(field(v, offset, 60), c.menuTextColor3);
        c.showNoModulesWarning = !"0".equals(field(v, offset, 61));
        c.separatorStyle = choice(field(v, offset, 48), "ascii", "ascii", "doubleLine", "background", "singleLine", "inline");
        c.titleIconPlacement = choice(field(v, offset, 49), "none", "none", "left", "right", "both");
        c.titleAlignment = choice(field(v, offset, 50), "left", "left", "center", "right");
        c.titleSeparator = "1".equals(field(v, offset, 51));
        c.menuCorners = choice(field(v, offset, 52), "rounded", "rounded", "square");
        c.menuOutlineAnimation = choice(field(v, offset, 53), "static", "static", "gradient", "vertical", "rainbow");
        c.outlineAnimationSpeed = integer(field(v, offset, 54), 1, -10, 10);
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

    private static void applyLegacyIconJson(OverlayConfig c) {
        if (c.legacyIconJson == null || c.legacyIconJson.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(c.legacyIconJson);
            if (!"unipatches-legacy-icon".equals(root.optString("format")) || root.optInt("version", -1) != 1) return;
            JSONObject settings = root.optJSONObject("settings");
            if (settings == null) return;

            c.buttonSize = integer(settings.optString("iconSize", ""), c.buttonSize, 32, 128);
            c.opacity = integer(settings.optString("iconOpacity", ""), Math.round(c.opacity * 100f), 10, 100) / 100f;
            c.gravity = gravity(settings.optString("iconPosition", ""));
            String buttonShape = settings.optString("buttonShape", "");
            c.shape = "square".equals(buttonShape) ? 0 : ("squircle".equals(buttonShape) ? 2 : ("circle".equals(buttonShape) ? 1 : c.shape));

            c.buttonBackground = parseJsonColor(settings, "background", c.buttonBackground);
            c.iconBackground2 = parseJsonColor(settings, "background2", c.iconBackground2);
            c.iconGradientAngle = jsonInt(settings, "backgroundAngle", c.iconGradientAngle, 0, 360);
            c.gradientBackground = settings.has("backgroundGradient") && settings.optBoolean("backgroundGradient", c.gradientBackground);
            c.iconBackgroundStyle = choice(settings.optString("backgroundStyle", c.iconBackgroundStyle), c.iconBackgroundStyle, "flat", "faceted");
            c.iconOutlineColor = parseJsonColor(settings, "outline", c.iconOutlineColor);
            c.iconOutlineColor2 = parseJsonColor(settings, "outline2", c.iconOutlineColor2);
                c.iconOutline = settings.has("outlineEnabled")
                    ? settings.optBoolean("outlineEnabled", c.iconOutline) : c.iconOutline;
            c.iconOutlineWidth = jsonInt(settings, "outlineWidth", c.iconOutlineWidth, 1, 8);
            c.iconOutlineGradient = settings.optBoolean("outlineGradient", c.iconOutlineGradient);
            c.iconOutlineGradientAngle = jsonInt(settings, "outlineAngle", c.iconOutlineGradientAngle, 0, 360);
            c.iconHighlight = settings.optBoolean("highlight", c.iconHighlight);

            c.buttonText = limit(settings.optString("legacyText", c.buttonText), 3, c.buttonText);
            c.iconTextSize = jsonInt(settings, "legacyTextSize", c.iconTextSize, 8, 48);
            c.iconBold = settings.optBoolean("legacyBold", c.iconBold);
            c.iconTextFont = choice(settings.optString("legacyTextFont", c.iconTextFont), c.iconTextFont,
                    "default", "roboto", "sansSerif", "serif", "monospace", "sansCondensed", "sansMedium", "sansBlack");
            c.buttonTextColor = parseJsonColor(settings, "legacyTextColor", c.buttonTextColor);
            c.iconShape = choice(settings.optString("legacyShape", c.iconShape), c.iconShape,
                    "triangle", "roundedTriangle", "circle", "square", "roundedRect", "diamond", "star", "heart");
            c.iconShapeColor1 = parseJsonColor(settings, "legacyShapeColor1", c.iconShapeColor1);
            c.iconShapeColor2 = parseJsonColor(settings, "legacyShapeColor2", c.iconShapeColor2);
            c.iconShapeGradient = settings.optBoolean("legacyShapeGradient", c.iconShapeGradient);
            c.iconShapeGradientAngle = jsonInt(settings, "legacyShapeAngle", c.iconShapeGradientAngle, 0, 360);
            c.iconShapeStrokeWidth = jsonInt(settings, "legacyShapeStroke", c.iconShapeStrokeWidth, 1, 12);
            c.iconShapeScale = jsonInt(settings, "legacyShapeScale", c.iconShapeScale, 20, 100);
            c.iconShadow = settings.optBoolean("shadowEnabled", c.iconShadow);
            c.iconShadowColor = parseJsonColor(settings, "shadowColor", c.iconShadowColor);
            c.iconShadowOpacity = jsonInt(settings, "shadowOpacity", c.iconShadowOpacity, 0, 100);
            c.iconShadowOffsetX = jsonInt(settings, "shadowOffsetX", c.iconShadowOffsetX, -32, 32);
            c.iconShadowOffsetY = jsonInt(settings, "shadowOffsetY", c.iconShadowOffsetY, -32, 32);
            c.iconShadowBlur = jsonInt(settings, "shadowBlur", c.iconShadowBlur, 0, 32);
            c.iconShadowSpread = jsonInt(settings, "shadowSpread", c.iconShadowSpread, 0, 16);

            JSONArray parts = settings.optJSONArray("iconParts");
            String iconMode = settings.optString("iconMode", "");
            if ("parts".equals(iconMode) && parts != null) {
                List<String> validParts = new ArrayList<>();
                for (int i = 0; i < parts.length() && validParts.size() < 12; i++) {
                    String part = parts.optString(i, "");
                    if (validIconPart(part)) validParts.add(part);
                }
                c.iconParts = validParts.toArray(new String[0]);
                c.iconStyle = "parts";
            } else {
                c.iconStyle = "text";
            }
        } catch (Exception ignored) {
            // Invalid optional JSON must leave the normal payload settings active.
        }
    }

    /** Applies manager-owned startup overrides while retaining patch-time values as fallbacks. */
    static void applyManagedConfiguration(OverlayConfig c, String encoded) {
        if (c == null || encoded == null || encoded.trim().isEmpty()) return;
        try {
            JSONObject values = new JSONObject(encoded);
            c.menuWidthLimitPercent = jsonInt(values, "runtimeOverlayMenuWidthLimit", c.menuWidthLimitPercent, 45, 90);
            c.menuHeightLimitPercent = jsonInt(values, "runtimeOverlayMenuHeightLimit", c.menuHeightLimitPercent, 45, 90);
            c.title = limit(values.optString("runtimeOverlayTitle", c.title), 80, c.title);
            c.description = limit(values.optString("runtimeOverlayDescription", c.description), 500, c.description);
            c.appendDescription = limit(values.optString("runtimeOverlayAppendDescription", c.appendDescription), 500, c.appendDescription);
            c.descriptionAlignment = choice(values.optString("runtimeOverlayDescriptionAlignment", c.descriptionAlignment), c.descriptionAlignment, "left", "center", "right");
            c.appendDescriptionColor = parseJsonColor(values, "runtimeOverlayAppendDescriptionColor", c.appendDescriptionColor);
            c.background = colorWithManagedTransparency(values, "runtimeOverlayBackgroundColor", "runtimeOverlayBackgroundTransparency", c.background, c.backgroundTransparency);
            c.backgroundTransparency = jsonInt(values, "runtimeOverlayBackgroundTransparency", c.backgroundTransparency, 0, 100);
            c.outline = parseJsonColor(values, "runtimeOverlayOutlineColor", c.outline);
            c.repositoryText = empty(values.optString("runtimeOverlayRepositoryText", c.repositoryText), c.repositoryText);
            c.repositoryUrl = validUrl(values.optString("runtimeOverlayRepositoryUrl", c.repositoryUrl));
            c.buttonText = limit(values.optString("runtimeOverlayButtonText", c.buttonText), 3, c.buttonText);
            c.iconBold = values.has("runtimeOverlayIconBold") ? values.optBoolean("runtimeOverlayIconBold") : c.iconBold;
            c.buttonTextColor = parseJsonColor(values, "runtimeOverlayButtonTextColor", c.buttonTextColor);
            c.iconTextGradient = values.optBoolean("runtimeOverlayIconTextGradient", c.iconTextGradient);
            c.iconTextColor2 = parseJsonColor(values, "runtimeOverlayIconTextColor2", c.iconTextColor2);
            c.iconTextGradientAngle = jsonInt(values, "runtimeOverlayIconTextGradientAngle", c.iconTextGradientAngle, 0, 360);
            c.iconTextSize = jsonInt(values, "runtimeOverlayIconTextSizeSp", c.iconTextSize, 8, 48);
            c.iconTextFont = choice(values.optString("runtimeOverlayIconTextFont", c.iconTextFont), c.iconTextFont, "default", "roboto", "sansSerif", "serif", "monospace", "sansCondensed", "sansMedium", "sansBlack");
            c.menuTextFont = choice(values.optString("runtimeOverlayMenuTextFont", c.menuTextFont), c.menuTextFont, "default", "roboto", "sansSerif", "serif", "monospace", "sansCondensed", "sansMedium", "sansBlack");
            c.iconStyle = choice(values.optString("runtimeOverlayIconStyle", c.iconStyle), c.iconStyle, "text", "parts");
            c.iconHighlight = values.optBoolean("runtimeOverlayIconHighlight", c.iconHighlight);
            c.gradientBackground = values.optBoolean("runtimeOverlayIconGradientBackground", c.gradientBackground);
            c.buttonBackground = parseJsonColor(values, "runtimeOverlayButtonBackgroundColor", c.buttonBackground);
            c.iconBackground2 = parseJsonColor(values, "runtimeOverlayIconBackgroundColor2", c.iconBackground2);
            c.iconGradientAngle = jsonInt(values, "runtimeOverlayIconGradientAngle", c.iconGradientAngle, 0, 360);
            c.iconBackgroundStyle = choice(values.optString("runtimeOverlayIconBackgroundStyle", c.iconBackgroundStyle), c.iconBackgroundStyle, "flat", "faceted");
            c.iconBackgroundColor3 = parseJsonColor(values, "runtimeOverlayIconBackgroundColor3", c.iconBackgroundColor3);
            c.iconBackgroundColor4 = parseJsonColor(values, "runtimeOverlayIconBackgroundColor4", c.iconBackgroundColor4);
            c.iconOutline = values.optBoolean("runtimeOverlayIconOutline", c.iconOutline);
            c.iconOutlineWidth = jsonInt(values, "runtimeOverlayIconOutlineWidthDp", c.iconOutlineWidth, 1, 8);
            c.iconOutlineColor = parseJsonColor(values, "runtimeOverlayIconOutlineColor", c.iconOutlineColor);
            c.iconOutlineGradient = values.optBoolean("runtimeOverlayIconOutlineGradient", c.iconOutlineGradient);
            c.iconOutlineColor2 = parseJsonColor(values, "runtimeOverlayIconOutlineColor2", c.iconOutlineColor2);
            c.iconOutlineGradientAngle = jsonInt(values, "runtimeOverlayIconOutlineGradientAngle", c.iconOutlineGradientAngle, 0, 360);
            c.iconShape = choice(values.optString("runtimeOverlayIconShape", c.iconShape), c.iconShape, "triangle", "triangle", "chevron", "smile", "circle", "z", "revanced");
            c.iconShapeColor1 = parseJsonColor(values, "runtimeOverlayIconShapeColor1", c.iconShapeColor1);
            c.iconShapeColor2 = parseJsonColor(values, "runtimeOverlayIconShapeColor2", c.iconShapeColor2);
            c.iconShapeGradient = values.optBoolean("runtimeOverlayIconShapeGradient", c.iconShapeGradient);
            c.iconShapeGradientAngle = jsonInt(values, "runtimeOverlayIconShapeGradientAngle", c.iconShapeGradientAngle, 0, 360);
            c.iconShapeStrokeWidth = jsonInt(values, "runtimeOverlayIconShapeStrokeWidth", c.iconShapeStrokeWidth, 1, 12);
            c.iconShapeScale = jsonInt(values, "runtimeOverlayIconShapeScale", c.iconShapeScale, 20, 100);
            c.iconShadow = values.optBoolean("runtimeOverlayIconShadow", c.iconShadow);
            c.iconShadowColor = parseJsonColor(values, "runtimeOverlayIconShadowColor", c.iconShadowColor);
            c.iconShadowOpacity = jsonInt(values, "runtimeOverlayIconShadowOpacity", c.iconShadowOpacity, 0, 100);
            c.iconShadowOffsetX = jsonInt(values, "runtimeOverlayIconShadowOffsetX", c.iconShadowOffsetX, -32, 32);
            c.iconShadowOffsetY = jsonInt(values, "runtimeOverlayIconShadowOffsetY", c.iconShadowOffsetY, -32, 32);
            c.iconShadowBlur = jsonInt(values, "runtimeOverlayIconShadowBlur", c.iconShadowBlur, 0, 32);
            c.iconShadowSpread = jsonInt(values, "runtimeOverlayIconShadowSpread", c.iconShadowSpread, 0, 16);
            c.controlTheme = choice(values.optString("runtimeOverlayControlTheme", c.controlTheme), c.controlTheme, "legacy", "modern", "monet");
            c.controlBackground = parseJsonColor(values, "runtimeOverlayControlBackground", c.controlBackground);
            c.controlForeground = parseJsonColor(values, "runtimeOverlayControlForeground", c.controlForeground);
            c.controlOutlineColor = parseJsonColor(values, "runtimeOverlayMenuTextColor7", c.controlOutlineColor);
            c.bottomButtonStyle = choice(values.optString("runtimeOverlayBottomButtonStyle", c.bottomButtonStyle), c.bottomButtonStyle, "text", "solid", "gradient");
            c.bottomButtonShape = choice(values.optString("runtimeOverlayBottomButtonShape", c.bottomButtonShape), c.bottomButtonShape, "square", "squircle");
            c.bottomButtonPadding = values.optBoolean("runtimeOverlayBottomButtonPadding", c.bottomButtonPadding);
            c.bottomButtonTextColor = parseJsonColor(values, "runtimeOverlayBottomButtonTextColor", c.bottomButtonTextColor);
            c.bottomButtonBackground1 = parseJsonColor(values, "runtimeOverlayBottomButtonBackground1", c.bottomButtonBackground1);
            c.bottomButtonBackground2 = parseJsonColor(values, "runtimeOverlayBottomButtonBackground2", c.bottomButtonBackground2);
            c.menuTextColor1 = parseJsonColor(values, "runtimeOverlayMenuTextColor1", c.menuTextColor1);
            c.menuTextColor2 = parseJsonColor(values, "runtimeOverlayMenuTextColor2", c.menuTextColor2);
            c.menuTextColor3 = parseJsonColor(values, "runtimeOverlayMenuTextColor3", c.menuTextColor3);
            c.menuTextColor4 = parseJsonColor(values, "runtimeOverlayMenuTextColor4", c.menuTextColor4);
            c.menuTextColor5 = parseJsonColor(values, "runtimeOverlayMenuTextColor5", c.menuTextColor5);
            c.menuTextColor6 = parseJsonColor(values, "runtimeOverlayMenuTextColor6", c.menuTextColor6);
            c.separatorBackgroundColor = parseJsonColor(values, "runtimeOverlaySeparatorBackgroundColor", c.separatorBackgroundColor);
            c.separatorStyle = choice(values.optString("runtimeOverlaySeparatorStyle", c.separatorStyle), c.separatorStyle, "ascii", "doubleLine", "background", "singleLine", "inline");
            c.titleIconPlacement = choice(values.optString("runtimeOverlayTitleIconPlacement", c.titleIconPlacement), c.titleIconPlacement, "none", "left", "right", "both");
            c.titleAlignment = choice(values.optString("runtimeOverlayTitleAlignment", c.titleAlignment), c.titleAlignment, "left", "center", "right");
            c.titleSeparator = values.optBoolean("runtimeOverlayTitleSeparator", c.titleSeparator);
            c.menuCorners = choice(values.optString("runtimeOverlayMenuCorners", c.menuCorners), c.menuCorners, "rounded", "square");
            c.menuOutlineAnimation = choice(values.optString("runtimeOverlayMenuOutlineAnimation", c.menuOutlineAnimation), c.menuOutlineAnimation, "static", "gradient", "vertical", "rainbow");
            c.outlineAnimationSpeed = jsonInt(values, "runtimeOverlayOutlineAnimationSpeed", c.outlineAnimationSpeed, -10, 10);
            c.openingAnimation = choice(values.optString("runtimeOverlayOpeningAnimation", c.openingAnimation), c.openingAnimation, "fade", "scale", "disabled", "appearRight", "appearTop", "appearBottom", "appearLeft");
            c.closingAnimation = choice(values.optString("runtimeOverlayClosingAnimation", c.closingAnimation), c.closingAnimation, "fade", "scale", "disabled", "disappearUp", "disappearDown", "disappearLeft", "disappearRight");
            c.animationDuration = jsonInt(values, "runtimeOverlayAnimationDuration", c.animationDuration, 0, 5000);
            c.animationEasing = choice(values.optString("runtimeOverlayAnimationEasing", c.animationEasing), c.animationEasing, "linear", "logarithmic");
            c.outlineWidth = jsonInt(values, "runtimeOverlayOutlineWidthDp", c.outlineWidth, 1, 8);
            c.showExtraPopupHeaders = values.optBoolean("runtimeOverlayShowExtraPopupHeaders", c.showExtraPopupHeaders);
            c.shape = shape(values.optString("runtimeOverlayButtonShape", ""), c.shape);
            c.buttonSize = jsonInt(values, "runtimeOverlayButtonSizeDp", c.buttonSize, 32, 128);
            c.opacity = jsonInt(values, "runtimeOverlayButtonIdleOpacityPercent", Math.round(c.opacity * 100f), 10, 100) / 100f;
            c.dragVisibilityDurationSeconds = jsonInt(values, "runtimeOverlayButtonDragVisibilityDurationSeconds", c.dragVisibilityDurationSeconds, 1, 10);
            c.gravity = gravity(values.optString("runtimeOverlayButtonPosition", ""));
            c.showNoModulesWarning = values.optBoolean("runtimeOverlayShowNoModulesWarning", c.showNoModulesWarning);
            c.activateStatisticsOnLaunch = values.optBoolean("runtimeOverlayActivateStatisticsOnLaunch", c.activateStatisticsOnLaunch);
            c.enableMonitorsOnLaunch = values.optBoolean("runtimeOverlayEnableMonitorsOnLaunch", c.enableMonitorsOnLaunch);
            c.statisticMonitorPosition = monitorPosition(values.optString("runtimeOverlayStatisticMonitorPosition", ""), c.statisticMonitorPosition);
            c.monitorScale = floatValue(values.optString("runtimeOverlayMonitorScale", ""), c.monitorScale, .5f, 2f);
            c.monitorColumns = jsonInt(values, "runtimeOverlayMonitorColumns", c.monitorColumns, 1, 3);
            c.temperatureFormat = choice(values.optString("runtimeOverlayTemperatureFormat", c.temperatureFormat), c.temperatureFormat, "celsius", "fahrenheit", "kelvin");
            c.timeFormat = choice(values.optString("runtimeOverlayTimeFormat", c.timeFormat), c.timeFormat, "12", "24");
            applyManagedModules(c, values);
            JSONArray parts = values.optJSONArray("runtimeOverlayIconParts");
            if (parts != null) {
                List<String> validParts = new ArrayList<>();
                for (int i = 0; i < parts.length() && validParts.size() < 12; i++) {
                    String part = parts.optString(i, "");
                    if (validIconPart(part)) validParts.add(part);
                }
                c.iconParts = validParts.toArray(new String[0]);
                // An explicit manager-selected icon type wins over the presence of a stored
                // parts list. This lets users switch back to the text icon without deleting
                // their saved multi-part strings.
                if (!values.has("runtimeOverlayIconStyle")) {
                    c.iconStyle = validParts.isEmpty() ? "text" : "parts";
                }
            }
        } catch (Exception ignored) {
            // Manager settings are optional overrides. Invalid values keep patch-time defaults.
        }
    }

    private static int parseJsonColor(JSONObject settings, String name, int fallback) {
        String value = settings.optString(name, "");
        return value.matches("#[0-9a-fA-F]{6}") ? color(value, fallback) : fallback;
    }

    private static int colorWithManagedTransparency(JSONObject values, String colorName, String transparencyName, int fallback, int fallbackTransparency) {
        int color = parseJsonColor(values, colorName, fallback);
        int transparency = jsonInt(values, transparencyName, fallbackTransparency, 0, 100);
        return withAlpha(color, transparency);
    }

    private static int shape(String value, int fallback) {
        if ("square".equals(value)) return 0;
        if ("squircle".equals(value)) return 2;
        if ("circle".equals(value)) return 1;
        return fallback;
    }

    private static int monitorPosition(String value, int fallback) {
        if ("top".equals(value)) return 1;
        if ("bottom".equals(value)) return 2;
        if ("none".equals(value)) return 0;
        return fallback;
    }

    private static void applyManagedModules(OverlayConfig c, JSONObject values) {
        c.keepAwake = managedModule(values, "runtimeOverlayIncludeKeepScreenAwake", c.keepAwake);
        c.fullscreen = managedModule(values, "runtimeOverlayIncludeFullscreen", c.fullscreen);
        c.screenshots = managedModule(values, "runtimeOverlayIncludeScreenshots", c.screenshots);
        c.systemTime = managedModule(values, "runtimeOverlayIncludeSystemTime", c.systemTime);
        c.fps = managedModule(values, "runtimeOverlayIncludeFps", c.fps);
        c.sessionTime = managedModule(values, "runtimeOverlayIncludeSessionTime", c.sessionTime);
        c.batteryStatus = managedModule(values, "runtimeOverlayIncludeBatteryStatus", c.batteryStatus);
        c.appMemory = managedModule(values, "runtimeOverlayIncludeAppMemory", c.appMemory);
        c.networkStatus = managedModule(values, "runtimeOverlayIncludeNetworkStatus", c.networkStatus);
        c.deviceInformation = managedModule(values, "runtimeOverlayIncludeDeviceInformation", c.deviceInformation);
        c.deviceTemperature = managedModule(values, "runtimeOverlayIncludeDeviceTemperature", c.deviceTemperature);
        c.appBrightness = managedModule(values, "runtimeOverlayIncludeAppBrightness", c.appBrightness);
        c.rotationMode = managedModule(values, "runtimeOverlayIncludeRotationMode", c.rotationMode);
        c.appAudioMute = managedModule(values, "runtimeOverlayIncludeAppAudioMute", c.appAudioMute);
        c.disableHaptics = managedModule(values, "runtimeOverlayIncludeDisableHaptics", c.disableHaptics);
        c.disableAnimations = managedModule(values, "runtimeOverlayIncludeDisableAnimations", c.disableAnimations);
        c.includeDoNotDisturb = managedModule(values, "runtimeOverlayIncludeDoNotDisturb", c.includeDoNotDisturb);
        c.includeOverlayRuntimeLogs = managedModule(values, "runtimeOverlayIncludeOverlayRuntimeLogs", c.includeOverlayRuntimeLogs);
        if (values.has("runtimeOverlayEnableOverlayRuntimeLogsOnLaunch")) c.enableOverlayRuntimeLogsOnLaunch = values.optBoolean("runtimeOverlayEnableOverlayRuntimeLogsOnLaunch");
    }

    private static boolean managedModule(JSONObject values, String key, boolean current) {
        if (!values.has(key)) return current;
        boolean enabled = values.optBoolean(key);
        return enabled;
    }

        private static boolean validIconPart(String encoded) {
        if (encoded == null || encoded.length() > 260) return false;
        String[] fields = encoded.split("\\|", -1);
        if (fields.length < 12 || fields.length > 15) return false;
        String shape = fields[0].trim();
        if (!("triangle".equals(shape) || "roundedTriangle".equals(shape) || "v".equals(shape)
            || "circle".equals(shape) || "ring".equals(shape) || "square".equals(shape)
            || "roundedRect".equals(shape) || "line".equals(shape) || "arc".equals(shape)
            || "diamond".equals(shape) || "star".equals(shape) || "heart".equals(shape)
            || "text".equals(shape))) return false;
        if (fields.length >= 14 && !("true".equalsIgnoreCase(fields[13].trim())
            || "false".equalsIgnoreCase(fields[13].trim()))) return false;
        return fields.length < 15 || "default".equals(fields[14].trim()) || "roboto".equals(fields[14].trim())
            || "sansSerif".equals(fields[14].trim()) || "serif".equals(fields[14].trim())
            || "monospace".equals(fields[14].trim()) || "sansCondensed".equals(fields[14].trim())
            || "sansMedium".equals(fields[14].trim()) || "sansBlack".equals(fields[14].trim());
        }

    private static int jsonInt(JSONObject settings, String name, int fallback, int min, int max) {
        return settings.has(name) ? integer(settings.optString(name, ""), fallback, min, max) : fallback;
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
