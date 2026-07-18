package com.webtoonmap.mobile.joatoon;

import android.text.Html;

import com.webtoonmap.mobile.network.NetworkRetry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JoatoonApi {
    public static final String KEY_PREFIX = "joatoon_";
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";
    private static final Pattern IMG_TAG = Pattern.compile("(?is)<img\\b[^>]*>");
    private static final Pattern IMAGE_ATTR = Pattern.compile(
            "(?is)(?:data-src|data-original|data-lazy-src|data-url|src)\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");

    public static final class SeriesInfo {
        public final String seriesId, title, description, thumbnailUrl, tags, pageUrl;
        public final List<EpisodeMeta> episodes;

        public SeriesInfo(String seriesId, String title, String description, String thumbnailUrl,
                          String tags, String pageUrl, List<EpisodeMeta> episodes) {
            this.seriesId = seriesId;
            this.title = title;
            this.description = description;
            this.thumbnailUrl = thumbnailUrl;
            this.tags = tags;
            this.pageUrl = pageUrl;
            this.episodes = episodes;
        }
    }

    public static final class EpisodeMeta {
        public final int number;
        public final String title, url;

        public EpisodeMeta(int number, String title, String url) {
            this.number = number;
            this.title = title;
            this.url = url;
        }
    }

    private JoatoonApi() { }

    public static String seriesKey(String seriesId) {
        return KEY_PREFIX + seriesId;
    }

    public static boolean isSeriesKey(String key) {
        return key != null && key.matches(KEY_PREFIX + "\\d+");
    }

    public static String remoteId(String key) {
        return isSeriesKey(key) ? key.substring(KEY_PREFIX.length()) : null;
    }

    public static String seriesUrl(String baseUrl, String seriesId) {
        return trimBase(baseUrl) + "/toon/w/" + seriesId + "?cso=asc";
    }

    public static SeriesInfo fetchSeriesInfo(String baseUrl, String seriesId, String cookie) throws Exception {
        String pageUrl = seriesUrl(baseUrl, seriesId);
        String html = getText(pageUrl, trimBase(baseUrl), cookie);
        String title = cleanTitle(firstNonEmpty(
                findMeta(html, "og:title"),
                tagText(html, "h1"),
                tagText(html, "h2"),
                tagText(html, "title")));
        if (title.isEmpty()) title = "조아툰-" + seriesId;
        String description = firstNonEmpty(findMeta(html, "og:description"), "");
        String thumbnail = absoluteUrl(pageUrl, findMeta(html, "og:image"));

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Matcher tagMatcher = Pattern.compile(
                "(?is)<(?:span|a)\\b[^>]*class=[\\\"'][^\\\"']*genre-badge[^\\\"']*[\\\"'][^>]*>(.*?)</(?:span|a)>")
                .matcher(html);
        while (tagMatcher.find()) {
            String tag = stripHtml(tagMatcher.group(1));
            if (!tag.isEmpty() && tag.length() <= 30) tags.add(tag);
        }

        Map<Integer, EpisodeMeta> episodes = new TreeMap<>();
        Pattern linkPattern = Pattern.compile("(?is)<a\\b[^>]*href=[\\\"']([^\\\"']*/toon/w/" +
                Pattern.quote(seriesId) + "/c/(\\d+)[^\\\"']*)[\\\"'][^>]*>(.*?)</a>");
        Matcher links = linkPattern.matcher(html);
        while (links.find()) {
            String text = stripHtml(links.group(3));
            Matcher numberMatcher = Pattern.compile("(\\d{1,4})\\s*(?:화|회)").matcher(text);
            if (!numberMatcher.find()) continue;
            int number = Integer.parseInt(numberMatcher.group(1));
            if (number <= 0 || number > 5000) continue;
            String url = absoluteUrl(pageUrl, links.group(1));
            if (url != null) episodes.putIfAbsent(number,
                    new EpisodeMeta(number, number + "화", url));
        }
        return new SeriesInfo(seriesId, title, stripHtml(description), thumbnail,
                String.join(", ", tags), pageUrl, new ArrayList<>(episodes.values()));
    }

    public static List<String> fetchEpisodeImages(String episodeUrl, String cookie) throws Exception {
        String html = getText(episodeUrl, episodeUrl, cookie);
        LinkedHashSet<String> images = new LinkedHashSet<>();
        Matcher tags = IMG_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            if (!tag.toLowerCase(Locale.US).contains("data-loading-img")) continue;
            Matcher attr = IMAGE_ATTR.matcher(tag);
            if (!attr.find()) continue;
            String url = absoluteUrl(episodeUrl, attr.group(1));
            if (isWebtoonImage(url)) images.add(url);
        }
        if (images.isEmpty()) {
            Matcher fallback = IMAGE_ATTR.matcher(html);
            while (fallback.find()) {
                String url = absoluteUrl(episodeUrl, fallback.group(1));
                if (isWebtoonImage(url)) images.add(url);
            }
        }
        if (images.isEmpty()) throw new IOException("회차 이미지를 찾지 못했습니다.");
        return new ArrayList<>(images);
    }

    public static byte[] downloadBytes(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie, "image/*,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("조아툰 이미지 HTTP " + code);
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = in.read(buffer)) >= 0) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedIOException("조아툰 이미지 다운로드 중단");
                        }
                        out.write(buffer, 0, count);
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
            HttpURLConnection conn = open(url, referer, cookie, "text/html,application/xhtml+xml,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("조아툰 응답 오류 " + code);
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = in.read(buffer)) >= 0) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedIOException("조아툰 요청 중단");
                        }
                        out.write(buffer, 0, count);
                    }
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        
        });
    }

    private static HttpURLConnection open(String url, String referer, String cookie, String accept)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(0);
        conn.setReadTimeout(0);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", accept);
        if (referer != null) conn.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        return NetworkRetry.track(conn);
    }

    private static String findMeta(String html, String key) {
        String quoted = Pattern.quote(key);
        Pattern first = Pattern.compile("(?is)<meta\\b[^>]*(?:property|name)=[\\\"']" + quoted +
                "[\\\"'][^>]*content=[\\\"']([^\\\"']*)[\\\"'][^>]*>");
        Matcher matcher = first.matcher(html);
        if (matcher.find()) return matcher.group(1);
        Pattern reverse = Pattern.compile("(?is)<meta\\b[^>]*content=[\\\"']([^\\\"']*)[\\\"'][^>]*" +
                "(?:property|name)=[\\\"']" + quoted + "[\\\"'][^>]*>");
        matcher = reverse.matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String tagText(String html, String tag) {
        Matcher matcher = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(html);
        return matcher.find() ? stripHtml(matcher.group(1)) : "";
    }

    private static String stripHtml(String value) {
        if (value == null || value.isEmpty()) return "";
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
                .replaceAll("\\s+", " ").trim();
    }

    private static String cleanTitle(String value) {
        return stripHtml(value).replaceFirst("(?i)\\s*-\\s*조아툰(?:\\s+JOATOON)?.*$", "").trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static String absoluteUrl(String base, String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return new URL(new URL(base), raw.replace("&amp;", "&")).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isWebtoonImage(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("/toonimg/") && !lower.contains("/storage/ad/") &&
                lower.matches(".*\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$");
    }

    private static String trimBase(String baseUrl) {
        return baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }
}




