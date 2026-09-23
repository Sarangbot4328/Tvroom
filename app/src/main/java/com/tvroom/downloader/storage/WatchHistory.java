package com.tvroom.downloader.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.tvroom.downloader.data.VideoItem;

import java.io.File;
import java.util.List;

/** Last time each downloaded file was opened in the player. Resume position is separate. */
public final class WatchHistory {
    private static final String PREFS = "video_watch_history";
    private static final String POSITION_PREFS = "video_playback_positions";
    private static final long RESUME_FALLBACK_MS = 1_000L;

    private WatchHistory() { }

    public static void mark(Context context, String path) {
        String key = absolute(path);
        if (key == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(key, System.currentTimeMillis()).apply();
    }

    /** Most recently watched video in this playlist, or -1 when nothing has been opened. */
    public static int lastWatchedIndex(Context context, List<VideoItem> videos) {
        SharedPreferences history = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences positions = context.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE);
        int best = -1;
        long bestAt = 0L;
        int resumeFallback = -1;
        for (int i = 0; i < videos.size(); i++) {
            String key = absolute(videos.get(i).filePath);
            if (key == null) continue;
            long at = history.getLong(key, 0L);
            if (at > bestAt) {
                bestAt = at;
                best = i;
            }
            if (positions.getLong(key, 0L) >= RESUME_FALLBACK_MS) resumeFallback = i;
        }
        return best >= 0 ? best : resumeFallback;
    }

    private static String absolute(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            return new File(path).getAbsolutePath();
        } catch (Exception ignored) {
            return path;
        }
    }
}
