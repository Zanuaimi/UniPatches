/*
 * UniPatches legacy compatibility runtime hooks.
 *
 * Static helpers invoked from Application.onCreate by Legacy App Compatibility
 * patches. Kept dependency-free so the extension dex stays small.
 */
package unipatch.compatcore;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
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

    private LegacyCompatRuntime() {
    }

    /** Stores the application context for later path redirects. */
    public static void init(Context context) {
        appContext = context != null ? context.getApplicationContext() : null;
    }

    /**
     * Exempts every hidden API prefix ("L") for this app process so old apps
     * relying on non-SDK reflection keep working. Requires Android P+.
     */
    public static void exemptHiddenApis() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            return; // Hidden API enforcement did not exist before P.
        }
        try {
            HiddenApiBypass.addHiddenApiExemptions("L");
            Log.i(TAG, "Hidden API exemptions applied");
        } catch (Throwable t) {
            Log.w(TAG, "Hidden API exemptions failed", t);
        }
    }

    /**
     * Installs a permissive TrustManager and HostnameVerifier on the
     * HttpsURLConnection defaults. WebView does not inherit these.
     */
    public static void trustAllCertificates() {
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
