package com.peerzap;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps device discovery and the receive server alive while the app is in the
 * background, within Android's execution limits: it is a data-sync foreground
 * service with an ongoing, low-importance notification.
 */
public class TransferService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForeground(Notifier.ID_SERVICE, Notifier.service(this));
            } else {
                startForeground(Notifier.ID_SERVICE, Notifier.service(this));
            }
        } catch (Throwable ignored) {}

        Discovery d = Discovery.get(this);
        d.start();
        TransferManager.get(this).start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
