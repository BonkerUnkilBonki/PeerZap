package com.peerzap.qs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends files to a stock Google Quick Share receiver over Wi-Fi LAN.
 *
 * The mirror of {@link QsReceiver}: connects as the UKEY2 client, completes the
 * handshake and paired-key exchange, offers the files with an IntroductionFrame,
 * waits for the peer's ACCEPT, then streams each file as FILE payload chunks with
 * the split LAST_CHUNK terminator Android expects.
 */
public final class QsSender {

    public interface Listener {
        void onProgress(long done, long total, String currentFile);
        void onDone(String summary);
        void onError(String message);
    }

    /** One file to offer. */
    public String peerName() { return peerName; }

    public static final class Entry {
        public final File file;
        public final String name;
        public final String mimeType;
        public Entry(File file, String name, String mimeType) {
            this.file = file; this.name = name; this.mimeType = mimeType;
        }
        public long size() { return file.length(); }
    }

    private static final int CHUNK = 512 * 1024;

    private final String host;
    private final int port;
    private final String localName;
    private final String localEndpointId;
    private final byte[] localEndpointInfo;
    private final List<Entry> files;
    /** The peer's real name, when its response carried one. */
    private volatile String peerName = null;
    private final Listener listener;

    public QsSender(String host, int port, String localName, String localEndpointId,
                    byte[] localEndpointInfo, List<Entry> files, Listener listener) {
        this.host = host; this.port = port; this.localName = localName;
        this.localEndpointId = localEndpointId; this.localEndpointInfo = localEndpointInfo;
        this.files = files; this.listener = listener;
    }

