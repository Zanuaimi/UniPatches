/*
 * UniPatches legacy compatibility runtime hooks.
 *
 * Static helpers invoked from Application.onCreate or a validated launcher
 * Activity by Legacy App Compatibility patches. Kept dependency-free so the
 * extension dex stays small.
 */
package unipatch.compatcore;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Static entry points for legacy compatibility runtime hooks. */
public final class LegacyCompatRuntime {
    private static final String TAG = "UniPatchLegacy";

    private static volatile Context appContext = null;

    enum HiddenApiExemptionOutcome {
        UNSUPPORTED,
        APPLIED,
        ALREADY_APPLIED,
        REJECTED
    }

    interface HiddenApiExemptionApplier {
        boolean apply(String signaturePrefix) throws Throwable;
    }

    /** Prevents repeated VMRuntime.setHiddenApiExemptions calls within one app process. */
    static final class HiddenApiExemptionOnce {
        private boolean applied;

        synchronized HiddenApiExemptionOutcome apply(
                int sdk,
                HiddenApiExemptionApplier applier
        ) throws Throwable {
            if (sdk < android.os.Build.VERSION_CODES.P) {
                return HiddenApiExemptionOutcome.UNSUPPORTED;
            }
            if (applied) {
                return HiddenApiExemptionOutcome.ALREADY_APPLIED;
            }
            if (!applier.apply("L")) {
                return HiddenApiExemptionOutcome.REJECTED;
            }
            applied = true;
            return HiddenApiExemptionOutcome.APPLIED;
        }
    }

    private static final HiddenApiExemptionOnce HIDDEN_API_EXEMPTION_ONCE = new HiddenApiExemptionOnce();

    private LegacyCompatRuntime() {
    }

    /** Stores the application context for later path redirects. */
    public static void init(Context context) {
        appContext = context != null ? context.getApplicationContext() : null;
    }

