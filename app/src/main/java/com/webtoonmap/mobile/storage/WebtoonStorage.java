package com.webtoonmap.mobile.storage;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Locale;

public final class WebtoonStorage {
    private final Context context;
    private final String treeUri;

    public WebtoonStorage(Context context, String treeUri) {
        this.context = context.getApplicationContext();
        this.treeUri = treeUri == null || treeUri.isEmpty() ? null : treeUri;
    }

    public String storageUri() { return treeUri; }

    public String writeThumbnail(String titleId, byte[] bytes) throws IOException {
        if (treeUri == null) {
            File dir = defaultSeriesDir(titleId);
            ensureDirectory(dir);
            File output = new File(dir, "thumbnail.jpg");
            File pending = new File(dir, "thumbnail.jpg.part");
            try (OutputStream out = new FileOutputStream(pending)) { out.write(bytes); }
            if (output.exists() && !output.delete()) {
                pending.delete();
                throw new IOException("기존 썸네일 삭제 실패");
            }
            if (!pending.renameTo(output)) {
                pending.delete();
                throw new IOException("썸네일 저장 완료 처리 실패");
            }
            return output.getAbsolutePath();
        }
        DocumentFile dir = externalSeriesDir(titleId, true);
        if (dir == null) throw new IOException("선택한 저장 폴더에 작품 폴더를 만들 수 없습니다.");
        DocumentFile pending = dir.createFile("application/octet-stream",
                "thumbnail.part-" + System.currentTimeMillis());
        if (pending == null) throw new IOException("임시 썸네일 파일 생성 실패");
        try (OutputStream out = context.getContentResolver().openOutputStream(pending.getUri(), "w")) {
            if (out == null) throw new IOException("선택 폴더를 열 수 없습니다.");
            out.write(bytes);
        } catch (IOException e) {
            pending.delete();
            throw e;
        }
        DocumentFile old = dir.findFile("thumbnail.jpg");
        if (old != null && !old.delete()) {
            pending.delete();
            throw new IOException("기존 썸네일 삭제 실패");
        }
        if (!pending.renameTo("thumbnail.jpg")) {
            pending.delete();
            throw new IOException("썸네일 저장 완료 처리 실패");
        }
        DocumentFile output = dir.findFile("thumbnail.jpg");
        if (output == null) throw new IOException("저장한 썸네일을 찾지 못했습니다.");
        return output.getUri().toString();
    }

    public void writeEpisodeZip(String titleId, int episode, File source) throws IOException {
        String name = episodeName(episode);
        if (treeUri == null) {
            File dir = defaultSeriesDir(titleId);
            ensureDirectory(dir);
            File target = new File(dir, name);
            File pending = new File(dir, name + ".part");
            try {
                copyFile(source, pending);
            } catch (IOException e) {
                pending.delete();
                throw e;
            }
            if (target.exists() && !target.delete()) {
                pending.delete();
                throw new IOException("기존 ZIP 삭제 실패");
            }
            if (!pending.renameTo(target)) {
                pending.delete();
                throw new IOException("ZIP 저장 완료 처리 실패");
            }
            return;
        }
        DocumentFile dir = externalSeriesDir(titleId, true);
        if (dir == null) throw new IOException("선택한 저장 폴더에 작품 폴더를 만들 수 없습니다.");
        DocumentFile pending = dir.createFile("application/octet-stream",
                name + ".part-" + System.currentTimeMillis());
        if (pending == null) throw new IOException("임시 ZIP 파일 생성 실패");
        try (InputStream in = new FileInputStream(source);
             OutputStream out = context.getContentResolver().openOutputStream(pending.getUri(), "w")) {
            if (out == null) throw new IOException("선택 폴더를 열 수 없습니다.");
            copy(in, out);
        } catch (IOException e) {
            pending.delete();
            throw e;
        }
        DocumentFile old = dir.findFile(name);
        if (old != null && !old.delete()) {
            pending.delete();
            throw new IOException("기존 ZIP 삭제 실패");
        }
        if (!pending.renameTo(name)) {
            pending.delete();
            throw new IOException("ZIP 저장 완료 처리 실패");
        }
    }

    public InputStream openEpisodeZip(String titleId, int episode) throws IOException {
        String name = episodeName(episode);
        if (treeUri == null) {
            File file = new File(defaultSeriesDir(titleId), name);
            if (!file.isFile()) throw new IOException(name + " 파일이 없습니다.");
            return new FileInputStream(file);
        }
        DocumentFile dir = externalSeriesDir(titleId, false);
        DocumentFile file = dir == null ? null : dir.findFile(name);
        if (file == null || !file.isFile()) throw new IOException(name + " 파일이 없습니다.");
        InputStream in = context.getContentResolver().openInputStream(file.getUri());
        if (in == null) throw new IOException(name + " 파일을 열 수 없습니다.");
        return in;
    }

