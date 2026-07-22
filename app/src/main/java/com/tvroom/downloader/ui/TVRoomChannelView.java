package com.tvroom.downloader.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
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
    private String homeUrl;
    private String allowedHost;
    private final WebView webView;
    private final ProgressBar progress;
    private final TextView errorView;
    private final Button downloadButton;
    private final Button testButton;
    private final Button moveButton;
    private final Button stopButton;
    private final CaptureState capture = new CaptureState();
    private boolean receiverRegistered;
    private boolean webViewUsable = true;

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

        errorView = new TextView(activity);
        errorView.setBackgroundColor(ContextCompat.getColor(activity, R.color.background));
        errorView.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        errorView.setTextSize(16);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(28), dp(28), dp(28), dp(28));
        errorView.setVisibility(GONE);
        addView(errorView, new LayoutParams(-1, -1));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        LayoutParams progressParams = new LayoutParams(-1, dp(3));
        progressParams.gravity = Gravity.TOP;
        addView(progress, progressParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        stopButton = button("중단", Color.rgb(198, 40, 40), 76);
        moveButton = button("이동", Color.rgb(69, 90, 100), 64);
        downloadButton = button("먼저 영상 재생", ContextCompat.getColor(activity, R.color.green), 110);
        testButton = button("30초 테스트", Color.rgb(239, 108, 0), 108);
        actions.addView(stopButton);
        actions.addView(moveButton);
        actions.addView(downloadButton);
        actions.addView(testButton);
        for (int i = 1; i < actions.getChildCount(); i++) {
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) actions.getChildAt(i).getLayoutParams();
            params.setMarginStart(dp(6));
            actions.getChildAt(i).setLayoutParams(params);
        }
        actions.setElevation(dp(8));
        LayoutParams actionParams = new LayoutParams(-2, dp(52));
        actionParams.gravity = Gravity.END | Gravity.BOTTOM;
        actionParams.setMargins(dp(16), dp(16), dp(16), dp(20));
        addView(actions, actionParams);

        configureWebView();
        moveButton.setOnClickListener(v -> showNavigation());
        downloadButton.setOnClickListener(v -> confirmDownload());
        testButton.setOnClickListener(v -> confirmTestDownload());
        stopButton.setOnClickListener(v -> VideoDownloadService.stop(activity));
        webView.loadUrl(homeUrl);
        updateButtons();
    }

    private Button button(String text, int color, int width) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(width), dp(52)));
        return button;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        installBridgeAndHook();

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                if (!webViewUsable) return;
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? GONE : VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String host = request.getUrl().getHost();
                if (isSameHostOrSubdomain(host, allowedHost)) return false;
                if (isNumberedTvroomHost(host) && isNumberedTvroomHost(allowedHost)) {
                    homeUrl = rootUrl(request.getUrl());
                    allowedHost = host;
                    AppSettings.setSiteUrl(activity, homeUrl);
                    Toast.makeText(activity, "변경된 티비룸 주소로 자동 연결합니다.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                showSiteError("사이트 주소가 변경되었거나 허용되지 않은 주소로 이동했습니다.\n\n"
                        + "설정 탭에서 최신 티비룸 주소를 확인해 주세요.");
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (!webViewUsable) return;
                hideSiteError();
                capture.reset(url);
                updateButtons();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                   WebResourceError error) {
                if (request.isForMainFrame()) {
                    showSiteError("티비룸 사이트에 연결할 수 없습니다.\n\n"
                            + "인터넷 연결 또는 설정 탭의 사이트 주소를 확인해 주세요.");
                }
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                       WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    showSiteError("티비룸 사이트에서 오류를 응답했습니다. (HTTP "
                            + response.getStatusCode() + ")\n\n설정 탭에서 사이트 주소를 확인해 주세요.");
                }
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                                      SslError error) {
                handler.cancel();
                showSiteError("사이트의 보안 인증서를 확인할 수 없어 연결을 중단했습니다.\n\n"
                        + "설정 탭에서 사이트 주소를 확인해 주세요.");
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                webViewUsable = false;
                removeView(view);
                view.destroy();
                showSiteError("WebView를 불러오는 중 오류가 발생했습니다.\n\n"
                        + "티비룸 탭을 다시 누르면 새로 연결합니다.");
                updateButtons();
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                capture.rememberRequest(request.getUrl().toString(), request.getRequestHeaders());
                post(TVRoomChannelView.this::updateButtons);
                return super.shouldInterceptRequest(view, request);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!webViewUsable) return;
                syncSession();
                view.evaluateJavascript(captureScript(), null);
                updateButtons();
            }
        });
    }

    private void installBridgeAndHook() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "TVRoomBridge", Collections.singleton("*"),
                    new WebViewCompat.WebMessageListener() {
                        @Override public void onPostMessage(WebView view, WebMessageCompat message,
                                                            Uri sourceOrigin, boolean isMainFrame,
                                                            JavaScriptReplyProxy replyProxy) {
                            String data = message.getData();
                            if (data != null && data.length() < 16384) {
                                capture.acceptMessage(data);
                                updateButtons();
                            }
                        }
                    });
        } else {
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface public void postMessage(String data) {
                    if (data != null && data.length() < 16384) {
                        post(() -> {
                            capture.acceptMessage(data);
                            updateButtons();
                        });
                    }
                }
            }, "TVRoomBridge");
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                    webView, captureScript(), Collections.singleton("*"));
        }
    }

    private String captureScript() {
        try (InputStream in = activity.getAssets().open("tvroom_capture.js");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }

    private void syncSession() {
        if (!webViewUsable) return;
        String url = webView.getUrl();
        if (url == null) return;
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
        boolean videoPage = webViewUsable && isVideoPage(webView.getUrl());
        boolean running = VideoDownloadService.isRunning();
        boolean ready = capture.ready();
        stopButton.setVisibility(running ? VISIBLE : GONE);
        downloadButton.setVisibility(videoPage ? VISIBLE : GONE);
        testButton.setVisibility(videoPage && !running ? VISIBLE : GONE);
        downloadButton.setEnabled(videoPage && ready);
        testButton.setEnabled(videoPage && ready);
        downloadButton.setText(!ready ? "먼저 영상 재생" : running ? "대기열 추가" : "다운로드");
    }

    private boolean isVideoPage(String url) {
        if (url == null) return false;
        String path = Uri.parse(url).getPath();
        return path != null && path.toLowerCase(java.util.Locale.US).contains("/video/");
    }

    private void confirmDownload() {
        syncSession();
        if (!capture.ready()) {
            Toast.makeText(activity, "먼저 영상을 재생해 주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        CaptureState.Snapshot snapshot = capture.snapshot();
        boolean queueing = VideoDownloadService.isRunning();
        new AlertDialog.Builder(activity)
                .setTitle(queueing ? "다운로드 대기열 추가" : "영상 다운로드")
                .setMessage(snapshot.title +
                        (queueing ? " 영상을 대기열에 추가할까요?" : " 영상을 다운로드할까요?"))
                .setNegativeButton("취소", null)
                .setPositiveButton(queueing ? "추가" : "시작", (dialog, which) -> {
                    boolean accepted = VideoDownloadService.start(activity, snapshot);
                    if (!accepted) {
                        Toast.makeText(activity, "이미 다운로드 중이거나 대기열에 있는 영상입니다.",
                                Toast.LENGTH_LONG).show();
                    } else if (queueing) {
                        Toast.makeText(activity, "다운로드 대기열에 추가했습니다.",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        activity.showDownloads();
                    }
                    updateButtons();
                })
                .show();
    }

    private void confirmTestDownload() {
        syncSession();
        if (!capture.ready()) {
            Toast.makeText(activity, "먼저 영상을 재생해 주세요.", Toast.LENGTH_LONG).show();
            return;
        }
        CaptureState.Snapshot snapshot = capture.snapshot();
        new AlertDialog.Builder(activity)
                .setTitle("30초 재생 테스트")
                .setMessage(snapshot.title + " 영상의 처음 약 30초만 다운로드할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("테스트", (dialog, which) -> {
                    boolean accepted = VideoDownloadService.start(activity, snapshot, true);
                    if (!accepted) {
                        Toast.makeText(activity, "이미 이 영상의 테스트를 다운로드 중입니다.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        activity.showDownloads();
                    }
                    updateButtons();
                })
                .show();
    }

    private void showNavigation() {
        if (!webViewUsable) {
            activity.applySiteAddress();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("페이지 이동")
                .setItems(new String[]{"메인으로 가기", "뒤로 가기", "앞으로 가기", "새로고침"},
                        (dialog, which) -> {
                            if (which == 0) webView.loadUrl(homeUrl);
                            else if (which == 1 && webView.canGoBack()) webView.goBack();
                            else if (which == 2 && webView.canGoForward()) webView.goForward();
                            else if (which == 3) webView.reload();
                        })
                .setNegativeButton("닫기", null)
                .show();
    }

    public void goHome() {
        if (!webViewUsable) {
            activity.applySiteAddress();
            return;
        }
        if (!homeUrl.equals(webView.getUrl())) webView.loadUrl(homeUrl);
    }

    public boolean canGoBack() { return webViewUsable && webView.canGoBack(); }
    public void goBack() { if (webViewUsable) webView.goBack(); }

    public void destroy() {
        if (!webViewUsable) return;
        webViewUsable = false;
        webView.stopLoading();
        webView.destroy();
    }

    private boolean isSameHostOrSubdomain(String host, String expected) {
        if (host == null || expected == null) return false;
        return host.equalsIgnoreCase(expected)
                || host.toLowerCase(java.util.Locale.US)
                .endsWith("." + expected.toLowerCase(java.util.Locale.US));
    }

    private boolean isNumberedTvroomHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(java.util.Locale.US);
        if (normalized.startsWith("www.")) normalized = normalized.substring(4);
        return normalized.matches("tvroom\\d+\\.org");
    }

    private String rootUrl(Uri uri) {
        String host = uri.getHost();
        return host == null ? homeUrl : "https://" + host + "/";
    }

    private void showSiteError(String message) {
        progress.setVisibility(GONE);
        errorView.setText(message);
        errorView.setVisibility(VISIBLE);
    }

    private void hideSiteError() {
        errorView.setVisibility(GONE);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(activity, receiver,
                    new IntentFilter(VideoDownloadService.ACTION_PROGRESS),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
