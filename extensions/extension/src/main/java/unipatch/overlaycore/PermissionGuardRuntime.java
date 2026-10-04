package unipatch.overlaycore;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runtime permission policy used by Permission Guard bytecode replacements. */
public final class PermissionGuardRuntime {
    private static final String[] GROUPS = {
        "camera", "microphone", "location", "contacts", "phone", "sms",
        "calendar", "storage", "media", "notifications", "internet", "nearbyDevices", "bluetooth"
    };
    private static final Set<String> BLOCKED_GROUPS = new HashSet<>();

    private PermissionGuardRuntime() { }

    public static synchronized void initialize(Context context, String groups) {
        BLOCKED_GROUPS.clear();
        BLOCKED_GROUPS.addAll(parseGroups(groups));
    }

    public static synchronized void applyManagedConfiguration(String values) {
        try {
            JSONObject json = new JSONObject(values == null ? "{}" : values);
            for (String group : GROUPS) {
                String key = "permissionGuard" + Character.toUpperCase(group.charAt(0)) + group.substring(1);
                if (json.has(key)) setRuntimeBlockedLocked(group, json.optBoolean(key, false));
            }
        } catch (Exception ignored) { }
    }

    public static synchronized boolean isBlocked(String group) {
        return BLOCKED_GROUPS.contains(group);
    }

    public static synchronized void setRuntimeBlocked(String group, boolean blocked) {
        if (group == null || group.trim().isEmpty()) return;
        if (blocked) BLOCKED_GROUPS.add(group.trim());
        else BLOCKED_GROUPS.remove(group.trim());
    }

    private static void setRuntimeBlockedLocked(String group, boolean blocked) {
        if (blocked) BLOCKED_GROUPS.add(group);
        else BLOCKED_GROUPS.remove(group);
    }

    public static int checkSelfPermission(Context context, String permission) {
        if (isBlockedPermission(permission)) return PackageManager.PERMISSION_DENIED;
        return context == null ? PackageManager.PERMISSION_DENIED : context.checkSelfPermission(permission);
    }

    public static int checkCallingPermission(Context context, String permission) {
        if (isBlockedPermission(permission)) return PackageManager.PERMISSION_DENIED;
        return context == null ? PackageManager.PERMISSION_DENIED : context.checkCallingPermission(permission);
    }

    public static int checkCallingOrSelfPermission(Context context, String permission) {
        if (isBlockedPermission(permission)) return PackageManager.PERMISSION_DENIED;
        return context == null ? PackageManager.PERMISSION_DENIED : context.checkCallingOrSelfPermission(permission);
    }

    public static int checkSelfPermissionCompat(Context context, String permission) {
        return checkSelfPermission(context, permission);
    }

    public static void requestPermissions(Activity activity, String[] permissions, int requestCode) {
        if (activity == null || permissions == null) return;
        String[] allowed = allowedPermissions(permissions);
        if (allowed.length > 0) activity.requestPermissions(allowed, requestCode);
    }

    public static void requestPermissionsCompat(Activity activity, String[] permissions, int requestCode) {
        requestPermissions(activity, permissions, requestCode);
    }

    private static boolean isBlockedPermission(String permission) {
        if (permission == null) return false;
        for (String group : GROUPS) {
            if (!isBlocked(group)) continue;
            if (belongsToGroup(group, permission)) return true;
        }
        return false;
    }

    private static String[] allowedPermissions(String[] permissions) {
        List<String> allowed = new ArrayList<>();
        for (String permission : permissions) if (!isBlockedPermission(permission)) allowed.add(permission);
        return allowed.toArray(new String[0]);
    }

    private static Set<String> parseGroups(String value) {
        Set<String> groups = new HashSet<>();
        if (value == null) return groups;
        for (String group : value.split(",")) {
            String trimmed = group.trim();
            for (String known : GROUPS) if (known.equals(trimmed)) groups.add(trimmed);
        }
        return groups;
    }

    private static boolean belongsToGroup(String group, String permission) {
        switch (group) {
            case "camera": return "android.permission.CAMERA".equals(permission);
            case "microphone": return "android.permission.RECORD_AUDIO".equals(permission);
            case "location": return permission.equals("android.permission.ACCESS_COARSE_LOCATION") || permission.equals("android.permission.ACCESS_FINE_LOCATION") || permission.equals("android.permission.ACCESS_BACKGROUND_LOCATION");
            case "contacts": return permission.equals("android.permission.READ_CONTACTS") || permission.equals("android.permission.WRITE_CONTACTS") || permission.equals("android.permission.GET_ACCOUNTS");
            case "phone": return permission.equals("android.permission.READ_PHONE_STATE") || permission.equals("android.permission.READ_PHONE_NUMBERS") || permission.equals("android.permission.CALL_PHONE") || permission.equals("android.permission.ANSWER_PHONE_CALLS") || permission.equals("android.permission.ADD_VOICEMAIL") || permission.equals("android.permission.USE_SIP");
            case "sms": return permission.equals("android.permission.READ_SMS") || permission.equals("android.permission.RECEIVE_SMS") || permission.equals("android.permission.RECEIVE_MMS") || permission.equals("android.permission.SEND_SMS") || permission.equals("android.permission.RECEIVE_WAP_PUSH");
            case "calendar": return permission.equals("android.permission.READ_CALENDAR") || permission.equals("android.permission.WRITE_CALENDAR");
            case "storage": return permission.equals("android.permission.READ_EXTERNAL_STORAGE") || permission.equals("android.permission.WRITE_EXTERNAL_STORAGE") || permission.equals("android.permission.MANAGE_EXTERNAL_STORAGE");
            case "media": return permission.equals("android.permission.READ_MEDIA_IMAGES") || permission.equals("android.permission.READ_MEDIA_VIDEO") || permission.equals("android.permission.READ_MEDIA_AUDIO") || permission.equals("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
            case "notifications": return "android.permission.POST_NOTIFICATIONS".equals(permission);
            case "internet": return "android.permission.INTERNET".equals(permission);
            case "nearbyDevices": return permission.equals("android.permission.BLUETOOTH_SCAN") || permission.equals("android.permission.BLUETOOTH_CONNECT") || permission.equals("android.permission.BLUETOOTH_ADVERTISE") || permission.equals("android.permission.NEARBY_WIFI_DEVICES");
            case "bluetooth": return permission.equals("android.permission.BLUETOOTH") || permission.equals("android.permission.BLUETOOTH_ADMIN");
            default: return false;
        }
    }
}
