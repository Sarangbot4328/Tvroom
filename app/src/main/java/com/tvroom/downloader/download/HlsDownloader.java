package com.tvroom.downloader.download;

import com.tvroom.downloader.web.CaptureState;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class HlsDownloader {
    interface Progress { void update(String message, int percent); }
    interface Cancellation { boolean cancelled(); void connection(HttpURLConnection connection); }

    private static final Pattern ATTR_URI = Pattern.compile("URI=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_IV = Pattern.compile("IV=0x([0-9a-f]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEGMENT_LIST = Pattern.compile("(.*segment_list_)(\\d+)(\\.png(?:[?#].*)?)", Pattern.CASE_INSENSITIVE);
    private static final int SEGMENT_MAX_ATTEMPTS = 5;
    private static final long RETRY_BASE_DELAY_MS = 1_000L;
    private final CaptureState.Snapshot job;
    private final Progress progress;
    private final Cancellation cancellation;

    HlsDownloader(CaptureState.Snapshot job, Progress progress, Cancellation cancellation) {
        this.job = job; this.progress = progress; this.cancellation = cancellation;
    }

    boolean download(File workDir, File mediaOutput) throws Exception {
        Exception last = null;
        for (int i = job.m3u8Urls.size() - 1; i >= 0; i--) {
            checkCancelled();
            try {
                Playlist playlist = resolvePlaylist(job.m3u8Urls.get(i));
                DownloadedSegments segments = downloadPlaylist(playlist,
                        new File(workDir, "playlist_" + i));
                progress.update("오프라인 영상 구성 중…", 92);
                return saveOfflineHls(segments, mediaOutput);
            } catch (InterruptedException error) { throw error; }
            catch (Exception error) { last = error; mediaOutput.delete(); }
        }
        String segment = firstSegmentList(job.segmentUrls);
        if (segment != null && !job.keyHex.isEmpty()) {
            List<File> segments = downloadSegmentList(segment,
                    new File(workDir, "captured_segments"),
                    hex(job.keyHex), ivOrZero(job.ivHex), 0L);
            progress.update("오프라인 영상 구성 중…", 92);
            return saveOfflineHls(new DownloadedSegments(segments), mediaOutput);
        }
        throw new IllegalStateException(last == null ? "캡처된 스트림을 다운로드하지 못했습니다." : clean(last));
    }

    private Playlist resolvePlaylist(String url) throws Exception {
        String text = new String(fetch(url, job.pageUrl, false), StandardCharsets.UTF_8);
        if (!text.contains("#EXTM3U")) throw new IllegalStateException("m3u8 응답이 아닙니다.");
        if (text.contains("#EXT-X-STREAM-INF")) {
            String variant = bestVariant(text, url);
            if (variant == null) throw new IllegalStateException("HLS 화질 목록이 비어 있습니다.");
            return resolvePlaylist(variant);
        }
        Playlist out = new Playlist();
        out.baseUrl = url;
        out.mediaSequence = 0;
        double pendingDuration = Double.NaN;
        boolean pendingDiscontinuity = false;
        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                String value = line.substring(line.indexOf(':') + 1);
                int comma = value.indexOf(',');
                if (comma >= 0) value = value.substring(0, comma);
                try { pendingDuration = Double.parseDouble(value.trim()); }
                catch (Exception ignored) { pendingDuration = Double.NaN; }
            } else if (line.equals("#EXT-X-DISCONTINUITY")) {
                pendingDiscontinuity = true;
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try { out.mediaSequence = Long.parseLong(line.substring(line.indexOf(':') + 1).trim()); }
                catch (Exception ignored) { }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                out.encrypted = line.toUpperCase(Locale.US).contains("METHOD=AES-128");
                Matcher uri = ATTR_URI.matcher(line); if (uri.find()) out.keyUrl = absolute(url, uri.group(1));
                Matcher iv = ATTR_IV.matcher(line); if (iv.find()) out.iv = padHex(iv.group(1));
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                out.segments.add(absolute(url, line));
                out.durations.add(pendingDuration);
                out.discontinuities.add(pendingDiscontinuity);
                pendingDuration = Double.NaN;
                pendingDiscontinuity = false;
            }
        }
        if (out.segments.isEmpty()) throw new IllegalStateException("HLS 세그먼트가 없습니다.");
        return out;
    }

    private DownloadedSegments downloadPlaylist(Playlist playlist, File segmentDir) throws Exception {
        String custom = firstSegmentList(playlist.segments);
        byte[] capturedKey = job.keyHex.isEmpty() ? null : hex(job.keyHex);
        byte[] key = capturedKey;
        if (key == null && playlist.encrypted && playlist.keyUrl != null) {
            key = normalizeKey(fetch(playlist.keyUrl, job.pageUrl, false));
        }
        byte[] iv = playlist.iv != null ? playlist.iv : ivOrZero(job.ivHex);
        if (custom != null) {
            if (key == null) throw new IllegalStateException("segment_list 암호화 키를 찾지 못했습니다.");
            if (playlist.segments.size() > 1) {
                return downloadKnownSegmentList(playlist, segmentDir, key, iv);
            }
            List<File> parts = downloadSegmentList(
                    custom, segmentDir, key, iv, playlist.mediaSequence);
            DownloadedSegments downloaded = new DownloadedSegments(parts);
            if (parts.size() == playlist.segments.size()) {
                downloaded.durations.addAll(playlist.durations);
                downloaded.discontinuities.addAll(playlist.discontinuities);
            } else {
                double typical = typicalDuration(playlist.durations);
                if (!Double.isNaN(typical)) {
                    downloaded.durations.addAll(
                            Collections.nCopies(parts.size(), typical));
                    downloaded.discontinuities.addAll(
                            Collections.nCopies(parts.size(), false));
                }
            }
            return downloaded;
        }
        ensureDirectory(segmentDir);
        List<File> parts = new ArrayList<>();
        DownloadedSegments downloaded = new DownloadedSegments(parts);
        int total = playlist.segments.size();
        for (int i = 0; i < total; i++) {
            checkCancelled();
            progress.update("영상 조각 다운로드 " + (i + 1) + "/" + total,
                    12 + (int) ((i + 1L) * 75 / total));
            byte[] data = downloadPlaylistSegment(playlist, key, iv, i, total);
            File part = new File(segmentDir, String.format(Locale.US, "seg_%06d.ts", i));
            writePart(part, data);
            parts.add(part);
            downloaded.durations.add(i < playlist.durations.size()
                    ? playlist.durations.get(i) : Double.NaN);
            boolean originalDiscontinuity = i < playlist.discontinuities.size()
                    && playlist.discontinuities.get(i);
            downloaded.discontinuities.add(originalDiscontinuity);
        }
        if (parts.size() != total) {
            throw new IllegalStateException("영상 조각이 누락되어 다운로드를 완료하지 않았습니다.");
        }
        return downloaded;
    }

    private DownloadedSegments downloadKnownSegmentList(Playlist playlist, File segmentDir,
                                                         byte[] key, byte[] iv) throws Exception {
        ensureDirectory(segmentDir);
        List<File> parts = new ArrayList<>();
        DownloadedSegments downloaded = new DownloadedSegments(parts);
        int total = playlist.segments.size();
        long firstIndex = playlist.mediaSequence;
        Matcher first = SEGMENT_LIST.matcher(playlist.segments.get(0));
        if (first.matches()) firstIndex = Long.parseLong(first.group(2));

        for (int i = 0; i < total; i++) {
            checkCancelled();
            String url = playlist.segments.get(i);
            Matcher matcher = SEGMENT_LIST.matcher(url);
            long sourceIndex = matcher.matches()
                    ? Long.parseLong(matcher.group(2)) : firstIndex + i;
            progress.update("암호화 영상 조각 다운로드 " + (i + 1) + "/" + total,
                    12 + (int) ((i + 1L) * 75 / Math.max(1, total)));
            byte[] ts = downloadCustomSegment(url, key, iv, sourceIndex, firstIndex,
                    playlist.mediaSequence, i);
            File part = new File(segmentDir, String.format(Locale.US, "seg_%06d.ts", i));
            writePart(part, ts);
            parts.add(part);
            downloaded.durations.add(i < playlist.durations.size()
                    ? playlist.durations.get(i) : Double.NaN);
            downloaded.discontinuities.add(i < playlist.discontinuities.size()
                    && playlist.discontinuities.get(i));
        }
        if (parts.size() != total) {
            throw new IllegalStateException("암호화 영상 조각이 누락되어 다운로드를 완료하지 않았습니다.");
        }
        return downloaded;
    }

    private byte[] downloadPlaylistSegment(Playlist playlist, byte[] key, byte[] iv,
                                           int index, int total) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= SEGMENT_MAX_ATTEMPTS; attempt++) {
            checkCancelled();
            try {
                byte[] data = fetch(playlist.segments.get(index), job.pageUrl, false);
                if (key != null && playlist.encrypted) {
                    long sequence = playlist.mediaSequence + index;
                    return decryptBest(data, key, iv, sequence,
                            playlist.mediaSequence, playlist.mediaSequence);
                }
                return normalizeTs(data);
            } catch (InterruptedException error) {
                throw error;
            } catch (Exception error) {
                last = error;
                if (attempt < SEGMENT_MAX_ATTEMPTS) {
                    progress.update("영상 조각 재시도 " + (index + 1) + "/" + total
                                    + " · " + (attempt + 1) + "/" + SEGMENT_MAX_ATTEMPTS,
                            12 + (int) ((index + 1L) * 75 / Math.max(1, total)));
                    waitBeforeRetry(attempt);
                }
            }
        }
        throw new IllegalStateException("영상 조각 " + (index + 1) + "/" + total
                + "을 " + SEGMENT_MAX_ATTEMPTS + "회 시도했지만 받지 못했습니다.", last);
    }

    private List<File> downloadSegmentList(String seedUrl, File segmentDir,
                                           byte[] key, byte[] iv, long mediaSequence) throws Exception {
        Matcher matcher = SEGMENT_LIST.matcher(seedUrl);
        if (!matcher.matches()) throw new IllegalStateException("segment_list 주소 형식을 인식하지 못했습니다.");
        String prefix = matcher.group(1), suffix = matcher.group(3);
        int captured = Integer.parseInt(matcher.group(2));
        int start = probe(prefix + 0 + suffix) ? 0 : captured;
        int misses = 0, saved = 0, index = start;
        int firstMissing = -1;
        ensureDirectory(segmentDir);
        List<File> parts = new ArrayList<>();
        while (index < start + 10000 && misses < 3) {
            checkCancelled();
            String url = prefix + index + suffix;
            byte[] ts;
            try {
                ts = downloadCustomSegment(url, key, iv, index, start, mediaSequence, saved);
            }
            catch (HttpStatusException error) {
                if (error.code == 404 || error.code == 403) {
                    if (firstMissing < 0) firstMissing = index;
                    misses++;
                    index++;
                    continue;
                }
                throw error;
            }
            if (misses > 0) {
                throw new IllegalStateException("중간 영상 조각 " + firstMissing
                        + "번이 누락되어 다운로드를 완료하지 않았습니다.");
            }
            misses = 0;
            firstMissing = -1;
            File part = new File(segmentDir, String.format(Locale.US, "seg_%06d.ts", index));
            writePart(part, ts);
            parts.add(part);
            saved++;
            int shown = Math.min(88, 12 + saved / 2);
            progress.update("암호화 영상 조각 " + saved + "개 복원", shown);
            index++;
        }
        if (saved == 0) throw new IllegalStateException("복원 가능한 segment_list 조각이 없습니다.");
        if (index >= start + 10000 && misses < 3) {
            throw new IllegalStateException("영상 조각 끝을 확인하지 못해 다운로드를 완료하지 않았습니다.");
        }
        return parts;
    }

    private byte[] downloadCustomSegment(String url, byte[] key, byte[] iv, long index,
                                         long firstIndex, long mediaSequence, int saved)
            throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= SEGMENT_MAX_ATTEMPTS; attempt++) {
            checkCancelled();
            try {
                byte[] encrypted = fetch(url, job.pageUrl, true);
                return decryptBest(encrypted, key, iv, index, firstIndex, mediaSequence);
            } catch (InterruptedException error) {
                throw error;
            } catch (Exception error) {
                last = error;
                if (attempt < SEGMENT_MAX_ATTEMPTS) {
                    progress.update("암호화 영상 조각 재시도 " + index + " · "
                                    + (attempt + 1) + "/" + SEGMENT_MAX_ATTEMPTS,
                            Math.min(88, 12 + saved / 2));
                    waitBeforeRetry(attempt);
                }
            }
        }
        if (last instanceof HttpStatusException) throw (HttpStatusException) last;
        throw new IllegalStateException("암호화 영상 조각 " + index + "번을 "
                + SEGMENT_MAX_ATTEMPTS + "회 시도했지만 복원하지 못했습니다.", last);
    }

    private void waitBeforeRetry(int failedAttempt) throws InterruptedException {
        long remaining = RETRY_BASE_DELAY_MS << Math.min(3, Math.max(0, failedAttempt - 1));
        while (remaining > 0L) {
            checkCancelled();
            long pause = Math.min(250L, remaining);
            Thread.sleep(pause);
            remaining -= pause;
        }
    }

    private static void ensureDirectory(File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("영상 조각 임시 폴더를 만들지 못했습니다.");
        }
    }

    private static void writePart(File output, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(output)) {
            out.write(data);
        }
        if (!output.isFile() || output.length() != data.length) {
            output.delete();
            throw new IllegalStateException("영상 조각 파일을 완전히 저장하지 못했습니다.");
        }
    }

    /**
     * Keeps the original HLS segment boundaries. Android's platform MediaMuxer can produce an MP4
     * that reports the right duration and seek frames but never advances playback when the source
     * has discontinuous timestamps, so it must not be used as a success path for these streams.
     */
    private boolean saveOfflineHls(DownloadedSegments downloaded, File output) throws Exception {
        output.delete();
        File offlineSegments = new File(output.getParentFile(), "offline_segments");
        TsRemuxer.createOfflineHls(downloaded.files, downloaded.durations,
                downloaded.discontinuities, output, offlineSegments, (completed, total) -> {
            int percent = 92 + (int) (completed * 7L / Math.max(1, total));
            progress.update("오프라인 영상 저장 " + completed + "/" + total, percent);
        });
        return false;
    }

    private boolean probe(String url) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= SEGMENT_MAX_ATTEMPTS; attempt++) {
            try { return fetch(url, job.pageUrl, true).length > 0; }
            catch (InterruptedException error) { throw error; }
            catch (HttpStatusException error) {
                last = error;
                if (error.code == 404 || error.code == 403) return false;
            }
            catch (Exception error) { last = error; }
            if (attempt < SEGMENT_MAX_ATTEMPTS) waitBeforeRetry(attempt);
        }
        throw last == null ? new IllegalStateException("첫 영상 조각 확인 실패") : last;
    }

    private byte[] fetch(String value, String referer, boolean allowImage) throws Exception {
        Exception last = null;
        for (String candidate : refererCandidates(value, referer)) {
            try { return fetchOnce(value, candidate, allowImage); }
            catch (InterruptedException error) { throw error; }
            catch (Exception error) { last = error; }
        }
        throw last == null ? new IllegalStateException("네트워크 요청 실패") : last;
    }

    private byte[] fetchOnce(String value, String referer, boolean allowImage) throws Exception {
        checkCancelled();
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        cancellation.connection(connection);
        connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", job.userAgent.isEmpty() ?
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36" : job.userAgent);
        if (!job.cookie.isEmpty()) connection.setRequestProperty("Cookie", job.cookie);
        if (referer != null && !referer.isEmpty()) {
            connection.setRequestProperty("Referer", referer);
            try { URL ref = new URL(referer); connection.setRequestProperty("Origin", ref.getProtocol() + "://" + ref.getHost()); }
            catch (Exception ignored) { }
        }
        connection.setRequestProperty("Accept", allowImage ? "*/*" : "application/vnd.apple.mpegurl,*/*");
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
        try {
            int code = connection.getResponseCode();
            if (code >= 400) throw new HttpStatusException(code);
            try (java.io.InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = in.read(buffer)) >= 0) { checkCancelled(); out.write(buffer, 0, read); }
                return out.toByteArray();
            }
        } finally { cancellation.connection(null); connection.disconnect(); }
    }

    private List<String> refererCandidates(String requestUrl, String preferred) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String captured = job.streamReferers.get(requestUrl);
        if (captured != null && !captured.isEmpty()) values.add(captured);
        for (String value : job.streamReferers.values()) {
            if (value != null && !value.isEmpty()) values.add(value);
        }
        String jwt = jwtReferer(requestUrl);
        if (jwt != null) values.add(jwt);
        if (preferred != null && !preferred.isEmpty()) values.add(preferred);
        values.add(job.pageUrl);
        try {
            URL request = new URL(requestUrl);
            values.add(request.getProtocol() + "://" + request.getHost() + "/");
        } catch (Exception ignored) { }
        return new ArrayList<>(values);
    }

    private static String jwtReferer(String url) {
        try {
            for (String part : new URL(url).getPath().split("/")) {
                if (part.chars().filter(ch -> ch == '.').count() < 2) continue;
                String payload = part.split("\\.")[1];
                String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
                org.json.JSONObject object = new org.json.JSONObject(json);
                String value = object.optString("referer", object.optString("Referer", object.optString("origin")));
                if (value.isEmpty()) continue;
                return value.startsWith("http") ? value : "https://" + value + "/";
            }
        } catch (Exception ignored) { }
        return null;
    }

    private String bestVariant(String text, String base) {
        String[] lines = text.split("\\r?\\n"); long best = -1; String result = null;
        for (int i = 0; i + 1 < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
            Matcher m = BANDWIDTH.matcher(lines[i]); long bandwidth = m.find() ? Long.parseLong(m.group(1)) : 0;
            String candidate = lines[i + 1].trim();
            if (!candidate.startsWith("#") && bandwidth >= best) { best = bandwidth; result = absolute(base, candidate); }
        }
        return result;
    }

    private static String absolute(String base, String relative) {
        try { return new URL(new URL(base), relative).toString(); }
        catch (Exception error) { return relative; }
    }

    private static String firstSegmentList(List<String> values) {
        for (String value : values) if (value.toLowerCase(Locale.US).contains("segment_list")) return value;
        return null;
    }

    private static double typicalDuration(List<Double> values) {
        List<Double> valid = new ArrayList<>();
        for (Double value : values) {
            if (value != null && !Double.isNaN(value) && !Double.isInfinite(value)
                    && value >= 0.05 && value <= 120.0) valid.add(value);
        }
        if (valid.isEmpty()) return Double.NaN;
        Collections.sort(valid);
        int middle = valid.size() / 2;
        return (valid.size() & 1) == 1 ? valid.get(middle)
                : (valid.get(middle - 1) + valid.get(middle)) / 2.0;
    }

    private static byte[] normalizeKey(byte[] value) {
        if (value.length == 16) return value;
        String text = new String(value, StandardCharsets.US_ASCII).trim().replaceFirst("^0x", "");
        if (text.matches("(?i)[0-9a-f]{32,}")) return Arrays.copyOf(hex(text), 16);
        return Arrays.copyOf(value, 16);
    }

    private static byte[] decryptBest(byte[] encrypted, byte[] key, byte[] configuredIv,
                                      long index, long firstIndex, long mediaSequence)
            throws GeneralSecurityException {
        // Some servers use the segment_list name while returning an already decoded TS payload.
        // The strict multi-packet check in normalizeTs makes this safe without mistaking ciphertext.
        try { return normalizeTs(encrypted); } catch (Exception ignored) { }
        Set<String> seen = new LinkedHashSet<>();
        List<byte[]> candidates = new ArrayList<>();
        long relativeIndex = Math.max(0L, index - firstIndex);
        candidates.add(configuredIv);
        candidates.add(new byte[16]);
        candidates.add(sequenceIv(index));
        candidates.add(sequenceIv(relativeIndex));
        candidates.add(sequenceIv(index + 1));
        candidates.add(sequenceIv(relativeIndex + 1));
        candidates.add(sequenceIv(mediaSequence + relativeIndex));
        candidates.add(addToIv(configuredIv, index));
        candidates.add(addToIv(configuredIv, relativeIndex));
        GeneralSecurityException last = null;
        for (byte[] candidate : candidates) {
            String marker = Arrays.toString(candidate); if (!seen.add(marker)) continue;
            for (String padding : new String[]{"PKCS5Padding", "NoPadding"}) {
                try {
                    Cipher cipher = Cipher.getInstance("AES/CBC/" + padding);
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOf(key, 16), "AES"),
                            new IvParameterSpec(Arrays.copyOf(candidate, 16)));
                    byte[] value = cipher.doFinal(encrypted);
                    try { return normalizeTs(value); } catch (Exception ignored) { }
                } catch (GeneralSecurityException error) { last = error; }
            }
        }
        throw last == null ? new GeneralSecurityException("AES 복호화 실패") : last;
    }

    private static byte[] normalizeTs(byte[] value) {
        int limit = Math.min(value.length, 4096);
        for (int offset = 0; offset < limit; offset++) {
            if ((value[offset] & 0xff) != 0x47) continue;
            int packets = (value.length - offset) / 188;
            if (packets < 3) continue;
            int checks = Math.min(8, packets);
            boolean valid = true;
            for (int packet = 0; packet < checks; packet++) {
                int position = offset + packet * 188;
                if ((value[position] & 0xff) != 0x47
                        || (value[position + 3] & 0x30) == 0) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                // Remove image wrappers and AES padding so concatenated segments stay 188-byte aligned.
                return Arrays.copyOfRange(value, offset, offset + packets * 188);
            }
        }
        throw new IllegalStateException("복호화 결과가 MPEG-TS 형식이 아닙니다.");
    }

    private static byte[] sequenceIv(long value) {
        byte[] out = new byte[16];
        for (int i = 15; i >= 8; i--) { out[i] = (byte) value; value >>>= 8; }
        return out;
    }

    private static byte[] addToIv(byte[] base, long value) {
        byte[] out = Arrays.copyOf(base, 16);
        int carry = 0;
        for (int i = 15; i >= 0; i--) {
            int add = i >= 8 ? (int) ((value >>> ((15 - i) * 8)) & 0xff) : 0;
            int sum = (out[i] & 0xff) + add + carry;
            out[i] = (byte) sum;
            carry = sum >>> 8;
        }
        return out;
    }

    private static byte[] ivOrZero(String value) { return value == null || value.isEmpty() ? new byte[16] : padHex(value); }
    private static byte[] padHex(String value) { return Arrays.copyOf(hex(value), 16); }
    private static byte[] hex(String text) {
        String clean = text.replaceFirst("(?i)^0x", "");
        if ((clean.length() & 1) == 1) clean = "0" + clean;
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    private void checkCancelled() throws InterruptedException {
        if (cancellation.cancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException("다운로드 중단");
    }
    private static String clean(Exception error) {
        String message = error.getMessage(); return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
    private static final class Playlist {
        String baseUrl, keyUrl;
        byte[] iv;
        boolean encrypted;
        long mediaSequence;
        final List<String> segments = new ArrayList<>();
        final List<Double> durations = new ArrayList<>();
        final List<Boolean> discontinuities = new ArrayList<>();
    }
    private static final class DownloadedSegments {
        final List<File> files;
        final List<Double> durations = new ArrayList<>();
        final List<Boolean> discontinuities = new ArrayList<>();
        DownloadedSegments(List<File> files) { this.files = files; }
    }
    private static final class HttpStatusException extends Exception {
        final int code; HttpStatusException(int code) { super("HTTP " + code); this.code = code; }
    }
}
