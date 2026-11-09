package com.webshell.shell;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Foreground service that polls a server endpoint and turns the answer into notifications.
 *
 * Expected JSON (array, or an object with a "notifications" array):
 * [{"id":"42","title":"Hello","text":"New message","url":"/chat/7"}]
 * "{lastId}" inside the poll URL is replaced by the id of the last notification already shown.
 */
public class PollService extends Service {
    private static final int FG_ID = 7001;
    private static final String PREFS = "poll";

    private HandlerThread ht;
    private Handler h;
    private AppConfig cfg;
    private SharedPreferences sp;
    private PowerManager.WakeLock wl;
    private volatile boolean stopped;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            try {
                if (wl != null) wl.acquire(40000);
                poll();
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (wl != null && wl.isHeld()) wl.release();
                } catch (Exception ignored) {
                }
            }
            if (!stopped) h.postDelayed(this, cfg.pollSec * 1000L);
        }
    };

    static boolean enabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("enabled", true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
    }

    static void start(Context c) {
        try {
            Intent i = new Intent(c, PollService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, PollService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        cfg = AppConfig.load(this);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Notifier.ensureChannel(this, Notifier.CH_BG, cfg.name + " (background)", NotificationManager.IMPORTANCE_MIN);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, Notifier.CH_BG) : new Notification.Builder(this);
        nb.setContentTitle(cfg.name)
                .setContentText(cfg.bgNotifText)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true)
                .setContentIntent(Notifier.openIntent(this, null, 0));
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_ID, nb.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FG_ID, nb.build());
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (cfg.pollUrl.isEmpty()) return START_STICKY; // keep-alive only (e.g. page-driven work)

        if (ht == null) {
            ht = new HandlerThread("poll");
            ht.start();
            h = new Handler(ht.getLooper());
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":poll");
            } catch (Exception ignored) {
            }
        }
        stopped = false;
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        if (h != null) h.removeCallbacksAndMessages(null);
        if (ht != null) ht.quitSafely();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ polling

    private void poll() throws Exception {
        String last = sp.getString("last", "");
        String u = AppConfig.resolve(cfg.url, cfg.pollUrl.replace("{lastId}", Uri.encode(last)));
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        try {
            if (cfg.ignoreSsl && c instanceof HttpsURLConnection) trustAll((HttpsURLConnection) c);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/json");
            String cookie = null;
            try {
                cookie = CookieManager.getInstance().getCookie(u);
            } catch (Throwable ignored) {
            }
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            if (c.getResponseCode() != 200) return;

            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 512 * 1024) return;
                }
            }
            String body = bo.toString("UTF-8").trim();
            JSONArray arr;
            if (body.startsWith("[")) {
                arr = new JSONArray(body);
            } else {
                JSONObject o = new JSONObject(body);
                arr = o.optJSONArray("notifications");
            }
            if (arr == null) return;
            handle(arr);
        } finally {
            c.disconnect();
        }
    }

    private void handle(JSONArray arr) {
        Set<String> seen = new HashSet<>(sp.getStringSet("seen", new HashSet<>()));
        String lastId = null;
        int shown = 0;
        for (int i = 0; i < arr.length() && shown < 10; i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String title = it.optString("title", cfg.name);
            String text = it.optString("text", it.optString("body", it.optString("message", "")));
            String url = it.optString("url", "");
            String id = it.has("id") ? String.valueOf(it.opt("id")) : String.valueOf((title + "|" + text).hashCode());
            lastId = id;
            if (text.isEmpty() && title.isEmpty()) continue;
            if (!seen.add(id)) continue;
            Notifier.show(this, cfg, title, text, AppConfig.resolve(cfg.url, url));
            shown++;
        }
        if (seen.size() > 300) {
            Set<String> trimmed = new HashSet<>();
            int skip = seen.size() - 200;
            for (String s : seen) if (skip-- <= 0) trimmed.add(s);
            seen = trimmed;
        }
        SharedPreferences.Editor ed = sp.edit().putStringSet("seen", seen);
        if (lastId != null) ed.putString("last", lastId);
        ed.apply();
    }

    private static void trustAll(HttpsURLConnection c) {
        try {
            TrustManager[] tm = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier((host, session) -> true);
        } catch (Exception ignored) {
        }
    }
}
