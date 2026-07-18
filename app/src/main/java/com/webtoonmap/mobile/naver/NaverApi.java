package com.webtoonmap.mobile.naver;

import org.json.JSONArray;
import org.json.JSONObject;

import com.webtoonmap.mobile.network.NetworkRetry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NaverApi {
    public static final String ORIGIN = "https://comic.naver.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";
    private static final Pattern IMAGE_PATTERN = Pattern.compile(
            "https://image-comic\\.pstatic\\.net/(?:webtoon|mobilewebimg)/\\d+/\\d+/[^\\\"'\\s<>]+?\\.(?:jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);

    public static final class SeriesInfo {
        public final String titleId, title, description, thumbnailUrl, tags, segment;
        public SeriesInfo(String titleId, String title, String description, String thumbnailUrl,
                          String tags, String segment) {
            this.titleId = titleId; this.title = title; this.description = description;
            this.thumbnailUrl = thumbnailUrl; this.tags = tags; this.segment = segment;
        }
    }

    public static final class EpisodeMeta {
        public final int number;
        public final String title;
        public final boolean locked;
        public EpisodeMeta(int number, String title, boolean locked) {
            this.number = number; this.title = title; this.locked = locked;
        }
    }

    private NaverApi() { }

    public static SeriesInfo fetchSeriesInfo(String titleId, String cookie) throws Exception {
        JSONObject data = new JSONObject(getText(
                ORIGIN + "/api/article/list/info?titleId=" + titleId, listUrl(titleId, "webtoon"), cookie));
        List<String> tags = new ArrayList<>();
        JSONArray array = data.optJSONArray("curationTagList");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject tagObject = array.optJSONObject(i);
                if (tagObject == null) continue;
                String tag = tagObject.optString("tagName").replaceFirst("^#", "").trim();
                if (!tag.isEmpty()) tags.add(tag);
            }
        }
        String level = data.optString("webtoonLevelCode", "WEBTOON");
        String segment = "BEST_CHALLENGE".equals(level) ? "bestChallenge" :
                ("CHALLENGE".equals(level) ? "challenge" : "webtoon");
        String thumb = data.optString("thumbnailUrl", "");
        if (thumb.isEmpty()) thumb = data.optString("posterThumbnailUrl", "");
        return new SeriesInfo(titleId, data.optString("titleName", "웹툰"),
                data.optString("synopsis", ""), thumb, String.join(", ", tags), segment);
    }

    public static List<EpisodeMeta> fetchFreeEpisodes(String titleId, String cookie) throws Exception {
        JSONObject first = new JSONObject(getText(
                ORIGIN + "/api/article/list?titleId=" + titleId + "&page=1", listUrl(titleId, "webtoon"), cookie));
        JSONObject pageInfo = first.optJSONObject("pageInfo");
        int pages = Math.max(1, pageInfo == null ? 1 : pageInfo.optInt("totalPages", 1));
        List<EpisodeMeta> all = new ArrayList<>();
        appendEpisodes(first, all);
        for (int page = 2; page <= pages; page++) {
            JSONObject data = new JSONObject(getText(
                    ORIGIN + "/api/article/list?titleId=" + titleId + "&page=" + page,
                    listUrl(titleId, "webtoon"), cookie));
            appendEpisodes(data, all);
        }
        all.removeIf(ep -> ep.locked || ep.number <= 0);
        all.sort(Comparator.comparingInt(ep -> ep.number));
        return all;
    }

    private static void appendEpisodes(JSONObject data, List<EpisodeMeta> out) {
        JSONArray list = data.optJSONArray("articleList");
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject ep = list.optJSONObject(i);
            if (ep == null) continue;
            out.add(new EpisodeMeta(ep.optInt("no"), ep.optString("subtitle"),
                    ep.optBoolean("thumbnailLock", false)));
        }
    }

    public static List<String> fetchEpisodeImages(String titleId, int episode, String segment,
                                                   String cookie) throws Exception {
        String detail = ORIGIN + "/" + segment + "/detail?titleId=" + titleId + "&no=" + episode;
        String html = getText(detail, listUrl(titleId, segment), cookie);
        Matcher matcher = IMAGE_PATTERN.matcher(html.replace("\\/", "/"));
        Set<String> urls = new LinkedHashSet<>();
        String webtoonMarker = "/webtoon/" + titleId + "/" + episode + "/";
        String mobileMarker = "/mobilewebimg/" + titleId + "/" + episode + "/";
        while (matcher.find()) {
            String value = matcher.group().replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&");
            if (!value.contains(webtoonMarker) && !value.contains(mobileMarker)) continue;
            if (value.matches("(?i).*?(thumbnail|age_|img-ctguide|static/agerate).*")) continue;
            urls.add(value);
        }
        if (urls.isEmpty()) throw new IOException(episode + "화 이미지를 찾지 못했습니다.");
        return new ArrayList<>(urls);
    }

    public static byte[] downloadBytes(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie, "image/*,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("이미지 HTTP " + code);
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedIOException("이미지 다운로드 중단");
                        }
                        out.write(buffer, 0, n);
                    }
                    return out.toByteArray();
                }
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        
        });
    }

    private static String getText(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie, "application/json,text/html,*/*");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("네이버 응답 오류 " + code);
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedIOException("네이버 요청 중단");
                        }
                        out.write(buffer, 0, n);
                    }
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        
        });
    }

    private static HttpURLConnection open(String url, String referer, String cookie, String accept) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(0);
        conn.setReadTimeout(0);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", accept);
        if (referer != null && !referer.isEmpty()) conn.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        return NetworkRetry.track(conn);
    }

    public static String listUrl(String titleId, String segment) {
        return ORIGIN + "/" + segment + "/list?titleId=" + titleId;
    }
}




