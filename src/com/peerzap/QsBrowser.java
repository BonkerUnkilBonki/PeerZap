package com.peerzap;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browses mDNS for real Google Quick Share devices advertising
 * {@code _FC9F5ED42C8A._tcp.}, so this app can list them alongside its own peers.
 *
 * The instance name is URL-safe base64 of 10 bytes: 0x23, a 4-byte endpoint id,
 * the 3-byte service id FC 9F 5E, then two zero bytes. The TXT key "n" is
 * URL-safe base64 of the endpoint info:
 *   1 byte  version(3 bits) | visible(1) | device type(3) | reserved(1)
 *   16 bytes identity
 *   1 byte  name length, then the UTF-8 name (present when the visible bit is 0)
 */
public final class QsBrowser {

    private static final String TAG = "QsBrowser";

    private final Context ctx;
    private NsdManager.DiscoveryListener listener;
    private final Map<String, JSONObject> found = new LinkedHashMap<>();
    private volatile boolean running;

    public QsBrowser(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public synchronized void start() {
        if (running) return;
        running = true;
        NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) { running = false; return; }

        listener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { Log.i(TAG, "browsing " + type); }

            @Override public void onServiceFound(NsdServiceInfo info) {
                if (!QsDiscovery.SERVICE_TYPE.equals(info.getServiceType())) return;
                Log.i(TAG, "found " + info.getServiceName());
                resolve(info);
            }

            @Override public void onServiceLost(NsdServiceInfo info) {
                synchronized (QsBrowser.this) { found.remove(info.getServiceName()); }
            }

            @Override public void onDiscoveryStopped(String type) { Log.i(TAG, "stopped " + type); }

            @Override public void onStartDiscoveryFailed(String type, int code) {
                Log.w(TAG, "start failed " + code);
                running = false;
            }

            @Override public void onStopDiscoveryFailed(String type, int code) {}
        };

        try { nsd.discoverServices(QsDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener); }
        catch (Throwable t) { Log.w(TAG, "discoverServices: " + t.getMessage()); running = false; }
    }

    public void stop() {
        running = false;
        try {
            NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd != null && listener != null) nsd.stopServiceDiscovery(listener);
        } catch (Throwable ignored) {}
        listener = null;
        synchronized (this) { found.clear(); }
    }

    private void resolve(final NsdServiceInfo info) {
        NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int code) {
                    Log.w(TAG, "resolve failed for " + i.getServiceName() + ": " + code);
                }
                @Override public void onServiceResolved(NsdServiceInfo r) {
                    // We advertise the same mDNS service type, so our own record comes
                    // back in the browse results. Drop anything resolving to one of this
                    // device's own addresses, otherwise we list ourselves as a peer.
                    java.net.InetAddress addr = r.getHost();
                    if (addr != null && QsDiscovery.localAddresses().contains(addr.getHostAddress())) {
                        Log.i(TAG, "ignoring our own advertisement " + r.getServiceName());
                        synchronized (QsBrowser.this) { found.remove(r.getServiceName()); }
                        return;
                    }
                    JSONObject o = toDevice(r);
                    if (o != null) synchronized (QsBrowser.this) {
                        found.put(r.getServiceName(), o);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "resolve: " + t.getMessage());
        }
    }

    /** Turns a resolved service into the app's device shape. */
    private JSONObject toDevice(NsdServiceInfo r) {
        try {
            String instanceName = r.getServiceName();
            String endpointId = endpointIdFrom(instanceName);
            String name = null;
            int deviceType = 0;

            byte[] raw = r.getAttributes() == null ? null : r.getAttributes().get("n");
            if (raw != null) {
                byte[] decoded = decodeBase64(new String(raw, StandardCharsets.US_ASCII));
                if (decoded != null) {
                    // Android's getAttributes() is inconsistent about the TXT value: some
                    // versions hand back the raw attribute value, others leave the length
                    // byte on the front. Trying offset 1 as well costs nothing and is the
                    // difference between a name and no name. Log both so the truth is
                    // visible in Diagnostics.
                    com.peerzap.qs.QsLog.add("browse", "n=" + new String(raw, StandardCharsets.US_ASCII)
                            + " decoded[" + decoded.length + "]=" + hex(decoded));
                    for (int skip = 0; skip <= 1 && name == null; skip++) {
                        if (decoded.length < skip + 18) continue;
                        int flags = decoded[skip] & 0xFF;
                        // bit 4 set means the device is not advertising a name
                        boolean hasName = (flags & 0x10) == 0;
                        int type = (flags >> 1) & 0x07;
                        if (skip == 0) deviceType = type;
                        if (!hasName) { if (skip == 0) deviceType = type; continue; }
                        int len = decoded[skip + 17] & 0xFF;
                        if (len > 0 && decoded.length >= skip + 18 + len) {
                            String candidate = new String(decoded, skip + 18, len, StandardCharsets.UTF_8);
                            if (isPrintable(candidate)) {
                                name = candidate;
                                if (skip == 0) deviceType = type;
                            }
                        }
                    }
                    com.peerzap.qs.QsLog.add("browse", "parsed name=\"" + name + "\" type=" + deviceType);
                }
            }

            InetAddress addr = r.getHost();
            String hostIp = addr == null ? "" : addr.getHostAddress();
            // Stock Quick Share does not always put its name in the mDNS record. If we
            // have talked to this address before, use the name it gave us then.
            if (!isPrintable(name) && !hostIp.isEmpty()) {
                try {
                    String remembered = new Store(ctx).peerNameFor(hostIp);
                    if (isPrintable(remembered)) name = remembered;
                } catch (Throwable ignored) {}
            }
            JSONObject o = new JSONObject();
            o.put("id", "qs:" + (endpointId == null ? instanceName : endpointId));
            o.put("name", isPrintable(name) ? name.trim() : "Quick Share device");
            o.put("ip", hostIp);
            o.put("model", deviceTypeLabel(deviceType));
            o.put("port", r.getPort());
            o.put("endpointId", endpointId == null ? "" : endpointId);
            o.put("stock", true);
            return o;
        } catch (Throwable t) {
            Log.w(TAG, "toDevice: " + t.getMessage());
            return null;
        }
    }

    private static String deviceTypeLabel(int type) {
        switch (type) {
            case 1: return "Phone";
            case 2: return "Tablet";
            case 3: return "Laptop";
            // no label for an unknown type: the row already appends "· Quick Share",
            // so returning one here printed "Quick Share" twice
            default: return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length && i < 64; i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    /** A plausible device name: not null, non-blank, and free of control bytes. */
    private static boolean isPrintable(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }

    /** The 4-byte endpoint id inside the 10-byte base64 instance name. */
    static String endpointIdFrom(String instanceName) {
        byte[] raw = decodeBase64(instanceName);
        if (raw == null || raw.length < 5) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < 5; i++) sb.append(String.format("%02x", raw[i]));
        return sb.toString();
    }

    private static byte[] decodeBase64(String text) {
        if (text == null) return null;
        try {
            return Base64.decode(text, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Throwable ignored) {}
        try {
            return Base64.decode(text, Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (Throwable ignored) {}
        return null;
    }

    /** The devices found so far. */
    public synchronized JSONArray devices() {
        JSONArray a = new JSONArray();
        for (JSONObject o : found.values()) a.put(o);
        return a;
    }

    public synchronized int count() { return found.size(); }

    /** The device with this id, or null. */
    public synchronized JSONObject deviceById(String id) {
        for (JSONObject o : found.values()) {
            if (id != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }
}
