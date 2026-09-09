package com.tvroom.downloader.data;

import android.content.SharedPreferences;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class PlaylistTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private VideoItem video(String id, String work, String episode) throws Exception {
        File media = folder.newFile(id + ".mp4"); Files.write(media.toPath(), new byte[]{1, 2, 3});
        return new VideoItem(id, work + " " + episode, "https://tvroom31.org/video/" + work + "/" + episode,
                null, media.getAbsolutePath(), "complete", 100, "");
    }
    private SharedPreferences memoryPreferences() {
        Map<String, Object> values = new HashMap<>();
        Object[] editor = new Object[1];
        editor[0] = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (p, method, args) -> {
            if (method.getName().startsWith("put")) { values.put((String) args[0], args[1]); return editor[0]; }
            if (method.getName().equals("remove")) { values.remove((String) args[0]); return editor[0]; }
            if (method.getName().equals("commit")) return true;
            return null;
        });
        return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (p, method, args) -> {
            if (method.getName().equals("edit")) return editor[0];
            if (method.getName().startsWith("get")) return values.getOrDefault(args[0], args[1]);
            return null;
        });
    }
    @Test public void automaticOneThroughTenIsOneNumericPlaylist() throws Exception {
        List<VideoItem> videos = new ArrayList<>();
        for (int i = 10; i >= 1; i--) videos.add(video("a" + i, "A", i + "화"));
        VideoPlaylist group = VideoPlaylist.automatic(videos).get(0);
        assertEquals("A 1~10화", group.label());
        assertEquals("a1", group.videos.get(0).id); assertEquals("a10", group.videos.get(9).id);
        assertEquals(1, VideoPlaylist.automatic(videos).size());
    }
    @Test public void gapsAreNotPresentedAsCompleteRanges() throws Exception {
        VideoPlaylist group = VideoPlaylist.automatic(Arrays.asList(video("a", "A", "1화"), video("b", "A", "3화"), video("c", "A", "4화"))).get(0);
        assertEquals("A 1, 3~4화", group.label());
    }
    @Test public void workPathSeparatesSeasonsAndIgnoresChangingHost() throws Exception {
        VideoItem original = video("a", "A-시즌1", "1화");
        VideoItem newerHost = new VideoItem("b", "A 2화", "https://tvroom99.org/video/A-%EC%8B%9C%EC%A6%8C1/2%ED%99%94?x=1", null, original.filePath, "complete", 100, "");
        assertEquals(VideoPlaylist.workKey(original), VideoPlaylist.workKey(newerHost));
        assertEquals(2, VideoPlaylist.automatic(Arrays.asList(original, newerHost, video("c", "A-시즌2", "1화"))).size());
    }
    @Test public void datedEpisodeAndSpecialAreSortedCorrectly() throws Exception {
        VideoItem dated = new VideoItem("a", "A 제14회 260906", "https://tvroom31.org/video/A/14", null, null, "complete", 100, "");
        VideoItem special = video("s", "A", "특별편");
        assertEquals(14, VideoPlaylist.episode(dated));
        assertEquals(-1, VideoPlaylist.episode(special));
        assertEquals("a", VideoPlaylist.automatic(Arrays.asList(special, dated)).get(0).videos.get(0).id);
    }
    @Test public void manualOrderPersistsAndDeletingNeverDeletesVideos() throws Exception {
        SharedPreferences prefs = memoryPreferences(); PlaylistStore store = new PlaylistStore(prefs);
        VideoItem a = video("a", "A", "1화"), b = video("b", "B", "1화");
        store.save(null, "내 목록", Arrays.asList(b, a, b));
        List<VideoPlaylist> lists = new PlaylistStore(prefs).list(Arrays.asList(a, b));
        VideoPlaylist manual = lists.get(lists.size() - 1);
        assertFalse(manual.automatic); assertEquals(2, manual.videos.size()); assertEquals("b", manual.videos.get(0).id);
        store.save(manual.id, "바꾼 이름", Arrays.asList(a, b));
        lists = store.list(Arrays.asList(a, b)); manual = lists.get(lists.size() - 1);
        assertEquals("바꾼 이름", manual.title); assertEquals("a", manual.videos.get(0).id);
        store.delete(manual);
        assertEquals(2, store.list(Arrays.asList(a, b)).size());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(a.filePath).toPath()));
        assertTrue(new File(b.filePath).isFile());
    }
    @Test public void automaticExclusionsPersistAndCanBeRestored() throws Exception {
        SharedPreferences prefs = memoryPreferences(); PlaylistStore store = new PlaylistStore(prefs);
        VideoItem a = video("a", "A", "1화"), b = video("b", "A", "2화"); List<VideoItem> library = Arrays.asList(a, b);
        store.exclude(a); assertEquals("b", new PlaylistStore(prefs).list(library).get(0).videos.get(0).id);
        store.delete(store.list(library).get(0)); assertTrue(new PlaylistStore(prefs).list(library).isEmpty());
        store.restoreAutomatic(); assertEquals(2, store.list(library).get(0).videos.size());
        assertTrue(new File(a.filePath).exists()); assertTrue(new File(b.filePath).exists());
    }
    @Test public void unavailableVideosAreNotQueuedAndManualListRemainsEditable() throws Exception {
        PlaylistStore store = new PlaylistStore(memoryPreferences()); VideoItem a = video("a", "A", "1화");
        store.save(null, "내 목록", Collections.singletonList(a));
        assertTrue(new File(a.filePath).delete());
        List<VideoPlaylist> lists = store.list(Collections.singletonList(a));
        assertEquals(1, lists.size()); assertFalse(lists.get(0).automatic); assertTrue(lists.get(0).videos.isEmpty());
    }
    @Test public void viewModePersists() {
        SharedPreferences prefs = memoryPreferences(); PlaylistStore store = new PlaylistStore(prefs);
        assertFalse(store.isPlaylistMode()); store.setPlaylistMode(true); assertTrue(new PlaylistStore(prefs).isPlaylistMode());
    }
}
