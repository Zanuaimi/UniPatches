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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    static final class ExpansionMetadata {
        final long length;
        final String sha256;

        ExpansionMetadata(long length, String sha256) {
            this.length = length;
            this.sha256 = sha256;
        }

        String encode() {
            return length + " " + sha256;
        }
    }

    static ExpansionMetadata parseExpansionMetadata(String text) {
        if (text == null) return null;
        String[] fields = text.trim().split("\\s+");
        if (fields.length != 2 || !fields[1].matches("[0-9a-f]{64}")) return null;
        try {
            long length = Long.parseLong(fields[0]);
            return length > 0 ? new ExpansionMetadata(length, fields[1]) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static boolean fileMatchesExpected(File file, ExpansionMetadata metadata) {
        if (file == null || metadata == null || !file.isFile() || file.length() != metadata.length) return false;
        try {
            return metadata.sha256.equals(sha256(file));
        } catch (IOException e) {
            return false;
        }
    }

    static boolean copyAndVerify(InputStream input, File temporary, ExpansionMetadata metadata) throws IOException {
        if (metadata == null || temporary == null) return false;
        if (temporary.exists() && !temporary.delete()) return false;
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            output.getFD().sync();
        } catch (IOException e) {
            temporary.delete();
            throw e;
        }
        if (!fileMatchesExpected(temporary, metadata)) {
            temporary.delete();
            return false;
        }
        return true;
    }

    static boolean stageVerifiedFile(
            InputStream input,
            File temporary,
            File target,
            File backup,
            ExpansionMetadata metadata
    ) throws IOException {
        try (InputStream source = input) {
            if (!copyAndVerify(source, temporary, metadata)) return false;
        }
        if (publishVerifiedTemp(temporary, target, backup)) return true;
        temporary.delete();
        return false;
    }

    static boolean publishVerifiedTemp(File temporary, File target, File backup) {
        if (temporary == null || target == null || backup == null || !temporary.isFile()) return false;
        // Android/Linux rename replaces an existing file atomically within the same directory.
        if (temporary.renameTo(target)) return true;
        if (!target.exists() || backup.exists() || !target.renameTo(backup)) return false;
        if (!temporary.renameTo(target)) {
            if (!backup.renameTo(target)) Log.e(TAG, "Could not restore prior OBB after failed publish: " + backup);
            return false;
        }
        return true;
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        byte[] bytes = digest.digest();
        char[] hex = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            output[i * 2] = hex[value >>> 4];
            output[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(output);
    }

    private static String readUtf8(InputStream input) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (text.length() + line.length() > 128) return null;
                if (text.length() > 0) text.append(' ');
                text.append(line);
            }
        }
        return text.toString();
    }

    private static boolean markerMatches(File marker, File target, ExpansionMetadata metadata) {
        if (!fileMatchesExpected(target, metadata) || !marker.isFile() || marker.lastModified() < target.lastModified()) return false;
        try (InputStream input = new FileInputStream(marker)) {
            return metadata.encode().equals(readUtf8(input));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean writeMarker(File marker, ExpansionMetadata metadata) {
        File temporary = new File(marker.getPath() + ".tmp");
        if (temporary.exists() && !temporary.delete()) return false;
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(metadata.encode().getBytes("UTF-8"));
            output.flush();
            output.getFD().sync();
        } catch (IOException e) {
            temporary.delete();
            return false;
        }
        if (marker.exists() && !marker.delete()) {
            temporary.delete();
            return false;
        }
        if (!temporary.renameTo(marker)) {
            temporary.delete();
            return false;
        }
        return true;
    }

    private static ExpansionMetadata readExpansionMetadata(AssetManager assets, String name) throws IOException {
        try (InputStream input = assets.open("unipatch-legacy-expansion/" + name + ".sha256")) {
            return parseExpansionMetadata(readUtf8(input));
        }
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
            String name = null;
            int obbCount = 0;
            boolean hasChecksum = false;
            if (names != null) {
                for (String assetName : names) {
                    if (assetName.endsWith(".obb")) {
                        name = assetName;
                        obbCount++;
                    }
                }
                if (obbCount == 1) {
                    for (String assetName : names) {
                        if ((name + ".sha256").equals(assetName)) hasChecksum = true;
                    }
                }
            }
            if (names == null || names.length != 2 || obbCount != 1 || !hasChecksum) {
                Log.w(TAG, "Embedded expansion skipped: expected one .obb and its .sha256 metadata asset");
                return;
            }
            ExpansionMetadata metadata = readExpansionMetadata(assets, name);
            if (metadata == null) {
                Log.w(TAG, "Embedded expansion skipped: checksum metadata is invalid");
                return;
            }
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
            String versionKey = ".unipatch." + metadata.sha256;
            File marker = new File(obbDir, "." + name + versionKey + ".verified");
            File backup = new File(obbDir, "." + name + versionKey + ".bak");
            temporary = new File(obbDir, "." + name + ".unipatch.tmp");

            if (!target.exists() && backup.isFile() && !backup.renameTo(target)) {
                Log.w(TAG, "Embedded expansion skipped: cannot restore interrupted OBB replacement " + backup);
                return;
            }
            if (target.exists() && !target.isFile()) {
                Log.w(TAG, "Embedded expansion skipped: target is not a file " + target);
                return;
            }
            if (markerMatches(marker, target, metadata)) {
                if (backup.exists()) backup.delete();
                Log.i(TAG, "Embedded expansion already verified at " + target);
                return;
            }
            if (fileMatchesExpected(target, metadata)) {
                writeMarker(marker, metadata);
                if (backup.exists()) backup.delete();
                Log.i(TAG, "Existing expansion OBB matches embedded checksum at " + target);
                return;
            }
            if (backup.exists()) {
                if (fileMatchesExpected(backup, metadata)) {
                    if (target.exists() && !target.delete()) {
                        Log.w(TAG, "Embedded expansion skipped: cannot replace stale target with verified backup " + target);
                        return;
                    }
                    if (!backup.renameTo(target)) {
                        Log.w(TAG, "Embedded expansion skipped: cannot restore verified OBB backup " + backup);
                        return;
                    }
                    writeMarker(marker, metadata);
                    return;
                }
                Log.w(TAG, "Embedded expansion skipped: an unverified recovery file already exists " + backup);
                return;
            }
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Embedded expansion skipped: cannot clear temporary file " + temporary);
                return;
            }
            boolean staged;
            try (InputStream input = assets.open("unipatch-legacy-expansion/" + name)) {
                staged = stageVerifiedFile(input, temporary, target, backup, metadata);
            }
            if (!staged) {
                Log.w(TAG, "Embedded expansion skipped: staged OBB length or SHA-256 did not match metadata");
                return;
            }
            if (!writeMarker(marker, metadata)) {
                Log.w(TAG, "Embedded expansion staged and verified, but freshness marker could not be written");
            }
            if (backup.exists() && !backup.delete()) {
                Log.w(TAG, "Embedded expansion staged; old OBB backup remains at " + backup);
            }
            Log.i(TAG, "Embedded expansion staged and verified at " + target);
        } catch (Throwable t) {
            if (temporary != null) temporary.delete();
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
