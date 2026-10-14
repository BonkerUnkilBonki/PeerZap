package com.peerzap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.os.Build;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wi-Fi Direct (peer-to-peer) discovery and link setup.
 *
 * This exists so two phones that are near each other but NOT on the same Wi-Fi
 * network - one on a hotspot, one on home Wi-Fi, or one on mobile data with Wi-Fi
 * on - can still reach each other. It does not transfer anything itself: once the
 * direct group is up it hands the peer's address to TransferManager, which runs the
 * same transfer it already runs over a LAN. Nothing in the existing paths changes.
 *
 * It cannot help two phones that are far apart; that needs a relay or NAT
 * traversal, which needs a server this app does not have.
 */
public class QsWifiDirect {

    private static final String TAG = "QsWifiDirect";

    /** The group owner is always the DHCP server of the P2P subnet. */
    public static final String GROUP_OWNER_IP = "192.168.49.1";
    /** The first client to join is handed this address by convention. */
    public static final String FIRST_CLIENT_IP = "192.168.49.2";

    private final Context ctx;
    private WifiP2pManager mgr;
    private Channel ch;
    private BroadcastReceiver rx;
    private boolean started;

    /** deviceAddress -> the framework's device record, for connect(). */
    private final Map<String, WifiP2pDevice> peers = new LinkedHashMap<>();
    /** the same peers in the app's device shape, for the list. */
    private final Map<String, JSONObject> found = new LinkedHashMap<>();

    private String connectingAddress = null;
    private Runnable onLinked = null;
    /** The peer's address on the direct link, once the group is formed. */
    private volatile String linkedPeerIp = null;

    public QsWifiDirect(Context c) { this.ctx = c.getApplicationContext(); }

    // --------------------------------------------------------------- lifecycle