    public boolean episodeZipExists(String titleId, int episode) {
        String name = episodeName(episode);
        if (treeUri == null) return new File(defaultSeriesDir(titleId), name).isFile();
        DocumentFile dir = externalSeriesDir(titleId, false);
        DocumentFile file = dir == null ? null : dir.findFile(name);
        return file != null && file.isFile();
    }

    public int cleanupIncomplete(String titleId) throws IOException {
        int deleted = 0;
        if (treeUri == null) {
            File dir = defaultSeriesDir(titleId);
            File[] children = dir.listFiles();
            if (children == null) return 0;
            for (File child : children) {
                if (!isIncompleteArtifact(child.getName(), child.isDirectory())) continue;
                if (!deleteRecursively(child)) {
                    throw new IOException("미완성 회차 파일 정리 실패: " + child.getName());
                }
                deleted++;
            }
            return deleted;
        }

        DocumentFile dir = externalSeriesDir(titleId, false);
        if (dir == null) return 0;
        for (DocumentFile child : dir.listFiles()) {
            String name = child.getName();
            if (name == null || !isIncompleteArtifact(name, child.isDirectory())) continue;
            if (!child.delete()) {
                throw new IOException("미완성 회차 파일 정리 실패: " + name);
            }
            deleted++;
        }
        return deleted;
    }

    public boolean deleteSeries(String titleId) {
        if (treeUri == null) return deleteRecursively(defaultSeriesDir(titleId));
        DocumentFile dir = externalSeriesDir(titleId, false);
        return dir == null || dir.delete();
    }

    private File defaultSeriesDir(String titleId) {
        return new File(context.getFilesDir(), "webtoons/" + titleId);
    }

    private DocumentFile externalSeriesDir(String titleId, boolean create) {
        DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri));
        if (root == null || !root.isDirectory()) return null;
        DocumentFile dir = root.findFile(titleId);
        if (dir == null && create) dir = root.createDirectory(titleId);
        if (dir != null && !dir.isDirectory()) return null;
        return dir;
    }

    private static String episodeName(int episode) {
        return String.format(Locale.US, "%03d.zip", episode);
    }

    private static boolean isIncompleteArtifact(String name, boolean directory) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.contains(".part") || lower.endsWith(".tmp")) return true;
        if (directory && lower.matches("\\d{1,4}([_-](images?|temp|tmp))?")) return true;
        return !directory && lower.matches("\\d{1,4}[-_]\\d{1,4}\\.(jpe?g|png|webp|gif)");
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("저장 폴더 생성 실패");
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("파일 저장 중단");
            }
            out.write(buffer, 0, count);
        }
    }

    public static final class CleanupResult {
        public final int deletedFiles;
        public final int failedPaths;

        CleanupResult(int deletedFiles, int failedPaths) {
            this.deletedFiles = deletedFiles;
            this.failedPaths = failedPaths;
        }
    }

    public static CleanupResult cleanupTemporaryFiles(Context context) {
        Context appContext = context.getApplicationContext();
        int deleted = 0;
        int failed = 0;
        String[] cacheDirectories = {
                "exports", "download-zips", "imports", "import-stage", "viewer"
        };
        for (String name : cacheDirectories) {
            File directory = new File(appContext.getCacheDir(), name);
            int fileCount = countFiles(directory);
            if (deleteRecursively(directory)) deleted += fileCount;
            else failed++;
        }

        File webtoonRoot = new File(appContext.getFilesDir(), "webtoons");
        File[] seriesDirectories = webtoonRoot.listFiles(File::isDirectory);
        if (seriesDirectories != null) {
            WebtoonStorage internal = new WebtoonStorage(appContext, null);
            for (File directory : seriesDirectories) {
                try {
                    deleted += internal.cleanupIncomplete(directory.getName());
                } catch (Exception ignored) {
                    failed++;
                }
            }
        }

        try {
            for (SeriesItem item : LibraryDatabase.get(appContext).listSeries()) {
                if (item.storageUri == null || item.storageUri.isEmpty()) continue;
                try {
                    deleted += new WebtoonStorage(appContext, item.storageUri)
                            .cleanupIncomplete(item.titleId);
                } catch (Exception ignored) {
                    failed++;
                }
            }
        } catch (Exception ignored) {
            failed++;
        }
        return new CleanupResult(deleted, failed);
    }

    private static int countFiles(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return 1;
        int count = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) count += countFiles(child);
        return count;
    }

    public static boolean deleteRecursively(File file) {
        if (!file.exists()) return true;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) if (!deleteRecursively(child)) return false;
        return file.delete();
    }
}
