package com.quickshare;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the Accept / Decline buttons on an incoming-transfer notification. */
public class NotifyReceiver extends BroadcastReceiver {

    public static final String ACCEPT = "com.quickshare.ACCEPT";
    public static final String REJECT = "com.quickshare.REJECT";

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        String id = i.getStringExtra("id");
        TransferManager tm = TransferManager.get(c);
        if (ACCEPT.equals(i.getAction())) {
            tm.accept(id);
        } else if (REJECT.equals(i.getAction())) {
            tm.reject(id);
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(Notifier.ID_INCOMING);
    }
}
