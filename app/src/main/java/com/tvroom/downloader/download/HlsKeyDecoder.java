package com.tvroom.downloader.download;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Decodes raw HLS keys and the envelope used by the site's public HLS loader. */
final class HlsKeyDecoder {
    private HlsKeyDecoder() { }

    static byte[] decode(byte[] response) {
        if (response.length == 16) return response.clone();
        if (response.length > 65536) throw invalid();
        String text = new String(response, StandardCharsets.UTF_8).trim();
        String hex = text.replaceFirst("^0[xX]", "");
        if (hex.matches("(?i)[0-9a-f]{32}")) {
            byte[] key = new byte[16];
            for (int i = 0; i < key.length; i++) key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            return key;
        }
        try {
            if (!text.startsWith("{")) {
                byte[] decoded = Base64.getDecoder().decode(text);
                if (decoded.length == 16) return decoded;
                text = new String(decoded, StandardCharsets.UTF_8);
            }
            JSONObject object = new JSONObject(text);
            JSONObject rule = object.getJSONObject("rule");
            int count = rule.getInt("segments_count");
            int noise = rule.getInt("noise_length");
            if (rule.getInt("key_length") != 16 || count < 1 || count > 16 || noise < 0 || noise > 1024) throw invalid();
            JSONArray sizes = rule.getJSONArray("segment_sizes");
            JSONArray permutation = rule.getJSONArray("permutation");
            if (sizes.length() != count || permutation.length() != count) throw invalid();
            byte[] encoded = Base64.getDecoder().decode(object.getString("encrypted_key"));
            byte[][] chunks = new byte[count][];
            int offset = 0, total = 0;
            for (int i = 0; i < count; i++) {
                int originalIndex = permutation.getInt(i);
                if (originalIndex < 0 || originalIndex >= count || chunks[originalIndex] != null) throw invalid();
                int size = sizes.getInt(originalIndex);
                if (size < 1 || size > 16 || offset + size + noise > encoded.length) throw invalid();
                chunks[originalIndex] = Arrays.copyOfRange(encoded, offset, offset + size);
                offset += size + noise;
                total += size;
            }
            if (total != 16 || offset != encoded.length) throw invalid();
            byte[] key = new byte[16];
            offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, key, offset, chunk.length);
                offset += chunk.length;
            }
            return key;
        } catch (Exception error) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("지원하지 않거나 손상된 HLS 키 응답입니다.");
    }
}
