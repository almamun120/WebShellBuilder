package com.webshell.shell;

import android.os.Build;

/** Maps permission keys (from config.json) to runtime permission names. */
final class Perms {
    private Perms() {}

    static String[] runtime(String key) {
        switch (key) {
            case "camera":
                return new String[]{"android.permission.CAMERA"};
            case "microphone":
                return new String[]{"android.permission.RECORD_AUDIO"};
            case "location":
                return new String[]{"android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_COARSE_LOCATION"};
            case "call":
                return new String[]{"android.permission.CALL_PHONE"};
            case "sms":
                return new String[]{"android.permission.SEND_SMS"};
            case "contacts":
                return new String[]{"android.permission.READ_CONTACTS"};
            case "storage":
                if (Build.VERSION.SDK_INT >= 33) {
                    return new String[]{"android.permission.READ_MEDIA_IMAGES",
                            "android.permission.READ_MEDIA_VIDEO",
                            "android.permission.READ_MEDIA_AUDIO"};
                } else if (Build.VERSION.SDK_INT >= 29) {
                    return new String[]{"android.permission.READ_EXTERNAL_STORAGE"};
                }
                return new String[]{"android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"};
            case "notifications":
                return Build.VERSION.SDK_INT >= 33
                        ? new String[]{"android.permission.POST_NOTIFICATIONS"}
                        : new String[0];
            case "bluetooth":
                return Build.VERSION.SDK_INT >= 31
                        ? new String[]{"android.permission.BLUETOOTH_CONNECT"}
                        : new String[0];
            default:
                return new String[0]; // vibrate, wakelock, apps: install-time only
        }
    }
}
