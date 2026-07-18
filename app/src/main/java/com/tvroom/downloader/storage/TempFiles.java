package com.tvroom.downloader.storage;

import android.content.Context;

import java.io.File;

public final class TempFiles {
    private TempFiles() { }

    public static File root(Context context) {
        return new File(context.getCacheDir(), "tvroom-downloads");
    }

    public static File jobDir(Context context, String id) {
        File dir = new File(root(context), id);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("임시 다운로드 폴더를 만들지 못했습니다.");
        }
        return dir;
    }

    public static CleanupResult cleanup(Context context) {
        CleanupResult result = new CleanupResult();
        deleteChildren(root(context), result);
        return result;
    }

    public static void deleteJob(Context context, String id) {
        deleteTree(new File(root(context), id), null);
    }

    private static void deleteChildren(File dir, CleanupResult result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) deleteTree(file, result);
    }

    private static boolean deleteTree(File file, CleanupResult result) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child, result);
        }
        boolean ok = file.delete();
        if (result != null) {
            if (ok) result.deleted++;
            else result.failed++;
        }
        return ok;
    }

    public static final class CleanupResult {
        public int deleted;
        public int failed;
    }
}
