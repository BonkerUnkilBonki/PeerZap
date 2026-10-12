package com.quickshare;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nearby device discovery over the local network using UDP broadcast.
 *
 * Every instance broadcasts a small JSON beacon every couple of seconds and
 * listens on the same UDP port, so peers on the same Wi-Fi network learn about
 * each other without any server or internet connection. Beacons are also
 * answered with a unicast reply, which makes discovery near-instant on networks
 * that filter some broadcast traffic.
 *
 * This is a plain, documented local-network mechanism. It talks to other
 * instances of this app only - it does not speak Google's proprietary Quick
 * Share protocol and does not claim to.
 */
public class Discovery {

    public static final int DISCOVERY_PORT = 8890;
    public static final int TRANSFER_PORT = 8891;
    private static final String MAGIC = "QS1";
    private static final long BEACON_MS = 2200;
    private static final long EXPIRE_MS = 9000;

    public interface Listener { void onDevicesChanged(JSONArray devices); }

    private static volatile Discovery instance;

    public static Discovery get(Context c) {
        Discovery d = instance;
        if (d == null) {
            synchronized (Discovery.class) {
                d = instance;
                if (d == null) {
                    d = new Discovery(c.getApplicationContext());
                    instance = d;
                }
            }
        }
        return d;
    }

    private final Context ctx;
    private final Store store;
    private volatile Listener listener;

    private DatagramSocket socket;
    private Thread rx, tx;
    private volatile boolean running;
    private final Map<String, JSONObject> devices = new LinkedHashMap<>();
    private final Map<String, Long> lastSeen = new LinkedHashMap<>();
    private volatile String lastSig = "";

    private Discovery(Context c) {
        this.ctx = c.getApplicationContext();
        this.store = new Store(c);
    }

    public void setListener(Listener l) {
        this.listener = l;
        if (l != null) l.onDevicesChanged(devices());
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
        } catch (Exception e) {
            socket = null;
        }
        rx = new Thread(new Runnable() { public void run() { receiveLoop(); } }, "qs-discovery-rx");
        rx.setDaemon(true);
        rx.start();
        tx = new Thread(new Runnable() { public void run() { sendLoop(); } }, "qs-discovery-tx");
        tx.setDaemon(true);
        tx.start();
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) { try { socket.close(); } catch (Throwable ignored) {} socket = null; }
        devices.clear();
        lastSeen.clear();
        notifyIfChanged();
    }

    public boolean isRunning() { return running; }

    public synchronized JSONArray devices() {
        JSONArray a = new JSONArray();
        for (JSONObject o : devices.values()) a.put(o);
        return a;
    }

    public synchronized JSONObject deviceById(String id) {
        return devices.get(id);
    }

    private JSONObject beacon() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", store.deviceId());
            o.put("name", store.deviceName(ctx));
            o.put("model", (Build.MANUFACTURER + " " + Build.MODEL).trim());
            o.put("port", TRANSFER_PORT);
            o.put("t", System.currentTimeMillis());
        } catch (Exception ignored) {}
        return o;
    }

    private void sendLoop() {
        while (running) {
            try {
                if (store.visible()) {
                    byte[] data = (MAGIC + " " + beacon().toString()).getBytes("UTF-8");
                    for (InetAddress addr : broadcastTargets()) {
                        try {
                            socket.send(new DatagramPacket(data, data.length, addr, DISCOVERY_PORT));
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            prune();
            try { Thread.sleep(BEACON_MS); } catch (InterruptedException e) { break; }
        }
    }

    private List<InetAddress> broadcastTargets() {
        List<InetAddress> out = new ArrayList<>();
        try { out.add(InetAddress.getByName("255.255.255.255")); } catch (Throwable ignored) {}
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null) out.add(b);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void receiveLoop() {
        byte[] buf = new byte[8192];
        while (running && socket != null) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String s = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (!s.startsWith(MAGIC + " ")) continue;
                JSONObject o = new JSONObject(s.substring(MAGIC.length() + 1));
                String id = o.optString("id", "");
                if (id.isEmpty() || id.equals(store.deviceId())) continue;
                String ip = p.getAddress().getHostAddress();
                o.put("ip", ip);
                o.put("port", o.optInt("port", TRANSFER_PORT));
                synchronized (this) {
                    devices.put(id, o);
                    lastSeen.put(id, System.currentTimeMillis());
                }
                // answer directly so the peer sees us immediately
                if (store.visible()) {
                    try {
                        byte[] rep = (MAGIC + " " + beacon().toString()).getBytes("UTF-8");
                        socket.send(new DatagramPacket(rep, rep.length, p.getAddress(), DISCOVERY_PORT));
                    } catch (Throwable ignored) {}
                }
                notifyIfChanged();
            } catch (Throwable ignored) {
                if (!running) break;
            }
        }
    }

    private synchronized void prune() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastSeen.entrySet()) {
            if (now - e.getValue() > EXPIRE_MS) dead.add(e.getKey());
        }
        for (String id : dead) { devices.remove(id); lastSeen.remove(id); changed = true; }
        if (changed) notifyIfChanged();
    }

    private void notifyIfChanged() {
        String sig;
        synchronized (this) {
            StringBuilder sb = new StringBuilder();
            for (JSONObject o : devices.values())
                sb.append(o.optString("id")).append('|').append(o.optString("ip")).append('|')
                  .append(o.optString("name")).append(';');
            sig = sb.toString();
        }
        if (sig.equals(lastSig)) return;
        lastSig = sig;
        Listener l = listener;
        if (l != null) l.onDevicesChanged(devices());
    }
}
