package com.webtoonmap.mobile.activation;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;

public final class ActivationStore {
    private static final String PREFS = "mobile_activation";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_ACTIVATED = "activated";
    private static final String KEY_PENDING_HASH = "pending_hash";
    private static final String KEY_PENDING_MESSAGE_ID = "pending_message_id";
    private static final String KEY_UPDATE_ID = "telegram_update_id";

    private ActivationStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getUserName(Context context) {
        return normalizeName(prefs(context).getString(KEY_USER_NAME, ""));
    }

    public static boolean needsUserName(Context context) {
        return getUserName(context).isEmpty();
    }

    public static boolean saveInitialUserName(Context context, String value) {
        String name = normalizeName(value);
        if (name.isEmpty() || name.length() > 80 || !needsUserName(context)) return false;
        return prefs(context).edit()
                .putString(KEY_USER_NAME, name)
                .putBoolean(KEY_ACTIVATED, false)
                .remove(KEY_PENDING_HASH)
                .remove(KEY_PENDING_MESSAGE_ID)
                .putLong(KEY_UPDATE_ID, 0L)
                .commit();
    }

    public static boolean isActivated(Context context) {
        return !needsUserName(context) && prefs(context).getBoolean(KEY_ACTIVATED, false);
    }

    public static boolean hasPendingCode(Context context) {
        return !prefs(context).getString(KEY_PENDING_HASH, "").isEmpty();
    }

    public static void setPendingCode(Context context, String code, long messageId) {
        String userName = getUserName(context);
        if (userName.isEmpty() || code == null || code.trim().isEmpty()) return;
        prefs(context).edit()
                .putString(KEY_PENDING_HASH, hash(userName, code.trim()))
                .putLong(KEY_PENDING_MESSAGE_ID, messageId)
                .apply();
    }

    public static boolean verifyAndActivate(Context context, String code) {
        if (code == null || code.trim().isEmpty()) return false;
        String expected = prefs(context).getString(KEY_PENDING_HASH, "");
        if (expected.isEmpty()) return false;
        String actual = hash(getUserName(context), code.trim());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) return false;
        return prefs(context).edit()
                .putBoolean(KEY_ACTIVATED, true)
                .remove(KEY_PENDING_HASH)
                .commit();
    }

    public static long getPendingMessageId(Context context) {
        return prefs(context).getLong(KEY_PENDING_MESSAGE_ID, 0L);
    }

    public static long getTelegramUpdateId(Context context) {
        return prefs(context).getLong(KEY_UPDATE_ID, 0L);
    }

    public static void setTelegramUpdateId(Context context, long updateId) {
        prefs(context).edit().putLong(KEY_UPDATE_ID, updateId).apply();
    }

    public static String normalizeName(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        return normalized.replaceAll("\\s+", " ");
    }

    private static String hash(String userName, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((userName + "\u0000" + code)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("활성화 코드 저장 실패", e);
        }
    }
}
