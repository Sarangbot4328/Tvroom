package com.tvroom.downloader.export;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultDecoderFactory;
import androidx.media3.transformer.ExoPlayerAssetLoader;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.tvroom.downloader.MainActivity;
import com.tvroom.downloader.R;
import com.tvroom.downloader.data.VideoItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public final class VideoExportService extends Service {
    public static final String ACTION_PROGRESS = "com.tvroom.downloader.EXPORT_PROGRESS";
    public static final String EXTRA_MESSAGE = "message";

    private static final String ACTION_START = "export_start";
    private static final String ACTION_CANCEL = "export_cancel";
    private static final String EXTRA_TITLES = "titles";
    private static final String EXTRA_PATHS = "paths";
    private static final String EXTRA_FOLDER = "folder";
    private static final String OLD_CHANNEL = "tvroom_export";
    private static final String PROGRESS_CHANNEL = "tvroom_export_active_v3";
    private static final String COMPLETE_CHANNEL = "tvroom_export_no_badge_v2";
    private static final int NOTIFICATION_ID = 7202;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finishing = new AtomicBoolean(false);
    private final List<String> titles = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private DocumentFile destination;
    private File tempRoot;
    private Transformer transformer;
    private int currentIndex;
    private int successCount;
    private int failedCount;
    private String lastFailure = "";

    public static boolean isRunning() { return RUNNING.get(); }

    public static void clearFinishedNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.cancel(NOTIFICATION_ID);
        manager.deleteNotificationChannel(OLD_CHANNEL);
    }

    public static boolean start(Context context, List<VideoItem> videos, Uri folder) {
        if (videos == null || videos.isEmpty() || folder == null || !RUNNING.compareAndSet(false, true)) {
            return false;
        }
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> paths = new ArrayList<>();
        for (VideoItem video : videos) {
            if (video.filePath == null || !new File(video.filePath).isFile()) continue;
            titles.add(video.title);
            paths.add(video.filePath);
        }
        if (paths.isEmpty()) {
            RUNNING.set(false);
            return false;
        }
        Intent intent = new Intent(context, VideoExportService.class).setAction(ACTION_START)
                .putStringArrayListExtra(EXTRA_TITLES, titles)
                .putStringArrayListExtra(EXTRA_PATHS, paths)
                .putExtra(EXTRA_FOLDER, folder.toString());
        try {
            ContextCompat.startForegroundService(context, intent);
            return true;
        } catch (Exception error) {
            RUNNING.set(false);
            return false;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.deleteNotificationChannel(OLD_CHANNEL);
        NotificationChannel progressChannel = new NotificationChannel(
                PROGRESS_CHANNEL, "영상 내보내기 진행", NotificationManager.IMPORTANCE_LOW);
        progressChannel.setShowBadge(true);
        manager.createNotificationChannel(progressChannel);
        NotificationChannel completeChannel = new NotificationChannel(
                COMPLETE_CHANNEL, "영상 내보내기 완료", NotificationManager.IMPORTANCE_LOW);
        completeChannel.setShowBadge(false);
        manager.createNotificationChannel(completeChannel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelExport();
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        if (!startForegroundSafely()) return START_NOT_STICKY;

        try {
            ArrayList<String> incomingTitles = intent.getStringArrayListExtra(EXTRA_TITLES);
            ArrayList<String> incomingPaths = intent.getStringArrayListExtra(EXTRA_PATHS);
            String folder = intent.getStringExtra(EXTRA_FOLDER);
            if (incomingTitles == null || incomingPaths == null
                    || incomingPaths.isEmpty() || folder == null) {
                finishExport("내보낼 영상 정보를 읽지 못했습니다.");
                return START_NOT_STICKY;
            }
            destination = DocumentFile.fromTreeUri(this, Uri.parse(folder));
            if (destination == null || !destination.isDirectory() || !destination.canWrite()) {
                finishExport("선택한 폴더에 쓸 수 없습니다.");
                return START_NOT_STICKY;
            }

            titles.clear();
            paths.clear();
            int count = Math.min(incomingTitles.size(), incomingPaths.size());
            for (int i = 0; i < count; i++) {
                titles.add(incomingTitles.get(i));
                paths.add(incomingPaths.get(i));
            }
            File cache = getExternalCacheDir();
            if (cache == null) cache = getCacheDir();
            tempRoot = new File(cache, "video_export_" + System.currentTimeMillis());
            if (!tempRoot.mkdirs()) {
                finishExport("내보내기 임시 폴더를 만들지 못했습니다.");
                return START_NOT_STICKY;
            }

            broadcast("선택한 영상 " + paths.size() + "개를 내보내기 시작합니다.");
            processNext();
        } catch (RuntimeException error) {
            finishExport("내보내기 시작 실패 · " + clean(error));
        }
        return START_NOT_STICKY;
    }

    private boolean startForegroundSafely() {
        int preferredType = Build.VERSION.SDK_INT >= 35
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID,
                    notification("영상 내보내기 준비 중", 0).build(), preferredType);
            return true;
        } catch (RuntimeException firstError) {
            if (preferredType != ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
                try {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID,
                            notification("영상 내보내기 준비 중", 0).build(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    return true;
                } catch (RuntimeException ignored) { }
            }
            RUNNING.set(false);
            broadcast("내보내기 서비스를 시작하지 못했습니다 · " + clean(firstError));
            stopSelf();
            return false;
        }
    }

    private void processNext() {
        try {
            processNextSafely();
        } catch (RuntimeException error) {
            failCurrent("MP4 변환 준비 실패 · " + clean(error));
        }
    }

    private void processNextSafely() {
        if (cancelled.get()) {
            finishExport("영상 내보내기를 취소했습니다.");
            return;
        }
        if (currentIndex >= paths.size()) {
            String message;
            if (failedCount == 0) {
                message = "영상 " + successCount + "개를 내보냈습니다.";
            } else if (successCount == 0) {
                message = "내보내기 실패 · " + lastFailure;
            } else {
                message = "내보내기 완료 · 성공 " + successCount + "개 · 실패 "
                        + failedCount + "개\n마지막 오류 · " + lastFailure;
            }
            finishExport(message);
            return;
        }
        File input = new File(paths.get(currentIndex));
        if (!input.isFile()) {
            failCurrent("원본 영상을 찾을 수 없습니다 · " + titles.get(currentIndex));
            return;
        }
        File output = new File(tempRoot, String.format(Locale.US, "export_%03d.mp4", currentIndex));
        output.delete();
        String title = titles.get(currentIndex);
        update("MP4 변환 " + (currentIndex + 1) + "/" + paths.size() + " · " + title, overallProgress(0));

        MediaItem.Builder media = new MediaItem.Builder().setUri(Uri.fromFile(input));
        boolean hls = input.getName().toLowerCase(Locale.US).endsWith(".m3u8");
        if (hls) media.setMimeType(MimeTypes.APPLICATION_M3U8);

        // Preserve the original H.264/AAC samples whenever possible. Forcing output MIME types
        // makes Transformer initialize device encoders even though this export only needs an MP4
        // container, and some phones fail while creating those unnecessary encoders.
        Transformer.Builder builder = new Transformer.Builder(this)
                // Offline HLS can legitimately pause while crossing a discontinuity or opening
                // the next group of local TS segments. The default 10-second watchdog treats
                // that pause as a stuck muxer and aborts with ERROR_CODE_MUXING_TIMEOUT.
                .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET);
        if (hls) {
            int tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                    | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES;
            HlsMediaSource.Factory mediaSourceFactory = new HlsMediaSource.Factory(
                    new DefaultDataSource.Factory(this))
                    .setExtractorFactory(new DefaultHlsExtractorFactory(tsFlags, true));
            ExoPlayerAssetLoader.Factory assetLoaderFactory = new ExoPlayerAssetLoader.Factory(
                    this, new DefaultDecoderFactory.Builder(this).build(), Clock.DEFAULT,
                    mediaSourceFactory);
            builder.setAssetLoaderFactory(assetLoaderFactory);
        }
        transformer = builder.addListener(new Transformer.Listener() {
            @Override public void onCompleted(Composition composition, ExportResult result) {
                mainHandler.removeCallbacks(progressPoller);
                transformer = null;
                if (cancelled.get() || finishing.get()) {
                    output.delete();
                    return;
                }
                if (!output.isFile() || output.length() < 4096) {
                    output.delete();
                    failCurrent("완성된 MP4 파일이 비어 있습니다 · " + title);
                } else {
                    copyToDestination(output, title);
                }
            }

            @Override public void onError(Composition composition, ExportResult result,
                                          ExportException exception) {
                mainHandler.removeCallbacks(progressPoller);
                transformer = null;
                output.delete();
                failCurrent("MP4 변환 실패 · " + title + " · " + exportError(exception));
            }
        }).build();
        try {
            transformer.start(media.build(), output.getAbsolutePath());
            mainHandler.postDelayed(progressPoller, 500);
        } catch (Exception error) {
            transformer = null;
            output.delete();
            failCurrent("MP4 변환을 시작하지 못했습니다 · " + title + " · " + clean(error));
        }
    }

    private final Runnable progressPoller = new Runnable() {
        @Override public void run() {
            Transformer active = transformer;
            if (active == null) return;
            try {
                ProgressHolder holder = new ProgressHolder();
                int state = active.getProgress(holder);
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    update("MP4 변환 " + (currentIndex + 1) + "/" + paths.size()
                            + " · " + holder.progress + "%", overallProgress(holder.progress));
                }
                mainHandler.postDelayed(this, 700);
            } catch (RuntimeException error) {
                transformer = null;
                try { active.cancel(); } catch (RuntimeException ignored) { }
                failCurrent("MP4 변환 상태 확인 실패 · " + clean(error));
            }
        }
    };

    private void copyToDestination(File source, String title) {
        try {
            fileExecutor.execute(() -> {
                DocumentFile target = null;
                try {
                    String name = uniqueName(safeName(title));
                    target = destination.createFile("video/mp4", name);
                    if (target == null) {
                        throw new IllegalStateException("대상 파일을 만들지 못했습니다.");
                    }
                    try (FileInputStream in = new FileInputStream(source);
                         OutputStream out = getContentResolver().openOutputStream(target.getUri(), "w")) {
                        if (out == null) {
                            throw new IllegalStateException("대상 파일을 열지 못했습니다.");
                        }
                        byte[] buffer = new byte[1024 * 1024];
                        int read;
                        while ((read = in.read(buffer)) >= 0) {
                            if (cancelled.get()) throw new InterruptedException("내보내기 취소");
                            out.write(buffer, 0, read);
                        }
                        out.flush();
                    }
                    source.delete();
                    mainHandler.post(() -> {
                        if (cancelled.get() || finishing.get()) return;
                        successCount++;
                        currentIndex++;
                        update("저장 완료 " + currentIndex + "/" + paths.size(), overallProgress(0));
                        processNext();
                    });
                } catch (Exception error) {
                    if (target != null) {
                        try { target.delete(); } catch (RuntimeException ignored) { }
                    }
                    source.delete();
                    String message = "파일 저장 실패 · " + title + " · " + clean(error);
                    mainHandler.post(() -> {
                        if (cancelled.get()) finishExport("영상 내보내기를 취소했습니다.");
                        else failCurrent(message);
                    });
                }
            });
        } catch (RuntimeException error) {
            source.delete();
            failCurrent("파일 저장 준비 실패 · " + title + " · " + clean(error));
        }
    }

    private void failCurrent(String message) {
        if (finishing.get()) return;
        if (cancelled.get()) {
            finishExport("영상 내보내기를 취소했습니다.");
            return;
        }
        lastFailure = message;
        failedCount++;
        currentIndex++;
        broadcast(message);
        update("다음 영상 처리 중 · " + currentIndex + "/" + paths.size(), overallProgress(0));
        processNext();
    }

    private String uniqueName(String base) {
        String name = base + ".mp4";
        int suffix = 1;
        while (destination.findFile(name) != null) name = base + "_" + suffix++ + ".mp4";
        return name;
    }

    private static String safeName(String value) {
        String safe = value.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ").trim();
        return safe.isEmpty() ? "tvroom-video" : safe;
    }

    private int overallProgress(int itemProgress) {
        return Math.min(99, (currentIndex * 100 + itemProgress) / Math.max(1, paths.size()));
    }

    private void cancelExport() {
        cancelled.set(true);
        Transformer active = transformer;
        if (active != null) active.cancel();
        mainHandler.removeCallbacks(progressPoller);
        finishExport("영상 내보내기를 취소했습니다.");
    }

    private void finishExport(String message) {
        if (!finishing.compareAndSet(false, true)) return;
        mainHandler.removeCallbacks(progressPoller);
        Transformer active = transformer;
        transformer = null;
        if (active != null) {
            try { active.cancel(); } catch (Exception ignored) { }
        }
        deleteTree(tempRoot);
        RUNNING.set(false);
        broadcast(message);
        NotificationManager manager = getSystemService(NotificationManager.class);
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException ignored) { }
        manager.cancel(NOTIFICATION_ID);
        try { manager.notify(NOTIFICATION_ID, completionNotification(message).build()); }
        catch (RuntimeException ignored) { }
        stopSelf();
    }

    private void update(String message, int progress) {
        try {
            getSystemService(NotificationManager.class).notify(
                    NOTIFICATION_ID, notification(message, progress).build());
        } catch (RuntimeException ignored) { }
        broadcast(message);
    }

    private NotificationCompat.Builder notification(String message, int progress) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 2,
                new Intent(this, VideoExportService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, PROGRESS_CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("티비룸 영상 내보내기")
                .setContentText(message)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .setNumber(1)
                .setContentIntent(open)
                .addAction(0, "취소", cancel);
        if (progress > 0) builder.setProgress(100, progress, false);
        else builder.setProgress(0, 0, true);
        return builder;
    }

    private NotificationCompat.Builder completionNotification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, COMPLETE_CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("티비룸 영상 내보내기")
                .setContentText(message)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .setAutoCancel(true)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                .setNumber(0)
                .setContentIntent(open);
    }

    private void broadcast(String message) {
        sendBroadcast(new Intent(ACTION_PROGRESS).setPackage(getPackageName())
                .putExtra(EXTRA_MESSAGE, message));
    }

    private static String clean(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error == null ? "알 수 없는 오류" : error.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String exportError(ExportException error) {
        StringBuilder detail = new StringBuilder(error.getErrorCodeName());
        String message = clean(error);
        if (!message.equals(error.getErrorCodeName())) detail.append(" · ").append(message);
        Throwable cause = error.getCause();
        if (cause != null) {
            String causeMessage = clean(cause);
            if (!causeMessage.equals(message)) detail.append(" · 원인: ").append(causeMessage);
        }
        return detail.toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onTimeout(int startId, int fgsType) {
        finishExport("Android의 작업 시간 제한으로 내보내기를 중단했습니다.");
    }

    @Override public void onDestroy() {
        cancelled.set(true);
        mainHandler.removeCallbacksAndMessages(null);
        if (transformer != null) {
            try { transformer.cancel(); } catch (Exception ignored) { }
        }
        fileExecutor.shutdownNow();
        deleteTree(tempRoot);
        RUNNING.set(false);
        super.onDestroy();
    }
}
