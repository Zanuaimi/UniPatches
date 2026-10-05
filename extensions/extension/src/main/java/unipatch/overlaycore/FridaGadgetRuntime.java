package unipatch.overlaycore;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

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
    private static boolean loaded;

    private FridaGadgetRuntime() { }

    public static void initialize(Context context, boolean minimalFootprint) {
        if (context == null) return;
        synchronized (LOCK) {
            if (loaded) return;
            try {
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
                loaded = true;
            } catch (Throwable error) {
                Log.e(TAG, "Frida Gadget initialization skipped", error);
            }
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
        if (Build.VERSION.SDK_INT >= 23) {
            return Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        }
        return Build.SUPPORTED_ABIS;
    }

    private static String assetToken(String abi) {
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
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        try (InputStream input = context.getAssets().open(asset);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
        }
    }

    private static void writeConfig(File file, File bundle, boolean minimalFootprint) throws IOException {
        StringBuilder config = new StringBuilder(192)
                .append("{\"interaction\":{\"type\":\"script\",\"path\":")
                .append(jsonString(bundle.getAbsolutePath()));
        if (!minimalFootprint) config.append(",\"on_change\":\"reload\"");
        config.append("}}");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(config.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default: result.append(c);
            }
        }
        return result.append('"').toString();
    }
}
