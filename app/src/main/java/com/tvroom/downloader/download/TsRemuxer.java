package com.tvroom.downloader.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class TsRemuxer {
    private TsRemuxer() { }

    static void remux(File input, File output) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            List<Integer> sourceTracks = new ArrayList<>();
            List<Integer> targetTracks = new ArrayList<>();
            int maxInput = 4 * 1024 * 1024;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || (!mime.startsWith("video/") && !mime.startsWith("audio/"))) continue;
                sourceTracks.add(i);
                targetTracks.add(muxer.addTrack(format));
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInput = Math.max(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
                extractor.selectTrack(i);
            }
            if (sourceTracks.isEmpty()) throw new IllegalStateException("복원할 영상·오디오 트랙이 없습니다.");
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocateDirect(maxInput);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastTime = -1;
            while (true) {
                int sourceTrack = extractor.getSampleTrackIndex();
                if (sourceTrack < 0) break;
                int mapping = sourceTracks.indexOf(sourceTrack);
                if (mapping < 0) { extractor.advance(); continue; }
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = Math.max(extractor.getSampleTime(), lastTime + 1);
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(targetTracks.get(mapping), buffer, info);
                lastTime = Math.max(lastTime, info.presentationTimeUs);
                extractor.advance();
            }
            muxer.stop();
            muxer.release();
            muxer = null;
            if (!output.isFile() || output.length() < 4096) {
                throw new IllegalStateException("복원된 MP4 파일이 비어 있습니다.");
            }
        } finally {
            extractor.release();
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) { }
        }
    }
}
