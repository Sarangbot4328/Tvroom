package com.tvroom.downloader.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.tvroom.downloader.MainActivity;
import com.tvroom.downloader.R;
import com.tvroom.downloader.download.VideoDownloadService;
import com.tvroom.downloader.storage.AppSettings;
import com.tvroom.downloader.web.CaptureState;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;

public final class TVRoomChannelView extends FrameLayout {
    private final MainActivity activity;
    private final String homeUrl;
    private final String allowedHost;
    private final WebView webView;
    private final ProgressBar progress;
    private final Button downloadButton, moveButton, stopButton;
    private final CaptureState capture = new CaptureState();
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateButtons(); }
    };

    public TVRoomChannelView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        homeUrl = AppSettings.getSiteUrl(activity);
        allowedHost = Uri.parse(homeUrl).getHost();
        webView = new WebView(activity);
        addView(webView, new LayoutParams(-1, -1));
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        LayoutParams pp = new LayoutParams(-1, dp(3)); pp.gravity = Gravity.TOP; addView(progress, pp);

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        stopButton = button("중단", Color.rgb(198, 40, 40), 76);
        moveButton = button("이동", Color.rgb(69, 90, 100), 76);
        downloadButton = button("다운로드", ContextCompat.getColor(activity, R.color.green), 120);
        actions.addView(stopButton); actions.addView(moveButton); actions.addView(downloadButton);
        for (int i = 1; i < actions.getChildCount(); i++) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) actions.getChildAt(i).getLayoutParams();
            p.setMarginStart(dp(6)); actions.getChildAt(i).setLayoutParams(p);
        }
        actions.setElevation(dp(8));
        LayoutParams ap = new LayoutParams(-2, dp(52)); ap.gravity = Gravity.END | Gravity.BOTTOM;
        ap.setMargins(dp(16), dp(16), dp(16), dp(20)); addView(actions, ap);
        configureWebView();
        moveButton.setOnClickListener(v -> showNavigation());
        downloadButton.setOnClickListener(v -> confirmDownload());
        stopButton.setOnClickListener(v -> VideoDownloadService.stop(activity));
        webView.loadUrl(homeUrl);
        updateButtons();
    }

    private Button button(String text, int color, int width) {
        Button button = new Button(activity);
        button.setText(text); button.setTextColor(Color.WHITE); button.setTextSize(14);
        button.setAllCaps(false); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(width), dp(52)));
        return button;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true); settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        installBridgeAndHook();
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value); progress.setVisibility(value >= 100 ? GONE : VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String host = request.getUrl().getHost();
                return host == null || !(host.equalsIgnoreCase(allowedHost) || host.endsWith("." + allowedHost));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                capture.reset(url); updateButtons();
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                capture.rememberRequest(request.getUrl().toString(), request.getRequestHeaders());
                post(TVRoomChannelView.this::updateButtons);
                return super.shouldInterceptRequest(view, request);
            }
            @Override public void onPageFinished(WebView view, String url) {
                syncSession(); view.evaluateJavascript(captureScript(), null); updateButtons();
            }
        });
    }

    private void installBridgeAndHook() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "TVRoomBridge", Collections.singleton("*"),
                    new WebViewCompat.WebMessageListener() {
                        @Override public void onPostMessage(WebView view, WebMessageCompat message, Uri sourceOrigin,
                                                            boolean isMainFrame, JavaScriptReplyProxy replyProxy) {
                            String data = message.getData();
                            if (data != null && data.length() < 16384) { capture.acceptMessage(data); updateButtons(); }
                        }
                    });
        } else {
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface public void postMessage(String data) {
                    if (data != null && data.length() < 16384) post(() -> { capture.acceptMessage(data); updateButtons(); });
                }
            }, "TVRoomBridge");
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, captureScript(), Collections.singleton("*"));
        }
    }

    private String captureScript() {
        try (InputStream in = activity.getAssets().open("tvroom_capture.js"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception ignored) { return ""; }
    }

    private void syncSession() {
        String url = webView.getUrl();
        StringBuilder cookies = new StringBuilder();
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        String pageCookies = CookieManager.getInstance().getCookie(url);
        if (pageCookies != null) Collections.addAll(unique, pageCookies.split(";\\s*"));
        for (String known : capture.knownUrls()) {
            String value = CookieManager.getInstance().getCookie(known);
            if (value != null) Collections.addAll(unique, value.split(";\\s*"));
        }
        for (String value : unique) {
            if (value.isEmpty()) continue;
            if (cookies.length() > 0) cookies.append("; ");
            cookies.append(value);
        }
        capture.setSession(url, cookies.toString(), webView.getSettings().getUserAgentString());
    }

    private void updateButtons() {
        boolean videoPage = isVideoPage(webView.getUrl());
        boolean running = VideoDownloadService.isRunning();
        stopButton.setVisibility(running ? VISIBLE : GONE);
        downloadButton.setVisibility(videoPage ? VISIBLE : GONE);
        downloadButton.setEnabled(videoPage && capture.ready() && !running);
        downloadButton.setText(running ? "다운로드 중" : capture.ready() ? "다운로드" : "영상 분석 중…");
    }

    private boolean isVideoPage(String url) {
        if (url == null) return false;
        String path = Uri.parse(url).getPath();
        return path != null && path.toLowerCase().contains("/video/");
    }

    private void confirmDownload() {
        syncSession();
        if (!capture.ready()) {
            Toast.makeText(activity, "영상을 재생한 뒤 잠시 기다려 주세요.", Toast.LENGTH_LONG).show(); return;
        }
        CaptureState.Snapshot snapshot = capture.snapshot();
        new AlertDialog.Builder(activity).setTitle("영상 다운로드")
                .setMessage("‘" + snapshot.title + "’ 영상을 다운로드 탭에 저장합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("시작", (d, w) -> {
                    VideoDownloadService.start(activity, snapshot);
                    activity.showDownloads(); updateButtons();
                }).show();
    }

    private void showNavigation() {
        new AlertDialog.Builder(activity).setTitle("페이지 이동")
                .setItems(new String[]{"메인으로 가기", "뒤로 가기", "앞으로 가기", "새로고침"}, (d, which) -> {
                    if (which == 0) webView.loadUrl(homeUrl);
                    else if (which == 1 && webView.canGoBack()) webView.goBack();
                    else if (which == 2 && webView.canGoForward()) webView.goForward();
                    else if (which == 3) webView.reload();
                }).setNegativeButton("닫기", null).show();
    }

    public void goHome() { if (!homeUrl.equals(webView.getUrl())) webView.loadUrl(homeUrl); }
    public boolean canGoBack() { return webView.canGoBack(); }
    public void goBack() { webView.goBack(); }
    public void destroy() { webView.stopLoading(); webView.destroy(); }

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
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
