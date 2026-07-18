package com.tvroom.downloader.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DownloadChannelView extends android.widget.FrameLayout {
    private final MainActivity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final VideoAdapter adapter = new VideoAdapter();
    private final SwipeRefreshLayout swipe;
    private final View empty;
    private final TextView status;
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
        swipe = findViewById(R.id.swipe);
        list.setLayoutManager(new LinearLayoutManager(activity)); list.setAdapter(adapter);
        findViewById(R.id.refresh).setOnClickListener(v -> refresh());
        swipe.setOnRefreshListener(this::refresh); refresh();
    }

    public void refresh() {
        swipe.setRefreshing(true);
        executor.execute(() -> {
            List<VideoItem> rows = LibraryDatabase.get(activity).list();
            post(() -> { adapter.setItems(rows); empty.setVisibility(rows.isEmpty() ? VISIBLE : GONE); swipe.setRefreshing(false); });
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(activity, receiver, new IntentFilter(VideoDownloadService.ACTION_PROGRESS),
                    ContextCompat.RECEIVER_NOT_EXPORTED); receiverRegistered = true;
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
            holder.play.setEnabled(playable);
            holder.play.setOnClickListener(v -> {
                if (!playable) return;
                activity.startActivity(new Intent(activity, PlayerActivity.class)
                        .putExtra(PlayerActivity.EXTRA_PATH, item.filePath)
                        .putExtra(PlayerActivity.EXTRA_TITLE, item.title));
            });
            holder.itemView.setOnClickListener(v -> holder.play.performClick());
            holder.delete.setOnClickListener(v -> confirmDelete(item));
        }
        @Override public int getItemCount() { return items.size(); }
        private String statusText(VideoItem item) {
            if ("complete".equals(item.status)) return "다운로드 완료 · 눌러서 재생";
            if ("downloading".equals(item.status)) return "다운로드 중 · " + item.progress + "%";
            if ("queued".equals(item.status)) return "다운로드 준비 중";
            if ("stopped".equals(item.status)) return "다운로드 중단됨 · 임시 파일 삭제 완료";
            return "오류 · " + (item.error == null ? "다운로드 실패" : item.error);
        }
        final class Holder extends RecyclerView.ViewHolder {
            final ImageView thumbnail; final TextView title, status; final Button play, delete;
            Holder(View view) { super(view); thumbnail = view.findViewById(R.id.thumbnail);
                title = view.findViewById(R.id.title); status = view.findViewById(R.id.status);
                play = view.findViewById(R.id.play); delete = view.findViewById(R.id.delete); }
        }
    }

    private void confirmDelete(VideoItem item) {
        if (VideoDownloadService.isRunning() && ("queued".equals(item.status) || "downloading".equals(item.status))) {
            Toast.makeText(activity, "먼저 다운로드를 중단해 주세요.", Toast.LENGTH_LONG).show(); return;
        }
        new AlertDialog.Builder(activity).setTitle("영상 삭제")
                .setMessage("‘" + item.title + "’ 영상과 썸네일을 삭제할까요?")
                .setNegativeButton("취소", null).setPositiveButton("삭제", (d, w) -> {
                    executor.execute(() -> { LibraryDatabase.get(activity).delete(item.id); post(this::refresh); });
                }).show();
    }
}
