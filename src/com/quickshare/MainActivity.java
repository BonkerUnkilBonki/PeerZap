package com.quickshare;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;

import java.util.ArrayList;

/**
 * The full app screen: hosts the bundled web UI in a WebView and wires it to the
 * native sharing engine through {@link QsBridge}.
 */
public class MainActivity extends Activity {

    private WebView web;
    private QsBridge bridge;
    private static final String DEFAULT_URL = "file:///android_asset/www/index.html";
    private ValueCallback<Uri[]> filePathCb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bridge = new QsBridge(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.setBackgroundColor(Color.parseColor("#F2F2F2"));

        web.addJavascriptInterface(bridge.new Api(), "QuickShare");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                try {
                    startActivityForResult(bridge.pickIntent(), QsBridge.REQ_PICK);
                } catch (Exception e) {
                    filePathCb = null;
                    return false;
                }
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
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
        web.loadUrl(DEFAULT_URL);

        Notifier.ensureChannels(this);
        requestNeededPermissions();
        bridge.startEngine();
        bridge.handleShareIntent(getIntent());
    }

    private void requestNeededPermissions() {
        ArrayList<String> want = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            want.add("android.permission.POST_NOTIFICATIONS");
            want.add("android.permission.NEARBY_WIFI_DEVICES");
        } else {
            want.add("android.permission.ACCESS_FINE_LOCATION");
        }
        try { requestPermissions(want.toArray(new String[0]), 1); } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        bridge.attach(web);
        bridge.startEngine();
    }

    @Override
    protected void onStop() {
        // leaving the app stops Quick Share - unless a transfer is still running,
        // in which case it finishes first, or the user asked it to keep listening
        bridge.onVisibleUiGone();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        bridge.detach();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bridge.handleShareIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == QsBridge.REQ_PICK) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (resultCode == RESULT_OK && data != null) {
                android.content.ClipData cd = data.getClipData();
                if (cd != null) {
                    for (int k = 0; k < cd.getItemCount(); k++) {
                        Uri u = cd.getItemAt(k).getUri();
                        if (u != null) uris.add(u);
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
            }
            if (filePathCb != null) {
                filePathCb.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
                filePathCb = null;
            }
            bridge.onActivityResult(requestCode, resultCode, data);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
