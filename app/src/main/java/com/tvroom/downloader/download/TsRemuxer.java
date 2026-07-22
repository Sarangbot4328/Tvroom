package com.tvroom.downloader.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TsRemuxer {
    interface Progress { void update(int completed, int total); }

    private static final long UNSET = Long.MIN_VALUE;
    private static final long DEFAULT_VIDEO_STEP_US = 33_333L;
    private static final long DEFAULT_AUDIO_STEP_US = 21_333L;
    private static final int TS_PACKET_SIZE = 188;
    private static final int SEGMENTS_PER_BATCH = 8;
    private static final long MPEG_CLOCK_WRAP = 1L << 33;
    private static final long DEFAULT_SEGMENT_TICKS = 6L * 90_000L;

    private TsRemuxer() { }

    /**
     * Keeps the already decrypted MPEG-TS stream when a device's platform extractor cannot
     * remux it to MP4. Each HLS segment can restart its timestamps and continuity counters, so
     * rebuild both while joining. This is the Android equivalent of ffmpeg's +genpts fallback.
     */
    static void concatenate(List<File> segments, File output, Progress progress) throws Exception {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalStateException("저장할 영상 조각이 없습니다.");
        }
        if (output.exists() && !output.delete()) {
            throw new IOException("기존 임시 영상 파일을 제거하지 못했습니다.");
        }
        byte[] buffer = new byte[1024 * 1024];
        long outputBaseTicks = 0L;
        Map<Integer, Integer> continuity = new HashMap<>();
        try (FileOutputStream out = new FileOutputStream(output)) {
            for (int i = 0; i < segments.size(); i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("다운로드 중단");
                }
                File segment = segments.get(i);
                if (!segment.isFile() || segment.length() < TS_PACKET_SIZE * 3L
                        || segment.length() % TS_PACKET_SIZE != 0
                        || segment.length() > Integer.MAX_VALUE) {
                    throw new IOException("영상 조각 " + (i + 1) + "의 형식이 올바르지 않습니다.");
                }
                byte[] data = readFully(segment, buffer);
                long durationTicks = normalizeTimeline(data, outputBaseTicks, continuity);
                out.write(data);
                outputBaseTicks += durationTicks;
                if (progress != null) progress.update(i + 1, segments.size());
            }
            out.getFD().sync();
        } catch (Exception error) {
            output.delete();
            throw error;
        }
        if (!output.isFile() || output.length() < 4096) {
            output.delete();
            throw new IOException("저장된 원본 영상 파일이 비어 있습니다.");
        }
    }

    private static byte[] readFully(File file, byte[] buffer) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream((int) file.length());
        try (FileInputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) data.write(buffer, 0, read);
        }
        return data.toByteArray();
    }

    private static long normalizeTimeline(byte[] data, long outputBaseTicks,
                                          Map<Integer, Integer> continuity) {
        TimestampRange range = new TimestampRange();
        for (int offset = 0; offset + TS_PACKET_SIZE <= data.length; offset += TS_PACKET_SIZE) {
            collectTimestamps(data, offset, range);
        }

        long shift = range.found ? outputBaseTicks - range.first : 0L;
        for (int offset = 0; offset + TS_PACKET_SIZE <= data.length; offset += TS_PACKET_SIZE) {
            rewriteContinuity(data, offset, continuity);
            if (range.found) rewriteTimestamps(data, offset, shift);
        }

        if (!range.found) return DEFAULT_SEGMENT_TICKS;
        long measured = range.last - range.first + 3_000L;
        if (measured < 45_000L || measured > 30L * 90_000L) return DEFAULT_SEGMENT_TICKS;
        return measured;
    }

    private static void collectTimestamps(byte[] data, int packet, TimestampRange range) {
        long pcr = readPcr(data, packet);
        if (pcr >= 0) range.add(pcr);
        int payload = pesPayloadOffset(data, packet);
        if (payload < 0 || payload + 14 >= packet + TS_PACKET_SIZE) return;
        int flags = (data[payload + 7] >>> 6) & 0x03;
        if (flags == 2 || flags == 3) range.add(readPts(data, payload + 9));
        if (flags == 3 && payload + 18 < packet + TS_PACKET_SIZE) {
            range.add(readPts(data, payload + 14));
        }
    }

    private static void rewriteTimestamps(byte[] data, int packet, long shift) {
        int adaptationControl = (data[packet + 3] >>> 4) & 0x03;
        if (adaptationControl == 2 || adaptationControl == 3) {
            int length = data[packet + 4] & 0xff;
            if (length >= 7 && packet + 5 + length <= packet + TS_PACKET_SIZE
                    && (data[packet + 5] & 0x10) != 0) {
                int pcrOffset = packet + 6;
                long pcr = readPcrBase(data, pcrOffset);
                writePcrBase(data, pcrOffset, addClock(pcr, shift));
            }
        }

        int payload = pesPayloadOffset(data, packet);
        if (payload < 0 || payload + 14 >= packet + TS_PACKET_SIZE) return;
        int flags = (data[payload + 7] >>> 6) & 0x03;
        if (flags == 2 || flags == 3) {
            int ptsOffset = payload + 9;
            writePts(data, ptsOffset, addClock(readPts(data, ptsOffset), shift));
        }
        if (flags == 3 && payload + 18 < packet + TS_PACKET_SIZE) {
            int dtsOffset = payload + 14;
            writePts(data, dtsOffset, addClock(readPts(data, dtsOffset), shift));
        }
    }

    private static int pesPayloadOffset(byte[] data, int packet) {
        if ((data[packet] & 0xff) != 0x47 || (data[packet + 1] & 0x40) == 0) return -1;
        int adaptationControl = (data[packet + 3] >>> 4) & 0x03;
        if (adaptationControl == 0 || adaptationControl == 2) return -1;
        int payload = packet + 4;
        if (adaptationControl == 3) payload += 1 + (data[payload] & 0xff);
        if (payload + 9 >= packet + TS_PACKET_SIZE) return -1;
        if (data[payload] != 0 || data[payload + 1] != 0 || data[payload + 2] != 1) return -1;
        return payload;
    }

    private static long readPcr(byte[] data, int packet) {
        int adaptationControl = (data[packet + 3] >>> 4) & 0x03;
        if (adaptationControl != 2 && adaptationControl != 3) return -1L;
        int length = data[packet + 4] & 0xff;
        if (length < 7 || packet + 5 + length > packet + TS_PACKET_SIZE
                || (data[packet + 5] & 0x10) == 0) return -1L;
        return readPcrBase(data, packet + 6);
    }

    private static long readPcrBase(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 25)
                | ((long) (data[offset + 1] & 0xff) << 17)
                | ((long) (data[offset + 2] & 0xff) << 9)
                | ((long) (data[offset + 3] & 0xff) << 1)
                | ((long) (data[offset + 4] & 0x80) >>> 7);
    }

    private static void writePcrBase(byte[] data, int offset, long value) {
        int extension = ((data[offset + 4] & 0x01) << 8) | (data[offset + 5] & 0xff);
        data[offset] = (byte) (value >>> 25);
        data[offset + 1] = (byte) (value >>> 17);
        data[offset + 2] = (byte) (value >>> 9);
        data[offset + 3] = (byte) (value >>> 1);
        data[offset + 4] = (byte) (((value & 1L) << 7) | 0x7e | (extension >>> 8));
        data[offset + 5] = (byte) extension;
    }

    private static long readPts(byte[] data, int offset) {
        return ((long) (data[offset] & 0x0e) << 29)
                | ((long) (data[offset + 1] & 0xff) << 22)
                | ((long) (data[offset + 2] & 0xfe) << 14)
                | ((long) (data[offset + 3] & 0xff) << 7)
                | ((long) (data[offset + 4] & 0xfe) >>> 1);
    }

    private static void writePts(byte[] data, int offset, long value) {
        int prefix = data[offset] & 0xf0;
        data[offset] = (byte) (prefix | (((value >>> 30) & 0x07) << 1) | 1);
        data[offset + 1] = (byte) (value >>> 22);
        data[offset + 2] = (byte) ((((value >>> 15) & 0x7f) << 1) | 1);
        data[offset + 3] = (byte) (value >>> 7);
        data[offset + 4] = (byte) (((value & 0x7f) << 1) | 1);
    }

    private static long addClock(long value, long shift) {
        long adjusted = (value + shift) % MPEG_CLOCK_WRAP;
        return adjusted < 0 ? adjusted + MPEG_CLOCK_WRAP : adjusted;
    }

    private static void rewriteContinuity(byte[] data, int packet,
                                          Map<Integer, Integer> continuity) {
        int pid = ((data[packet + 1] & 0x1f) << 8) | (data[packet + 2] & 0xff);
        int adaptationControl = (data[packet + 3] >>> 4) & 0x03;
        if (adaptationControl == 1 || adaptationControl == 3) {
            int next = continuity.containsKey(pid) ? continuity.get(pid) : 0;
            data[packet + 3] = (byte) ((data[packet + 3] & 0xf0) | next);
            continuity.put(pid, (next + 1) & 0x0f);
        } else if (adaptationControl == 2 && continuity.containsKey(pid)) {
            int current = (continuity.get(pid) + 15) & 0x0f;
            data[packet + 3] = (byte) ((data[packet + 3] & 0xf0) | current);
        }
    }

    private static final class TimestampRange {
        boolean found;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;

        void add(long value) {
            if (value < 0) return;
            found = true;
            first = Math.min(first, value);
            last = Math.max(last, value);
        }
    }

    /**
     * Opens short batches of HLS segments. A small batch keeps MediaExtractor away from long
     * discontinuous TS inputs while avoiding one native extractor instance per tiny fragment.
     * PAT/PMT packets are prefixed so continuation fragments can be parsed independently.
     */
    static void remux(List<File> segments, File output, Progress progress) throws Exception {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalStateException("복원할 영상 조각이 없습니다.");
        }

        byte[] psiHeader = findPsiHeader(segments);
        File scratchDir = output.getParentFile();
        TrackFormats formats = findTrackFormats(segments, scratchDir, psiHeader);
        if (formats.video == null) {
            throw new IllegalStateException("영상 조각에서 비디오 트랙을 찾지 못했습니다.");
        }
        if (formats.audio == null) {
            throw new IllegalStateException("영상 조각에서 오디오 트랙을 찾지 못했습니다.");
        }
        if (output.exists() && !output.delete()) {
            throw new IllegalStateException("기존 임시 MP4 파일을 제거하지 못했습니다.");
        }

        MediaMuxer muxer = null;
        boolean started = false;
        long videoLastUs = UNSET;
        long audioLastUs = UNSET;
        long videoStepUs = DEFAULT_VIDEO_STEP_US;
        long audioStepUs = DEFAULT_AUDIO_STEP_US;
        long segmentBaseUs = 0L;
        int videoSamples = 0;
        int audioSamples = 0;
        int audioBatches = 0;
        int processedBatches = 0;
        int processedSegments = 0;
        File batchFile = new File(scratchDir, ".remux_batch.ts");

        try {
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTarget = muxer.addTrack(formats.video);
            int audioTarget = formats.audio == null ? -1 : muxer.addTrack(formats.audio);
            muxer.start();
            started = true;

            int bufferSize = Math.max(4 * 1024 * 1024, formats.maxInputSize);
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            for (int batchStart = 0; batchStart < segments.size(); batchStart += SEGMENTS_PER_BATCH) {
                int batchEnd = Math.min(segments.size(), batchStart + SEGMENTS_PER_BATCH);
                createBatchFile(segments, batchStart, batchEnd, batchFile, psiHeader);
                MediaExtractor extractor = new MediaExtractor();
                try {
                    try {
                        extractor.setDataSource(batchFile.getAbsolutePath());
                    } catch (Exception error) {
                        throw new IllegalStateException(
                                "영상 조각 " + (batchStart + 1) + "~" + batchEnd
                                        + " 묶음을 읽지 못했습니다.", error);
                    }
                    int videoSource = findTrack(extractor, "video/", formats.videoMime);
                    int audioSource = formats.audio == null
                            ? -1 : findTrack(extractor, "audio/", formats.audioMime);
                    if (videoSource < 0) {
                        throw new IllegalStateException(
                                "영상 조각 " + (batchStart + 1) + "~" + batchEnd
                                        + "에서 비디오 트랙을 읽지 못했습니다.");
                    }
                    extractor.selectTrack(videoSource);
                    if (audioSource >= 0) extractor.selectTrack(audioSource);

                    long firstTimeUs = extractor.getSampleTime();
                    if (firstTimeUs < 0) {
                        throw new IllegalStateException(
                                "영상 조각 " + (batchStart + 1) + "~" + batchEnd + "이 비어 있습니다.");
                    }

                    long segmentMaxRelativeUs = 0L;
                    long segmentVideoLastInputUs = UNSET;
                    long segmentAudioLastInputUs = UNSET;
                    int segmentVideoSamples = 0;
                    int segmentAudioSamples = 0;

                    while (true) {
                        int sourceTrack = extractor.getSampleTrackIndex();
                        if (sourceTrack < 0) break;
                        boolean video = sourceTrack == videoSource;
                        boolean audio = sourceTrack == audioSource;
                        if (!video && !audio) {
                            extractor.advance();
                            continue;
                        }

                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;
                        long inputTimeUs = extractor.getSampleTime();
                        if (inputTimeUs < 0) {
                            extractor.advance();
                            continue;
                        }

                        long relativeUs = Math.max(0L, inputTimeUs - firstTimeUs);
                        long outputTimeUs = segmentBaseUs + relativeUs;
                        if (video) {
                            if (segmentVideoLastInputUs != UNSET && inputTimeUs > segmentVideoLastInputUs) {
                                long delta = inputTimeUs - segmentVideoLastInputUs;
                                if (delta < 1_000_000L) videoStepUs = smoothStep(videoStepUs, delta);
                            }
                            if (videoLastUs != UNSET && outputTimeUs <= videoLastUs) {
                                outputTimeUs = videoLastUs + Math.max(1L, videoStepUs);
                            }
                            segmentVideoLastInputUs = inputTimeUs;
                            videoLastUs = outputTimeUs;
                            segmentVideoSamples++;
                            videoSamples++;
                        } else {
                            if (segmentAudioLastInputUs != UNSET && inputTimeUs > segmentAudioLastInputUs) {
                                long delta = inputTimeUs - segmentAudioLastInputUs;
                                if (delta < 1_000_000L) audioStepUs = smoothStep(audioStepUs, delta);
                            }
                            if (audioLastUs != UNSET && outputTimeUs <= audioLastUs) {
                                outputTimeUs = audioLastUs + Math.max(1L, audioStepUs);
                            }
                            segmentAudioLastInputUs = inputTimeUs;
                            audioLastUs = outputTimeUs;
                            segmentAudioSamples++;
                            audioSamples++;
                        }

                        buffer.position(0);
                        buffer.limit(size);
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = outputTimeUs;
                        info.flags = extractor.getSampleFlags();
                        muxer.writeSampleData(video ? videoTarget : audioTarget, buffer, info);
                        segmentMaxRelativeUs = Math.max(segmentMaxRelativeUs, relativeUs);
                        extractor.advance();
                    }

                    if (segmentVideoSamples == 0) {
                        throw new IllegalStateException(
                                "영상 조각 " + (batchStart + 1) + "~" + batchEnd
                                        + "의 비디오 데이터가 비어 있습니다.");
                    }
                    long tailStepUs = segmentAudioSamples > 0
                            ? Math.max(videoStepUs, audioStepUs) : videoStepUs;
                    segmentBaseUs += segmentMaxRelativeUs + Math.max(1L, tailStepUs);
                    if (segmentAudioSamples > 0) audioBatches++;
                    processedBatches++;
                    processedSegments = batchEnd;
                    if (progress != null) progress.update(processedSegments, segments.size());
                } finally {
                    extractor.release();
                    if (batchFile.exists()) batchFile.delete();
                }
            }

            if (processedSegments != segments.size() || videoSamples == 0) {
                throw new IllegalStateException("모든 영상 조각을 MP4로 복원하지 못했습니다.");
            }
            if (formats.audio != null && audioSamples == 0) {
                throw new IllegalStateException("오디오 트랙을 MP4로 복원하지 못했습니다.");
            }
            if (audioBatches * 10 < processedBatches * 9) {
                throw new IllegalStateException("일부 영상 조각의 오디오를 읽지 못해 복원을 중단했습니다.");
            }

            muxer.stop();
            started = false;
            muxer.release();
            muxer = null;

            long writtenDurationUs = Math.max(videoLastUs == UNSET ? 0L : videoLastUs,
                    audioLastUs == UNSET ? 0L : audioLastUs);
            validateOutput(output, writtenDurationUs);
        } finally {
            if (batchFile.exists()) batchFile.delete();
            if (muxer != null) {
                if (started) {
                    try { muxer.stop(); } catch (Exception ignored) { }
                }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if ((!output.isFile() || output.length() < 4096) && output.exists()) {
                output.delete();
            }
        }
    }

    private static TrackFormats findTrackFormats(List<File> segments, File scratchDir,
                                                  byte[] psiHeader) throws Exception {
        TrackFormats result = new TrackFormats();
        Exception lastError = null;
        File probeFile = new File(scratchDir, ".remux_probe.ts");
        for (int start = 0; start < segments.size(); start += SEGMENTS_PER_BATCH) {
            int end = Math.min(segments.size(), start + SEGMENTS_PER_BATCH);
            createBatchFile(segments, start, end, probeFile, psiHeader);
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(probeFile.getAbsolutePath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;
                    if (result.video == null && mime.startsWith("video/")) {
                        result.video = format;
                        result.videoMime = mime;
                    } else if (result.audio == null && mime.startsWith("audio/")) {
                        result.audio = format;
                        result.audioMime = mime;
                    }
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        result.maxInputSize = Math.max(result.maxInputSize,
                                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    }
                }
            } catch (Exception error) {
                lastError = error;
            } finally {
                extractor.release();
                if (probeFile.exists()) probeFile.delete();
            }
            if (result.video != null && result.audio != null) break;
        }
        if (result.video == null && lastError != null) {
            throw new IllegalStateException(
                    "복원용 영상 조각에서 미디어 트랙을 읽지 못했습니다.", lastError);
        }
        return result;
    }

    private static void createBatchFile(List<File> segments, int start, int end,
                                        File output, byte[] psiHeader) throws IOException {
        if (output.exists() && !output.delete()) {
            throw new IOException("기존 복원 묶음 파일을 제거하지 못했습니다.");
        }
        byte[] buffer = new byte[1024 * 1024];
        try (FileOutputStream out = new FileOutputStream(output)) {
            if (psiHeader != null && psiHeader.length > 0) out.write(psiHeader);
            for (int i = start; i < end; i++) {
                try (FileInputStream in = new FileInputStream(segments.get(i))) {
                    int read;
                    while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
                }
            }
        } catch (IOException error) {
            output.delete();
            throw error;
        }
    }

    /** Finds one PAT and one PMT packet so every small batch is independently parseable. */
    private static byte[] findPsiHeader(List<File> segments) {
        byte[] pat = null;
        int pmtPid = -1;
        byte[] packet = new byte[TS_PACKET_SIZE];
        for (File segment : segments) {
            try (FileInputStream in = new FileInputStream(segment)) {
                int scanned = 0;
                while (scanned++ < 4096 && readPacket(in, packet)) {
                    if ((packet[0] & 0xff) != 0x47) continue;
                    int pid = ((packet[1] & 0x1f) << 8) | (packet[2] & 0xff);
                    if (pid == 0) {
                        if (pat == null) pat = Arrays.copyOf(packet, packet.length);
                        int parsed = parsePmtPid(packet);
                        if (parsed >= 0) pmtPid = parsed;
                    } else if (pmtPid >= 0 && pid == pmtPid && pat != null) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream(TS_PACKET_SIZE * 2);
                        out.write(pat, 0, pat.length);
                        out.write(packet, 0, packet.length);
                        return out.toByteArray();
                    }
                }
            } catch (Exception ignored) { }
        }
        return pat;
    }

    private static boolean readPacket(FileInputStream in, byte[] packet) throws IOException {
        int offset = 0;
        while (offset < packet.length) {
            int read = in.read(packet, offset, packet.length - offset);
            if (read < 0) return false;
            offset += read;
        }
        return true;
    }

    private static int parsePmtPid(byte[] packet) {
        if ((packet[1] & 0x40) == 0) return -1;
        int adaptationControl = (packet[3] >>> 4) & 0x03;
        if (adaptationControl == 0 || adaptationControl == 2) return -1;
        int offset = 4;
        if (adaptationControl == 3) {
            offset += 1 + (packet[offset] & 0xff);
        }
        if (offset >= packet.length) return -1;
        offset += 1 + (packet[offset] & 0xff);
        if (offset + 8 >= packet.length || (packet[offset] & 0xff) != 0x00) return -1;
        int sectionLength = ((packet[offset + 1] & 0x0f) << 8) | (packet[offset + 2] & 0xff);
        int entriesEnd = Math.min(packet.length, offset + 3 + sectionLength - 4);
        for (int entry = offset + 8; entry + 4 <= entriesEnd; entry += 4) {
            int program = ((packet[entry] & 0xff) << 8) | (packet[entry + 1] & 0xff);
            if (program == 0) continue;
            return ((packet[entry + 2] & 0x1f) << 8) | (packet[entry + 3] & 0xff);
        }
        return -1;
    }

    private static int findTrack(MediaExtractor extractor, String prefix, String preferredMime) {
        int fallback = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith(prefix)) continue;
            if (fallback < 0) fallback = i;
            if (mime.equals(preferredMime)) return i;
        }
        return fallback;
    }

    private static long smoothStep(long current, long measured) {
        return (current * 7L + measured) / 8L;
    }

    private static void validateOutput(File output, long writtenDurationUs) {
        if (!output.isFile() || output.length() < 4096) {
            throw new IllegalStateException("복원된 MP4 파일이 비어 있습니다.");
        }
        if (writtenDurationUs < 1_000_000L) {
            throw new IllegalStateException("완성된 MP4의 재생시간이 올바르지 않습니다.");
        }
    }

    private static final class TrackFormats {
        MediaFormat video;
        MediaFormat audio;
        String videoMime;
        String audioMime;
        int maxInputSize;
    }
}
