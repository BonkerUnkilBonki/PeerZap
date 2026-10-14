package com.peerzap.qs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Quick Share receiver: binds a TCP port, completes the UKEY2 handshake with
 * an incoming stock-Quick-Share sender, then streams the offered files to disk.
 *
 * The transport and crypto layers are covered by JVM tests; the sharing FSM
 * below is written from the protocol spec and needs a real peer to confirm.
 */
public final class QsReceiver {

    public interface Listener {
        /** Called on a worker thread; return true to accept the transfer. */
        boolean onConsent(String peerName, List<QsFrames.FileMeta> files, String pin);
        void onProgress(long done, long total, String currentFile);
        void onFileSaved(File file);
        void onFinished(String summary);
        void onError(String message);
    }

    private final Listener listener;
    private final File outDir;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String localName = "Android";

    public QsReceiver(Listener listener, File outDir) {
        this.listener = listener;
        this.outDir = outDir;
    }

    public void setLocalName(String name) { this.localName = name; }

    /** The peer's address on this connection, for name caching. */
    public String peerAddress() { return peerAddr; }

    private volatile String peerAddr = null;

    /** Binds an ephemeral port and starts accepting. Returns the port. */
    public int start() throws IOException {
        server = new ServerSocket(0);
        QsLog.add("listen", "bound TCP server on 0.0.0.0:" + server.getLocalPort());
        running.set(true);
        pool.execute(new Runnable() {
            @Override public void run() { acceptLoop(); }
        });
        return server.getLocalPort();
    }

    public int port() { return server == null ? -1 : server.getLocalPort(); }

    private final java.util.concurrent.atomic.AtomicLong payloadIds =
            new java.util.concurrent.atomic.AtomicLong(1000);
    private long nextPayloadId() { return payloadIds.incrementAndGet(); }

    private volatile boolean keepAliveRunning;
    private Thread keepAliveThread;

