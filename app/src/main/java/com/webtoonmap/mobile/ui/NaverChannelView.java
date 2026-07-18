package com.webtoonmap.mobile.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.webtoonmap.mobile.MainActivity;
import com.webtoonmap.mobile.R;
import com.webtoonmap.mobile.data.LibraryDatabase;
import com.webtoonmap.mobile.download.SeriesDownloadService;
import com.webtoonmap.mobile.download.SourceJobStore;
import com.webtoonmap.mobile.hitomi.HitomiApi;
import com.webtoonmap.mobile.joatoon.JoatoonApi;
import com.webtoonmap.mobile.storage.SourceSettings;
import com.webtoonmap.mobile.storage.ViewedSeriesHistory;
import com.webtoonmap.mobile.wolfdot.WolfdotApi;

import java.text.DateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NaverChannelView extends FrameLayout {
    private final MainActivity activity;
    private final String source;
    private final String homeUrl;
    private final String allowedHost;
    private final WebView webView;
    private final ProgressBar progress;
    private final Button downloadButton;
    private final Button moveButton;
    private final Button stopButton;
    private boolean clearHistoryOnNextPage;
    private boolean receiverRegistered;
    private boolean stopping;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getBooleanExtra(SeriesDownloadService.EXTRA_DONE, false)) stopping = false;
            if (intent.getBooleanExtra(SeriesDownloadService.EXTRA_ERROR, false)) {
                String message = intent.getStringExtra(SeriesDownloadService.EXTRA_MESSAGE);
                Toast.makeText(activity, message == null ? "다운로드에 실패했습니다." : message,
                        Toast.LENGTH_LONG).show();
            }
            updateActionButtons();
        }
    };

    public NaverChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        source = SourceSettings.getSource(activity);
        homeUrl = SourceSettings.homeUrl(activity);
        allowedHost = Uri.parse(homeUrl).getHost();
        setBackgroundColor(Color.WHITE);

        webView = new WebView(activity);
        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        LayoutParams progressParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(3));
        progressParams.gravity = Gravity.TOP;
        addView(progress, progressParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        stopButton = new Button(activity);
        stopButton.setText("중단");
        stopButton.setTextColor(Color.WHITE);
        stopButton.setTextSize(14);
        stopButton.setAllCaps(false);
        stopButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(198, 40, 40)));
        stopButton.setVisibility(View.GONE);
        actions.addView(stopButton, new LinearLayout.LayoutParams(dp(76), dp(52)));

        moveButton = new Button(activity);
        moveButton.setText("이동");
        moveButton.setTextColor(Color.WHITE);
        moveButton.setTextSize(14);
        moveButton.setAllCaps(false);
        moveButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(69, 90, 100)));
        LinearLayout.LayoutParams moveParams = new LinearLayout.LayoutParams(dp(76), dp(52));
        moveParams.setMarginStart(dp(6));
        actions.addView(moveButton, moveParams);

        downloadButton = new Button(activity);
        downloadButton.setText("전체 다운로드");
        downloadButton.setTextColor(Color.WHITE);
        downloadButton.setTextSize(14);
        downloadButton.setAllCaps(false);
        downloadButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.green, activity.getTheme())));
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(dp(132), dp(52));
        downloadParams.setMarginStart(dp(6));
        actions.addView(downloadButton, downloadParams);
        actions.setElevation(dp(8));
        LayoutParams actionParams = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(52));
        actionParams.gravity = Gravity.END | Gravity.BOTTOM;
        actionParams.setMargins(dp(16), dp(16), dp(16), dp(20));
        addView(actions, actionParams);

        configureWebView();
        downloadButton.setOnClickListener(v -> confirmDownload());
        moveButton.setOnClickListener(v -> showNavigationMenu());
        stopButton.setOnClickListener(v -> stopAllDownloads());
        webView.loadUrl(homeUrl);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(false);
        if (!SourceSettings.SOURCE_NAVER.equals(source)) {
            settings.setUserAgentString(JoatoonApi.USER_AGENT);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if (host == null) return true;
                if (SourceSettings.SOURCE_NAVER.equals(source) &&
                        (host.equals("comic.naver.com") || host.endsWith(".naver.com"))) {
                    return false;
                }
                if (!SourceSettings.SOURCE_NAVER.equals(source) && allowedHost != null &&
                        (host.equalsIgnoreCase(allowedHost) || host.endsWith("." + allowedHost))) {
                    return false;
                }
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (clearHistoryOnNextPage) {
                    view.clearHistory();
                    clearHistoryOnNextPage = false;
                }
                updateActionButtons();
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                updateActionButtons();
            }
        });
    }

    private void updateActionButtons() {
        String titleId = seriesKeyFrom(webView.getUrl());
        boolean running = SeriesDownloadService.isRunning();
        if (!running) stopping = false;
        stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
        stopButton.setEnabled(running && !stopping);
        stopButton.setText(stopping ? "중단 중…" : "중단");
        downloadButton.setVisibility(titleId == null ? View.GONE : View.VISIBLE);
        if (titleId == null) return;
        if (SeriesDownloadService.isDownloading(titleId)) {
            downloadButton.setText("다운로드 중");
            downloadButton.setEnabled(false);
        } else if (SeriesDownloadService.isQueued(activity, titleId)) {
            downloadButton.setText(running ? "대기열 등록됨" : "다운로드 재개");
            downloadButton.setEnabled(!running);
        } else {
            downloadButton.setText(running ? "대기열 추가" : "전체 다운로드");
            downloadButton.setEnabled(true);
        }
    }

    private void confirmDownload() {
        String titleId = seriesKeyFrom(webView.getUrl());
        if (titleId == null) {
            Toast.makeText(activity, "먼저 작품 페이지를 열어 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (SeriesDownloadService.isDownloading(titleId)) {
            Toast.makeText(activity, "이 작품을 다운로드하고 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean queued = SeriesDownloadService.isQueued(activity, titleId);
        if (queued && SeriesDownloadService.isRunning()) {
            Toast.makeText(activity, "이미 대기열에 등록된 작품입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean queueMode = SeriesDownloadService.isRunning();
        ViewedSeriesHistory.Entry history = ViewedSeriesHistory.get(activity, titleId);
        if (history != null && LibraryDatabase.get(activity).getSeries(titleId) == null) {
            String historyTitle = history.title.isEmpty()
                    ? "이 작품은" : "‘" + history.title + "’ 작품은";
            String viewedDate = history.viewedAt <= 0 ? "" :
                    " (" + DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(new Date(history.viewedAt)) + ")";
            String queueNotice = queueMode
                    ? "\n\n현재 다른 작품을 받고 있어 대기열에 추가됩니다."
                    : "";
            new AlertDialog.Builder(activity)
                    .setTitle("이전에 본 작품")
                    .setMessage(historyTitle + " 이전에 다운로드하여 본 기록이 있습니다" +
                            viewedDate + ". 삭제한 작품일 수 있습니다. 그래도 다시 받을까요?" +
                            queueNotice)
                    .setNegativeButton("취소", null)
                    .setPositiveButton(queueMode ? "대기열" : "다시 받기",
                            (dialog, which) -> startDownload(titleId, queued, queueMode))
                    .show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(queueMode ? "대기열 추가" : (queued ? "다운로드 재개" : "전체 다운로드"))
                .setMessage(queueMode ?
                        "현재 작품을 다운로드 대기열에 추가합니다. 앞 작품의 다운로드가 끝나면 자동으로 시작합니다." :
                        (!SourceSettings.SOURCE_NAVER.equals(source) ?
                                "현재 작품의 회차를 모두 저장합니다. 이미 받은 회차는 건너뜁니다." :
                                "현재 이용 가능한 공개 회차를 모두 저장합니다. 이미 받은 회차는 건너뜁니다."))
                .setNegativeButton("취소", null)
                .setPositiveButton(queueMode ? "대기열" : "시작",
                        (dialog, which) -> startDownload(titleId, queued, queueMode))
                .show();
    }

    private void startDownload(String titleId, boolean queued, boolean queueMode) {
        registerSourceJob(titleId, webView.getUrl());
        SeriesDownloadService.enqueue(activity, titleId);
        Toast.makeText(activity, queueMode ? "대기열에 추가했습니다." :
                        (queued ? "다운로드를 다시 시작했습니다." : "다운로드를 시작했습니다."),
                Toast.LENGTH_SHORT).show();
        updateActionButtons();
    }

    private void showNavigationMenu() {
        String[] options = {"메인으로 가기", "뒤로 가기", "앞으로 가기"};
        new AlertDialog.Builder(activity)
                .setTitle("페이지 이동")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        webView.stopLoading();
                        webView.loadUrl(homeUrl);
                    } else if (which == 1) {
                        if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            Toast.makeText(activity, "뒤로 이동할 페이지가 없습니다.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 2) {
                        if (webView.canGoForward()) {
                            webView.goForward();
                        } else {
                            Toast.makeText(activity, "앞으로 이동할 페이지가 없습니다.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void stopAllDownloads() {
        if (!SeriesDownloadService.isRunning()) return;
        stopping = true;
        stopButton.setEnabled(false);
        stopButton.setText("중단 중…");
        SeriesDownloadService.stopAll(activity);
        Toast.makeText(activity, "현재 다운로드와 대기열 전체를 중단합니다.", Toast.LENGTH_SHORT).show();
    }

    private String seriesKeyFrom(String url) {
        if (url == null) return null;
        if (SourceSettings.SOURCE_JOATOON.equals(source)) {
            try {
                Matcher matcher = Pattern.compile("/toon/w/(\\d+)(?:/|$)", Pattern.CASE_INSENSITIVE)
                        .matcher(Uri.parse(url).getPath());
                return matcher.find() ? JoatoonApi.seriesKey(matcher.group(1)) : null;
            } catch (Exception ignored) {
                return null;
            }
        }
        if (SourceSettings.SOURCE_MANHWABANG.equals(source)) {
            try {
                Uri uri = Uri.parse(url);
                Matcher matcher = Pattern.compile("^/(webtoon|manhua)/eps_list/?$",
                        Pattern.CASE_INSENSITIVE).matcher(uri.getPath());
                String id = uri.getQueryParameter("id");
                if (!matcher.find() || id == null || !id.matches("\\d+")) return null;
                return SourceJobStore.keyFor(SourceSettings.SOURCE_MANHWABANG,
                        matcher.group(1).toLowerCase() + ":" + id);
            } catch (Exception ignored) {
                return null;
            }
        }
        if (SourceSettings.SOURCE_BLACKTOON.equals(source)) {
            try {
                String path = Uri.parse(url).getPath();
                Matcher matcher = Pattern.compile("^/webtoon/(\\d+)\\.html$",
                        Pattern.CASE_INSENSITIVE).matcher(path == null ? "" : path);
                return matcher.find()
                        ? SourceJobStore.keyFor(SourceSettings.SOURCE_BLACKTOON, matcher.group(1))
                        : null;
            } catch (Exception ignored) {
                return null;
            }
        }
        if (SourceSettings.SOURCE_WOLFDOT.equals(source)) {
            try {
                Uri uri = Uri.parse(url);
                String path = uri.getPath();
                String id = uri.getQueryParameter("toon");
                Matcher matcher = Pattern.compile("^/(list|cl)/?$",
                        Pattern.CASE_INSENSITIVE).matcher(path == null ? "" : path);
                if (!matcher.find() || id == null || !id.matches("\\d+")) return null;
                String identity = matcher.group(1).equalsIgnoreCase("cl")
                        ? WolfdotApi.KIND_COMIC + ":" + id : id;
                return SourceJobStore.keyFor(SourceSettings.SOURCE_WOLFDOT, identity);
            } catch (Exception ignored) {
                return null;
            }
        }
        if (SourceSettings.SOURCE_HITOMI.equals(source)) {
            try {
                String id = hitomiGalleryId(Uri.parse(url));
                return id == null ? null : HitomiApi.seriesKey(id);
            } catch (Exception ignored) {
                return null;
            }
        }
        if (SourceSettings.SOURCE_ILILTOON.equals(source)) {
            try {
                Uri uri = Uri.parse(url);
                if (uri.getQueryParameter("wr_id") != null) return null;
                String table = uri.getQueryParameter("bo_table");
                if (table != null && !table.equalsIgnoreCase("toons") &&
                        !table.equalsIgnoreCase("cartoonson")) return null;
                String id = uri.getQueryParameter("is");
                if (id == null || id.trim().isEmpty()) id = uri.getQueryParameter("stx");
                if (id == null || id.trim().isEmpty()) return null;
                return SourceJobStore.keyFor(SourceSettings.SOURCE_ILILTOON,
                        (table == null ? "toons" : table.toLowerCase()) + ":" + id.trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        try {
            String value = Uri.parse(url).getQueryParameter("titleId");
            return value != null && value.matches("\\d+") ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void registerSourceJob(String key, String pageUrl) {
        if (key == null || pageUrl == null) return;
        try {
            Uri uri = Uri.parse(pageUrl);
            if (SourceSettings.SOURCE_MANHWABANG.equals(source)) {
                String path = uri.getPath();
                Matcher matcher = Pattern.compile("^/(webtoon|manhua)/eps_list/?$",
                        Pattern.CASE_INSENSITIVE).matcher(path == null ? "" : path);
                String id = uri.getQueryParameter("id");
                if (matcher.find() && id != null) {
                    SourceJobStore.register(activity, key, source, pageUrl, id,
                            matcher.group(1).toLowerCase());
                }
            } else if (SourceSettings.SOURCE_ILILTOON.equals(source)) {
                String id = uri.getQueryParameter("is");
                if (id == null || id.isEmpty()) id = uri.getQueryParameter("stx");
                SourceJobStore.register(activity, key, source, pageUrl, id,
                        uri.getQueryParameter("bo_table"));
            } else if (SourceSettings.SOURCE_BLACKTOON.equals(source)) {
                String path = uri.getPath();
                Matcher matcher = Pattern.compile("^/webtoon/(\\d+)\\.html$",
                        Pattern.CASE_INSENSITIVE).matcher(path == null ? "" : path);
                if (matcher.find()) {
                    SourceJobStore.register(activity, key, source, pageUrl,
                            matcher.group(1), null);
                }
            } else if (SourceSettings.SOURCE_WOLFDOT.equals(source)) {
                String path = uri.getPath();
                String id = uri.getQueryParameter("toon");
                Matcher matcher = Pattern.compile("^/(list|cl)/?$",
                        Pattern.CASE_INSENSITIVE).matcher(path == null ? "" : path);
                if (matcher.find() && id != null && id.matches("\\d+")) {
                    String kind = matcher.group(1).equalsIgnoreCase("cl")
                            ? WolfdotApi.KIND_COMIC : WolfdotApi.KIND_WEBTOON;
                    SourceJobStore.register(activity, key, source, pageUrl, id, kind);
                }
            } else if (SourceSettings.SOURCE_HITOMI.equals(source)) {
                String id = hitomiGalleryId(uri);
                if (id != null) {
                    SourceJobStore.register(activity, key, source, pageUrl, id, "gallery");
                }
            }
        } catch (Exception ignored) { }
    }

    private String hitomiGalleryId(Uri uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        if (path == null) return null;
        Matcher reader = Pattern.compile("^/reader/(\\d+)\\.html$",
                Pattern.CASE_INSENSITIVE).matcher(path);
        if (reader.find()) return reader.group(1);
        Matcher gallery = Pattern.compile(
                "^/[^/]+/.+-(\\d+)\\.html$",
                Pattern.CASE_INSENSITIVE).matcher(path);
        return gallery.find() ? gallery.group(1) : null;
    }

    public boolean canGoBack() { return webView.canGoBack(); }
    public void goBack() { webView.goBack(); }
    public void goHome() {
        clearHistoryOnNextPage = true;
        webView.stopLoading();
        webView.loadUrl(homeUrl);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(activity, receiver,
                    new IntentFilter(SeriesDownloadService.ACTION_PROGRESS),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        updateActionButtons();
    }
    @Override protected void onDetachedFromWindow() {
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }
    public void destroyWebView() { webView.stopLoading(); webView.destroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}






