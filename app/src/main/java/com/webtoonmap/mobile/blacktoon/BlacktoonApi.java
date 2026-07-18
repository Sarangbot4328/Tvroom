package com.webtoonmap.mobile.blacktoon;

import android.text.Html;

import com.webtoonmap.mobile.joatoon.JoatoonApi;
import com.webtoonmap.mobile.network.NetworkRetry;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BlacktoonApi {
    public static final String KEY_PREFIX = "blacktoon_";
    private static final String DEFAULT_IMG_DOMAIN = "https://3c99fa.speedwebgo.com/";

    private static volatile String cachedImgOrigin;
    private static volatile String cachedImgDomain;

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

    private BlacktoonApi() { }

    public static boolean isSeriesKey(String key) {
        return key != null && key.matches(KEY_PREFIX + "[0-9a-f]+");
    }

    public static SeriesInfo fetchSeriesInfo(String baseUrl, String seriesId, String cookie)
            throws Exception {
        String base = trimSlash(baseUrl);
        String detailUrl = base + "/webtoon/" + seriesId + ".html";
        String html = getText(detailUrl, base + "/", cookie);

        String title = stripHtml(tagText(html, "title"))
                .replaceFirst("(?i)\\s*BlackToon.*$", "")
                .replaceFirst("(?i)\\s*블랙툰.*$", "").trim();
        if (title.isEmpty()) {
            title = stripHtml(firstGroup(html,
                    "(?is)<h3[^>]*>\\s*<b>(.*?)</b>"));
        }
        if (title.isEmpty()) title = "블랙툰 만화";

        String description = stripHtml(findMeta(html, "description"));
        if (description.isEmpty()) description = stripHtml(findMeta(html, "og:description"));

        String imgDomain = imageDomain(origin(detailUrl), base + "/", cookie);
        String thumbnail = null;
        String thumbOsrc = firstGroup(html,
                "(?is)<img\\b[^>]*class=[\\\"'][^\\\"']*thumb2[^\\\"']*[\\\"'][^>]*>");
        if (!thumbOsrc.isEmpty()) {
            String oSrc = firstGroup(thumbOsrc, "(?is)o_src\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
            if (oSrc.isEmpty()) {
                oSrc = firstGroup(thumbOsrc, "(?is)(?:data-original|src)\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
            }
            thumbnail = resolveImage(oSrc, imgDomain);
        }

        List<EpisodeMeta> episodes = fetchEpisodeList(base, seriesId, detailUrl, cookie);
        return new SeriesInfo(title, description, thumbnail,
                String.join(", ", parseTags(html)), detailUrl, episodes);
    }

    private static List<EpisodeMeta> fetchEpisodeList(String base, String seriesId,
                                                      String referer, String cookie)
            throws Exception {
        String listUrl = base + "/data/toonlist/" + seriesId + ".js";
        String js = getText(listUrl, referer, cookie);
        Matcher matcher = Pattern.compile("(?is)var\\s+clist\\s*=\\s*(\\[[\\s\\S]*\\])\\s*;?")
                .matcher(js);
        if (!matcher.find()) throw new IOException("블랙툰 회차 목록을 찾지 못했습니다.");

        List<EpisodeMeta> episodes = new ArrayList<>();
        LinkedHashSet<Integer> usedNumbers = new LinkedHashSet<>();
        int fallbackNumber = 1;
        JSONArray array = new JSONArray(matcher.group(1));
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String relative = obj.optString("u", "").replace("\\/", "/");
            if (relative.isEmpty()) continue;
            String url = absoluteUrl(base + "/", relative.replace("&amp;", "&"));
            if (url == null) continue;
            String title = stripHtml(obj.optString("t", "")).trim();
            if (title.isEmpty()) title = "회차";
            int order = obj.optInt("od", 0);
            int number = order > 0 ? order : episodeNumber(title);
            if (number <= 0 || usedNumbers.contains(number)) {
                while (usedNumbers.contains(fallbackNumber)) fallbackNumber++;
                number = fallbackNumber;
            }
            usedNumbers.add(number);
            if (title.length() > 120) title = title.substring(0, 120);
            episodes.add(new EpisodeMeta(number, title, url));
        }
        episodes.sort((a, b) -> Integer.compare(a.number, b.number));
        if (episodes.isEmpty()) throw new IOException("블랙툰 회차 목록이 비어 있습니다.");
        return episodes;
    }

    public static List<String> fetchEpisodeImages(String episodeUrl, String cookie) throws Exception {
        String html = getText(episodeUrl, episodeUrl, cookie);
        String block = firstGroup(html,
                "(?is)id=[\\\"']toon_content_imgs[\\\"'][^>]*>(.*?)</div>");
        if (block.isEmpty()) block = html;
        String imgDomain = imageDomain(origin(episodeUrl), episodeUrl, cookie);

        LinkedHashSet<String> images = new LinkedHashSet<>();
        Matcher img = Pattern.compile("(?is)<img\\b[^>]*>").matcher(block);
        while (img.find()) {
            String tag = img.group();
            String oSrc = firstGroup(tag, "(?is)o_src\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
            if (oSrc.isEmpty()) {
                oSrc = firstGroup(tag,
                        "(?is)(?:data-original|data-src|src)\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
            }
            if (oSrc.isEmpty() || oSrc.startsWith("data:")) continue;
            if (oSrc.toLowerCase().contains("loading")) continue;
            String url = resolveImage(oSrc, imgDomain);
            if (url != null) images.add(url);
        }
        if (images.isEmpty()) throw new IOException("블랙툰 회차 이미지를 찾지 못했습니다.");
        return new ArrayList<>(images);
    }

    public static byte[] downloadBytes(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie, "image/*,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("블랙툰 이미지 HTTP " + code);
                return readAll(conn.getInputStream(), "블랙툰 이미지 다운로드 중단");
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        });
    }

    private static String imageDomain(String imgOrigin, String referer, String cookie) {
        String origin = imgOrigin == null ? "" : imgOrigin;
        String cached = cachedImgDomain;
        if (cached != null && origin.equals(cachedImgOrigin)) return cached;
        String domain = DEFAULT_IMG_DOMAIN;
        try {
            String config = getTextOnce(origin + "/data/config.js", referer, cookie);
            String parsed = firstGroup(config,
                    "(?is)var\\s+img_domain\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
            if (!parsed.isEmpty()) domain = parsed;
        } catch (Exception ignored) { }
        if (!domain.endsWith("/")) domain += "/";
        cachedImgOrigin = origin;
        cachedImgDomain = domain;
        return domain;
    }

    private static String resolveImage(String oSrc, String imgDomain) {
        if (oSrc == null) return null;
        String value = oSrc.trim().replace("&amp;", "&");
        if (value.isEmpty()) return null;
        if (value.matches("(?i)^https?://.*")) return value;
        String domain = imgDomain == null || imgDomain.isEmpty() ? DEFAULT_IMG_DOMAIN : imgDomain;
        if (!domain.endsWith("/")) domain += "/";
        return domain + value.replaceFirst("^/", "");
    }

    private static LinkedHashSet<String> parseTags(String html) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Matcher badge = Pattern.compile(
                "(?is)<span\\b[^>]*class=[\\\"'][^\\\"']*badge-light[^\\\"']*[\\\"'][^>]*>(.*?)</span>")
                .matcher(html);
        while (badge.find()) {
            String value = stripHtml(badge.group(1));
            if (!value.isEmpty() && value.length() <= 20) tags.add(value);
        }
        return tags;
    }

    private static String getText(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie,
                    "text/html,application/xhtml+xml,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("블랙툰 응답 오류 " + code);
                return new String(readAll(conn.getInputStream(), "블랙툰 요청 중단"),
                        StandardCharsets.UTF_8);
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }
        });
    }

    private static String getTextOnce(String url, String referer, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", JoatoonApi.USER_AGENT);
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
        if (referer != null) conn.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        NetworkRetry.track(conn);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("블랙툰 설정 응답 오류 " + code);
            return new String(readAll(conn.getInputStream(), "블랙툰 설정 요청 중단"),
                    StandardCharsets.UTF_8);
        } finally {
            NetworkRetry.release(conn);
            conn.disconnect();
        }
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

    private static int episodeNumber(String text) {
        Matcher matcher = Pattern.compile("(\\d{1,4})\\s*(?:화|회)").matcher(text);
        int result = 0;
        while (matcher.find()) result = Integer.parseInt(matcher.group(1));
        return result > 0 && result <= 5000 ? result : 0;
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
        Matcher matcher = Pattern.compile(pattern).matcher(input);
        if (!matcher.find()) return "";
        return matcher.groupCount() >= 1 ? nullToEmpty(matcher.group(1)) : nullToEmpty(matcher.group());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String stripHtml(String value) {
        if (value == null) return "";
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
                .replaceAll("\\s+", " ").trim();
    }

    private static String trimSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    private static String absoluteUrl(String base, String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.startsWith("data:")) return null;
        try {
            URL resolved = new URL(new URL(base), raw.trim().replace(" ", "%20"));
            return resolved.toURI().toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String origin(String url) {
        try {
            URL parsed = new URL(url);
            return parsed.getProtocol() + "://" + parsed.getAuthority();
        } catch (Exception ignored) {
            return url;
        }
    }
}
