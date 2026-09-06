package com.tvroom.downloader.download;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import static org.junit.Assert.*;

public class HlsKeyDecoderTest {
    @Test public void rawAndHexAndBase64Keys() {
        byte[] key = new byte[16];
        assertArrayEquals(key, HlsKeyDecoder.decode(key));
        assertArrayEquals(key, HlsKeyDecoder.decode("00000000000000000000000000000000".getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals(key, HlsKeyDecoder.decode(Base64.getEncoder().encode(key)));
    }
    static byte[] envelope(byte[] key) throws Exception {
        // Unequal sizes and a non-involutive permutation catch incorrect reorder algorithms.
        int[] sizes = {1, 3, 5, 7}; int[] offsets = {0, 1, 4, 9}; int[] order = {2, 0, 3, 1};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i : order) { out.write(key, offsets[i], sizes[i]); out.write(new byte[]{99, 98}); }
        JSONObject rule = new JSONObject().put("segments_count", 4).put("noise_length", 2)
            .put("key_length", 16).put("segment_sizes", new JSONArray(sizes)).put("permutation", new JSONArray(order));
        JSONObject payload = new JSONObject().put("rule", rule)
            .put("encrypted_key", Base64.getEncoder().encodeToString(out.toByteArray()));
        return Base64.getEncoder().encode(payload.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Test public void siteEnvelopeRestoresPermutationAndRemovesNoise() throws Exception {
        byte[] key = new byte[16]; for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        byte[] response = envelope(key);
        assertArrayEquals(key, HlsKeyDecoder.decode(response));
        assertArrayEquals(key, HlsKeyDecoder.decode(Base64.getDecoder().decode(response)));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsHtmlErrorInsteadOfTruncatingItIntoKey() {
        HlsKeyDecoder.decode("<html>Token has expired</html>".getBytes(StandardCharsets.UTF_8));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidPermutation() throws Exception {
        JSONObject value = new JSONObject(new String(Base64.getDecoder().decode(envelope(new byte[16])), StandardCharsets.UTF_8));
        value.getJSONObject("rule").put("permutation", new JSONArray(new int[]{0, 0, 2, 3}));
        HlsKeyDecoder.decode(value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
