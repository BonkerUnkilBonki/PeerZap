package com.quickshare;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;

/**
 * The JavaScript bridge shared by the main app and the Quick Settings panel.
 * It exposes the native sharing engine to a WebView and pushes engine events
 * back into the page as {@code window.qsEvent(name, payload)} calls.
 *
 * Either screen can own one; only the {@link WebView} and {@link Activity} differ.
 */
public class QsBridge {

    public static final int REQ_PICK = 101;

    private final Activity act;
    private volatile WebView web;

    public QsBridge(Activity a) { this.act = a; }

    public void attach(WebView w) {
        this.web = w;
        Discovery.get(act).setListener(discoveryListener);
        TransferManager.get(act).setListener(transferListener);
    }

    public void detach() {
        this.web = null;
        Discovery.get(act).setListener(null);
        TransferManager.get(act).setListener(null);
    }

    public Activity activity() { return act; }

    /** Starts the engine (discovery + receive server). */
    public void startEngine() {
        // the UI is back, so cancel any pending "shut down when the transfer ends"
        try { TransferManager.get(act).setShutdownWhenIdle(false); } catch (Throwable ignored) {}
        // Quick Share never keeps a background service of its own: the engine runs
        // in-process while a screen is visible, and the service is only started for
        // the duration of a transfer (see onVisibleUiGone).
        Discovery.get(act).start();
        TransferManager.get(act).start();
    }

    /**
     * Called when the panel (or the app screen) goes away.
     *
     * - If a transfer is still running, the engine is kept alive so it can finish
     *   and the live progress notification keeps updating; it then shuts itself
     *   down on its own.
     * - Otherwise the engine is stopped, unless the user asked Quick Share to keep
     *   listening in the background.
     */
    public void onVisibleUiGone() {
        TransferManager tm = TransferManager.get(act);
        try {
            if (tm.hasActive()) {
                // A transfer is still running: keep Quick Share alive just long
                // enough for it to finish. It shuts itself down the moment the
                // last transfer ends (TransferManager.shutdownIfIdle).
                try { act.startService(new Intent(act, TransferService.class)); } catch (Throwable ignored) {}
                tm.setShutdownWhenIdle(true);
                return;
            }
        } catch (Throwable ignored) {}
        // nothing in flight - close Quick Share completely, no background
        stopEngine();
    }

    /**
     * Stops the engine completely - discovery, the receive server and the
     * foreground service. Used when the "run only while the panel is open"
     * setting is on and the visible screen goes away.
     */
    public void stopEngine() {
        try { Discovery.get(act).stop(); } catch (Throwable ignored) {}
        try { TransferManager.get(act).stop(); } catch (Throwable ignored) {}
        try { act.stopService(new Intent(act, TransferService.class)); } catch (Throwable ignored) {}
    }

    public void push(final String name, final String jsonLiteral) {
        final WebView w = web;
        if (w == null) return;
        w.post(new Runnable() {
            public void run() {
                try {
                    w.evaluateJavascript(
                            "window.qsEvent&&window.qsEvent('" + name + "'," + jsonLiteral + ")", null);
                } catch (Throwable ignored) {}
            }
        });
    }

    public void toast(final String text) { push("toast", JSONObject.quote(text)); }

    // ------------------------------------------------------------ listeners

    private final Discovery.Listener discoveryListener = new Discovery.Listener() {
        public void onDevicesChanged(JSONArray devices) { push("devices", devices.toString()); }
    };

    private final TransferManager.Listener transferListener = new TransferManager.Listener() {
        public void onTransfersChanged(JSONArray active) { push("transfers", active.toString()); }
        public void onHistoryChanged(JSONArray history) { push("history", history.toString()); }
        public void onIncoming(JSONObject rec) { push("incoming", rec.toString()); }
        public void onToast(String text) { push("toast", JSONObject.quote(text)); }
    };

    // ---------------------------------------------------------- share intent

