package com.tvroom.downloader.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class AppSettings {
    public static final String DEFAULT_URL = "https://tvroom20.org/";
    private static final String PREFS = "tvroom_settings";
    private static final String KEY_URL = "site_url";

    private AppSettings() { }

    public static String getSiteUrl(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, DEFAULT_URL);
    }

    public static boolean setSiteUrl(Context context, String value) {
        String normalized = normalize(value);
        if (normalized == null) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_URL, normalized).apply();
        return true;
    }

    public static String host(Context context) {
        return Uri.parse(getSiteUrl(context)).getHost();
    }

    private static String normalize(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("https://")) return null;
        try {
            Uri uri = Uri.parse(text);
            if (uri.getHost() == null || uri.getHost().isEmpty()) return null;
            return text.endsWith("/") ? text : text + "/";
        } catch (Exception ignored) {
            return null;
        }
    }
}
