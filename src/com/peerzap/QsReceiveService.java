package com.peerzap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import com.peerzap.qs.QsFrames;
import com.peerzap.qs.QsReceiver;

import java.io.File;
import java.util.List;

/**
 * Keeps a Quick Share receiver alive while the user wants to accept transfers
 * from stock Quick Share: a foreground service of type connectedDevice, an
 * mDNS advertisement, and the TCP session handler.
 */
public class QsReceiveService extends Service {

    private static final String TAG = "QsReceiveService";
    private static final String CH = "qs_compat2";
    private static final int NOTIF_ID = 7301;

    public static volatile boolean running = false;
    public static volatile int port = -1;
    public static volatile String lastStatus = "";
    /** Live transfer state for the UI, shaped like a TransferManager transfer so
     *  the panel's existing progress card renders it with no extra UI code. */
    public static volatile String liveTransfer = null;

    public static String live() { return liveTransfer; }

    /** A stock send waiting on the user's Accept/Decline, with its PIN. */
    private static final Object CONSENT_LOCK = new Object();
    private static volatile String pendingConsent = null;
    private static volatile Boolean consentDecision = null;

    public static String pendingConsentJson() { return pendingConsent; }

    /** Called from the notification's Accept/Decline buttons. */
    public static void resolveConsent(boolean accept) {
        synchronized (CONSENT_LOCK) {
            consentDecision = accept;
            CONSENT_LOCK.notifyAll();
        }
    }

    private volatile String currentFile = "";

