package com.webtoonmap.mobile.manhwabang;

import android.text.Html;

import com.webtoonmap.mobile.joatoon.JoatoonApi;

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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ManhwabangApi {
    public static final String KEY_PREFIX = "manhwabang_";
    private static final Pattern CDN_IMAGE = Pattern.compile(
            "(?i)https?:\\\\?/\\\\?/[^\\\"'\\\\<>\\s]*cdn-content\\.sbs[^\\\"'\\\\<>\\s]*/(?:webtoon|manhua)/images/[^\\\"'\\\\<>\\s]+?\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\\"'\\\\<>\\s]*)?");

    public static final class SeriesInfo {
        public final String title, description, thumbnailUrl, tags, pageUrl;
        public final List<EpisodeMeta> episodes;

        SeriesInfo(String title, String description, String thumbnailUrl, String tags,
                   String pageUrl, List<EpisodeMeta> episodes) {
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

        EpisodeMeta(int number, String title, String url) {
            this.number = number;
            this.title = title;
            this.url = url;
        }
    }

    private ManhwabangApi() { }

    public static boolean isSeriesKey(String key) {
        return key != null && key.matches(KEY_PREFIX + "[0-9a-f]+");
    }

    public static SeriesInfo fetchSeriesInfo(String pageUrl, String kind, String cookie) throws Exception {
        String html = getText(pageUrl, origin(pageUrl), cookie);
        String title = stripHtml(tagText(html, "title"))
                .replaceFirst("(?i)^무료\\s*", "")
                .replaceFirst("(?i)\\s*다시보기\\s*-?\\s*만화방.*$", "")
                .replaceFirst("(?i)\\s*-\\s*만화방.*$", "").trim();
        if (title.isEmpty()) title = "만화방 웹툰";
        String description = stripHtml(findMeta(html, "og:description"));
        if (description.isEmpty()) description = stripHtml(findMeta(html, "description"));
        String thumbnail = absoluteUrl(pageUrl, findMeta(html, "og:image"));
        if (thumbnail == null) {
            Matcher thumb = Pattern.compile(
                    "(?i)(https?:)?//[^\\\"'()\\s]+/(?:webtoon|manhua)/images/(?:thumb|thumbnail)/[^\\\"'()\\s]+")
                    .matcher(html);
            if (thumb.find()) thumbnail = absoluteUrl(pageUrl, thumb.group());
        }

        LinkedHashSet<String> tagSet = new LinkedHashSet<>();
        Matcher genre = Pattern.compile(
                "(?is)<(?:a|span|button)\\b[^>]*>(미스터리|드라마|판타지|액션|무협|로맨스|BLGL|BL|성인|19|일상|개그|학원)</(?:a|span|button)>")
                .matcher(html);
        while (genre.find()) tagSet.add(stripHtml(genre.group(1)));
        if (tagSet.isEmpty()) {
            String keywords = stripHtml(findMeta(html, "keywords"))
                    .replaceFirst("^무료\\s*", "")
                    .replaceFirst("\\s*다시보기$", "").trim();
            if (!keywords.isEmpty()) tagSet.add(keywords);
        }

        String normalizedKind = "manhua".equalsIgnoreCase(kind) ? "manhua" : "webtoon";
        Pattern links = Pattern.compile("(?is)<a\\b[^>]*href=[\\\"']([^\\\"']*/" +
                normalizedKind + "/view\\?eid=\\d+[^\\\"']*)[\\\"'][^>]*>(.*?)</a>");
        Matcher link = links.matcher(html);
        Map<Integer, EpisodeMeta> episodes = new TreeMap<>();
        while (link.find()) {
            String text = stripHtml(link.group(2));
            Integer number = episodeNumber(text);
            if (number == null) continue;
            String url = absoluteUrl(pageUrl, link.group(1).replace("&amp;", "&"));
            if (url == null) continue;
            String episodeTitle = text.length() > 120 ? text.substring(0, 120) : text;
            episodes.putIfAbsent(number, new EpisodeMeta(number, episodeTitle, url));
        }
        return new SeriesInfo(title, description, thumbnail, String.join(", ", tagSet),
                pageUrl, new ArrayList<>(episodes.values()));
    }

    public static List<String> fetchEpisodeImages(String episodeUrl, String cookie) throws Exception {
        String html = getText(episodeUrl, episodeUrl, cookie);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = CDN_IMAGE.matcher(html);
        while (matcher.find()) {
            String cleaned = matcher.group().replace("\\/", "/");
            String url = absoluteUrl(episodeUrl, cleaned);
            if (url != null) found.add(url);
        }
        List<String> images = new ArrayList<>(found);
        images.sort(Comparator.comparingInt(ManhwabangApi::imageNumber).thenComparing(v -> v));
        if (images.isEmpty()) throw new IOException("만화방 회차 이미지를 찾지 못했습니다.");
        return images;
    }

    public static byte[] downloadBytes(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie, "image/*,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("만화방 이미지 HTTP " + code);
                return readAll(conn.getInputStream(), "만화방 이미지 다운로드 중단");
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
                if (code < 200 || code >= 300) throw new IOException("만화방 응답 오류 " + code);
                return new String(readAll(conn.getInputStream(), "만화방 요청 중단"), StandardCharsets.UTF_8);
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        
        });
    }

    private static byte[] readAll(InputStream input, String interruptedMessage) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(interruptedMessage);
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static HttpURLConnection open(String url, String referer, String cookie, String accept)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(0);
        conn.setReadTimeout(0);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", JoatoonApi.USER_AGENT);
        conn.setRequestProperty("Accept", accept);
        if (referer != null) conn.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        return NetworkRetry.track(conn);
    }

    private static Integer episodeNumber(String text) {
        Matcher matcher = Pattern.compile("(\\d{1,4})\\s*(?:화|회)").matcher(text);
        Integer result = null;
        while (matcher.find()) result = Integer.parseInt(matcher.group(1));
        if (result != null && result > 0 && result <= 5000) return result;
        matcher = Pattern.compile("^#\\s*(\\d{1,4})\\b").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static int imageNumber(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        Matcher matcher = Pattern.compile("(\\d{1,5})").matcher(name);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private static String findMeta(String html, String key) {
        Matcher matcher = Pattern.compile("(?is)<meta\\b[^>]*(?:property|name)=[\\\"']" +
                Pattern.quote(key) + "[\\\"'][^>]*content=[\\\"']([^\\\"']*)[\\\"']")
                .matcher(html);
        if (matcher.find()) return matcher.group(1);
        matcher = Pattern.compile("(?is)<meta\\b[^>]*content=[\\\"']([^\\\"']*)[\\\"'][^>]*" +
                "(?:property|name)=[\\\"']" + Pattern.quote(key) + "[\\\"']").matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String tagText(String html, String tag) {
        Matcher matcher = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">").matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String stripHtml(String value) {
        if (value == null) return "";
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
                .replaceAll("\\s+", " ").trim();
    }

    private static String absoluteUrl(String base, String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return new URL(new URL(base), raw).toString(); }
        catch (Exception ignored) { return null; }
    }

    private static String origin(String url) {
        try {
            URL parsed = new URL(url);
            return parsed.getProtocol() + "://" + parsed.getAuthority();
        } catch (Exception ignored) { return url; }
    }
}






