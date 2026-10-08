package unipatch.overlaycore;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runtime loader for the patch-time embedded Frida Gadget and JavaScript bundle. */
public final class FridaGadgetRuntime {
    private static final String TAG = "UniPatchesFrida";
    private static final String ASSET_ROOT = "unipatch/frida";
    private static final String BUNDLE_ASSET = ASSET_ROOT + "/bundle.js";
    private static final String LIBRARY_NAME = "libfrida-gadget.so";
    private static final String CONFIG_NAME = "libfrida-gadget.config.so";
    private static final Object LOCK = new Object();
    private static final State STATE = new State();
    private static Application.ActivityLifecycleCallbacks failureCallbacks;

    interface Startup { void run() throws Throwable; }

    static final class State {
        boolean loaded;
        boolean notified;

        void start(Startup startup) throws Throwable {
            if (loaded) return;
            startup.run();
            loaded = true;
        }

        void notifyOnce(Runnable notification) {
            if (loaded || notified) return;
            try {
                notification.run();
                notified = true;
            } catch (Throwable ignored) { /* Reporting is nonfatal and may retry. */ }
        }
    }

    private FridaGadgetRuntime() { }

    public static void initialize(Context context, boolean minimalFootprint) {
        if (context == null) return;
        synchronized (LOCK) {
            if (STATE.loaded) return;
            try {
                STATE.start(() -> {
                    Context app = context.getApplicationContext() != null
                            ? context.getApplicationContext() : context;
                    File directory = app.getDir("unipatch-frida", Context.MODE_PRIVATE);
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw new IOException("Could not create private Gadget directory");
                    }
                    File bundle = new File(directory, "bundle.js");
                    copyAsset(app, BUNDLE_ASSET, bundle);
                    File gadget = new File(directory, LIBRARY_NAME);
                    copySelectedGadget(app, gadget);
                    if (!gadget.setReadable(true, true) || !gadget.setExecutable(true, true)) {
                        Log.w(TAG, "Could not change Gadget file permissions; continuing with platform defaults");
                    }
                    File config = new File(directory, CONFIG_NAME);
                    writeConfig(config, bundle, minimalFootprint);
                    System.load(gadget.getAbsolutePath());
                });
            } catch (Throwable error) {
                try { Log.e(TAG, "Frida Gadget initialization failed; a later call may retry", error); }
                catch (Throwable ignored) { }
                reportFailure(context);
                return;
            }
            try {
                clearFailureCallback();
                Log.i(TAG, "Frida Gadget loaded; script execution is not verified");
            } catch (Throwable ignored) { /* Reporting must not turn successful loading into failure. */ }
        }
    }

    private static void copySelectedGadget(Context context, File destination) throws IOException {
        String[] abis = processAbis();
        if (abis != null) {
            for (String abi : abis) {
                String token = assetToken(abi);
                if (token == null) continue;
                String asset = ASSET_ROOT + "/gadget/" + token + "/" + LIBRARY_NAME;
                if (assetExists(context, asset)) {
                    copyAsset(context, asset, destination);
                    return;
                }
            }
        }
        String custom = ASSET_ROOT + "/gadget/custom/" + LIBRARY_NAME;
        if (assetExists(context, custom)) {
            copyAsset(context, custom, destination);
            return;
        }
        throw new IOException("No Frida Gadget asset matches device ABI");
    }

    private static String[] processAbis() {
        boolean is64Bit = Build.VERSION.SDK_INT >= 23 ? Process.is64Bit()
                : is64BitArchitecture(System.getProperty("os.arch", ""));
        return is64Bit ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
    }

    static boolean is64BitArchitecture(String architecture) {
        return "aarch64".equals(architecture) || "arm64".equals(architecture)
                || "x86_64".equals(architecture) || "amd64".equals(architecture);
    }

    static String assetToken(String abi) {
        if ("arm64-v8a".equals(abi)) return "arm64";
        if ("armeabi-v7a".equals(abi)) return "arm";
        if ("x86_64".equals(abi)) return "x86_64";
        if ("x86".equals(abi)) return "x86";
        return null;
    }

    private static boolean assetExists(Context context, String asset) {
        try (InputStream input = context.getAssets().open(asset)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void copyAsset(Context context, String asset, File destination) throws IOException {
        try (InputStream input = context.getAssets().open(asset)) {
            writeAtomically(input, destination);
        }
    }

    static void writeAtomically(InputStream input, File destination) throws IOException {
        File parent = destination.getAbsoluteFile().getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        File temporary = File.createTempFile(destination.getName(), ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > 0) output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (!temporary.renameTo(destination)) throw new IOException("Could not replace " + destination);
        } finally {
            temporary.delete();
        }
    }

    private static void writeConfig(File file, File bundle, boolean minimalFootprint) throws IOException {
        try (InputStream input = new ByteArrayInputStream(
                configJson(bundle, minimalFootprint).getBytes(StandardCharsets.UTF_8))) {
            writeAtomically(input, file);
        }
    }

    static String configJson(File bundle, boolean minimalFootprint) {
        StringBuilder config = new StringBuilder(192)
                .append("{\"interaction\":{\"type\":\"script\",\"path\":")
                .append(jsonString(bundle.getAbsolutePath()));
        if (!minimalFootprint) config.append(",\"on_change\":\"reload\"");
        return config.append("}}").toString();
    }

    private static void clearFailureCallback() {
        if (failureApplication != null && failureCallbacks != null) {
            failureApplication.unregisterActivityLifecycleCallbacks(failureCallbacks);
            failureCallbacks = null;
            failureApplication = null;
        }
    }

    private static Application failureApplication;

    private static void reportFailure(Context context) {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                synchronized (LOCK) {
                    try {
                        if (STATE.loaded || STATE.notified) return;
                        if (context instanceof Activity && ((Activity) context).hasWindowFocus()) {
                            showFailure((Activity) context);
                        }
                        Context app = context.getApplicationContext();
                        if (app == null) app = context;
                        if (app instanceof Application && failureCallbacks == null && !STATE.notified) {
                            Application application = (Application) app;
                            Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
                                public void onActivityResumed(Activity activity) { showFailure(activity); }
                                public void onActivityCreated(Activity activity, Bundle state) { }
                                public void onActivityStarted(Activity activity) { }
                                public void onActivityPaused(Activity activity) { }
                                public void onActivityStopped(Activity activity) { }
                                public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                                public void onActivityDestroyed(Activity activity) { }
                            };
                            application.registerActivityLifecycleCallbacks(callbacks);
                            failureApplication = application;
                            failureCallbacks = callbacks;
                        }
                    } catch (Throwable ignored) { /* Reporting must never break the host app. */ }
                }
            });
        } catch (Throwable ignored) { /* No main looper: reporting is optional. */ }
    }

    private static void showFailure(Activity activity) {
        synchronized (LOCK) {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                STATE.notifyOnce(() -> new AlertDialog.Builder(activity)
                        .setTitle("Frida Gadget could not start")
                        .setMessage("Frida could not start. App will continue without it.")
                        .setPositiveButton(android.R.string.ok, null).show());
                if (STATE.notified || STATE.loaded) clearFailureCallback();
            } catch (Throwable ignored) { /* Retry feedback on a later foreground activity. */ }
        }
    }

    static String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        result.append("\\u00");
                        result.append("0123456789abcdef".charAt(c >> 4));
                        result.append("0123456789abcdef".charAt(c & 15));
                    } else result.append(c);
            }
        }
        return result.append('"').toString();
    }
}
