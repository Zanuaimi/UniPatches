package unipatch.overlaycore;

import java.lang.reflect.Constructor;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Shared low-level BillingClient purchase construction helpers.
 *
 * <p>One product maps to exactly one token, order id, signature and purchase time, no
 * matter which callback or inventory query builds the object. Real Play billing behaves
 * that way, and apps that compare the purchase from {@code onPurchasesUpdated} against
 * the one returned by {@code queryPurchasesAsync} reject a pair that disagrees.
 */
public final class BillingPurchaseFactory {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** 2023-01-01T00:00:00Z; a plausible, stable purchase epoch for every emulated product. */
    private static final long PURCHASE_EPOCH_MILLIS = 1672531200000L;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private BillingPurchaseFactory() { }

    static Object construct(Class<?> purchaseClass, String productId, String packageName,
                            String orderId, String token, boolean acknowledged)
            throws ReflectiveOperationException {
        return construct(purchaseClass, productId, packageName, orderId, token, signature(productId), acknowledged);
    }

    static Object construct(Class<?> purchaseClass, String productId, String packageName,
                            String orderId, String token, String signature, boolean acknowledged)
            throws ReflectiveOperationException {
        Constructor<?> constructor = purchaseClass.getConstructor(String.class, String.class);
        return constructor.newInstance(
                purchaseJson(productId, packageName, orderId, token, acknowledged),
                signature);
    }

    /**
     * Purchase JSON in the shape BillingClient parses.
     *
     * <p>{@code purchaseState} 0 is the documented "purchased" value; BillingClient only
     * maps 4 to PENDING and treats every other value as PURCHASED, so strict readers and
     * the library agree. Key order is pinned by BillingPurchaseFactoryTest because the
     * app may hand the raw string to its backend.
     */
    static String purchaseJson(String productId, String packageName, String orderId, String token,
                               boolean acknowledged) {
        String product = PurchaseJson.escape(productId);
        return "{\"orderId\":\"" + PurchaseJson.escape(orderId) + "\"" +
                ",\"packageName\":\"" + PurchaseJson.escape(packageName) + "\"" +
                ",\"productId\":\"" + product + "\"" +
                ",\"products\":[\"" + product + "\"]" +
                ",\"purchaseTime\":" + purchaseTime(productId) +
                ",\"purchaseState\":0" +
                ",\"purchaseToken\":\"" + PurchaseJson.escape(token) + "\"" +
                ",\"quantity\":1" +
                ",\"acknowledged\":" + acknowledged +
                ",\"autoRenewing\":true" +
                ",\"developerPayload\":\"\"" +
                ",\"obfuscatedAccountId\":\"" + obfuscatedId(productId, "account") + "\"" +
                ",\"obfuscatedProfileId\":\"" + obfuscatedId(productId, "profile") + "\"" +
                "}";
    }

    /** Inventory JSON for one product; carries the same token/order/time as the callback purchase. */
    public static String inventoryJson(String productId) {
        String id = productId == null ? "" : productId;
        return purchaseJson(id, packageName(), orderId(id), token(id), true);
    }

    /**
     * Host package name, resolved once from the running process.
     *
     * <p>Injected static code has no Context, and {@code Purchase.getPackageName()} is
     * compared against the caller's own package by more than one wrapper, so an empty
     * string here is a real difference from Play behaviour, not cosmetic.
     */
    static String packageName() {
        String cached = resolvedPackageName;
        if (cached != null) return cached;
        String resolved = "";
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object application = threadClass.getMethod("getApplication").invoke(thread);
                if (application instanceof android.content.Context) {
                    resolved = ((android.content.Context) application).getPackageName();
                }
            }
        } catch (Throwable ignored) {
            // Unit tests and stripped builds have no ActivityThread. Empty stays empty.
        }
        if (resolved == null || resolved.isEmpty()) return "";
        // Only cache a real answer: the first inventory build can run before the
        // Application exists, and caching "" would pin the wrong value for the session.
        resolvedPackageName = resolved;
        return resolved;
    }

    /** Canonical purchase token for a product. */
    public static String token(String productId) {
        String digest = digestHex(productId, "unipatches-token");
        // Play tokens are long base64url blobs; two digest groups keep length and shape plausible.
        return base64Url(digest, 40) + "." + base64Url(digest + "-renewal", 40);
    }

    /** Canonical order id in the GPA.XXXX-XXXX-XXXX-XXXXX shape Play uses for one-time products. */
    public static String orderId(String productId) {
        String digest = digestHex(productId, "unipatches-order");
        StringBuilder out = new StringBuilder("GPA.");
        appendGroup(out, digest, 0, 4);
        out.append('-');
        appendGroup(out, digest, 4, 4);
        out.append('-');
        appendGroup(out, digest, 8, 4);
        out.append('-');
        appendGroup(out, digest, 12, 5);
        return out.toString();
    }

    /** Stable RSA-shaped signature placeholder; apps gate on "signature present" far more often than on the bytes. */
    public static String signature(String productId) {
        return base64Url(digestHex(productId, "unipatches-signature") + digestHex(productId, "signature-2"), 192);
    }

    /** Stable purchase time for a product, inside a plausible recent window. */
    public static long purchaseTime(String productId) {
        int offset = Math.abs(String.valueOf(productId).hashCode()) % 365;
        return PURCHASE_EPOCH_MILLIS + offset * DAY_MILLIS;
    }

    private static void appendGroup(StringBuilder out, String digest, int start, int length) {
        for (int i = 0; i < length; i++) {
            char c = digest.charAt(start + i);
            out.append(Character.isDigit(c) ? c : (char) ('1' + (Math.abs(c) % 9)));
        }
    }

    private static String obfuscatedId(String productId, String scope) {
        return digestHex(productId, "unipatches-" + scope).substring(0, 32);
    }

    private static String digestHex(String productId, String salt) {
        String source = salt + "|" + (productId == null ? "" : productId.trim());
        byte[] bytes;
        try {
            bytes = MessageDigest.getInstance("SHA-256").digest(source.getBytes(UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            bytes = source.getBytes(UTF_8);
        }
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    /** Minimal base64url encoding; java.util.Base64 is API 26 and the patched app may be older. */
    private static String base64Url(String hex, int length) {
        byte[] source = hex.getBytes(UTF_8);
        StringBuilder out = new StringBuilder((length * 4 + 2) / 3 + 4);
        int buffer = 0;
        int bits = 0;
        for (byte value : source) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 6) {
                bits -= 6;
                out.append(ALPHABET.charAt((buffer >> bits) & 0x3f));
            }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (6 - bits)) & 0x3f));
        return out.length() <= length ? out.toString() : out.substring(0, length);
    }

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static volatile String resolvedPackageName;
}
