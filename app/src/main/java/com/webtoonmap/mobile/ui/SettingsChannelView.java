package com.webtoonmap.mobile.ui;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.download.SeriesDownloadService;
import com.webtoonmap.mobile.export.TransferImporter;
import com.webtoonmap.mobile.storage.SourceSettings;
import com.webtoonmap.mobile.storage.WebtoonStorage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsChannelView extends FrameLayout {
    private final MainActivity activity;
    private final TextView version;
    private final RadioGroup sourceGroup;
    private final RadioGroup viewModeGroup;
    private final View joatoonAddressBox;
    private final View manhwabangAddressBox;
    private final View ililtoonAddressBox;
    private final View blacktoonAddressBox;
    private final View wolfdotAddressBox;
    private final View hitomiAddressBox;
    private final EditText joatoonUrl;
    private final EditText manhwabangUrl;
    private final EditText ililtoonUrl;
    private final EditText blacktoonUrl;
    private final EditText wolfdotUrl;
    private final EditText hitomiUrl;
    private final CheckBox autoAdvance;
    private final View autoAdvanceOptions;
    private final EditText autoAdvanceSeconds;
    private final Button saveAutoAdvanceSeconds;
    private final CheckBox lowDataMode;
    private final View lowDataOptions;
    private final EditText lowDataMinutes;
    private final Button saveLowDataMinutes;
    private final Button importButton;
    private final TextView importStatus;
    private final Button cleanupButton;
    private final TextView cleanupStatus;
    private final ActivityResultLauncher<String[]> importLauncher;
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor();
    private boolean importing;
    private boolean cleaning;
    private boolean refreshing;

    public SettingsChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        LayoutInflater.from(activity).inflate(R.layout.channel_settings, this, true);
        version = findViewById(R.id.app_version);
        sourceGroup = findViewById(R.id.source_group);
        viewModeGroup = findViewById(R.id.view_mode_group);
        joatoonAddressBox = findViewById(R.id.joatoon_address_box);
        manhwabangAddressBox = findViewById(R.id.manhwabang_address_box);
        ililtoonAddressBox = findViewById(R.id.ililtoon_address_box);
        blacktoonAddressBox = findViewById(R.id.blacktoon_address_box);
        wolfdotAddressBox = findViewById(R.id.wolfdot_address_box);
        hitomiAddressBox = findViewById(R.id.hitomi_address_box);
        joatoonUrl = findViewById(R.id.joatoon_url);
        manhwabangUrl = findViewById(R.id.manhwabang_url);
        ililtoonUrl = findViewById(R.id.ililtoon_url);
        blacktoonUrl = findViewById(R.id.blacktoon_url);
        wolfdotUrl = findViewById(R.id.wolfdot_url);
        hitomiUrl = findViewById(R.id.hitomi_url);
        autoAdvance = findViewById(R.id.auto_advance);
        autoAdvanceOptions = findViewById(R.id.auto_advance_options);
        autoAdvanceSeconds = findViewById(R.id.auto_advance_seconds);
        saveAutoAdvanceSeconds = findViewById(R.id.save_auto_advance_seconds);
        lowDataMode = findViewById(R.id.low_data_mode);
        lowDataOptions = findViewById(R.id.low_data_options);
        lowDataMinutes = findViewById(R.id.low_data_minutes);
        saveLowDataMinutes = findViewById(R.id.save_low_data_minutes);
        importButton = findViewById(R.id.import_transfer);
        importStatus = findViewById(R.id.import_status);
        cleanupButton = findViewById(R.id.cleanup_temp_files);
        cleanupStatus = findViewById(R.id.cleanup_temp_status);
        importLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) importTransfer(uri);
                });
        sourceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (refreshing) return;
            String source = sourceForCheckedId(checkedId);
            SourceSettings.setSource(activity, source);
            updateAddressVisibility(source);
            activity.applyChannelSettings();
            Toast.makeText(activity, SourceSettings.channelLabel(activity) +
                    " 채널로 변경했습니다.", Toast.LENGTH_SHORT).show();
        });
        viewModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (refreshing) return;
            String mode = checkedId == R.id.view_mode_page
                    ? SourceSettings.VIEW_MODE_PAGE
                    : checkedId == R.id.view_mode_page_fit
                    ? SourceSettings.VIEW_MODE_PAGE_FIT : SourceSettings.VIEW_MODE_SCROLL;
            SourceSettings.setViewMode(activity, mode);
            String message = checkedId == R.id.view_mode_page
                    ? "만화책 모드 2(가로 폭 채우기)로 변경했습니다."
                    : checkedId == R.id.view_mode_page_fit
                    ? "만화책 모드 1(한 페이지 전체 보기)로 변경했습니다."
                    : "웹툰 방식(아래로 스크롤)으로 변경했습니다.";
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        });
        autoAdvance.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (refreshing) return;
            SourceSettings.setAutoAdvanceEnabled(activity, isChecked);
            updateAutoAdvanceControls(isChecked);
            Toast.makeText(activity, isChecked
                            ? "자동 넘기기를 켰습니다."
                            : "자동 넘기기를 껐습니다.",
                    Toast.LENGTH_SHORT).show();
        });
        saveAutoAdvanceSeconds.setOnClickListener(v -> saveAutoAdvanceSeconds());
        lowDataMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (refreshing) return;
            SourceSettings.setLowDataMode(activity, isChecked);
            updateLowDataControls(isChecked);
            Toast.makeText(activity, isChecked
                            ? "저데이터 모드를 켰습니다."
                            : "저데이터 모드를 껐습니다.",
                    Toast.LENGTH_SHORT).show();
        });
        saveLowDataMinutes.setOnClickListener(v -> saveLowDataMinutes());
        findViewById(R.id.save_joatoon_url).setOnClickListener(v -> saveJoatoonUrl());
        findViewById(R.id.save_manhwabang_url).setOnClickListener(v -> saveManhwabangUrl());
        findViewById(R.id.save_ililtoon_url).setOnClickListener(v -> saveIliltoonUrl());
        findViewById(R.id.save_blacktoon_url).setOnClickListener(v -> saveBlacktoonUrl());
        findViewById(R.id.save_wolfdot_url).setOnClickListener(v -> saveWolfdotUrl());
        findViewById(R.id.save_hitomi_url).setOnClickListener(v -> saveHitomiUrl());
        importButton.setOnClickListener(v -> {
            if (!importing) importLauncher.launch(new String[]{"application/zip", "application/octet-stream"});
        });
        cleanupButton.setOnClickListener(v -> confirmTemporaryCleanup());
        refresh();
    }

    public void refresh() {
        refreshing = true;
        String source = SourceSettings.getSource(activity);
        sourceGroup.check(checkedIdForSource(source));
        updateAddressVisibility(source);
        String viewMode = SourceSettings.getViewMode(activity);
        viewModeGroup.check(SourceSettings.VIEW_MODE_PAGE.equals(viewMode)
                ? R.id.view_mode_page
                : SourceSettings.VIEW_MODE_PAGE_FIT.equals(viewMode)
                ? R.id.view_mode_page_fit : R.id.view_mode_scroll);
        joatoonUrl.setText(SourceSettings.getJoatoonUrl(activity));
        manhwabangUrl.setText(SourceSettings.getManhwabangUrl(activity));
        ililtoonUrl.setText(SourceSettings.getIliltoonUrl(activity));
        blacktoonUrl.setText(SourceSettings.getBlacktoonUrl(activity));
        wolfdotUrl.setText(SourceSettings.getWolfdotUrl(activity));
        hitomiUrl.setText(SourceSettings.getHitomiUrl(activity));
        boolean autoAdvanceEnabled = SourceSettings.isAutoAdvanceEnabled(activity);
        autoAdvance.setChecked(autoAdvanceEnabled);
        autoAdvanceSeconds.setText(String.valueOf(
                SourceSettings.getAutoAdvanceSeconds(activity)));
        updateAutoAdvanceControls(autoAdvanceEnabled);
        boolean lowDataEnabled = SourceSettings.isLowDataMode(activity);
        lowDataMode.setChecked(lowDataEnabled);
        lowDataMinutes.setText(String.valueOf(
                SourceSettings.getLowDataRestartMinutes(activity)));
        updateLowDataControls(lowDataEnabled);
        cleanupButton.setEnabled(!cleaning && !importing &&
                !SeriesDownloadService.isRunning());
        try {
            String name = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
            version.setText("버전 " + name);
        } catch (Exception ignored) {
            version.setText("버전 1.5.8");
        }
        refreshing = false;
    }

    private void saveAutoAdvanceSeconds() {
        String raw = autoAdvanceSeconds.getText().toString().trim();
        int seconds;
        try {
            seconds = Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            Toast.makeText(activity, "넘기기 간격을 초 단위 숫자로 입력해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!SourceSettings.setAutoAdvanceSeconds(activity, seconds)) {
            Toast.makeText(activity, "넘기기 간격은 1초부터 3600초 사이로 입력해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        autoAdvanceSeconds.setText(String.valueOf(seconds));
        Toast.makeText(activity, "자동 넘기기 간격을 " + seconds + "초로 저장했습니다.",
                Toast.LENGTH_SHORT).show();
    }

    private void updateAutoAdvanceControls(boolean enabled) {
        autoAdvanceOptions.setAlpha(enabled ? 1f : 0.45f);
        autoAdvanceSeconds.setEnabled(enabled);
        saveAutoAdvanceSeconds.setEnabled(enabled);
    }

    private void saveLowDataMinutes() {
        String raw = lowDataMinutes.getText().toString().trim();
        int minutes;
        try {
            minutes = Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            Toast.makeText(activity, "대기 시간을 분 단위 숫자로 입력해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!SourceSettings.setLowDataRestartMinutes(activity, minutes)) {
            Toast.makeText(activity, "대기 시간은 1분부터 1440분 사이로 입력해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        lowDataMinutes.setText(String.valueOf(minutes));
        Toast.makeText(activity, "자동 이어받기 대기 시간을 " + minutes + "분으로 저장했습니다.",
                Toast.LENGTH_SHORT).show();
    }

    private void updateLowDataControls(boolean enabled) {
        lowDataOptions.setAlpha(enabled ? 1f : 0.45f);
        lowDataMinutes.setEnabled(enabled);
        saveLowDataMinutes.setEnabled(enabled);
    }

    private void importTransfer(Uri uri) {
        if (importing) return;
        importing = true;
        importButton.setEnabled(false);
        cleanupButton.setEnabled(false);
        importButton.setText("가져오는 중…");
        importStatus.setText("파일을 확인하는 중…");
        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("데이터 가져오기")
                .setMessage("파일을 확인하는 중…")
                .setCancelable(false)
                .create();
        progressDialog.show();
        importExecutor.execute(() -> {
            try {
                TransferImporter.Result result = TransferImporter.importArchive(activity, uri,
                        (current, total, title) -> post(() -> {
                            String text = current + "/" + total + " · ‘" + title + "’ 복원 중";
                            importStatus.setText(text);
                            progressDialog.setMessage(text);
                        }));
                post(() -> {
                    importing = false;
                    importButton.setEnabled(true);
                    cleanupButton.setEnabled(!cleaning && !SeriesDownloadService.isRunning());
                    importButton.setText("데이터 가져오기");
                    activity.refreshDownloads();
                    String summary = "가져오기 완료 · " + result.imported.size() + "개 작품";
                    if (!result.errors.isEmpty()) {
                        summary += " · 오류 " + result.errors.size() + "개";
                        importStatus.setText(summary + "\n" + result.errors.get(0));
                    } else {
                        importStatus.setText(summary);
                    }
                    progressDialog.dismiss();
                    new AlertDialog.Builder(activity)
                            .setTitle("가져오기 완료")
                            .setMessage(summary + (result.errors.isEmpty() ? "" : "\n" + result.errors.get(0)))
                            .setPositiveButton("확인", null)
                            .show();
                    Toast.makeText(activity, summary, Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "가져오기에 실패했습니다." : error.getMessage();
                post(() -> {
                    importing = false;
                    importButton.setEnabled(true);
                    cleanupButton.setEnabled(!cleaning && !SeriesDownloadService.isRunning());
                    importButton.setText("데이터 가져오기");
                    importStatus.setText("가져오기 실패 · " + message);
                    progressDialog.dismiss();
                    new AlertDialog.Builder(activity)
                            .setTitle("가져오기 실패")
                            .setMessage(message)
                            .setPositiveButton("확인", null)
                            .show();
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void confirmTemporaryCleanup() {
        if (cleaning) return;
        if (importing) {
            Toast.makeText(activity, "가져오기가 끝난 뒤 임시 파일을 삭제해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (SeriesDownloadService.isRunning()) {
            Toast.makeText(activity, "다운로드가 끝난 뒤 임시 파일을 삭제해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("임시 파일 삭제")
                .setMessage("내보내기·가져오기·뷰어 캐시와 중단된 다운로드 조각만 삭제합니다.\n\n" +
                        "다운로드가 완료된 웹툰, 회차 ZIP, 썸네일과 작품 정보는 삭제하지 않습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> cleanupTemporaryFiles())
                .show();
    }

    private void cleanupTemporaryFiles() {
        if (cleaning) return;
        cleaning = true;
        cleanupButton.setEnabled(false);
        cleanupButton.setText("삭제 중…");
        importButton.setEnabled(false);
        cleanupStatus.setText("안전한 임시 파일을 확인하는 중…");
        cleanupExecutor.execute(() -> {
            WebtoonStorage.CleanupResult result =
                    WebtoonStorage.cleanupTemporaryFiles(activity);
            post(() -> {
                cleaning = false;
                cleanupButton.setEnabled(!importing && !SeriesDownloadService.isRunning());
                cleanupButton.setText("임시 파일 삭제");
                importButton.setEnabled(!importing);
                String message = result.deletedFiles > 0
                        ? "임시 파일 " + result.deletedFiles + "개를 삭제했습니다."
                        : "삭제할 임시 파일이 없습니다.";
                if (result.failedPaths > 0) {
                    message += " 삭제하지 못한 위치 " + result.failedPaths + "곳";
                }
                cleanupStatus.setText(message);
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void saveJoatoonUrl() {
        if (!SourceSettings.setJoatoonUrl(activity, joatoonUrl.getText().toString())) {
            Toast.makeText(activity, "https://로 시작하는 올바른 주소를 입력해 주세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        joatoonUrl.setText(SourceSettings.getJoatoonUrl(activity));
        activity.applyChannelSettings();
        Toast.makeText(activity, "조아툰 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void saveManhwabangUrl() {
        if (!SourceSettings.setManhwabangUrl(activity, manhwabangUrl.getText().toString())) {
            invalidUrl();
            return;
        }
        manhwabangUrl.setText(SourceSettings.getManhwabangUrl(activity));
        activity.applyChannelSettings();
        Toast.makeText(activity, "만화방 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void saveIliltoonUrl() {
        if (!SourceSettings.setIliltoonUrl(activity, ililtoonUrl.getText().toString())) {
            invalidUrl();
            return;
        }
        ililtoonUrl.setText(SourceSettings.getIliltoonUrl(activity));
        activity.applyChannelSettings();
        Toast.makeText(activity, "일일툰 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void saveBlacktoonUrl() {
        if (!SourceSettings.setBlacktoonUrl(activity, blacktoonUrl.getText().toString())) {
            invalidUrl();
            return;
        }
        blacktoonUrl.setText(SourceSettings.getBlacktoonUrl(activity));
        activity.applyChannelSettings();
        Toast.makeText(activity, "블랙툰 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void saveWolfdotUrl() {
        if (!SourceSettings.setWolfdotUrl(activity, wolfdotUrl.getText().toString())) {
            invalidUrl();
            return;
        }
        wolfdotUrl.setText(SourceSettings.getWolfdotUrl(activity));
        activity.applyChannelSettings();
        Toast.makeText(activity, "늑대닷컴 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void saveHitomiUrl() {
        if (!SourceSettings.setHitomiUrl(activity, hitomiUrl.getText().toString())) {
            invalidUrl();
            return;
        }
        hitomiUrl.setText(SourceSettings.getHitomiUrl(activity));
        activity.applyChannelSettings();
        Toast.makeText(activity, "히토미 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void invalidUrl() {
        Toast.makeText(activity, "https://로 시작하는 올바른 주소를 입력해 주세요.",
                Toast.LENGTH_LONG).show();
    }

    private String sourceForCheckedId(int checkedId) {
        if (checkedId == R.id.source_joatoon) return SourceSettings.SOURCE_JOATOON;
        if (checkedId == R.id.source_manhwabang) return SourceSettings.SOURCE_MANHWABANG;
        if (checkedId == R.id.source_ililtoon) return SourceSettings.SOURCE_ILILTOON;
        if (checkedId == R.id.source_blacktoon) return SourceSettings.SOURCE_BLACKTOON;
        if (checkedId == R.id.source_wolfdot) return SourceSettings.SOURCE_WOLFDOT;
        if (checkedId == R.id.source_hitomi) return SourceSettings.SOURCE_HITOMI;
        return SourceSettings.SOURCE_NAVER;
    }

    private int checkedIdForSource(String source) {
        if (SourceSettings.SOURCE_JOATOON.equals(source)) return R.id.source_joatoon;
        if (SourceSettings.SOURCE_MANHWABANG.equals(source)) return R.id.source_manhwabang;
        if (SourceSettings.SOURCE_ILILTOON.equals(source)) return R.id.source_ililtoon;
        if (SourceSettings.SOURCE_BLACKTOON.equals(source)) return R.id.source_blacktoon;
        if (SourceSettings.SOURCE_WOLFDOT.equals(source)) return R.id.source_wolfdot;
        if (SourceSettings.SOURCE_HITOMI.equals(source)) return R.id.source_hitomi;
        return R.id.source_naver;
    }

    private void updateAddressVisibility(String source) {
        joatoonAddressBox.setVisibility(SourceSettings.SOURCE_JOATOON.equals(source) ? VISIBLE : GONE);
        manhwabangAddressBox.setVisibility(SourceSettings.SOURCE_MANHWABANG.equals(source) ? VISIBLE : GONE);
        ililtoonAddressBox.setVisibility(SourceSettings.SOURCE_ILILTOON.equals(source) ? VISIBLE : GONE);
        blacktoonAddressBox.setVisibility(SourceSettings.SOURCE_BLACKTOON.equals(source) ? VISIBLE : GONE);
        wolfdotAddressBox.setVisibility(SourceSettings.SOURCE_WOLFDOT.equals(source) ? VISIBLE : GONE);
        hitomiAddressBox.setVisibility(SourceSettings.SOURCE_HITOMI.equals(source) ? VISIBLE : GONE);
    }

}

