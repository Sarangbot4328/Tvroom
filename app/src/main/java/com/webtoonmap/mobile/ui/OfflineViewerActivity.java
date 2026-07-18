package com.webtoonmap.mobile.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.EpisodeItem;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.data.SeriesItem;
import com.webtoonmap.mobile.storage.SourceSettings;
import com.webtoonmap.mobile.storage.ViewedSeriesHistory;
import com.webtoonmap.mobile.storage.WebtoonStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class OfflineViewerActivity extends AppCompatActivity {
    private static final String STATE_EPISODE_INDEX = "viewer_episode_index";
    private static final String STATE_PAGE_INDEX = "viewer_page_index";
    private static final String STATE_AUTO_ADVANCE_PAUSED = "viewer_auto_advance_paused";

    private String titleId;
    private List<EpisodeItem> episodes;
    private int episodeIndex;
    private WebView webView;
    private TextView titleView;
    private Button previous;
    private Button next;
    private boolean pageMode;
    private boolean pageWidthMode;
    private boolean episodeTransitionPending;
    private boolean autoAdvanceEnabled;
    private boolean autoAdvancePaused;
    private boolean viewerResumed;
    private int autoAdvanceSeconds;
    private float webtoonTouchStartY;
    private float webtoonTouchStartX;
    private boolean webtoonAtBottomOnTouchStart;
    private long lastWebtoonTapAt;
    private float lastWebtoonTapX;
    private float lastWebtoonTapY;
    private volatile int currentPage;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler autoAdvanceHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoAdvanceTask = this::performAutoAdvance;
    private int loadGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_viewer);
        SystemBarInsets.apply(this, findViewById(R.id.viewer_root), false);
        titleId = getIntent().getStringExtra("title_id");
        int episodeNo = getIntent().getIntExtra("episode_no", -1);
        if (titleId == null || episodeNo < 0) { finish(); return; }

        autoAdvanceEnabled = SourceSettings.isAutoAdvanceEnabled(this);
        autoAdvanceSeconds = SourceSettings.getAutoAdvanceSeconds(this);
        episodes = LibraryDatabase.get(this).listEpisodes(titleId);
        episodeIndex = 0;
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).number == episodeNo) episodeIndex = i;
        if (state != null && !episodes.isEmpty()) {
            episodeIndex = Math.max(0, Math.min(
                    state.getInt(STATE_EPISODE_INDEX, episodeIndex), episodes.size() - 1));
            currentPage = Math.max(0, state.getInt(STATE_PAGE_INDEX, 0));
            autoAdvancePaused = state.getBoolean(STATE_AUTO_ADVANCE_PAUSED, false);
        }

        webView = findViewById(R.id.image_list);
        titleView = findViewById(R.id.title);
        previous = findViewById(R.id.previous);
        next = findViewById(R.id.next);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        previous.setOnClickListener(v -> loadEpisode(episodeIndex - 1, 0));
        next.setOnClickListener(v -> requestNextEpisode());

        pageMode = SourceSettings.isPageMode(this);
        pageWidthMode = SourceSettings.isPageWidthMode(this);
        if (pageMode) {
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override public void handleOnBackPressed() {
                    // 가장자리 뒤로 가기 제스처로 뷰어가 닫히는 것을 막습니다.
                }
            });
        }

        WebSettings settings = webView.getSettings();
        // 페이지 제어 스크립트는 앱이 생성한 로컬 만화책 HTML에서만 실행됩니다.
        settings.setJavaScriptEnabled(pageMode || autoAdvanceEnabled);
        settings.setAllowFileAccess(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        if (pageMode) webView.addJavascriptInterface(new ViewerBridge(), "AndroidViewer");
        else configureWebtoonEndGesture();
        loadEpisode(episodeIndex, currentPage);
    }

    private void loadEpisode(int index, int initialPage) {
        if (index < 0 || index >= episodes.size()) return;
        autoAdvanceHandler.removeCallbacks(autoAdvanceTask);
        episodeTransitionPending = true;
        episodeIndex = index;
        currentPage = Math.max(0, initialPage);
        EpisodeItem episode = episodes.get(index);
        SeriesItem series = LibraryDatabase.get(this).getSeries(titleId);
        titleView.setText((series == null ? "웹툰" : series.title) + " · " + episode.number + "화");
        previous.setEnabled(index > 0);
        next.setEnabled(index + 1 < episodes.size());

        int generation = ++loadGeneration;
        webView.loadData("<html><body style='background:#111;color:#aaa;text-align:center;padding-top:80px'>ZIP 회차를 여는 중…</body></html>",
                "text/html", "UTF-8");
        String storageUri = series == null ? null : series.storageUri;
        executor.execute(() -> {
            try {
                File dir = extractEpisode(storageUri, episode.number);
                File[] images = dir.listFiles(file -> file.isFile() && file.getName().matches("(?i).+\\.(jpg|jpeg|png|webp)$"));
                if (images == null) images = new File[0];
                Arrays.sort(images, Comparator.comparing(File::getName));
                boolean hasNext = index + 1 < episodes.size();
                String html = pageMode
                        ? buildPageHtml(images, hasNext, currentPage)
                        : buildScrollHtml(images, hasNext);
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing()) return;
                    episodeTransitionPending = false;
                    ViewedSeriesHistory.record(OfflineViewerActivity.this, series);
                    LibraryDatabase.get(OfflineViewerActivity.this)
                            .markEpisodeViewed(titleId, episode.number);
                    webView.loadDataWithBaseURL("file://" + dir.getAbsolutePath() + "/", html,
                            "text/html", "UTF-8", null);
                    if (!pageMode) webView.scrollTo(0, 0);
                    scheduleAutoAdvance();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "ZIP 회차를 열지 못했습니다." : e.getMessage();
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing()) return;
                    episodeTransitionPending = false;
                    webView.loadData("<html><body style='background:#111;color:#ff8a80;padding:30px'>" +
                                    escapeHtml(message) + "</body></html>", "text/html", "UTF-8");
                });
            }
        });
    }

    private void requestNextEpisode() {
        if (episodeTransitionPending || episodeIndex + 1 >= episodes.size()) return;
        loadEpisode(episodeIndex + 1, 0);
    }

    private void scheduleAutoAdvance() {
        autoAdvanceHandler.removeCallbacks(autoAdvanceTask);
        if (!autoAdvanceEnabled || autoAdvancePaused || !viewerResumed ||
                episodeTransitionPending || isFinishing()) return;
        autoAdvanceHandler.postDelayed(autoAdvanceTask, autoAdvanceSeconds * 1000L);
    }

    private void performAutoAdvance() {
        if (!autoAdvanceEnabled || autoAdvancePaused || !viewerResumed ||
                episodeTransitionPending || isFinishing()) return;
        if (pageMode) {
            webView.evaluateJavascript(
                    "if(window.autoAdvancePage){window.autoAdvancePage();}", null);
        } else if (isWebtoonAtBottom()) {
            requestNextEpisode();
        } else {
            webView.evaluateJavascript(
                    "window.scrollBy({top:Math.max(1,window.innerHeight*0.9),behavior:'smooth'});",
                    null);
        }
        scheduleAutoAdvance();
    }

    private void toggleAutoAdvance() {
        if (!autoAdvanceEnabled) return;
        autoAdvancePaused = !autoAdvancePaused;
        if (autoAdvancePaused) {
            autoAdvanceHandler.removeCallbacks(autoAdvanceTask);
        } else {
            scheduleAutoAdvance();
        }
        Toast.makeText(this, autoAdvancePaused
                        ? "자동 넘기기 일시 중단"
                        : "자동 넘기기 재개",
                Toast.LENGTH_SHORT).show();
    }

    private void configureWebtoonEndGesture() {
        webView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                webtoonTouchStartX = event.getX();
                webtoonTouchStartY = event.getY();
                webtoonAtBottomOnTouchStart = isWebtoonAtBottom();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - webtoonTouchStartX;
                float dy = event.getY() - webtoonTouchStartY;
                float tapThreshold = 24f * getResources().getDisplayMetrics().density;
                if (autoAdvanceEnabled && Math.hypot(dx, dy) <= tapThreshold) {
                    long now = event.getEventTime();
                    float doubleTapDistance = 48f *
                            getResources().getDisplayMetrics().density;
                    if (now - lastWebtoonTapAt <= 350L &&
                            Math.hypot(event.getX() - lastWebtoonTapX,
                                    event.getY() - lastWebtoonTapY) <= doubleTapDistance) {
                        lastWebtoonTapAt = 0L;
                        toggleAutoAdvance();
                        webtoonAtBottomOnTouchStart = false;
                        return true;
                    }
                    lastWebtoonTapAt = now;
                    lastWebtoonTapX = event.getX();
                    lastWebtoonTapY = event.getY();
                } else {
                    lastWebtoonTapAt = 0L;
                }
                float swipeDistance = event.getY() - webtoonTouchStartY;
                float threshold = 48f * getResources().getDisplayMetrics().density;
                if (webtoonAtBottomOnTouchStart && swipeDistance < -threshold) {
                    requestNextEpisode();
                }
                webtoonAtBottomOnTouchStart = false;
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                webtoonAtBottomOnTouchStart = false;
                lastWebtoonTapAt = 0L;
            }
            return false;
        });
    }

    @SuppressWarnings("deprecation")
    private boolean isWebtoonAtBottom() {
        if (webView.getContentHeight() <= 0) return false;
        float contentHeight = webView.getContentHeight() * webView.getScale();
        float tolerance = 24f * getResources().getDisplayMetrics().density;
        return contentHeight <= webView.getScrollY() + webView.getHeight() + tolerance;
    }

    private String buildScrollHtml(File[] images, boolean hasNext) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=3'><style>html,body{margin:0;background:#111}img{display:block;width:100%;height:auto}</style></head><body>");
        for (File image : images) html.append("<img src='").append(image.getName()).append("' loading='lazy'>");
        html.append("<div style='height:64px;color:#aaa;text-align:center;padding-top:30px'>")
                .append(hasNext ? "위로 한 번 더 넘기면 다음 회차" : "마지막 회차입니다")
                .append("</div></body></html>");
        return html.toString();
    }

    private String buildPageHtml(File[] images, boolean hasNext, int initialPage) {
        String pageStyle = pageWidthMode
                ? ".page{flex:0 0 100vw;width:100vw;height:100vh;display:flex;align-items:flex-start;justify-content:center;overflow-x:hidden;overflow-y:auto;overscroll-behavior-y:contain}"
                + ".page img{width:100vw;height:auto;max-width:none;display:block;-webkit-user-drag:none;user-select:none}"
                : ".page{flex:0 0 100vw;width:100vw;height:100vh;display:flex;align-items:center;justify-content:center}"
                + ".page img{max-width:100vw;max-height:100vh;width:auto;height:auto;display:block;-webkit-user-drag:none;user-select:none}";
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=3'><style>"
                + "html,body{margin:0;background:#111;height:100%;overflow:hidden}"
                + ".pager{display:flex;flex-direction:row;height:100vh;width:100vw;overflow:visible;will-change:transform}"
                + pageStyle
                + "</style></head><body><div id='pager' class='pager'>");
        for (File image : images) {
            html.append("<div class='page'><img src='").append(image.getName()).append("'></div>");
        }
        html.append("</div><script>")
                .append("const pager=document.getElementById('pager');")
                .append("const hasNextEpisode=").append(hasNext).append(";")
                .append("const autoAdvanceEnabled=").append(autoAdvanceEnabled).append(";")
                .append("let currentPage=").append(Math.max(0, initialPage)).append(";")
                .append("let startX=0,startY=0,tracking=false,wheelLocked=false,lastTapAt=0,tapTimer=null;")
                .append("function maxPage(){return Math.max(0,pager.children.length-1);}")
                .append("function isZoomed(){return window.visualViewport&&window.visualViewport.scale>1.05;}")
                .append("function reportPage(){if(window.AndroidViewer&&AndroidViewer.onPageChanged){AndroidViewer.onPageChanged(currentPage);}}")
                .append("function goToPage(page,animated){currentPage=Math.max(0,Math.min(page,maxPage()));pager.style.transition=animated?'transform 180ms ease-out':'none';pager.style.transform='translate3d('+(-currentPage*window.innerWidth)+'px,0,0)';reportPage();}")
                .append("function turnPage(direction,animated){if(direction>0&&currentPage>=maxPage()){if(hasNextEpisode&&window.AndroidViewer&&AndroidViewer.onNextEpisodeRequested){AndroidViewer.onNextEpisodeRequested();}else{goToPage(maxPage(),animated);}return;}goToPage(currentPage+direction,animated);}")
                .append("function autoAdvancePage(){const page=pager.children[currentPage];if(page&&page.scrollTop+page.clientHeight<page.scrollHeight-2){page.scrollBy({top:Math.max(1,page.clientHeight*0.9),behavior:'smooth'});}else{turnPage(1,true);}}")
                .append("function handleTap(touch){const direction=touch.clientX>=window.innerWidth/2?1:-1;if(!autoAdvanceEnabled){turnPage(direction,true);return;}const now=Date.now();if(now-lastTapAt<=350){if(tapTimer){clearTimeout(tapTimer);tapTimer=null;}lastTapAt=0;if(window.AndroidViewer&&AndroidViewer.onAutoAdvanceToggleRequested){AndroidViewer.onAutoAdvanceToggleRequested();}return;}lastTapAt=now;tapTimer=setTimeout(function(){tapTimer=null;turnPage(direction,true);},350);}")
                .append("pager.addEventListener('touchstart',function(e){if(e.touches.length!==1||isZoomed()){tracking=false;return;}tracking=true;startX=e.touches[0].clientX;startY=e.touches[0].clientY;},{passive:true});")
                .append("pager.addEventListener('touchmove',function(e){if(!tracking||e.touches.length!==1){tracking=false;return;}const dx=e.touches[0].clientX-startX;const dy=e.touches[0].clientY-startY;if(Math.abs(dx)>Math.abs(dy)){e.preventDefault();}},{passive:false});")
                .append("pager.addEventListener('touchend',function(e){if(!tracking||e.changedTouches.length===0)return;tracking=false;const touch=e.changedTouches[0];const dx=touch.clientX-startX;const dy=touch.clientY-startY;const threshold=Math.max(40,window.innerWidth*0.08);if(Math.abs(dx)>=threshold&&Math.abs(dx)>Math.abs(dy)){turnPage(dx<0?1:-1,true);}else if(Math.hypot(dx,dy)<=20){if(autoAdvanceEnabled)e.preventDefault();handleTap(touch);}else{goToPage(currentPage,true);}},{passive:false});")
                .append("pager.addEventListener('touchcancel',function(){tracking=false;goToPage(currentPage,true);},{passive:true});")
                .append("window.addEventListener('wheel',function(e){if(isZoomed()||Math.abs(e.deltaX)<=Math.abs(e.deltaY))return;e.preventDefault();if(wheelLocked)return;wheelLocked=true;turnPage(e.deltaX>0?1:-1,true);setTimeout(function(){wheelLocked=false;},350);},{passive:false});")
                .append("window.addEventListener('keydown',function(e){if(e.key==='ArrowRight'){e.preventDefault();turnPage(1,true);}else if(e.key==='ArrowLeft'){e.preventDefault();turnPage(-1,true);}});")
                .append("window.addEventListener('resize',function(){goToPage(currentPage,false);});")
                .append("window.addEventListener('load',function(){goToPage(currentPage,false);});")
                .append("window.getCurrentPage=function(){return currentPage;};")
                .append("window.turnPage=turnPage;")
                .append("window.autoAdvancePage=autoAdvancePage;")
                .append("goToPage(currentPage,false);")
                .append("</script></body></html>");
        return html.toString();
    }

    private final class ViewerBridge {
        @JavascriptInterface
        public void onPageChanged(int page) {
            currentPage = Math.max(0, page);
        }

        @JavascriptInterface
        public void onNextEpisodeRequested() {
            runOnUiThread(() -> {
                if (!isFinishing()) requestNextEpisode();
            });
        }

        @JavascriptInterface
        public void onAutoAdvanceToggleRequested() {
            runOnUiThread(() -> {
                if (!isFinishing()) toggleAutoAdvance();
            });
        }
    }

    @Override protected void onResume() {
        super.onResume();
        viewerResumed = true;
        scheduleAutoAdvance();
    }

    @Override protected void onPause() {
        viewerResumed = false;
        autoAdvanceHandler.removeCallbacks(autoAdvanceTask);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_EPISODE_INDEX, episodeIndex);
        outState.putInt(STATE_PAGE_INDEX, currentPage);
        outState.putBoolean(STATE_AUTO_ADVANCE_PAUSED, autoAdvancePaused);
        super.onSaveInstanceState(outState);
    }

    private File extractEpisode(String storageUri, int episode) throws Exception {
        File viewerRoot = new File(getCacheDir(), "viewer");
        WebtoonStorage.deleteRecursively(viewerRoot);
        File dir = new File(viewerRoot, titleId + "/" + String.format(Locale.US, "%03d", episode));
        if (!dir.mkdirs()) throw new IllegalStateException("임시 뷰어 폴더 생성 실패");
        try (InputStream source = new WebtoonStorage(this, storageUri).openEpisodeZip(titleId, episode);
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String safeName = new File(entry.getName()).getName();
                if (!safeName.matches("(?i).+\\.(jpg|jpeg|png|webp)$")) continue;
                File output = new File(dir, safeName);
                try (FileOutputStream out = new FileOutputStream(output)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        out.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
        return dir;
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override protected void onDestroy() {
        loadGeneration++;
        autoAdvanceHandler.removeCallbacks(autoAdvanceTask);
        executor.shutdownNow();
        WebtoonStorage.deleteRecursively(new File(getCacheDir(), "viewer"));
        webView.destroy();
        super.onDestroy();
    }
}
