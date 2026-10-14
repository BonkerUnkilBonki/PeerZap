package com.peerzap;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Content-URI metadata helpers and streaming save of received files. */
public class FileUtil {

    public interface Progress { void onBytes(long written); }

    /** Best-effort display name for a content:// or file:// uri. */
    public static String displayName(Context ctx, Uri u) {
        if (u == null) return "file";
        if ("file".equals(u.getScheme())) {
            String p = u.getLastPathSegment();
            return p == null ? "file" : p;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        String p = u.getLastPathSegment();
        return p == null ? "file" : p;
    }

    /** Size in bytes, or -1 when unknown. */
    public static long size(Context ctx, Uri u) {
        if (u == null) return -1;
        if ("file".equals(u.getScheme())) {
            try { File f = new File(u.getPath()); return f.length(); } catch (Throwable t) { return -1; }
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return -1;
    }

    public static String mime(Context ctx, Uri u, String name) {
        try {
            String m = ctx.getContentResolver().getType(u);
            if (m != null && !m.isEmpty()) return m;
        } catch (Throwable ignored) {}
        return mimeFromName(name);
    }

    public static String mimeFromName(String name) {
        if (name == null) return "application/octet-stream";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = name.substring(dot + 1).toLowerCase();
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "application/octet-stream" : m;
    }

    public static InputStream open(Context ctx, Uri u) throws Exception {
        return ctx.getContentResolver().openInputStream(u);
    }

    /** Strip path separators so a hostile filename cannot escape the target folder. */
    public static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) name = "file";
        name = name.replace('\\', '_').replace('/', '_').trim();
        if (name.equals(".") || name.equals("..")) name = "file";
        if (name.length() > 160) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : "";
            name = name.substring(0, 120) + ext;
        }
        return name;
    }

    private static String uniqueName(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!new File(dir, cand).exists()) return cand;
        }
        return base + "_" + System.currentTimeMillis() + ext;
    }

    /**
     * Streams a received file to the public Downloads folder (API 29+ via MediaStore,
     * older via the legacy public directory), falling back to the app's own external
     * files directory when the public location is unavailable.
     *
     * @return JSONObject {name, path, uri, size}
     */
    public static JSONObject saveReceived(Context ctx, String rawName, String mime, long size,
                                          InputStream in, Progress cb) throws Exception {
        String name = safeName(rawName);
        String dirName = new Store(ctx).saveDirName();
        JSONObject out = new JSONObject();

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = null;
            try {
                String rel = Environment.DIRECTORY_DOWNLOADS + "/" + dirName;
                name = uniqueMediaStoreName(cr, rel, name);
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                if (mime != null && !mime.isEmpty()) v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            } catch (Exception e) {
                uri = null;
            }
            if (uri != null) {
                try {
                    OutputStream os = cr.openOutputStream(uri);
                    if (os == null) throw new Exception("no output stream");
                    copy(in, os, cb);
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                    out.put("name", name);
                    out.put("path", "Download/" + dirName + "/" + name);
                    out.put("uri", uri.toString());
                    out.put("size", size);
                    return out;
                } catch (Exception e) {
                    // remove the incomplete entry and report the failure - do not
                    // silently write a second copy somewhere else
                    try { cr.delete(uri, null, null); } catch (Throwable ignored) {}
                    throw e;
                }
            }
        } else {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), dirName);
                if (!dir.exists()) dir.mkdirs();
                name = uniqueName(dir, name);
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                copy(in, fos, cb);
                out.put("name", name);
                out.put("path", "Download/" + dirName + "/" + name);
                out.put("uri", ReceivedFileProvider.uriFor(f.getAbsolutePath()).toString());
                out.put("size", size);
                return out;
            } catch (Exception e) {
                // fall through
            }
        }

        // Fallback: app-specific external files dir (always writable, no permission)
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "received");
        if (!dir.exists()) dir.mkdirs();
        name = uniqueName(dir, name);
        File f = new File(dir, name);
        FileOutputStream fos = new FileOutputStream(f);
        copy(in, fos, cb);
        out.put("name", name);
        out.put("path", f.getAbsolutePath());
        out.put("uri", ReceivedFileProvider.uriFor(f.getAbsolutePath()).toString());
        out.put("size", size);
        return out;
    }

    private static String uniqueMediaStoreName(ContentResolver cr, String rel, String name) {
        try {
            Uri q = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Cursor c = cr.query(q, new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{rel}, null);
            java.util.HashSet<String> have = new java.util.HashSet<>();
            if (c != null) {
                while (c.moveToNext()) {
                    String n = c.getString(0);
                    if (n != null) have.add(n);
                }
                c.close();
            }
            if (!have.contains(name)) return name;
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            for (int i = 1; i < 10000; i++) {
                String cand = base + " (" + i + ")" + ext;
                if (!have.contains(cand)) return cand;
            }
        } catch (Throwable ignored) {}
        return name;
    }

    private static void copy(InputStream in, OutputStream out, Progress cb) throws Exception {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (cb != null) cb.onBytes(total);
            }
            out.flush();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            try { out.close(); } catch (Throwable ignored) {}
        }
    }
}
