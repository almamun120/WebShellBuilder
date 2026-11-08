package com.webshell.shell;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** Runtime configuration, read from assets/config.json (written by the builder). */
final class AppConfig {
    String url = "https://example.com";
    String name = "App";
    int splashBg = Color.WHITE;
    int accent = 0xFF3B82F6;
    int statusColor = 0;
    boolean splashImage = false;
    boolean splashName = true;
    int splashMs = 1500;
    String orientation = "auto";
    boolean fullscreen = false;
    boolean doubleBackExit = true;
    boolean keepScreenOn = false;
    boolean ignoreSsl = false;
    boolean desktop = false;
    boolean externalLinks = false;
    boolean jsBridge = true;
    boolean askOnStart = true;
    boolean zoom = true;
    String userAgentSuffix = "";
    String offlineTitle = "ইন্টারনেট সংযোগ নেই";
    String offlineMessage = "সংযোগ ফিরে এলে স্বয়ংক্রিয়ভাবে লোড হবে";
    String serverTitle = "সার্ভারের সাথে সংযোগ হচ্ছে না";
    String serverMessage = "সার্ভার চালু হলেই অ্যাপ খুলে যাবে";
    String retryLabel = "আবার চেষ্টা করুন";
    String exitMessage = "বের হতে আবার ব্যাক চাপুন";
    final Set<String> perms = new HashSet<>();
    String host = "";

    static AppConfig load(Context c) {
        AppConfig a = new AppConfig();
        try (InputStream in = c.getAssets().open("config.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            a.url = o.optString("url", a.url);
            a.name = o.optString("name", a.name);
            a.splashBg = color(o.optString("splashBg"), a.splashBg);
            a.accent = color(o.optString("accent"), a.accent);
            a.statusColor = color(o.optString("statusColor"), 0);
            a.splashImage = o.optBoolean("splashImage", false);
            a.splashName = o.optBoolean("splashName", true);
            a.splashMs = o.optInt("splashMs", 1500);
            a.orientation = o.optString("orientation", "auto");
            a.fullscreen = o.optBoolean("fullscreen", false);
            a.doubleBackExit = o.optBoolean("doubleBackExit", true);
            a.keepScreenOn = o.optBoolean("keepScreenOn", false);
            a.ignoreSsl = o.optBoolean("ignoreSsl", false);
            a.desktop = o.optBoolean("desktop", false);
            a.externalLinks = o.optBoolean("externalLinks", false);
            a.jsBridge = o.optBoolean("jsBridge", true);
            a.askOnStart = o.optBoolean("askOnStart", true);
            a.zoom = o.optBoolean("zoom", true);
            a.userAgentSuffix = o.optString("userAgentSuffix", "");
            a.offlineTitle = o.optString("offlineTitle", a.offlineTitle);
            a.offlineMessage = o.optString("offlineMessage", a.offlineMessage);
            a.serverTitle = o.optString("serverTitle", a.serverTitle);
            a.serverMessage = o.optString("serverMessage", a.serverMessage);
            a.retryLabel = o.optString("retryLabel", a.retryLabel);
            a.exitMessage = o.optString("exitMessage", a.exitMessage);
            JSONArray p = o.optJSONArray("permissions");
            if (p != null) for (int i = 0; i < p.length(); i++) a.perms.add(p.getString(i));
        } catch (Exception ignored) {
            // fall back to defaults
        }
        a.host = hostOf(a.url);
        return a;
    }

    static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return "";
            h = h.toLowerCase();
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }

    private static int color(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return def;
        }
    }
}
