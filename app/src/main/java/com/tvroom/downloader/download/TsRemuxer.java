package com.tvroom.downloader.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

final class TsRemuxer {
    interface Progress { void update(int completed, int total); }

    private static final long UNSET = Long.MIN_VALUE;
    private static final long DEFAULT_VIDEO_STEP_US = 33_333L;
    private static final long DEFAULT_AUDIO_STEP_US = 21_333L;

    private TsRemuxer() { }

    /**
     * Opens each HLS segment separately. MediaExtractor often stops at a program or timestamp
     * discontinuity when hundreds of MPEG-TS files are byte-concatenated. Giving every segment
     * its own extractor and placing all samples on one timeline preserves duration and audio.
     */
    static void remux(List<File> segments, File output, Progress progress) throws Exception {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalStateException("복원할 영상 조각이 없습니다.");
        }

        TrackFormats formats = findTrackFormats(segments);
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
        int audioSegments = 0;
        int processedSegments = 0;

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

            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(segments.get(segmentIndex).getAbsolutePath());
                    int videoSource = findTrack(extractor, "video/", formats.videoMime);
                    int audioSource = formats.audio == null
                            ? -1 : findTrack(extractor, "audio/", formats.audioMime);
                    if (videoSource < 0) {
                        throw new IllegalStateException(
                                "영상 조각 " + (segmentIndex + 1) + "에서 비디오 트랙을 읽지 못했습니다.");
                    }
                    extractor.selectTrack(videoSource);
                    if (audioSource >= 0) extractor.selectTrack(audioSource);

                    long firstTimeUs = extractor.getSampleTime();
                    if (firstTimeUs < 0) {
                        throw new IllegalStateException(
                                "영상 조각 " + (segmentIndex + 1) + "이 비어 있습니다.");
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
                                "영상 조각 " + (segmentIndex + 1) + "의 비디오 데이터가 비어 있습니다.");
                    }
                    long tailStepUs = segmentAudioSamples > 0
                            ? Math.max(videoStepUs, audioStepUs) : videoStepUs;
                    segmentBaseUs += segmentMaxRelativeUs + Math.max(1L, tailStepUs);
                    if (segmentAudioSamples > 0) audioSegments++;
                    processedSegments++;
                    if (progress != null) progress.update(processedSegments, segments.size());
                } finally {
                    extractor.release();
                }
            }

            if (processedSegments != segments.size() || videoSamples == 0) {
                throw new IllegalStateException("모든 영상 조각을 MP4로 복원하지 못했습니다.");
            }
            if (formats.audio != null && audioSamples == 0) {
                throw new IllegalStateException("오디오 트랙을 MP4로 복원하지 못했습니다.");
            }
            if (audioSegments * 10 < processedSegments * 9) {
                throw new IllegalStateException("일부 영상 조각의 오디오를 읽지 못해 복원을 중단했습니다.");
            }

            muxer.stop();
            started = false;
            muxer.release();
            muxer = null;

            long writtenDurationUs = Math.max(videoLastUs == UNSET ? 0L : videoLastUs,
                    audioLastUs == UNSET ? 0L : audioLastUs);
            validateOutput(output, formats.audio != null, writtenDurationUs);
        } finally {
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

    private static TrackFormats findTrackFormats(List<File> segments) throws Exception {
        TrackFormats result = new TrackFormats();
        for (File segment : segments) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(segment.getAbsolutePath());
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
            } finally {
                extractor.release();
            }
            if (result.video != null && result.audio != null) break;
        }
        return result;
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

    private static void validateOutput(File output, boolean expectedAudio,
                                       long writtenDurationUs) throws Exception {
        if (!output.isFile() || output.length() < 4096) {
            throw new IllegalStateException("복원된 MP4 파일이 비어 있습니다.");
        }
        MediaExtractor check = new MediaExtractor();
        try {
            check.setDataSource(output.getAbsolutePath());
            boolean hasVideo = false;
            boolean hasAudio = false;
            long reportedDurationUs = 0L;
            for (int i = 0; i < check.getTrackCount(); i++) {
                MediaFormat format = check.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) hasVideo = true;
                if (mime != null && mime.startsWith("audio/")) hasAudio = true;
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    reportedDurationUs = Math.max(reportedDurationUs,
                            format.getLong(MediaFormat.KEY_DURATION));
                }
            }
            if (!hasVideo) throw new IllegalStateException("완성된 MP4에 비디오 트랙이 없습니다.");
            if (expectedAudio && !hasAudio) {
                throw new IllegalStateException("완성된 MP4에 오디오 트랙이 없습니다.");
            }
            if (writtenDurationUs < 1_000_000L) {
                throw new IllegalStateException("완성된 MP4의 재생시간이 올바르지 않습니다.");
            }
            if (reportedDurationUs > 0 && reportedDurationUs + 2_000_000L
                    < writtenDurationUs * 9L / 10L) {
                throw new IllegalStateException("MP4 재생시간 복원에 실패했습니다.");
            }
        } finally {
            check.release();
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
