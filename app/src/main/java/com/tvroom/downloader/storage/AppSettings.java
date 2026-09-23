package com.tvroom.downloader.storage;

import android.content.Context;
import android.net.Uri;

import androidx.appcompat.app.AppCompatDelegate;

public final class AppSettings {
    public static final String DEFAULT_URL = "https://tvroom21.org/";
    public static final String THEME_WHITE = "white";
    public static final String THEME_BLACK = "black";
    private static final String PREFS = "tvroom_settings";
    private static final String KEY_URL = "site_url";
    private static final String KEY_THEME = "theme";

    private AppSettings() { }

    public static int getDownloadColumns(Context context) {
        int columns = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("download_columns", 1);
        return Math.max(1, Math.min(3, columns));
    }

    public static void setDownloadColumns(Context context, int columns) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("download_columns", Math.max(1, Math.min(3, columns))).apply();
    }

    public static String getTheme(Context context) {
        String theme = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, THEME_WHITE);
        return THEME_BLACK.equals(theme) ? THEME_BLACK : THEME_WHITE;
    }

    public static boolean isBlackTheme(Context context) {
        return THEME_BLACK.equals(getTheme(context));
    }

    /** @return true when the stored theme actually changed. */
    public static boolean setTheme(Context context, String theme) {
        String normalized = THEME_BLACK.equals(theme) ? THEME_BLACK : THEME_WHITE;
        if (normalized.equals(getTheme(context))) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_THEME, normalized).apply();
        return true;
    }

    public static void applyNightMode(Context context) {
        int mode = isBlackTheme(context)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO;
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
    }

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
