package com.peerzap.qs;

/**
 * The four traffic keys plus the authentication string, derived from the UKEY2
 * next-protocol secret. Constants are exactly those in the reference
 * implementation (grishka/NearDrop PROTOCOL.md):
 *
 *   D2D salt          = SHA256("D2D")            = 82AA55A0...
 *   SecureMessage salt= SHA256("SecureMessage")  = BF9D2A53...
 *   D2D sub-keys      = HKDF(salt = D2D salt, IKM = nextSecret, info = "client" / "server")
 *   ENC key           = HKDF(salt = SM salt,  IKM = own d2d key, info = "ENC:2")
 *   SIG key           = HKDF(salt = SM salt,  IKM = own d2d key, info = "SIG:1")
 *
 * The derivation is symmetric; only the role decides which side's sub-key
 * becomes "encrypt" and which becomes "decrypt".
 */
public final class D2DKeys {

    private static final byte[] D2D_SALT = hex("82AA55A0D397F88346CA1CEE8D3909B95F13FA7DEB1D4AB38376B8256DA85510");
    private static final byte[] SM_SALT = hex("BF9D2A53C63616D75DB0A7165B91C1EF73E537F2427405FA23610A4BE657642E");

    public final byte[] authString;
    public final byte[] encryptKey;
    public final byte[] sendHmacKey;
    public final byte[] decryptKey;
    public final byte[] receiveHmacKey;

    private D2DKeys(byte[] auth, byte[] enc, byte[] sig, byte[] dec, byte[] rcv) {
        authString = auth; encryptKey = enc; sendHmacKey = sig; decryptKey = dec; receiveHmacKey = rcv;
    }

    public static D2DKeys from(Ukey2.Result r, boolean isServer) throws Exception {
        byte[] d2dClient = Hkdf.derive(r.nextSecret, D2D_SALT, "client".getBytes("UTF-8"), 32);
        byte[] d2dServer = Hkdf.derive(r.nextSecret, D2D_SALT, "server".getBytes("UTF-8"), 32);
        byte[] own = isServer ? d2dServer : d2dClient;
        byte[] peer = isServer ? d2dClient : d2dServer;
        byte[] enc = Hkdf.derive(own, SM_SALT, "ENC:2".getBytes("UTF-8"), 32);
        byte[] sig = Hkdf.derive(own, SM_SALT, "SIG:1".getBytes("UTF-8"), 32);
        byte[] dec = Hkdf.derive(peer, SM_SALT, "ENC:2".getBytes("UTF-8"), 32);
        byte[] rcv = Hkdf.derive(peer, SM_SALT, "SIG:1".getBytes("UTF-8"), 32);
        return new D2DKeys(r.authString, enc, sig, dec, rcv);
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
