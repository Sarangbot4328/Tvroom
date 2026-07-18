package com.webtoonmap.mobile.data;

public final class EpisodeItem {
    public final String titleId;
    public final int number;
    public final String title;
    public final int imageCount;
    public final boolean viewed;

    public EpisodeItem(String titleId, int number, String title, int imageCount, boolean viewed) {
        this.titleId = titleId;
        this.number = number;
        this.title = title;
        this.imageCount = imageCount;
        this.viewed = viewed;
    }
}
