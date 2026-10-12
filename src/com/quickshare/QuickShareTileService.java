package com.quickshare;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * The Quick Settings tile. Tapping it drops the Quick Share panel up from the
 * bottom of the screen and collapses the Quick Settings shade - the full app is
 * never opened. The tile also reflects whether the device is currently
 * discoverable.
 */
public class QuickShareTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        updateTile();
        Intent i = new Intent(this, SharePanelActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(i);
            }
        } catch (Throwable t) {
            // last resort - open the panel the ordinary way
            try { startActivity(i); } catch (Throwable ignored) {}
        }
    }

    private void updateTile() {
        try {
            Tile t = getQsTile();
            if (t == null) return;
            Store st = new Store(this);
            boolean visible = st.visible();
            t.setState(visible ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            if (Build.VERSION.SDK_INT >= 29) {
                t.setSubtitle(visible ? "Visible nearby" : "Hidden");
            }
            try {
                t.setIcon(Icon.createWithResource(this, R.drawable.ic_qs_tile));
            } catch (Throwable ignored) {}
            t.updateTile();
        } catch (Throwable ignored) {}
    }
}
