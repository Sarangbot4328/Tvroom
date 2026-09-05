package com.tvroom.downloader.ui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.tvroom.downloader.MainActivity;
import com.tvroom.downloader.data.LibraryDatabase;
import com.tvroom.downloader.data.VideoItem;
import com.tvroom.downloader.download.VideoDownloadService;
import com.tvroom.downloader.web.CaptureState;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Resolves one episode at a time without navigating the user's player. */
final class BulkDownloadController {
    private final MainActivity activity;
    private final TVRoomChannelView owner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> episodes = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private TVRoomChannelView resolver;
    private boolean active;
    private int index, skipped, completed;
    private String waitingId;
    private long deadline;
    private final Runnable tick = this::advance;

    BulkDownloadController(MainActivity activity, TVRoomChannelView owner) {
        this.activity = activity; this.owner = owner;
    }
    boolean isActive() { return active; }

    void choose(WebView page) {
        if (active) return;
        try (InputStream in = activity.getAssets().open("tvroom_episodes.js")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            page.evaluateJavascript(out.toString("UTF-8"), result -> {
                if (active || activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    Object decoded = new JSONTokener(result).nextValue();
                    JSONArray rows = new JSONArray((String) decoded);
                    List<JSONObject> candidates = new ArrayList<>();
                    StringBuilder preview = new StringBuilder();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        candidates.add(row);
                        preview.append(row.getString("title")).append("\n");
                    }
                    if (candidates.isEmpty()) {
                        new AlertDialog.Builder(activity).setTitle("회차 목록을 찾지 못했습니다")
                            .setMessage("작품의 회차 목록이 표시된 페이지에서 다시 눌러 주세요. 목록이 인식되지 않으면 각 회차에서 대기열 추가를 사용할 수 있습니다.")
                            .setPositiveButton("확인", null).show();
                        return;
                    }
                    new AlertDialog.Builder(activity).setTitle("전체 다운로드 · " + candidates.size() + "개")
                        .setMessage(preview + "\n위 회차를 순서대로 다운로드합니다. 저장된 회차와 대기열에 있는 회차는 건너뜁니다. 자동 분석 중에는 앱을 열어 두세요.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("전체 다운로드", (d, w) -> start(candidates)).show();
                } catch (Exception error) {
                    Toast.makeText(activity, "회차 목록을 읽지 못했습니다. 페이지를 새로고침해 주세요.", Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception error) {
            Toast.makeText(activity, "회차 분석을 시작하지 못했습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void start(List<JSONObject> rows) {
        if (active) return;
        episodes.clear(); episodes.addAll(rows); failures.clear();
        index = 0; skipped = 0; completed = 0; waitingId = null; active = true;
        owner.setKeepScreenOn(true);
        advance();
    }

    private void advance() {
        if (!active) return;
        LibraryDatabase db = LibraryDatabase.get(activity);
        if (waitingId != null) {
            VideoItem item = db.getItem(waitingId);
            if (item != null && ("queued".equals(item.status) || "downloading".equals(item.status))) {
                schedule(); return;
            }
            if (item != null && "stopped".equals(item.status)) { cancel(); return; }
            if (item != null && "complete".equals(item.status)) completed++;
            else failures.add(episodes.get(index).optString("title") + " · 다운로드 실패");
            waitingId = null; index++;
        }
        while (index < episodes.size() && db.hasDownload(episodes.get(index).optString("url"))) {
            disposeResolver(); skipped++; index++;
        }
        if (index >= episodes.size()) { finish(); return; }
        owner.bulkState("전체 " + (index + 1) + "/" + episodes.size());
        if (resolver == null) {
            if (VideoDownloadService.isRunning()) { schedule(); return; }
            resolver = new TVRoomChannelView(activity, true);
            // Keep a live rendering surface behind the visible browser.
            owner.addView(resolver, 0, new FrameLayout.LayoutParams(-1, -1));
            resolver.loadEpisode(episodes.get(index).optString("url"));
            deadline = SystemClock.elapsedRealtime() + 60000;
        }
        CaptureState.Snapshot snapshot = resolver.resolvedSnapshot();
        if (snapshot != null && !android.net.Uri.parse(snapshot.pageUrl).getPath().replaceAll("/+$", "")
                .equals(android.net.Uri.parse(episodes.get(index).optString("url")).getPath().replaceAll("/+$", ""))) {
            failures.add(episodes.get(index).optString("title") + " · 다른 페이지로 이동됨");
            index++; disposeResolver(); schedule(); return;
        }
        if (snapshot != null) {
            try {
                if (VideoDownloadService.start(activity, snapshot)) waitingId = snapshot.id;
                else { skipped++; index++; }
            } catch (Exception error) {
                failures.add(episodes.get(index).optString("title") + " · 서비스 시작 실패"); index++;
            }
            disposeResolver();
        } else if (SystemClock.elapsedRealtime() >= deadline) {
            failures.add(episodes.get(index).optString("title") + " · 자동 재생/분석 시간 초과");
            index++; disposeResolver();
        }
        schedule();
    }

    private void schedule() { handler.postDelayed(tick, 1000); }
    private void disposeResolver() {
        if (resolver != null) { owner.removeView(resolver); resolver.destroy(); resolver = null; }
    }
    void cancel() {
        active = false; handler.removeCallbacks(tick); disposeResolver();
        owner.setKeepScreenOn(false);
        owner.bulkState("전체 다운로드");
    }
    private void finish() {
        cancel();
        String message = "완료 " + completed + "개 · 이미 저장/대기 " + skipped + "개 · 실패 " + failures.size() + "개";
        if (!failures.isEmpty()) message += "\n\n" + android.text.TextUtils.join("\n", failures)
                + "\n\n실패한 회차는 직접 재생한 뒤 다운로드하거나 전체 다운로드를 다시 시도해 주세요.";
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            new AlertDialog.Builder(activity).setTitle("전체 다운로드 결과")
                .setMessage(message).setPositiveButton("확인", null).show();
        }
    }
}
