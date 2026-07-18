package com.webtoonmap.mobile.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class SourceSettings {
    public static final String SOURCE_NAVER = "naver";
    public static final String SOURCE_JOATOON = "joatoon";
    public static final String SOURCE_MANHWABANG = "manhwabang";
    public static final String SOURCE_ILILTOON = "ililtoon";
    public static final String SOURCE_BLACKTOON = "blacktoon";
    public static final String SOURCE_WOLFDOT = "wolfdot";
    public static final String SOURCE_HITOMI = "hitomi";
    public static final String DEFAULT_JOATOON_URL = "https://joa-new.com";
    public static final String DEFAULT_MANHWABANG_URL = "https://manhwabang.net";
    public static final String DEFAULT_ILILTOON_URL = "https://11toon148.com";
    public static final String DEFAULT_BLACKTOON_URL = "https://blacktoon416.com";
    public static final String DEFAULT_WOLFDOT_URL = "https://wfwf421.com";
    public static final String DEFAULT_HITOMI_URL = "https://hitomi.la";
    public static final String VIEW_MODE_SCROLL = "scroll";
    public static final String VIEW_MODE_PAGE = "page";
    public static final String VIEW_MODE_PAGE_FIT = "page_fit";
    private static final String PREFS = "source_settings";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_JOATOON_URL = "joatoon_url";
    private static final String KEY_MANHWABANG_URL = "manhwabang_url";
    private static final String KEY_ILILTOON_URL = "ililtoon_url";
    private static final String KEY_BLACKTOON_URL = "blacktoon_url";
    private static final String KEY_WOLFDOT_URL = "wolfdot_url";
    private static final String KEY_HITOMI_URL = "hitomi_url";
    private static final String KEY_VIEW_MODE = "view_mode";
    private static final String KEY_LOW_DATA_MODE = "low_data_mode";
    private static final String KEY_LOW_DATA_RESTART_MINUTES = "low_data_restart_minutes";
    public static final int DEFAULT_LOW_DATA_RESTART_MINUTES = 5;
    private static final int MIN_LOW_DATA_RESTART_MINUTES = 1;
    private static final int MAX_LOW_DATA_RESTART_MINUTES = 1440;
    private static final String KEY_AUTO_ADVANCE = "auto_advance";
    private static final String KEY_AUTO_ADVANCE_SECONDS = "auto_advance_seconds";
    public static final int DEFAULT_AUTO_ADVANCE_SECONDS = 2;
    private static final int MIN_AUTO_ADVANCE_SECONDS = 1;
    private static final int MAX_AUTO_ADVANCE_SECONDS = 3600;

    private SourceSettings() { }

    public static String getViewMode(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VIEW_MODE, VIEW_MODE_SCROLL);
        if (VIEW_MODE_PAGE.equals(value)) return VIEW_MODE_PAGE;
        if (VIEW_MODE_PAGE_FIT.equals(value)) return VIEW_MODE_PAGE_FIT;
        return VIEW_MODE_SCROLL;
    }

    public static boolean isPageMode(Context context) {
        return !VIEW_MODE_SCROLL.equals(getViewMode(context));
    }

    public static boolean isPageWidthMode(Context context) {
        return VIEW_MODE_PAGE.equals(getViewMode(context));
    }

    public static void setViewMode(Context context, String mode) {
        String value;
        if (VIEW_MODE_PAGE.equals(mode)) value = VIEW_MODE_PAGE;
        else if (VIEW_MODE_PAGE_FIT.equals(mode)) value = VIEW_MODE_PAGE_FIT;
        else value = VIEW_MODE_SCROLL;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_VIEW_MODE, value).apply();
    }

    public static boolean isLowDataMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOW_DATA_MODE, false);
    }

    public static void setLowDataMode(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_LOW_DATA_MODE, enabled).apply();
    }

    public static int getLowDataRestartMinutes(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_LOW_DATA_RESTART_MINUTES, DEFAULT_LOW_DATA_RESTART_MINUTES);
        if (value < MIN_LOW_DATA_RESTART_MINUTES || value > MAX_LOW_DATA_RESTART_MINUTES) {
            return DEFAULT_LOW_DATA_RESTART_MINUTES;
        }
        return value;
    }

    public static boolean setLowDataRestartMinutes(Context context, int minutes) {
        if (minutes < MIN_LOW_DATA_RESTART_MINUTES ||
                minutes > MAX_LOW_DATA_RESTART_MINUTES) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_LOW_DATA_RESTART_MINUTES, minutes).apply();
        return true;
    }

    public static boolean isAutoAdvanceEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_ADVANCE, false);
    }

    public static void setAutoAdvanceEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_ADVANCE, enabled).apply();
    }

    public static int getAutoAdvanceSeconds(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_AUTO_ADVANCE_SECONDS, DEFAULT_AUTO_ADVANCE_SECONDS);
        if (value < MIN_AUTO_ADVANCE_SECONDS || value > MAX_AUTO_ADVANCE_SECONDS) {
            return DEFAULT_AUTO_ADVANCE_SECONDS;
        }
        return value;
    }

    public static boolean setAutoAdvanceSeconds(Context context, int seconds) {
        if (seconds < MIN_AUTO_ADVANCE_SECONDS ||
                seconds > MAX_AUTO_ADVANCE_SECONDS) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_AUTO_ADVANCE_SECONDS, seconds).apply();
        return true;
    }

    public static String getSource(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SOURCE, SOURCE_NAVER);
    }

    public static boolean isJoatoon(Context context) {
        return SOURCE_JOATOON.equals(getSource(context));
    }

    public static void setSource(Context context, String source) {
        String value = isKnownSource(source) ? source : SOURCE_NAVER;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SOURCE, value).apply();
    }

    public static String getJoatoonUrl(Context context) {
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JOATOON_URL, DEFAULT_JOATOON_URL);
        String normalized = normalizeUrl(stored);
        return normalized == null ? DEFAULT_JOATOON_URL : normalized;
    }

    public static boolean setJoatoonUrl(Context context, String raw) {
        return setUrl(context, KEY_JOATOON_URL, raw);
    }

    public static String getManhwabangUrl(Context context) {
        return getUrl(context, KEY_MANHWABANG_URL, DEFAULT_MANHWABANG_URL);
    }

    public static boolean setManhwabangUrl(Context context, String raw) {
        return setUrl(context, KEY_MANHWABANG_URL, raw);
    }

    public static String getIliltoonUrl(Context context) {
        return getUrl(context, KEY_ILILTOON_URL, DEFAULT_ILILTOON_URL);
    }

    public static boolean setIliltoonUrl(Context context, String raw) {
        return setUrl(context, KEY_ILILTOON_URL, raw);
    }

    public static String getBlacktoonUrl(Context context) {
        return getUrl(context, KEY_BLACKTOON_URL, DEFAULT_BLACKTOON_URL);
    }

    public static boolean setBlacktoonUrl(Context context, String raw) {
        return setUrl(context, KEY_BLACKTOON_URL, raw);
    }

    public static String getWolfdotUrl(Context context) {
        return getUrl(context, KEY_WOLFDOT_URL, DEFAULT_WOLFDOT_URL);
    }

    public static boolean setWolfdotUrl(Context context, String raw) {
        return setUrl(context, KEY_WOLFDOT_URL, raw);
    }

    public static String getHitomiUrl(Context context) {
        return getUrl(context, KEY_HITOMI_URL, DEFAULT_HITOMI_URL);
    }

    public static boolean setHitomiUrl(Context context, String raw) {
        return setUrl(context, KEY_HITOMI_URL, raw);
    }

    public static String channelLabel(Context context) {
        String source = getSource(context);
        if (SOURCE_JOATOON.equals(source)) return "조아툰";
        if (SOURCE_MANHWABANG.equals(source)) return "만화방";
        if (SOURCE_ILILTOON.equals(source)) return "일일툰";
        if (SOURCE_BLACKTOON.equals(source)) return "블랙툰";
        if (SOURCE_WOLFDOT.equals(source)) return "늑대닷컴";
        if (SOURCE_HITOMI.equals(source)) return "히토미";
        return "네이버 웹툰";
    }

    public static String homeUrl(Context context) {
        String source = getSource(context);
        if (SOURCE_JOATOON.equals(source)) return getJoatoonUrl(context);
        if (SOURCE_MANHWABANG.equals(source)) return getManhwabangUrl(context) + "/webtoon/list?type=def";
        if (SOURCE_ILILTOON.equals(source)) return getIliltoonUrl(context);
        if (SOURCE_BLACKTOON.equals(source)) return getBlacktoonUrl(context);
        if (SOURCE_WOLFDOT.equals(source)) return getWolfdotUrl(context);
        if (SOURCE_HITOMI.equals(source)) return getHitomiUrl(context);
        return "https://comic.naver.com/webtoon";
    }

    private static boolean setUrl(Context context, String key, String raw) {
        String normalized = normalizeUrl(raw);
        if (normalized == null) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key, normalized).apply();
        return true;
    }

    private static String getUrl(Context context, String key, String fallback) {
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key, fallback);
        String normalized = normalizeUrl(stored);
        return normalized == null ? fallback : normalized;
    }

    private static boolean isKnownSource(String source) {
        return SOURCE_NAVER.equals(source) || SOURCE_JOATOON.equals(source) ||
                SOURCE_MANHWABANG.equals(source) || SOURCE_ILILTOON.equals(source) ||
                SOURCE_BLACKTOON.equals(source) || SOURCE_WOLFDOT.equals(source) ||
                SOURCE_HITOMI.equals(source);
    }

    public static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.trim().isEmpty()) return null;
            if (!"https".equalsIgnoreCase(scheme)) return null;
            String authority = uri.getEncodedAuthority();
            return "https://" + authority;
        } catch (Exception ignored) {
            return null;
        }
    }
}
