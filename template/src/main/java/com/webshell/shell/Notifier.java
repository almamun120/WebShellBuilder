package com.webshell.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Shared notification helpers for the activity and the background service. */
final class Notifier {
    static final String ACTION_OPEN = "com.webshell.shell.OPEN_URL";
    static final String EXTRA_URL = "open_url";
    static final String CH_APP = "app";
    static final String CH_BG = "bg";

    private Notifier() {}

    static void ensureChannel(Context c, String id, String name, int importance) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(id, name, importance));
    }

    static PendingIntent openIntent(Context c, String url, int code) {
        Intent open = new Intent(c, MainActivity.class)
                .setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (url != null && !url.isEmpty()) open.putExtra(EXTRA_URL, url);
        return PendingIntent.getActivity(c, code, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Shows a normal (dismissable) notification. {@code url} is optional and opened in the app on tap. */
    static void show(Context c, AppConfig cfg, String title, String text, String url) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            ensureChannel(c, CH_APP, cfg.name, NotificationManager.IMPORTANCE_DEFAULT);
            nb = new Notification.Builder(c, CH_APP);
        } else {
            nb = new Notification.Builder(c);
        }
        int id = (int) (System.nanoTime() & 0x7FFFFFFF);
        nb.setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .setContentIntent(openIntent(c, url, id));
        try {
            nm.notify(id, nb.build());
        } catch (Exception ignored) {
        }
    }
}
