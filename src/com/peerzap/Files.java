package com.peerzap;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.ContactsContract;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The data behind the built-in file manager: MediaStore-backed categories
 * (recent, images, documents, audio, downloads, screenshots, apk, archive, files),
 * a plain folder browser over shared storage, the installed-apps list and the
 * contacts list.
 *
 * Every entry is returned as a small JSON object the web UI renders with the
 * app's own design language. No thumbnails are shipped over the bridge - the UI
 * draws a type icon from the name/MIME, which keeps the list fast.
 */
public class Files {

    public static final String[] CATEGORIES = {
            "recent", "images", "documents", "audio", "downloads", "screenshots",
            "apps", "apk", "archive", "files", "folder", "contacts"
    };

    // ------------------------------------------------------------- categories

    public static JSONArray list(Context ctx, String category) {
        if (category == null) category = "files";
        switch (category) {
            case "recent":      return recent(ctx);
            case "images":      return media(ctx, imagesUris(ctx), null, "date", 400);
            case "audio":       return media(ctx, audioUris(ctx), null, "date", 400);
            case "documents":   return media(ctx, filesUris(ctx), nameLike(DOC_EXT), "date", 400);
            case "downloads":   return downloads(ctx);
            case "screenshots": return screenshots(ctx);
            case "apk":         return media(ctx, filesUris(ctx), nameLike(new String[]{"apk"}), "date", 400);
            case "archive":     return media(ctx, filesUris(ctx), nameLike(ARCHIVE_EXT), "date", 400);
            case "apps":        return apps(ctx);
            case "contacts":    return contacts(ctx);
            case "files":       return media(ctx, filesUris(ctx), sizePositive(), "date", 500);
            default:            return new JSONArray();
        }
    }

    /** Counts per category, for the tiles on the overview screen. */
    public static JSONObject counts(Context ctx) {
        JSONObject o = new JSONObject();
        for (String c : CATEGORIES) {
            if ("folder".equals(c) || "apps".equals(c) || "contacts".equals(c)) continue;
            try { o.put(c, list(ctx, c).length()); } catch (Throwable ignored) {}
        }
        return o;
    }

