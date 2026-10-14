package com.peerzap;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * The Quick Share panel. It is launched by the Quick Settings tile (or by the
 * notification's quick action) and appears over whatever the user was doing as a
 * bottom sheet - the app's full screen never opens.
 *
 * The activity itself is fully transparent and the panel (scrim + bottom sheet)
 * is drawn by the page, so it can animate up from the bottom in the same visual
 * language as the rest of the app.
 */
public class SharePanelActivity extends Activity {

    private static final String PANEL_URL = "file:///android_asset/www/panel.html";
    private WebView web;
    private QsBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setDimAmount(0f);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Throwable ignored) {}

        bridge = new QsBridge(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setDisplayZoomControls(false);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setVerticalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        web.addJavascriptInterface(bridge.new Api(), "QuickShare");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                android.net.Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
        });

        setContentView(web);
        bridge.attach(web);
        web.loadUrl(PANEL_URL);

        // make sure the engine is up even if the app was never opened
        bridge.setUiVisible(true);

        // a share from the system sheet opens the panel, not the app
        bridge.handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bridge.handleShareIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bridge.attach(web);
        bridge.setUiVisible(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // the panel is hidden: stop Quick Share, unless a transfer is still running
        bridge.onVisibleUiGone();
    }

    @Override
    protected void onDestroy() {
        bridge.detach();
        // the panel is gone: stop Quick Share, unless a transfer is still running
        // (in which case it is kept alive until it finishes, then shuts down)
        bridge.onVisibleUiGone();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (bridge.onActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        dismiss();
    }

    /** Slides the panel out and closes this overlay. */
    public void dismiss() {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    web.evaluateJavascript("window.qsCloseAnim&&window.qsCloseAnim()", null);
                } catch (Throwable ignored) {}
                web.postDelayed(new Runnable() {
                    public void run() { finish(); }
                }, 180);
            }
        });
    }

    @Override
    public void finish() {
        super.finish();
        try { overridePendingTransition(R.anim.panel_in, R.anim.panel_out); } catch (Throwable ignored) {}
    }
}
