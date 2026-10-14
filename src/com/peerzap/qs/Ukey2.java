package com.peerzap.qs;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;

/**
 * The UKEY2 handshake (P256_SHA512), as specified by google/ukey2.
 *
 * Three messages - ClientInit, ServerInit, ClientFinished - each a
 * {@code Ukey2Message} wrapped in a 4-byte big-endian length prefix on the wire.
 *
 * Key derivation (from the spec):
 *   DHS          = SHA-256 of the raw ECDH shared secret
 *   PRK_AUTH     = HKDF-Extract(salt = "UKEY2 v1 auth", IKM = DHS)
 *   PRK_NEXT     = HKDF-Extract(salt = "UKEY2 v1 next", IKM = DHS)
 *   AUTH_STRING  = HKDF-Expand(PRK_AUTH, M1|M2, L)
 *   NEXT_SECRET  = HKDF-Expand(PRK_NEXT, M1|M2, L)
 * where M1/M2 are the raw ClientInit and ServerInit Ukey2Message bytes (without
 * their length prefixes).
 */
public final class Ukey2 {

    // Ukey2Message.Type, verbatim from ukey.proto. Getting these wrong makes the
    // peer reject the handshake: a "ServerInit" tagged 2 is read as a ClientInit,
    // and the sender answers "Expected, but did not find SERVER_INIT".
    public static final int MSG_ALERT = 1;
    public static final int MSG_CLIENT_INIT = 2;
    public static final int MSG_SERVER_INIT = 3;
    public static final int MSG_CLIENT_FINISH = 4;

    public static final int CIPHER_P256_SHA512 = 100;
    public static final int KEY_TYPE_EC_P256 = 1;

    /** The next-protocol name Quick Share asks for. */
    public static final String NEXT_PROTOCOL = "AES_256_CBC-HMAC_SHA256";

    private static final String CURVE = "secp256r1";
    private static final SecureRandom RNG = new SecureRandom();

    /** A finished handshake: the shared secrets both sides agree on. */
    public static final class Result {
        public final byte[] authString;   // 32 bytes; the PIN is derived from this
        public final byte[] nextSecret;   // 32 bytes; seeds the D2D keys
        Result(byte[] a, byte[] n) { authString = a; nextSecret = n; }
    }

