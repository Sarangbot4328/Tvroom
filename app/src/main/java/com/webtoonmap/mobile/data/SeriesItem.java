package com.webtoonmap.mobile.data;

public final class SeriesItem {
    public final String titleId;
    public final String title;
    public final String description;
    public final String tags;
    public final String thumbnailPath;
    public final String storageUri;
    public final String status;
    public final int episodeCount;

    public SeriesItem(String titleId, String title, String description, String tags,
                      String thumbnailPath, String storageUri, String status, int episodeCount) {
        this.titleId = titleId;
        this.title = title;
        this.description = description;
        this.tags = tags;
        this.thumbnailPath = thumbnailPath;
        this.storageUri = storageUri;
        this.status = status;
        this.episodeCount = episodeCount;
    }
}
