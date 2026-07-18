package com.webtoonmap.mobile.download;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class SourceJobStore {
    private static final String PREFS = "source_download_jobs";

    public static final class Job {
        public final String key, source, relativeUrl, remoteId, kind;

        Job(String key, String source, String relativeUrl, String remoteId, String kind) {
            this.key = key;
            this.source = source;
            this.relativeUrl = relativeUrl;
            this.remoteId = remoteId;
            this.kind = kind;
        }

        public String pageUrl(String baseUrl) {
            String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
            return base + (relativeUrl.startsWith("/") ? relativeUrl : "/" + relativeUrl);
        }
    }

    private SourceJobStore() { }

    public static String keyFor(String source, String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((source + ":" + identity).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) hex.append(String.format("%02x", digest[i] & 0xff));
            return source + "_" + hex;
        } catch (Exception e) {
            return source + "_" + Integer.toHexString(identity.hashCode());
        }
    }

    public static void register(Context context, String key, String source, String pageUrl,
                                String remoteId, String kind) {
        Uri uri = Uri.parse(pageUrl);
        String relative = uri.getEncodedPath();
        if (relative == null || relative.isEmpty()) relative = "/";
        if (uri.getEncodedQuery() != null) relative += "?" + uri.getEncodedQuery();
        try {
            JSONObject json = new JSONObject();
            json.put("source", source);
            json.put("relative_url", relative);
            json.put("remote_id", remoteId == null ? "" : remoteId);
            json.put("kind", kind == null ? "" : kind);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(key, json.toString()).commit();
        } catch (Exception ignored) { }
    }

    public static Job get(Context context, String key) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key, null);
        if (raw == null) return null;
        try {
            JSONObject json = new JSONObject(raw);
            return new Job(key, json.optString("source"), json.optString("relative_url", "/"),
                    json.optString("remote_id"), json.optString("kind"));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void remove(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply();
    }
}
