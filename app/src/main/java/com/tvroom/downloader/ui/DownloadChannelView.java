package com.tvroom.downloader.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.tvroom.downloader.MainActivity;
import com.tvroom.downloader.R;
import com.tvroom.downloader.data.LibraryDatabase;
import com.tvroom.downloader.data.VideoItem;
import com.tvroom.downloader.download.VideoDownloadService;
import com.tvroom.downloader.export.VideoExportService;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DownloadChannelView extends android.widget.FrameLayout {
    private final MainActivity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final VideoAdapter adapter = new VideoAdapter();
    private final SwipeRefreshLayout swipe;
    private final View empty;
    private final TextView status;
    private final Button exportButton;
    private final Button refreshButton;
    private final Set<String> selectedIds = new LinkedHashSet<>();
    private boolean exportSelection;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(VideoDownloadService.EXTRA_MESSAGE);
            if (message != null) status.setText(message);
            refresh();
        }
    };

    public DownloadChannelView(MainActivity activity) {
        super(activity); this.activity = activity;
        LayoutInflater.from(activity).inflate(R.layout.channel_downloads, this, true);
        RecyclerView list = findViewById(R.id.video_list);
        empty = findViewById(R.id.empty); status = findViewById(R.id.download_status);
        exportButton = findViewById(R.id.export);
        refreshButton = findViewById(R.id.refresh);
        swipe = findViewById(R.id.swipe);
        list.setLayoutManager(new LinearLayoutManager(activity)); list.setAdapter(adapter);
        exportButton.setOnClickListener(v -> {
            if (exportSelection) chooseExportFolder(); else beginExportSelection();
        });
        refreshButton.setOnClickListener(v -> {
            if (!cancelExportSelection()) refresh();
        });
        swipe.setOnRefreshListener(this::refresh); refresh();
    }

    public void refresh() {
        swipe.setRefreshing(true);
        executor.execute(() -> {
            List<VideoItem> rows = LibraryDatabase.get(activity).list();
            post(() -> {
                adapter.setItems(rows);
                empty.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
                swipe.setRefreshing(false);
                updateExportControls();
            });
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(VideoDownloadService.ACTION_PROGRESS);
            filter.addAction(VideoExportService.ACTION_PROGRESS);
            ContextCompat.registerReceiver(activity, receiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED); receiverRegistered = true;
        }
    }

    private void beginExportSelection() {
        if (VideoExportService.isRunning()) {
            Toast.makeText(activity, "이미 영상을 내보내고 있습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.hasExportableItems()) {
            Toast.makeText(activity, "내보낼 수 있는 완료 영상이 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        exportSelection = true;
        selectedIds.clear();
        status.setText("내보낼 영상을 여러 개 선택할 수 있습니다.");
        adapter.notifyDataSetChanged();
        updateExportControls();
    }

    private void chooseExportFolder() {
        if (selectedIds.isEmpty()) return;
        activity.chooseExportFolder();
    }

    public void onExportFolderSelected(Uri folder) {
        List<VideoItem> selected = adapter.selectedItems();
        if (selected.isEmpty()) {
            cancelExportSelection();
            return;
        }
        if (!VideoExportService.start(activity, selected, folder)) {
            String message = VideoExportService.isRunning()
                    ? "이미 영상을 내보내고 있습니다."
                    : "내보내기 서비스를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.";
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            return;
        }
        int count = selected.size();
        exportSelection = false;
        selectedIds.clear();
        adapter.notifyDataSetChanged();
        status.setText("선택한 영상 " + count + "개를 MP4로 내보내는 중…");
        updateExportControls();
    }

    public boolean cancelExportSelection() {
        if (!exportSelection) return false;
        exportSelection = false;
        selectedIds.clear();
        status.setText("저장한 영상을 오프라인으로 볼 수 있습니다");
        adapter.notifyDataSetChanged();
        updateExportControls();
        return true;
    }

    private void updateExportControls() {
        if (exportSelection) {
            exportButton.setText("내보내기 (" + selectedIds.size() + ")");
            exportButton.setEnabled(!selectedIds.isEmpty());
            refreshButton.setText("취소");
            swipe.setEnabled(false);
        } else {
            exportButton.setText(VideoExportService.isRunning() ? "내보내는 중" : "내보내기");
            exportButton.setEnabled(!VideoExportService.isRunning() && adapter.hasExportableItems());
            refreshButton.setText("새로고침");
            swipe.setEnabled(true);
        }
    }
    @Override protected void onDetachedFromWindow() {
        if (receiverRegistered) { activity.unregisterReceiver(receiver); receiverRegistered = false; }
        super.onDetachedFromWindow();
    }

    private final class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.Holder> {
        private final List<VideoItem> items = new ArrayList<>();
        void setItems(List<VideoItem> value) { items.clear(); items.addAll(value); notifyDataSetChanged(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_video, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            VideoItem item = items.get(position);
            holder.title.setText(item.title); holder.status.setText(statusText(item));
            holder.thumbnail.setImageDrawable(null);
            if (item.thumbnailPath != null && new File(item.thumbnailPath).isFile()) {
                holder.thumbnail.setImageBitmap(BitmapFactory.decodeFile(item.thumbnailPath));
            }
            boolean playable = "complete".equals(item.status) && item.filePath != null && new File(item.filePath).isFile();
            holder.exportCheck.setOnCheckedChangeListener(null);
            holder.exportCheck.setVisibility(exportSelection ? VISIBLE : GONE);
            holder.exportCheck.setEnabled(playable);
            holder.exportCheck.setChecked(selectedIds.contains(item.id));
            holder.exportCheck.setOnCheckedChangeListener((button, checked) -> {
                if (!playable) return;
                if (checked) selectedIds.add(item.id); else selectedIds.remove(item.id);
                updateExportControls();
            });
            holder.play.setVisibility(exportSelection ? GONE : VISIBLE);
            holder.delete.setVisibility(exportSelection ? GONE : VISIBLE);
            holder.play.setEnabled(playable);
            holder.play.setOnClickListener(v -> {
                if (!playable) return;
                activity.startActivity(new Intent(activity, PlayerActivity.class)
                        .putExtra(PlayerActivity.EXTRA_PATH, item.filePath)
                        .putExtra(PlayerActivity.EXTRA_TITLE, item.title));
            });
            holder.itemView.setOnClickListener(v -> {
                if (exportSelection && playable) holder.exportCheck.setChecked(!holder.exportCheck.isChecked());
                else holder.play.performClick();
            });
            holder.delete.setOnClickListener(v -> confirmDelete(item));
        }
        @Override public int getItemCount() { return items.size(); }
        boolean hasExportableItems() {
            for (VideoItem item : items) {
                if ("complete".equals(item.status) && item.filePath != null
                        && new File(item.filePath).isFile()) return true;
            }
            return false;
        }
        List<VideoItem> selectedItems() {
            List<VideoItem> result = new ArrayList<>();
            for (VideoItem item : items) if (selectedIds.contains(item.id)) result.add(item);
            return result;
        }
        private String statusText(VideoItem item) {
            if ("complete".equals(item.status)) {
                boolean hls = item.filePath != null
                        && item.filePath.toLowerCase(java.util.Locale.US).endsWith(".m3u8");
                return hls ? "다운로드 완료 · 오프라인 HLS · 눌러서 재생"
                        : "다운로드 완료 · 눌러서 재생";
            }
            if ("downloading".equals(item.status)) return "다운로드 중 · " + item.progress + "%";
            if ("queued".equals(item.status)) return "다운로드 준비 중";
            if ("stopped".equals(item.status)) return "다운로드 중단됨 · 임시 파일 삭제 완료";
            return "오류 · " + (item.error == null ? "다운로드 실패" : item.error);
        }
        final class Holder extends RecyclerView.ViewHolder {
            final ImageView thumbnail; final TextView title, status; final Button play, delete;
            final CheckBox exportCheck;
            Holder(View view) { super(view); thumbnail = view.findViewById(R.id.thumbnail);
                title = view.findViewById(R.id.title); status = view.findViewById(R.id.status);
                play = view.findViewById(R.id.play); delete = view.findViewById(R.id.delete);
                exportCheck = view.findViewById(R.id.export_check); }
        }
    }

    private void confirmDelete(VideoItem item) {
        if (VideoDownloadService.isRunning() && ("queued".equals(item.status) || "downloading".equals(item.status))) {
            Toast.makeText(activity, "먼저 다운로드를 중단해 주세요.", Toast.LENGTH_LONG).show(); return;
        }
        new AlertDialog.Builder(activity).setTitle("영상 삭제")
                .setMessage("‘" + item.title + "’ 영상과 썸네일을 삭제할까요?")
                .setNegativeButton("취소", null).setPositiveButton("삭제", (d, w) -> {
                    executor.execute(() -> {
                        PlayerActivity.clearSavedPosition(activity, item.filePath);
                        LibraryDatabase.get(activity).delete(item.id);
                        post(this::refresh);
                    });
                }).show();
    }
}
