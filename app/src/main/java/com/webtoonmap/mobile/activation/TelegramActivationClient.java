package com.webtoonmap.mobile.activation;

import android.content.Context;

import com.webtoonmap.mobile.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TelegramActivationClient {
    private static final Pattern COMMAND = Pattern.compile(
            "^/웹툰모바일\\s+(.+?)\\s+[\\(（](.+?)[\\)）]\\s*$");

    private static final String BOT_TOKEN = BuildConfig.TELEGRAM_BOT_TOKEN.trim();
    private static final String ADMIN_CHAT_ID = BuildConfig.TELEGRAM_ADMIN_CHAT_ID.trim();

    public interface Listener {
        void onReady();
        void onPendingCode();
        void onConnectionMessage(String message);
    }

    private final Context context;
    private final String userName;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String apiBase;
    private volatile boolean running;
    private volatile HttpURLConnection activeConnection;

    public TelegramActivationClient(Context context, String userName, Listener listener) {
        this.context = context.getApplicationContext();
        this.userName = ActivationStore.normalizeName(userName);
        this.listener = listener;
        this.apiBase = "https://api.telegram.org/bot" + BOT_TOKEN + "/";
    }

    public static boolean isConfigured() {
        return !BOT_TOKEN.isEmpty() && !ADMIN_CHAT_ID.isEmpty();
    }

    public void start() {
        if (running || userName.isEmpty() || !isConfigured()) return;
        running = true;
        executor.execute(this::pollLoop);
    }

    public void stop() {
        running = false;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        executor.shutdownNow();
    }

    public void notifyActivated(long replyToMessageId) {
        executor.execute(() -> sendMessage(
                "✅ [웹툰모바일 · " + userName + "] 영구 활성화되었습니다.",
                replyToMessageId));
    }

    private void pollLoop() {
        try {
            long lastUpdateId = ActivationStore.getTelegramUpdateId(context);
            listener.onReady();
            while (running && !ActivationStore.isActivated(context)) {
                try {
                    JSONObject response = getUpdates(lastUpdateId + 1L, 20);
                    JSONArray updates = response.optJSONArray("result");
                    if (updates == null) continue;
                    for (int i = 0; i < updates.length(); i++) {
                        JSONObject update = updates.optJSONObject(i);
                        if (update == null) continue;
                        long updateId = update.optLong("update_id", 0L);
                        if (updateId > lastUpdateId) {
                            lastUpdateId = updateId;
                            ActivationStore.setTelegramUpdateId(context, lastUpdateId);
                        }
                        processUpdate(update);
                    }
                } catch (Exception e) {
                    if (!running) break;
                    String detail = e.getMessage() == null ? "" : e.getMessage();
                    if (detail.contains("409")) {
                        listener.onConnectionMessage(
                                "같은 텔레그램 봇을 사용하는 다른 프로그램을 종료해 주세요.");
                    } else {
                        listener.onConnectionMessage("텔레그램 연결을 다시 시도하고 있습니다…");
                    }
                    try { Thread.sleep(3000L); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (running) listener.onConnectionMessage("텔레그램 연결을 다시 시도합니다…");
        }
    }

    private void processUpdate(JSONObject update) {
        JSONObject message = update.optJSONObject("message");
        if (message == null) return;
        JSONObject chat = message.optJSONObject("chat");
        if (chat == null || !ADMIN_CHAT_ID.equals(
                String.valueOf(chat.optLong("id")))) return;
        String text = message.optString("text", "").trim();
        if (!text.startsWith("/웹툰모바일")) return;

        long messageId = message.optLong("message_id", 0L);
        Matcher matcher = COMMAND.matcher(text);
        if (!matcher.matches()) {
            sendMessage("형식이 올바르지 않습니다. 예: /웹툰모바일 " + userName + " (1234)", messageId);
            return;
        }
        String target = ActivationStore.normalizeName(matcher.group(1));
        String code = matcher.group(2).trim();
        if (code.isEmpty()) {
            sendMessage("활성화 코드가 비어 있습니다. 예: /웹툰모바일 " + userName + " (1234)", messageId);
            return;
        }
        if (!target.equals(userName)) {
            sendMessage("이 기기의 등록 사용자명은 「" + userName + "」입니다. (요청: 「" + target + "」)\n"
                    + "이 기기를 활성화하려면 /웹툰모바일 " + userName + " (" + code + ") 를 보내세요.", messageId);
            return;
        }

        ActivationStore.setPendingCode(context, code, messageId);
        sendMessage("🔒 [웹툰모바일 · " + userName + "] 코드 입력 대기 중…", messageId);
        listener.onPendingCode();
    }

    private JSONObject getUpdates(long offset, int timeoutSeconds) throws Exception {
        URL url = new URL(apiBase + "getUpdates?offset=" + offset + "&timeout=" + timeoutSeconds);
        HttpURLConnection connection = open(url, "GET");
        activeConnection = connection;
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("Telegram HTTP " + status);
            JSONObject result = new JSONObject(readText(connection.getInputStream()));
            if (!result.optBoolean("ok", false)) throw new IllegalStateException("Telegram API 오류");
            return result;
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    private void sendMessage(String text, long replyToMessageId) {
        HttpURLConnection connection = null;
        try {
            connection = open(new URL(apiBase + "sendMessage"), "POST");
            JSONObject body = new JSONObject()
                    .put("chat_id", ADMIN_CHAT_ID)
                    .put("text", text);
            if (replyToMessageId > 0L) body.put("reply_to_message_id", replyToMessageId);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private HttpURLConnection open(URL url, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(35000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "WebtoonMapMobile/1.1");
        return connection;
    }

    private static String readText(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
