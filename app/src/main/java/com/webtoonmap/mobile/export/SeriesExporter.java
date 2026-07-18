package com.webtoonmap.mobile.export;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.webtoonmap.mobile.data.EpisodeItem;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.download.SourceJobStore;
import com.webtoonmap.mobile.joatoon.JoatoonApi;
import com.webtoonmap.mobile.storage.SourceSettings;
import com.webtoonmap.mobile.storage.WebtoonStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SeriesExporter {
    public interface Progress {
        void onProgress(int current, int total);
    }

    private static final String FORMAT = "wtoon-transfer";
    private static final int VERSION = 1;

    private SeriesExporter() { }

    public static File export(Context context, List<SeriesItem> seriesItems,
                              LibraryDatabase database, Progress progress) throws Exception {
        if (seriesItems == null || seriesItems.isEmpty()) {
            throw new IOException("내보낼 작품이 없습니다.");
        }

        int totalEpisodes = 0;
        for (SeriesItem series : seriesItems) {
            List<EpisodeItem> episodes = database.listEpisodes(series.titleId);
            if (episodes.isEmpty()) throw new IOException("‘" + series.title + "’에 내보낼 회차가 없습니다.");
            totalEpisodes += episodes.size();
        }

        File exportDir = new File(context.getCacheDir(), "exports");
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IOException("내보내기 임시 폴더를 만들 수 없습니다.");
        }
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        String filename = "웹툰여지도_이전_" + seriesItems.size() + "작품_" + date + ".wtoon.zip";
        File output = new File(exportDir, filename);
        File pending = new File(exportDir, filename + ".part");
        if (pending.exists() && !pending.delete()) {
            throw new IOException("이전 내보내기 임시 파일을 지울 수 없습니다.");
        }
        if (output.exists() && !output.delete()) {
            throw new IOException("이전 내보내기 파일을 지울 수 없습니다.");
        }

        JSONArray webtoons = new JSONArray();
        int completed = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(pending))) {
            zip.setLevel(Deflater.BEST_SPEED);
            for (int index = 0; index < seriesItems.size(); index++) {
                SeriesItem series = seriesItems.get(index);
                List<EpisodeItem> episodes = database.listEpisodes(series.titleId);
                String slug = String.format(Locale.US, "%03d", index);
                String prefix = "items/" + slug + "/";
                WebtoonStorage storage = new WebtoonStorage(context, series.storageUri);

                JSONArray episodeArray = new JSONArray();
                Integer lastRead = null;
                for (EpisodeItem episode : episodes) {
                    String episodeName = String.format(Locale.US, "%03d.zip", episode.number);
                    try (InputStream in = storage.openEpisodeZip(series.titleId, episode.number)) {
                        writeEntry(zip, prefix + "episodes/" + episodeName, in);
                    }
                    JSONObject value = new JSONObject();
                    value.put("episode_number", episode.number);
                    value.put("folder_name", episodeName);
                    value.put("title", episode.title);
                    value.put("image_count", episode.imageCount);
                    value.put("viewed", episode.viewed);
                    episodeArray.put(value);
                    if (episode.viewed && (lastRead == null || episode.number > lastRead)) {
                        lastRead = episode.number;
                    }
                    completed++;
                    if (progress != null) progress.onProgress(completed, totalEpisodes);
                }

                String thumbnailExt = writeThumbnail(context, zip, prefix, series.thumbnailPath)
                        ? ".jpg" : null;
                JSONObject item = new JSONObject();
                String source = sourceFromTitleId(series.titleId);
                String transferTitleId = series.titleId;
                String pageUrl = null;
                SourceJobStore.Job sourceJob = SourceJobStore.get(context, series.titleId);
                if (sourceJob != null) {
                    source = sourceJob.source;
                    if (sourceJob.remoteId != null && !sourceJob.remoteId.isEmpty()) {
                        transferTitleId = sourceJob.remoteId;
                    }
                    pageUrl = sourceJob.pageUrl(baseUrlFor(context, source));
                } else if ("joatoon".equals(source) && series.titleId.startsWith("joatoon_")) {
                    transferTitleId = series.titleId.substring("joatoon_".length());
                    pageUrl = JoatoonApi.seriesUrl(SourceSettings.getJoatoonUrl(context), transferTitleId);
                } else if ("naver".equals(source)) {
                    pageUrl = "https://comic.naver.com/webtoon/list?titleId=" + transferTitleId;
                }
                item.put("slug", slug);
                item.put("title", series.title);
                item.put("description", series.description);
                item.put("tags", tagsArray(series.tags));
                item.put("source", source);
                item.put("title_id", transferTitleId);
                item.put("page_url", pageUrl == null ? JSONObject.NULL : pageUrl);
                item.put("created_at", Instant.now().toString());
                item.put("last_read_episode", lastRead == null ? JSONObject.NULL : lastRead);
                item.put("episode_count", episodes.size());
                item.put("episodes", episodeArray);
                item.put("thumbnail_ext", thumbnailExt == null ? JSONObject.NULL : thumbnailExt);
                webtoons.put(item);
            }

            JSONObject manifest = new JSONObject();
            manifest.put("format", FORMAT);
            manifest.put("version", VERSION);
            manifest.put("exporter", "android");
            manifest.put("exported_at", Instant.now().toString());
            manifest.put("webtoon_count", webtoons.length());
            manifest.put("webtoons", webtoons);
            byte[] bytes = manifest.toString(2).getBytes(StandardCharsets.UTF_8);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(bytes);
            zip.closeEntry();
        } catch (Exception error) {
            pending.delete();
            output.delete();
            throw error;
        }

        if (output.exists() && !output.delete()) {
            pending.delete();
            throw new IOException("기존 내보내기 파일을 교체할 수 없습니다.");
        }
        if (!pending.renameTo(output)) {
            pending.delete();
            throw new IOException("내보내기 파일을 완성할 수 없습니다.");
        }
        return output;
    }

    public static void saveTransferFile(Context context, File source, Uri destination)
            throws Exception {
        if (source == null || !source.isFile() || destination == null) {
            throw new IOException("저장할 내보내기 파일이 없습니다.");
        }
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = context.getContentResolver().openOutputStream(destination, "w")) {
            if (out == null) throw new IOException("선택한 저장 위치를 열 수 없습니다.");
            copy(in, out);
        } catch (Exception error) {
            try {
                DocumentFile failed = DocumentFile.fromSingleUri(context, destination);
                if (failed != null) failed.delete();
            } catch (Exception ignored) { }
            throw error;
        }
    }

    public static void exportViewerFolders(Context context, Uri treeUri,
                                           List<SeriesItem> seriesItems,
                                           LibraryDatabase database,
                                           Progress progress) throws Exception {
        if (treeUri == null) throw new IOException("저장할 폴더를 선택하지 않았습니다.");
        if (seriesItems == null || seriesItems.isEmpty()) {
            throw new IOException("내보낼 작품이 없습니다.");
        }
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.isDirectory() || !root.canWrite()) {
            throw new IOException("선택한 폴더에 저장할 수 없습니다.");
        }

        int totalEpisodes = 0;
        for (SeriesItem series : seriesItems) {
            List<EpisodeItem> episodes = database.listEpisodes(series.titleId);
            if (episodes.isEmpty()) {
                throw new IOException("‘" + series.title + "’에 내보낼 회차가 없습니다.");
            }
            totalEpisodes += episodes.size();
        }

        int completed = 0;
        for (SeriesItem series : seriesItems) {
            String folderName = safeFolderName(series.title, series.titleId);
            DocumentFile seriesDirectory = root.findFile(folderName);
            if (seriesDirectory == null) seriesDirectory = root.createDirectory(folderName);
            if (seriesDirectory == null || !seriesDirectory.isDirectory()) {
                throw new IOException("‘" + folderName + "’ 폴더를 만들 수 없습니다.");
            }

            WebtoonStorage storage = new WebtoonStorage(context, series.storageUri);
            for (EpisodeItem episode : database.listEpisodes(series.titleId)) {
                String episodeName = String.format(Locale.US, "%03d.zip", episode.number);
                writeViewerEpisode(context, storage, series.titleId, episode.number,
                        seriesDirectory, episodeName);
                completed++;
                if (progress != null) progress.onProgress(completed, totalEpisodes);
            }
        }
    }

    private static void writeViewerEpisode(Context context, WebtoonStorage storage,
                                           String titleId, int episodeNumber,
                                           DocumentFile directory, String episodeName)
            throws Exception {
        String pendingName = episodeName + ".part-" + System.currentTimeMillis();
        DocumentFile pending = directory.createFile("application/octet-stream", pendingName);
        if (pending == null) throw new IOException(episodeName + " 임시 파일 생성 실패");
        try (InputStream in = storage.openEpisodeZip(titleId, episodeNumber);
             OutputStream out = context.getContentResolver().openOutputStream(pending.getUri(), "w")) {
            if (out == null) throw new IOException(episodeName + " 저장 위치를 열 수 없습니다.");
            copy(in, out);
        } catch (Exception error) {
            pending.delete();
            throw error;
        }

        DocumentFile previous = directory.findFile(episodeName);
        if (previous != null && !previous.delete()) {
            pending.delete();
            throw new IOException("기존 " + episodeName + " 파일을 교체할 수 없습니다.");
        }
        if (!pending.renameTo(episodeName)) {
            pending.delete();
            throw new IOException(episodeName + " 저장 완료 처리 실패");
        }
    }

    private static String safeFolderName(String title, String titleId) {
        String value = title == null ? "" : title.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("[. ]+$", "").trim();
        if (value.isEmpty()) value = titleId == null ? "웹툰" : titleId;
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static boolean writeThumbnail(Context context, ZipOutputStream zip, String prefix,
                                          String path) {
        if (path == null || path.isEmpty()) return false;
        try (InputStream in = openPath(context, path)) {
            if (in == null) return false;
            writeEntry(zip, prefix + "thumbnail.jpg", in);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static JSONArray tagsArray(String tags) {
        JSONArray result = new JSONArray();
        if (tags == null) return result;
        for (String tag : tags.split("[,，]")) {
            String value = tag.trim();
            if (!value.isEmpty()) result.put(value);
        }
        return result;
    }

    private static String sourceFromTitleId(String titleId) {
        String value = titleId == null ? "" : titleId.toLowerCase(Locale.US);
        if (value.startsWith("joatoon_")) return "joatoon";
        if (value.startsWith("manhwabang_")) return "manhwabang";
        if (value.startsWith("ililtoon_")) return "ililtoon";
        if (value.startsWith("blacktoon_")) return "blacktoon";
        if (value.startsWith("wolfdot_")) return "wolfdot";
        if (value.startsWith("hitomi_")) return "hitomi";
        return "naver";
    }

    private static String baseUrlFor(Context context, String source) {
        if (SourceSettings.SOURCE_JOATOON.equals(source)) return SourceSettings.getJoatoonUrl(context);
        if (SourceSettings.SOURCE_MANHWABANG.equals(source)) return SourceSettings.getManhwabangUrl(context);
        if (SourceSettings.SOURCE_ILILTOON.equals(source)) return SourceSettings.getIliltoonUrl(context);
        if (SourceSettings.SOURCE_BLACKTOON.equals(source)) return SourceSettings.getBlacktoonUrl(context);
        if (SourceSettings.SOURCE_WOLFDOT.equals(source)) return SourceSettings.getWolfdotUrl(context);
        if (SourceSettings.SOURCE_HITOMI.equals(source)) return SourceSettings.getHitomiUrl(context);
        return "https://comic.naver.com";
    }

    private static InputStream openPath(Context context, String path) throws Exception {
        if (path.startsWith("content://")) {
            return context.getContentResolver().openInputStream(Uri.parse(path));
        }
        File file = new File(path);
        return file.isFile() ? new java.io.FileInputStream(file) : null;
    }

    private static void writeEntry(ZipOutputStream zip, String name, InputStream input)
            throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        copy(input, zip);
        zip.closeEntry();
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("내보내기가 중단되었습니다.");
            }
            output.write(buffer, 0, count);
        }
    }
}