    /** Android sends a keep-alive every 10s and drops the peer if we do not. */
    private void startKeepAlive(final SecureChannel sec) {
        keepAliveRunning = true;
        keepAliveThread = new Thread(new Runnable() {
            @Override public void run() {
                long seq = 0;
                while (keepAliveRunning) {
                    try {
                        Thread.sleep(10000);
                        if (!keepAliveRunning) break;
                        sec.send(QsFrames.keepAlive(false, seq++));
                        QsLog.add("send", "keep-alive seq=" + (seq - 1));
                    } catch (InterruptedException e) { return; }
                    catch (Exception e) { return; }
                }
            }
        });
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    private void stopKeepAlive() {
        keepAliveRunning = false;
        if (keepAliveThread != null) keepAliveThread.interrupt();
    }

    public void stop() {
        running.set(false);
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private void acceptLoop() {
        QsLog.add("listen", "accept loop started");
        while (running.get()) {
            try {
                Socket s = server.accept();
                QsLog.add("listen", "incoming from " + s.getRemoteSocketAddress());
                final Socket sock = s;
                pool.execute(new Runnable() {
                    @Override public void run() { session(sock); }
                });
            } catch (IOException e) {
                if (!running.get() || server == null || server.isClosed()) return;
                // a transient accept failure must not kill the listener
                QsLog.add("listen", "accept failed: " + e.getMessage() + " (continuing)");
            }
        }
    }

    // ------------------------------------------------------------------ session

    private void session(Socket socket) {
        try {
            java.net.InetAddress ra = socket.getInetAddress();
            if (ra != null) peerAddr = ra.getHostAddress();
        } catch (Throwable ignored) {}
        String peer = "Unknown device";
        QsLog.add("session", "accepted TCP connection from " + socket.getRemoteSocketAddress());
        try {
            socket.setTcpNoDelay(true);
            SecureChannel ch = new SecureChannel(socket.getInputStream(), socket.getOutputStream(), null);

            // 1. plaintext CONNECTION_REQUEST
            byte[] req = ch.readFramed();
            QsLog.add("recv", "connection request " + (req == null ? "-> end of stream"
                    : req.length + " bytes  " + QsLog.hex(req, 24)));
            if (req != null) {
                byte[] v1 = QsFrames.subMessage(req, 2);
                byte[] cr = v1 == null ? null : QsFrames.subMessage(v1, 2);
                if (cr != null) {
                    // The readable device name lives in endpoint_info (field 6), not
                    // in endpoint_name (field 2) - reading field 2 gives binary junk.
                    // endpoint_info is: 1 byte flags | 16 bytes identity |
                    // 1 byte name length | UTF-8 name.
                    String name = null;
                    byte[] info = QsFrames.subMessage(cr, 6);
                    if (info != null && info.length >= 18) {
                        int len = info[17] & 0xFF;
                        if (info.length >= 18 + len) {
                            name = new String(info, 18, len, java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                    if (name == null || name.trim().isEmpty()) {
                        String alt = QsFrames.stringField(cr, 2);
                        if (alt != null && isPrintable(alt)) name = alt;
                    }
                    if (name != null && !name.trim().isEmpty()) peer = name.trim();
                    QsLog.add("peer", "name=\"" + peer + "\"");
                }
            }

            // 2. UKEY2
            Ukey2.Server ukey = new Ukey2.Server();
            byte[] clientInit = ch.readFramed();
            QsLog.add("recv", "ukey2 client init " + (clientInit == null ? "-> end of stream"
                    : clientInit.length + " bytes"));
            if (clientInit == null) throw new IOException("no ClientInit");
            byte[] serverInit = ukey.handleClientInit(clientInit);
            ch.writeFramed(serverInit);
            QsLog.add("send", "ukey2 server init " + serverInit.length + " bytes");
            // Do not assume the frame right after ServerInit is the ClientFinished.
            // Stock senders do not always send the handshake frames in the order
            // the spec describes, so classify each frame by its UKEY2 message type
            // and skip anything that is not a ClientFinish.
            byte[] clientFinish = null;
            for (int i = 0; i < 6 && clientFinish == null; i++) {
                byte[] f = ch.readFramed();
                if (f == null) break;
                int t = Ukey2.msgType(f);
                QsLog.add("recv", "handshake frame " + f.length + " bytes, ukey2 type=" + t
                        + (t == Ukey2.MSG_ALERT ? " (ALERT)" : "")
                        + "  " + QsLog.hex(f, 40));
                if (t == Ukey2.MSG_CLIENT_FINISH) {
                    clientFinish = f;
                } else if (t == Ukey2.MSG_ALERT) {
                    QsLog.add("ukey2", Ukey2.describeAlert(f));
                    break;
                } else {
                    QsLog.add("ukey2", "skipping a non-ClientFinished handshake frame");
                }
            }
            if (clientFinish == null) throw new IOException("no UKEY2 ClientFinished arrived");
            QsLog.add("recv", "ukey2 client finish " + clientFinish.length + " bytes");
            ukey.handleClientFinish(clientFinish);
            QsLog.add("handshake", "key exchange complete");

            D2DKeys keys = D2DKeys.from(ukey.result, true);
            String pin = Ukey2.pin(ukey.result.authString);
            QsLog.add("handshake", "confirmation PIN " + pin);

            // 3. a plaintext connection response goes each way
            ch.writeFramed(QsFrames.connectionResponse(1));
            QsLog.add("send", "connection response (accept)");
            try {
                socket.setSoTimeout(6000);
                byte[] theirResponse = ch.readFramed();
                QsLog.add("recv", "their connection response "
                        + (theirResponse == null ? "-> end of stream" : theirResponse.length + " bytes"));
            } catch (java.net.SocketTimeoutException ignored) {
                QsLog.add("recv", "no connection response within 6s, carrying on");
            } finally {
                socket.setSoTimeout(0);
            }

            // 4. everything from here is encrypted
            SecureChannel sec = new SecureChannel(socket.getInputStream(), socket.getOutputStream(), keys);
            startKeepAlive(sec);
            QsLog.add("encrypted", "switched to the encrypted layer");
            // Bada's receiver runs the bandwidth-upgrade exchange BEFORE the
            // sharing negotiation: a stock sender solicits an upgrade as soon as
            // the encrypted channel is up, and answering the paired-key frame
            // first makes it hang up. Give the peer a moment to make its offer,
            // decline it (we only run one Wi-Fi LAN socket), then negotiate.
            // Bada's order, verbatim: after the handshake the receiver sends its
            // paired-key frame IMMEDIATELY. runServerUpgradeIfAvailable returns
            // without waiting when no upgrade medium is available, and only then
            // does startNegotiation() emit the frame. Waiting for the peer's frame
            // first (a previous round's invention) made the sender hang up sooner.
            // The upgrade exchange, when the sender does solicit one, is handled
            // inside the receive loop below.
            sendBytes(sec, nextPayloadId(), QsFrames.pairedKeyEncryption());
            receiveLoop(sec, peer, pin, null, false);
            stopKeepAlive();

        } catch (Exception e) {
            QsLog.add("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            listener.onError("Quick Share session ended: " + e.getMessage());
        } finally {
            QsLog.add("session", "connection closed");
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void receiveLoop(SecureChannel sec, String peer, String pin, byte[] pending,
                             boolean peerPkeAlreadyAnswered) throws Exception {
        final long loopStart = System.currentTimeMillis();
        Map<Long, ByteArrayOutputStream> byteBuffers = new HashMap<>();
        Map<Long, FileOutputStream> fileStreams = new HashMap<>();
        Map<Long, File> fileTargets = new HashMap<>();
        Map<Long, Long> fileWritten = new HashMap<>();
        long totalBytes = 0, doneBytes = 0;
        int fileCount = 0;
        boolean consented = false;
        List<QsFrames.FileMeta> offered = new ArrayList<>();
        java.util.Set<Long> expectedPayloads = new java.util.HashSet<>();
        java.util.Set<Long> completedPayloads = new java.util.HashSet<>();

        while (true) {
            byte[] frame;
            if (pending != null) { frame = pending; pending = null; }
            else {
                try { frame = sec.receive(); }
                catch (Exception ex) {
                    QsLog.add("recv", "read failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    throw ex;
                }
            }
            if (frame == null) { QsLog.add("recv", "peer closed the connection (EOF)"); break; }

            byte[] v1 = QsFrames.subMessage(frame, 2);
            if (v1 == null) {
                QsLog.add("recv", "encrypted frame with no v1 body: " + QsLog.hex(frame, 24));
                continue;
            }
            long type = QsFrames.varintField(v1, 1, -1);

            if (type == QsFrames.T_DISCONNECTION) {
                QsFrames.Disconnection d = QsFrames.parseDisconnection(frame);
                QsLog.add("recv", "disconnection requestSafe=" + (d != null && d.requestSafe)
                        + " ackSafe=" + (d != null && d.ackSafe));
                if (d != null && d.requestSafe) {
                    try { sec.send(QsFrames.disconnection(false, true)); } catch (Exception ignored) {}
                }
                break;
            }
            if (QsFrames.isKeepAlive(frame)) { QsLog.add("recv", "keep-alive"); continue; }

            if (QsFrames.isBandwidthUpgrade(frame)) {
                int ev = QsFrames.upgradeEvent(frame);
                QsLog.add("recv", "bandwidth upgrade frame, event=" + ev
                        + " (7 = PATH_REQUEST, 5 = FAILURE)");
                if (ev != QsFrames.UPGRADE_FAILURE) {
                    // We only run a single Wi-Fi LAN socket, so decline politely
                    // rather than leaving the sender waiting.
                    try {
                        sec.send(QsFrames.upgradeFailure(5));   // WIFI_LAN
                        QsLog.add("send", "bandwidth upgrade failure (staying on Wi-Fi LAN)");
                    } catch (Exception ignored) {}
                }
                continue;
            }

            if (type != QsFrames.T_PAYLOAD_TRANSFER) {
                QsLog.add("recv", "offline frame type " + type + " (ignored)");
                continue;
            }

            QsFrames.Payload p = QsFrames.parsePayload(frame);
            if (p == null) {
                QsLog.add("recv", "payload frame did not parse: " + QsLog.hex(frame, 32));
                continue;
            }
            QsLog.add("recv", "payload id=" + p.id + " packet=" + p.packetType + " type=" + p.type
                    + " off=" + p.offset + " len=" + (p.body == null ? 0 : p.body.length)
                    + " flags=" + p.flags);

            if (p.type == QsFrames.PAYLOAD_BYTES) {
                ByteArrayOutputStream buf = byteBuffers.get(p.id);
                if (buf == null) { buf = new ByteArrayOutputStream(); byteBuffers.put(p.id, buf); }
                if (p.body != null) buf.write(p.body);
                if (p.isLast()) {
                    byte[] message = buf.toByteArray();
                    byteBuffers.remove(p.id);
                    int st = QsFrames.sharingType(message);
                    QsLog.add("recv", "bytes payload id=" + p.id + " sharing type " + st
                            + " (" + message.length + " bytes)");
                    if (st == QsFrames.S_INTRODUCTION) {
                        List<QsFrames.FileMeta> files = QsFrames.parseIntroduction(message);
                        offered = files == null ? new ArrayList<>() : files;
                        for (QsFrames.FileMeta m : offered) {
                            if (m.size > 0) totalBytes += m.size;
                            if (m.payloadId != 0) expectedPayloads.add(m.payloadId);
                        }
                        QsLog.add("intro", "offered " + offered.size() + " file(s) from " + peer);
                        consented = listener.onConsent(peer, offered, pin);
                        sendBytes(sec, nextPayloadId(),
                                QsFrames.sharingResponse(consented ? 1 : 2, offered));
                        if (!consented) break;
                    } else if (st == QsFrames.S_PAIRED_KEY_ENCRYPTION) {
                        if (peerPkeAlreadyAnswered) {
                            QsLog.add("recv", "peer's paired-key frame (already answered)");
                        } else {
                            sendBytes(sec, nextPayloadId(), QsFrames.pairedKeyResult(3));
                        }
                    } else if (st == QsFrames.S_PAIRED_KEY_RESULT) {
                        // informational; the sender proceeds regardless
                    } else if (st == QsFrames.S_RESPONSE) {
                        // Samsung sends a preemptive RESPONSE after the introduction;
                        // ACCEPT/UNKNOWN just means it is declaring readiness
                    }
                }
                continue;
            }

            if (p.type == QsFrames.PAYLOAD_FILE) {
                FileOutputStream fos = fileStreams.get(p.id);
                if (fos == null) {
                    QsFrames.FileMeta meta = null;
                    for (QsFrames.FileMeta m : offered) if (m.payloadId == p.id) meta = m;
                    String name = meta != null && meta.name != null ? meta.name
                            : (p.fileName != null ? p.fileName : ("received-" + p.id + ".bin"));
                    File target = uniqueTarget(safeName(name));
                    fos = new FileOutputStream(target);
                    fileStreams.put(p.id, fos);
                    fileTargets.put(p.id, target);
                    fileWritten.put(p.id, 0L);
                    fileCount++;
                }
                if (p.body != null) {
                    fos.write(p.body);
                    doneBytes += p.body.length;
                    fileWritten.put(p.id, fileWritten.get(p.id) + p.body.length);
                }
                listener.onProgress(doneBytes, totalBytes, fileTargets.get(p.id).getName());
                if (p.isLast()) {
                    fos.close();
                    fileStreams.remove(p.id);
                    File done = fileTargets.get(p.id);
                    long announced = 0;
                    for (QsFrames.FileMeta m : offered) if (m.payloadId == p.id) announced = m.size;
                    QsLog.add("file", "saved " + done.getName() + " (" + done.length() + " bytes)"
                            + (announced > 0 && announced != done.length()
                                    ? " - ANNOUNCED " + announced + ", SHORT BY "
                                      + (announced - done.length()) : ""));
                    listener.onFileSaved(done);
                    completedPayloads.add(p.id);
                    if (!expectedPayloads.isEmpty()
                            && completedPayloads.containsAll(expectedPayloads)) {
                        // Every announced file has arrived. Bada's receiver sends a
                        // Disconnection and closes here; without it both sides wait
                        // each other out for the full keep-alive timeout.
                        QsLog.add("session", "all announced payloads received - disconnecting");
                        try {
                            sec.send(QsFrames.disconnection(true, false));
                            QsLog.add("send", "disconnection (all files received)");
                        } catch (Exception ignored) {}
                        break;
                    }
                }
            }
        }

        QsLog.add("session", "receive loop ended after " + (System.currentTimeMillis() - loopStart)
                + "ms in the encrypted phase");
        stopKeepAlive();
        for (FileOutputStream f : fileStreams.values()) try { f.close(); } catch (IOException ignored) {}
        // we advertised safe_to_disconnect_version = 1, so One UI enforces the
        // handshake: ask it to drain before we close
        try {
            sec.send(QsFrames.disconnection(true, false));
            QsLog.add("send", "disconnection (requesting safe disconnect)");
        } catch (Exception ignored) {}
        if (consented && fileCount > 0) {
            listener.onFinished("Received " + fileCount + " file(s) from " + peer);
        }
    }

    private void sendBytes(SecureChannel sec, long id, byte[] message) throws Exception {
        QsLog.add("send", "bytes payload id=" + id + " sharing type "
                + QsFrames.sharingType(message) + " (" + message.length + " bytes)");
        sec.send(QsFrames.payloadFrame(id, QsFrames.PAYLOAD_BYTES, message.length, null, 0, 0, message));
        // Android sends the whole message then an empty LAST_CHUNK frame
        sec.send(QsFrames.payloadFrame(id, QsFrames.PAYLOAD_BYTES, message.length, null,
                message.length, QsFrames.FLAG_LAST_CHUNK, new byte[0]));
    }

    /** True if a string looks like a real device name rather than binary junk. */
    private static boolean isPrintable(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t') return false;
        }
        return true;
    }

    private String safeName(String name) {
        String base = new File(name).getName();
        StringBuilder sb = new StringBuilder();
        for (char c : base.toCharArray()) sb.append(c == '/' || c == '\\' || c == 0 ? '_' : c);
        if (sb.length() == 0) sb.append("received.bin");
        return sb.toString();
    }

    private File uniqueTarget(String name) {
        File f = new File(outDir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            f = new File(outDir, stem + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return f;
    }
}
