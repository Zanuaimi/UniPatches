package unipatch.compatcore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Private content URI provider for narrowly recognized file:// Intent shares. */
public final class LegacyCompatFileProvider extends ContentProvider {
    public static final String AUTHORITY_SUFFIX = ".legacycompat.fileprovider";
    public static final String PATHS_META_DATA = "android.support.FILE_PROVIDER_PATHS";
    private static final String TAG = "LegacyCompatFileProvider";
    private static final String[] DEFAULT_COLUMNS = { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };

    private static final class Root {
        final String name;
        final File directory;
        Root(String name, File directory) { this.name = name; this.directory = directory; }
    }

    @Override public boolean onCreate() {
        try {
            Context context = getContext();
            if (context == null) throw new IllegalStateException("Provider context is unavailable");
            LegacyCompatRuntime.init(context);
            roots(context);
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Provider paths are invalid; file URI sharing is unavailable (" + error.getClass().getSimpleName() + ")");
            return false;
        }
    }

    public static Uri getUriForFile(Context context, File file) {
        if (context == null || file == null) throw new IllegalArgumentException("Missing context or shared file");
        try {
            File canonical = file.getCanonicalFile();
            if (!canonical.isFile()) throw new IllegalArgumentException("Shared path is not an existing regular file");
            Root best = null;
            String bestPath = null;
            for (Root root : roots(context).values()) {
                String rootPath = root.directory.getPath();
                String filePath = canonical.getPath();
                if ((filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator)) &&
                    (bestPath == null || rootPath.length() > bestPath.length())) {
                    best = root;
                    bestPath = rootPath;
                }
            }
            if (best == null) throw new IllegalArgumentException("File is outside configured private sharing roots");
            String relative = canonical.getPath().substring(bestPath.length());
            if (relative.startsWith(File.separator)) relative = relative.substring(1);
            Uri.Builder builder = new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(best.name);
            if (!relative.isEmpty()) {
                for (String segment : relative.split(java.util.regex.Pattern.quote(File.separator))) {
                    if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                        throw new IllegalArgumentException("Unsafe file path segment in shared URI");
                    }
                    builder.appendPath(segment);
                }
            }
            return builder.build();
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot canonicalize shared file path");
        }
    }

    public static void grantReadPermission(Intent intent) {
        if (intent == null) throw new IllegalArgumentException("Missing Intent for file URI sharing");
        int flags = intent.getFlags() & ~(Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        intent.setFlags(flags | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Override public String getType(Uri uri) {
        File file = resolve(uri);
        String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "application/octet-stream" : type;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        String[] columns = projection == null ? DEFAULT_COLUMNS : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) values[index] = queryValue(file, columns[index]);
        cursor.addRow(values);
        return cursor;
    }

    static Object queryValue(File file, String column) {
        if (OpenableColumns.DISPLAY_NAME.equals(column)) return file.getName();
        if (OpenableColumns.SIZE.equals(column)) return file.length();
        return null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (!file.isFile()) throw new FileNotFoundException("Shared file no longer exists");
        if (!"r".equals(mode)) throw new FileNotFoundException("File URI sharing provider is read-only");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only file provider"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only file provider"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only file provider"); }

    private File resolve(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) ||
            !(getContext().getPackageName() + AUTHORITY_SUFFIX).equals(uri.getAuthority())) {
            throw new IllegalArgumentException("URI does not belong to this private file provider");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 2) throw new IllegalArgumentException("File URI has no configured root and relative path");
        Root root = roots(getContext()).get(segments.get(0));
        if (root == null) throw new IllegalArgumentException("URI references an unconfigured file root");
        File file = root.directory;
        for (int index = 1; index < segments.size(); index++) {
            String segment = segments.get(index);
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || segment.indexOf(File.separatorChar) >= 0) {
                throw new IllegalArgumentException("Unsafe file path segment in content URI");
            }
            file = new File(file, segment);
        }
        try {
            File canonical = file.getCanonicalFile();
            String rootPath = root.directory.getCanonicalPath();
            if (!canonical.getPath().startsWith(rootPath + File.separator)) {
                throw new IllegalArgumentException("Resolved file escaped configured provider root");
            }
            return canonical;
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot resolve shared file URI", error);
        }
    }

    private static Map<String, Root> roots(Context context) {
        String authority = context.getPackageName() + AUTHORITY_SUFFIX;
        ProviderInfo info = context.getPackageManager().resolveContentProvider(authority, PackageManager.GET_META_DATA);
        if (info == null || !authority.equals(info.authority)) throw new IllegalStateException("File provider is not declared for authority " + authority);
        if (info.exported || !info.grantUriPermissions) throw new SecurityException("File provider must remain private with grantUriPermissions enabled");
        Bundle metadata = info.metaData;
        int pathsResource = metadata == null ? 0 : metadata.getInt(PATHS_META_DATA, 0);
        if (pathsResource == 0) throw new IllegalStateException("File provider has no " + PATHS_META_DATA + " resource");

        XmlResourceParser parser = context.getResources().getXml(pathsResource);
        Map<String, Root> result = new HashMap<>();
        try {
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) continue;
                String tag = parser.getName();
                if ("paths".equals(tag)) continue;
                String name = parser.getAttributeValue(null, "name");
                String path = parser.getAttributeValue(null, "path");
                if (name == null || name.isEmpty() || path == null || path.startsWith("/")) {
                    throw new IllegalStateException("Invalid file-provider path root");
                }
                File base;
                if ("files-path".equals(tag) && "files".equals(name) && ".".equals(path)) base = context.getFilesDir();
                else if ("cache-path".equals(tag) && "cache".equals(name) && ".".equals(path)) base = context.getCacheDir();
                // These are app-specific roots; shared <external-path> and arbitrary subpaths are rejected.
                else if ("external-files-path".equals(tag) && "external-files".equals(name) && ".".equals(path)) base = externalDirectory(context, "getExternalFilesDir", true);
                else if ("external-cache-path".equals(tag) && "external-cache".equals(name) && ".".equals(path)) base = externalDirectory(context, "getExternalCacheDir", false);
                else throw new IllegalStateException("Unsupported or unverified file-provider root: " + tag);
                if (base == null) continue;
                File canonicalBase = base.getCanonicalFile();
                File canonicalRoot = new File(canonicalBase, path).getCanonicalFile();
                if (!(canonicalRoot.equals(canonicalBase) || canonicalRoot.getPath().startsWith(canonicalBase.getPath() + File.separator))) {
                    throw new SecurityException("File-provider root escapes app-specific storage");
                }
                if (result.containsKey(name)) throw new IllegalStateException("Duplicate file-provider root name: " + name);
                result.put(name, new Root(name, canonicalRoot));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read file-provider paths resource", error);
        } finally {
            parser.close();
        }
        if (result.isEmpty()) throw new IllegalStateException("File provider has no usable private roots");
        return result;
    }

    private static File externalDirectory(Context context, String methodName, boolean takesType) {
        if (Build.VERSION.SDK_INT < 8) return null;
        try {
            java.lang.reflect.Method method = takesType
                ? Context.class.getMethod(methodName, String.class)
                : Context.class.getMethod(methodName);
            return (File) (takesType ? method.invoke(context, new Object[] { null }) : method.invoke(context));
        } catch (Exception unavailable) {
            return null;
        }
    }

}
