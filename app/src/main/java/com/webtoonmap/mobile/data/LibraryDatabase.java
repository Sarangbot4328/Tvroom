package com.webtoonmap.mobile.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class LibraryDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "webtoon_map.db";
    private static final int DB_VERSION = 3;
    private static volatile LibraryDatabase instance;

    public static LibraryDatabase get(Context context) {
        if (instance == null) {
            synchronized (LibraryDatabase.class) {
                if (instance == null) instance = new LibraryDatabase(context.getApplicationContext());
            }
        }
        return instance;
    }

    private LibraryDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE series (" +
                "title_id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', " +
                "tags TEXT NOT NULL DEFAULT '', thumbnail_path TEXT, storage_uri TEXT, status TEXT NOT NULL DEFAULT 'downloading', " +
                "downloaded_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE episodes (" +
                "title_id TEXT NOT NULL, episode_no INTEGER NOT NULL, title TEXT NOT NULL, " +
                "image_count INTEGER NOT NULL DEFAULT 0, viewed INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(title_id, episode_no), " +
                "FOREIGN KEY(title_id) REFERENCES series(title_id) ON DELETE CASCADE)");
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE series ADD COLUMN storage_uri TEXT");
        if (oldVersion < 3) db.execSQL(
                "ALTER TABLE episodes ADD COLUMN viewed INTEGER NOT NULL DEFAULT 0");
    }

    public void upsertSeries(SeriesItem item) {
        ContentValues v = new ContentValues();
        v.put("title_id", item.titleId);
        v.put("title", item.title);
        v.put("description", item.description);
        v.put("tags", item.tags);
        v.put("thumbnail_path", item.thumbnailPath);
        v.put("storage_uri", item.storageUri);
        v.put("status", item.status);
        v.put("downloaded_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("series", v, "title_id=?", new String[]{item.titleId});
        if (updated == 0) db.insertOrThrow("series", null, v);
    }

    public void setSeriesStatus(String titleId, String status) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("downloaded_at", System.currentTimeMillis());
        getWritableDatabase().update("series", v, "title_id=?", new String[]{titleId});
    }

    public void upsertEpisode(EpisodeItem item) {
        ContentValues v = new ContentValues();
        v.put("title_id", item.titleId);
        v.put("episode_no", item.number);
        v.put("title", item.title);
        v.put("image_count", item.imageCount);
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("episodes", v, "title_id=? AND episode_no=?",
                new String[]{item.titleId, String.valueOf(item.number)});
        if (updated == 0) db.insertOrThrow("episodes", null, v);
    }

    public boolean hasCompleteEpisode(String titleId, int number) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT image_count FROM episodes WHERE title_id=? AND episode_no=?",
                new String[]{titleId, String.valueOf(number)})) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }

    public List<SeriesItem> listSeries() {
        List<SeriesItem> out = new ArrayList<>();
        String sql = "SELECT s.title_id,s.title,s.description,s.tags,s.thumbnail_path,s.storage_uri,s.status," +
                "COUNT(e.episode_no) FROM series s LEFT JOIN episodes e ON e.title_id=s.title_id " +
                "GROUP BY s.title_id ORDER BY s.downloaded_at DESC";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                out.add(new SeriesItem(c.getString(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7)));
            }
        }
        return out;
    }

    public SeriesItem getSeries(String titleId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT title_id,title,description,tags,thumbnail_path,storage_uri,status FROM series WHERE title_id=?",
                new String[]{titleId})) {
            if (!c.moveToFirst()) return null;
            return new SeriesItem(c.getString(0), c.getString(1), c.getString(2),
                    c.getString(3), c.getString(4), c.getString(5), c.getString(6), 0);
        }
    }

    public List<EpisodeItem> listEpisodes(String titleId) {
        List<EpisodeItem> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT episode_no,title,image_count,viewed FROM episodes WHERE title_id=? ORDER BY episode_no",
                new String[]{titleId})) {
            while (c.moveToNext()) {
                out.add(new EpisodeItem(titleId, c.getInt(0), c.getString(1),
                        c.getInt(2), c.getInt(3) != 0));
            }
        }
        return out;
    }

    public void markEpisodeViewed(String titleId, int number) {
        ContentValues v = new ContentValues();
        v.put("viewed", 1);
        getWritableDatabase().update("episodes", v, "title_id=? AND episode_no=?",
                new String[]{titleId, String.valueOf(number)});
    }

    public void setEpisodeViewed(String titleId, int number, boolean viewed) {
        ContentValues v = new ContentValues();
        v.put("viewed", viewed ? 1 : 0);
        getWritableDatabase().update("episodes", v, "title_id=? AND episode_no=?",
                new String[]{titleId, String.valueOf(number)});
    }

    public void deleteSeries(String titleId) {
        getWritableDatabase().delete("series", "title_id=?", new String[]{titleId});
    }
}
