package com.peerzap.qs;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;

/** HKDF-SHA256 (RFC 5869) built directly on javax.crypto.Mac - no dependencies. */
public final class Hkdf {

    public static byte[] extract(byte[] salt, byte[] ikm) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt == null ? new byte[32] : salt, "HmacSHA256"));
        return mac.doFinal(ikm);
    }

    public static byte[] expand(byte[] prk, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        int counter = 1;
        while (out.size() < length) {
            mac.reset();
            mac.update(t);
            if (info != null) mac.update(info);
            mac.update((byte) counter++);
            t = mac.doFinal();
            out.write(t, 0, t.length);
        }
        byte[] all = out.toByteArray();
        byte[] result = new byte[length];
        System.arraycopy(all, 0, result, 0, length);
        return result;
    }

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        return expand(extract(salt, ikm), info, length);
    }

    public static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    public static byte[] sha512(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(data);
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p == null ? 0 : p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private Hkdf() {}
}
