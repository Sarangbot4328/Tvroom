package com.webtoonmap.mobile.wolfdot;

import android.text.Html;

import com.webtoonmap.mobile.joatoon.JoatoonApi;
import com.webtoonmap.mobile.network.NetworkRetry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WolfdotApi {
    public static final String KEY_PREFIX = "wolfdot_";
    public static final String KIND_WEBTOON = "webtoon";
    public static final String KIND_COMIC = "comic";
    private static final Charset DEFAULT_CHARSET = Charset.forName("EUC-KR");
    private static final int MAX_LIST_PAGES = 100;

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

    private WolfdotApi() { }

    public static boolean isSeriesKey(String key) {
        return key != null && key.matches(KEY_PREFIX + "[0-9a-f]+");
    }

    public static SeriesInfo fetchSeriesInfo(String baseUrl, String seriesId, String cookie)
            throws Exception {
        return fetchSeriesInfo(baseUrl, seriesId, KIND_WEBTOON, cookie);
    }

    public static SeriesInfo fetchSeriesInfo(String baseUrl, String seriesId, String kind,
                                             String cookie) throws Exception {
        if (seriesId == null || !seriesId.matches("\\d+")) {
            throw new IOException("늑대닷컴 작품 번호가 올바르지 않습니다.");
        }
        String base = trimSlash(baseUrl);
        String pagePath = KIND_COMIC.equals(kind) ? "/cl" : "/list";
        String pageUrl = base + pagePath + "?toon=" + seriesId;
        String html = getText(pageUrl, base + "/", cookie);

        String title = stripHtml(firstGroup(html,
                "(?is)<h1\\b[^>]*class=[\\\"'][^\\\"']*w-title[^\\\"']*[\\\"'][^>]*>(.*?)</h1>"));
        if (title.isEmpty()) {
            title = stripHtml(tagText(html, "title"))
                    .replaceFirst("(?i)\\s*-\\s*늑대닷컴.*$", "").trim();
        }
        if (title.isEmpty()) title = "늑대닷컴 작품";

        String description = stripHtml(firstGroup(html,
                "(?is)<div\\b[^>]*id=[\\\"']summary[\\\"'][^>]*>(.*?)</div>"));
        if (description.isEmpty()) description = stripHtml(findMeta(html, "description"));

        String thumbnail = null;
        String thumbBlock = firstGroup(html,
                "(?is)<div\\b[^>]*class=[\\\"'][^\\\"']*thumb-wrap[^\\\"']*[\\\"'][^>]*>(.*?)</div>");
        String thumbTag = firstGroup(thumbBlock, "(?is)<img\\b[^>]*>");
        if (!thumbTag.isEmpty()) {
            thumbnail = absoluteUrl(pageUrl,
                    firstGroup(thumbTag, "(?is)src\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"));
        }

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Matcher tagMatcher = Pattern.compile(
                "(?is)<a\\b[^>]*class=[\\\"'][^\\\"']*gtag[^\\\"']*[\\\"'][^>]*>(.*?)</a>")
                .matcher(html);
        while (tagMatcher.find()) {
            String tag = stripHtml(tagMatcher.group(1)).replaceFirst("^#", "").trim();
            if (!tag.isEmpty() && tag.length() <= 30) tags.add(tag);
        }

        Map<Integer, EpisodeMeta> episodes = new TreeMap<>();
        parseEpisodes(html, pageUrl, episodes);
        int lastPage = listPageCount(html);
        for (int page = 2; page <= lastPage; page++) {
            String listPageUrl = pageUrl + "&s=n&pg=" + page;
            String pageHtml = getText(listPageUrl, pageUrl, cookie);
            int before = episodes.size();
            parseEpisodes(pageHtml, listPageUrl, episodes);
            if (episodes.size() == before) break;
        }
        if (episodes.isEmpty()) throw new IOException("늑대닷컴 회차 목록을 찾지 못했습니다.");

        return new SeriesInfo(title, description, thumbnail, String.join(", ", tags),
                pageUrl, new ArrayList<>(episodes.values()));
    }

    public static List<String> fetchEpisodeImages(String episodeUrl, String cookie)
            throws Exception {
        String html = getText(episodeUrl, episodeUrl, cookie);
        String block = firstGroup(html,
                "(?is)<div\\b[^>]*id=[\\\"']vimg-area[\\\"'][^>]*>(.*?)</div>");
        if (block.isEmpty()) throw new IOException("늑대닷컴 이미지 영역을 찾지 못했습니다.");

        LinkedHashSet<String> images = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?is)<img\\b[^>]*>").matcher(block);
        while (matcher.find()) {
            String tag = matcher.group();
            String raw = firstGroup(tag,
                    "(?is)data-src\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
            if (raw.isEmpty()) {
                raw = firstGroup(tag, "(?is)src\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
            }
            if (raw.isEmpty() || raw.startsWith("data:") ||
                    raw.toLowerCase().contains("/assets/") ||
                    raw.toLowerCase().contains("sprite")) continue;
            String url = absoluteUrl(episodeUrl, raw);
            if (url != null) images.add(url);
        }
        if (images.isEmpty()) throw new IOException("늑대닷컴 회차 이미지를 찾지 못했습니다.");
        return new ArrayList<>(images);
    }

    public static byte[] downloadBytes(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie, "image/*,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("늑대닷컴 이미지 HTTP " + code);
                }
                return readAll(conn.getInputStream(), "늑대닷컴 이미지 다운로드 중단");
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        });
    }

    private static void parseEpisodes(String html, String pageUrl,
                                      Map<Integer, EpisodeMeta> episodes) {
        Matcher links = Pattern.compile(
                "(?is)<a\\b(?=[^>]*class=[\\\"'][^\\\"']*ep-item[^\\\"']*[\\\"'])([^>]*)>(.*?)</a>")
                .matcher(html);
        while (links.find()) {
            String href = firstGroup(links.group(1),
                    "(?is)href\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
                    .replace("&amp;", "&");
            Matcher numberMatcher = Pattern.compile("(?:[?&])num=(\\d{1,5})(?:&|$)")
                    .matcher(href);
            if (!numberMatcher.find()) continue;
            int number;
            try {
                number = Integer.parseInt(numberMatcher.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (number <= 0 || number > 10000) continue;
            String url = absoluteUrl(pageUrl, href);
            if (url == null) continue;
            String title = stripHtml(firstGroup(links.group(2),
                    "(?is)<span\\b[^>]*class=[\\\"'][^\\\"']*ep-title[^\\\"']*[\\\"'][^>]*>(.*?)</span>"));
            if (title.isEmpty()) title = stripHtml(links.group(2));
            if (title.isEmpty()) title = number + "화";
            if (title.length() > 120) title = title.substring(0, 120);
            episodes.putIfAbsent(number, new EpisodeMeta(number, title, url));
        }
    }

    private static int listPageCount(String html) {
        int maximum = 1;
        Matcher matcher = Pattern.compile("(?i)(?:&|&amp;)pg=(\\d{1,3})")
                .matcher(html);
        while (matcher.find()) {
            try {
                maximum = Math.max(maximum, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) { }
        }
        return Math.min(maximum, MAX_LIST_PAGES);
    }

    private static String getText(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie,
                    "text/html,application/xhtml+xml,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("늑대닷컴 응답 오류 " + code);
                }
                byte[] bytes = readAll(conn.getInputStream(), "늑대닷컴 요청 중단");
                return new String(bytes, responseCharset(conn.getContentType()));
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        });
    }

    private static Charset responseCharset(String contentType) {
        if (contentType != null) {
            Matcher matcher = Pattern.compile("(?i)charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)")
                    .matcher(contentType);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1));
                } catch (Exception ignored) { }
            }
        }
        return DEFAULT_CHARSET;
    }

    private static byte[] readAll(InputStream input, String interruptedMessage) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException(interruptedMessage);
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static HttpURLConnection open(String url, String referer, String cookie,
                                           String accept) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", JoatoonApi.USER_AGENT);
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8");
        if (referer != null) conn.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        return NetworkRetry.track(conn);
    }

    private static String findMeta(String html, String key) {
        String value = firstGroup(html, "(?is)<meta\\b[^>]*(?:property|name)=[\\\"']" +
                Pattern.quote(key) + "[\\\"'][^>]*content=[\\\"']([^\\\"']*)[\\\"']");
        if (!value.isEmpty()) return value;
        return firstGroup(html, "(?is)<meta\\b[^>]*content=[\\\"']([^\\\"']*)[\\\"'][^>]*" +
                "(?:property|name)=[\\\"']" + Pattern.quote(key) + "[\\\"']");
    }

    private static String tagText(String html, String tag) {
        return firstGroup(html, "(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + ">");
    }

    private static String firstGroup(String input, String pattern) {
        if (input == null || input.isEmpty()) return "";
        Matcher matcher = Pattern.compile(pattern).matcher(input);
        if (!matcher.find()) return "";
        return matcher.groupCount() >= 1 && matcher.group(1) != null
                ? matcher.group(1) : matcher.group();
    }

    private static String stripHtml(String value) {
        if (value == null) return "";
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
                .replaceAll("\\s+", " ").trim();
    }

    private static String absoluteUrl(String base, String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.startsWith("data:")) return null;
        try {
            URL resolved = new URL(new URL(base), raw.trim().replace("&amp;", "&"));
            return resolved.toURI().toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String trimSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}