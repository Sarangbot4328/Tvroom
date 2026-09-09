package com.tvroom.downloader.ui;

import android.content.Intent;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.ImageView;
import java.io.File;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.tvroom.downloader.MainActivity;
import com.tvroom.downloader.R;
import com.tvroom.downloader.data.LibraryDatabase;
import com.tvroom.downloader.data.PlaylistStore;
import com.tvroom.downloader.data.VideoItem;
import com.tvroom.downloader.data.VideoPlaylist;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlaylistChannelView extends LinearLayout {
    private final MainActivity activity;
    private final PlaylistStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<VideoPlaylist> groups = new ArrayList<>();
    private List<VideoItem> library = new ArrayList<>();
    private final GroupAdapter adapter = new GroupAdapter();
    private final TextView message;
    private int generation;

    public PlaylistChannelView(MainActivity activity) {
        super(activity); this.activity = activity; store = new PlaylistStore(activity);
        setOrientation(VERTICAL);
        LinearLayout controls = new LinearLayout(activity);
        Button create = new Button(activity); create.setText("새 재생목록");
        Button restore = new Button(activity); restore.setText("자동 목록 복원");
        controls.addView(create, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        controls.addView(restore, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        addView(controls);
        message = new TextView(activity); message.setTextColor(activity.getColor(R.color.text_secondary));
        message.setPadding(dp(16), dp(8), dp(16), dp(8)); addView(message);
        RecyclerView list = new RecyclerView(activity); list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter); addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        create.setOnClickListener(v -> nameDialog("새 재생목록", "", name -> chooseVideos(null, name)));
        restore.setOnClickListener(v -> new AlertDialog.Builder(activity).setTitle("자동 목록 복원")
                .setMessage("숨긴 자동 목록과 제외한 회차를 다시 표시합니다. 수동 재생목록은 유지됩니다.")
                .setNegativeButton("취소", null).setPositiveButton("복원", (d, w) -> { store.restoreAutomatic(); refresh(); }).show());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    public void refresh() {
        int request = ++generation;
        executor.execute(() -> {
            try {
                List<VideoItem> rows = LibraryDatabase.get(activity).list();
                List<VideoPlaylist> playlists = store.list(rows);
                post(() -> {
                    if (request != generation || activity.isFinishing() || activity.isDestroyed()) return;
                    library = rows; groups.clear(); groups.addAll(playlists); adapter.notifyDataSetChanged();
                    message.setText(groups.isEmpty() ? "재생목록이 없습니다. 영상을 다운로드하거나 새 목록을 만들어 주세요."
                            : "선택한 회차부터 순서대로 재생합니다. 목록 삭제는 파일에 영향을 주지 않습니다.");
                });
            } catch (Exception error) { post(() -> message.setText("재생목록을 불러오지 못했습니다. 새로고침해 주세요.")); }
        });
    }

    private interface Named { void accept(String name); }
    private void nameDialog(String title, String initial, Named callback) {
        EditText input = new EditText(activity); input.setSingleLine(); input.setText(initial); input.setHint("재생목록 이름");
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(input)
                .setNegativeButton("취소", null).setPositiveButton("다음", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("이름을 입력해 주세요."); return; }
            dialog.dismiss(); callback.accept(name);
        })); dialog.show();
    }

    private void chooseVideos(VideoPlaylist playlist, String name) {
        List<VideoItem> candidates = new ArrayList<>();
        for (VideoItem item : library) if (PlaylistStore.playable(item)) candidates.add(item);
        candidates.sort(Comparator.comparing(VideoPlaylist::workKey)
                .thenComparingInt(item -> VideoPlaylist.episode(item) < 0 ? Integer.MAX_VALUE : VideoPlaylist.episode(item)));
        boolean[] checked = new boolean[candidates.size()];
        String[] titles = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            titles[i] = candidates.get(i).title;
            if (playlist != null) for (VideoItem video : playlist.videos) if (video.id.equals(candidates.get(i).id)) checked[i] = true;
        }
        new AlertDialog.Builder(activity).setTitle(name + " · 영상 선택")
                .setMultiChoiceItems(titles, checked, (d, which, value) -> checked[which] = value)
                .setNegativeButton("취소", null).setPositiveButton("저장", (d, w) -> {
                    List<VideoItem> selected = new ArrayList<>();
                    // Retained members keep their custom order; newly selected members append.
                    if (playlist != null) for (VideoItem old : playlist.videos) {
                        for (int i = 0; i < candidates.size(); i++) if (checked[i] && old.id.equals(candidates.get(i).id)) {
                            selected.add(candidates.get(i)); checked[i] = false; break;
                        }
                    }
                    for (int i = 0; i < candidates.size(); i++) if (checked[i]) selected.add(candidates.get(i));
                    store.save(playlist == null ? null : playlist.id, name, selected); refresh();
                }).show();
    }

    private void showEpisodes(VideoPlaylist playlist) {
        LinearLayout content = new LinearLayout(activity); content.setOrientation(VERTICAL);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(playlist.label()).setView(content)
                .setNeutralButton("목록 관리", (d, w) -> manage(playlist)).setNegativeButton("닫기", null).create();
        if (playlist.videos.isEmpty()) {
            TextView empty = new TextView(activity); empty.setText("재생 가능한 영상이 없습니다. 목록 관리에서 영상을 추가해 주세요.");
            empty.setPadding(dp(20), dp(16), dp(20), dp(16)); content.addView(empty);
        } else {
            RecyclerView episodes = new RecyclerView(activity);
            episodes.setLayoutManager(new LinearLayoutManager(activity));
            episodes.setAdapter(new EpisodeAdapter(playlist, dialog));
            content.addView(episodes, new LayoutParams(LayoutParams.MATCH_PARENT,
                    Math.min(dp(440), getResources().getDisplayMetrics().heightPixels / 2)));
            dialog.setOnDismissListener(d -> episodes.setAdapter(null));
        }
        dialog.show();
    }

    private void playEpisode(VideoPlaylist playlist, int index, AlertDialog dialog) {
        VideoItem selected = playlist.videos.get(index);
        if (!PlaylistStore.playable(selected)) {
            Toast.makeText(activity, "영상 파일을 찾을 수 없습니다.", Toast.LENGTH_LONG).show(); refresh(); return;
        }
        ArrayList<String> ids = new ArrayList<>();
        for (VideoItem video : playlist.videos) ids.add(video.id);
        dialog.dismiss();
        activity.startActivity(new Intent(activity, PlayerActivity.class)
                .putStringArrayListExtra(PlayerActivity.EXTRA_VIDEO_IDS, ids)
                .putExtra(PlayerActivity.EXTRA_START_ID, selected.id)
                .putExtra(PlayerActivity.EXTRA_PATH, selected.filePath)
                .putExtra(PlayerActivity.EXTRA_TITLE, selected.title));
    }

    private void manage(VideoPlaylist playlist) {
        String[] options = playlist.automatic ? new String[]{"회차 제외", "자동 목록 숨기기"}
                : new String[]{"영상 추가 / 제외", "재생 순서 변경", "이름 변경", "재생목록 삭제"};
        new AlertDialog.Builder(activity).setTitle(playlist.title).setItems(options, (d, which) -> {
            if (playlist.automatic && which == 0) {
                String[] titles = new String[playlist.videos.size()]; boolean[] selected = new boolean[titles.length];
                for (int i = 0; i < titles.length; i++) titles[i] = playlist.videos.get(i).title;
                new AlertDialog.Builder(activity).setTitle("자동 목록에서만 제외")
                        .setMultiChoiceItems(titles, selected, (dialog, index, checked) -> selected[index] = checked)
                        .setNegativeButton("취소", null).setPositiveButton("제외", (dialog, w) -> {
                            for (int i = 0; i < selected.length; i++) if (selected[i]) store.exclude(playlist.videos.get(i)); refresh();
                        }).show();
            } else if (!playlist.automatic && which == 0) chooseVideos(playlist, playlist.title);
            else if (!playlist.automatic && which == 1) reorder(playlist, new ArrayList<>(playlist.videos));
            else if (!playlist.automatic && which == 2) nameDialog("이름 변경", playlist.title, name -> { store.save(playlist.id, name, playlist.videos); refresh(); });
            else new AlertDialog.Builder(activity).setTitle(playlist.automatic ? "자동 목록 숨기기" : "재생목록 삭제")
                    .setMessage("‘" + playlist.title + "’ 목록만 제거합니다. 다운로드한 영상 파일은 유지됩니다.")
                    .setNegativeButton("취소", null).setPositiveButton("확인", (dialog, w) -> { store.delete(playlist); refresh(); }).show();
        }).show();
    }

    private void reorder(VideoPlaylist playlist, List<VideoItem> draft) {
        String[] titles = new String[draft.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = (i + 1) + ". " + draft.get(i).title;
        new AlertDialog.Builder(activity).setTitle("옮길 영상을 선택하세요")
                .setItems(titles, (d, which) -> new AlertDialog.Builder(activity).setTitle(draft.get(which).title)
                        .setItems(new String[]{"맨 위로", "한 칸 위로", "한 칸 아래로", "맨 아래로"}, (dialog, move) -> {
                            int target = move == 0 ? 0 : move == 1 ? Math.max(0, which - 1)
                                    : move == 2 ? Math.min(draft.size() - 1, which + 1) : draft.size() - 1;
                            VideoItem item = draft.remove(which); draft.add(target, item); reorder(playlist, draft);
                        }).setNegativeButton("돌아가기", (dialog, w) -> reorder(playlist, draft))
                        .setOnCancelListener(dialog -> reorder(playlist, draft)).show())
                .setPositiveButton("순서 저장", (d, w) -> { store.save(playlist.id, playlist.title, draft); refresh(); })
                .setNegativeButton("취소", null).show();
    }

    private static String cover(VideoPlaylist playlist) {
        for (VideoItem video : playlist.videos) {
            if (video.thumbnailPath != null && new File(video.thumbnailPath).isFile()) return video.thumbnailPath;
        }
        return null;
    }

    private final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title, detail, action;
        final ImageView thumbnail;
        RowHolder(View view) {
            super(view); title = view.findViewById(R.id.playlist_title);
            detail = view.findViewById(R.id.playlist_detail); action = view.findViewById(R.id.playlist_action);
            thumbnail = view.findViewById(R.id.playlist_thumbnail);
        }
    }

    private RowHolder createRow(ViewGroup parent) {
        return new RowHolder(LayoutInflater.from(activity).inflate(R.layout.row_playlist, parent, false));
    }

    private final class GroupAdapter extends RecyclerView.Adapter<RowHolder> {
        @Override public RowHolder onCreateViewHolder(ViewGroup parent, int type) { return createRow(parent); }
        @Override public void onBindViewHolder(RowHolder holder, int position) {
            VideoPlaylist playlist = groups.get(position); holder.title.setText(playlist.label());
            holder.detail.setText((playlist.automatic ? "자동 재생목록" : "수동 재생목록") + " · " + playlist.videos.size() + "개 영상");
            holder.action.setText("회차 선택  ›");
            PlaylistThumbnails.bind(holder.thumbnail, cover(playlist));
            holder.itemView.setOnClickListener(v -> showEpisodes(playlist));
        }
        @Override public void onViewRecycled(RowHolder holder) { PlaylistThumbnails.clear(holder.thumbnail); }
        @Override public int getItemCount() { return groups.size(); }
    }

    private final class EpisodeAdapter extends RecyclerView.Adapter<RowHolder> {
        private final VideoPlaylist playlist;
        private final AlertDialog dialog;
        EpisodeAdapter(VideoPlaylist playlist, AlertDialog dialog) { this.playlist = playlist; this.dialog = dialog; }
        @Override public RowHolder onCreateViewHolder(ViewGroup parent, int type) { return createRow(parent); }
        @Override public void onBindViewHolder(RowHolder holder, int position) {
            VideoItem video = playlist.videos.get(position);
            holder.title.setText(video.title); holder.detail.setText((position + 1) + " / " + playlist.videos.size());
            holder.action.setText("이 영상부터 재생  ›");
            PlaylistThumbnails.bind(holder.thumbnail, video.thumbnailPath);
            holder.itemView.setOnClickListener(v -> playEpisode(playlist, position, dialog));
        }
        @Override public void onViewRecycled(RowHolder holder) { PlaylistThumbnails.clear(holder.thumbnail); }
        @Override public int getItemCount() { return playlist.videos.size(); }
    }
}