    public synchronized void start() {
        if (started) return;
        try {
            mgr = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
            if (mgr == null) return;
            ch = mgr.initialize(ctx, Looper.getMainLooper(), null);
            if (ch == null) return;

            rx = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (i == null || i.getAction() == null) return;
                    switch (i.getAction()) {
                        case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                            requestPeers();
                            break;
                        case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                            onConnectionChanged(i);
                            break;
                        default:
                            break;
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            ctx.registerReceiver(rx, f);
            started = true;
            com.peerzap.qs.QsLog.add("direct", "wifi direct started");
            discover();
        } catch (Throwable t) {
            com.peerzap.qs.QsLog.add("direct", "start failed: " + t.getMessage());
        }
    }

    public synchronized void stop() {
        if (!started) return;
        try { ctx.unregisterReceiver(rx); } catch (Throwable ignored) {}
        try { if (mgr != null) mgr.removeGroup(ch, null); } catch (Throwable ignored) {}
        started = false;
        peers.clear(); found.clear();
    }

    public boolean isStarted() { return started; }



    // --------------------------------------------------------------- discovery

    public void discover() {
        if (mgr == null || ch == null) return;
        try {
            mgr.discoverPeers(ch, new ActionListener() {
                @Override public void onSuccess() {
                    com.peerzap.qs.QsLog.add("direct", "peer discovery started");
                }
                @Override public void onFailure(int reason) {
                    // reason 0 usually means a permission was refused (location on
                    // older Android, NEARBY_WIFI_DEVICES on 13+)
                    com.peerzap.qs.QsLog.add("direct", "discoverPeers failed, reason=" + reason);
                }
            });
        } catch (Throwable t) {
            com.peerzap.qs.QsLog.add("direct", "discover threw: " + t.getMessage());
        }
    }

    private void requestPeers() {
        if (mgr == null || ch == null) return;
        try {
            mgr.requestPeers(ch, new PeerListListener() {
                @Override public void onPeersAvailable(android.net.wifi.p2p.WifiP2pDeviceList list) {
                    synchronized (QsWifiDirect.this) {
                        peers.clear(); found.clear();
                        if (list != null) for (WifiP2pDevice d : list.getDeviceList()) {
                            if (d == null || d.deviceAddress == null) continue;
                            peers.put(d.deviceAddress, d);
                            found.put(d.deviceAddress, toDevice(d));
                        }
                    }
                    com.peerzap.qs.QsLog.add("direct", "peers: " + peers.size());
                }
            });
        } catch (Throwable t) {
            com.peerzap.qs.QsLog.add("direct", "requestPeers threw: " + t.getMessage());
        }
    }

    private JSONObject toDevice(WifiP2pDevice d) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", "p2p:" + d.deviceAddress);
            String name = d.deviceName;
            if (name == null || name.trim().isEmpty()) name = "Direct device";
            o.put("name", name.trim());
            // the address on the direct link is only known once a group forms
            o.put("ip", "");
            o.put("port", Discovery.TRANSFER_PORT);
            o.put("address", d.deviceAddress);
            o.put("direct", true);
            o.put("model", "Wi-Fi Direct");
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The direct-link peers, in the app's device shape. */
    public synchronized JSONArray devices() {
        JSONArray a = new JSONArray();
        for (JSONObject o : found.values()) a.put(o);
        return a;
    }

    public synchronized JSONObject deviceById(String id) {
        if (id == null || !id.startsWith("p2p:")) return null;
        return found.get(id.substring(4));
    }

    // ------------------------------------------------------------- link setup

    /**
     * Forms a direct group with this peer and calls onReady once the link is up.
     * The peer's address on the link is then available from linkedPeerIp().
     */
    public void connect(final String deviceId, final Runnable onReady) {
        if (mgr == null || ch == null) {
            com.peerzap.qs.QsLog.add("direct", "connect: wifi direct not started");
            return;
        }
        final WifiP2pDevice d;
        synchronized (this) { d = peers.get(deviceId == null ? "" : deviceId.replace("p2p:", "")); }
        if (d == null) {
            com.peerzap.qs.QsLog.add("direct", "connect: peer not in the list");
            return;
        }
        try {
            WifiP2pConfig cfg = new WifiP2pConfig();
            cfg.deviceAddress = d.deviceAddress;
            cfg.wps.setup = android.net.wifi.WpsInfo.PBC;
            connectingAddress = d.deviceAddress;
            onLinked = onReady;
            linkedPeerIp = null;
            com.peerzap.qs.QsLog.add("direct", "connecting to " + d.deviceName
                    + " (" + d.deviceAddress + ")");
            mgr.connect(ch, cfg, new ActionListener() {
                @Override public void onSuccess() {
                    com.peerzap.qs.QsLog.add("direct", "connect requested");
                }
                @Override public void onFailure(int reason) {
                    com.peerzap.qs.QsLog.add("direct", "connect failed, reason=" + reason);
                    connectingAddress = null; onLinked = null;
                }
            });
        } catch (Throwable t) {
            com.peerzap.qs.QsLog.add("direct", "connect threw: " + t.getMessage());
        }
    }

    /** The peer's address on the direct link, or null until a group is formed. */
    public String linkedPeerIp() { return linkedPeerIp; }

    private void onConnectionChanged(Intent intent) {
        try {
            NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
            if (ni == null || !ni.isConnected()) return;
            if (mgr == null || ch == null) return;
            mgr.requestConnectionInfo(ch, new ConnectionInfoListener() {
                @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
                    if (info == null || !info.groupFormed) return;
                    // The group owner is the subnet's gateway; a client can always
                    // reach it at a fixed address. When we ARE the group owner the
                    // peer is the client, which the framework hands the next address.
                    String peerIp = info.isGroupOwner
                            ? FIRST_CLIENT_IP
                            : (info.groupOwnerAddress == null
                                    ? GROUP_OWNER_IP
                                    : info.groupOwnerAddress.getHostAddress());
                    linkedPeerIp = peerIp;
                    com.peerzap.qs.QsLog.add("direct", "group formed, we are "
                            + (info.isGroupOwner ? "owner" : "client")
                            + ", peer at " + peerIp);
                    Runnable r = onLinked;
                    onLinked = null;
                    if (r != null) {
                        try { r.run(); } catch (Throwable t) {
                            com.peerzap.qs.QsLog.add("direct", "onLinked threw: " + t.getMessage());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            com.peerzap.qs.QsLog.add("direct", "connectionChanged threw: " + t.getMessage());
        }
    }

    /** Tears the group down once a transfer is done, so Wi-Fi returns to normal. */
    public void disconnect() {
        try {
            if (mgr != null && ch != null) mgr.removeGroup(ch, null);
        } catch (Throwable ignored) {}
        linkedPeerIp = null;
    }
}
