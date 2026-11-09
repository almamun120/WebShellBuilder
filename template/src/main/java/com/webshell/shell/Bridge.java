package com.webshell.shell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JavaScript interface, available to the web page as window.Android (and window.WebShell).
 * Only calls coming from a page on the configured server host are honoured.
 */
public class Bridge {
    private final MainActivity a;

    Bridge(MainActivity a) {
        this.a = a;
    }

    private boolean ok() {
        return a.trusted();
    }

    @android.webkit.JavascriptInterface
    public void toast(String msg) {
        if (!ok()) return;
        a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_SHORT).show());
    }

    @android.webkit.JavascriptInterface
    public boolean hasPermission(String key) {
        return ok() && a.granted(key);
    }

    @android.webkit.JavascriptInterface
    public void requestPermission(String key) {
        if (!ok()) return;
        a.runOnUiThread(() -> a.ensureKey(key, () -> {}, () -> {}));
    }

    /** Direct call if the "call" permission is enabled and granted, otherwise opens the dialer. */
    @android.webkit.JavascriptInterface
    public void call(String number) {
        if (!ok()) return;
        a.runOnUiThread(() -> {
            Uri u = Uri.parse("tel:" + Uri.encode(number));
            if (a.has("call")) {
                a.ensureKey("call",
                        () -> a.launch(new Intent(Intent.ACTION_CALL, u)),
                        () -> a.launch(new Intent(Intent.ACTION_DIAL, u)));
            } else {
                a.launch(new Intent(Intent.ACTION_DIAL, u));
            }
        });
    }

    @android.webkit.JavascriptInterface
    public void dial(String number) {
        if (!ok()) return;
        a.runOnUiThread(() -> a.launch(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))));
    }

    /** Opens the SMS composer with prefilled text. */
    @android.webkit.JavascriptInterface
    public void sms(String number, String body) {
        if (!ok()) return;
        a.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
            i.putExtra("sms_body", body);
            a.launch(i);
        });
    }

    /** Sends an SMS directly (needs the "sms" permission). */
    @android.webkit.JavascriptInterface
    public void sendSms(String number, String body) {
        if (!ok() || !a.has("sms")) return;
        a.runOnUiThread(() -> a.ensureKey("sms", () -> {
            try {
                SmsManager sm = Build.VERSION.SDK_INT >= 31
                        ? a.getSystemService(SmsManager.class) : SmsManager.getDefault();
                ArrayList<String> parts = sm.divideMessage(body);
                sm.sendMultipartTextMessage(number, null, parts, null, null);
                Toast.makeText(a, "SMS sent", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(a, "SMS failed", Toast.LENGTH_SHORT).show();
            }
        }, () -> {}));
    }

    @android.webkit.JavascriptInterface
    public void mail(String to, String subject, String body) {
        if (!ok()) return;
        a.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
            i.putExtra(Intent.EXTRA_EMAIL, new String[]{to});
            i.putExtra(Intent.EXTRA_SUBJECT, subject);
            i.putExtra(Intent.EXTRA_TEXT, body);
            a.launch(i);
        });
    }

    /** Opens another app by package name, or any URI/scheme (whatsapp://, market://, geo:...). */
    @android.webkit.JavascriptInterface
    public boolean openApp(String target) {
        if (!ok() || target == null) return false;
        try {
            Intent i;
            if (target.contains(":")) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            } else {
                i = a.getPackageManager().getLaunchIntentForPackage(target);
                if (i == null) return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @android.webkit.JavascriptInterface
    public void openUrl(String url) {
        if (!ok()) return;
        a.runOnUiThread(() -> a.launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
    }

    @android.webkit.JavascriptInterface
    public void share(String text) {
        if (!ok()) return;
        a.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            a.launch(Intent.createChooser(i, null));
        });
    }

    @android.webkit.JavascriptInterface
    public void vibrate(int ms) {
        if (!ok()) return;
        try {
            Vibrator v = (Vibrator) a.getSystemService(Context.VIBRATOR_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Exception ignored) {
        }
    }

    @android.webkit.JavascriptInterface
    public void copy(String text) {
        if (!ok()) return;
        a.runOnUiThread(() -> {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("text", text));
        });
    }

    @android.webkit.JavascriptInterface
    public void notify(String title, String text) {
        if (!ok()) return;
        a.runOnUiThread(() -> a.showNotification(title, text));
    }

    @android.webkit.JavascriptInterface
    public void flashlight(boolean on) {
        if (!ok()) return;
        try {
            CameraManager cm = (CameraManager) a.getSystemService(Context.CAMERA_SERVICE);
            cm.setTorchMode(cm.getCameraIdList()[0], on);
        } catch (Exception ignored) {
        }
    }

    @android.webkit.JavascriptInterface
    public void keepScreenOn(boolean on) {
        if (!ok()) return;
        a.runOnUiThread(() -> {
            if (on) a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    @android.webkit.JavascriptInterface
    public void setOrientation(String mode) {
        if (!ok()) return;
        a.runOnUiThread(() -> a.setRequestedOrientation(
                "portrait".equals(mode) ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : "landscape".equals(mode) ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
    }

    @android.webkit.JavascriptInterface
    public void openSettings() {
        if (!ok()) return;
        a.runOnUiThread(() -> a.launch(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.getPackageName()))));
    }

    @android.webkit.JavascriptInterface
    public void reload() {
        if (!ok()) return;
        a.runOnUiThread(() -> a.web.reload());
    }

    @android.webkit.JavascriptInterface
    public void exitApp() {
        if (!ok()) return;
        a.runOnUiThread(a::finish);
    }

    @android.webkit.JavascriptInterface
    public String getDeviceInfo() {
        if (!ok()) return "{}";
        try {
            JSONObject o = new JSONObject();
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("model", Build.MODEL);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("package", a.getPackageName());
            o.put("online", a.isOnline());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ------------------------------------------------------------------ files

    /** Saves a file to Downloads. {@code base64} may be plain base64 or a data: URL. */
    @android.webkit.JavascriptInterface
    public void saveFile(String name, String mime, String base64) {
        if (!ok()) return;
        try {
            byte[] data = decode(base64);
            a.saveBytes(name, mime, data);
        } catch (Exception e) {
            a.downloadFailed();
        }
    }

    /** Opens the share sheet with a file. {@code base64} may be plain base64 or a data: URL. */
    @android.webkit.JavascriptInterface
    public void shareFile(String name, String mime, String base64) {
        if (!ok()) return;
        try {
            byte[] data = decode(base64);
            a.runOnUiThread(() -> {
                try {
                    FileSaver.share(a, name, mime, data);
                } catch (Exception e) {
                    a.downloadFailed();
                }
            });
        } catch (Exception e) {
            a.downloadFailed();
        }
    }

    private static byte[] decode(String s) {
        if (s == null) return new byte[0];
        int comma = s.startsWith("data:") ? s.indexOf(',') : -1;
        return Base64.decode(comma >= 0 ? s.substring(comma + 1) : s, Base64.DEFAULT);
    }

    // ------------------------------------------------------------------ device info

    /** JSON array: [{"name":"..","numbers":[".."]}]. Needs the "contacts" permission (else "[]"). */
    @android.webkit.JavascriptInterface
    public String getContacts() {
        if (!ok() || !a.granted("contacts")) return "[]";
        Map<String, JSONArray> map = new LinkedHashMap<>();
        try (Cursor c = a.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
            int rows = 0;
            while (c != null && c.moveToNext() && rows++ < 5000) {
                String name = c.getString(0);
                String num = c.getString(1);
                if (name == null || num == null) continue;
                JSONArray arr = map.get(name);
                if (arr == null) {
                    arr = new JSONArray();
                    map.put(name, arr);
                }
                arr.put(num);
            }
        } catch (Exception ignored) {
        }
        JSONArray out = new JSONArray();
        try {
            for (Map.Entry<String, JSONArray> e : map.entrySet()) {
                out.put(new JSONObject().put("name", e.getKey()).put("numbers", e.getValue()));
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    @android.webkit.JavascriptInterface
    public String getClipboard() {
        if (!ok()) return "";
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return "";
            CharSequence t = d.getItemAt(0).coerceToText(a);
            return t == null ? "" : t.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** JSON: {"level":0-100,"charging":true|false}. */
    @android.webkit.JavascriptInterface
    public String getBattery() {
        if (!ok()) return "{}";
        try {
            Intent i = a.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            JSONObject o = new JSONObject();
            o.put("level", level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1);
            o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** "wifi" | "cellular" | "ethernet" | "vpn" | "other" | "none". */
    @android.webkit.JavascriptInterface
    public String getNetworkType() {
        if (!ok()) return "none";
        try {
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return "none";
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null) return "none";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
            return "other";
        } catch (Exception e) {
            return "none";
        }
    }

    @android.webkit.JavascriptInterface
    public boolean isDarkMode() {
        return ok() && a.isNight();
    }

    // ------------------------------------------------------------------ UI

    /** Colour like "#1E88E5". */
    @android.webkit.JavascriptInterface
    public void setStatusBarColor(String hex) {
        if (!ok()) return;
        try {
            int c = android.graphics.Color.parseColor(hex);
            a.runOnUiThread(() -> a.setStatusColor(c));
        } catch (Exception ignored) {
        }
    }

    /** Enable/disable pull-to-refresh for the current page (e.g. pages with their own scroll areas). */
    @android.webkit.JavascriptInterface
    public void setPullRefresh(boolean on) {
        if (!ok()) return;
        a.runOnUiThread(() -> a.pull.setPullEnabled(on));
    }

    // ------------------------------------------------------------------ scanner / auth

    /** Opens the camera scanner. The result arrives in window.onQRResult(text, format) (text = null if cancelled). */
    @android.webkit.JavascriptInterface
    public void scanQR() {
        if (!ok()) return;
        a.runOnUiThread(a::startScan);
    }

    /** Fingerprint / face / PIN prompt. The result arrives in window.onBiometricResult(success, reason). */
    @android.webkit.JavascriptInterface
    public void biometricAuth(String title) {
        if (!ok()) return;
        a.runOnUiThread(() -> a.pageAuth(title));
    }

    // ------------------------------------------------------------------ background service

    @android.webkit.JavascriptInterface
    public void startBackground() {
        if (!ok()) return;
        a.runOnUiThread(() -> a.setBackground(true));
    }

    @android.webkit.JavascriptInterface
    public void stopBackground() {
        if (!ok()) return;
        a.runOnUiThread(() -> a.setBackground(false));
    }

    /** Opens the system screen where the user can exempt the app from battery optimisation. */
    @android.webkit.JavascriptInterface
    public void openBatterySettings() {
        if (!ok()) return;
        a.runOnUiThread(() -> a.launch(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
    }

    /** Opens the Android print dialog for the current page (needs the printing option in the build). */
    @android.webkit.JavascriptInterface
    public void print() {
        if (!ok()) return;
        a.printPage(null);
    }
}
