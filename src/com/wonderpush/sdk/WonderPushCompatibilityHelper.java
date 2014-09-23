package com.wonderpush.sdk;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.webkit.WebView;

class WonderPushCompatibilityHelper {

    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    public static void ViewSetBackground(View view, Drawable background) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            view.setBackground(background);
        } else {
            view.setBackgroundDrawable(background);
        }
    }

    @SuppressWarnings("deprecation")
    public static void WebViewSettingsSetDatabasePath(WebView webView, String path) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            webView.getSettings().setDatabasePath(path);
        }
    }

}
