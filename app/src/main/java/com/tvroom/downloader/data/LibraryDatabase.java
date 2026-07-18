package com.tvroom.downloader.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class LibraryDatabase extends SQLiteOpenHelper {
    private static volatile LibraryDatabase instance;

    public static LibraryDatabase get(Context context) {
        if (instance == null) synchronized (LibraryDatabase.class) {
            if (instance == null) instance = new LibraryDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private LibraryDatabase(Context context) { super(context, "tvroom_library.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE videos (id TEXT PRIMARY KEY,title TEXT NOT NULL,page_url TEXT NOT NULL," +
                "thumbnail_path TEXT,file_path TEXT,status TEXT NOT NULL,progress INTEGER NOT NULL DEFAULT 0," +
                "error TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public void upsert(VideoItem item) {
        ContentValues values = values(item);
        values.put("created_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("videos", values, "id=?", new String[]{item.id});
        if (updated == 0) db.insertOrThrow("videos", null, values);
    }

    public void updateProgress(String id, String status, int progress, String error) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("progress", progress);
        values.put("error", error == null ? "" : error);
        getWritableDatabase().update("videos", values, "id=?", new String[]{id});
    }

    public void recoverInterruptedDownloads() {
        ContentValues values = new ContentValues();
        values.put("status", "stopped");
        values.put("progress", 0);
        values.put("error", "앱 종료로 중단되어 임시 파일을 정리했습니다.");
        getWritableDatabase().update("videos", values, "status IN ('queued','downloading')", null);
    }

    public void complete(String id, String thumbnailPath, String filePath) {
        ContentValues values = new ContentValues();
        values.put("status", "complete");
        values.put("progress", 100);
        values.put("error", "");
        values.put("thumbnail_path", thumbnailPath);
        values.put("file_path", filePath);
        getWritableDatabase().update("videos", values, "id=?", new String[]{id});
    }

    public void updateThumbnail(String id, String thumbnailPath) {
        if (thumbnailPath == null || thumbnailPath.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("thumbnail_path", thumbnailPath);
        getWritableDatabase().update("videos", values, "id=?", new String[]{id});
    }

    public List<VideoItem> list() {
        List<VideoItem> items = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,page_url,thumbnail_path,file_path,status,progress,error FROM videos ORDER BY created_at DESC", null)) {
            while (c.moveToNext()) items.add(read(c));
        }
        return items;
    }

    public VideoItem getItem(String id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,page_url,thumbnail_path,file_path,status,progress,error FROM videos WHERE id=?",
                new String[]{id})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public void delete(String id) {
        VideoItem item = getItem(id);
        if (item != null) {
            deleteFile(item.filePath);
            deleteFile(item.thumbnailPath);
        }
        getWritableDatabase().delete("videos", "id=?", new String[]{id});
    }

    private static ContentValues values(VideoItem item) {
        ContentValues values = new ContentValues();
        values.put("id", item.id);
        values.put("title", item.title);
        values.put("page_url", item.pageUrl);
        values.put("thumbnail_path", item.thumbnailPath);
        values.put("file_path", item.filePath);
        values.put("status", item.status);
        values.put("progress", item.progress);
        values.put("error", item.error == null ? "" : item.error);
        return values;
    }

    private static VideoItem read(Cursor c) {
        return new VideoItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5), c.getInt(6), c.getString(7));
    }

    private static void deleteFile(String path) {
        if (path != null && !path.isEmpty()) new File(path).delete();
    }
}
