package com.tvroom.downloader.data;

public final class VideoItem {
    public final String id;
    public final String title;
    public final String pageUrl;
    public final String thumbnailPath;
    public final String filePath;
    public final String status;
    public final int progress;
    public final String error;

    public VideoItem(String id, String title, String pageUrl, String thumbnailPath,
                     String filePath, String status, int progress, String error) {
        this.id = id;
        this.title = title;
        this.pageUrl = pageUrl;
        this.thumbnailPath = thumbnailPath;
        this.filePath = filePath;
        this.status = status;
        this.progress = progress;
        this.error = error;
    }
}
