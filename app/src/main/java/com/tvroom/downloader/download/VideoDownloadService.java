package com.tvroom.downloader.download;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.tvroom.downloader.MainActivity;
import com.tvroom.downloader.R;
import com.tvroom.downloader.data.LibraryDatabase;
import com.tvroom.downloader.data.VideoItem;
import com.tvroom.downloader.storage.TempFiles;
import com.tvroom.downloader.web.CaptureState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VideoDownloadService extends Service {
    public static final String ACTION_PROGRESS = "com.tvroom.downloader.DOWNLOAD_PROGRESS";
    public static final String EXTRA_MESSAGE = "message";
    private static final String ACTION_START = "start";
    private static final String ACTION_STOP = "stop";
    private static final String EXTRA_JOB = "job";
    private static final String CHANNEL = "tvroom_download";
    private static final int NOTIFICATION_ID = 7201;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object queueLock = new Object();
    private final Queue<String> pendingJobs = new ArrayDeque<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean processing;
    private volatile HttpURLConnection activeConnection;
    private volatile Thread worker;
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() { return RUNNING.get(); }

    public static boolean start(Context context, CaptureState.Snapshot snapshot) {
        LibraryDatabase database = LibraryDatabase.get(context);
        synchronized (VideoDownloadService.class) {
            VideoItem existing = database.getItem(snapshot.id);
            if (existing != null && ("queued".equals(existing.status) || "downloading".equals(existing.status))) {
                return false;
            }
            database.upsert(new VideoItem(snapshot.id, snapshot.title, snapshot.pageUrl,
                    null, null, "queued", 0, ""));
        }
        Intent intent = new Intent(context, VideoDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_JOB, snapshot.toJson());
        ContextCompat.startForegroundService(context, intent);
        return true;
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, VideoDownloadService.class).setAction(ACTION_STOP));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "영상 다운로드", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        String raw = intent.getStringExtra(EXTRA_JOB);
        if (raw == null || raw.isEmpty()) return START_NOT_STICKY;

        boolean begin;
        int waiting;
        synchronized (queueLock) {
            pendingJobs.offer(raw);
            waiting = pendingJobs.size();
            begin = !processing;
            if (begin) processing = true;
        }
        RUNNING.set(true);
        if (begin) {
            stopRequested.set(false);
            cancelled.set(false);
            startForeground(NOTIFICATION_ID, notification("다운로드 준비 중", 0));
            executor.execute(this::drainQueue);
        } else {
            broadcast("다운로드 대기열에 추가했습니다. 대기 " + waiting + "개");
        }
        return START_NOT_STICKY;
    }

    private void drainQueue() {
        worker = Thread.currentThread();
        while (!stopRequested.get()) {
            String raw;
            synchronized (queueLock) {
                raw = pendingJobs.poll();
                if (raw == null) processing = false;
            }
            if (raw == null || stopRequested.get()) break;
            cancelled.set(false);
            runJob(raw);
        }
        synchronized (queueLock) {
            if (stopRequested.get()) pendingJobs.clear();
            processing = false;
        }
        if (worker == Thread.currentThread()) worker = null;
        mainHandler.post(this::finishIfIdle);
    }

    private void finishIfIdle() {
        boolean beginAgain = false;
        synchronized (queueLock) {
            if (!pendingJobs.isEmpty() && !processing && !stopRequested.get()) {
                processing = true;
                beginAgain = true;
            } else if (processing || !pendingJobs.isEmpty()) {
                return;
            }
        }
        if (beginAgain) {
            executor.execute(this::drainQueue);
            return;
        }
        RUNNING.set(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void requestStop() {
        stopRequested.set(true);
        cancelled.set(true);
        synchronized (queueLock) { pendingJobs.clear(); }
        LibraryDatabase.get(this).stopQueuedDownloads();
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        updateNotification("다운로드 중단 중", 0);
        broadcast("현재 다운로드와 대기열을 중단합니다.");
    }

    private void runJob(String raw) {
        CaptureState.Snapshot job = null;
        File workDir = null;
        try {
            job = CaptureState.Snapshot.fromJson(raw);
            acquireWakeLock();
            workDir = TempFiles.jobDir(this, job.id);
            LibraryDatabase.get(this).updateProgress(job.id, "downloading", 1, "");
            broadcast("다운로드 준비 중");
            String thumbnail = downloadThumbnail(job);
            LibraryDatabase.get(this).updateThumbnail(job.id, thumbnail);
            broadcast("영상 정보를 저장했습니다. 다운로드를 시작합니다.");
            File tempMedia = new File(workDir, "restored.media.part");
            CaptureState.Snapshot finalJob = job;
            boolean mp4 = new HlsDownloader(job, (message, percent) -> {
                LibraryDatabase.get(this).updateProgress(finalJob.id, "downloading", percent, "");
                updateNotification(message, percent);
                broadcast(message);
            }, new HlsDownloader.Cancellation() {
                @Override public boolean cancelled() { return cancelled.get(); }
                @Override public void connection(HttpURLConnection connection) { activeConnection = connection; }
            }).download(workDir, tempMedia);
            if (cancelled.get()) throw new InterruptedException("다운로드 중단");
            File finalFile;
            if (mp4) {
                finalFile = finalVideoFile(job.title, ".mp4");
                publish(tempMedia, finalFile);
            } else {
                finalFile = publishOfflineHls(tempMedia,
                        new File(workDir, "offline_segments"), job.title);
            }
            LibraryDatabase.get(this).complete(job.id, thumbnail, finalFile.getAbsolutePath());
            updateNotification("다운로드 완료", 100);
            broadcast("다운로드 완료 · " + job.title);
        } catch (InterruptedException error) {
            if (job != null) LibraryDatabase.get(this).updateProgress(
                    job.id, "stopped", 0, "사용자가 다운로드를 중단했습니다.");
            broadcast("다운로드를 중단하고 임시 파일을 삭제했습니다.");
        } catch (Exception error) {
            if (cancelled.get() || stopRequested.get()) {
                if (job != null) LibraryDatabase.get(this).updateProgress(
                        job.id, "stopped", 0, "사용자가 다운로드를 중단했습니다.");
                broadcast("다운로드를 중단하고 임시 파일을 삭제했습니다.");
            } else {
                String message = error.getMessage() == null ? "다운로드에 실패했습니다." : error.getMessage();
                if (job != null) LibraryDatabase.get(this).updateProgress(job.id, "error", 0, message);
                broadcast("다운로드 실패 · " + message);
            }
        } finally {
            activeConnection = null;
            if (workDir != null && job != null) TempFiles.deleteJob(this, job.id);
            releaseWakeLock();
        }
    }

    private File finalVideoFile(String title, String extension) {
        File root = videoRoot();
        String safe = safeTitle(title);
        File file = new File(root, safe + extension);
        int suffix = 1;
        while (file.exists()) file = new File(root, safe + "_" + suffix++ + extension);
        return file;
    }

    private File publishOfflineHls(File playlist, File sourceSegments, String title) throws Exception {
        File root = videoRoot();
        String safe = safeTitle(title);
        File packageDir = new File(root, safe + ".hls");
        int suffix = 1;
        while (packageDir.exists()) packageDir = new File(root, safe + "_" + suffix++ + ".hls");
        File targetSegments = new File(packageDir, sourceSegments.getName());
        if (!targetSegments.mkdirs()) throw new IllegalStateException("오프라인 영상 폴더를 만들지 못했습니다.");
        try {
            File index = new File(packageDir, "index.m3u8");
            publish(playlist, index);
            File[] parts = sourceSegments.listFiles((dir, name) -> name.endsWith(".ts"));
            if (parts == null || parts.length == 0) {
                throw new IllegalStateException("오프라인 영상 조각이 없습니다.");
            }
            java.util.Arrays.sort(parts, java.util.Comparator.comparing(File::getName));
            for (File part : parts) publish(part, new File(targetSegments, part.getName()));
            return index;
        } catch (Exception error) {
            deleteTree(packageDir);
            throw error;
        }
    }

    private File videoRoot() {
        File movies = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
        if (movies == null) movies = new File(getFilesDir(), "movies");
        File root = new File(movies, "TVRoomDownloader");
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("영상 저장 폴더를 만들지 못했습니다.");
        }
        return root;
    }

    private String safeTitle(String title) {
        String safe = title.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        return safe.isEmpty() ? "tvroom-video" : safe;
    }

    private void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private void publish(File source, File destination) throws Exception {
        File publishing = new File(destination.getParentFile(), destination.getName() + ".part");
        publishing.delete();
        try (InputStream in = Files.newInputStream(source.toPath());
             FileOutputStream out = new FileOutputStream(publishing)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (cancelled.get()) throw new InterruptedException("다운로드 중단");
                out.write(buffer, 0, read);
            }
            out.getFD().sync();
        } catch (Exception error) {
            publishing.delete();
            throw error;
        }
        Files.move(publishing.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        source.delete();
    }

    private String downloadThumbnail(CaptureState.Snapshot job) {
        if (job.thumbnailUrl.isEmpty()) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(job.thumbnailUrl).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Referer", job.pageUrl);
            connection.setRequestProperty("User-Agent", job.userAgent);
            if (!job.cookie.isEmpty()) connection.setRequestProperty("Cookie", job.cookie);
            File root = new File(getFilesDir(), "thumbnails");
            if (!root.exists()) root.mkdirs();
            File output = new File(root, job.id + ".jpg");
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            }
            return output.getAbsolutePath();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void broadcast(String message) {
        sendBroadcast(new Intent(ACTION_PROGRESS).setPackage(getPackageName())
                .putExtra(EXTRA_MESSAGE, message));
    }

    private android.app.Notification notification(String message, int progress) {
        PendingIntent pending = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, VideoDownloadService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("티비룸 다운로더")
                .setContentText(message)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(pending)
                .addAction(0, "중단", stop);
        if (progress > 0) builder.setProgress(100, progress, false);
        else builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void updateNotification(String message, int progress) {
        getSystemService(NotificationManager.class).notify(
                NOTIFICATION_ID, notification(message, progress));
    }

    private void acquireWakeLock() {
        releaseWakeLock();
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "tvroom:download");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        stopRequested.set(true);
        cancelled.set(true);
        synchronized (queueLock) {
            pendingJobs.clear();
            processing = false;
        }
        LibraryDatabase.get(this).stopQueuedDownloads();
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        executor.shutdownNow();
        releaseWakeLock();
        RUNNING.set(false);
        super.onDestroy();
    }
}
