package com.webtoonmap.mobile.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.EpisodeItem;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.download.SeriesDownloadService;
import com.webtoonmap.mobile.download.SourceJobStore;
import com.webtoonmap.mobile.export.SeriesExporter;
import com.webtoonmap.mobile.storage.ViewedSeriesHistory;
import com.webtoonmap.mobile.storage.WebtoonStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.InputStream;

public final class DownloadChannelView extends android.widget.FrameLayout {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SeriesAdapter adapter = new SeriesAdapter();
    private final RecyclerView list;
    private final View empty;
    private final TextView status;
    private final Button exportButton;
    private final SwipeRefreshLayout swipe;
    private final ActivityResultLauncher<Intent> shareLauncher;
    private final ActivityResultLauncher<String> saveExportLauncher;
    private final ActivityResultLauncher<Uri> viewerFolderLauncher;
    private File pendingSharedExport;
    private File pendingSavedExport;
    private List<SeriesItem> pendingViewerItems;
    private boolean receiverRegistered;
    private boolean exporting;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            status.setText(intent.getStringExtra(SeriesDownloadService.EXTRA_MESSAGE));
            refresh();
        }
    };

    public DownloadChannelView(Context context) {
        super(context);
        if (!(context instanceof MainActivity)) {
            throw new IllegalArgumentException("DownloadChannelView requires MainActivity");
        }
        MainActivity activity = (MainActivity) context;
        shareLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> deletePendingSharedExport());
        saveExportLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"),
                this::finishTransferFileSave);
        viewerFolderLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                this::finishViewerFolderSelection);
        LayoutInflater.from(context).inflate(R.layout.channel_downloads, this, true);
        list = findViewById(R.id.series_list);
        empty = findViewById(R.id.empty);
        status = findViewById(R.id.download_status);
        exportButton = findViewById(R.id.export);
        swipe = findViewById(R.id.swipe);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        findViewById(R.id.refresh).setOnClickListener(v -> refresh());
        exportButton.setOnClickListener(v -> chooseExportMode());
        swipe.setOnRefreshListener(this::refresh);
        refresh();
    }

    public void refresh() {
        swipe.setRefreshing(true);
        executor.execute(() -> {
            List<SeriesItem> rows = LibraryDatabase.get(getContext()).listSeries();
            post(() -> {
                adapter.setItems(rows);
                empty.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
                exportButton.setEnabled(!rows.isEmpty() && !exporting);
                swipe.setRefreshing(false);
            });
        });
    }

    private void chooseExportMode() {
        if (exporting) return;
        new AlertDialog.Builder(getContext())
                .setTitle("내보내기 방식 선택")
                .setItems(new CharSequence[]{
                        "앱 데이터 교환\n웹툰여지도 앱·PC판에서 가져오는 전용 형식",
                        "다른 만화책 앱용\n작품명 폴더 안에 회차별 ZIP 저장"
                }, (dialog, which) -> chooseSeries(which == 1))
                .setNegativeButton("취소", null)
                .show();
    }

    private void chooseSeries(boolean viewerMode) {
        if (exporting) return;
        List<SeriesItem> rows = adapter.snapshot();
        if (rows.isEmpty()) {
            Toast.makeText(getContext(), "내보낼 작품이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] labels = new CharSequence[rows.size()];
        boolean[] selected = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            SeriesItem item = rows.get(i);
            labels[i] = item.title + "\n" + item.episodeCount + "개 회차";
        }
        new AlertDialog.Builder(getContext())
                .setTitle(viewerMode ? "다른 만화책 앱용 작품 선택" :
                        "앱 데이터 교환용 작품 선택")
                .setMultiChoiceItems(labels, selected,
                        (dialog, which, checked) -> selected[which] = checked)
                .setPositiveButton("내보내기", (dialog, which) -> {
                    List<SeriesItem> chosen = new ArrayList<>();
                    for (int i = 0; i < rows.size(); i++) {
                        if (selected[i]) chosen.add(rows.get(i));
                    }
                    if (chosen.isEmpty()) {
                        Toast.makeText(getContext(), "한 작품 이상 선택해 주세요.",
                                Toast.LENGTH_SHORT).show();
                    } else if (viewerMode) {
                        requestViewerFolder(chosen);
                    } else {
                        exportSeries(chosen);
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void exportSeries(List<SeriesItem> items) {
        if (exporting) return;
        for (SeriesItem item : items) {
            if (SeriesDownloadService.isDownloading(item.titleId) ||
                    SeriesDownloadService.isQueued(getContext(), item.titleId)) {
                Toast.makeText(getContext(), "‘" + item.title +
                                "’ 다운로드가 끝난 뒤 내보내 주세요.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        exporting = true;
        exportButton.setEnabled(false);
        exportButton.setText("압축 중…");
        status.setText(items.size() + "개 작품 내보내기 준비 중…");
        AlertDialog progressDialog = new AlertDialog.Builder(getContext())
                .setTitle("데이터 내보내기")
                .setMessage(items.size() + "개 작품 내보내기 준비 중…")
                .setCancelable(false)
                .create();
        progressDialog.show();
        executor.execute(() -> {
            try {
                LibraryDatabase database = LibraryDatabase.get(getContext());
                File file = SeriesExporter.export(getContext(), items, database,
                        (current, total) -> post(() -> {
                            String text = items.size() + "개 작품 압축 중 · " + current + "/" + total + "회차";
                            status.setText(text);
                            progressDialog.setMessage(text);
                        }));
                post(() -> {
                    exporting = false;
                    exportButton.setEnabled(true);
                    exportButton.setText("내보내기");
                    status.setText("내보내기 완료 · " + file.getName());
                    progressDialog.dismiss();
                    AlertDialog completedDialog = new AlertDialog.Builder(getContext())
                            .setTitle("내보내기 완료")
                            .setMessage(items.size() + "개 작품을 하나의 파일로 만들었습니다.\n" +
                                    file.getName() + "\n\n공유·기기 저장·닫기 후 앱의 임시 파일은 자동 삭제됩니다.")
                            .setPositiveButton("공유하기", (dialog, which) -> shareExport(file))
                            .setNeutralButton("기기에 저장",
                                    (dialog, which) -> saveTransferExport(file))
                            .setNegativeButton("닫기", (dialog, which) -> deleteExportFile(file))
                            .create();
                    completedDialog.setOnCancelListener(dialog -> deleteExportFile(file));
                    completedDialog.show();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ?
                        "내보내기에 실패했습니다." : error.getMessage();
                post(() -> {
                    exporting = false;
                    exportButton.setEnabled(adapter.getItemCount() > 0);
                    exportButton.setText("내보내기");
                    status.setText("내보내기 실패");
                    progressDialog.dismiss();
                    new AlertDialog.Builder(getContext())
                            .setTitle("내보내기 실패")
                            .setMessage(message)
                            .setPositiveButton("확인", null)
                            .show();
                    Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void requestViewerFolder(List<SeriesItem> items) {
        for (SeriesItem item : items) {
            if (SeriesDownloadService.isDownloading(item.titleId) ||
                    SeriesDownloadService.isQueued(getContext(), item.titleId)) {
                Toast.makeText(getContext(), "‘" + item.title +
                                "’ 다운로드가 끝난 뒤 내보내 주세요.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        exporting = true;
        exportButton.setEnabled(false);
        pendingViewerItems = new ArrayList<>(items);
        status.setText("저장할 상위 폴더를 선택해 주세요.");
        try {
            viewerFolderLauncher.launch(null);
        } catch (Exception error) {
            pendingViewerItems = null;
            finishExportUi("폴더 선택 화면을 열지 못했습니다.");
        }
    }

    private void finishViewerFolderSelection(Uri treeUri) {
        List<SeriesItem> items = pendingViewerItems;
        pendingViewerItems = null;
        if (items == null) return;
        if (treeUri == null) {
            finishExportUi("만화책 앱용 내보내기를 취소했습니다.");
            return;
        }
        AlertDialog progressDialog = new AlertDialog.Builder(getContext())
                .setTitle("다른 만화책 앱용 내보내기")
                .setMessage("작품 폴더를 만드는 중…")
                .setCancelable(false)
                .create();
        progressDialog.show();
        executor.execute(() -> {
            try {
                LibraryDatabase database = LibraryDatabase.get(getContext());
                SeriesExporter.exportViewerFolders(getContext(), treeUri, items, database,
                        (current, total) -> post(() -> {
                            String message = "회차 ZIP 저장 중 · " + current + "/" + total;
                            status.setText(message);
                            progressDialog.setMessage(message);
                        }));
                post(() -> {
                    progressDialog.dismiss();
                    finishExportUi("다른 만화책 앱용 내보내기 완료 · " +
                            items.size() + "개 작품");
                    new AlertDialog.Builder(getContext())
                            .setTitle("내보내기 완료")
                            .setMessage("선택한 저장소에 작품명 폴더와 회차별 ZIP 파일을 저장했습니다.")
                            .setPositiveButton("확인", null)
                            .show();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? "만화책 앱용 내보내기에 실패했습니다." : error.getMessage();
                post(() -> {
                    progressDialog.dismiss();
                    finishExportUi("내보내기 실패");
                    new AlertDialog.Builder(getContext())
                            .setTitle("내보내기 실패")
                            .setMessage(message)
                            .setPositiveButton("확인", null)
                            .show();
                });
            }
        });
    }

    private void saveTransferExport(File file) {
        deletePendingSavedExport();
        pendingSavedExport = file;
        exporting = true;
        exportButton.setEnabled(false);
        status.setText("저장할 위치를 선택해 주세요.");
        try {
            saveExportLauncher.launch(file.getName());
        } catch (Exception error) {
            deletePendingSavedExport();
            finishExportUi("저장 화면을 열지 못했습니다.");
        }
    }

    private void finishTransferFileSave(Uri destination) {
        File file = pendingSavedExport;
        pendingSavedExport = null;
        if (file == null) return;
        if (destination == null) {
            deleteExportFile(file);
            finishExportUi("기기 저장을 취소했습니다.");
            return;
        }
        status.setText("선택한 위치에 저장 중…");
        executor.execute(() -> {
            String message;
            boolean success;
            try {
                SeriesExporter.saveTransferFile(getContext(), file, destination);
                message = "앱 데이터 교환 파일을 기기에 저장했습니다.";
                success = true;
            } catch (Exception error) {
                message = error.getMessage() == null
                        ? "기기에 저장하지 못했습니다." : error.getMessage();
                success = false;
            } finally {
                deleteExportFile(file);
            }
            final String resultMessage = message;
            final boolean saved = success;
            post(() -> {
                finishExportUi(saved ? "기기 저장 완료" : "기기 저장 실패");
                Toast.makeText(getContext(), resultMessage, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void finishExportUi(String message) {
        exporting = false;
        exportButton.setEnabled(adapter.getItemCount() > 0);
        exportButton.setText("내보내기");
        status.setText(message);
    }

    private void shareExport(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(getContext(),
                    getContext().getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, file.getName())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri(file.getName(), uri));
            Intent chooser = Intent.createChooser(share, "웹툰 내보내기");
            if (!(getContext() instanceof Activity)) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            deletePendingSharedExport();
            pendingSharedExport = file;
            shareLauncher.launch(chooser);
        } catch (Exception error) {
            pendingSharedExport = null;
            deleteExportFile(file);
            Toast.makeText(getContext(), "공유 화면을 열지 못했습니다. 임시 파일을 삭제했습니다.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void deletePendingSavedExport() {
        File file = pendingSavedExport;
        pendingSavedExport = null;
        deleteExportFile(file);
    }

    private void deletePendingSharedExport() {
        File file = pendingSharedExport;
        pendingSharedExport = null;
        deleteExportFile(file);
    }

    private static void deleteExportFile(File file) {
        if (file != null && file.isFile()) file.delete();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(getContext(), receiver,
                    new IntentFilter(SeriesDownloadService.ACTION_PROGRESS),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (receiverRegistered) {
            getContext().unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    private final class SeriesAdapter extends RecyclerView.Adapter<SeriesAdapter.Holder> {
        private final List<SeriesItem> items = new ArrayList<>();
        void setItems(List<SeriesItem> rows) { items.clear(); items.addAll(rows); notifyDataSetChanged(); }
        List<SeriesItem> snapshot() { return new ArrayList<>(items); }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_series, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            SeriesItem item = items.get(position);
            h.title.setText(item.title);
            h.tags.setText(item.tags.isEmpty() ? "태그 없음" : "# " + item.tags.replace(",", "  #"));
            String state = statusLabel(item);
            String suffix = state.isEmpty() ? "" : " · " + state;
            h.count.setText(item.episodeCount + "개 회차" + suffix);
            h.image.setImageDrawable(null);
            h.image.setTag(item.thumbnailPath);
            if (item.thumbnailPath != null) executor.execute(() -> {
                Bitmap bitmap = decodeThumbnail(item.thumbnailPath);
                h.image.post(() -> {
                    if (item.thumbnailPath.equals(h.image.getTag())) h.image.setImageBitmap(bitmap);
                });
            });
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(getContext(), DownloadedSeriesActivity.class);
                intent.putExtra("title_id", item.titleId);
                getContext().startActivity(intent);
            });
            h.resume.setOnClickListener(v -> resumeDownload(item));
            h.delete.setOnClickListener(v -> confirmDelete(item));
        }

        private void resumeDownload(SeriesItem item) {
            if (SeriesDownloadService.isDownloading(item.titleId)) {
                Toast.makeText(getContext(), "이 작품을 이미 다운로드하고 있습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean queued = SeriesDownloadService.isQueued(getContext(), item.titleId);
            if (queued && SeriesDownloadService.isRunning()) {
                Toast.makeText(getContext(), "이미 다운로드 대기열에 있습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean queueMode = SeriesDownloadService.isRunning();
            status.setText("‘" + item.title + "’ " + (queueMode ? "대기열 추가 중…" : "이어받기 준비 중…"));
            SeriesDownloadService.enqueue(getContext(), item.titleId);
            Toast.makeText(getContext(), queueMode ? "이어받기를 대기열에 추가했습니다." :
                    (queued ? "중단된 대기열을 다시 시작했습니다." :
                            "완성되지 않은 회차부터 이어받습니다."),
                    Toast.LENGTH_SHORT).show();
        }

        private Bitmap decodeThumbnail(String path) {
            if (!path.startsWith("content://")) return BitmapFactory.decodeFile(path);
            try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(path))) {
                return in == null ? null : BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
                return null;
            }
        }

        private void confirmDelete(SeriesItem item) {
            if (SeriesDownloadService.isDownloading(item.titleId) ||
                    SeriesDownloadService.isQueued(getContext(), item.titleId)) {
                Toast.makeText(getContext(), "다운로드 중이거나 대기열에 있는 작품은 삭제할 수 없습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(getContext())
                    .setTitle("작품 삭제")
                    .setMessage("‘" + item.title + "’의 썸네일, 모든 회차 ZIP과 작품 정보를 실제 저장소에서 삭제합니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제", (dialog, which) -> executor.execute(() -> {
                        LibraryDatabase database = LibraryDatabase.get(getContext());
                        boolean previouslyViewed = false;
                        for (EpisodeItem episode : database.listEpisodes(item.titleId)) {
                            if (episode.viewed) { previouslyViewed = true; break; }
                        }
                        boolean deleted;
                        try {
                            deleted = new WebtoonStorage(getContext(), item.storageUri)
                                    .deleteSeries(item.titleId);
                        } catch (Exception ignored) {
                            deleted = false;
                        }
                        if (deleted) {
                            if (previouslyViewed) ViewedSeriesHistory.record(getContext(), item);
                            database.deleteSeries(item.titleId);
                            SourceJobStore.remove(getContext(), item.titleId);
                        }
                        final boolean result = deleted;
                        post(() -> {
                            Toast.makeText(getContext(), result ? "작품을 완전히 삭제했습니다." :
                                    "저장 폴더를 삭제하지 못했습니다. 폴더 권한을 확인해 주세요.", Toast.LENGTH_LONG).show();
                            refresh();
                        });
                    })).show();
        }

        private String statusLabel(SeriesItem item) {
            if (SeriesDownloadService.isDownloading(item.titleId)) return "다운로드 중";
            if (SeriesDownloadService.isQueued(getContext(), item.titleId)) return "대기열";
            if ("downloading".equals(item.status)) {
                return "이어받기 필요";
            }
            if ("error".equals(item.status) || "paused".equals(item.status)) return "이어받기 필요";
            return "complete".equals(item.status) ? "" : item.status;
        }

        @Override public int getItemCount() { return items.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image; final TextView title, tags, count; final Button resume, delete;
            Holder(View view) {
                super(view);
                image = view.findViewById(R.id.thumbnail);
                title = view.findViewById(R.id.title);
                tags = view.findViewById(R.id.tags);
                count = view.findViewById(R.id.count);
                resume = view.findViewById(R.id.resume);
                delete = view.findViewById(R.id.delete);
            }
        }
    }
}






