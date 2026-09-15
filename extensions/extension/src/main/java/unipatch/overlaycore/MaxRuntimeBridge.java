package unipatch.overlaycore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.json.JSONObject;

/** Reflection-only MAX callback bridge. It keeps SDK-specific control flow out of injected smali. */
public final class MaxRuntimeBridge {
    private static final String UNITY_MANAGER = "com.applovin.mediation.unity.MaxUnityAdManager";
    private static final String REWARDED_LISTENER = "com.applovin.mediation.MaxRewardedAdListener";

    private MaxRuntimeBridge() { }

    public static void dispatchNativeSyntheticReward(Object ad) {
        dispatchNative(ad, true);
    }

    public static void dispatchNativeImmediateReward(Object ad) {
        dispatchNative(ad, false);
    }

    private static void dispatchNative(Object ad, boolean complete) {
        if (ad == null) return;
        try {
            Class<?> listenerType = Class.forName(REWARDED_LISTENER);
            Object listener = findListener(
                    ad,
                    listenerType,
                    0,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            if (listener == null) return;
            invokeCallback(listener, "onAdDisplayed", ad);
            invokeCallback(listener, "onRewardedVideoStarted", ad);
            Object reward = createReward();
            if (reward != null) invokeCallback(listener, "onUserRewarded", ad, reward);
            if (complete) {
                invokeCallback(listener, "onRewardedVideoCompleted", ad);
                invokeCallback(listener, "onAdHidden", ad);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // An SDK revision may change its private listener layout. Preserve app behavior.
        }
    }

    public static void dispatchUnitySyntheticReward(String adUnitId) {
        dispatchUnity(adUnitId, true);
    }

    public static void dispatchUnityImmediateReward(String adUnitId) {
        dispatchUnity(adUnitId, false);
    }

    private static void dispatchUnity(String adUnitId, boolean complete) {
        try {
            Class<?> manager = Class.forName(UNITY_MANAGER);
            Method forward = manager.getDeclaredMethod("forwardUnityEvent", JSONObject.class);
            forward.setAccessible(true);
            forward.invoke(null, event("OnRewardedAdDisplayedEvent", adUnitId));
            forward.invoke(null, event("OnRewardedAdReceivedRewardEvent", adUnitId));
            if (complete) forward.invoke(null, event("OnRewardedAdHiddenEvent", adUnitId));
        } catch (ReflectiveOperationException | RuntimeException | org.json.JSONException ignored) {
            // Keep the original MAX path available when Unity bridge details differ.
        }
    }

    private static JSONObject event(String name, String adUnitId) throws org.json.JSONException {
        JSONObject event = new JSONObject();
        event.put("name", name);
        event.put("adUnitId", adUnitId == null ? "" : adUnitId);
        event.put("adFormat", "rewarded");
        if ("OnRewardedAdReceivedRewardEvent".equals(name)) {
            event.put("rewardLabel", "reward");
            event.put("rewardAmount", "1");
        }
        return event;
    }

    private static Object createReward() throws ReflectiveOperationException {
        Class<?> reward = Class.forName("com.applovin.mediation.MaxReward");
        return reward.getMethod("create", int.class, String.class).invoke(null, 1, "reward");
    }

    private static Object findListener(Object value, Class<?> listenerType, int depth, Set<Object> visited) {
        if (value == null || depth > 4 || !visited.add(value)) return null;
        if (listenerType.isInstance(value)) return value;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                Class<?> fieldType = field.getType();
                if (!listenerType.isAssignableFrom(fieldType) &&
                        !fieldType.getName().contains("applovin")) continue;
                try {
                    field.setAccessible(true);
                    Object nested = field.get(value);
                    Object listener = findListener(nested, listenerType, depth + 1, visited);
                    if (listener != null) return listener;
                } catch (IllegalAccessException | RuntimeException ignored) {
                    // Continue searching other fields and superclass state.
                }
            }
        }
        return null;
    }

    private static void invokeCallback(Object listener, String name, Object... args) {
        for (Class<?> type = listener.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
                if (!areCompatible(method.getParameterTypes(), args)) continue;
                try {
                    method.setAccessible(true);
                    method.invoke(listener, args);
                    return;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return;
                }
            }
        }
    }

    private static boolean areCompatible(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) return false;
            } else if (!wrap(types[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
