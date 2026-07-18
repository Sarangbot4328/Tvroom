package com.tvroom.downloader.web;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CaptureState {
    private String pageUrl = "";
    private String title = "";
    private String thumbnailUrl = "";
    private String cookie = "";
    private String userAgent = "";
    private String keyHex = "";
    private String ivHex = "";
    private final Set<String> m3u8 = new LinkedHashSet<>();
    private final Set<String> segments = new LinkedHashSet<>();

    public synchronized void reset(String url) {
        pageUrl = url == null ? "" : url;
        title = "";
        thumbnailUrl = "";
        keyHex = "";
        ivHex = "";
        m3u8.clear();
        segments.clear();
    }

    public synchronized void setSession(String page, String cookies, String ua) {
        if (page != null) pageUrl = page;
        cookie = cookies == null ? "" : cookies;
        userAgent = ua == null ? "" : ua;
    }

    public synchronized void acceptMessage(String raw) {
        try {
            JSONObject message = new JSONObject(raw);
            String type = message.optString("type");
            if ("url".equals(type)) rememberUrl(message.optString("url"));
            else if ("crypto".equals(type)) {
                String key = message.optString("key");
                String iv = message.optString("iv");
                if (key.matches("(?i)[0-9a-f]{32,}")) keyHex = key.substring(0, 32);
                if (iv.matches("(?i)[0-9a-f]{32,}")) ivHex = iv.substring(0, 32);
            } else if ("meta".equals(type)) {
                String nextTitle = message.optString("title").trim();
                String nextThumbnail = message.optString("thumbnail").trim();
                if (!nextTitle.isEmpty()) title = nextTitle;
                if (nextThumbnail.startsWith("http")) thumbnailUrl = nextThumbnail;
            }
        } catch (Exception ignored) { }
    }

    public synchronized void rememberUrl(String url) {
        if (url == null || !url.startsWith("http")) return;
        String lower = url.toLowerCase();
        if (lower.contains("m3u8")) m3u8.add(url);
        if (lower.contains("segment_list") || lower.matches(".*\\.(ts|m4s)([?#].*)?$")) segments.add(url);
    }

    public synchronized boolean ready() {
        return !m3u8.isEmpty() || (!segments.isEmpty() && !keyHex.isEmpty());
    }

    public synchronized List<String> knownUrls() {
        List<String> out = new ArrayList<>(m3u8);
        out.addAll(segments);
        return out;
    }

    public synchronized Snapshot snapshot() {
        String resolvedTitle = title;
        if (resolvedTitle.isEmpty()) {
            String last = Uri.parse(pageUrl).getLastPathSegment();
            resolvedTitle = last == null ? "티비룸 영상" : Uri.decode(last).replace('-', ' ');
        }
        return new Snapshot(idFor(pageUrl), resolvedTitle, pageUrl, thumbnailUrl, cookie, userAgent,
                keyHex, ivHex, new ArrayList<>(m3u8), new ArrayList<>(segments));
    }

    private static String idFor(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 10; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public static final class Snapshot {
        public final String id, title, pageUrl, thumbnailUrl, cookie, userAgent, keyHex, ivHex;
        public final List<String> m3u8Urls, segmentUrls;

        Snapshot(String id, String title, String pageUrl, String thumbnailUrl, String cookie,
                 String userAgent, String keyHex, String ivHex, List<String> m3u8Urls,
                 List<String> segmentUrls) {
            this.id = id; this.title = title; this.pageUrl = pageUrl; this.thumbnailUrl = thumbnailUrl;
            this.cookie = cookie; this.userAgent = userAgent; this.keyHex = keyHex; this.ivHex = ivHex;
            this.m3u8Urls = m3u8Urls; this.segmentUrls = segmentUrls;
        }

        public String toJson() {
            try {
                JSONObject object = new JSONObject();
                object.put("id", id).put("title", title).put("pageUrl", pageUrl)
                        .put("thumbnailUrl", thumbnailUrl).put("cookie", cookie)
                        .put("userAgent", userAgent).put("keyHex", keyHex).put("ivHex", ivHex)
                        .put("m3u8Urls", new JSONArray(m3u8Urls)).put("segmentUrls", new JSONArray(segmentUrls));
                return object.toString();
            } catch (Exception error) { throw new IllegalStateException(error); }
        }

        public static Snapshot fromJson(String raw) throws Exception {
            JSONObject object = new JSONObject(raw);
            return new Snapshot(object.getString("id"), object.getString("title"), object.getString("pageUrl"),
                    object.optString("thumbnailUrl"), object.optString("cookie"), object.optString("userAgent"),
                    object.optString("keyHex"), object.optString("ivHex"), list(object.optJSONArray("m3u8Urls")),
                    list(object.optJSONArray("segmentUrls")));
        }

        private static List<String> list(JSONArray array) {
            List<String> out = new ArrayList<>();
            if (array != null) for (int i = 0; i < array.length(); i++) out.add(array.optString(i));
            return out;
        }
    }
}
