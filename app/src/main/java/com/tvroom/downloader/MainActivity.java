package com.tvroom.downloader;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tvroom.downloader.ui.DownloadChannelView;
import com.tvroom.downloader.ui.SettingsChannelView;
import com.tvroom.downloader.ui.SystemBarInsets;
import com.tvroom.downloader.ui.TVRoomChannelView;
import com.tvroom.downloader.activation.ActivationActivity;
import com.tvroom.downloader.activation.ActivationStore;
import com.tvroom.downloader.data.LibraryDatabase;
import com.tvroom.downloader.download.VideoDownloadService;
import com.tvroom.downloader.export.VideoExportService;
import com.tvroom.downloader.storage.TempFiles;

public final class MainActivity extends AppCompatActivity {
    private FrameLayout content;
    private Button tvroomButton, downloadsButton, settingsButton;
    private TVRoomChannelView tvroomView;
    private DownloadChannelView downloadsView;
    private SettingsChannelView settingsView;
    private ActivityResultLauncher<Uri> exportFolderPicker;
    private int selected;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ActivationStore.isActivated(this)) {
            startActivity(new Intent(this, ActivationActivity.class));
            finish();
            return;
        }
        if (!VideoDownloadService.isRunning()) {
            VideoDownloadService.clearFinishedNotification(this);
        }
        if (!VideoExportService.isRunning()) {
            VideoExportService.clearFinishedNotification(this);
        }
        exportFolderPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri == null || downloadsView == null) return;
                    try {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (Exception ignored) { }
                    downloadsView.onExportFolderSelected(uri);
                });
        if (!VideoDownloadService.isRunning()) {
            TempFiles.cleanup(this);
            LibraryDatabase.get(this).recoverInterruptedDownloads();
        }
        setContentView(R.layout.activity_main);
        SystemBarInsets.apply(this, findViewById(R.id.main_root), true);
        content = findViewById(R.id.content);
        tvroomButton = findViewById(R.id.nav_tvroom);
        downloadsButton = findViewById(R.id.nav_downloads);
        settingsButton = findViewById(R.id.nav_settings);
        tvroomView = new TVRoomChannelView(this);
        downloadsView = new DownloadChannelView(this);
        settingsView = new SettingsChannelView(this);
        tvroomButton.setOnClickListener(v -> { showTvroom(); tvroomView.goHome(); });
        downloadsButton.setOnClickListener(v -> showDownloads());
        settingsButton.setOnClickListener(v -> showSettings());
        showTvroom();
        requestNotificationPermission();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (selected == 1 && downloadsView.cancelExportSelection()) return;
                if (selected == 0 && tvroomView.isFullscreen()) {
                    tvroomView.exitFullscreen();
                    return;
                }
                if (selected != 0) showTvroom();
                else if (tvroomView.canGoBack()) tvroomView.goBack();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
    }

    public void showDownloads() { selected = 1; downloadsView.refresh(); swap(downloadsView); tint(); }
    public void refreshDownloads() { if (downloadsView != null) downloadsView.refresh(); }
    public void chooseExportFolder() { exportFolderPicker.launch(null); }
    private void showTvroom() { selected = 0; swap(tvroomView); tint(); }
    private void showSettings() { selected = 2; settingsView.refresh(); swap(settingsView); tint(); }

    public void applySiteAddress() {
        boolean showing = selected == 0;
        if (tvroomView != null) tvroomView.destroy();
        tvroomView = new TVRoomChannelView(this);
        if (showing) swap(tvroomView);
    }

    private void swap(android.view.View view) {
        if (view.getParent() == content) return;
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    private void tint() {
        int active = ContextCompat.getColor(this, R.color.green_dark);
        int idle = ContextCompat.getColor(this, R.color.text_secondary);
        tvroomButton.setTextColor(selected == 0 ? active : idle);
        downloadsButton.setTextColor(selected == 1 ? active : idle);
        settingsButton.setTextColor(selected == 2 ? active : idle);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 400);
        }
    }

    @Override protected void onDestroy() {
        if (tvroomView != null) tvroomView.destroy();
        super.onDestroy();
    }
}
