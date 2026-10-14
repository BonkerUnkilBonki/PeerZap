package com.peerzap;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Peer-to-peer file transfer between instances of this app over the local network.
 *
 * The receiver runs a small TCP server. A sender connects, sends a JSON "offer"
 * (file names, sizes, MIME types) and waits for an accept/reject reply before any
 * file bytes move. On accept the sender streams each file after a one-line header
 * and finishes with a "done" marker; the receiver writes each file straight to
 * storage as it arrives (never holding a whole file in memory).
 *
 * The wire format is a simple newline-delimited JSON header followed by raw bytes.
 * It is intentionally plain and documented - see PROTOCOL.md - and it is used only
 * between two copies of this app. It is not Google's Quick Share protocol.
 */
public class TransferManager {

    public interface Listener {
        void onTransfersChanged(JSONArray active);
        void onHistoryChanged(JSONArray history);
        void onIncoming(JSONObject transfer);
        void onToast(String text);
    }

    private static volatile TransferManager instance;

    public static TransferManager get(Context c) {
        TransferManager m = instance;
        if (m == null) {
            synchronized (TransferManager.class) {
                m = instance;
                if (m == null) {
                    m = new TransferManager(c.getApplicationContext());
                    instance = m;
                }
            }
        }
        return m;
    }

    private final Context ctx;
    private final Store store;
    private volatile Listener listener;

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;

    private final Map<String, JSONObject> active = new LinkedHashMap<>();
    private final Map<String, Incoming> incoming = new LinkedHashMap<>();
    private final Map<String, Boolean> cancelled = new HashMap<>();
    private final List<JSONObject> pendingFiles = new ArrayList<>();

    /**
     * Set when the visible UI goes away while a transfer is still running: the
     * engine is kept alive so the transfer (and its live progress notification)
     * can finish, and then shuts itself down.
     */
    private volatile boolean shutdownWhenIdle = false;

    public synchronized void setShutdownWhenIdle(boolean b) { shutdownWhenIdle = b; }

    public synchronized boolean hasActive() { return !active.isEmpty(); }

    private static class Incoming {
        String id;
        Socket sock;
        JSONObject offer;
        JSONObject record;
        boolean decided;
        boolean accepted;
        final Object lock = new Object();
    }

    private TransferManager(Context c) {
        this.ctx = c.getApplicationContext();
        this.store = new Store(c);
    }

    public void setListener(Listener l) { this.listener = l; }

    public boolean isRunning() { return running; }

    // ---------------------------------------------------------------- server

    public synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(new Runnable() {
            public void run() { acceptLoop(); }
        }, "qs-transfer-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (server != null) { try { server.close(); } catch (Throwable ignored) {} server = null; }
    }

