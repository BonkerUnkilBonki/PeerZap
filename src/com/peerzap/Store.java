package com.peerzap;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/** Local persistence: device identity, settings and transfer history. */
public class Store {

    private static final String PREF = "quickshare";

    private final SharedPreferences sp;

    public Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String deviceId() {
        String id = sp.getString("deviceId", null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            sp.edit().putString("deviceId", id).apply();
        }
        return id;
    }

    public String deviceName(Context c) {
        String n = sp.getString("deviceName", null);
        if (n == null || n.trim().isEmpty()) n = defaultName(c);
        return n;
    }

    public void setDeviceName(String n) { sp.edit().putString("deviceName", n).apply(); }

    /**
     * A peer's real name, learned from a connection. Stock Quick Share does not
     * always advertise its name in the mDNS record, so the browse list has nothing
     * to show until we have talked to the device once. Keyed by address, which is
     * the one thing the browse result and a live connection share.
     */
    public String peerNameFor(String ip) {
        if (ip == null || ip.isEmpty()) return null;
        return sp.getString("peer:" + ip, null);
    }

    public void rememberPeerName(String ip, String name) {
        if (ip == null || ip.isEmpty() || name == null || name.trim().isEmpty()) return;
        sp.edit().putString("peer:" + ip, name.trim()).apply();
    }

    private static String defaultName(Context c) {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String mo = Build.MODEL == null ? "" : Build.MODEL;
        String s = (m + " " + mo).trim();
        if (s.isEmpty()) s = "Android device";
        // Title-case the manufacturer, keep the model as-is
        if (s.length() > 0) s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        return s;
    }

    public boolean visible() { return sp.getBoolean("visible", true); }
    public void setVisible(boolean v) { sp.edit().putBoolean("visible", v).apply(); }

    public String saveDirName() { return sp.getString("saveDir", "PeerZap"); }
    public void setSaveDirName(String d) { sp.edit().putString("saveDir", d).apply(); }

    public boolean notifyIncoming() { return sp.getBoolean("notifyIncoming", true); }
    public void setNotifyIncoming(boolean v) { sp.edit().putBoolean("notifyIncoming", v).apply(); }

    public boolean notifyComplete() { return sp.getBoolean("notifyComplete", true); }
    public void setNotifyComplete(boolean v) { sp.edit().putBoolean("notifyComplete", v).apply(); }

    public boolean autoAccept() { return sp.getBoolean("autoAccept", false); }
    public void setAutoAccept(boolean v) { sp.edit().putBoolean("autoAccept", v).apply(); }

    // ---- appearance (kept natively so the panel matches the app exactly) ----

    public String theme() { return sp.getString("theme", "light"); }
    public void setTheme(String v) { sp.edit().putString("theme", v).apply(); }

    public String accent() { return sp.getString("accent", "blue"); }
    public void setAccent(String v) { sp.edit().putString("accent", v).apply(); }

    public boolean glow() { return sp.getBoolean("glow", true); }
    public void setGlow(boolean v) { sp.edit().putBoolean("glow", v).apply(); }

    /** The frosted-glass surface on the panel and the bottom sheets. */
    public boolean glass() { return sp.getBoolean("glass", true); }
    public void setGlass(boolean v) { sp.edit().putBoolean("glass", v).apply(); }

    /** Experimental: talk to stock Google Quick Share over Wi-Fi LAN. */
        /** Which launcher icon style the user picked. */
    public String iconStyle() { return sp.getString("iconStyle", "light"); }
    public void setIconStyle(String v) { sp.edit().putString("iconStyle", v).apply(); }

    public boolean qsCompat() { return sp.getBoolean("qsCompat", true); }   // on by default

    /** Strict mode: nothing runs in the background. The listener only lives while the
     *  app or the panel is actually on screen. */
    public boolean strictMode() { return sp.getBoolean("strictMode", false); }
    public void setStrictMode(boolean v) { sp.edit().putBoolean("strictMode", v).apply(); }
    public void setQsCompat(boolean v) { sp.edit().putBoolean("qsCompat", v).apply(); }

    /** Hex for the current accent, used to tint notifications. */
    public String accentHex() {
        String a = accent();
        if ("purple".equals(a)) return "#8B5CF6";
        if ("green".equals(a)) return "#12B76A";
        if ("pink".equals(a)) return "#EC4899";
        if ("amber".equals(a)) return "#F59E0B";
        if ("teal".equals(a)) return "#14B8A6";
        if ("red".equals(a)) return "#EF4444";
        if ("indigo".equals(a)) return "#6366F1";
        return "#1B6EF3";
    }

    // ---- history ----

    public synchronized JSONArray history() {
        try {
            return new JSONArray(sp.getString("history", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Prepend a completed/failed transfer record; keeps the newest 200. */
    public synchronized void addHistory(JSONObject entry) {
        JSONArray a = history();
        JSONArray out = new JSONArray();
        out.put(entry);
        for (int i = 0; i < a.length() && out.length() < 200; i++) out.put(a.opt(i));
        sp.edit().putString("history", out.toString()).apply();
    }

    public synchronized void clearHistory() { sp.edit().putString("history", "[]").apply(); }

    /** Remove a single history entry by id. */
    public synchronized void removeHistory(String id) {
        JSONArray a = history();
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (id != null && id.equals(o.optString("id"))) continue;
            out.put(o);
        }
        sp.edit().putString("history", out.toString()).apply();
    }
}
