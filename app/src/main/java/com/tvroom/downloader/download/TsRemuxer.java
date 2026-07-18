package com.tvroom.downloader.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class TsRemuxer {
    private static final long UNSET = Long.MIN_VALUE;

    private TsRemuxer() { }

    static void remux(File input, File output) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean expectedVideo = false;
        boolean expectedAudio = false;
        long longestOutputTimeUs = 0;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            List<Integer> sourceTracks = new ArrayList<>();
            List<Integer> targetTracks = new ArrayList<>();
            List<String> mimeTypes = new ArrayList<>();
            int maxInput = 4 * 1024 * 1024;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || (!mime.startsWith("video/") && !mime.startsWith("audio/"))) continue;
                expectedVideo |= mime.startsWith("video/");
                expectedAudio |= mime.startsWith("audio/");
                sourceTracks.add(i);
                targetTracks.add(muxer.addTrack(format));
                mimeTypes.add(mime);
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInput = Math.max(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
                extractor.selectTrack(i);
            }
            if (!expectedVideo || sourceTracks.isEmpty()) {
                throw new IllegalStateException("복원된 파일에서 영상 트랙을 찾지 못했습니다.");
            }

            int trackCount = sourceTracks.size();
            long[] lastInputTimeUs = new long[trackCount];
            long[] lastOutputTimeUs = new long[trackCount];
            long[] timeOffsetUs = new long[trackCount];
            long[] sampleStepUs = new long[trackCount];
            long[] samplesWritten = new long[trackCount];
            Arrays.fill(lastInputTimeUs, UNSET);
            Arrays.fill(lastOutputTimeUs, UNSET);
            for (int i = 0; i < trackCount; i++) {
                sampleStepUs[i] = mimeTypes.get(i).startsWith("audio/") ? 21_333L : 33_333L;
            }

            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocateDirect(maxInput);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int sourceTrack = extractor.getSampleTrackIndex();
                if (sourceTrack < 0) break;
                int mapping = sourceTracks.indexOf(sourceTrack);
                if (mapping < 0) {
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

                if (lastInputTimeUs[mapping] == UNSET) {
                    timeOffsetUs[mapping] = -inputTimeUs;
                } else {
                    long inputDeltaUs = inputTimeUs - lastInputTimeUs[mapping];
                    if (inputDeltaUs > 0 && inputDeltaUs < 1_000_000L) {
                        sampleStepUs[mapping] = (sampleStepUs[mapping] * 7L + inputDeltaUs) / 8L;
                    }
                    long resetToleranceUs = Math.max(250_000L, sampleStepUs[mapping] * 4L);
                    if (inputTimeUs + resetToleranceUs < lastInputTimeUs[mapping]) {
                        timeOffsetUs[mapping] = lastOutputTimeUs[mapping] +
                                sampleStepUs[mapping] - inputTimeUs;
                    }
                }

                long outputTimeUs = inputTimeUs + timeOffsetUs[mapping];
                if (lastOutputTimeUs[mapping] != UNSET && outputTimeUs <= lastOutputTimeUs[mapping]) {
                    outputTimeUs = lastOutputTimeUs[mapping] + Math.max(1L, sampleStepUs[mapping]);
                }

                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = outputTimeUs;
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(targetTracks.get(mapping), buffer, info);
                lastInputTimeUs[mapping] = inputTimeUs;
                lastOutputTimeUs[mapping] = outputTimeUs;
                samplesWritten[mapping]++;
                longestOutputTimeUs = Math.max(longestOutputTimeUs, outputTimeUs);
                extractor.advance();
            }

            for (int i = 0; i < trackCount; i++) {
                if (samplesWritten[i] == 0) {
                    throw new IllegalStateException(mimeTypes.get(i).startsWith("audio/")
                            ? "오디오 트랙을 복원하지 못했습니다."
                            : "영상 트랙을 복원하지 못했습니다.");
                }
            }
            muxer.stop();
            muxer.release();
            muxer = null;

            if (!output.isFile() || output.length() < 4096) {
                throw new IllegalStateException("복원된 MP4 파일이 비어 있습니다.");
            }
            validateOutput(output, expectedVideo, expectedAudio, longestOutputTimeUs);
        } finally {
            extractor.release();
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) { }
            }
        }
    }

    private static void validateOutput(File output, boolean expectedVideo, boolean expectedAudio,
                                       long writtenDurationUs) throws Exception {
        MediaExtractor check = new MediaExtractor();
        try {
            check.setDataSource(output.getAbsolutePath());
            boolean hasVideo = false;
            boolean hasAudio = false;
            long reportedDurationUs = 0;
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
            if (expectedVideo && !hasVideo) {
                throw new IllegalStateException("완성된 MP4에 영상 트랙이 없습니다.");
            }
            if (expectedAudio && !hasAudio) {
                throw new IllegalStateException("완성된 MP4에 오디오 트랙이 없습니다.");
            }
            long durationUs = Math.max(reportedDurationUs, writtenDurationUs);
            if (durationUs < 1_000_000L) {
                throw new IllegalStateException("완성된 MP4의 재생시간이 올바르지 않습니다.");
            }
            if (reportedDurationUs > 0 && writtenDurationUs > 10_000_000L &&
                    reportedDurationUs + 2_000_000L < writtenDurationUs * 9L / 10L) {
                throw new IllegalStateException("MP4 재생시간 복원에 실패했습니다.");
            }
        } finally {
            check.release();
        }
    }
}
