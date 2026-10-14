package com.peerzap;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the Accept / Decline buttons on an incoming-transfer notification. */
public class NotifyReceiver extends BroadcastReceiver {

    public static final String ACCEPT = "com.peerzap.ACCEPT";
    public static final String REJECT = "com.peerzap.REJECT";

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        String id = i.getStringExtra("id");
        if ("qsconsent".equals(id)) {
            // a stock Quick Share sender waiting on our answer
            QsReceiveService.resolveConsent(ACCEPT.equals(i.getAction()));
        } else if (ACCEPT.equals(i.getAction())) {
            TransferManager.get(c).accept(id);
        } else if (REJECT.equals(i.getAction())) {
            TransferManager.get(c).reject(id);
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(Notifier.ID_INCOMING);
    }
}