    public void handleShareIntent(Intent i) {
        if (i == null) return;
        String action = i.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        ArrayList<Uri> uris = new ArrayList<>();
        try {
            if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) uris.addAll(list);
            } else {
                Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
            }
        } catch (Throwable ignored) {}
        if (uris.isEmpty()) return;
        final JSONArray files = describe(uris);
        TransferManager.get(act).setPendingFiles(files);
        push("picked", files.toString());
        final WebView w = web;
        if (w != null) w.postDelayed(new Runnable() {
            public void run() { push("picked", files.toString()); }
        }, 700);
    }

    public JSONArray describe(ArrayList<Uri> uris) {
        JSONArray arr = new JSONArray();
        for (Uri u : uris) {
            try {
                String name = FileUtil.displayName(act, u);
                JSONObject o = new JSONObject();
                o.put("uri", u.toString());
                o.put("name", name);
                o.put("size", FileUtil.size(act, u));
                o.put("mime", FileUtil.mime(act, u, name));
                arr.put(o);
            } catch (Throwable ignored) {}
        }
        return arr;
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK) return false;
        ArrayList<Uri> uris = new ArrayList<>();
        if (resultCode == Activity.RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int k = 0; k < cd.getItemCount(); k++) {
                    Uri u = cd.getItemAt(k).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri u : uris) {
                try {
                    act.getContentResolver().takePersistableUriPermission(
                            u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
            }
        }
        if (!uris.isEmpty()) {
            JSONArray files = describe(uris);
            TransferManager.get(act).setPendingFiles(files);
            push("picked", files.toString());
        }
        return true;
    }

    public Intent pickIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return Intent.createChooser(i, "Select files to share");
    }

    // ---------------------------------------------------------------- bridge

    public class Api {

        @JavascriptInterface
        public void theme(final String barColor, final boolean lightIcons) {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        int c = Color.parseColor(barColor);
                        act.getWindow().setStatusBarColor(c);
                        act.getWindow().setNavigationBarColor(c);
                        if (Build.VERSION.SDK_INT >= 23) {
                            int f = act.getWindow().getDecorView().getSystemUiVisibility();
                            if (lightIcons) f &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                            else f |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                            act.getWindow().getDecorView().setSystemUiVisibility(f);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        }

        @JavascriptInterface
        public String getState() {
            try {
                JSONObject o = new JSONObject();
                Discovery d = Discovery.get(act);
                TransferManager tm = TransferManager.get(act);
                Store st = new Store(act);
                o.put("devices", d.devices());
                o.put("transfers", tm.snapshotActive());
                o.put("incoming", tm.snapshotIncoming());
                o.put("history", st.history());
                o.put("pending", tm.pendingFiles());
                o.put("running", d.isRunning());
                o.put("localIp", localIp());
                JSONObject s = new JSONObject();
                s.put("deviceName", st.deviceName(act));
                s.put("visible", st.visible());
                s.put("saveDir", st.saveDirName());
                s.put("notifyIncoming", st.notifyIncoming());
                s.put("notifyComplete", st.notifyComplete());
                s.put("autoAccept", st.autoAccept());
                s.put("theme", st.theme());
                s.put("accent", st.accent());
                s.put("glow", st.glow());
                s.put("glass", st.glass());
                s.put("version", "1.9");
                o.put("settings", s);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void startDiscovery() { Discovery.get(act).start(); }

        @JavascriptInterface
        public void stopDiscovery() { Discovery.get(act).stop(); }

        @JavascriptInterface
        public String getDevices() { return Discovery.get(act).devices().toString(); }

        @JavascriptInterface
        public void pickFiles() {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try { act.startActivityForResult(pickIntent(), REQ_PICK); } catch (Throwable ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void clearPending() { TransferManager.get(act).clearPending(); }

        @JavascriptInterface
        public String sendTo(final String deviceId) {
            JSONObject dev = Discovery.get(act).deviceById(deviceId);
            if (dev == null) return null;
            return TransferManager.get(act).sendTo(dev);
        }

        @JavascriptInterface
        public void accept(String id) { TransferManager.get(act).accept(id); }

        @JavascriptInterface
        public void reject(String id) { TransferManager.get(act).reject(id); }

        @JavascriptInterface
        public void cancel(String id) { TransferManager.get(act).cancel(id); }

        @JavascriptInterface
        public String history() { return new Store(act).history().toString(); }

        @JavascriptInterface
        public void clearHistory() { new Store(act).clearHistory(); }

        @JavascriptInterface
        public void removeHistory(String id) { new Store(act).removeHistory(id); }

        @JavascriptInterface
        public void setSetting(String key, String value) {
            Store st = new Store(act);
            if (key == null) return;
            switch (key) {
                case "deviceName": st.setDeviceName(value); break;
                case "visible": st.setVisible("true".equals(value)); break;
                case "saveDir": st.setSaveDirName(value); break;
                case "notifyIncoming": st.setNotifyIncoming("true".equals(value)); break;
                case "notifyComplete": st.setNotifyComplete("true".equals(value)); break;
                case "autoAccept": st.setAutoAccept("true".equals(value)); break;
                case "theme": st.setTheme(value); break;
                case "accent": st.setAccent(value); break;
                case "glow": st.setGlow("true".equals(value)); break;
                case "glass": st.setGlass("true".equals(value)); break;
            }
        }

        @JavascriptInterface
        public void openFile(String ref) {
            try {
                Uri u;
                String type;
                if (ref != null && ref.startsWith("content://")) {
                    u = Uri.parse(ref);
                    type = act.getContentResolver().getType(u);
                    if (type == null) type = FileUtil.mimeFromName(ref);
                } else {
                    u = ReceivedFileProvider.uriFor(ref);
                    type = FileUtil.mimeFromName(ref);
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(u, type);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Throwable t) {
                toast("No app can open this file");
            }
        }

        @JavascriptInterface
        public void copy(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) act.getSystemService(Activity.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Quick Share", text));
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public String localIpValue() { return localIp(); }

        /** Opens the full app screen from the panel. */
        @JavascriptInterface
        public void openApp() {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(act, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        act.startActivity(i);
                    } catch (Throwable ignored) {}
                }
            });
        }

        /** Lets the panel ask its host activity to close. */
        @JavascriptInterface
        public void closePanel() {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    if (act instanceof SharePanelActivity) ((SharePanelActivity) act).dismiss();
                }
            });
        }

        // --------------------------------------------------- built-in file manager

        @JavascriptInterface
        public boolean hasAllFilesAccess() {
            try {
                if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
                return act.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public void requestAllFilesAccess() {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + act.getPackageName()));
                            act.startActivity(i);
                        } else {
                            act.requestPermissions(new String[]{
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
                        }
                    } catch (Throwable t) {
                        try {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Throwable ignored) {}
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean hasContactsAccess() {
            try {
                return act.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public void requestContactsAccess() {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        act.requestPermissions(new String[]{"android.permission.READ_CONTACTS"}, 3);
                    } catch (Throwable ignored) {}
                }
            });
        }

        @JavascriptInterface
        public String listFiles(String category) {
            try { return Files.list(act, category).toString(); } catch (Throwable t) { return "[]"; }
        }

        @JavascriptInterface
        public String fileCounts() {
            try { return Files.counts(act).toString(); } catch (Throwable t) { return "{}"; }
        }

        @JavascriptInterface
        public String listFolder(String path) {
            try { return Files.folder(act, path).toString(); } catch (Throwable t) { return "[]"; }
        }

        @JavascriptInterface
        public String storageRoots() {
            try { return Files.roots(act).toString(); } catch (Throwable t) { return "[]"; }
        }

        @JavascriptInterface
        public String fileInfo(String ref) {
            try { return Files.info(act, ref).toString(); } catch (Throwable t) { return "{}"; }
        }

        @JavascriptInterface
        public void openRef(String ref) {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Uri u = toReadable(ref);
                        String type = act.getContentResolver().getType(u);
                        if (type == null) type = FileUtil.mimeFromName(ref);
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(u, type);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(i);
                    } catch (Throwable t) { toast("No app can open this file"); }
                }
            });
        }

        @JavascriptInterface
        public void shareRef(String ref, String mime, String name) {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Uri u = toReadable(ref);
                        String type = (mime == null || mime.isEmpty())
                                ? act.getContentResolver().getType(u) : mime;
                        if (type == null) type = FileUtil.mimeFromName(name != null ? name : ref);
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType(type);
                        i.putExtra(Intent.EXTRA_STREAM, u);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        act.startActivity(Intent.createChooser(i, "Share " + (name == null ? "file" : name)));
                    } catch (Throwable t) { toast("Could not share this file"); }
                }
            });
        }

        /**
         * Queues the files chosen in the in-panel file picker. Takes a JSON array
         * of {uri|path, name, size, mime} entries and makes them the pending send.
         */
        @JavascriptInterface
        public void pickRefs(String json) {
            try {
                JSONArray arr = new JSONArray(json);
                ArrayList<Uri> uris = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String ref = o.optString("uri", "");
                    if (ref.isEmpty()) ref = o.optString("path", "");
                    if (ref.isEmpty()) continue;
                    try { uris.add(toReadable(ref)); } catch (Throwable ignored) {}
                }
                if (uris.isEmpty()) return;
                JSONArray files = describe(uris);
                TransferManager.get(act).setPendingFiles(files);
                push("picked", files.toString());
            } catch (Throwable ignored) {}
        }

        /** Sends the file straight to the Quick Share panel (queues it for sending). */
        @JavascriptInterface
        public void sendRef(final String ref, final String mime, final String name) {
            try {
                android.net.Uri u = toReadable(ref);
                ArrayList<Uri> one = new ArrayList<>();
                one.add(u);
                JSONArray files = describe(one);
                TransferManager.get(act).setPendingFiles(files);
                push("picked", files.toString());
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void openApp(String pkg) {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
                        if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(i); }
                        else toast("This app has no launcher");
                    } catch (Throwable t) { toast("Could not open this app"); }
                }
            });
        }

        @JavascriptInterface
        public void appInfo(String pkg) {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(Uri.parse("package:" + pkg));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(i);
                    } catch (Throwable ignored) {}
                }
            });
        }

        /** Copies an installed app's APK to cache and shares it. */
        @JavascriptInterface
        public void shareAppApk(final String pkg) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        android.content.pm.ApplicationInfo ai = act.getPackageManager().getApplicationInfo(pkg, 0);
                        File src = new File(ai.sourceDir);
                        File dir = new File(act.getCacheDir(), "shared");
                        if (!dir.exists()) dir.mkdirs();
                        File dst = new File(dir, pkg + ".apk");
                        InputStream in = new java.io.FileInputStream(src);
                        FileOutputStream out = new FileOutputStream(dst);
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.flush(); out.close(); in.close();
                        final Uri u = ReceivedFileProvider.uriFor(dst.getAbsolutePath());
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                try {
                                    Intent i = new Intent(Intent.ACTION_SEND);
                                    i.setType("application/vnd.android.package-archive");
                                    i.putExtra(Intent.EXTRA_STREAM, u);
                                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    act.startActivity(Intent.createChooser(i, "Share app"));
                                } catch (Throwable ignored) {}
                            }
                        });
                    } catch (Throwable t) { toast("Could not extract this APK"); }
                }
            }).start();
        }

        @JavascriptInterface
        public void dialNumber(String number) {
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(i);
                    } catch (Throwable ignored) {}
                }
            });
        }
    }

    /** Turns any reference into a URI another app can read. */
    private Uri toReadable(String ref) {
        if (ref == null) return Uri.EMPTY;
        if (ref.startsWith("content://")) return Uri.parse(ref);
        if (ref.startsWith("file://")) return ReceivedFileProvider.uriFor(Uri.parse(ref).getPath());
        return ReceivedFileProvider.uriFor(ref);
    }

    private String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!a.isLoopbackAddress() && a.getHostAddress() != null
                            && a.getHostAddress().indexOf(':') < 0) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }
}
