package com.webshell.shell;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that keeps audio playing when the app is in the background and shows the
 * media notification (play / pause / previous / next / close). The player itself lives in
 * {@link MediaPlayerView}; this service only provides the notification and keeps the process alive.
 */
public class MediaService extends Service {
    private static final String CHANNEL = "media";
    private static final int NOTIF_ID = 7411;
    private static final String A_TOGGLE = "com.webshell.shell.MEDIA_TOGGLE";
    private static final String A_NEXT = "com.webshell.shell.MEDIA_NEXT";
    private static final String A_PREV = "com.webshell.shell.MEDIA_PREV";
    private static final String A_CLOSE = "com.webshell.shell.MEDIA_CLOSE";

    private static MediaService instance;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, MediaService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
            // background start not allowed or service missing: playback still works while the app is open
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, MediaService.class));
        } catch (Exception ignored) {
        }
    }

    /** Re-posts the notification (play state or title changed). */
    static void refresh() {
        MediaService s = instance;
        if (s != null) s.post();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaPlayerView v = MediaPlayerView.active;
        String a = intent == null ? null : intent.getAction();
        if (v == null) {
            // nothing to play any more: satisfy the foreground-start contract, then leave
            post();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (A_TOGGLE.equals(a)) v.togglePlay();
        else if (A_NEXT.equals(a)) v.next();
        else if (A_PREV.equals(a)) v.prev();
        else if (A_CLOSE.equals(a)) {
            v.requestClose();
            return START_NOT_STICKY;
        }
        post();
        return START_NOT_STICKY;
    }

    private PendingIntent action(String action, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(action);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(this, code, i, flags)
                : PendingIntent.getService(this, code, i, flags);
    }

    private Notification.Action act(int icon, String label, String action, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, action(action, code)).build();
    }

    private void post() {
        MediaPlayerView v = MediaPlayerView.active;
        boolean playing = v != null && v.isPlaying();
        String title = v == null ? "" : v.title();

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            Notifier.ensureChannel(this, CHANNEL, "Media", NotificationManager.IMPORTANCE_LOW);
            nb = new Notification.Builder(this, CHANNEL);
        } else {
            nb = new Notification.Builder(this);
        }
        nb.setSmallIcon(playing ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentTitle(title.isEmpty() ? "Media" : title)
                .setContentText(playing ? "▶" : "⏸")
                .setContentIntent(Notifier.openIntent(this, null, NOTIF_ID))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true);

        int n = 0;
        int compactPlay = 0;
        boolean prev = v != null && v.hasPrev();
        boolean next = v != null && v.hasNext();
        if (prev) {
            nb.addAction(act(android.R.drawable.ic_media_previous, "Previous", A_PREV, 1));
            n++;
        }
        compactPlay = n;
        nb.addAction(act(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Pause" : "Play", A_TOGGLE, 2));
        n++;
        if (next) {
            nb.addAction(act(android.R.drawable.ic_media_next, "Next", A_NEXT, 3));
            n++;
        }
        nb.addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "Close", A_CLOSE, 4));

        Notification.MediaStyle style = new Notification.MediaStyle();
        MediaSession.Token token = v == null ? null : v.sessionToken();
        if (token != null) style.setMediaSession(token);
        if (next) style.setShowActionsInCompactView(compactPlay, compactPlay + 1);
        else style.setShowActionsInCompactView(compactPlay);
        nb.setStyle(style);

        Notification notif = nb.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, notif);
            }
            if (!playing) stopForeground(false); // keep the notification, but let it be swiped away
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
