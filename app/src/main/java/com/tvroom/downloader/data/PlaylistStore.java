package com.tvroom.downloader.data;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Separate preferences hold memberships only. No video/database deletion API is used here. */
public final class PlaylistStore {
    private final SharedPreferences prefs;
    public PlaylistStore(Context context) { prefs = context.getSharedPreferences("video_playlists_v1", Context.MODE_PRIVATE); }
    PlaylistStore(SharedPreferences preferences) { prefs = preferences; }
    public boolean isPlaylistMode() { return prefs.getBoolean("playlist_mode", false); }
    public void setPlaylistMode(boolean enabled) { prefs.edit().putBoolean("playlist_mode", enabled).apply(); }
    public static boolean playable(VideoItem video) {
        return "complete".equals(video.status) && video.filePath != null && new File(video.filePath).isFile();
    }
    public List<VideoPlaylist> list(List<VideoItem> library) {
        List<VideoItem> available = new ArrayList<>();
        Map<String, VideoItem> byId = new HashMap<>();
        Set<String> excluded = prefs.getStringSet("excluded", new HashSet<>());
        Set<String> hidden = prefs.getStringSet("hidden", new HashSet<>());
        for (VideoItem video : library) if (playable(video)) {
            byId.put(video.id, video);
            if (!excluded.contains(video.id)) available.add(video);
        }
        List<VideoPlaylist> result = new ArrayList<>();
        for (VideoPlaylist group : VideoPlaylist.automatic(available)) if (!hidden.contains(group.id)) result.add(group);
        JSONArray manual = manual();
        for (int i = 0; i < manual.length(); i++) {
            JSONObject row = manual.optJSONObject(i);
            if (row == null) continue;
            List<VideoItem> items = new ArrayList<>();
            JSONArray ids = row.optJSONArray("ids");
            if (ids != null) for (int j = 0; j < ids.length(); j++) {
                VideoItem video = byId.get(ids.optString(j));
                if (video != null) items.add(video);
            }
            result.add(new VideoPlaylist(row.optString("id"), row.optString("title"), false, items));
        }
        return result;
    }
    private JSONArray manual() {
        try { return new JSONArray(prefs.getString("manual", "[]")); }
        catch (Exception error) { throw new IllegalStateException("재생목록 정보를 읽지 못했습니다.", error); }
    }
    public void save(String id, String title, List<VideoItem> videos) {
        if (title.trim().isEmpty()) throw new IllegalArgumentException("재생목록 이름을 입력해 주세요.");
        JSONArray all = manual(), next = new JSONArray();
        try {
            for (int i = 0; i < all.length(); i++) if (!all.getJSONObject(i).optString("id").equals(id)) next.put(all.getJSONObject(i));
            JSONArray ids = new JSONArray();
            Set<String> seen = new LinkedHashSet<>();
            for (VideoItem item : videos) if (seen.add(item.id)) ids.put(item.id);
            next.put(new JSONObject().put("id", id == null ? "manual:" + UUID.randomUUID() : id)
                    .put("title", title.trim()).put("ids", ids));
            prefs.edit().putString("manual", next.toString()).apply();
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    public void delete(VideoPlaylist playlist) {
        if (playlist.automatic) {
            Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", new HashSet<>()));
            hidden.add(playlist.id); prefs.edit().putStringSet("hidden", hidden).apply();
        } else {
            JSONArray all = manual(), next = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject row = all.optJSONObject(i);
                if (row != null && !playlist.id.equals(row.optString("id"))) next.put(row);
            }
            prefs.edit().putString("manual", next.toString()).apply();
        }
    }
    public void exclude(VideoItem video) {
        Set<String> excluded = new HashSet<>(prefs.getStringSet("excluded", new HashSet<>()));
        excluded.add(video.id); prefs.edit().putStringSet("excluded", excluded).apply();
    }
    public void restoreAutomatic() { prefs.edit().remove("hidden").remove("excluded").apply(); }
}
