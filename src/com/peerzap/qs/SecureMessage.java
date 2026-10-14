package com.peerzap.qs;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The Quick Share "secure message" envelope.
 *
 * SecureMessage { header_and_body = 1, signature = 2 }
 * HeaderAndBody { header = 1, body = 2 }
 * Header { signature_scheme = 1 (HMAC_SHA256), encryption_scheme = 2 (AES_256_CBC),
 *          iv = 5, public_metadata = 6 }
 *
 * The body is AES-256-CBC/PKCS7 encrypted; the signature is HMAC-SHA256 over the
 * serialized header-and-body. This class is the stateless primitive - sequence
 * numbers and key roles live in the connection layer.
 */
public final class SecureMessage {

    public static final int SIG_HMAC_SHA256 = 1;
    public static final int ENC_AES_256_CBC = 2;

    private static final SecureRandom RNG = new SecureRandom();

    /**
     * public_metadata for a device-to-device message.
     *
     * The message is GcmMetadata { type = 1, version = 2 } - note the field order,
     * which is the opposite of the "version then type" wording in the protocol
     * notes. Type.DEVICE_TO_DEVICE_MESSAGE is 13, not 1. A real sender writes
     * `08 0d 10 01` here; this code was writing `08 01 10 01`, and because the
     * receiver's own decrypt routine never inspects this field, only the peer
     * noticed - it closed the connection the instant our frames arrived.
     */
    public static final int GCM_TYPE_DEVICE_TO_DEVICE_MESSAGE = 13;
    public static final int GCM_METADATA_VERSION = 1;

    public static byte[] d2dPublicMetadata() {
        return new Proto.Writer()
                .int32Always(1, GCM_TYPE_DEVICE_TO_DEVICE_MESSAGE)
                .int32Always(2, GCM_METADATA_VERSION)
                .toByteArray();
    }

    public static byte[] encrypt(byte[] plaintext, byte[] encKey, byte[] sigKey, byte[] publicMetadata) throws Exception {
        byte[] iv = new byte[16];
        RNG.nextBytes(iv);

        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] ciphertext = c.doFinal(plaintext);

        byte[] header = new Proto.Writer()
                .int32Always(1, SIG_HMAC_SHA256)
                .int32Always(2, ENC_AES_256_CBC)
                .bytes(5, iv)
                .bytes(6, publicMetadata)
                .toByteArray();
        byte[] headerAndBody = new Proto.Writer()
                .message(1, header)
                .bytes(2, ciphertext)
                .toByteArray();

        byte[] signature = hmac(sigKey, headerAndBody);
        QsLog.add("envelope", "sending: " + describeHeader(headerAndBody));
        return new Proto.Writer().bytes(1, headerAndBody).bytes(2, signature).toByteArray();
    }

    /** A readable dump of a header, for comparing ours with the peer's. */
    public static String describeHeader(byte[] headerAndBody) {
        try {
            byte[] h = QsFrames.subMessage(headerAndBody, 1);
            if (h == null) return "(no header)";
            byte[] iv = QsFrames.subMessage(h, 5);
            byte[] pm = QsFrames.subMessage(h, 6);
            byte[] body = QsFrames.subMessage(headerAndBody, 2);
            return "sig=" + QsFrames.varintField(h, 1, -1)
                    + " enc=" + QsFrames.varintField(h, 2, -1)
                    + " iv=" + (iv == null ? "absent" : iv.length + "B")
                    + " pubmeta=" + (pm == null ? "absent" : QsLog.hex(pm, 16))
                    + " body=" + (body == null ? 0 : body.length) + "B";
        } catch (Throwable t) { return "(unreadable: " + t + ")"; }
    }

    /** Result of decrypting an envelope. */
    public static final class Opened {
        public final byte[] plaintext;
        public final byte[] publicMetadata;
        Opened(byte[] p, byte[] m) { plaintext = p; publicMetadata = m; }
    }

    public static Opened decrypt(byte[] secureMessage, byte[] encKey, byte[] sigKey) throws Exception {
        byte[] headerAndBody = null, signature = null;
        Proto.Reader r = new Proto.Reader(secureMessage);
        Proto.Reader.Field f;
        while ((f = r.next()) != null) {
            if (f.number == 1) headerAndBody = f.bytes;
            else if (f.number == 2) signature = f.bytes;
        }
        if (headerAndBody == null || signature == null) throw new IllegalStateException("malformed secure message");
        QsLog.add("envelope", "received: " + describeHeader(headerAndBody));

        // verify before decrypting
        byte[] expected = hmac(sigKey, headerAndBody);
        if (!MessageDigestEqual(expected, signature)) throw new SecurityException("HMAC verification failed");

        byte[] header = null, body = null;
        Proto.Reader hr = new Proto.Reader(headerAndBody);
        while ((f = hr.next()) != null) {
            if (f.number == 1) header = f.bytes;
            else if (f.number == 2) body = f.bytes;
        }
        if (header == null || body == null) throw new IllegalStateException("malformed header-and-body");

        byte[] iv = null, meta = null;
        Proto.Reader hh = new Proto.Reader(header);
        while ((f = hh.next()) != null) {
            if (f.number == 5) iv = f.bytes;
            else if (f.number == 6) meta = f.bytes;
        }
        if (iv == null) throw new IllegalStateException("no IV");

        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        return new Opened(c.doFinal(body), meta);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static boolean MessageDigestEqual(byte[] a, byte[] b) {
        return java.security.MessageDigest.isEqual(a, b);
    }

    private SecureMessage() {}
}
