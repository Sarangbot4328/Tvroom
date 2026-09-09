package com.tvroom.downloader.data;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure grouping logic: metadata only; never changes downloaded files. */
public final class VideoPlaylist {
    private static final Pattern EPISODE = Pattern.compile("(?:제\\s*)?(\\d{1,5})\\s*(?:회차|화|회)|(?:episode|ep)\\s*\\.?\\s*(\\d{1,5})", Pattern.CASE_INSENSITIVE);
    public final String id, title;
    public final boolean automatic;
    public final List<VideoItem> videos;

    public VideoPlaylist(String id, String title, boolean automatic, List<VideoItem> videos) {
        this.id = id; this.title = title; this.automatic = automatic;
        this.videos = Collections.unmodifiableList(new ArrayList<>(videos));
    }

    private static String[] parts(VideoItem item) {
        try {
            String path = new URI(item.pageUrl).getPath();
            return path == null ? new String[0] : path.replaceAll("^/+|/+$", "").split("/");
        } catch (Exception error) { return new String[0]; }
    }

    public static String workKey(VideoItem item) {
        String[] path = parts(item);
        return path.length == 3 && "video".equals(path[0]) ? "auto:" + path[1] : "single:" + item.id;
    }

    public static int episode(VideoItem item) {
        String[] path = parts(item);
        // Prefer the episode path: the work title may itself contain a number followed by 화/회.
        String label = path.length == 3 ? path[2] : "";
        Matcher match = EPISODE.matcher(label);
        if (match.find()) return Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2));
        if (label.matches("\\d{1,5}")) return Integer.parseInt(label);
        match = EPISODE.matcher(item.title == null ? "" : item.title);
        int number = -1;
        while (match.find()) number = Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2));
        return number;
    }

    public static List<VideoPlaylist> automatic(List<VideoItem> videos) {
        Map<String, List<VideoItem>> groups = new LinkedHashMap<>();
        for (VideoItem video : videos) groups.computeIfAbsent(workKey(video), key -> new ArrayList<>()).add(video);
        List<VideoPlaylist> result = new ArrayList<>();
        for (Map.Entry<String, List<VideoItem>> entry : groups.entrySet()) {
            List<VideoItem> items = entry.getValue();
            items.sort(Comparator.comparingInt((VideoItem item) -> episode(item) < 0 ? Integer.MAX_VALUE : episode(item))
                    .thenComparing(item -> item.title == null ? "" : item.title).thenComparing(item -> item.id));
            String[] path = parts(items.get(0));
            String title = entry.getKey().startsWith("auto:") ? path[1].replace('-', ' ').replace('_', ' ') : items.get(0).title;
            result.add(new VideoPlaylist(entry.getKey(), title, true, items));
        }
        return result;
    }

    public String label() {
        if (!automatic) return title;
        TreeSet<Integer> numbers = new TreeSet<>();
        int specials = 0;
        for (VideoItem video : videos) {
            int n = episode(video);
            if (n >= 0) numbers.add(n); else specials++;
        }
        if (numbers.isEmpty()) return title;
        List<String> ranges = new ArrayList<>();
        int start = -1, last = -1;
        for (int number : numbers) {
            if (start < 0) start = number;
            else if (number != last + 1) { ranges.add(start == last ? "" + start : start + "~" + last); start = number; }
            last = number;
        }
        ranges.add(start == last ? "" + start : start + "~" + last);
        return title + " " + String.join(", ", ranges) + "화" + (specials > 0 ? " · 특별편 " + specials + "개" : "");
    }
}
