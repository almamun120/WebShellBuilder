package com.webshell.shell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.SmsManager;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;

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
}
