package com.webtoonmap.mobile.export;

import android.content.Context;
import android.net.Uri;

import com.webtoonmap.mobile.data.EpisodeItem;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.storage.WebtoonStorage;
import com.webtoonmap.mobile.download.SeriesDownloadService;
import com.webtoonmap.mobile.download.SourceJobStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class TransferImporter {
    public interface Progress {
        void onProgress(int current, int total, String title);
    }

    public static final class Result {
        public final List<String> imported;
        public final List<String> errors;

        Result(List<String> imported, List<String> errors) {
            this.imported = imported;
            this.errors = errors;
        }
    }

    private static final String FORMAT = "wtoon-transfer";
    private static final int VERSION = 1;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;

    private TransferImporter() { }

    public static Result importArchive(Context context, Uri uri, Progress progress) throws Exception {
        File cacheDir = new File(context.getCacheDir(), "imports");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("가져오기 임시 폴더를 만들 수 없습니다.");
        }
        File archive = new File(cacheDir, "incoming-" + System.currentTimeMillis() + ".wtoon.zip");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(archive)) {
            if (in == null) throw new IOException("선택한 파일을 열 수 없습니다.");
            copy(in, out);
        }

        List<String> imported = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new IOException("manifest.json이 없는 웹툰여지도 파일입니다.");
            }
            JSONObject manifest = new JSONObject(new String(
                    readEntry(zip, manifestEntry, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
            if (!FORMAT.equals(manifest.optString("format")) ||
                    manifest.optInt("version", 0) != VERSION) {
                throw new IOException("지원하지 않는 데이터 이전 파일입니다.");
            }
            JSONArray items = manifest.optJSONArray("webtoons");
            boolean forceUnread = !"android".equalsIgnoreCase(manifest.optString("exporter", "pc"));
            if (items == null || items.length() == 0) {
                throw new IOException("가져올 작품이 없습니다.");
            }
            if (items.length() > 500) throw new IOException("작품 수가 너무 많습니다.");

            LibraryDatabase database = LibraryDatabase.get(context);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                String title = item == null ? "웹툰" : item.optString("title", "웹툰");
                try {
                    imported.add(importOne(context, database, zip, item, forceUnread));
                } catch (Exception error) {
                    errors.add("‘" + title + "’: " +
                            (error.getMessage() == null ? "가져오기 실패" : error.getMessage()));
                }
                if (progress != null) progress.onProgress(i + 1, items.length(), title);
            }
        } finally {
            archive.delete();
        }
        return new Result(imported, errors);
    }

    private static String importOne(Context context, LibraryDatabase database, ZipFile zip,
                                    JSONObject item, boolean forceUnread) throws Exception {
        if (item == null) throw new IOException("작품 정보가 비어 있습니다.");
        String slug = item.optString("slug", "").trim();
        if (!slug.matches("[A-Za-z0-9_-]{1,80}")) throw new IOException("잘못된 작품 식별자입니다.");
        String title = item.optString("title", "").trim();
        if (title.isEmpty()) throw new IOException("작품 제목이 비어 있습니다.");
        String source = item.optString("source", "naver").trim().toLowerCase(Locale.US);
        String rawTitleId = item.isNull("title_id") ? "" : item.optString("title_id", "");
        String titleId = safeTitleId(rawTitleId, source, title, slug);
        if (SeriesDownloadService.isDownloading(titleId) ||
                SeriesDownloadService.isQueued(context, titleId)) {
            throw new IOException("다운로드가 끝난 뒤 가져와 주세요.");
        }
        JSONArray episodeJson = item.optJSONArray("episodes");
        if (episodeJson == null || episodeJson.length() == 0) {
            throw new IOException("회차 정보가 없습니다.");
        }
        if (episodeJson.length() > 5000) throw new IOException("회차 수가 너무 많습니다.");

        SeriesItem existingSeries = database.getSeries(titleId);
        boolean existed = existingSeries != null;
        File stageDir = new File(context.getCacheDir(), "import-stage/" +
                titleId + "-" + System.currentTimeMillis());
        if (!stageDir.mkdirs()) throw new IOException("회차 임시 폴더를 만들 수 없습니다.");
        WebtoonStorage storage = new WebtoonStorage(context, null);
        List<StagedEpisode> staged = new ArrayList<>();
        Set<Integer> seenNumbers = new HashSet<>();
        int lastRead = item.isNull("last_read_episode")
                ? -1 : item.optInt("last_read_episode", -1);

        try {
            for (int i = 0; i < episodeJson.length(); i++) {
                JSONObject episode = episodeJson.optJSONObject(i);
                if (episode == null) continue;
                int number = episode.optInt("episode_number", episode.optInt("number", 0));
                if (number <= 0 || number > 5000 || !seenNumbers.add(number)) continue;
                String folderName = episode.optString("folder_name",
                        String.format(Locale.US, "%03d.zip", number));
                if (!safeLeaf(folderName)) throw new IOException("잘못된 회차 파일명입니다.");
                File stagedZip = new File(stageDir, String.format(Locale.US, "%03d.zip", number));
                int imageCount = stageEpisode(zip, slug, folderName, stagedZip);
                if (imageCount <= 0) throw new IOException(number + "화 이미지가 없습니다.");
                String episodeTitle = episode.optString("title", number + "화");
                boolean viewed = !forceUnread && (episode.has("viewed")
                        ? episode.optBoolean("viewed", false)
                        : lastRead >= number);
                staged.add(new StagedEpisode(number, episodeTitle, imageCount, viewed, stagedZip));
            }
            if (staged.isEmpty()) throw new IOException("가져올 수 있는 회차가 없습니다.");

            for (StagedEpisode episode : staged) {
                storage.writeEpisodeZip(titleId, episode.number, episode.file);
            }

            String thumbnailPath = existingSeries == null ? null : existingSeries.thumbnailPath;
            ZipEntry thumbnail = findThumbnail(zip, slug, item.optString("thumbnail_ext", ""));
            if (thumbnail != null) {
                thumbnailPath = storage.writeThumbnail(titleId,
                        readEntry(zip, thumbnail, 64 * 1024 * 1024));
            }
            String description = item.isNull("description") ? "" : item.optString("description", "");
            String tags = tagsCsv(item.opt("tags"));
            String pageUrl = item.isNull("page_url") ? "" : item.optString("page_url", "").trim();
            if (!"naver".equals(source) && !pageUrl.isEmpty()) {
                Uri parsed = Uri.parse(pageUrl);
                String kind = parsed.getQueryParameter("bo_table");
                if (kind == null || kind.isEmpty()) {
                    String path = parsed.getPath();
                    if ("wolfdot".equals(source)) {
                        kind = path != null && path.matches("(?i)^/cl/?$")
                                ? "comic" : "webtoon";
                    } else {
                        kind = path != null && path.contains("/manhua/")
                                ? "manhua" : "webtoon";
                    }
                }
                SourceJobStore.register(context, titleId, source, pageUrl, rawTitleId, kind);
            }
            database.upsertSeries(new SeriesItem(titleId, title, description, tags,
                    thumbnailPath, null, "completed", staged.size()));
            for (StagedEpisode episode : staged) {
                database.upsertEpisode(new EpisodeItem(titleId, episode.number,
                        episode.title, episode.imageCount, episode.viewed));
                database.setEpisodeViewed(titleId, episode.number, episode.viewed);
            }
            return title;
        } catch (Exception error) {
            if (!existed) {
                database.deleteSeries(titleId);
                storage.deleteSeries(titleId);
            }
            throw error;
        } finally {
            WebtoonStorage.deleteRecursively(stageDir);
        }
    }

    private static int stageEpisode(ZipFile zip, String slug, String folderName, File output)
            throws Exception {
        String exactName = "items/" + slug + "/episodes/" + folderName;
        ZipEntry exact = zip.getEntry(exactName);
        if (exact != null && !exact.isDirectory()) {
            try (InputStream in = zip.getInputStream(exact);
                 OutputStream out = new FileOutputStream(output)) {
                copy(in, out);
            }
            return countZipImages(output);
        }

        String prefix = exactName + "/";
        int count = 0;
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                String relative = entry.getName().substring(prefix.length());
                if (!safeRelative(relative) || !isImage(relative)) continue;
                out.putNextEntry(new ZipEntry(relative));
                try (InputStream in = zip.getInputStream(entry)) { copy(in, out); }
                out.closeEntry();
                count++;
            }
        }
        return count;
    }

    private static int countZipImages(File file) throws Exception {
        int count = 0;
        try (ZipFile zip = new ZipFile(file)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && safeRelative(entry.getName()) && isImage(entry.getName())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static ZipEntry findThumbnail(ZipFile zip, String slug, String extension) {
        String prefix = "items/" + slug + "/thumbnail";
        if (extension != null && extension.matches("(?i)\\.(?:jpg|jpeg|png|webp|gif)")) {
            ZipEntry exact = zip.getEntry(prefix + extension);
            if (exact != null && !exact.isDirectory()) return exact;
        }
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith(prefix) && isImage(entry.getName())) {
                return entry;
            }
        }
        return null;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, int maximum) throws Exception {
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            int total = 0;
            while ((count = in.read(buffer)) >= 0) {
                total += count;
                if (total > maximum) throw new IOException("ZIP 항목이 너무 큽니다: " + entry.getName());
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static String tagsCsv(Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<String> tags = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String tag = array.optString(i, "").trim();
                if (!tag.isEmpty()) tags.add(tag);
            }
            return android.text.TextUtils.join(", ", tags);
        }
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
    }

    private static String safeTitleId(String raw, String source, String title, String slug)
            throws Exception {
        String value = raw == null ? "" : raw.trim();
        if (!value.isEmpty() && !"naver".equals(source) &&
                !value.toLowerCase(Locale.US).startsWith(source + "_")) {
            value = source + "_" + value;
        }
        value = value.replaceAll("[^\\p{L}\\p{N}_.-]", "_");
        if (value.isEmpty()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((source + "|" + title + "|" + slug)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format(Locale.US, "%02x", hash[i]));
            value = "imported_" + hex;
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static boolean safeLeaf(String value) {
        return value != null && !value.isEmpty() && !value.contains("/") &&
                !value.contains("\\") && !".".equals(value) && !"..".equals(value);
    }

    private static boolean safeRelative(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.startsWith("\\")) return false;
        for (String part : value.replace('\\', '/').split("/")) {
            if ("..".equals(part)) return false;
        }
        return true;
    }

    private static boolean isImage(String value) {
        return value != null && value.toLowerCase(Locale.US)
                .matches(".*\\.(?:jpg|jpeg|png|webp|gif|bmp)$");
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("가져오기가 중단되었습니다.");
            output.write(buffer, 0, count);
        }
    }

    private static final class StagedEpisode {
        final int number;
        final String title;
        final int imageCount;
        final boolean viewed;
        final File file;

        StagedEpisode(int number, String title, int imageCount, boolean viewed, File file) {
            this.number = number;
            this.title = title;
            this.imageCount = imageCount;
            this.viewed = viewed;
            this.file = file;
        }
    }
}