    private static final String[] DOC_EXT = {"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "csv", "md", "rtf", "epub", "odt", "ods", "json", "xml"};
    private static final String[] ARCHIVE_EXT = {"zip", "rar", "7z", "tar", "gz", "xz", "bz2", "iso"};

    private static Uri filesUri() { return MediaStore.Files.getContentUri("external"); }

    /** Every mounted storage volume - internal shared storage plus any SD card. */
    public static String[] volumes(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Set<String> v = MediaStore.getExternalVolumeNames(ctx);
                if (v != null && !v.isEmpty()) return v.toArray(new String[0]);
            }
        } catch (Throwable ignored) {}
        return new String[]{"external"};
    }

    private static Uri[] filesUris(Context ctx) {
        String[] vols = volumes(ctx);
        Uri[] out = new Uri[vols.length];
        for (int i = 0; i < vols.length; i++) out[i] = MediaStore.Files.getContentUri(vols[i]);
        return out;
    }

    private static Uri[] imagesUris(Context ctx) {
        if (Build.VERSION.SDK_INT < 29) return new Uri[]{MediaStore.Images.Media.EXTERNAL_CONTENT_URI};
        String[] vols = volumes(ctx);
        Uri[] out = new Uri[vols.length];
        for (int i = 0; i < vols.length; i++) out[i] = MediaStore.Images.Media.getContentUri(vols[i]);
        return out;
    }

    private static Uri[] audioUris(Context ctx) {
        if (Build.VERSION.SDK_INT < 29) return new Uri[]{MediaStore.Audio.Media.EXTERNAL_CONTENT_URI};
        String[] vols = volumes(ctx);
        Uri[] out = new Uri[vols.length];
        for (int i = 0; i < vols.length; i++) out[i] = MediaStore.Audio.Media.getContentUri(vols[i]);
        return out;
    }

    private static String nameLike(String[] exts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < exts.length; i++) {
            if (i > 0) sb.append(" OR ");
            sb.append(MediaStore.MediaColumns.DISPLAY_NAME).append(" LIKE '%.").append(exts[i]).append("'");
        }
        return sb.toString();
    }

    private static String sizePositive() { return MediaStore.MediaColumns.SIZE + " > 0"; }

    private static String pathLike(String needle) {
        if (Build.VERSION.SDK_INT >= 29)
            return MediaStore.MediaColumns.RELATIVE_PATH + " LIKE '%" + needle + "%'";
        return MediaStore.MediaColumns.DATA + " LIKE '%" + needle + "%'";
    }

    private static JSONArray recent(Context ctx) {
        return media(ctx, filesUris(ctx), sizePositive(), "date", 250);
    }

    private static JSONArray downloads(Context ctx) {
        return media(ctx, filesUris(ctx), "(" + sizePositive() + ") AND (" + pathLike("Download") + ")", "date", 300);
    }

    private static JSONArray screenshots(Context ctx) {
        String sel = "(" + sizePositive() + ") AND (" + pathLike("Screenshot") + ")";
        return media(ctx, filesUris(ctx), sel, "date", 300);
    }

    // ------------------------------------------------------------- MediaStore

    /** Queries every volume and merges the rows. */
    private static JSONArray media(Context ctx, Uri[] uris, String selection, String order, int limit) {
        JSONArray out = new JSONArray();
        if (uris == null) return out;
        for (Uri u : uris) {
            if (out.length() >= limit) break;
            JSONArray part = mediaOne(ctx, u, selection, order, limit - out.length());
            for (int i = 0; i < part.length(); i++) out.put(part.opt(i));
        }
        return out;
    }

    private static JSONArray mediaOne(Context ctx, Uri uri, String selection, String order, int limit) {
        JSONArray out = new JSONArray();
        ContentResolver cr = ctx.getContentResolver();
        boolean modern = Build.VERSION.SDK_INT >= 29;
        String[] cols = modern
                ? new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                               MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
                               MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH}
                : new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                               MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
                               MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA};
        String sort = "date".equals(order)
                ? MediaStore.MediaColumns.DATE_ADDED + " DESC"
                : MediaStore.MediaColumns.DISPLAY_NAME + " ASC";
        Cursor c = null;
        try {
            c = cr.query(uri, cols, selection, null, sort);
            if (c == null) return out;
            int iId = c.getColumnIndex(MediaStore.MediaColumns._ID);
            int iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int iDate = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
            int iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int iPath = c.getColumnIndex(cols[5]);
            while (c.moveToNext() && out.length() < limit) {
                String name = iName >= 0 ? c.getString(iName) : null;
                if (name == null || name.isEmpty()) continue;
                if (name.startsWith(".")) continue;                       // hidden
                JSONObject o = new JSONObject();
                long id = iId >= 0 ? c.getLong(iId) : -1;
                o.put("kind", "file");
                o.put("name", name);
                o.put("uri", ContentUris.withAppendedId(uri, id).toString());
                o.put("size", iSize >= 0 ? c.getLong(iSize) : 0);
                o.put("date", iDate >= 0 ? c.getLong(iDate) * 1000L : 0);
                o.put("mime", iMime >= 0 ? String.valueOf(c.getString(iMime)) : "");
                o.put("path", iPath >= 0 ? String.valueOf(c.getString(iPath)) : "");
                out.put(o);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    // ----------------------------------------------------------------- folders

    /**
     * Storage roots the browser can start from: internal shared storage plus any
     * SD card / other mounted volume, with a name and a removable flag for each.
     */
    public static JSONArray roots(Context ctx) {
        JSONArray out = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                List<StorageVolume> vols = sm.getStorageVolumes();
                if (vols != null) for (StorageVolume v : vols) {
                    File dir = null;
                    try { if (Build.VERSION.SDK_INT >= 30) dir = v.getDirectory(); } catch (Throwable ignored) {}
                    if (dir == null) {
                        try {
                            String uuid = v.getUuid();
                            if (uuid != null) dir = new File("/storage/" + uuid);
                        } catch (Throwable ignored) {}
                    }
                    if (dir == null || !dir.isDirectory() || !dir.canRead()) continue;
                    String label = null;
                    try { label = v.getDescription(ctx); } catch (Throwable ignored) {}
                    if (label == null || label.trim().isEmpty()) label = dir.getName();
                    boolean removable = false;
                    try { removable = v.isRemovable(); } catch (Throwable ignored) {}
                    addRoot(out, seen, dir, label, removable);
                }
            }
        } catch (Throwable ignored) {}

        // always offer the primary shared storage
        try { addRoot(out, seen, Environment.getExternalStorageDirectory(), "Internal storage", false); }
        catch (Throwable ignored) {}

        // and anything else mounted under /storage (an SD card is /storage/XXXX-XXXX)
        try {
            File[] kids = new File("/storage").listFiles();
            if (kids != null) for (File f : kids) {
                if (!f.isDirectory() || !f.canRead()) continue;
                boolean removable = !"emulated".equals(f.getName()) && !"self".equals(f.getName());
                addRoot(out, seen, f, removable ? "SD card (" + f.getName() + ")" : f.getName(), removable);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void addRoot(JSONArray out, Set<String> seen, File dir, String label, boolean removable) {
        try {
            String p = dir.getCanonicalPath();
            if (!seen.add(p)) return;
            JSONObject o = new JSONObject();
            o.put("kind", "root");
            o.put("name", label);
            o.put("path", p);
            o.put("removable", removable);
            out.put(o);
        } catch (Throwable ignored) {}
    }

    /** Contents of one folder: sub-folders first, then files. */
    public static JSONArray folder(Context ctx, String path) {
        JSONArray out = new JSONArray();
        if (path == null || path.isEmpty()) return out;
        File dir = new File(path);
        if (!dir.isDirectory() || !dir.canRead()) return out;
        File[] kids = dir.listFiles();
        if (kids == null) return out;
        List<File> list = new ArrayList<>(Arrays.asList(kids));
        Collections.sort(list, new Comparator<File>() {
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        int n = 0;
        for (File f : list) {
            if (f.getName().startsWith(".")) continue;
            if (n++ > 800) break;
            try {
                JSONObject o = new JSONObject();
                o.put("kind", f.isDirectory() ? "dir" : "file");
                o.put("name", f.getName());
                o.put("path", f.getAbsolutePath());
                o.put("size", f.isDirectory() ? 0 : f.length());
                o.put("date", f.lastModified());
                o.put("mime", f.isDirectory() ? "" : FileUtil.mimeFromName(f.getName()));
                if (!f.isDirectory()) o.put("uri", ReceivedFileProvider.uriFor(f.getAbsolutePath()).toString());
                out.put(o);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    // -------------------------------------------------------------------- apps

    public static JSONArray apps(Context ctx) {
        JSONArray out = new JSONArray();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> list = pm.getInstalledApplications(0);
            Collections.sort(list, new Comparator<ApplicationInfo>() {
                public int compare(ApplicationInfo a, ApplicationInfo b) {
                    return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
                }
            });
            for (ApplicationInfo ai : list) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("kind", "app");
                    o.put("name", String.valueOf(pm.getApplicationLabel(ai)));
                    o.put("package", ai.packageName);
                    String src = ai.sourceDir;
                    o.put("path", src == null ? "" : src);
                    long size = src == null ? 0 : new File(src).length();
                    o.put("size", size);
                    o.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    try {
                        o.put("version", String.valueOf(pm.getPackageInfo(ai.packageName, 0).versionName));
                    } catch (Throwable ignored) {}
                    o.put("launchable", pm.getLaunchIntentForPackage(ai.packageName) != null);
                    out.put(o);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return out;
    }

    // ---------------------------------------------------------------- contacts

    public static JSONArray contacts(Context ctx) {
        JSONArray out = new JSONArray();
        Cursor c = null;
        try {
            String[] proj = new String[]{
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
            };
            c = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            if (c == null) return out;
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            int iName = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int iNum = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            while (c.moveToNext() && out.length() < 800) {
                String name = iName >= 0 ? c.getString(iName) : null;
                String num = iNum >= 0 ? c.getString(iNum) : null;
                if (name == null || name.isEmpty()) continue;
                String key = name + "|" + num;
                if (!seen.add(key)) continue;
                JSONObject o = new JSONObject();
                o.put("kind", "contact");
                o.put("name", name);
                o.put("number", num == null ? "" : num);
                out.put(o);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    // ------------------------------------------------------------------ details

    public static JSONObject info(Context ctx, String ref) {
        JSONObject o = new JSONObject();
        try {
            o.put("ref", ref);
            if (ref != null && ref.startsWith("content://")) {
                Cursor c = ctx.getContentResolver().query(Uri.parse(ref),
                        new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                                     MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED},
                        null, null, null);
                if (c != null && c.moveToFirst()) {
                    o.put("name", String.valueOf(c.getString(0)));
                    o.put("size", c.getLong(1));
                    o.put("mime", String.valueOf(c.getString(2)));
                    o.put("date", c.getLong(3) * 1000L);
                    c.close();
                }
            } else if (ref != null && ref.startsWith("file://")) {
                File f = new File(Uri.parse(ref).getPath());
                o.put("name", f.getName());
                o.put("size", f.length());
                o.put("date", f.lastModified());
                o.put("mime", FileUtil.mimeFromName(f.getName()));
                o.put("path", f.getAbsolutePath());
            }
        } catch (Throwable ignored) {}
        return o;
    }
}