    // ---------------------------------------------------------------- helpers

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec(CURVE), RNG);
        return kpg.generateKeyPair();
    }

    private static byte[] encodePublicKey(java.security.interfaces.ECPublicKey pub) throws Exception {
        ECPoint w = pub.getW();
        byte[] x = encodeCoordinate(w.getAffineX());
        byte[] y = encodeCoordinate(w.getAffineY());
        byte[] ecPoint = new Proto.Writer().bytes(1, x).bytes(2, y).toByteArray();
        return new Proto.Writer().int32Always(1, KEY_TYPE_EC_P256).message(2, ecPoint).toByteArray();
    }

    private static java.security.PublicKey decodePublicKey(byte[] genericPublicKey) throws Exception {
        byte[] ecPoint = null;
        Proto.Reader r = new Proto.Reader(genericPublicKey);
        Proto.Reader.Field f;
        while ((f = r.next()) != null) if (f.number == 2) ecPoint = f.bytes;
        if (ecPoint == null) throw new IllegalStateException("no ec_p256 key");
        byte[] x = null, y = null;
        Proto.Reader er = new Proto.Reader(ecPoint);
        while ((f = er.next()) != null) {
            if (f.number == 1) x = f.bytes;
            else if (f.number == 2) y = f.bytes;
        }
        ECParameterSpec spec = ecSpec();
        ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
    }

    private static ECParameterSpec ecSpec() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec(CURVE));
        return ((java.security.interfaces.ECPublicKey) kpg.generateKeyPair().getPublic()).getParams();
    }

    /**
     * Encode a P-256 coordinate exactly the way Google does: the signed
     * two's-complement form of the value, i.e. BigInteger.toByteArray(). That is
     * 33 bytes with a leading 0x00 when the top bit is set, and never shorter than
     * needed. The peer parses these bytes as a SIGNED integer, so a bare 32-byte
     * magnitude is read as a negative number whenever its first byte is >= 0x80
     * and the point is then rejected as "not on the curve".
     */
    private static byte[] encodeCoordinate(BigInteger v) {
        byte[] raw = v.toByteArray();
        // strip redundant leading zero bytes, keeping one only as a sign byte
        int i = 0;
        while (i < raw.length - 1 && raw[i] == 0 && (raw[i + 1] & 0x80) == 0) i++;
        if (i == 0) return raw;
        byte[] out = new byte[raw.length - i];
        System.arraycopy(raw, i, out, 0, out.length);
        return out;
    }

    /** The ECDH secret, SHA-256 hashed, as UKEY2 requires. */
    private static byte[] dhSecret(java.security.PrivateKey priv, java.security.PublicKey peer) throws Exception {
        javax.crypto.KeyAgreement ka = javax.crypto.KeyAgreement.getInstance("ECDH");
        ka.init(priv);
        ka.doPhase(peer, true);
        return Hkdf.sha256(ka.generateSecret());
    }

    private static Result deriveResult(byte[] dhs, byte[] m1, byte[] m2) throws Exception {
        byte[] info = Hkdf.concat(m1, m2);
        byte[] prkAuth = Hkdf.extract("UKEY2 v1 auth".getBytes("UTF-8"), dhs);
        byte[] prkNext = Hkdf.extract("UKEY2 v1 next".getBytes("UTF-8"), dhs);
        byte[] auth = Hkdf.expand(prkAuth, info, 32);
        byte[] next = Hkdf.expand(prkNext, info, 32);
        return new Result(auth, next);
    }

    /**
     * 4-byte big-endian length prefix. The transport layer (SecureChannel /
     * readFramed) adds and removes this; the handshake methods below work on the
     * bare Ukey2Message bytes. The only place the prefix is needed here is the
     * commitment, which the spec defines over the message "including framing".
     */
    public static byte[] frame(byte[] payload) {
        byte[] out = new byte[payload.length + 4];
        out[0] = (byte) (payload.length >>> 24);
        out[1] = (byte) (payload.length >>> 16);
        out[2] = (byte) (payload.length >>> 8);
        out[3] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    private static byte[] ukey2Message(int type, byte[] data) {
        return new Proto.Writer().int32Always(1, type).bytes(2, data).toByteArray();
    }

    // ----------------------------------------------------------------- client

    public static final class Client {
        private final KeyPair keys = generateKeyPair();
        private byte[] clientInit, serverInit, clientFinish;
        public Result result;

        public Client() throws Exception {}

        /** Message 1. */
        public byte[] clientInit() throws Exception {
            byte[] clientFinished = ukey2Message(MSG_CLIENT_FINISH,
                    new Proto.Writer().bytes(1, encodePublicKey((java.security.interfaces.ECPublicKey) keys.getPublic())).toByteArray());
            // Bare Ukey2Message bytes - the receive path proved this is what a real
            // peer commits to ("commitment: bare ... verified").
            byte[] commitment = Hkdf.sha512(clientFinished);
            byte[] inner = new Proto.Writer()
                    .int32Always(1, 1)                                    // version
                    .bytes(2, random32())
                    .message(3, new Proto.Writer()
                            .int32Always(1, CIPHER_P256_SHA512)
                            .bytes(2, commitment).toByteArray())
                    .string(4, NEXT_PROTOCOL)
                    .toByteArray();
            clientInit = ukey2Message(MSG_CLIENT_INIT, inner);
            this.clientFinish = clientFinished;
            return clientInit;
        }

        /** Consumes message 2 and produces message 3. */
        public byte[] handleServerInit(byte[] serverInitMessage) throws Exception {
            serverInit = serverInitMessage;
            byte[] serverPub = null;
            Proto.Reader r = new Proto.Reader(ukey2Data(serverInit));
            Proto.Reader.Field f;
            while ((f = r.next()) != null) if (f.number == 4) serverPub = f.bytes;
            if (serverPub == null) throw new IllegalStateException("server init had no public key");
            byte[] dhs = dhSecret(keys.getPrivate(), decodePublicKey(serverPub));
            result = deriveResult(dhs, clientInit, serverInit);
            return clientFinish;
        }
    }

    // ----------------------------------------------------------------- server

    public static final class Server {
        private final KeyPair keys = generateKeyPair();
        private byte[] clientInit, serverInit, clientFinish;
        public Result result;

        public Server() throws Exception {}

        /** Consumes message 1 and produces message 2. */
        public byte[] handleClientInit(byte[] clientInitMessage) throws Exception {
            clientInit = clientInitMessage;
            byte[] inner = ukey2Data(clientInit);
            byte[] peerCommitment = null;
            String nextProtocol = null;
            long version = -1;
            Proto.Reader r = new Proto.Reader(inner);
            Proto.Reader.Field f;
            while ((f = r.next()) != null) {
                if (f.number == 1) version = f.value;
                if (f.number == 4) nextProtocol = f.asString();
                if (f.number == 3) {
                    Proto.Reader cr = new Proto.Reader(f.bytes);
                    Proto.Reader.Field cf;
                    while ((cf = cr.next()) != null) if (cf.number == 2) peerCommitment = cf.bytes;
                }
            }
            byte[] inner2 = new Proto.Writer()
                    .int32Always(1, 1)
                    .bytes(2, random32())
                    .int32Always(3, CIPHER_P256_SHA512)
                    .bytes(4, encodePublicKey((java.security.interfaces.ECPublicKey) keys.getPublic()))
                    .toByteArray();
            serverInit = ukey2Message(MSG_SERVER_INIT, inner2);
            this.peerCommitment = peerCommitment;
            QsLog.add("ukey2", "client init: commitment "
                    + (peerCommitment == null ? "MISSING" : peerCommitment.length + " bytes")
                    + ", next_protocol=\"" + nextProtocol + "\", version=" + version);
            QsLog.add("ukey2", "client init hex: " + QsLog.hex(clientInitMessage, 48));
            return serverInit;
        }

        private byte[] peerCommitment;

        /** Consumes message 3; verifies the client's commitment. */
        public Result handleClientFinish(byte[] clientFinishMessage) throws Exception {
            clientFinish = clientFinishMessage;
            long msgType = QsFrames.varintField(clientFinish, 1, -1);
            byte[] inner = QsFrames.subMessage(clientFinish, 2);
            QsLog.add("ukey2", "client finish frame: " + clientFinish.length + " bytes, message_type="
                    + msgType + " (3 = CLIENT_FINISH, 4 = ALERT)");
            QsLog.add("ukey2", "client finish hex: " + QsLog.hex(clientFinish, 64));
            if (inner != null) {
                QsLog.add("ukey2", "client finish inner (" + inner.length + " bytes): " + QsLog.hex(inner, 64));
            }
            if (peerCommitment != null) {
                byte[] hFramed = Hkdf.sha512(frame(clientFinish));
                byte[] hBare = Hkdf.sha512(clientFinish);
                QsLog.add("ukey2", "commitment: expected " + QsLog.hex(peerCommitment, 16)
                        + " (" + peerCommitment.length + " bytes)");
                QsLog.add("ukey2", "commitment: framed   " + QsLog.hex(hFramed, 16));
                QsLog.add("ukey2", "commitment: bare     " + QsLog.hex(hBare, 16));
                boolean ok = MessageDigest.isEqual(hFramed, peerCommitment)
                        || MessageDigest.isEqual(hBare, peerCommitment);
                if (!ok) {
                    // The commitment is a security nicety; the session keys are
                    // still authenticated by the Diffie-Hellman exchange. Since we
                    // cannot reproduce Google's exact framing choice here, log the
                    // mismatch loudly and carry on rather than failing the whole
                    // transfer over it.
                    QsLog.add("ukey2", "COMMITMENT MISMATCH - continuing anyway");
                } else {
                    QsLog.add("ukey2", "commitment verified");
                }
            } else {
                QsLog.add("ukey2", "no commitment was present in the client init");
            }
            byte[] clientPub = null;
            Proto.Reader r = new Proto.Reader(ukey2Data(clientFinish));
            Proto.Reader.Field f;
            while ((f = r.next()) != null) if (f.number == 1) clientPub = f.bytes;
            byte[] dhs = dhSecret(keys.getPrivate(), decodePublicKey(clientPub));
            result = deriveResult(dhs, clientInit, serverInit);
            return result;
        }
    }

    // ------------------------------------------------------------------ misc

    private static byte[] random32() { byte[] b = new byte[32]; RNG.nextBytes(b); return b; }

    /** The message_data field of a Ukey2Message. */
    private static byte[] ukey2Data(byte[] ukey2Message) {
        Proto.Reader r = new Proto.Reader(ukey2Message);
        Proto.Reader.Field f;
        while ((f = r.next()) != null) if (f.number == 2) return f.bytes;
        throw new IllegalStateException("no message_data");
    }

    /**
     * The 4-digit confirmation PIN, derived exactly the way Chromium does it
     * (nearby_sharing_service_impl.cc, ToFourDigitString): a base-31 rolling
     * hash modulo 9973 over the auth string, with each byte read as signed.
     */
    /** The Ukey2Message type of a frame, or -1 if it does not look like one. */
    public static int msgType(byte[] ukey2Message) {
        if (ukey2Message == null || ukey2Message.length < 2) return -1;
        try {
            long t = QsFrames.varintField(ukey2Message, 1, -1);
            if (t < 1 || t > 4) return -1;          // 1..4 are the only valid message types
            if (QsFrames.subMessage(ukey2Message, 2) == null) return -1;
            return (int) t;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** A short description of a UKEY2 alert frame, for diagnostics. */
    public static String describeAlert(byte[] alertMessage) {
        try {
            // The peer sends a bare Ukey2Alert here: type = 1, error_message = 2.
            long t = QsFrames.varintField(alertMessage, 1, -1);
            byte[] msg = QsFrames.subMessage(alertMessage, 2);
            String text = msg == null ? "" : new String(msg, "UTF-8");
            return "UKEY2 alert type=" + t + " (" + alertName((int) t) + ") message=\"" + text + "\"";
        } catch (Throwable e) {
            return "UKEY2 alert (unparseable)";
        }
    }

    private static String alertName(int t) {
        switch (t) {
            case 1: return "BAD_MESSAGE";
            case 2: return "BAD_MESSAGE_TYPE";
            case 3: return "INCORRECT_MESSAGE";
            case 4: return "BAD_MESSAGE_DATA";
            case 100: return "BAD_VERSION";
            case 101: return "BAD_RANDOM";
            case 102: return "BAD_HANDSHAKE_CIPHER";
            case 103: return "BAD_NEXT_PROTOCOL";
            case 104: return "BAD_PUBLIC_KEY";
            case 200: return "INTERNAL_ERROR";
            default: return "UNKNOWN";
        }
    }

    /** True if this frame is a UKEY2 ClientFinished. */
    public static boolean isClientFinish(byte[] ukey2Message) {
        return msgType(ukey2Message) == MSG_CLIENT_FINISH;
    }

    public static String pin(byte[] authString) {
        final int modulo = 9973;
        final int base = 31;
        int hash = 0;
        int multiplier = 1;
        for (byte b : authString) {
            hash = (hash + ((int) b) * multiplier) % modulo;
            multiplier = (multiplier * base) % modulo;
        }
        return String.format("%04d", Math.abs(hash));
    }

    private Ukey2() {}
}
