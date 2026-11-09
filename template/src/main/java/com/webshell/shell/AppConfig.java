package com.webshell.shell;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    // --- extra features ---
    boolean pullRefresh = true;
    /** system | light | dark */
    String theme = "system";
    boolean forceDarkWeb = false;
    /** none | device (biometric / PIN / pattern) */
    String lock = "none";
    int lockTimeoutSec = 30;
    String lockTitle = "অ্যাপ লক করা আছে";
    String unlockLabel = "আনলক করুন";
    boolean offlineCache = false;
    boolean shareTarget = false;
    boolean deepLinks = false;
    /** none | bottom */
    String navMode = "none";
    final List<String[]> navItems = new ArrayList<>();      // {title, url}
    final List<String[]> shortcuts = new ArrayList<>();     // {title, url}
    boolean bgService = false;
    boolean bgOnBoot = false;
    String pollUrl = "";
    int pollSec = 60;
    String bgNotifText = "ব্যাকগ্রাউন্ডে চলছে";
    String scanHint = "QR / বারকোড ফ্রেমের ভেতরে আনুন";

    // --- splash layout: logo | fullFit | fullCrop | fullStretch ---
    String splashMode = "logo";
    int splashSize = 180;
    boolean splashSpinner = true;

    // --- printing and built-in media player ---
    boolean printing = false;
    boolean mediaPlayer = false;
    final java.util.Set<String> mediaExt = new java.util.HashSet<>();
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
            a.pullRefresh = o.optBoolean("pullRefresh", true);
            a.theme = o.optString("theme", "system");
            a.forceDarkWeb = o.optBoolean("forceDarkWeb", false);
            a.lock = o.optString("lock", "none");
            a.lockTimeoutSec = Math.max(0, o.optInt("lockTimeoutSec", 30));
            a.lockTitle = o.optString("lockTitle", a.lockTitle);
            a.unlockLabel = o.optString("unlockLabel", a.unlockLabel);
            a.offlineCache = o.optBoolean("offlineCache", false);
            a.shareTarget = o.optBoolean("shareTarget", false);
            a.deepLinks = o.optBoolean("deepLinks", false);
            a.navMode = o.optString("navMode", "none");
            readPairs(o.optJSONArray("navItems"), a.navItems, a.url);
            readPairs(o.optJSONArray("shortcuts"), a.shortcuts, a.url);
            a.bgService = o.optBoolean("bgService", false);
            a.bgOnBoot = o.optBoolean("bgOnBoot", false);
            a.pollUrl = o.optString("pollUrl", "");
            a.pollSec = Math.max(15, o.optInt("pollSec", 60));
            a.bgNotifText = o.optString("bgNotifText", a.bgNotifText);
            a.scanHint = o.optString("scanHint", a.scanHint);
            a.splashMode = o.optString("splashMode", "logo");
            a.splashSize = Math.max(40, Math.min(600, o.optInt("splashSize", 180)));
            a.splashSpinner = o.optBoolean("splashSpinner", true);
            a.printing = o.optBoolean("printing", false);
            a.mediaPlayer = o.optBoolean("mediaPlayer", false);
            JSONArray me = o.optJSONArray("mediaExt");
            if (me != null) {
                for (int i = 0; i < me.length(); i++) {
                    String e = me.optString(i, "").trim().toLowerCase();
                    if (e.startsWith(".")) e = e.substring(1);
                    if (!e.isEmpty()) a.mediaExt.add(e);
                }
            }
            JSONArray p = o.optJSONArray("permissions");
            if (p != null) for (int i = 0; i < p.length(); i++) a.perms.add(p.getString(i));
        } catch (Exception ignored) {
            // fall back to defaults
        }
        a.host = hostOf(a.url);
        return a;
    }

    private static void readPairs(JSONArray arr, List<String[]> out, String base) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String title = it.optString("title", "").trim();
            String url = resolve(base, it.optString("url", "").trim());
            if (!title.isEmpty() && !url.isEmpty()) out.add(new String[]{title, url});
        }
    }

    /** Resolves a possibly relative URL against the main server URL. */
    static String resolve(String base, String u) {
        if (u == null || u.isEmpty()) return "";
        if (u.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) return u;
        try {
            return new java.net.URI(base).resolve(u).toString();
        } catch (Exception e) {
            return u;
        }
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
