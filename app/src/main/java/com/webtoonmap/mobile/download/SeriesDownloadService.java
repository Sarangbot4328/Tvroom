package com.webtoonmap.mobile.download;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.EpisodeItem;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.blacktoon.BlacktoonApi;
import com.webtoonmap.mobile.hitomi.HitomiApi;
import com.webtoonmap.mobile.ililtoon.IliltoonApi;
import com.webtoonmap.mobile.manhwabang.ManhwabangApi;
import com.webtoonmap.mobile.joatoon.JoatoonApi;
import com.webtoonmap.mobile.naver.NaverApi;
import com.webtoonmap.mobile.network.NetworkRetry;
import com.webtoonmap.mobile.network.OptionalImageDownloader;
import com.webtoonmap.mobile.storage.SourceSettings;
import com.webtoonmap.mobile.storage.StorageSettings;
import com.webtoonmap.mobile.storage.WebtoonStorage;
import com.webtoonmap.mobile.wolfdot.WolfdotApi;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SeriesDownloadService extends Service {
    public static final String ACTION_PROGRESS = "com.webtoonmap.mobile.DOWNLOAD_PROGRESS";
    public static final String EXTRA_TITLE_ID = "title_id";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_ERROR = "error";
    private static final String ACTION_PROCESS = "com.webtoonmap.mobile.PROCESS_DOWNLOAD_QUEUE";
    private static final String ACTION_STOP = "com.webtoonmap.mobile.STOP_CURRENT_DOWNLOAD";
    private static final String CHANNEL_ID = "webtoon_downloads";
    private static final int NOTIFICATION_ID = 2001;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String currentTitleId;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService progressWatchdog =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicBoolean stalledRestartRequested = new AtomicBoolean(false);
    private final AtomicBoolean manualStopRequested = new AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;
    private volatile Thread workerThread;
    private volatile boolean serviceDestroying;
    private volatile long lastProgressAtMs;

    public static boolean isRunning() { return RUNNING.get(); }
    public static boolean isDownloading(String titleId) {
        return RUNNING.get() && titleId != null && titleId.equals(currentTitleId);
    }

    public static boolean enqueue(Context context, String titleId) {
        boolean added = DownloadQueue.add(context.getApplicationContext(), titleId);
        Intent intent = new Intent(context, SeriesDownloadService.class);
        intent.setAction(ACTION_PROCESS);
        intent.putExtra(EXTRA_TITLE_ID, titleId);
        ContextCompat.startForegroundService(context, intent);
        return added;
    }

    public static boolean isQueued(Context context, String titleId) {
        return titleId != null && DownloadQueue.contains(context.getApplicationContext(), titleId);
    }

    public static void stopAll(Context context) {
        Intent intent = new Intent(context, SeriesDownloadService.class);
        intent.setAction(ACTION_STOP);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":webtoon-download");
        wakeLock.setReferenceCounted(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "웹툰 다운로드", NotificationManager.IMPORTANCE_LOW));
        lastProgressAtMs = SystemClock.elapsedRealtime();
        progressWatchdog.scheduleWithFixedDelay(
                this::checkForStalledDownload, 15, 15, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("다운로드 준비 중…", 0, 0));
        String action = intent == null ? ACTION_PROCESS : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            manualStopRequested.set(true);
            stalledRestartRequested.set(false);
            DownloadQueue.clear(this);
            if (RUNNING.get()) {
                cancelRequested.set(true);
                Thread thread = workerThread;
                NetworkRetry.cancel(thread);
                broadcast("전체 다운로드와 대기열을 중단하는 중…", false, false);
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return START_STICKY;
        }
        ensureWorker();
        return START_STICKY;
    }

    private void ensureWorker() {
        if (DownloadQueue.size(this) == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            broadcast("대기열에 추가됨 · " + DownloadQueue.size(this) + "개 작품", false, false);
            return;
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
        executor.execute(this::drainQueue);
    }

    private void drainQueue() {
        workerThread = Thread.currentThread();
        try {
            while (true) {
                String titleId = DownloadQueue.peek(this);
                if (titleId == null) break;
                currentTitleId = titleId;
                cancelRequested.set(false);
                stalledRestartRequested.set(false);
                markProgress();
                Thread.interrupted();
                try {
                    downloadOne(titleId);
                } catch (Exception e) {
                    boolean cancelled = cancelRequested.get() || Thread.currentThread().isInterrupted() ||
                            e instanceof InterruptedException;
                    Thread.interrupted();
                    if (cancelled) {
                        LibraryDatabase.get(this).setSeriesStatus(titleId, "paused");
                        if (stalledRestartRequested.get() && !manualStopRequested.get()) {
                            broadcast("진행 정체 감지 · 미완성 회차 정리 후 자동 이어받기합니다.",
                                    false, false);
                        } else {
                            broadcast("전체 다운로드 중단됨 · 대기열과 미완성 회차를 정리했습니다.",
                                    true, false);
                        }
                    } else {
                        LibraryDatabase.get(this).setSeriesStatus(titleId, "error");
                        String message = e.getMessage() == null ? "다운로드 실패" : e.getMessage();
                        update("오류 · " + message, 0, 0);
                        broadcast(message, true, true);
                    }
                } finally {
                    cleanupIncomplete(titleId);
                    boolean restart = stalledRestartRequested.get() &&
                            SourceSettings.isLowDataMode(this) &&
                            !manualStopRequested.get() && !serviceDestroying;
                    if (!restart && !serviceDestroying) DownloadQueue.remove(this, titleId);
                    currentTitleId = null;
                    if (restart) {
                        cancelRequested.set(false);
                        stalledRestartRequested.set(false);
                        Thread.interrupted();
                        update("자동 이어받기 재시작 중…", 0, 0);
                    }
                }
            }
        } finally {
            workerThread = null;
            currentTitleId = null;
            RUNNING.set(false);
            if (!serviceDestroying && DownloadQueue.size(this) > 0) {
                ensureWorker();
            } else {
                releaseWakeLock();
                if (!serviceDestroying) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }
        }
    }

    private void downloadOne(String titleId) throws Exception {
        if (ManhwabangApi.isSeriesKey(titleId)) {
            downloadManhwabang(titleId);
        } else if (IliltoonApi.isSeriesKey(titleId)) {
            downloadIliltoon(titleId);
        } else if (BlacktoonApi.isSeriesKey(titleId)) {
            downloadBlacktoon(titleId);
        } else if (WolfdotApi.isSeriesKey(titleId)) {
            downloadWolfdot(titleId);
        } else if (HitomiApi.isSeriesKey(titleId)) {
            downloadHitomi(titleId);
        } else if (JoatoonApi.isSeriesKey(titleId)) {
            downloadJoatoon(titleId);
        } else {
            downloadNaver(titleId);
        }
    }

    private void downloadNaver(String titleId) throws Exception {
        LibraryDatabase db = LibraryDatabase.get(this);
        checkCancelled();
        String cookie = CookieManager.getInstance().getCookie(NaverApi.ORIGIN);
        update("작품 정보를 불러오는 중… · 대기열 " + DownloadQueue.size(this) + "개", 0, 0);
        NaverApi.SeriesInfo info = NaverApi.fetchSeriesInfo(titleId, cookie);
        checkCancelled();
        SeriesItem existing = db.getSeries(titleId);
        String storageUri = existing == null ? StorageSettings.getTreeUri(this) : existing.storageUri;
        WebtoonStorage storage = new WebtoonStorage(this, storageUri);

        update("미완성 회차 파일을 정리하는 중…", 0, 0);
        cleanupDownloadCache(titleId);
        storage.cleanupIncomplete(titleId);

        String thumbnailPath = saveThumbnail(info, storage, cookie);
        checkCancelled();
        if (thumbnailPath == null && existing != null) thumbnailPath = existing.thumbnailPath;
        db.upsertSeries(new SeriesItem(titleId, info.title, info.description, info.tags,
                thumbnailPath, storage.storageUri(), "downloading", 0));

        List<NaverApi.EpisodeMeta> episodes = NaverApi.fetchFreeEpisodes(titleId, cookie);
        checkCancelled();
        if (episodes.isEmpty()) throw new IllegalStateException("다운로드 가능한 공개 회차가 없습니다.");

        int current = 0;
        for (NaverApi.EpisodeMeta episode : episodes) {
            checkCancelled();
            current++;
            if (db.hasCompleteEpisode(titleId, episode.number) &&
                    storage.episodeZipExists(titleId, episode.number)) {
                update(current + "/" + episodes.size() + "화 · 이미 보유", current, episodes.size());
                continue;
            }
            String label = current + "/" + episodes.size() + "화 · " + episode.title;
            update(label + " 분석 중", current - 1, episodes.size());
            List<String> images = NaverApi.fetchEpisodeImages(
                    titleId, episode.number, info.segment, cookie);
            checkCancelled();
            String referer = NaverApi.ORIGIN + "/" + info.segment + "/detail?titleId=" +
                    titleId + "&no=" + episode.number;
            int saved = 0;
            File tempDir = new File(getCacheDir(), "download-zips");
            if (!tempDir.exists() && !tempDir.mkdirs()) throw new IllegalStateException("임시 폴더 생성 실패");
            File tempZip = new File(tempDir, titleId + "-" + episode.number + ".zip.part");
            try {
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tempZip))) {
                    for (int i = 0; i < images.size(); i++) {
                        checkCancelled();
                        String imageUrl = images.get(i);
                        byte[] bytes = NaverApi.downloadBytes(imageUrl, referer, cookie);
                        checkCancelled();
                        String entryName = String.format(Locale.US, "%03d", i + 1) + imageExtension(imageUrl);
                        zip.putNextEntry(new ZipEntry(entryName));
                        zip.write(bytes);
                        zip.closeEntry();
                        saved++;
                        if ((i + 1) % 5 == 0 || i + 1 == images.size()) {
                            update(label + " · " + (i + 1) + "/" + images.size() + "장 압축", current - 1, episodes.size());
                        }
                    }
                }
                checkCancelled();
                if (saved == 0) throw new IllegalStateException(episode.number + "화 저장 실패");
                storage.writeEpisodeZip(titleId, episode.number, tempZip);
            } finally {
                tempZip.delete();
            }
            checkCancelled();
            db.upsertEpisode(new EpisodeItem(titleId, episode.number, episode.title, saved, false));
            update(label + " ZIP 저장 완료", current, episodes.size());
        }
        db.setSeriesStatus(titleId, "complete");
        update("완료 · " + episodes.size() + "개 공개 회차", episodes.size(), episodes.size());
        broadcast("다운로드 완료", true, false);
    }

    private void downloadJoatoon(String titleId) throws Exception {
        LibraryDatabase db = LibraryDatabase.get(this);
        String seriesId = JoatoonApi.remoteId(titleId);
        if (seriesId == null) throw new IllegalArgumentException("잘못된 조아툰 작품 번호입니다.");
        String baseUrl = SourceSettings.getJoatoonUrl(this);
        String cookie = CookieManager.getInstance().getCookie(baseUrl);
        checkCancelled();
        update("조아툰 작품 정보를 불러오는 중… · 대기열 " + DownloadQueue.size(this) + "개", 0, 0);
        JoatoonApi.SeriesInfo info = JoatoonApi.fetchSeriesInfo(baseUrl, seriesId, cookie);
        checkCancelled();

        SeriesItem existing = db.getSeries(titleId);
        String storageUri = existing == null ? StorageSettings.getTreeUri(this) : existing.storageUri;
        WebtoonStorage storage = new WebtoonStorage(this, storageUri);
        update("미완성 회차 파일을 정리하는 중…", 0, 0);
        cleanupDownloadCache(titleId);
        storage.cleanupIncomplete(titleId);

        String thumbnailPath = saveJoatoonThumbnail(titleId, info, storage, cookie);
        checkCancelled();
        if (thumbnailPath == null && existing != null) thumbnailPath = existing.thumbnailPath;
        db.upsertSeries(new SeriesItem(titleId, info.title, info.description, info.tags,
                thumbnailPath, storage.storageUri(), "downloading", 0));
        if (info.episodes.isEmpty()) throw new IllegalStateException("조아툰 회차 목록을 찾지 못했습니다.");

        int current = 0;
        for (JoatoonApi.EpisodeMeta episode : info.episodes) {
            checkCancelled();
            current++;
            if (db.hasCompleteEpisode(titleId, episode.number) &&
                    storage.episodeZipExists(titleId, episode.number)) {
                update(current + "/" + info.episodes.size() + "화 · 이미 보유", current, info.episodes.size());
                continue;
            }

            String label = current + "/" + info.episodes.size() + "화 · " + episode.title;
            update(label + " 분석 중", current - 1, info.episodes.size());
            List<String> images = JoatoonApi.fetchEpisodeImages(episode.url, cookie);
            checkCancelled();
            int saved = 0;
            File tempDir = new File(getCacheDir(), "download-zips");
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                throw new IllegalStateException("임시 폴더 생성 실패");
            }
            File tempZip = new File(tempDir, titleId + "-" + episode.number + ".zip.part");
            try {
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tempZip))) {
                    for (int i = 0; i < images.size(); i++) {
                        checkCancelled();
                        String imageUrl = images.get(i);
                        byte[] bytes = JoatoonApi.downloadBytes(imageUrl, episode.url, cookie);
                        checkCancelled();
                        String entryName = String.format(Locale.US, "%03d", i + 1) + imageExtension(imageUrl);
                        zip.putNextEntry(new ZipEntry(entryName));
                        zip.write(bytes);
                        zip.closeEntry();
                        saved++;
                        if ((i + 1) % 5 == 0 || i + 1 == images.size()) {
                            update(label + " · " + (i + 1) + "/" + images.size() + "장 압축",
                                    current - 1, info.episodes.size());
                        }
                    }
                }
                checkCancelled();
                if (saved == 0) throw new IllegalStateException(episode.number + "화 저장 실패");
                storage.writeEpisodeZip(titleId, episode.number, tempZip);
            } finally {
                tempZip.delete();
            }
            checkCancelled();
            db.upsertEpisode(new EpisodeItem(titleId, episode.number, episode.title, saved, false));
            update(label + " ZIP 저장 완료", current, info.episodes.size());
        }
        db.setSeriesStatus(titleId, "complete");
        update("완료 · " + info.episodes.size() + "개 회차", info.episodes.size(), info.episodes.size());
        broadcast("조아툰 다운로드 완료", true, false);
    }


    private interface ExternalSiteApi {
        List<String> fetchImages(String episodeUrl) throws Exception;
        byte[] downloadBytes(String imageUrl, String referer) throws Exception;
    }

    private static final class ExternalEpisode {
        final int number;
        final String title;
        final String url;

        ExternalEpisode(int number, String title, String url) {
            this.number = number;
            this.title = title;
            this.url = url;
        }
    }

    private void downloadManhwabang(String titleId) throws Exception {
        SourceJobStore.Job job = SourceJobStore.get(this, titleId);
        if (job == null) {
            throw new IllegalStateException("만화방 작품 주소 정보가 없습니다. 작품 페이지에서 다시 다운로드를 눌러 주세요.");
        }
        String baseUrl = SourceSettings.getManhwabangUrl(this);
        String pageUrl = job.pageUrl(baseUrl);
        String cookie = CookieManager.getInstance().getCookie(baseUrl);
        checkCancelled();
        update("만화방 작품 정보를 불러오는 중… · 대기열 " + DownloadQueue.size(this) + "개", 0, 0);
        ManhwabangApi.SeriesInfo info =
                ManhwabangApi.fetchSeriesInfo(pageUrl, job.kind, cookie);
        List<ExternalEpisode> episodes = new java.util.ArrayList<>();
        for (ManhwabangApi.EpisodeMeta episode : info.episodes) {
            episodes.add(new ExternalEpisode(episode.number, episode.title, episode.url));
        }
        downloadExternalSeries(titleId, "만화방", info.title, info.description, info.tags,
                info.thumbnailUrl, info.pageUrl, cookie, episodes, new ExternalSiteApi() {
                    @Override public List<String> fetchImages(String episodeUrl) throws Exception {
                        return ManhwabangApi.fetchEpisodeImages(episodeUrl, cookie);
                    }

                    @Override public byte[] downloadBytes(String imageUrl, String referer)
                            throws Exception {
                        return ManhwabangApi.downloadBytes(imageUrl, referer, cookie);
                    }
                });
    }

    private void downloadIliltoon(String titleId) throws Exception {
        SourceJobStore.Job job = SourceJobStore.get(this, titleId);
        if (job == null) {
            throw new IllegalStateException("일일툰 작품 주소 정보가 없습니다. 작품 페이지에서 다시 다운로드를 눌러 주세요.");
        }
        String baseUrl = SourceSettings.getIliltoonUrl(this);
        String pageUrl = job.pageUrl(baseUrl);
        String cookie = CookieManager.getInstance().getCookie(baseUrl);
        checkCancelled();
        update("일일툰 작품 정보를 불러오는 중… · 대기열 " + DownloadQueue.size(this) + "개", 0, 0);
        IliltoonApi.SeriesInfo info = IliltoonApi.fetchSeriesInfo(pageUrl, cookie);
        List<ExternalEpisode> episodes = new java.util.ArrayList<>();
        for (IliltoonApi.EpisodeMeta episode : info.episodes) {
            episodes.add(new ExternalEpisode(episode.number, episode.title, episode.url));
        }
        downloadExternalSeries(titleId, "일일툰", info.title, info.description, info.tags,
                info.thumbnailUrl, info.pageUrl, cookie, episodes, new ExternalSiteApi() {
                    @Override public List<String> fetchImages(String episodeUrl) throws Exception {
                        return IliltoonApi.fetchEpisodeImages(episodeUrl, cookie);
                    }

                    @Override public byte[] downloadBytes(String imageUrl, String referer)
                            throws Exception {
                        return IliltoonApi.downloadBytes(imageUrl, referer, cookie);
                    }
                });
    }

    private void downloadBlacktoon(String titleId) throws Exception {
        SourceJobStore.Job job = SourceJobStore.get(this, titleId);
        if (job == null) {
            throw new IllegalStateException("블랙툰 작품 주소 정보가 없습니다. 작품 페이지에서 다시 다운로드를 눌러 주세요.");
        }
        String baseUrl = SourceSettings.getBlacktoonUrl(this);
        String seriesId = job.remoteId;
        if (seriesId == null || seriesId.isEmpty()) {
            throw new IllegalStateException("블랙툰 작품 번호를 확인하지 못했습니다. 작품 페이지에서 다시 다운로드를 눌러 주세요.");
        }
        String cookie = CookieManager.getInstance().getCookie(baseUrl);
        checkCancelled();
        update("블랙툰 작품 정보를 불러오는 중… · 대기열 " + DownloadQueue.size(this) + "개", 0, 0);
        BlacktoonApi.SeriesInfo info = BlacktoonApi.fetchSeriesInfo(baseUrl, seriesId, cookie);
        List<ExternalEpisode> episodes = new java.util.ArrayList<>();
        for (BlacktoonApi.EpisodeMeta episode : info.episodes) {
            episodes.add(new ExternalEpisode(episode.number, episode.title, episode.url));
        }
        downloadExternalSeries(titleId, "블랙툰", info.title, info.description, info.tags,
                info.thumbnailUrl, info.pageUrl, cookie, episodes, new ExternalSiteApi() {
                    @Override public List<String> fetchImages(String episodeUrl) throws Exception {
                        return BlacktoonApi.fetchEpisodeImages(episodeUrl, cookie);
                    }

                    @Override public byte[] downloadBytes(String imageUrl, String referer)
                            throws Exception {
                        return BlacktoonApi.downloadBytes(imageUrl, referer, cookie);
                    }
                });
    }

    private void downloadWolfdot(String titleId) throws Exception {
        SourceJobStore.Job job = SourceJobStore.get(this, titleId);
        if (job == null) {
            throw new IllegalStateException("늑대닷컴 작품 주소 정보가 없습니다. 작품 페이지에서 다시 다운로드를 눌러 주세요.");
        }
        String baseUrl = SourceSettings.getWolfdotUrl(this);
        String seriesId = job.remoteId;
        if (seriesId == null || seriesId.isEmpty()) {
            throw new IllegalStateException("늑대닷컴 작품 번호를 확인하지 못했습니다. 작품 페이지에서 다시 다운로드를 눌러 주세요.");
        }
        String cookie = CookieManager.getInstance().getCookie(baseUrl);
        checkCancelled();
        update("늑대닷컴 작품 정보를 불러오는 중… · 대기열 " + DownloadQueue.size(this) + "개", 0, 0);
        WolfdotApi.SeriesInfo info = WolfdotApi.fetchSeriesInfo(baseUrl, seriesId, job.kind, cookie);
        List<ExternalEpisode> episodes = new java.util.ArrayList<>();
        for (WolfdotApi.EpisodeMeta episode : info.episodes) {
            episodes.add(new ExternalEpisode(episode.number, episode.title, episode.url));
        }
        downloadExternalSeries(titleId, "늑대닷컴", info.title, info.description, info.tags,
                info.thumbnailUrl, info.pageUrl, cookie, episodes, new ExternalSiteApi() {
                    @Override public List<String> fetchImages(String episodeUrl) throws Exception {
                        return WolfdotApi.fetchEpisodeImages(episodeUrl, cookie);
                    }

                    @Override public byte[] downloadBytes(String imageUrl, String referer)
                            throws Exception {
                        return WolfdotApi.downloadBytes(imageUrl, referer, cookie);
                    }
                });
    }

    private void downloadHitomi(String titleId) throws Exception {
        SourceJobStore.Job job = SourceJobStore.get(this, titleId);
        String baseUrl = SourceSettings.getHitomiUrl(this);
        String galleryId = job == null || job.remoteId == null || job.remoteId.isEmpty()
                ? HitomiApi.remoteId(titleId) : job.remoteId;
        if (galleryId == null || galleryId.isEmpty()) {
            throw new IllegalStateException("히토미 갤러리 번호를 확인하지 못했습니다. 작품 페이지에서 다시 다운로드를 눌러 주세요.");
        }
        String pageUrl = job == null
                ? baseUrl + "/reader/" + galleryId + ".html" : job.pageUrl(baseUrl);
        String cookie = CookieManager.getInstance().getCookie(baseUrl);
        checkCancelled();
        update("히토미 갤러리 정보를 불러오는 중… · 대기열 " + DownloadQueue.size(this) + "개", 0, 0);
        HitomiApi.SeriesInfo info =
                HitomiApi.fetchSeriesInfo(baseUrl, galleryId, pageUrl, cookie);
        List<ExternalEpisode> episodes = new java.util.ArrayList<>();
        episodes.add(new ExternalEpisode(1, "전체", info.pageUrl));
        downloadExternalSeries(titleId, "히토미", info.title, info.description, info.tags,
                info.thumbnailUrl, info.pageUrl, cookie, episodes, new ExternalSiteApi() {
                    @Override public List<String> fetchImages(String episodeUrl) {
                        return info.imageUrls;
                    }

                    @Override public byte[] downloadBytes(String imageUrl, String referer)
                            throws Exception {
                        return HitomiApi.downloadBytes(imageUrl, referer, cookie);
                    }
                });
    }

    private void downloadExternalSeries(String titleId, String sourceName, String title,
                                        String description, String tags, String thumbnailUrl,
                                        String pageUrl, String cookie,
                                        List<ExternalEpisode> episodes,
                                        ExternalSiteApi api) throws Exception {
        checkCancelled();
        LibraryDatabase db = LibraryDatabase.get(this);
        SeriesItem existing = db.getSeries(titleId);
        String storageUri = existing == null ? StorageSettings.getTreeUri(this) : existing.storageUri;
        WebtoonStorage storage = new WebtoonStorage(this, storageUri);

        update("미완성 회차 파일을 정리하는 중…", 0, 0);
        cleanupDownloadCache(titleId);
        storage.cleanupIncomplete(titleId);

        String thumbnailPath = saveExternalThumbnail(
                titleId, thumbnailUrl, pageUrl, cookie, storage);
        checkCancelled();
        if (thumbnailPath == null && existing != null) thumbnailPath = existing.thumbnailPath;
        db.upsertSeries(new SeriesItem(titleId, title, description, tags,
                thumbnailPath, storage.storageUri(), "downloading", 0));
        if (episodes.isEmpty()) {
            throw new IllegalStateException(sourceName + " 회차 목록을 찾지 못했습니다.");
        }

        int current = 0;
        for (ExternalEpisode episode : episodes) {
            checkCancelled();
            current++;
            if (db.hasCompleteEpisode(titleId, episode.number) &&
                    storage.episodeZipExists(titleId, episode.number)) {
                update(current + "/" + episodes.size() + "화 · 이미 보유",
                        current, episodes.size());
                continue;
            }

            String label = current + "/" + episodes.size() + "화 · " + episode.title;
            update(label + " 분석 중", current - 1, episodes.size());
            List<String> images = api.fetchImages(episode.url);
            checkCancelled();
            if (images.isEmpty()) {
                throw new IllegalStateException(episode.number + "화 이미지를 찾지 못했습니다.");
            }
            File tempZip = newEpisodeTempZip(titleId, episode.number);
            int saved = 0;
            try {
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tempZip))) {
                    for (int i = 0; i < images.size(); i++) {
                        checkCancelled();
                        String imageUrl = images.get(i);
                        update(label + " · " + (i + 1) + "/" + images.size() +
                                        "장 다운로드 중",
                                current - 1, episodes.size());
                        byte[] bytes = api.downloadBytes(imageUrl, episode.url);
                        checkCancelled();
                        String entryName = String.format(Locale.US, "%03d", i + 1) +
                                imageExtension(imageUrl);
                        zip.putNextEntry(new ZipEntry(entryName));
                        zip.write(bytes);
                        zip.closeEntry();
                        saved++;
                        if ((i + 1) % 5 == 0 || i + 1 == images.size()) {
                            update(label + " · " + (i + 1) + "/" + images.size() + "장 압축",
                                    current - 1, episodes.size());
                        }
                    }
                }
                checkCancelled();
                if (saved == 0) {
                    throw new IllegalStateException(episode.number + "화 저장 실패");
                }
                storage.writeEpisodeZip(titleId, episode.number, tempZip);
            } finally {
                tempZip.delete();
            }
            checkCancelled();
            db.upsertEpisode(new EpisodeItem(
                    titleId, episode.number, episode.title, saved, false));
            update(label + " ZIP 저장 완료", current, episodes.size());
        }

        db.setSeriesStatus(titleId, "complete");
        update("완료 · " + episodes.size() + "개 회차", episodes.size(), episodes.size());
        broadcast(sourceName + " 다운로드 완료", true, false);
    }

    private File newEpisodeTempZip(String titleId, int episodeNumber) {
        File tempDir = new File(getCacheDir(), "download-zips");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IllegalStateException("임시 폴더 생성 실패");
        }
        return new File(tempDir, titleId + "-" + episodeNumber + ".zip.part");
    }

    private String saveExternalThumbnail(String titleId, String thumbnailUrl, String pageUrl,
                                         String cookie, WebtoonStorage storage) {
        if (thumbnailUrl == null || thumbnailUrl.isEmpty()) return null;
        try {
            return storage.writeThumbnail(titleId,
                    OptionalImageDownloader.download(thumbnailUrl, pageUrl, cookie));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("다운로드 중단");
        }
    }

    private void cleanupIncomplete(String titleId) {
        Thread.interrupted();
        try {
            cleanupDownloadCache(titleId);
            SeriesItem item = LibraryDatabase.get(this).getSeries(titleId);
            String storageUri = item == null ? StorageSettings.getTreeUri(this) : item.storageUri;
            new WebtoonStorage(this, storageUri).cleanupIncomplete(titleId);
        } catch (Exception ignored) { }
    }

    private String saveThumbnail(NaverApi.SeriesInfo info, WebtoonStorage storage, String cookie) {
        if (info.thumbnailUrl == null || info.thumbnailUrl.isEmpty()) return null;
        try {
            byte[] bytes = OptionalImageDownloader.download(info.thumbnailUrl,
                    NaverApi.listUrl(info.titleId, info.segment), cookie);
            return storage.writeThumbnail(info.titleId, bytes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String saveJoatoonThumbnail(String titleId, JoatoonApi.SeriesInfo info,
                                        WebtoonStorage storage, String cookie) {
        if (info.thumbnailUrl == null || info.thumbnailUrl.isEmpty()) return null;
        try {
            byte[] bytes = OptionalImageDownloader.download(
                    info.thumbnailUrl, info.pageUrl, cookie);
            return storage.writeThumbnail(titleId, bytes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String imageExtension(String url) {
        String clean = url == null ? "" : url.toLowerCase(Locale.US).replaceFirst("[?#].*$", "");
        if (clean.endsWith(".png")) return ".png";
        if (clean.endsWith(".webp")) return ".webp";
        if (clean.endsWith(".jpeg")) return ".jpeg";
        return ".jpg";
    }

    private void cleanupDownloadCache(String titleId) {
        File tempDir = new File(getCacheDir(), "download-zips");
        File[] files = tempDir.listFiles((dir, name) ->
                name.startsWith(titleId + "-") && name.endsWith(".zip.part"));
        if (files == null) return;
        for (File file : files) {
            if (!file.delete()) throw new IllegalStateException("미완성 압축 파일 정리 실패");
        }
    }

    private void update(String message, int current, int total) {
        markProgress();
        getSystemService(NotificationManager.class).notify(
                NOTIFICATION_ID, notification(message, current, total));
        broadcast(message, false, false);
    }

    private void markProgress() {
        lastProgressAtMs = SystemClock.elapsedRealtime();
    }

    private void checkForStalledDownload() {
        if (serviceDestroying || manualStopRequested.get() ||
                !RUNNING.get() || currentTitleId == null) return;
        if (!SourceSettings.isLowDataMode(this)) {
            markProgress();
            return;
        }
        long limitMs = TimeUnit.MINUTES.toMillis(
                SourceSettings.getLowDataRestartMinutes(this));
        long stalledMs = SystemClock.elapsedRealtime() - lastProgressAtMs;
        if (stalledMs < limitMs ||
                !stalledRestartRequested.compareAndSet(false, true)) return;

        cancelRequested.set(true);
        int minutes = SourceSettings.getLowDataRestartMinutes(this);
        broadcast("저데이터 모드 · " + minutes +
                "분간 진행이 없어 자동 이어받기를 준비합니다.", false, false);
        NetworkRetry.cancel(workerThread);
    }

    private android.app.Notification notification(String message, int current, int total) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("웹툰여지도 · 다운로드")
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(pending);
        if (total > 0) builder.setProgress(total, current, false);
        else builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void broadcast(String message, boolean done, boolean error) {
        Intent intent = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_DONE, done);
        intent.putExtra(EXTRA_ERROR, error);
        sendBroadcast(intent);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        serviceDestroying = true;
        cancelRequested.set(true);
        Thread thread = workerThread;
        NetworkRetry.cancel(thread);
        RUNNING.set(false);
        currentTitleId = null;
        releaseWakeLock();
        progressWatchdog.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}