    /**
     * Copies one embedded expansion OBB into the conventional Android OBB directory.
     * The asset is copied through a temporary file so Unity never sees a partial archive.
     * Failure is logged and leaves the original app behavior intact.
     */
    public static void prepareEmbeddedExpansion(Context context) {
        if (context == null) {
            Log.w(TAG, "Embedded expansion skipped: no application context");
            return;
        }
        File temporary = null;
        try {
            AssetManager assets = context.getAssets();
            String[] names = assets.list("unipatch-legacy-expansion");
            if (names == null || names.length != 1 || !names[0].endsWith(".obb")) {
                Log.w(TAG, "Embedded expansion skipped: expected one .obb asset");
                return;
            }
            String name = names[0];
            File obbDir = context.getObbDir();
            if (obbDir == null) {
                Log.w(TAG, "Embedded expansion skipped: getObbDir returned null");
                return;
            }
            if (!obbDir.exists() && !obbDir.mkdirs()) {
                Log.w(TAG, "Embedded expansion skipped: cannot create " + obbDir);
                return;
            }
            File target = new File(obbDir, name);
            long assetLength = -1L;
            try (android.content.res.AssetFileDescriptor descriptor = assets.openFd("unipatch-legacy-expansion/" + name)) {
                assetLength = descriptor.getLength();
            } catch (Throwable ignored) {
                // Compressed assets do not expose a length; package update time still detects APK updates.
            }
            long packageUpdateTime = 0L;
            try {
                packageUpdateTime = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0)
                        .lastUpdateTime;
            } catch (Throwable ignored) {
                // Keep length-only freshness when package metadata is unavailable.
            }
            boolean targetFresh = target.isFile()
                    && target.length() > 0
                    && (assetLength < 0 || target.length() == assetLength)
                    && (packageUpdateTime <= 0 || target.lastModified() >= packageUpdateTime);
            if (targetFresh) {
                return;
            }
            if (target.exists() && !target.isFile()) {
                Log.w(TAG, "Embedded expansion skipped: target is not a file " + target);
                return;
            }
            temporary = new File(obbDir, "." + name + ".unipatch.tmp");
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Embedded expansion skipped: cannot clear temporary file " + temporary);
                return;
            }
            try (InputStream input = assets.open("unipatch-legacy-expansion/" + name);
                 OutputStream output = new java.io.FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
            if (temporary.length() == 0) {
                temporary.delete();
                Log.w(TAG, "Embedded expansion skipped: asset is empty");
                return;
            }
            if (target.exists() && !target.delete()) {
                temporary.delete();
                Log.w(TAG, "Embedded expansion failed: cannot replace " + target);
                return;
            }
            if (!temporary.renameTo(target)) {
                temporary.delete();
                Log.w(TAG, "Embedded expansion failed: cannot publish " + target);
                return;
            }
            Log.i(TAG, "Embedded expansion staged at " + target);
        } catch (Throwable t) {
            if (temporary != null) {
                temporary.delete();
            }
            Log.w(TAG, "Embedded expansion staging failed", t);
        }
    }

    /**
     * Exempts every hidden API prefix ("L") for this app process so old apps
     * relying on non-SDK reflection keep working. Requires Android P+.
     */
    public static void exemptHiddenApis() {
        int sdk = android.os.Build.VERSION.SDK_INT;
        if (sdk < android.os.Build.VERSION_CODES.P) {
            return; // Hidden API enforcement did not exist before P.
        }
        try {
            HiddenApiExemptionOutcome outcome = HIDDEN_API_EXEMPTION_ONCE.apply(
                    sdk,
                    signaturePrefix -> HiddenApiBypass.setHiddenApiExemptions(signaturePrefix)
            );
            switch (outcome) {
                case APPLIED:
                    Log.i(TAG, "Hidden API exemptions applied");
                    break;
                case ALREADY_APPLIED:
                    Log.i(TAG, "Hidden API exemptions already applied");
                    break;
                case REJECTED:
                    Log.w(TAG, "Hidden API exemptions were not applied");
                    break;
                case UNSUPPORTED:
                    return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Hidden API exemptions failed", t);
        }
    }

    /**
     * Installs a permissive TrustManager and HostnameVerifier on the
     * HttpsURLConnection defaults. WebView does not inherit these.
     *
     * This process-wide override is disabled unless the patch explicitly
     * acknowledges its high-risk security impact.
     */
    public static void trustAllCertificates() {
        Log.w(TAG, "Trust-all certificates refused: explicit high-risk acknowledgement is required");
    }

    /** Enables the process-wide trust-all override only for an acknowledged patch. */
    public static void trustAllCertificates(boolean acknowledgedHighRisk) {
        if (!acknowledgedHighRisk) {
            Log.w(TAG, "Trust-all certificates refused: explicit high-risk acknowledgement is required");
            return;
        }
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            });
            Log.i(TAG, "Trust-all certificates installed");
        } catch (Throwable t) {
            Log.w(TAG, "Trust-all certificates failed", t);
        }
    }

    /**
     * Replacement for Environment.getExternalStorageDirectory(): returns the
     * app-scoped external files directory so legacy root-level writes land in
     * a location the app can actually use.
     */
    public static File legacyExternalStorageDirectory() {
        Context context = appContext;
        File scoped = context != null ? context.getExternalFilesDir(null) : null;
        if (scoped != null) {
            return scoped;
        }
        return Environment.getExternalStorageDirectory();
    }

    /**
     * Replacement for Environment.getExternalStoragePublicDirectory(String):
     * maps the requested public type onto the app-scoped external files dir.
     */
    public static File legacyExternalStoragePublicDirectory(String type) {
        Context context = appContext;
        File scoped = context != null ? context.getExternalFilesDir(type) : null;
        if (scoped != null) {
            return scoped;
        }
        return Environment.getExternalStoragePublicDirectory(type);
    }
}
