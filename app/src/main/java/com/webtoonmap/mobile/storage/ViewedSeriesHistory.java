package com.webtoonmap.mobile.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.webtoonmap.mobile.data.SeriesItem;

import org.json.JSONObject;

public final class ViewedSeriesHistory {
    private static final String PREFS = "viewed_series_history";

    private ViewedSeriesHistory() { }

    public static final class Entry {
        public final String title;
        public final long viewedAt;

        Entry(String title, long viewedAt) {
            this.title = title;
            this.viewedAt = viewedAt;
        }
    }

    public static void record(Context context, SeriesItem series) {
        if (series == null || series.titleId == null || series.titleId.trim().isEmpty()) return;
        try {
            JSONObject value = new JSONObject();
            value.put("title", series.title == null ? "" : series.title);
            value.put("viewed_at", System.currentTimeMillis());
            preferences(context).edit()
                    .putString(series.titleId, value.toString())
                    .apply();
        } catch (Exception ignored) { }
    }

    public static Entry get(Context context, String titleId) {
        if (titleId == null || titleId.trim().isEmpty()) return null;
        String raw = preferences(context).getString(titleId, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject value = new JSONObject(raw);
            String title = value.optString("title", "").trim();
            return new Entry(title, value.optLong("viewed_at", 0L));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
