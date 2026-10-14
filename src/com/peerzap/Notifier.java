package com.peerzap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;

import org.json.JSONObject;

/**
 * Builds the app's notifications: the foreground-service notice, incoming-file
 * requests, completed transfers, and a live progress notification for a running
 * transfer.
 *
 * On Android 16 (API 36) the progress notification uses {@link Notification.ProgressStyle},
 * which is what makes it eligible to be shown as a Live Update - a progress chip in
 * the status bar and on the lock screen. On older versions the same notification
 * falls back to the classic determinate progress bar. The accent colour chosen in
 * the app tints it either way.
 */
public class Notifier {

    public static final String CH_SERVICE = "qs_service";
    public static final String CH_INCOMING = "qs_incoming";
    public static final String CH_DONE = "qs_done";
    public static final String CH_PROGRESS = "qs_progress";

    public static final int ID_SERVICE = 1;
    public static final int ID_INCOMING = 2;
    public static final int ID_DONE = 3;
    private static final int ID_PROGRESS_BASE = 100;

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_SERVICE) == null) {
            NotificationChannel ch = new NotificationChannel(CH_SERVICE, c.getString(R.string.svc_channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_INCOMING) == null) {
            NotificationChannel ch = new NotificationChannel(CH_INCOMING, c.getString(R.string.incoming_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_DONE) == null) {
            NotificationChannel ch = new NotificationChannel(CH_DONE, "Completed transfers",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_PROGRESS) == null) {
            NotificationChannel ch = new NotificationChannel(CH_PROGRESS, "Transfer progress",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private static int progressId(String transferId) {
        int h = transferId == null ? 0 : Math.abs(transferId.hashCode());
        return ID_PROGRESS_BASE + (h % 800);
    }

    private static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 0, i, flags);
    }

    private static PendingIntent action(Context c, String act, String id, int req) {
        Intent i = new Intent(c, NotifyReceiver.class);
        i.setAction(act);
        i.putExtra("id", id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, req, i, flags);
    }

    private static Notification.Builder builder(Context c, String channel) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, channel);
        return new Notification.Builder(c);
    }

    private static int accent(Context c) {
        try { return Color.parseColor(new Store(c).accentHex()); } catch (Throwable t) { return 0xFF1B6EF3; }
    }

    public static Notification service(Context c) {
        ensureChannels(c);
        Notification.Builder b = builder(c, CH_SERVICE);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(c.getString(R.string.svc_title))
                .setContentText(c.getString(R.string.svc_text))
                .setOngoing(true)
                .setColor(accent(c))
                .setContentIntent(open(c));
        return b.build();
    }

    public static void incoming(Context c, String id, String peer, int count) {
        ensureChannels(c);
        Notification.Builder b = builder(c, CH_INCOMING);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Incoming file" + (count == 1 ? "" : "s"))
                .setContentText(count + " file(s) from " + peer)
                .setAutoCancel(true)
                .setColor(accent(c))
                .setContentIntent(open(c));
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_save, "Accept", action(c, NotifyReceiver.ACCEPT, id, 10)).build());
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Decline", action(c, NotifyReceiver.REJECT, id, 11)).build());
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID_INCOMING, b.build());
    }

    /** Like incoming(), but shows the PIN the sender is displaying. */
    public static void incomingWithPin(Context c, String id, String peer, int count, String pin) {
        ensureChannels(c);
        Notification.Builder b = builder(c, CH_INCOMING);
        String sub = count + " file(s) from " + peer
                + (pin == null || pin.isEmpty() ? "" : " \u00b7 PIN " + pin);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Incoming file" + (count == 1 ? "" : "s"))
                .setContentText(sub)
                .setStyle(new Notification.BigTextStyle().bigText(
                        sub + "\nEnter this PIN on the sender to confirm."))
                .setAutoCancel(true)
                .setColor(accent(c))
                .setContentIntent(open(c));
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_save, "Accept", action(c, NotifyReceiver.ACCEPT, id, 10)).build());
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Decline", action(c, NotifyReceiver.REJECT, id, 11)).build());
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID_INCOMING, b.build());
    }

    /**
     * Live progress for a running transfer. Re-posted as bytes move, so the
     * notification (and, on Android 16, the Live Update chip) tracks the transfer.
     */
    public static void progress(Context c, JSONObject rec) {
        Notification n = buildProgress(c, rec);
        if (n == null) return;
        try {
            String id = rec.optString("id", "");
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(progressId(id), n);
        } catch (Throwable ignored) {}
    }

    /** Builds the live progress notification without posting it, so a foreground
     *  service can hand it to startForeground() and have one live notification
     *  rather than a plain one plus a progress one. */
    public static Notification buildProgress(Context c, JSONObject rec) {
        if (rec == null) return null;
        ensureChannels(c);
        try {
            String id = rec.optString("id", "");
            boolean sending = !"recv".equals(rec.optString("dir", "send"));
            String peer = rec.optString("peer", "device");
            long done = rec.optLong("done", 0);
            long size = rec.optLong("size", 0);
            int count = rec.optInt("count", 0);
            double speed = rec.optDouble("speed", 0);
            long eta = rec.optLong("eta", -1);
            int pct = size > 0 ? (int) Math.max(0, Math.min(100, done * 100 / size)) : 0;

            String title = (sending ? "Sending to " : "Receiving from ") + peer;
            StringBuilder sub = new StringBuilder();
            if (count > 1) sub.append(count).append(" files · ");
            sub.append(fmtSize(done));
            if (size > 0) sub.append(" of ").append(fmtSize(size));
            if (speed > 1) sub.append(" · ").append(fmtSize((long) speed)).append("/s");
            if (eta > 0) sub.append(" · ").append(eta).append("s left");

            Notification.Builder b = builder(c, CH_PROGRESS);
            b.setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle(title)
                    .setContentText(sub.toString())
                    .setColor(accent(c))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setContentIntent(open(c))
                    .setProgress(100, pct, false);

            if (Build.VERSION.SDK_INT >= 36) styleLive(b, c, pct);

            return b.build();
        } catch (Throwable ignored) { return null; }
    }

    /** Android 16+ only: attach a ProgressStyle so the system can show a Live Update. */
    private static void styleLive(Notification.Builder b, Context c, int pct) {
        try {
            Notification.ProgressStyle ps = new Notification.ProgressStyle()
                    .setProgress(pct)
                    .setStyledByProgress(true)
                    .setProgressTrackerIcon(Icon.createWithResource(c, R.drawable.ic_qs_tile));
            b.setStyle(ps);
            b.setShortCriticalText(pct + "%");
        } catch (Throwable ignored) {}
    }

    public static void cancelProgress(Context c, String id) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(progressId(id));
        } catch (Throwable ignored) {}
    }

    public static void completed(Context c, String peer, int count, boolean received) {
        ensureChannels(c);
        Notification.Builder b = builder(c, CH_DONE);
        b.setSmallIcon(received ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_sys_upload_done)
                .setContentTitle(received ? "Files received" : "Files sent")
                .setContentText(count + " file(s) " + (received ? "from " : "to ") + peer)
                .setAutoCancel(true)
                .setColor(accent(c))
                .setContentIntent(open(c));
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID_DONE, b.build());
    }

    private static String fmtSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return (n / 1024) + " KB";
        if (n < 1073741824L) return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", n / 1073741824.0);
    }
}