    private void setLive(String peer, int count, long done, long size, String status) {
        try { if (currentFile != null && !currentFile.isEmpty()) {} } catch (Throwable ignored) {}
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", "compat");
            o.put("dir", "recv");
            o.put("peer", peer == null || peer.isEmpty() ? "Quick Share device" : peer);
            o.put("count", count);
            o.put("done", done);
            o.put("size", size);
            o.put("speed", 0);
            o.put("eta", 0);
            o.put("status", status);
            o.put("current", currentFile == null ? "" : currentFile);
            liveTransfer = o.toString();
        } catch (Throwable ignored) {}
    }

    private QsReceiver receiver;
    private QsDiscovery discovery;

    @Override
    public void onCreate() {
        super.onCreate();
        // mirror the protocol log into logcat as well as the in-app buffer
        com.peerzap.qs.QsLog.setSink(new com.peerzap.qs.QsLog.Sink() {
            @Override public void log(String tag, String message) {
                android.util.Log.i("QsProto", tag + " " + message);
            }
        });
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;

        com.peerzap.qs.QsLog.add("service", "starting");
        startForegroundCompat();

        // Stage incoming files in an app-private directory. getExternalStoragePublicDirectory
        // needs "All files access"; without it mkdirs() fails and the old fallback
        // (Android/data/...) is invisible to file managers. Files are published into
        // MediaStore on completion instead, which needs no permission and is visible
        // everywhere.
        File dir = new File(getCacheDir(), "incoming");
        if (!dir.exists()) dir.mkdirs();
        com.peerzap.qs.QsLog.add("service", "staging incoming files in " + dir.getAbsolutePath());

        receiver = new QsReceiver(new QsReceiver.Listener() {
            @Override public boolean onConsent(String peer, List<QsFrames.FileMeta> files, String pin) {
                peerName = peer;
                // The UI shows the PIN; the service accepts so a transfer can
                // complete while the app is in the foreground.
                lastStatus = "Incoming from " + peer + " (PIN " + pin + ")";
                receivedCount = 0;
                receivedBytes = 0;
                synchronized (receivedNames) {
                    while (receivedNames.length() > 0) receivedNames.remove(0);
                    if (files != null) for (QsFrames.FileMeta fm : files) {
                        receivedNames.put(fm.name == null ? "file" : fm.name);
                    }
                }
                long sz = 0;
                try { for (QsFrames.FileMeta fm : files) sz += fm.size; } catch (Throwable ignored) {}
                setLive(peer, files == null ? 0 : files.size(), 0, sz, "waiting");

                // Stock Quick Share has no consent prompt of its own, so we show the
                // card ourselves. With auto-accept on it is informational - what is
                // arriving, then it receives. With it off the card carries the PIN and
                // the Accept/Decline buttons and the connection waits for the answer.
                final boolean auto = new Store(QsReceiveService.this).autoAccept();

                try {
                    org.json.JSONObject req = new org.json.JSONObject();
                    req.put("id", "qsconsent");
                    req.put("peer", peer == null ? "Quick Share device" : peer);
                    req.put("pin", pin == null ? "" : pin);
                    req.put("count", files == null ? 0 : files.size());
                    req.put("size", sz);
                    org.json.JSONArray names = new org.json.JSONArray();
                    if (files != null) for (QsFrames.FileMeta fm : files) {
                        names.put(fm.name == null ? "file" : fm.name);
                    }
                    req.put("files", names);
                    req.put("compat", true);
                    req.put("auto", auto);
                    req.put("kind", fileKind(files));
                    pendingConsent = req.toString();
                } catch (Throwable ignored) {}

                // bring the panel up so the user can see what is arriving - either the
                // Accept/Decline card, or the receiving progress in auto mode. Android
                // only allows this from the background with the overlay permission, so
                // it is wrapped and the notification remains the fallback.
                popPanel();

                if (auto) {
                    // show what is arriving, then let it through
                    updateLiveNotification();
                    com.peerzap.qs.QsLog.add("service",
                            "auto-accepting " + (files == null ? 0 : files.size()) + " file(s)");
                    return true;
                }

                consentDecision = null;
                updateNotification("Incoming from " + peer + " - PIN " + pin);
                try {
                    if (peerAddress == null && receiver != null) peerAddress = receiver.peerAddress();
                    if (peerAddress != null && peer != null) {
                        new Store(QsReceiveService.this).rememberPeerName(peerAddress, peer);
                    }
                } catch (Throwable ignored) {}
                Notifier.incomingWithPin(QsReceiveService.this, "qsconsent",
                        peer == null ? "Quick Share device" : peer,
                        files == null ? 0 : files.size(), pin);

                long deadline = System.currentTimeMillis() + 120000;
                synchronized (CONSENT_LOCK) {
                    while (consentDecision == null && System.currentTimeMillis() < deadline) {
                        try { CONSENT_LOCK.wait(1000); } catch (InterruptedException e) { break; }
                    }
                }
                boolean accepted = Boolean.TRUE.equals(consentDecision);
                pendingConsent = null;
                com.peerzap.qs.QsLog.add("service",
                        "consent for " + peer + ": " + (accepted ? "accepted" : "declined/timed out"));
                if (accepted) updateLiveNotification();
                return accepted;
            }
            @Override public void onProgress(long done, long total, String current) {
                currentFile = current == null ? "" : current;
                lastStatus = "Receiving " + current + " - " + (total > 0 ? (done * 100 / total) + "%" : done + " bytes");
                setLive(peerName, receivedCount, done, total, "active");
                updateLiveNotification();
            }
            @Override public void onFileSaved(File file) {
                Log.i(TAG, "staged " + file.getAbsolutePath());
                publish(file);
            }
            @Override public void onFinished(String summary) {
                lastStatus = summary;
                setLive(peerName, receivedCount, receivedBytes, receivedBytes, "completed");
                updateNotification(summary);
                if (new Store(QsReceiveService.this).notifyComplete() && receivedCount > 0) {
                    Notifier.completed(QsReceiveService.this, peerName, receivedCount, true);
                }
                pendingConsent = null;
                if (receivedCount > 0) recordHistory(peerName, receivedCount, receivedBytes);
            }
            @Override public void onError(String message) {
                Log.w(TAG, message);
                lastStatus = message;
                setLive(peerName, receivedCount, receivedBytes, receivedBytes, "failed");
            }
        }, dir);

        String name = new Store(this).deviceName(this);
        receiver.setLocalName(name);
        try {
            port = receiver.start();
            com.peerzap.qs.QsLog.add("service", "listening on port " + port);
            discovery = new QsDiscovery(this);
            discovery.advertise(name, port);
            com.peerzap.qs.QsLog.add("service", "advertised as \"" + name + "\" on port " + port);
            running = true;
            lastStatus = "Waiting for Quick Share - port " + port;
            updateNotification("Visible to Quick Share");
            Log.i(TAG, "receiver listening on " + port);
        } catch (Exception e) {
            Log.e(TAG, "could not start receiver", e);
            lastStatus = "Could not start: " + e.getMessage();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        com.peerzap.qs.QsLog.add("service", "stopping");
        writeLogFile();
        running = false;
        port = -1;
        try { if (discovery != null) discovery.stop(); } catch (Throwable ignored) {}
        try { if (receiver != null) receiver.stop(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private String peerName = "Quick Share device";
    private volatile String peerAddress = null;
    private int receivedCount = 0;
    private final org.json.JSONArray receivedNames = new org.json.JSONArray();
    private long receivedBytes = 0;

    /**
     * Move a staged file into the public Downloads collection via MediaStore, so it
     * shows up in file managers and the gallery without needing MANAGE_EXTERNAL_STORAGE.
     */
    private void publish(File staged) {
        try {
            receivedCount++;
            receivedBytes += staged.length();
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, staged.getName());
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeOf(staged.getName()));
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/PeerZap");
                v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                android.net.Uri uri = getContentResolver()
                        .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new java.io.IOException("MediaStore insert returned null");
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                java.io.InputStream is = new java.io.FileInputStream(staged);
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                is.close();
                os.flush();
                os.close();
                v.clear();
                v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, v, null, null);
                staged.delete();
                com.peerzap.qs.QsLog.add("file", "published to Download/QuickShare/" + staged.getName());
                lastStatus = "Saved " + staged.getName() + " to Download/QuickShare";
            } else {
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        android.net.Uri.fromFile(staged)));
                com.peerzap.qs.QsLog.add("file", "scanned " + staged.getAbsolutePath());
            }
        } catch (Throwable t) {
            com.peerzap.qs.QsLog.add("file", "publish failed: " + t.getMessage());
            lastStatus = "Could not save: " + t.getMessage();
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
    private static String mimeOf(String name) {
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

    /** Record the received transfer in the app's history, like the app's own transfers. */
    /** Brings the Quick Share panel to the front, if Android permits it. */
    private void popPanel() {
        try {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                com.peerzap.qs.QsLog.add("service",
                        "panel not popped: no overlay permission (use the notification)");
                return;
            }
            Intent i = new Intent(this, SharePanelActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
            com.peerzap.qs.QsLog.add("service", "panel popped for the incoming transfer");
        } catch (Throwable t) {
            com.peerzap.qs.QsLog.add("service", "popPanel failed: " + t.getMessage());
        }
    }

    /** "1 image", "3 files" - the same shorthand the stock prompt uses. */
    private static String fileKind(java.util.List<QsFrames.FileMeta> files) {
        if (files == null || files.isEmpty()) return "file";
        java.util.Set<String> kinds = new java.util.LinkedHashSet<>();
        for (QsFrames.FileMeta fm : files) {
            String n = fm.name == null ? "" : fm.name.toLowerCase(java.util.Locale.US);
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".webp") || n.endsWith(".gif")) kinds.add("image");
            else if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov")
                    || n.endsWith(".webm")) kinds.add("video");
            else if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav")) kinds.add("audio");
            else if (n.endsWith(".pdf") || n.endsWith(".doc") || n.endsWith(".docx")
                    || n.endsWith(".txt") || n.endsWith(".md")) kinds.add("document");
            else kinds.add("file");
        }
        if (kinds.size() != 1) return files.size() + " files";
        String one = kinds.iterator().next();
        return files.size() == 1 ? "1 " + one : files.size() + " " + one + "s";
    }

    private void recordHistory(String peer, int count, long size) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", "qs" + System.currentTimeMillis());
            o.put("dir", "receive");
            o.put("peer", peer);
            o.put("count", count);
            o.put("size", size);
            // a normal history record carries the file names; without this the
            // detail sheet shows only a count and a size
            synchronized (receivedNames) { o.put("files", new org.json.JSONArray(receivedNames.toString())); }
            o.put("status", "completed");
            o.put("done", size);
            o.put("time", System.currentTimeMillis());
            new Store(this).addHistory(o);
            com.peerzap.qs.QsLog.add("history", "recorded receipt from " + peer);
        } catch (Throwable t) {
            com.peerzap.qs.QsLog.add("history", "could not record: " + t.getMessage());
        }
    }

    /** Leaves the diagnostics on disk too, in case the UI is awkward to copy from. */
    private void writeLogFile() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "QuickShare");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "qs-diagnostics.txt");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
            out.write(com.peerzap.qs.QsLog.dump().getBytes("UTF-8"));
            out.close();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "could not write diagnostics: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------- notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                NotificationChannel c = new NotificationChannel(CH, "Quick Share compatibility",
                        NotificationManager.IMPORTANCE_DEFAULT);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }
        }
    }

    private Notification build(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setContentTitle("Quick Share")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_qs_tile)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b.build();
    }

    private void startForegroundCompat() {
        Notification n = build("Starting...");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    /** Re-posts the foreground notification as the live progress one. */
    private void updateLiveNotification() {
        try {
            String live = liveTransfer;
            if (live == null) return;
            android.app.Notification n = Notifier.buildProgress(this, new org.json.JSONObject(live));
            if (n == null) return;
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable ignored) {}
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, build(text));
    }
}
