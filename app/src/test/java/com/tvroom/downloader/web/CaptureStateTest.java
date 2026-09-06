package com.tvroom.downloader.web;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class CaptureStateTest {
    @Test public void hlsDoesNotRequireRecognizedSegmentSuffix() {
        CaptureState state = new CaptureState();
        state.reset("https://tvroom31.org/video/movie/main");
        state.rememberRequest("https://cdn.example/index0.aaa", Collections.singletonMap("Referer", "https://player.example/"));
        assertFalse(state.ready());
        state.rememberRequest("https://cdn.example/stream/m3u8/token", Collections.singletonMap("Referer", "https://player.example/"));
        assertTrue(state.ready());
    }
    @Test public void manifestAloneIsDownloadableIncludingNativeHls() {
        CaptureState state = new CaptureState();
        state.rememberUrl("https://cdn.example/index.m3u8");
        assertTrue(state.ready());
        state.reset("https://tvroom31.org/video/other/main");
        assertFalse(state.ready());
    }
    @Test public void rawCustomSegmentsStillRequireKey() {
        CaptureState state = new CaptureState();
        state.rememberRequest("https://cdn.example/segment_list_0.png", Collections.singletonMap("Referer", "https://player.example/"));
        assertFalse(state.ready());
        state.acceptMessage("{\"type\":\"crypto\",\"key\":\"000102030405060708090a0b0c0d0e0f\"}");
        assertTrue(state.ready());
        state.reset("https://tvroom31.org/video/next/main");
        assertFalse(state.ready());
    }
    @Test public void sessionAndManifestSurviveQueueSerialization() throws Exception {
        CaptureState state = new CaptureState();
        state.setSession("https://tvroom31.org/video/movie/main", "", "test-agent");
        state.acceptMessage("{\"type\":\"meta\",\"title\":\"movie\"}");
        state.acceptMessage("{\"type\":\"url\",\"url\":\"https://cdn.example/index.m3u8\",\"referer\":\"https://player.example/\"}");
        CaptureState.Snapshot copy = CaptureState.Snapshot.fromJson(state.snapshot().toJson());
        assertEquals("movie", copy.title);
        assertEquals("https://player.example/", copy.streamReferers.get(copy.m3u8Urls.get(0)));
    }
}
