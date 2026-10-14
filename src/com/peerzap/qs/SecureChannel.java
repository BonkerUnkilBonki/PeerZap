package com.peerzap.qs;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A framed, encrypted channel: every message after the handshake is a
 * DeviceToDeviceMessage { message = 1, sequence_number = 2 } sealed in a
 * SecureMessage envelope, with a 4-byte big-endian length prefix on the socket.
 * Sequence numbers start at 1 and are per-direction.
 */
public final class SecureChannel {

    private final InputStream in;
    private final OutputStream out;
    private final D2DKeys keys;
    private int sendSeq = 0;
    private int recvSeq = 0;

    public SecureChannel(InputStream in, OutputStream out, D2DKeys keys) {
        this.in = in; this.out = out; this.keys = keys;
    }

    public synchronized void send(byte[] offlineFrame) throws Exception {
        sendSeq++;
        byte[] d2d = new Proto.Writer()
                .bytes(1, offlineFrame)
                .int32Always(2, sendSeq)
                .toByteArray();
        byte[] envelope = SecureMessage.encrypt(d2d, keys.encryptKey, keys.sendHmacKey,
                SecureMessage.d2dPublicMetadata());
        writeFramed(envelope);
    }

    public byte[] receive() throws Exception {
        byte[] envelope = readFramed();
        if (envelope == null) return null;
        SecureMessage.Opened opened = SecureMessage.decrypt(envelope, keys.decryptKey, keys.receiveHmacKey);
        long seq = QsFrames.varintField(opened.plaintext, 2, -1);
        if (seq != recvSeq + 1) {
            throw new IOException("out-of-order sequence: expected " + (recvSeq + 1) + " got " + seq);
        }
        recvSeq++;
        return QsFrames.subMessage(opened.plaintext, 1);
    }

    // --------------------------------------------------------- plain framing

    public void writeFramed(byte[] payload) throws IOException {
        out.write((payload.length >>> 24) & 0xFF);
        out.write((payload.length >>> 16) & 0xFF);
        out.write((payload.length >>> 8) & 0xFF);
        out.write(payload.length & 0xFF);
        out.write(payload);
        out.flush();
    }

    /** Returns null at a clean end of stream. */
    public byte[] readFramed() throws IOException {
        DataInputStream d = new DataInputStream(in);
        int len;
        try { len = d.readInt(); } catch (java.io.EOFException e) { return null; }
        if (len < 0 || len > 32 * 1024 * 1024) throw new IOException("bad frame length " + len);
        byte[] buf = new byte[len];
        d.readFully(buf);
        return buf;
    }
}
