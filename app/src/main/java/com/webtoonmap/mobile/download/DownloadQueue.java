package com.webtoonmap.mobile.download;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DownloadQueue {
    private static final String PREFS = "download_queue";
    private static final String KEY_ITEMS = "title_ids";
    private static final Object LOCK = new Object();

    private DownloadQueue() { }

    static boolean add(Context context, String titleId) {
        synchronized (LOCK) {
            List<String> items = read(context);
            if (items.contains(titleId)) return false;
            items.add(titleId);
            write(context, items);
            return true;
        }
    }

    static String peek(Context context) {
        synchronized (LOCK) {
            List<String> items = read(context);
            return items.isEmpty() ? null : items.get(0);
        }
    }

    static void remove(Context context, String titleId) {
        synchronized (LOCK) {
            List<String> items = read(context);
            items.remove(titleId);
            write(context, items);
        }
    }

    static boolean contains(Context context, String titleId) {
        synchronized (LOCK) {
            return read(context).contains(titleId);
        }
    }

    static int size(Context context) {
        synchronized (LOCK) {
            return read(context).size();
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            write(context, new ArrayList<>());
        }
    }

    private static List<String> read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> stored = prefs.getStringSet(KEY_ITEMS, null);
        String order = prefs.getString(KEY_ITEMS + "_order", "");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (!order.isEmpty()) {
            for (String value : order.split(",")) {
                if (isValidKey(value) && (stored == null || stored.contains(value))) result.add(value);
            }
        }
        if (stored != null) for (String value : stored) if (isValidKey(value)) result.add(value);
        return new ArrayList<>(result);
    }

    private static boolean isValidKey(String value) {
        return value != null && value.matches(
                "(?:\\d+|joatoon_\\d+|manhwabang_[0-9a-f]+|ililtoon_[0-9a-f]+|" +
                        "blacktoon_[0-9a-f]+|wolfdot_[0-9a-f]+|hitomi_\\d+)");
    }

    private static void write(Context context, List<String> items) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(items);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_ITEMS, unique)
                .putString(KEY_ITEMS + "_order", String.join(",", unique))
                .commit();
    }
}