    private void acceptLoop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(Discovery.TRANSFER_PORT));
        } catch (Throwable t) {
            running = false;
            return;
        }
        while (running) {
            try {
                final Socket s = server.accept();
                Thread h = new Thread(new Runnable() {
                    public void run() { handleIncoming(s); }
                }, "qs-recv");
                h.setDaemon(true);
                h.start();
            } catch (Throwable t) {
                if (!running) break;
            }
        }
    }

    // ---------------------------------------------------------------- picking

    public synchronized void setPendingFiles(JSONArray files) {
        pendingFiles.clear();
        for (int i = 0; i < files.length(); i++) {
            JSONObject o = files.optJSONObject(i);
            if (o != null) pendingFiles.add(o);
        }
    }

    public synchronized JSONArray pendingFiles() {
        JSONArray a = new JSONArray();
        for (JSONObject o : pendingFiles) a.put(o);
        return a;
    }

    public synchronized void clearPending() { pendingFiles.clear(); }

    // ---------------------------------------------------------------- sending

    /** Sends the picked files to a discovered device. Returns the transfer id. */
    public synchronized String sendTo(JSONObject device) {
        if (pendingFiles.isEmpty()) return null;
        final List<JSONObject> files = new ArrayList<>(pendingFiles);
        final JSONObject dev = device;
        final String id = newId();
        pendingFiles.clear();

        JSONObject rec = newRecord(id, "send", dev.optString("name", "Device"), dev.optString("id", ""));
        try {
            JSONArray names = new JSONArray();
            long total = 0;
            for (JSONObject f : files) {
                names.put(f.optString("name", "file"));
                long sz = f.optLong("size", -1);
                if (sz > 0) total += sz;
            }
            rec.put("files", names);
            rec.put("count", files.size());
            rec.put("size", total);
            rec.put("status", "waiting");
        } catch (Exception ignored) {}
        active.put(id, rec);
        notifyTransfers();

        Thread t = new Thread(new Runnable() {
            public void run() { doSend(id, dev, files); }
        }, "qs-send");
        t.setDaemon(true);
        t.start();
        return id;
    }

    private void doSend(String id, JSONObject dev, List<JSONObject> files) {
        JSONObject rec = active.get(id);
        Socket sock = null;
        List<File> temps = new ArrayList<>();
        try {
            // resolve any unknown sizes by staging to a temp file first
            List<JSONObject> norm = new ArrayList<>();
            for (JSONObject f : files) {
                JSONObject n = new JSONObject(f.toString());
                if (n.optLong("size", -1) < 0) {
                    File tmp = stageToTemp(Uri.parse(f.optString("uri")));
                    temps.add(tmp);
                    n.put("path", tmp.getAbsolutePath());
                    n.put("size", tmp.length());
                }
                norm.add(n);
            }
            files = norm;

            String ip = dev.optString("ip");
            int port = dev.optInt("port", Discovery.TRANSFER_PORT);
            sock = new Socket();
            sock.connect(new InetSocketAddress(ip, port), 8000);
            sock.setTcpNoDelay(true);

            BufferedOutputStream out = new BufferedOutputStream(sock.getOutputStream(), 64 * 1024);
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream());

            JSONObject offer = new JSONObject();
            offer.put("t", "offer");
            offer.put("id", id);
            offer.put("name", store.deviceName(ctx));
            offer.put("model", android.os.Build.MODEL == null ? "" : android.os.Build.MODEL);
            JSONArray arr = new JSONArray();
            long total = 0;
            for (JSONObject f : files) {
                JSONObject fo = new JSONObject();
                fo.put("name", f.optString("name", "file"));
                long sz = f.optLong("size", -1);
                fo.put("size", sz);
                fo.put("mime", f.optString("mime", "application/octet-stream"));
                arr.put(fo);
                if (sz > 0) total += sz;
            }
            offer.put("files", arr);
            offer.put("count", files.size());
            offer.put("total", total);
            writeLine(out, offer);

            String replyLine = readLine(in);
            JSONObject reply = replyLine == null ? new JSONObject() : new JSONObject(replyLine);
            String rt = reply.optString("t", "");
            if ("reject".equals(rt)) {
                finish(id, "rejected", "Declined by " + dev.optString("name", "device"));
                close(sock);
                return;
            }
            if (!"accept".equals(rt)) {
                finish(id, "failed", "No response from " + dev.optString("name", "device"));
                close(sock);
                return;
            }

            rec.put("status", "active");
            rec.put("size", total);
            long start = System.currentTimeMillis();
            long done = 0;

            for (JSONObject f : files) {
                if (isCancelled(id)) throw new Cancelled();
                String name = f.optString("name", "file");
                long sz = f.optLong("size", -1);
                JSONObject hdr = new JSONObject();
                hdr.put("t", "file");
                hdr.put("name", name);
                hdr.put("size", sz);
                hdr.put("mime", f.optString("mime", "application/octet-stream"));
                writeLine(out, hdr);

                InputStream src = openSource(f);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = src.read(buf)) > 0) {
                    if (isCancelled(id)) { src.close(); throw new Cancelled(); }
                    out.write(buf, 0, n);
                    done += n;
                    progress(rec, done, total, start);
                }
                out.flush();
                try { src.close(); } catch (Throwable ignored) {}
            }
            JSONObject doneMsg = new JSONObject();
            doneMsg.put("t", "done");
            writeLine(out, doneMsg);
            out.flush();
            close(sock);

            JSONObject entry = historyEntry(rec, "completed", done, null);
            finish(id, "completed", null);
            store.addHistory(entry);
            notifyHistory();
            if (store.notifyComplete()) Notifier.completed(ctx, rec.optString("peer", "Device"),
                    rec.optInt("count", 1), false);
            toast("Sent " + rec.optInt("count", 1) + " file(s) to " + rec.optString("peer", "device"));
        } catch (Cancelled c) {
            close(sock);
            JSONObject entry = historyEntry(rec, "cancelled", rec.optLong("done", 0), "Cancelled");
            finish(id, "cancelled", "Cancelled");
            store.addHistory(entry);
            notifyHistory();
        } catch (Exception e) {
            close(sock);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            JSONObject entry = historyEntry(rec, "failed", rec.optLong("done", 0), msg);
            finish(id, "failed", msg);
            store.addHistory(entry);
            notifyHistory();
            toast("Send failed: " + msg);
        } finally {
            for (File f : temps) { try { f.delete(); } catch (Throwable ignored) {} }
        }
    }

    private InputStream openSource(JSONObject f) throws Exception {
        String p = f.optString("path", "");
        if (!p.isEmpty()) return new BufferedInputStream(new FileInputStream(p), 64 * 1024);
        return FileUtil.open(ctx, Uri.parse(f.optString("uri")));
    }

    private File stageToTemp(Uri uri) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "stage_" + UUID.randomUUID().toString().substring(0, 8));
        InputStream in = FileUtil.open(ctx, uri);
        FileOutputStream fos = new FileOutputStream(tmp);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        fos.flush();
        try { fos.close(); } catch (Throwable ignored) {}
        try { in.close(); } catch (Throwable ignored) {}
        return tmp;
    }

    // -------------------------------------------------------------- receiving

    private void handleIncoming(Socket s) {
        String id = null;
        try {
            s.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream());

            String line = readLine(in);
            if (line == null) { close(s); return; }
            JSONObject offer = new JSONObject(line);
            if (!"offer".equals(offer.optString("t", ""))) { close(s); return; }

            id = offer.optString("id", newId());
            JSONArray files = offer.optJSONArray("files");
            if (files == null) files = new JSONArray();

            Incoming inc = new Incoming();
            inc.id = id;
            inc.sock = s;
            inc.offer = offer;

            JSONObject rec = newRecord(id, "recv", offer.optString("name", "Device"), "");
            JSONArray names = new JSONArray();
            long total = offer.optLong("total", 0);
            for (int i = 0; i < files.length(); i++) {
                JSONObject fo = files.optJSONObject(i);
                if (fo != null) names.put(fo.optString("name", "file"));
            }
            rec.put("files", names);
            rec.put("count", files.length());
            rec.put("size", total);
            rec.put("status", "waiting");
            rec.put("ip", s.getInetAddress() == null ? "" : s.getInetAddress().getHostAddress());
            inc.record = rec;

            synchronized (this) { incoming.put(id, inc); active.put(id, rec); }
            notifyTransfers();
            Listener l = listener;
            if (l != null) l.onIncoming(rec);
            if (store.notifyIncoming()) Notifier.incoming(ctx, id, offer.optString("name", "Device"), files.length());

            // wait for the user's decision (auto-accept skips the wait)
            if (!store.autoAccept()) {
                synchronized (inc.lock) {
                    long deadline = System.currentTimeMillis() + 60000;
                    while (!inc.decided) {
                        long wait = deadline - System.currentTimeMillis();
                        if (wait <= 0) break;
                        try { inc.lock.wait(wait); } catch (InterruptedException ignored) {}
                    }
                }
            } else {
                synchronized (inc.lock) { inc.decided = true; inc.accepted = true; }
            }

            synchronized (this) { incoming.remove(id); }

            if (!inc.decided || !inc.accepted) {
                JSONObject rej = new JSONObject();
                rej.put("t", "reject");
                try { writeLine(out, rej); } catch (Throwable ignored) {}
                close(s);
                JSONObject entry = historyEntry(rec, "rejected", 0, "Declined");
                finish(id, "rejected", "Declined");
                store.addHistory(entry);
                notifyHistory();
                return;
            }

            JSONObject acc = new JSONObject();
            acc.put("t", "accept");
            writeLine(out, acc);

            rec.put("status", "active");
            long start = System.currentTimeMillis();
            long done = 0;
            JSONArray saved = new JSONArray();

            for (int i = 0; i < files.length(); i++) {
                JSONObject fo = files.optJSONObject(i);
                if (fo == null) continue;
                String hdrLine = readLine(in);
                if (hdrLine == null) throw new IOException("connection closed mid-transfer");
                JSONObject hdr = new JSONObject(hdrLine);
                String fname = hdr.optString("name", "file");
                long fsize = hdr.optLong("size", -1);
                String fmime = hdr.optString("mime", FileUtil.mimeFromName(fname));
                if (fsize < 0) throw new IOException("missing size for " + fname);

                if (isCancelled(id)) throw new Cancelled();

                final JSONObject recRef = rec;
                final long[] counter = new long[]{done};
                final long st = start;
                final long tot = total;
                JSONObject result = FileUtil.saveReceived(ctx, fname, fmime, fsize,
                        new Limited(in, fsize),
                        new FileUtil.Progress() {
                            public void onBytes(long written) {
                                progress(recRef, counter[0] + written, tot, st);
                            }
                        });
                done += fsize;
                saved.put(result.optString("uri", result.optString("path", "")));
                progress(rec, done, total, start);
            }

            try { readLine(in); } catch (Throwable ignored) {}
            close(s);

            rec.put("saved", saved);
            JSONObject entry = historyEntry(rec, "completed", done, null);
            entry.put("saved", saved);
            finish(id, "completed", null);
            store.addHistory(entry);
            notifyHistory();
            if (store.notifyComplete()) Notifier.completed(ctx, offer.optString("name", "Device"),
                    files.length(), true);
            toast("Received " + files.length() + " file(s) from " + offer.optString("name", "device"));
        } catch (Cancelled c) {
            close(s);
            JSONObject rec = active.get(id);
            JSONObject entry = historyEntry(rec, "cancelled", rec == null ? 0 : rec.optLong("done", 0), "Cancelled");
            finish(id, "cancelled", "Cancelled");
            store.addHistory(entry);
            notifyHistory();
        } catch (Exception e) {
            close(s);
            JSONObject rec = active.get(id);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            JSONObject entry = historyEntry(rec, "failed", rec == null ? 0 : rec.optLong("done", 0), msg);
            finish(id, "failed", msg);
            store.addHistory(entry);
            notifyHistory();
            toast("Receive failed: " + msg);
        }
    }

    // ------------------------------------------------------------- decisions

    public void accept(String id) {
        Incoming inc;
        synchronized (this) { inc = incoming.get(id); }
        if (inc == null) return;
        synchronized (inc.lock) { inc.decided = true; inc.accepted = true; inc.lock.notifyAll(); }
    }

    public void reject(String id) {
        Incoming inc;
        synchronized (this) { inc = incoming.get(id); }
        if (inc == null) return;
        synchronized (inc.lock) { inc.decided = true; inc.accepted = false; inc.lock.notifyAll(); }
    }

    public void cancel(String id) {
        synchronized (this) { cancelled.put(id, Boolean.TRUE); }
        Incoming inc;
        synchronized (this) { inc = incoming.get(id); }
        if (inc != null) {
            synchronized (inc.lock) { inc.decided = true; inc.accepted = false; inc.lock.notifyAll(); }
        }
        Socket s = inc != null ? inc.sock : null;
        close(s);
    }

    private boolean isCancelled(String id) {
        synchronized (this) { return Boolean.TRUE.equals(cancelled.get(id)); }
    }

    // ---------------------------------------------------------------- records

    public synchronized JSONArray snapshotActive() {
        JSONArray a = new JSONArray();
        for (JSONObject o : active.values()) a.put(o);
        return a;
    }

    public synchronized JSONArray snapshotIncoming() {
        JSONArray a = new JSONArray();
        for (Incoming inc : incoming.values()) a.put(inc.record);
        return a;
    }

    private JSONObject newRecord(String id, String dir, String peer, String peerId) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("dir", dir);
            o.put("peer", peer);
            o.put("peerId", peerId);
            o.put("status", "waiting");
            o.put("done", 0);
            o.put("size", 0);
            o.put("count", 0);
            o.put("speed", 0);
            o.put("eta", -1);
            o.put("time", System.currentTimeMillis());
        } catch (Exception ignored) {}
        return o;
    }

    private JSONObject historyEntry(JSONObject rec, String status, long done, String error) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", rec == null ? newId() : rec.optString("id"));
            o.put("dir", rec == null ? "send" : rec.optString("dir", "send"));
            o.put("peer", rec == null ? "Device" : rec.optString("peer", "Device"));
            o.put("files", rec == null ? new JSONArray() : rec.optJSONArray("files"));
            o.put("count", rec == null ? 0 : rec.optInt("count", 0));
            o.put("size", rec == null ? 0 : rec.optLong("size", 0));
            o.put("status", status);
            o.put("done", done);
            o.put("time", System.currentTimeMillis());
            if (error != null) o.put("error", error);
            if (rec != null && rec.optJSONArray("saved") != null) o.put("saved", rec.optJSONArray("saved"));
        } catch (Exception ignored) {}
        return o;
    }

    private void finish(String id, String status, String error) {
        JSONObject rec;
        synchronized (this) {
            rec = active.remove(id);
            cancelled.remove(id);
        }
        if (rec != null) {
            try {
                rec.put("status", status);
                if (error != null) rec.put("error", error);
            } catch (Exception ignored) {}
        }
        try { Notifier.cancelProgress(ctx, id); } catch (Throwable ignored) {}
        notifyTransfers();
        shutdownIfIdle();
    }

    /**
     * If the UI is gone and nothing is transferring any more, close the whole
     * background down: discovery, the receive server and the foreground service.
     */
    private void shutdownIfIdle() {
        if (!shutdownWhenIdle) return;
        synchronized (this) {
            if (!active.isEmpty()) return;
            shutdownWhenIdle = false;
        }
        try { Discovery.get(ctx).stop(); } catch (Throwable ignored) {}
        try { ctx.stopService(new Intent(ctx, TransferService.class)); } catch (Throwable ignored) {}
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Notifier.ID_SERVICE);
        } catch (Throwable ignored) {}
        stop();
        toast("Transfer finished - Quick Share stopped");
    }

    private void progress(JSONObject rec, long done, long total, long start) {
        if (rec == null) return;
        long now = System.currentTimeMillis();
        long last = rec.optLong("_np", 0);
        try { rec.put("done", done); } catch (Exception ignored) {}
        if (now - last < 180 && done < total) return;
        try { rec.put("_np", now); } catch (Exception ignored) {}
        long elapsed = Math.max(1, now - start);
        double speed = done * 1000.0 / elapsed;
        try {
            rec.put("speed", speed);
            if (speed > 1 && total > done) rec.put("eta", (long) ((total - done) / speed));
            else rec.put("eta", -1);
        } catch (Exception ignored) {}
        // live progress notification (a Live Update chip on Android 16)
        if ("active".equals(rec.optString("status", ""))) {
            try { Notifier.progress(ctx, rec); } catch (Throwable ignored) {}
        }
        notifyTransfers();
    }

    // ---------------------------------------------------------------- helpers

    private static String newId() {
        return "t" + System.currentTimeMillis() + Integer.toHexString((int) (Math.random() * 0xffff));
    }

    private void notifyTransfers() {
        Listener l = listener;
        if (l != null) l.onTransfersChanged(snapshotActive());
    }

    private void notifyHistory() {
        Listener l = listener;
        if (l != null) l.onHistoryChanged(store.history());
    }

    private void toast(String t) {
        Listener l = listener;
        if (l != null) l.onToast(t);
    }

    private static void close(Socket s) {
        if (s != null) try { s.close(); } catch (Throwable ignored) {}
    }

    private static void writeLine(OutputStream out, JSONObject o) throws IOException {
        out.write(o.toString().getBytes("UTF-8"));
        out.write('\n');
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 4 * 1024 * 1024) throw new IOException("header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), "UTF-8");
    }

    /** Marks the "transfer cancelled" condition distinctly from I/O errors. */
    private static class Cancelled extends IOException {
        Cancelled() { super("cancelled"); }
    }

    /**
     * Reads at most {@code n} bytes from the shared stream, then reports EOF.
     * close() is a no-op so the shared connection stays open for the next header.
     */
    private static class Limited extends FilterInputStream {
        private long remaining;
        Limited(InputStream in, long n) { super(in); remaining = n; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int c = super.read();
            if (c >= 0) remaining--;
            return c;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int r = super.read(b, off, (int) Math.min(len, remaining));
            if (r > 0) remaining -= r;
            return r;
        }
        @Override public void close() { /* keep the shared stream open */ }
    }
}
