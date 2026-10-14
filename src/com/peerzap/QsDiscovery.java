package com.peerzap;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Advertises this device as a Google Quick Share receiver over mDNS, so a stock
 * Quick Share sender on the same Wi-Fi network can find it.
 *
 * Service type: _FC9F5ED42C8A._tcp.  (the first six bytes of SHA256("NearbySharing"))
 * Instance name: URL-safe base64 of { 0x23, endpointId[4], 0xFC,0x9F,0x5E, 0x00,0x00 }
 * TXT record "n": URL-safe base64 of the endpoint info
 */
public final class QsDiscovery {

    private static final String TAG = "QsDiscovery";
    public static final String SERVICE_TYPE = "_FC9F5ED42C8A._tcp.";
    public static final String TXT_ENDPOINT_INFO = "n";
    public static final String TXT_IPV4 = "IPv4";
    public static final String TXT_WIFI_FREQUENCY = "f";

    /**
     * NsdServiceInfo.setAttribute(String, byte[]), reached reflectively.
     * Some Android 36 platform stubs only declare the String overload, and the
     * String path is lossy for any byte >= 0x80 (AOSP runs it through UTF-8).
     */
    private static final java.lang.reflect.Method SET_BYTES;
    static {
        java.lang.reflect.Method m = null;
        try { m = NsdServiceInfo.class.getMethod("setAttribute", String.class, byte[].class); }
        catch (Throwable ignored) {}
        SET_BYTES = m;
    }

    private static void setBinary(NsdServiceInfo info, String key, byte[] value) {
        try {
            if (SET_BYTES != null) { SET_BYTES.invoke(info, key, value); return; }
        } catch (Throwable t) {
            Log.w(TAG, "reflective setAttribute failed for " + key + ": " + t.getMessage());
        }
        try { info.setAttribute(key, new String(value, "US-ASCII")); } catch (Throwable ignored) {}
    }

    /** The device's LAN IPv4 address, which stock senders like to have explicitly. */
    static String localIpv4() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private final Context ctx;
    private NsdManager.RegistrationListener listener;
    private WifiManager.MulticastLock multicastLock;
    private String registeredName;

    public QsDiscovery(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public void advertise(String deviceName, int port) {
        NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);

        // mDNS needs a multicast lock or the advertisement is filtered out
        try {
            WifiManager wifi = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("quickshare-mdns");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "multicast lock unavailable: " + t.getMessage());
        }

        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceType(SERVICE_TYPE);
        info.setServiceName(instanceName());
        info.setPort(port);
        // the TXT value is the endpoint info encoded in URL-safe base64
        setBinary(info, TXT_ENDPOINT_INFO, Base64.encodeToString(endpointInfo(deviceName),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        // stock senders read these to reach us without relying on the mDNS A record
        String ipv4 = localIpv4();
        if (ipv4 != null) setBinary(info, TXT_IPV4, ipv4.getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        listener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo i) {
                registeredName = i.getServiceName();
                Log.i(TAG, "advertising as " + registeredName);
            }
            @Override public void onRegistrationFailed(NsdServiceInfo i, int errorCode) {
                Log.w(TAG, "mDNS registration failed: " + errorCode);
            }
            @Override public void onServiceUnregistered(NsdServiceInfo i) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo i, int errorCode) {}
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
    }

    public void stop() {
        try {
            if (listener != null) {
                NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
                if (nsd != null) nsd.unregisterService(listener);
            }
        } catch (Throwable t) { Log.w(TAG, "unregister: " + t.getMessage()); }
        listener = null;
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); }
        catch (Throwable ignored) {}
        multicastLock = null;
    }

    /** Every address this device answers on - used to filter ourselves out of results. */
    public static java.util.Set<String> localAddresses() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) out.add(addrs.nextElement().getHostAddress());
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** A 4-character alphanumeric endpoint id, matching the advertised instance name. */
    public static String endpointId() {
        byte[] raw = decode(instanceName());
        if (raw == null || raw.length < 5) return "QS00";
        StringBuilder sb = new StringBuilder();
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 1; i < 5; i++) sb.append(A.charAt((raw[i] & 0xFF) % A.length()));
        return sb.toString();
    }

    private static byte[] decode(String b64) {
        try { return Base64.decode(b64, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }
        catch (Throwable ignored) { return null; }
    }

    /** 10 bytes, URL-safe base64, no padding. */
    static String instanceName() {
        byte[] id = new byte[4];
        new SecureRandom().nextBytes(id);
        byte[] raw = new byte[10];
        raw[0] = 0x23;
        System.arraycopy(id, 0, raw, 1, 4);
        raw[5] = (byte) 0xFC; raw[6] = (byte) 0x9F; raw[7] = (byte) 0x5E;
        raw[8] = 0; raw[9] = 0;
        return Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    /**
     * The endpoint info blob:
     *   1 byte  bitfield: version(3) | visibility(1) | device type(3) | reserved(1)
     *   16 bytes identity (random; meaningless without Google's servers)
     *   1 byte  name length, then the UTF-8 name (only when visible)
     */
    static byte[] endpointInfo(String deviceName) {
        int version = 1, visible = 0, deviceType = 1;   // phone
        byte flags = (byte) ((version << 5) | (visible << 4) | (deviceType << 1));
        byte[] identity = new byte[16];
        new SecureRandom().nextBytes(identity);
        byte[] name = (deviceName == null ? "Android" : deviceName).getBytes(StandardCharsets.UTF_8);
        if (name.length > 19) {                          // stock Quick Share dislikes long names
            byte[] cut = new byte[19];
            System.arraycopy(name, 0, cut, 0, 19);
            name = cut;
        }
        byte[] out = new byte[1 + 16 + 1 + name.length];
        out[0] = flags;
        System.arraycopy(identity, 0, out, 1, 16);
        out[17] = (byte) name.length;
        System.arraycopy(name, 0, out, 18, name.length);
        return out;
    }
}
