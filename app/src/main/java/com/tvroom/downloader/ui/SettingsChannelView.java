package com.tvroom.downloader.ui;

import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.tvroom.downloader.MainActivity;
import com.tvroom.downloader.R;
import com.tvroom.downloader.download.VideoDownloadService;
import com.tvroom.downloader.storage.AppSettings;
import com.tvroom.downloader.storage.TempFiles;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsChannelView extends android.widget.FrameLayout {
    private final MainActivity activity;
    private final EditText url;
    private final Button cleanup;
    private final TextView cleanupStatus, version;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean cleaning;

    public SettingsChannelView(MainActivity activity) {
        super(activity); this.activity = activity;
        LayoutInflater.from(activity).inflate(R.layout.channel_settings, this, true);
        url = findViewById(R.id.tvroom_url); cleanup = findViewById(R.id.cleanup_temp_files);
        cleanupStatus = findViewById(R.id.cleanup_temp_status); version = findViewById(R.id.app_version);
        findViewById(R.id.save_tvroom_url).setOnClickListener(v -> saveAddress());
        cleanup.setOnClickListener(v -> confirmCleanup()); refresh();
    }

    public void refresh() {
        url.setText(AppSettings.getSiteUrl(activity));
        cleanup.setEnabled(!cleaning && !VideoDownloadService.isRunning());
        try { version.setText("버전 " + activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName); }
        catch (Exception ignored) { version.setText("버전 1.1.3"); }
    }

    private void saveAddress() {
        if (!AppSettings.setSiteUrl(activity, url.getText().toString())) {
            Toast.makeText(activity, "https://로 시작하는 올바른 주소를 입력해 주세요.", Toast.LENGTH_LONG).show(); return;
        }
        url.setText(AppSettings.getSiteUrl(activity)); activity.applySiteAddress();
        Toast.makeText(activity, "티비룸 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void confirmCleanup() {
        if (VideoDownloadService.isRunning()) {
            Toast.makeText(activity, "다운로드가 끝나거나 중단된 뒤 삭제해 주세요.", Toast.LENGTH_LONG).show(); return;
        }
        new AlertDialog.Builder(activity).setTitle("임시 파일 삭제")
                .setMessage("중단·실패한 다운로드 조각과 변환 캐시만 삭제합니다. 완료 영상은 유지됩니다.")
                .setNegativeButton("취소", null).setPositiveButton("삭제", (d, w) -> cleanup()).show();
    }

    private void cleanup() {
        cleaning = true; cleanup.setEnabled(false); cleanup.setText("삭제 중…");
        cleanupStatus.setText("임시 파일을 확인하는 중…");
        executor.execute(() -> {
            TempFiles.CleanupResult result = TempFiles.cleanup(activity);
            post(() -> {
                cleaning = false; cleanup.setEnabled(true); cleanup.setText("임시 파일 삭제");
                String message = result.deleted == 0 ? "삭제할 임시 파일이 없습니다." :
                        "임시 파일 " + result.deleted + "개를 삭제했습니다.";
                if (result.failed > 0) message += " · 실패 " + result.failed + "개";
                cleanupStatus.setText(message); Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            });
        });
    }
}
