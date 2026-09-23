package com.tvroom.downloader;

import android.app.Application;

import com.tvroom.downloader.storage.AppSettings;

public final class TVRoomApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppSettings.applyNightMode(this);
    }
}
