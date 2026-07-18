package com.tvroom.downloader.download;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;

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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile HttpURLConnection activeConnection;
    private volatile Thread worker;
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() { return RUNNING.get(); }

    public static void start(Context context, CaptureState.Snapshot snapshot) {
        LibraryDatabase.get(context).upsert(new VideoItem(snapshot.id, snapshot.title, snapshot.pageUrl,
                null, null, "queued", 0, ""));
        Intent intent = new Intent(context, VideoDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_JOB, snapshot.toJson());
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, VideoDownloadService.class).setAction(ACTION_STOP));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "영상 다운로드", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            cancelled.set(true); HttpURLConnection connection = activeConnection;
            if (connection != null) connection.disconnect();
            Thread thread = worker; if (thread != null) thread.interrupt();
            updateNotification("다운로드 중단 중…", 0); return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (!RUNNING.compareAndSet(false, true)) return START_NOT_STICKY;
        startForeground(NOTIFICATION_ID, notification("다운로드 준비 중…", 0));
        String raw = intent.getStringExtra(EXTRA_JOB);
        executor.execute(() -> runJob(raw));
        return START_NOT_STICKY;
    }

    private void runJob(String raw) {
        worker = Thread.currentThread();
        CaptureState.Snapshot job = null;
        File workDir = null;
        try {
            job = CaptureState.Snapshot.fromJson(raw);
            acquireWakeLock();
            workDir = TempFiles.jobDir(this, job.id);
            LibraryDatabase.get(this).updateProgress(job.id, "downloading", 1, "");
            broadcast("다운로드 준비 중…");
            String thumbnail = downloadThumbnail(job);
            LibraryDatabase.get(this).updateThumbnail(job.id, thumbnail);
            broadcast("영상 정보를 저장했습니다. 다운로드를 시작합니다.");
            File tempMp4 = new File(workDir, "restored.mp4.part");
            CaptureState.Snapshot finalJob = job;
            new HlsDownloader(job, (message, percent) -> {
                LibraryDatabase.get(this).updateProgress(finalJob.id, "downloading", percent, "");
                updateNotification(message, percent); broadcast(message);
            }, new HlsDownloader.Cancellation() {
                @Override public boolean cancelled() { return cancelled.get(); }
                @Override public void connection(HttpURLConnection connection) { activeConnection = connection; }
            }).download(workDir, tempMp4);
            if (cancelled.get()) throw new InterruptedException("다운로드 중단");
            File finalFile = finalVideoFile(job.title);
            publish(tempMp4, finalFile);
            LibraryDatabase.get(this).complete(job.id, thumbnail, finalFile.getAbsolutePath());
            updateNotification("다운로드 완료", 100); broadcast("다운로드 완료 · " + job.title);
        } catch (InterruptedException error) {
            if (job != null) LibraryDatabase.get(this).updateProgress(job.id, "stopped", 0, "사용자가 중단했습니다.");
            broadcast("다운로드를 중단하고 임시 파일을 삭제했습니다.");
        } catch (Exception error) {
            String message = error.getMessage() == null ? "다운로드에 실패했습니다." : error.getMessage();
            if (job != null) LibraryDatabase.get(this).updateProgress(job.id, "error", 0, message);
            broadcast("다운로드 실패 · " + message);
        } finally {
            activeConnection = null;
            if (workDir != null && job != null) TempFiles.deleteJob(this, job.id);
            releaseWakeLock(); RUNNING.set(false); worker = null;
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }

    private File finalVideoFile(String title) {
        File movies = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
        if (movies == null) movies = new File(getFilesDir(), "movies");
        File root = new File(movies, "TVRoomDownloader");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("영상 저장 폴더를 만들지 못했습니다.");
        String safe = title.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        if (safe.isEmpty()) safe = "tvroom-video";
        File file = new File(root, safe + ".mp4"); int suffix = 1;
        while (file.exists()) file = new File(root, safe + "_" + suffix++ + ".mp4");
        return file;
    }

    private void publish(File source, File destination) throws Exception {
        File publishing = new File(destination.getParentFile(), destination.getName() + ".part");
        publishing.delete();
        try (InputStream in = Files.newInputStream(source.toPath());
             FileOutputStream out = new FileOutputStream(publishing)) {
            byte[] buffer = new byte[1024 * 1024]; int read;
            while ((read = in.read(buffer)) >= 0) {
                if (cancelled.get()) throw new InterruptedException("다운로드 중단");
                out.write(buffer, 0, read);
            }
            out.getFD().sync();
        } catch (Exception error) {
            publishing.delete(); throw error;
        }
        Files.move(publishing.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        source.delete();
    }

    private String downloadThumbnail(CaptureState.Snapshot job) {
        if (job.thumbnailUrl.isEmpty()) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(job.thumbnailUrl).openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setRequestProperty("Referer", job.pageUrl);
            connection.setRequestProperty("User-Agent", job.userAgent);
            if (!job.cookie.isEmpty()) connection.setRequestProperty("Cookie", job.cookie);
            File root = new File(getFilesDir(), "thumbnails"); if (!root.exists()) root.mkdirs();
            File output = new File(root, job.id + ".jpg");
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[32 * 1024]; int read;
                while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            }
            return output.getAbsolutePath();
        } catch (Exception ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private void broadcast(String message) {
        sendBroadcast(new Intent(ACTION_PROGRESS).setPackage(getPackageName()).putExtra(EXTRA_MESSAGE, message));
    }

    private android.app.Notification notification(String message, int progress) {
        PendingIntent pending = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, VideoDownloadService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_app).setContentTitle("티비룸 다운로더")
                .setContentText(message).setOnlyAlertOnce(true).setOngoing(true)
                .setContentIntent(pending).addAction(0, "중단", stop);
        if (progress > 0) builder.setProgress(100, progress, false); else builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void updateNotification(String message, int progress) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message, progress));
    }

    private void acquireWakeLock() {
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "tvroom:download"); wakeLock.acquire(6 * 60 * 60 * 1000L);
    }
    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        cancelled.set(true); HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        executor.shutdownNow(); releaseWakeLock(); RUNNING.set(false); super.onDestroy();
    }
}
