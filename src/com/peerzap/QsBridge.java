package com.peerzap;

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
    // ------------------------------------------- stock Quick Share compatibility

    /** Starts listening for stock Quick Share senders (mDNS + TCP). */
    /** True while the app or the panel is actually on screen. */
    private volatile boolean uiVisible = false;
    /** When a UI last came up; used to ignore a stale "gone" from a transition. */
    private volatile long lastVisibleAt = 0;
    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable stopWhenStillHidden = new Runnable() {
        @Override public void run() {
            try {
                if (!uiVisible && new Store(act).strictMode()) {
                    com.peerzap.qs.QsLog.add("strict", "nothing on screen - shutting everything down");
                    stopQsCompat();
                    stopEngine();
                    try { if (qsDirect != null) qsDirect.stop(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    };

    /**
     * Strict mode keeps the listener alive only while a UI is on screen.
     *
     * The stop is debounced because of activity transitions: opening the panel over the
     * app makes MainActivity stop *and* SharePanelActivity start, and the two callbacks
     * can arrive in either order. Stopping immediately meant the panel's start could be
     * undone by the app's stop a moment later - the listener was left down, nothing
     * advertised over mDNS, and stock Quick Share could not see the device at all. So a
     * hidden UI schedules the stop instead of performing it, and any UI appearing within
     * the window cancels it.
     */
    public void setUiVisible(boolean v) {
        uiVisible = v;
        try {
            Store st = new Store(act);
            uiHandler.removeCallbacks(stopWhenStillHidden);
            if (v) {
                lastVisibleAt = System.currentTimeMillis();
                startEngine();
                if (st.qsCompat()) startQsCompat();
            } else if (st.strictMode()) {
                uiHandler.postDelayed(stopWhenStillHidden, 1500);
            }
        } catch (Throwable ignored) {}
    }

    public void startQsCompat() {
        try {
            Intent i = new Intent(act, QsReceiveService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) act.startForegroundService(i);
            else act.startService(i);
        } catch (Throwable t) {
            android.util.Log.w("QsBridge", "could not start QsReceiveService: " + t.getMessage());
        }
    }

    public void stopQsCompat() {
        try { act.stopService(new Intent(act, QsReceiveService.class)); } catch (Throwable ignored) {}
    }

    private QsBrowser qsBrowser;
    private com.peerzap.qs.QsSender qsSender;
    private QsWifiDirect qsDirect;
    /** Live outbound compat transfer, shaped like a TransferManager transfer. */
    private static volatile String liveSend = null;

    private static String liveSendJson(String peer, int count, long done, long size, String status) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", "qssend");
            o.put("dir", "send");
            o.put("peer", peer == null || peer.isEmpty() ? "Quick Share device" : peer);
            o.put("count", count);
            o.put("done", done);
            o.put("size", size);
            o.put("speed", 0);
            o.put("eta", 0);
            o.put("status", status);
            return o.toString();
        } catch (Throwable t) { return null; }
    }


    public void startEngine() {
        // the UI is back, so cancel any pending "shut down when the transfer ends"
        try { TransferManager.get(act).setShutdownWhenIdle(false); } catch (Throwable ignored) {}
        // Quick Share never keeps a background service of its own: the engine runs
        // in-process while a screen is visible, and the service is only started for
        // the duration of a transfer (see onVisibleUiGone).
        Discovery.get(act).start();
        // NB: this does NOT mark the UI visible. getState() calls startEngine() on every
        // poll, so doing that here meant a closing WebView's last poll re-armed
        // visibility and suppressed the strict-mode stop - the listener never went down.
        TransferManager.get(act).start();
        try {
            if (qsDirect == null) qsDirect = new QsWifiDirect(act);
            qsDirect.start();
        } catch (Throwable ignored) {}
        // also browse for real Google Quick Share devices on this network
        try {
            if (qsBrowser == null) qsBrowser = new QsBrowser(act);
            qsBrowser.start();
        } catch (Throwable t) {
            android.util.Log.w("QsBridge", "could not start the Quick Share browser: " + t.getMessage());
        }
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
        // Opening the panel over the app fires MainActivity.onStop and
        // SharePanelActivity.onResume, in either order. A "gone" landing just after a
        // "visible" is the outgoing activity, not the incoming one, so it is ignored -
        // otherwise the listener was stopped and nothing advertised over mDNS.
        if (System.currentTimeMillis() - lastVisibleAt < 1200) {
            com.peerzap.qs.QsLog.add("strict", "ignoring a stale gone (a UI just came up)");
            return;
        }
        uiVisible = false;
        // strict mode: schedule the stop rather than performing it, so a panel opening
        // over the app cannot be undone by the app's own onStop
        try {
            if (new Store(act).strictMode()) {
                uiHandler.removeCallbacks(stopWhenStillHidden);
                uiHandler.postDelayed(stopWhenStillHidden, 1500);
            }
        } catch (Throwable ignored) {}
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
        try { if (qsBrowser != null) qsBrowser.stop(); } catch (Throwable ignored) {}
        try { Discovery.get(act).stop(); } catch (Throwable ignored) {}
        try { TransferManager.get(act).stop(); } catch (Throwable ignored) {}
        try { act.stopService(new Intent(act, TransferService.class)); } catch (Throwable ignored) {}
        // an unregistered receiver keeps the process alive; Wi-Fi Direct holds one
        try { if (qsDirect != null) qsDirect.stop(); } catch (Throwable ignored) {}
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

    /**
     * Called by window.qsBack() once the page has undone everything it owns and
     * the back gesture should actually close the app. Runs on the UI thread
     * because it finishes the activity.
     */
    @JavascriptInterface
    public void exitApp() {
        if (act == null) return;
        act.runOnUiThread(new Runnable() {
            @Override public void run() { act.finish(); }
        });
    }

    /**
     * Sends the app's pending files to a real Quick Share device found by the
     * mDNS browse. The files are staged to the cache first so the protocol core
     * can stay free of Android imports.
     */
    @JavascriptInterface
    public String qsSendTo(final String deviceId) {
        try {
            com.peerzap.qs.QsLog.add("send", "qsSendTo(" + deviceId + ")");
            if (qsBrowser == null) { com.peerzap.qs.QsLog.add("send", "browser not started"); return "no-browser"; }
            org.json.JSONObject dev = qsBrowser.deviceById(deviceId);
            if (dev == null) {
                com.peerzap.qs.QsLog.add("send", "device not in the browse list");
                return "not-found";
            }
            com.peerzap.qs.QsLog.add("send", "peer " + dev.optString("name")
                    + " at " + dev.optString("ip") + ":" + dev.optInt("port"));
            String ip = dev.optString("ip");
            int port = dev.optInt("port");
            if (ip == null || ip.isEmpty() || port <= 0) return "no-address";
            final String peerName = dev.optString("name", "Quick Share device");

            org.json.JSONArray pending = TransferManager.get(act).pendingFiles();
            if (pending.length() == 0) return "no-files";

            final java.util.List<com.peerzap.qs.QsSender.Entry> entries = new java.util.ArrayList<>();
            for (int i = 0; i < pending.length(); i++) {
                org.json.JSONObject f = pending.optJSONObject(i);
                if (f == null) continue;
                String name = f.optString("name", "file");
                String uri = f.optString("uri", null);
                if (uri == null || uri.isEmpty()) continue;
                java.io.File tmp = new java.io.File(act.getCacheDir(),
                        "qssend_" + System.currentTimeMillis() + "_" + i);
                java.io.InputStream in = FileUtil.open(act, android.net.Uri.parse(uri));
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close(); in.close();
                entries.add(new com.peerzap.qs.QsSender.Entry(tmp, name, mimeOf(name)));
            }
            if (entries.isEmpty()) return "no-files";

            Store st = new Store(act);
            String endpointId = QsDiscovery.endpointId();
            byte[] info = QsDiscovery.endpointInfo(st.deviceName(act));

            qsSender = new com.peerzap.qs.QsSender(ip, port, st.deviceName(act), endpointId, info,
                    entries, new com.peerzap.qs.QsSender.Listener() {
                @Override public void onProgress(long done, long total, String current) {
                    com.peerzap.qs.QsLog.add("send", "progress " + done + "/" + total
                            + " (" + current + ")");
                    liveSend = liveSendJson(peerName, entries.size(), done, total, "active");
                    try { Notifier.progress(act, new org.json.JSONObject(liveSend)); } catch (Throwable ignored) {}
                }
                @Override public void onDone(String summary) {
                    com.peerzap.qs.QsLog.add("send", summary);
                    liveSend = null;
                    try { Notifier.cancelProgress(act, "qssend"); } catch (Throwable ignored) {}
                    try {
                        if (new Store(act).notifyComplete())
                            Notifier.completed(act, peerName, entries.size(), false);
                    } catch (Throwable ignored) {}
                    try {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("id", "qs" + System.currentTimeMillis());
                        o.put("dir", "send");
                        // the real peer name when we have one, and the file names, so the
                        // record matches what a LAN transfer produces
                        String realPeer = qsSender != null ? qsSender.peerName() : null;
                        if (realPeer == null || realPeer.isEmpty()) realPeer = peerName;
                        if (realPeer == null || realPeer.isEmpty()) realPeer = "Quick Share device";
                        o.put("peer", realPeer);
                        org.json.JSONArray names = new org.json.JSONArray();
                        long totalSize = 0;
                        for (com.peerzap.qs.QsSender.Entry en : entries) {
                            names.put(en.name);
                            totalSize += en.size();
                        }
                        o.put("files", names);
                        o.put("count", entries.size());
                        o.put("size", totalSize);
                        o.put("status", "completed");
                        o.put("done", totalSize);
                        o.put("time", System.currentTimeMillis());
                        new Store(act).addHistory(o);
                    } catch (Throwable ignored) {}
                }
                @Override public void onError(String message) {
                    com.peerzap.qs.QsLog.add("send", "error: " + message);
                    liveSend = null;
                    try { Notifier.cancelProgress(act, "qssend"); } catch (Throwable ignored) {}
                }
            });
            liveSend = liveSendJson(peerName, entries.size(), 0, 0, "waiting");
            qsSender.start();
            // if the peer named itself in its response, remember it against its address
            // so the browse list can show a real name next time
            final String addr = ip;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        for (int i = 0; i < 20 && qsSender != null; i++) {
                            String n = qsSender.peerName();
                            if (n != null) { new Store(act).rememberPeerName(addr, n); return; }
                            Thread.sleep(500);
                        }
                    } catch (Throwable ignored) {}
                }
            }).start();
            return "started";
        } catch (Throwable t) {
            com.peerzap.qs.QsLog.add("send", "could not start: " + t.getMessage());
            return "error:" + t.getMessage();
        }
    }

    /**
     * The MIME type for a filename, from the system's own table.
     *
     * This used to be a short hardcoded list that stopped at images, video, pdf, apk and
     * text, so everything else - zips, rars, 7z, docx, anything with a real extension -
     * was filed as application/octet-stream. Android uses this type to decide what a file
     * is, so a zip stored as octet-stream gets no "extract" action anywhere and needs a
     * specialised tool to open. MimeTypeMap knows the real type for hundreds of
     * extensions, so use it and keep the hardcoded cases only as a fallback.
     */
    private String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) {
            String ext = n.substring(dot + 1);
            try {
                String m = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(ext);
                if (m != null && !m.isEmpty()) return m;
            } catch (Throwable ignored) {}
        }
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".md") || n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

        /** Status of the Quick Share compatibility listener, for Settings. */
        /** The protocol log, for the Diagnostics panel in Settings. */
        @JavascriptInterface
        public String qsCompatLog() {
            try { return com.peerzap.qs.QsLog.dump(); } catch (Throwable t) { return ""; }
        }

        @JavascriptInterface
        public void qsCompatLogClear() {
            try { com.peerzap.qs.QsLog.clear(); } catch (Throwable ignored) {}
        }

        /**
         * Sends to a Wi-Fi Direct peer: form the group, then run the same transfer
         * the LAN path runs, against the peer's address on the direct link.
         */
        /** Opens the system Wi-Fi screen so the user can switch the radio on. */
        @JavascriptInterface
        public void openWifiSettings() {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Throwable ignored) {}
        }



        /** Pairs with a nearby phone if needed, then links over Bluetooth. */

        /** Every IPv4 address this device answers on, for manual connection. */

        /**
         * Sends to a typed-in address. This is the escape hatch for any path the app
         * cannot discover by itself - most usefully a VPN overlay such as Tailscale or
         * ZeroTier, which gives both phones a routable address over mobile data. The
         * transfer itself is the ordinary one; only the address is supplied by hand.
         */

        @JavascriptInterface
        public String qsDirectSend(final String deviceId) {
            try {
                com.peerzap.qs.QsLog.add("direct", "qsDirectSend(" + deviceId + ")");
                if (qsDirect == null) { qsDirect = new QsWifiDirect(act); qsDirect.start(); }
                org.json.JSONObject dev = qsDirect.deviceById(deviceId);
                if (dev == null) return "not-found";
                if (TransferManager.get(act).pendingFiles().length() == 0) return "no-files";
                qsDirect.connect(deviceId, new Runnable() {
                    @Override public void run() {
                        try {
                            String ip = qsDirect.linkedPeerIp();
                            if (ip == null) return;
                            org.json.JSONObject dev2 = qsDirect.deviceById(deviceId);
                            if (dev2 == null) return;
                            dev2.put("ip", ip);
                            com.peerzap.qs.QsLog.add("direct", "sending over the direct link to " + ip);
                            TransferManager.get(act).sendTo(dev2);
                        } catch (Throwable t) {
                            com.peerzap.qs.QsLog.add("direct", "direct send failed: " + t.getMessage());
                        }
                    }
                });
                return "linking";
            } catch (Throwable t) {
                com.peerzap.qs.QsLog.add("direct", "qsDirectSend threw: " + t.getMessage());
                return "error:" + t.getMessage();
            }
        }

        /** True when the app may bring the panel up from the background. */
        @JavascriptInterface
        public boolean canPopup() {
            try { return android.provider.Settings.canDrawOverlays(act); }
            catch (Throwable t) { return false; }
        }

        /** Opens "Display over other apps", which the incoming-transfer popup needs. */
        @JavascriptInterface
        public void openOverlaySettings() {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + act.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Throwable ignored) {}
        }

        private static final String UPD_REPO = "BonkerUnkilBonki/PeerZap";

        /**
         * Asks GitHub for the newest published release and compares it with this build.
         * Done natively rather than in the WebView: the page is served from file://, and
         * a cross-origin fetch from that origin is not reliable.
         */
        @JavascriptInterface
        public String checkForUpdate() {
            org.json.JSONObject out = new org.json.JSONObject();
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.github.com/repos/" + UPD_REPO + "/releases")
                                .openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(12000);
                c.setReadTimeout(12000);
                java.io.InputStream is = c.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                org.json.JSONArray rels = new org.json.JSONArray(new String(bos.toByteArray(), "UTF-8"));

                String bestTag = null, bestUrl = null, bestNotes = null;
                for (int i = 0; i < rels.length(); i++) {
                    org.json.JSONObject r = rels.optJSONObject(i);
                    if (r == null || r.optBoolean("draft", false)) continue;
                    String tag = r.optString("tag_name", "");
                    if (tag.isEmpty()) continue;
                    if (bestTag != null && !newerVersion(tag, bestTag)) continue;
                    org.json.JSONArray assets = r.optJSONArray("assets");
                    String url = null;
                    if (assets != null) for (int j = 0; j < assets.length(); j++) {
                        org.json.JSONObject a = assets.optJSONObject(j);
                        if (a != null && a.optString("name", "").toLowerCase().endsWith(".apk")) {
                            url = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                    bestTag = tag; bestUrl = url; bestNotes = r.optString("body", null);
                }

                String mine = "";
                try {
                    mine = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
                } catch (Throwable ignored) {}
                out.put("ok", true);
                out.put("current", mine == null ? "" : mine);
                out.put("latest", bestTag == null ? "" : bestTag);
                out.put("url", bestUrl == null ? "" : bestUrl);
                out.put("notes", bestNotes == null ? "" : bestNotes);
                out.put("available", bestTag != null && bestUrl != null
                        && mine != null && !mine.isEmpty() && newerVersion(bestTag, mine));
            } catch (Throwable t) {
                try { out.put("ok", false); out.put("error", t.getMessage()); } catch (Throwable ignored) {}
            }
            return out.toString();
        }

        /** Downloads an APK and hands it to the Android installer. */
        @JavascriptInterface
        public void downloadAndInstall(final String url, final String name) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                                new java.net.URL(url).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(60000);
                        java.io.InputStream is = c.getInputStream();
                        java.io.File out = new java.io.File(
                                android.os.Environment.getExternalStoragePublicDirectory(
                                        android.os.Environment.DIRECTORY_DOWNLOADS),
                                name == null || name.isEmpty() ? "QuickShare-update.apk" : name);
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                        fos.flush(); fos.close(); is.close();
                        com.peerzap.qs.QsLog.add("update", "downloaded " + out.getAbsolutePath());
                        final android.net.Uri u = android.net.Uri.fromFile(out);
                        act.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                try {
                                    Intent i = new Intent(Intent.ACTION_VIEW);
                                    i.setDataAndType(u, "application/vnd.android.package-archive");
                                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            | Intent.FLAG_ACTIVITY_NEW_TASK);
                                    act.startActivity(i);
                                } catch (Throwable t) {
                                    com.peerzap.qs.QsLog.add("update", "installer: " + t.getMessage());
                                }
                            }
                        });
                    } catch (Throwable t) {
                        com.peerzap.qs.QsLog.add("update", "download failed: " + t.getMessage());
                    }
                }
            }).start();
        }

        /** True when tag (e.g. v1.64) is a higher version than base (e.g. 1.63). */
        private boolean newerVersion(String tag, String base) {
            try {
                int[] a = versionParts(tag), b = versionParts(base);
                for (int i = 0; i < Math.max(a.length, b.length); i++) {
                    int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
                    if (x != y) return x > y;
                }
            } catch (Throwable ignored) {}
            return false;
        }

        private int[] versionParts(String v) {
            java.util.List<Integer> out = new java.util.ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (int i = 0; v != null && i < v.length(); i++) {
                char c = v.charAt(i);
                if (c >= '0' && c <= '9') cur.append(c);
                else if (cur.length() > 0) { out.add(Integer.parseInt(cur.toString())); cur.setLength(0); }
            }
            if (cur.length() > 0) out.add(Integer.parseInt(cur.toString()));
            int[] a = new int[out.size()];
            for (int i = 0; i < a.length; i++) a[i] = out.get(i);
            return a;
        }

        /**
         * Switches the launcher icon. The four styles are activity-aliases, and only one
         * is enabled at a time - a manifest icon cannot be changed at runtime, but which
         * alias is enabled can.
         */
        @JavascriptInterface
        public void setIcon(String which) {
            try {
                String want = ".IconLight";
                if ("light_ios".equals(which)) want = ".IconLightIos";
                else if ("dark".equals(which)) want = ".IconDark";
                else if ("dark_ios".equals(which)) want = ".IconDarkIos";
                String[] aliases = {".IconLight", ".IconLightIos", ".IconDark", ".IconDarkIos"};
                for (String alias : aliases) {
                    int state = alias.equals(want)
                            ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                    act.getPackageManager().setComponentEnabledSetting(
                            new android.content.ComponentName(act, alias),
                            state, android.content.pm.PackageManager.DONT_KILL_APP);
                }
                new Store(act).setIconStyle(which);
                com.peerzap.qs.QsLog.add("ui", "launcher icon -> " + which);
            } catch (Throwable t) {
                com.peerzap.qs.QsLog.add("ui", "setIcon: " + t.getMessage());
            }
        }

        /** Opens a link in whatever app handles it. */
        @JavascriptInterface
        public void openUrl(String url) {
            try {
                if (url == null || url.trim().isEmpty()) return;
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url.trim()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Throwable t) {
                com.peerzap.qs.QsLog.add("ui", "openUrl failed: " + t.getMessage());
            }
        }

        @JavascriptInterface
        public void qsConsentAccept() {
            com.peerzap.qs.QsLog.add("service", "user accepted the stock transfer");
            com.peerzap.QsReceiveService.resolveConsent(true);
        }

        @JavascriptInterface
        public void qsConsentReject() {
            com.peerzap.qs.QsLog.add("service", "user declined the stock transfer");
            com.peerzap.QsReceiveService.resolveConsent(false);
        }

        public String qsCompatStatus() {
            JSONObject o = new JSONObject();
            try {
                o.put("running", QsReceiveService.running);
                o.put("port", QsReceiveService.port);
                o.put("status", QsReceiveService.lastStatus == null ? "" : QsReceiveService.lastStatus);
            } catch (Throwable ignored) {}
            return o.toString();
        }

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
                // The panel and the app share this bridge, but only the app called
                // startEngine(). Without this the panel discovered nothing at all -
                // no local peers and no Quick Share devices.
                try { startEngine(); } catch (Throwable ignored) {}
                JSONObject o = new JSONObject();
                Discovery d = Discovery.get(act);
                TransferManager tm = TransferManager.get(act);
                Store st = new Store(act);
                JSONArray all = d.devices();
                if (qsBrowser != null) {
                    JSONArray stock = qsBrowser.devices();
                    for (int i = 0; i < stock.length(); i++) all.put(stock.get(i));
                }
                if (qsDirect != null) {
                    JSONArray direct = qsDirect.devices();
                    for (int i = 0; i < direct.length(); i++) all.put(direct.get(i));
                }
                o.put("devices", all);
                JSONArray transfers = tm.snapshotActive();
                try {
                    if (liveSend != null) {
                        JSONObject ls = new JSONObject(liveSend);
                        String lst = ls.optString("status");
                        if ("active".equals(lst) || "waiting".equals(lst)) transfers.put(ls);
                    }
                    String live = com.peerzap.QsReceiveService.live();
                    if (live != null) {
                        JSONObject lt = new JSONObject(live);
                        String lst = lt.optString("status");
                        // only an in-flight compat transfer belongs in the progress list
                        if ("active".equals(lst) || "waiting".equals(lst)) transfers.put(lt);
                    }
                } catch (Throwable ignored) {}
                o.put("transfers", transfers);
                JSONArray incoming = tm.snapshotIncoming();
                try {
                    String pc = com.peerzap.QsReceiveService.pendingConsentJson();
                    if (pc != null) incoming.put(new JSONObject(pc));
                } catch (Throwable ignored) {}
                o.put("incoming", incoming);
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
                s.put("qsCompat", st.qsCompat());
                s.put("strictMode", st.strictMode());
                s.put("iconStyle", st.iconStyle());
                // read it from the installed package - a hardcoded string goes stale
                // every release, which is exactly what happened here.
                String vName = "";
                String vCode = "";
                String pkgName = act.getPackageName();
                try {
                    android.content.pm.PackageInfo pi = act.getPackageManager()
                            .getPackageInfo(pkgName, 0);
                    vName = pi.versionName == null ? "" : pi.versionName;
                    vCode = android.os.Build.VERSION.SDK_INT >= 28
                            ? String.valueOf(pi.getLongVersionCode())
                            : String.valueOf(pi.versionCode);
                } catch (Throwable ignored) {}
                s.put("version", vName);
                s.put("versionCode", vCode);
                s.put("pkg", pkgName);
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
            // a Bluetooth peer is not in the LAN discovery list
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
                case "strictMode":
                    st.setStrictMode("true".equals(value));
                    uiHandler.removeCallbacks(stopWhenStillHidden);
                    if (st.qsCompat() && uiVisible) startQsCompat();
                    else if (st.strictMode() && !uiVisible) {
                        uiHandler.postDelayed(stopWhenStillHidden, 1500);
                    }
                    break;
                case "qsCompat":
                    boolean wasCompat = st.qsCompat();
                    boolean nowCompat = "true".equals(value);
                    st.setQsCompat(nowCompat);
                    // Stock Quick Share sends without our consent prompt, so turning
                    // compatibility ON also turns auto-accept on - once, as part of that
                    // switch. It must NOT happen on every write: applyTheme() re-sends
                    // qsCompat whenever any unrelated setting changes (accent, glow,
                    // glass, theme, icon), and re-forcing it there silently switched
                    // auto-accept back on moments after the user turned it off.
                    if (nowCompat && !wasCompat) st.setAutoAccept(true);
                    // strict mode: do not bring the listener up for a hidden UI
                    if (st.qsCompat() && (!st.strictMode() || uiVisible)) startQsCompat();
                    else stopQsCompat();
                    break;
            }
        }

        /**
         * Opens a file from a history entry that has no stored uri - the Quick Share
         * records, whose files land in Downloads/QuickShare via MediaStore. Looked up
         * by name, newest match first.
         */
        @JavascriptInterface
        public void openHistoryFile(String historyId) {
            try {
                org.json.JSONArray hist = new Store(act).history();
                org.json.JSONObject rec = null;
                for (int i = 0; i < hist.length(); i++) {
                    org.json.JSONObject o = hist.optJSONObject(i);
                    if (o != null && historyId.equals(o.optString("id"))) { rec = o; break; }
                }
                if (rec == null) return;
                org.json.JSONArray files = rec.optJSONArray("files");
                String name = files != null && files.length() > 0 ? files.optString(0) : null;
                if (name == null || name.isEmpty()) return;

                android.net.Uri uri = null;
                try {
                    String rel = android.os.Environment.DIRECTORY_DOWNLOADS + "/PeerZap";
                    android.database.Cursor c = act.getContentResolver().query(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            new String[]{android.provider.MediaStore.MediaColumns._ID},
                            android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                            new String[]{name}, android.provider.MediaStore.MediaColumns.DATE_ADDED + " DESC");
                    if (c != null) {
                        if (c.moveToFirst()) {
                            long idv = c.getLong(0);
                            uri = android.content.ContentUris.withAppendedId(
                                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, idv);
                        }
                        c.close();
                    }
                } catch (Throwable ignored) {}

                if (uri == null) {
                    com.peerzap.qs.QsLog.add("file", "open: " + name + " not found in Downloads/PeerZap");
                    return;
                }
                String type = act.getContentResolver().getType(uri);
                if (type == null) type = FileUtil.mimeFromName(name);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, type);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Throwable t) {
                com.peerzap.qs.QsLog.add("file", "openHistoryFile: " + t.getMessage());
            }
        }

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
