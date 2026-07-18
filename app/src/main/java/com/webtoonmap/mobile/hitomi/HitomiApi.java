package com.webtoonmap.mobile.hitomi;

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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HitomiApi {
    public static final String KEY_PREFIX = "hitomi_";
    private static final String ASSET_ORIGIN =
            "https://ltn.gold-usergeneratedcontent.net";
    private static final String IMAGE_DOMAIN = "gold-usergeneratedcontent.net";

    public static final class SeriesInfo {
        public final String title, description, thumbnailUrl, tags, pageUrl;
        public final List<String> imageUrls;

        SeriesInfo(String title, String description, String thumbnailUrl, String tags,
                   String pageUrl, List<String> imageUrls) {
            this.title = title;
            this.description = description;
            this.thumbnailUrl = thumbnailUrl;
            this.tags = tags;
            this.pageUrl = pageUrl;
            this.imageUrls = imageUrls;
        }
    }

    private static final class ImageRules {
        final String basePath;
        final Set<Integer> firstShardValues;

        ImageRules(String basePath, Set<Integer> firstShardValues) {
            this.basePath = basePath;
            this.firstShardValues = firstShardValues;
        }

        int shard(String hash) {
            return firstShardValues.contains(rotatedHashValue(hash)) ? 0 : 1;
        }
    }

    private HitomiApi() { }

    public static boolean isSeriesKey(String key) {
        return key != null && key.matches(KEY_PREFIX + "\\d+");
    }

    public static String seriesKey(String galleryId) {
        return galleryId != null && galleryId.matches("\\d+")
                ? KEY_PREFIX + galleryId : null;
    }

    public static String remoteId(String key) {
        return isSeriesKey(key) ? key.substring(KEY_PREFIX.length()) : null;
    }

    public static SeriesInfo fetchSeriesInfo(String baseUrl, String galleryId,
                                             String pageUrl, String cookie)
            throws Exception {
        if (galleryId == null || !galleryId.matches("\\d+")) {
            throw new IOException("히토미 갤러리 번호가 올바르지 않습니다.");
        }
        String base = trimSlash(baseUrl);
        String referer = pageUrl == null || pageUrl.trim().isEmpty()
                ? base + "/reader/" + galleryId + ".html" : pageUrl;
        String galleryScript = getText(
                ASSET_ORIGIN + "/galleries/" + galleryId + ".js", referer, cookie);
        JSONObject gallery = galleryJson(galleryScript);
        if (gallery.optInt("blocked", 0) != 0) {
            throw new IOException("차단된 히토미 갤러리입니다.");
        }

        String title = gallery.optString("title", "").trim();
        if (title.isEmpty()) title = gallery.optString("japanese_title", "").trim();
        if (title.isEmpty()) title = "히토미 " + galleryId;

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addValues(tags, gallery.optJSONArray("artists"), "artist");
        addValues(tags, gallery.optJSONArray("groups"), "group");
        addValues(tags, gallery.optJSONArray("parodys"), "parody");
        addValues(tags, gallery.optJSONArray("characters"), "character");
        addValues(tags, gallery.optJSONArray("tags"), "tag");
        String type = gallery.optString("type", "").trim();
        String language = gallery.optString("language_localname", "").trim();
        if (!type.isEmpty()) tags.add(type);
        if (!language.isEmpty()) tags.add(language);

        List<String> details = new ArrayList<>();
        String artistNames = joinedValues(gallery.optJSONArray("artists"), "artist");
        if (!artistNames.isEmpty()) details.add("작가: " + artistNames);
        if (!type.isEmpty()) details.add("유형: " + type);
        if (!language.isEmpty()) details.add("언어: " + language);

        String rulesScript = getText(ASSET_ORIGIN + "/gg.js", referer, cookie);
        ImageRules rules = parseRules(rulesScript);
        JSONArray files = gallery.optJSONArray("files");
        if (files == null || files.length() == 0) {
            throw new IOException("히토미 갤러리 이미지를 찾지 못했습니다.");
        }
        List<String> images = new ArrayList<>();
        String thumbnail = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String hash = file.optString("hash", "").toLowerCase(Locale.US);
            if (!hash.matches("[0-9a-f]{64}")) continue;
            images.add(fullImageUrl(hash, rules));
            if (thumbnail == null) thumbnail = thumbnailUrl(hash, rules);
        }
        if (images.isEmpty()) {
            throw new IOException("히토미 갤러리 이미지 주소를 만들지 못했습니다.");
        }
        return new SeriesInfo(title, String.join(" · ", details), thumbnail,
                String.join(", ", tags), referer, images);
    }

    public static byte[] downloadBytes(String url, String referer, String cookie)
            throws Exception {
        IOException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            // hitomi.la cookies belong to a different origin and can make the image CDN
            // reject or stall the request on some Android network stacks.
            HttpURLConnection conn = open(
                    url, referer, null, "image/webp,image/*,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("히토미 이미지 HTTP " + code);
                }
                return readAll(conn.getInputStream(), "히토미 이미지 다운로드 중단");
            } catch (IOException error) {
                if (Thread.currentThread().isInterrupted()) {
                    InterruptedIOException interrupted =
                            new InterruptedIOException("히토미 이미지 다운로드 중단");
                    interrupted.initCause(error);
                    throw interrupted;
                }
                lastError = error;
            } finally {
                NetworkRetry.release(conn);
                conn.disconnect();
            }

            if (attempt < 3) {
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        String detail = lastError == null || lastError.getMessage() == null
                ? "네트워크 응답 없음" : lastError.getMessage();
        throw new IOException("히토미 이미지 다운로드 실패(3회 시도): " + detail,
                lastError);
    }

    private static JSONObject galleryJson(String script) throws Exception {
        int marker = script.indexOf("galleryinfo");
        int start = marker < 0 ? -1 : script.indexOf('{', marker);
        int end = script.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("히토미 갤러리 정보를 해석하지 못했습니다.");
        }
        return new JSONObject(script.substring(start, end + 1));
    }

    private static ImageRules parseRules(String script) throws Exception {
        Matcher baseMatcher = Pattern.compile(
                "(?is)\\bb\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']")
                .matcher(script);
        if (!baseMatcher.find()) {
            throw new IOException("히토미 이미지 경로 규칙을 찾지 못했습니다.");
        }
        int start = script.indexOf("m: function");
        int end = start < 0 ? -1 : script.indexOf("return o", start);
        if (start < 0 || end <= start) {
            throw new IOException("히토미 이미지 서버 규칙을 찾지 못했습니다.");
        }
        Set<Integer> firstShardValues = new HashSet<>();
        Matcher cases = Pattern.compile("(?i)case\\s+(\\d+)\\s*:")
                .matcher(script.substring(start, end));
        while (cases.find()) {
            try {
                firstShardValues.add(Integer.parseInt(cases.group(1)));
            } catch (NumberFormatException ignored) { }
        }
        return new ImageRules(baseMatcher.group(1), firstShardValues);
    }

    private static String fullImageUrl(String hash, ImageRules rules) {
        int server = 1 + rules.shard(hash);
        return "https://w" + server + "." + IMAGE_DOMAIN + "/" +
                rules.basePath + rotatedHashValue(hash) + "/" + hash + ".webp";
    }

    private static String thumbnailUrl(String hash, ImageRules rules) {
        char server = (char) ('a' + rules.shard(hash));
        int length = hash.length();
        return "https://" + server + "tn." + IMAGE_DOMAIN + "/webpbigtn/" +
                hash.substring(length - 1) + "/" + hash.substring(length - 3, length - 1) +
                "/" + hash + ".webp";
    }

    private static int rotatedHashValue(String hash) {
        int length = hash.length();
        String rotated = hash.substring(length - 1) + hash.substring(length - 3, length - 1);
        return Integer.parseInt(rotated, 16);
    }

    private static void addValues(LinkedHashSet<String> output, JSONArray array, String key) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String value = item.optString(key, "").trim();
            if (!value.isEmpty() && value.length() <= 80) output.add(value);
        }
    }

    private static String joinedValues(JSONArray array, String key) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addValues(values, array, key);
        return String.join(", ", values);
    }

    private static String getText(String url, String referer, String cookie) throws Exception {
        return NetworkRetry.forever(() -> {
            HttpURLConnection conn = open(url, referer, cookie,
                    "text/javascript,application/javascript,text/plain,*/*;q=0.8");
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("히토미 응답 오류 " + code);
                }
                byte[] bytes = readAll(conn.getInputStream(), "히토미 요청 중단");
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
        return StandardCharsets.UTF_8;
    }

    private static byte[] readAll(InputStream input, String interruptedMessage)
            throws Exception {
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
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setRequestProperty("Connection", "close");
        if (referer != null && !referer.isEmpty()) conn.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        return NetworkRetry.track(conn);
    }

    private static String trimSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}