    /** Runs the whole send on a worker thread. */
    public void start() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { send(); }
                catch (Exception e) {
                    QsLog.add("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    listener.onError("Send failed: " + e.getMessage());
                }
            }
        }, "qs-send");
        t.setDaemon(true);
        t.start();
    }

    private void send() throws Exception {
        Socket socket = new Socket();
        QsLog.add("send", "connecting to " + host + ":" + port);
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15000);

        SecureChannel ch = new SecureChannel(socket.getInputStream(), socket.getOutputStream(), null);

        // 1. connection request
        ch.writeFramed(QsFrames.connectionRequest(localEndpointId, localName, localEndpointInfo));
        QsLog.add("send", "connection request sent");

        // 2. UKEY2 as the client
        Ukey2.Client ukey = new Ukey2.Client();
        ch.writeFramed(ukey.clientInit());
        byte[] serverInit = ch.readFramed();
        if (serverInit == null) throw new IOException("no ServerInit");
        QsLog.add("recv", "ukey2 server init " + serverInit.length + " bytes, type="
                + Ukey2.msgType(serverInit));
        ch.writeFramed(ukey.handleServerInit(serverInit));
        QsLog.add("handshake", "key exchange complete, PIN " + Ukey2.pin(ukey.result.authString));

        // 3. connection response: send first, then read (Bada's note - reading first
        //    deadlocks both peers until one times out)
        ch.writeFramed(QsFrames.connectionResponse(1));
        byte[] theirResponse = ch.readFramed();
        QsLog.add("recv", "their connection response "
                + (theirResponse == null ? "none" : theirResponse.length + " bytes"));
        // their endpoint_info may be in the response - if it is, we learn the real
        // name without waiting for a transfer in the other direction
        try {
            byte[] v1 = theirResponse == null ? null : QsFrames.subMessage(theirResponse, 1);
            byte[] info = v1 == null ? null : QsFrames.subMessage(v1, 6);
            if (info != null && info.length >= 18) {
                int len = info[17] & 0xFF;
                if (len > 0 && info.length >= 18 + len) {
                    String n = new String(info, 18, len, java.nio.charset.StandardCharsets.UTF_8).trim();
                    boolean printable = !n.isEmpty();
                    for (int i = 0; printable && i < n.length(); i++) {
                        char c = n.charAt(i);
                        if (c < 0x20 || c == 0x7F) printable = false;
                    }
                    if (printable) {
                        peerName = n;
                        QsLog.add("peer", "name from their response=\"" + n + "\"");
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 4. the encrypted layer. The client encrypts with the CLIENT key.
        SecureChannel sec = new SecureChannel(socket.getInputStream(), socket.getOutputStream(),
                D2DKeys.from(ukey.result, false));
        QsLog.add("encrypted", "switched to the encrypted layer");

        // 5. paired-key exchange
        sendBytes(sec, nextId(), QsFrames.pairedKeyEncryption());
        receiveUntil(sec, QsFrames.S_PAIRED_KEY_ENCRYPTION, "paired key encryption");
        sendBytes(sec, nextId(), QsFrames.pairedKeyResult(3));
        receiveUntil(sec, QsFrames.S_PAIRED_KEY_RESULT, "paired key result");

        // 6. offer the files
        List<QsFrames.FileMeta> metas = new ArrayList<>();
        long total = 0;
        for (Entry e : files) {
            QsFrames.FileMeta m = new QsFrames.FileMeta();
            m.name = e.name;
            m.size = e.size();
            m.mimeType = e.mimeType;
            m.payloadId = nextId();
            metas.add(m);
            total += m.size;
        }
        sendBytes(sec, nextId(), QsFrames.introduction(metas));
        QsLog.add("send", "introduction sent, " + metas.size() + " file(s)");

        // 7. wait for the peer to accept
        int status = -1;
        for (int i = 0; i < 40 && status < 0; i++) {
            byte[] frame = sec.receive();
            if (frame == null) throw new IOException("peer closed before accepting");
            if (handleNonPayload(sec, frame)) continue;
            QsFrames.Payload p = QsFrames.parsePayload(frame);
            if (p == null) continue;
            // the response arrives as a BYTES payload; buffer its two frames
            if (p.type == QsFrames.PAYLOAD_BYTES) {
                byte[] msg = collect(sec, frame, p);
                if (msg != null) status = QsFrames.sharingResponseStatus(msg);
            }
        }
        if (status != 1) throw new IOException("peer did not accept (status " + status + ")");
        QsLog.add("recv", "peer accepted the transfer");

        // 8. stream the files
        long done = 0;
        byte[] buf = new byte[CHUNK];
        for (int i = 0; i < metas.size(); i++) {
            QsFrames.FileMeta m = metas.get(i);
            File f = files.get(i).file;
            FileInputStream in = new FileInputStream(f);
            long off = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] body = new byte[n];
                System.arraycopy(buf, 0, body, 0, n);
                sec.send(QsFrames.payloadFrame(m.payloadId, QsFrames.PAYLOAD_FILE, m.size,
                        m.name, off, 0, body));
                off += n;
                done += n;
                listener.onProgress(done, total, m.name);
            }
            in.close();
            // the split terminator: an empty LAST_CHUNK at offset == size
            sec.send(QsFrames.payloadFrame(m.payloadId, QsFrames.PAYLOAD_FILE, m.size,
                    m.name, off, QsFrames.FLAG_LAST_CHUNK, new byte[0]));
            QsLog.add("send", "sent " + m.name + " (" + off + " bytes)");
        }

        // 9. teardown
        try { sec.send(QsFrames.disconnection(true, false)); } catch (Exception ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
        listener.onDone("Sent " + metas.size() + " file(s) to " + host);
    }

    // ---------------------------------------------------------------- helpers

    private long nextId() { return 1000 + (idCounter++); }
    private int idCounter = 0;

    private void sendBytes(SecureChannel sec, long id, byte[] message) throws Exception {
        sec.send(QsFrames.payloadFrame(id, QsFrames.PAYLOAD_BYTES, message.length, null, 0, 0, message));
        sec.send(QsFrames.payloadFrame(id, QsFrames.PAYLOAD_BYTES, message.length, null,
                message.length, QsFrames.FLAG_LAST_CHUNK, new byte[0]));
    }

    /** Waits for a BYTES payload carrying the given sharing frame type. */
    private void receiveUntil(SecureChannel sec, int sharingType, String what) throws Exception {
        for (int i = 0; i < 40; i++) {
            byte[] frame = sec.receive();
            if (frame == null) throw new IOException("peer closed while waiting for " + what);
            if (handleNonPayload(sec, frame)) continue;
            QsFrames.Payload p = QsFrames.parsePayload(frame);
            if (p == null || p.type != QsFrames.PAYLOAD_BYTES) continue;
            byte[] msg = collect(sec, frame, p);
            if (msg != null && QsFrames.sharingType(msg) == sharingType) {
                QsLog.add("recv", what + " received");
                return;
            }
        }
        throw new IOException("never received " + what);
    }

    /** Assembles a BYTES payload from its data frame plus the LAST_CHUNK terminator. */
    private byte[] collect(SecureChannel sec, byte[] firstFrame, QsFrames.Payload first)
            throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        if (first.body != null) buf.write(first.body);
        if (first.isLast()) return buf.toByteArray();
        for (int i = 0; i < 20; i++) {
            byte[] frame = sec.receive();
            if (frame == null) return null;
            if (handleNonPayload(sec, frame)) continue;
            QsFrames.Payload p = QsFrames.parsePayload(frame);
            if (p == null || p.id != first.id) continue;
            if (p.body != null) buf.write(p.body);
            if (p.isLast()) return buf.toByteArray();
        }
        return null;
    }

    /** Answers keep-alives and declines bandwidth upgrades. Returns true if handled. */
    private boolean handleNonPayload(SecureChannel sec, byte[] frame) throws Exception {
        if (QsFrames.isKeepAlive(frame)) { QsLog.add("recv", "keep-alive"); return true; }
        if (QsFrames.isBandwidthUpgrade(frame)) {
            QsLog.add("recv", "bandwidth upgrade, event=" + QsFrames.upgradeEvent(frame));
            try { sec.send(QsFrames.upgradeFailure(5)); } catch (Exception ignored) {}
            return true;
        }
        if (QsFrames.parseDisconnection(frame) != null) {
            QsLog.add("recv", "disconnection");
            return true;
        }
        return false;
    }
}
