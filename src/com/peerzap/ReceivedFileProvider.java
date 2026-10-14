package com.peerzap;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves received files to other apps (the viewer, the share sheet) as content://
 * URIs. Android 7+ refuses to hand a file:// path to another app, and this app is
 * built without AndroidX, so it ships this small provider of its own.
 *
 * Only files inside the app's own received folder or the public Downloads folder
 * can be served - the requested path is canonicalised and checked against those
 * roots, so it cannot be used to read arbitrary files.
 */
public class ReceivedFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.peerzap.files";

    public static Uri uriFor(String absolutePath) {
        return Uri.parse("content://" + AUTHORITY + "/f?path=" + Uri.encode(absolutePath));
    }

    private boolean allowed(Context c, File f) {
        try {
            String p = f.getCanonicalPath();
            String[] roots = new String[]{
                    c.getExternalFilesDir(null) == null ? "" : c.getExternalFilesDir(null).getCanonicalPath(),
                    c.getFilesDir() == null ? "" : c.getFilesDir().getCanonicalPath(),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getCanonicalPath(),
                    c.getCacheDir() == null ? "" : c.getCacheDir().getCanonicalPath(),
                    // the built-in file manager browses all of shared storage (the user
                    // grants "all files access" for it); served files are still only
                    // readable by whoever was granted the one URI
                    Environment.getExternalStorageDirectory().getCanonicalPath(),
                    // SD cards and other volumes live under /storage
                    new File("/storage").getCanonicalPath()
            };
            for (String r : roots) {
                if (r != null && !r.isEmpty() && p.startsWith(r)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private File fileFor(Uri uri) {
        String path = uri == null ? null : uri.getQueryParameter("path");
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (!f.exists() || f.isDirectory()) return null;
        if (!allowed(getContext(), f)) return null;
        return f;
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = fileFor(uri);
        if (f == null) return null;
        MatrixCursor c = new MatrixCursor(new String[]{"_display_name", "_size"});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (f == null) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File f = fileFor(uri);
        return FileUtil.mimeFromName(f == null ? null : f.getName());
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
