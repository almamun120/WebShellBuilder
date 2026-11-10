package com.webshell.shell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads .m3u / .m3u8 channel lists, .pls and .xspf playlists into a list of items. */
final class PlaylistParser {
    private PlaylistParser() {}

    /** True for file types that can hold a list of media URLs. */
    static boolean isPlaylistPath(String path) {
        if (path == null) return false;
        String p = path.toLowerCase();
        return p.endsWith(".m3u") || p.endsWith(".m3u8") || p.endsWith(".pls") || p.endsWith(".xspf");
    }

    /** Reads at most 1.5 MB of text from a downloaded file. */
    static String readText(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 1_500_000) sb.append(buf, 0, n);
        }
        if (sb.length() > 0 && sb.charAt(0) == '﻿') sb.deleteCharAt(0);
        return sb.toString();
    }

    /**
     * Parses {@code text}. Returns an empty list when the text is not a list of media URLs, which includes
     * HLS stream manifests (they contain #EXT-X- tags and must be handed to the player as they are).
     */
    static List<MediaPlayerView.Item> parse(String text, String baseUrl) {
        List<MediaPlayerView.Item> out = new ArrayList<>();
        if (text == null) return out;
        String t = text.trim();
        if (t.isEmpty()) return out;
        if (t.contains("#EXT-X-")) return out;
        if (t.regionMatches(true, 0, "[playlist]", 0, 10)) return parsePls(t, baseUrl);
        if (t.startsWith("<")) return parseXspf(t, baseUrl);
        return parseM3u(t, baseUrl);
    }

    private static List<MediaPlayerView.Item> parseM3u(String t, String base) {
        List<MediaPlayerView.Item> out = new ArrayList<>();
        String title = null;
        for (String raw : t.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int c = line.lastIndexOf(',');
                title = c >= 0 && c < line.length() - 1 ? line.substring(c + 1).trim() : null;
                if (title == null || title.isEmpty()) {
                    Matcher m = Pattern.compile("tvg-name=\"([^\"]*)\"").matcher(line);
                    if (m.find()) title = m.group(1);
                }
                continue;
            }
            if (line.startsWith("#")) continue;
            add(out, line, title, base);
            title = null;
        }
        return out;
    }

    private static List<MediaPlayerView.Item> parsePls(String t, String base) {
        List<MediaPlayerView.Item> out = new ArrayList<>();
        java.util.TreeMap<Integer, String[]> map = new java.util.TreeMap<>();
        Pattern p = Pattern.compile("^(File|Title)(\\d+)\\s*=\\s*(.*)$", Pattern.CASE_INSENSITIVE);
        for (String raw : t.split("\\r?\\n")) {
            Matcher m = p.matcher(raw.trim());
            if (!m.matches()) continue;
            int n = Integer.parseInt(m.group(2));
            String[] e = map.get(n);
            if (e == null) {
                e = new String[2];
                map.put(n, e);
            }
            e["file".equalsIgnoreCase(m.group(1)) ? 0 : 1] = m.group(3).trim();
        }
        for (String[] e : map.values()) if (e[0] != null) add(out, e[0], e[1], base);
        return out;
    }

    private static List<MediaPlayerView.Item> parseXspf(String t, String base) {
        List<MediaPlayerView.Item> out = new ArrayList<>();
        Matcher tracks = Pattern.compile("<track>(.*?)</track>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(t);
        while (tracks.find()) {
            String body = tracks.group(1);
            String loc = tag(body, "location");
            if (loc == null) continue;
            add(out, loc, tag(body, "title"), base);
        }
        return out;
    }

    private static String tag(String body, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(body);
        if (!m.find()) return null;
        String v = m.group(1).trim();
        if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) v = v.substring(9, v.length() - 3);
        return v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
    }

    private static void add(List<MediaPlayerView.Item> out, String ref, String title, String base) {
        String url = ref;
        if (!ref.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            try {
                url = new URI(base).resolve(ref.replace(" ", "%20")).toString();
            } catch (Exception e) {
                return;
            }
        }
        if (!url.matches("(?i)^https?://.*")) return;
        if (title == null || title.isEmpty()) title = android.webkit.URLUtil.guessFileName(url, null, null);
        out.add(new MediaPlayerView.Item(url, title));
    }
}
