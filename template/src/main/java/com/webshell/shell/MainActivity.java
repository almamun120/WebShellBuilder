package com.webshell.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 9001;
    private static final int REQ_CAPTURE = 9002;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    AppConfig cfg;
    WebView web;
    private WebView popup;
    private FrameLayout root;
    private ProgressBar progress;
    private View offlineView;
    private View splashView;
    private TextView offTitle;
    private TextView offMsg;
    private WebChromeClient chrome;
    PullRefreshLayout pull;
    private LinearLayout navBar;
    private final List<TextView> navButtons = new ArrayList<>();
    private View lockView;
    private ScannerView scanner;
    private BlobBridge blob;
    private MediaPlayerView media;
    private boolean locked;
    private boolean authBusy;
    private boolean promptOnResume;
    private boolean internalLaunch;
    private long stoppedAt;
    private int statusNow;
    private String pendingShare;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean pageDone;
    private boolean splashTimeUp;
    private boolean splashGone;
    private boolean mainError;
    private String failedUrl;
    volatile String currentUrl = "";
    private long lastBack;

    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private ValueCallback<Uri[]> fileCb;
    private Uri captureUri;

    private final SparseArray<Runnable[]> permCbs = new SparseArray<>();
    private int permCounter = 100;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;

    private final Runnable autoRetry = new Runnable() {
        @Override
        public void run() {
            if (offlineView.getVisibility() == View.VISIBLE) {
                retry();
                ui.postDelayed(this, 5000);
            }
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void attachBaseContext(Context base) {
        cfg = AppConfig.load(base);
        if ("dark".equals(cfg.theme) || "light".equals(cfg.theme)) {
            // Force the night/day mode for this activity so the WebView's prefers-color-scheme follows it.
            Configuration conf = new Configuration(base.getResources().getConfiguration());
            conf.uiMode = (conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | ("dark".equals(cfg.theme) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            base = base.createConfigurationContext(conf);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (cfg == null) cfg = AppConfig.load(this);
        statusNow = cfg.statusColor;
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        if ("portrait".equals(cfg.orientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if ("landscape".equals(cfg.orientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
        if (cfg.keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (cfg.statusColor != 0) getWindow().setStatusBarColor(cfg.statusColor);

        buildUi();
        setupWeb();
        applySystemUi(false);
        registerNetwork();
        if (cfg.askOnStart) requestStartPermissions();
        setupShortcuts();
        if (cfg.bgService && PollService.enabled(this)) PollService.start(this);
        if (isShare(getIntent())) prepareShare(getIntent());
        if (locked) promptOnResume = true;

        ui.postDelayed(() -> {
            splashTimeUp = true;
            tryHideSplash();
        }, cfg.splashMs);
        ui.postDelayed(this::hideSplashNow, 15000);

        if (b == null || web.restoreState(b) == null) loadMain();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        boolean internal = internalLaunch;
        internalLaunch = false;
        if (lockView != null && !locked && !internal && stoppedAt > 0
                && SystemClock.elapsedRealtime() - stoppedAt >= cfg.lockTimeoutSec * 1000L) {
            lockNow();
        }
        stoppedAt = 0;
        if (locked && promptOnResume && !authBusy) {
            promptOnResume = false;
            ui.postDelayed(this::unlockAttempt, 250);
        }
        if (offlineView.getVisibility() == View.VISIBLE) retry();
    }

    @Override
    protected void onPause() {
        if (scanner != null) scanner.dismiss();
        if (media != null) media.pause();
        CookieManager.getInstance().flush();
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        stoppedAt = SystemClock.elapsedRealtime();
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String u = urlFromIntent(i);
        if (u != null) {
            hideOffline();
            applyCacheMode();
            web.loadUrl(u);
        }
        if (isShare(i)) prepareShare(i);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (!"system".equals(cfg.theme)) return; // forced theme: unaffected by the system setting
        boolean night = isNight();
        applyDark(web.getSettings());
        applyChromeColors();
        js("(function(d){window.dispatchEvent(new CustomEvent('androidtheme',{detail:{dark:d}}));"
                + "if(typeof window.onThemeChanged==='function')window.onThemeChanged(d)})(" + night + ")");
    }

    @Override
    public void startActivityForResult(Intent i, int req, Bundle opts) {
        internalLaunch = true; // our own picker/camera/credential screen: do not re-lock on return
        try {
            super.startActivityForResult(i, req, opts);
        } catch (RuntimeException e) {
            internalLaunch = false;
            throw e;
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (netCb != null) {
            try {
                cm.unregisterNetworkCallback(netCb);
            } catch (Exception ignored) {
            }
        }
        if (popup != null) {
            popup.destroy();
            popup = null;
        }
        removeScanner();
        closeMedia();
        pull.removeView(web);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemUi(customView != null || media != null);
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean isDark(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) < 140;
    }

    @SuppressWarnings("deprecation")
    private void applySystemUi(boolean forceFull) {
        int flags = View.SYSTEM_UI_FLAG_VISIBLE;
        boolean splashFull = !splashGone && cfg.splashImage && cfg.splashMode.startsWith("full");
        if (cfg.fullscreen || forceFull || splashFull) {
            flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        } else if (statusNow != 0 && !isDark(statusNow)
                && Build.VERSION.SDK_INT >= 23) {
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    void setStatusColor(int color) {
        statusNow = color;
        getWindow().setStatusBarColor(color);
        applySystemUi(customView != null);
    }

    private void applyChromeColors() {
        root.setBackgroundColor(isNight() ? 0xFF121212 : Color.WHITE);
        if (navBar != null) {
            navBar.setBackgroundColor(isNight() ? 0xFF1E1E1E : Color.WHITE);
            updateNavSelection(currentUrl);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyDark(WebSettings s) {
        boolean force = cfg.forceDarkWeb && isNight();
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(force);
        } else if (Build.VERSION.SDK_INT >= 29) {
            s.setForceDark(force ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(isNight() ? 0xFF121212 : Color.WHITE);
        web = new WebView(this);
        pull = new PullRefreshLayout(this, web, cfg.accent);
        pull.setPullEnabled(cfg.pullRefresh);
        root.addView(pull, new FrameLayout.LayoutParams(MATCH, MATCH));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(cfg.accent));
        progress.setVisibility(View.GONE);
        root.addView(progress, new FrameLayout.LayoutParams(MATCH, dp(3), Gravity.TOP));

        offlineView = buildOffline();
        offlineView.setVisibility(View.GONE);
        root.addView(offlineView, new FrameLayout.LayoutParams(MATCH, MATCH));

        splashView = buildSplash();
        root.addView(splashView, new FrameLayout.LayoutParams(MATCH, MATCH));

        if (!"none".equals(cfg.lock)) {
            lockView = buildLock();
            locked = true;
            root.addView(lockView, new FrameLayout.LayoutParams(MATCH, MATCH));
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(root, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        if ("bottom".equals(cfg.navMode) && !cfg.navItems.isEmpty()) {
            navBar = buildNav();
            navBar.setVisibility(View.GONE);
            content.addView(navBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        setContentView(content);
    }

    // ------------------------------------------------------------------ bottom navigation

    private LinearLayout buildNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(isNight() ? 0xFF1E1E1E : Color.WHITE);
        bar.setElevation(dp(8));
        int max = Math.min(5, cfg.navItems.size());
        for (int i = 0; i < max; i++) {
            final String[] it = cfg.navItems.get(i);
            TextView t = new TextView(this);
            t.setText(it[0]);
            t.setTextSize(12);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(4), 0, dp(4), 0);
            t.setOnClickListener(v -> {
                hideOffline();
                applyCacheMode();
                web.loadUrl(it[1]);
            });
            bar.addView(t, new LinearLayout.LayoutParams(0, dp(54), 1f));
            navButtons.add(t);
        }
        updateNavSelection("");
        return bar;
    }

    private static String normUrl(String u) {
        if (u == null) return "";
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.toLowerCase();
    }

    private void updateNavSelection(String cur) {
        if (navBar == null) return;
        String c = normUrl(cur);
        int best = -1;
        int bestLen = -1;
        for (int i = 0; i < navButtons.size(); i++) {
            String n = normUrl(cfg.navItems.get(i)[1]);
            if ((c.equals(n) || c.startsWith(n + "/")) && n.length() > bestLen) {
                best = i;
                bestLen = n.length();
            }
        }
        int off = isNight() ? 0xFFA0A7B4 : 0xFF667085;
        for (int i = 0; i < navButtons.size(); i++) {
            TextView t = navButtons.get(i);
            t.setTextColor(i == best ? cfg.accent : off);
            t.setTypeface(i == best ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void updateNavVisibility() {
        if (navBar == null) return;
        boolean show = splashGone && !locked && customView == null && media == null;
        navBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ app lock

    private View buildLock() {
        int fg = isDark(cfg.splashBg) ? Color.WHITE : 0xFF1F2933;
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(cfg.splashBg);
        f.setClickable(true);

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(getPackageManager().getApplicationIcon(getApplicationInfo()));
        l.addView(iv, new LinearLayout.LayoutParams(dp(88), dp(88)));

        TextView name = new TextView(this);
        name.setText(cfg.name);
        name.setTextColor(fg);
        name.setTextSize(20);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(WRAP, WRAP);
        np.topMargin = dp(14);
        l.addView(name, np);

        TextView msg = new TextView(this);
        msg.setText(cfg.lockTitle);
        msg.setTextColor((fg & 0x00FFFFFF) | 0xB3000000);
        msg.setTextSize(14);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(WRAP, WRAP);
        mp.topMargin = dp(6);
        l.addView(msg, mp);

        Button btn = new Button(this);
        btn.setText(cfg.unlockLabel);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cfg.accent);
        bg.setCornerRadius(dp(24));
        btn.setBackground(bg);
        btn.setPadding(dp(28), 0, dp(28), 0);
        btn.setOnClickListener(v -> unlockAttempt());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(WRAP, dp(46));
        bp.topMargin = dp(26);
        l.addView(btn, bp);

        f.addView(l, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
        return f;
    }

    private void lockNow() {
        if (lockView == null) return;
        locked = true;
        promptOnResume = true;
        lockView.animate().cancel();
        lockView.setAlpha(1f);
        lockView.setVisibility(View.VISIBLE);
        updateNavVisibility();
    }

    private void unlockAttempt() {
        if (!locked || authBusy) return;
        authBusy = true;
        AppLock.authenticate(this, cfg.lockTitle, new AppLock.Result() {
            @Override
            public void ok() {
                authBusy = false;
                unlock();
            }

            @Override
            public void denied() {
                authBusy = false;
            }

            @Override
            public void unavailable() {
                authBusy = false;
                Toast.makeText(MainActivity.this, "ফোনে স্ক্রিন লক সেট নেই, তাই অ্যাপ লক কাজ করছে না", Toast.LENGTH_LONG).show();
                unlock();
            }
        });
    }

    private void unlock() {
        locked = false;
        lockView.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> lockView.setVisibility(View.GONE)).start();
        updateNavVisibility();
    }

    /** Biometric / PIN prompt requested by the page (Android.biometricAuth). */
    void pageAuth(String title) {
        String t = title == null || title.isEmpty() ? cfg.lockTitle : title;
        AppLock.authenticate(this, t, new AppLock.Result() {
            @Override
            public void ok() {
                js("window.onBiometricResult&&window.onBiometricResult(true,'ok')");
            }

            @Override
            public void denied() {
                js("window.onBiometricResult&&window.onBiometricResult(false,'denied')");
            }

            @Override
            public void unavailable() {
                js("window.onBiometricResult&&window.onBiometricResult(false,'unavailable')");
            }
        });
    }

    // ------------------------------------------------------------------ QR / barcode scanner

    void startScan() {
        if (scanner != null) return;
        ensureKey("camera", this::showScanner, () -> {
            Toast.makeText(this, "ক্যামেরা পারমিশন দরকার", Toast.LENGTH_SHORT).show();
            js("window.onQRResult&&window.onQRResult(null,'')");
        });
    }

    private void showScanner() {
        if (scanner != null) return;
        scanner = new ScannerView(this, cfg.accent, cfg.scanHint, new ScannerView.Callback() {
            @Override
            public void onResult(String text, String format) {
                removeScanner();
                js("window.onQRResult&&window.onQRResult(" + JSONObject.quote(text) + "," + JSONObject.quote(format) + ")");
            }

            @Override
            public void onCancel() {
                removeScanner();
                js("window.onQRResult&&window.onQRResult(null,'')");
            }
        });
        root.addView(scanner, new FrameLayout.LayoutParams(MATCH, MATCH));
        scanner.start();
    }

    private void removeScanner() {
        if (scanner == null) return;
        ScannerView s = scanner;
        scanner = null;
        s.stop();
        root.removeView(s);
    }

    // ------------------------------------------------------------------ background service

    void setBackground(boolean on) {
        if (!cfg.bgService) return;
        PollService.setEnabled(this, on);
        if (on) PollService.start(this);
        else PollService.stop(this);
    }

    // ------------------------------------------------------------------ JS helper

    /** Runs a script in the current page, only if the page belongs to the configured server. */
    void js(String code) {
        runOnUiThread(() -> {
            if (trusted()) web.evaluateJavascript(code, null);
        });
    }

    // ------------------------------------------------------------------ intents: deep links, shortcuts, share

    /** URL requested by a deep link, notification tap or shortcut (same server only), else null. */
    private String urlFromIntent(Intent i) {
        if (i == null) return null;
        String u = i.getStringExtra(Notifier.EXTRA_URL);
        if (u == null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) u = i.getDataString();
        if (u == null) return null;
        Uri p = Uri.parse(u);
        String sch = p.getScheme();
        if (sch == null || !(sch.equalsIgnoreCase("http") || sch.equalsIgnoreCase("https"))) return null;
        if (p.getHost() == null || !sameSite(p)) return null;
        return u;
    }

    private void setupShortcuts() {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null) return;
            List<ShortcutInfo> list = new ArrayList<>();
            for (String[] s : cfg.shortcuts) {
                if (list.size() >= 4) break;
                Intent i = new Intent(this, MainActivity.class)
                        .setAction(Notifier.ACTION_OPEN).putExtra(Notifier.EXTRA_URL, s[1]);
                list.add(new ShortcutInfo.Builder(this, "sc" + list.size())
                        .setShortLabel(s[0]).setLongLabel(s[0])
                        .setIcon(Icon.createWithResource(this, getApplicationInfo().icon))
                        .setIntent(i).build());
            }
            sm.setDynamicShortcuts(list);
        } catch (Exception ignored) {
        }
    }

    private boolean isShare(Intent i) {
        return cfg.shareTarget && i != null
                && (Intent.ACTION_SEND.equals(i.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(i.getAction()));
    }

    @SuppressWarnings("deprecation")
    private void prepareShare(Intent i) {
        final String text = i.getStringExtra(Intent.EXTRA_TEXT);
        final String subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
        final List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject();
                if (text != null) o.put("text", text);
                if (subject != null) o.put("subject", subject);
                JSONArray files = new JSONArray();
                for (Uri u : uris) {
                    if (files.length() >= 5) break;
                    String type = getContentResolver().getType(u);
                    if (type == null) type = "application/octet-stream";
                    JSONObject f = new JSONObject();
                    f.put("name", displayName(u));
                    f.put("type", type);
                    byte[] data = readLimited(u, 6 * 1024 * 1024);
                    if (data == null) f.put("error", "too_large");
                    else f.put("dataUrl", "data:" + type + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP));
                    files.put(f);
                }
                o.put("files", files);
                ui.post(() -> {
                    pendingShare = o.toString();
                    dispatchShareIfReady();
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private String displayName(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private byte[] readLimited(Uri u, int max) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) return null;
            }
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Hands shared text/files to the page: window.__androidShare + the "androidshare" event. */
    private void dispatchShareIfReady() {
        if (pendingShare == null || web.getProgress() < 100 || !trusted()) return;
        String json = pendingShare;
        pendingShare = null;
        web.evaluateJavascript("(function(d){window.__androidShare=d;try{window.dispatchEvent("
                + "new CustomEvent('androidshare',{detail:d}))}catch(e){}"
                + "if(typeof window.onAndroidShare==='function')window.onAndroidShare(d)})(" + json + ")", null);
    }

    private View buildOffline() {
        int fg = isDark(cfg.splashBg) ? Color.WHITE : 0xFF1F2933;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(cfg.splashBg);
        l.setClickable(true);
        l.setPadding(dp(32), dp(32), dp(32), dp(32));

        l.addView(new PulseView(this, cfg.accent), new LinearLayout.LayoutParams(dp(180), dp(180)));

        offTitle = new TextView(this);
        offTitle.setTextColor(fg);
        offTitle.setTextSize(20);
        offTitle.setTypeface(Typeface.DEFAULT_BOLD);
        offTitle.setGravity(Gravity.CENTER);
        offTitle.setText(cfg.offlineTitle);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(WRAP, WRAP);
        tp.topMargin = dp(16);
        l.addView(offTitle, tp);

        offMsg = new TextView(this);
        offMsg.setTextColor((fg & 0x00FFFFFF) | 0xB3000000);
        offMsg.setTextSize(14);
        offMsg.setGravity(Gravity.CENTER);
        offMsg.setText(cfg.offlineMessage);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(WRAP, WRAP);
        mp.topMargin = dp(8);
        l.addView(offMsg, mp);

        Button btn = new Button(this);
        btn.setText(cfg.retryLabel);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cfg.accent);
        bg.setCornerRadius(dp(24));
        btn.setBackground(bg);
        btn.setPadding(dp(28), 0, dp(28), 0);
        btn.setOnClickListener(v -> retry());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(WRAP, dp(46));
        bp.topMargin = dp(24);
        l.addView(btn, bp);
        return l;
    }

    private View buildSplash() {
        int fg = isDark(cfg.splashBg) ? Color.WHITE : 0xFF1F2933;
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(cfg.splashBg);
        f.setClickable(true);

        Bitmap bmp = null;
        if (cfg.splashImage) {
            try (InputStream in = getAssets().open("splash.png")) {
                bmp = BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
            }
        }
        boolean full = bmp != null && cfg.splashMode.startsWith("full");

        if (full) {
            // the picture covers the whole screen; name / spinner sit at the bottom
            ImageView iv = new ImageView(this);
            iv.setScaleType("fullCrop".equals(cfg.splashMode) ? ImageView.ScaleType.CENTER_CROP
                    : "fullStretch".equals(cfg.splashMode) ? ImageView.ScaleType.FIT_XY
                    : ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(bmp);
            f.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(0, 0, 0, dp(48));
            if (cfg.splashName) {
                TextView t = new TextView(this);
                t.setText(cfg.name);
                t.setTextColor(Color.WHITE);
                t.setShadowLayer(8, 0, 2, 0xCC000000);
                t.setTextSize(20);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setGravity(Gravity.CENTER);
                col.addView(t, new LinearLayout.LayoutParams(WRAP, WRAP));
            }
            if (cfg.splashSpinner) {
                ProgressBar pb = new ProgressBar(this);
                pb.setIndeterminateTintList(ColorStateList.valueOf(cfg.accent));
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(30), dp(30));
                pp.topMargin = dp(14);
                col.addView(pb, pp);
            }
            f.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
            return f;
        }

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (bmp != null) iv.setImageBitmap(bmp);
        else iv.setImageDrawable(getPackageManager().getApplicationIcon(getApplicationInfo()));
        l.addView(iv, new LinearLayout.LayoutParams(dp(cfg.splashSize), dp(cfg.splashSize)));

        if (cfg.splashName) {
            TextView t = new TextView(this);
            t.setText(cfg.name);
            t.setTextColor(fg);
            t.setTextSize(22);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.topMargin = dp(16);
            l.addView(t, lp);
        }

        if (cfg.splashSpinner) {
            ProgressBar pb = new ProgressBar(this);
            pb.setIndeterminateTintList(ColorStateList.valueOf(cfg.accent));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(30), dp(30));
            pp.topMargin = dp(28);
            l.addView(pb, pp);
        }

        f.addView(l, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
        return f;
    }

    private void tryHideSplash() {
        if (splashGone || !splashTimeUp || !(pageDone || mainError)) return;
        hideSplashNow();
    }

    private void hideSplashNow() {
        if (splashGone) return;
        splashGone = true;
        splashView.animate().alpha(0f).setDuration(250)
                .withEndAction(() -> {
                    splashView.setVisibility(View.GONE);
                    applySystemUi(customView != null || media != null);
                    updateNavVisibility();
                }).start();
    }

    // ------------------------------------------------------------------ offline handling

    boolean isOnline() {
        try {
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    private void registerNetwork() {
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                ui.post(MainActivity.this::applyCacheMode);
                ui.postDelayed(() -> {
                    if (offlineView.getVisibility() == View.VISIBLE) retry();
                }, 800);
            }

            @Override
            public void onLost(Network network) {
                ui.postDelayed(MainActivity.this::applyCacheMode, 500);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Exception e) {
            netCb = null;
        }
    }

    /** With "offline cache" on, pages are served from the WebView cache whenever there is no network. */
    private void applyCacheMode() {
        if (!cfg.offlineCache) return;
        web.getSettings().setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
    }

    private void loadMain() {
        String u = urlFromIntent(getIntent());
        if (u == null) u = cfg.url;
        if (!isOnline() && !cfg.offlineCache) {
            failedUrl = u;
            mainError = true;
            showOffline();
        } else {
            applyCacheMode();
            web.loadUrl(u);
        }
    }

    private void showOffline() {
        boolean online = isOnline();
        offTitle.setText(online ? cfg.serverTitle : cfg.offlineTitle);
        offMsg.setText(online ? cfg.serverMessage : cfg.offlineMessage);
        offlineView.setVisibility(View.VISIBLE);
        ui.removeCallbacks(autoRetry);
        ui.postDelayed(autoRetry, 5000);
        tryHideSplash();
    }

    private void hideOffline() {
        offlineView.setVisibility(View.GONE);
        ui.removeCallbacks(autoRetry);
    }

    private void retry() {
        if (failedUrl == null) failedUrl = cfg.url;
        int p = web.getProgress();
        if (p > 10 && p < 100) return; // a load is already running
        if (!isOnline() && !cfg.offlineCache) {
            showOffline();
            return;
        }
        applyCacheMode();
        web.loadUrl(failedUrl);
    }

    // ------------------------------------------------------------------ WebView

    private void configure(WebSettings s) {
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(cfg.zoom);
        s.setBuiltInZoomControls(cfg.zoom);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setGeolocationEnabled(true);
        applyDark(s);
        String ua = s.getUserAgentString();
        if (cfg.desktop) {
            Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(ua);
            String ver = m.find() ? m.group(1) : "120.0.0.0";
            ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                    + ver + " Safari/537.36";
        }
        if (!cfg.userAgentSuffix.isEmpty()) ua = ua + " " + cfg.userAgentSuffix;
        s.setUserAgentString(ua);
    }

    @SuppressWarnings("deprecation")
    private void setupWeb() {
        configure(web.getSettings());
        CookieManager cmgr = CookieManager.getInstance();
        cmgr.setAcceptCookie(true);
        cmgr.setAcceptThirdPartyCookies(web, true);
        if (cfg.jsBridge) {
            Bridge br = new Bridge(this);
            web.addJavascriptInterface(br, "Android");
            web.addJavascriptInterface(br, "WebShell");
        }
        if (cfg.printing) web.addJavascriptInterface(new PrintBridge(), "WebShellPrint");
        blob = new BlobBridge(this);
        web.addJavascriptInterface(blob, "WebShellDl"); // token-protected, used only for blob: downloads
        web.setWebViewClient(new Client());
        chrome = new Chrome();
        web.setWebChromeClient(chrome);
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (cfg.mediaPlayer && !url.startsWith("blob:") && !url.startsWith("data:")) {
                String fn = URLUtil.guessFileName(url, cd, mime);
                if (isMediaUrl(url) || cfg.mediaExt.contains(extOf(fn))) {
                    openMedia(url, fn);
                    return;
                }
            }
            startDownload(url, ua, cd, mime);
        });
        pull.setOnRefreshListener(() -> {
            if (mainError || offlineView.getVisibility() == View.VISIBLE) retry();
            else web.reload();
        });
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return handleUrl(r.getUrl().toString(), true);
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap favicon) {
            mainError = false;
            currentUrl = url;
            injectPrint();
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            currentUrl = url;
            CookieManager.getInstance().flush();
            if (!mainError) hideOffline();
            pageDone = true;
            pull.setRefreshing(false);
            injectPrint();
            updateNavSelection(url);
            dispatchShareIfReady();
            tryHideSplash();
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame()) return;
            if (String.valueOf(e.getDescription()).contains("ERR_ABORTED")) return;
            mainError = true;
            failedUrl = r.getUrl().toString();
            pull.setRefreshing(false);
            showOffline();
        }

        @Override
        public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
            if (cfg.ignoreSsl) h.proceed();
            else h.cancel();
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            recreate();
            return true;
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView v, int p) {
            progress.setProgress(p);
            progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            if (p >= 40) injectPrint();
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            boolean wantsCamera = p.isCaptureEnabled() && acceptsImage(p.getAcceptTypes());
            if (wantsCamera && cfg.perms.contains("camera") && Build.VERSION.SDK_INT >= 29) {
                ensureKey("camera", MainActivity.this::launchCapture, () -> launchPicker(p));
            } else {
                launchPicker(p);
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest req) {
            runOnUiThread(() -> {
                List<String> grant = new ArrayList<>();
                Set<String> need = new LinkedHashSet<>();
                for (String r : req.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && cfg.perms.contains("camera")) {
                        grant.add(r);
                        need.add("android.permission.CAMERA");
                    } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && cfg.perms.contains("microphone")) {
                        grant.add(r);
                        need.add("android.permission.RECORD_AUDIO");
                    } else if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                        grant.add(r);
                    }
                }
                if (grant.isEmpty()) {
                    req.deny();
                    return;
                }
                ensure(need.toArray(new String[0]),
                        () -> req.grant(grant.toArray(new String[0])),
                        req::deny);
            });
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            if (!cfg.perms.contains("location")) {
                cb.invoke(origin, false, false);
                return;
            }
            ensureKey("location", () -> cb.invoke(origin, true, false), () -> cb.invoke(origin, false, false));
        }

        @Override
        public void onShowCustomView(View v, CustomViewCallback cb) {
            if (customView != null) {
                cb.onCustomViewHidden();
                return;
            }
            customView = v;
            customCb = cb;
            v.setBackgroundColor(Color.BLACK);
            root.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
            web.setVisibility(View.INVISIBLE);
            applySystemUi(true);
            updateNavVisibility();
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            if (customCb != null) customCb.onCustomViewHidden();
            web.setVisibility(View.VISIBLE);
            applySystemUi(false);
            updateNavVisibility();
        }

        @Override
        public boolean onCreateWindow(WebView v, boolean isDialog, boolean gesture, Message msg) {
            WebView w = new WebView(MainActivity.this);
            configure(w.getSettings());
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
            w.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView x, WebResourceRequest r) {
                    return handleUrl(r.getUrl().toString(), false);
                }

                @Override
                public void onReceivedSslError(WebView x, SslErrorHandler h, SslError e) {
                    if (cfg.ignoreSsl) h.proceed();
                    else h.cancel();
                }
            });
            w.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onCloseWindow(WebView x) {
                    closePopup();
                }
            });
            closePopup();
            popup = w;
            root.addView(w, new FrameLayout.LayoutParams(MATCH, MATCH));
            ((WebView.WebViewTransport) msg.obj).setWebView(w);
            msg.sendToTarget();
            return true;
        }

        @Override
        public boolean onJsAlert(WebView v, String url, String message, JsResult r) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(hostTitle(url))
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView v, String url, String message, JsResult r) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(hostTitle(url))
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView v, String url, String message, String def, JsPromptResult r) {
            EditText et = new EditText(MainActivity.this);
            et.setText(def);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(hostTitle(url))
                    .setMessage(message)
                    .setView(et)
                    .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm(et.getText().toString()))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
            return true;
        }
    }

    // ------------------------------------------------------------------ printing

    private static final String PRINT_JS = "(function(){if(window.__wsPrint)return;window.__wsPrint=1;"
            + "window.print=function(){try{WebShellPrint.print(document.title||'')}catch(e){}}})()";

    private final class PrintBridge {
        @JavascriptInterface
        public void print(String title) {
            printPage(title);
        }
    }

    private void injectPrint() {
        if (cfg.printing) web.evaluateJavascript(PRINT_JS, null);
    }

    /** Opens the Android print dialog (printers, "Save as PDF") for the current page. */
    void printPage(String title) {
        runOnUiThread(() -> {
            try {
                String name = title != null && !title.isEmpty() ? title
                        : (web.getTitle() != null && !web.getTitle().isEmpty() ? web.getTitle() : cfg.name);
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                PrintAttributes attrs = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4).build();
                pm.print(name, web.createPrintDocumentAdapter(name), attrs);
            } catch (Exception e) {
                Toast.makeText(this, "প্রিন্ট করা যায়নি", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------------ built-in media player

    private static String extOf(String path) {
        if (path == null) return "";
        int d = path.lastIndexOf('.');
        int sl = path.lastIndexOf('/');
        return d < 0 || d < sl ? "" : path.substring(d + 1).toLowerCase();
    }

    private boolean isMediaUrl(String url) {
        if (!cfg.mediaPlayer || cfg.mediaExt.isEmpty()) return false;
        try {
            Uri u = Uri.parse(url);
            String sch = u.getScheme();
            if (sch == null || !(sch.equalsIgnoreCase("http") || sch.equalsIgnoreCase("https"))) return false;
            return cfg.mediaExt.contains(extOf(u.getPath()));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAudioExt(String ext) {
        switch (ext) {
            case "mp3": case "m4a": case "aac": case "wav": case "ogg": case "oga":
            case "flac": case "opus": case "amr": case "mid": case "midi": case "wma":
                return true;
            default:
                return false;
        }
    }

    private void openMedia(String url, String fileName) {
        if (media != null) return;
        String name = fileName != null && !fileName.isEmpty() ? fileName
                : URLUtil.guessFileName(url, null, null);
        boolean audio = isAudioExt(extOf(name)) || isAudioExt(extOf(Uri.parse(url).getPath()));
        media = new MediaPlayerView(this, cfg.accent, name, audio, new MediaPlayerView.Listener() {
            @Override
            public void onClose() {
                closeMedia();
            }

            @Override
            public void onError() {
                mediaFailed(url, name);
            }

            @Override
            public void onExternal() {
                openExternally(url);
            }
        });
        root.addView(media, new FrameLayout.LayoutParams(MATCH, MATCH));
        applySystemUi(true);
        updateNavVisibility();
        Map<String, String> h = new HashMap<>();
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) h.put("Cookie", cookie);
        h.put("User-Agent", web.getSettings().getUserAgentString());
        media.play(Uri.parse(url), h);
    }

    private void closeMedia() {
        if (media == null) return;
        MediaPlayerView m = media;
        media = null;
        m.release();
        root.removeView(m);
        applySystemUi(customView != null);
        updateNavVisibility();
    }

    private void openExternally(String url) {
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(Uri.parse(url).getPath()));
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), mime == null ? "video/*" : mime);
        if (!launch(i)) Toast.makeText(this, "খোলার মতো অ্যাপ পাওয়া যায়নি", Toast.LENGTH_SHORT).show();
    }

    private void mediaFailed(String url, String name) {
        if (media == null) return;
        new AlertDialog.Builder(this)
                .setTitle("প্লেয়ারে চালানো যাচ্ছে না")
                .setMessage(name + "\nএই ফরম্যাট/লিংক ইন-বিল্ট প্লেয়ার সাপোর্ট করে না।")
                .setPositiveButton("ডাউনলোড", (d, w) -> {
                    closeMedia();
                    startDownload(url, web.getSettings().getUserAgentString(), null, null);
                })
                .setNeutralButton("অন্য অ্যাপে খুলুন", (d, w) -> {
                    closeMedia();
                    openExternally(url);
                })
                .setNegativeButton("বন্ধ", (d, w) -> closeMedia())
                .setOnCancelListener(d -> closeMedia())
                .show();
    }

    private static String hostTitle(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    private void closePopup() {
        if (popup == null) return;
        final WebView p = popup;
        popup = null;
        ui.post(() -> {
            root.removeView(p);
            p.destroy();
        });
    }

    // ------------------------------------------------------------------ URL handling

    private boolean sameSite(Uri u) {
        String h = u.getHost();
        if (h == null || cfg.host.isEmpty()) return true;
        h = h.toLowerCase();
        return h.equals(cfg.host) || h.equals("www." + cfg.host) || h.endsWith("." + cfg.host);
    }

    /** @return true if the URL was consumed (launched elsewhere), false to load in the WebView. */
    private boolean handleUrl(String url, boolean allowExternal) {
        if (isMediaUrl(url)) {
            openMedia(url, null);
            return true;
        }
        Uri u = Uri.parse(url);
        String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        switch (sch) {
            case "http":
            case "https":
                if (allowExternal && cfg.externalLinks && !sameSite(u)) {
                    launch(new Intent(Intent.ACTION_VIEW, u));
                    return true;
                }
                return false;
            case "about":
            case "javascript":
            case "data":
            case "blob":
            case "file":
            case "":
                return false;
            case "tel":
                launch(new Intent(Intent.ACTION_DIAL, u));
                return true;
            case "sms":
            case "smsto":
            case "mms":
            case "mmsto":
                launch(new Intent(Intent.ACTION_SENDTO, u));
                return true;
            case "mailto":
                launch(new Intent(Intent.ACTION_SENDTO, u));
                return true;
            case "intent":
                try {
                    Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    i.setSelector(null);
                    if (!launch(i)) {
                        String fb = i.getStringExtra("browser_fallback_url");
                        if (fb != null) {
                            web.loadUrl(fb);
                        } else if (i.getPackage() != null) {
                            launch(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + i.getPackage())));
                        }
                    }
                } catch (URISyntaxException ignored) {
                }
                return true;
            default:
                launch(new Intent(Intent.ACTION_VIEW, u));
                return true;
        }
    }

    boolean launch(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean trusted() {
        if (cfg.host.isEmpty()) return false;
        String h = AppConfig.hostOf(currentUrl);
        return h.equals(cfg.host) || h.endsWith("." + cfg.host);
    }

    // ------------------------------------------------------------------ files / downloads

    private static boolean acceptsImage(String[] types) {
        if (types == null || types.length == 0) return true;
        for (String t : types) {
            if (t == null || t.isEmpty() || t.startsWith("image")) return true;
        }
        return false;
    }

    private void launchPicker(WebChromeClient.FileChooserParams p) {
        try {
            startActivityForResult(p.createIntent(), REQ_FILE);
        } catch (Exception e) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = null;
        }
    }

    private void launchCapture() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            captureUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAPTURE);
        } catch (Exception e) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = null;
            captureUri = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == AppLock.REQ_KEYGUARD) {
            AppLock.onActivityResult(req, res);
            return;
        }
        if (req == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCb = null;
            }
        } else if (req == REQ_CAPTURE) {
            if (fileCb != null) {
                if (res == RESULT_OK && captureUri != null) {
                    fileCb.onReceiveValue(new Uri[]{captureUri});
                } else {
                    fileCb.onReceiveValue(null);
                    if (captureUri != null) getContentResolver().delete(captureUri, null, null);
                }
                fileCb = null;
                captureUri = null;
            }
        }
    }

    private void startDownload(String url, String ua, String cd, String mime) {
        if (url.startsWith("blob:")) {
            String fn = URLUtil.guessFileName("https://localhost/download", cd, mime);
            web.evaluateJavascript(blob.prepare(url, fn, mime), null);
            Toast.makeText(this, "ডাউনলোড শুরু হয়েছে", Toast.LENGTH_SHORT).show();
            return;
        }
        if (url.startsWith("data:")) {
            try {
                FileSaver.Data d = FileSaver.parseDataUrl(url);
                saveBytes(URLUtil.guessFileName("https://localhost/download", cd, d.mime), d.mime, d.bytes);
            } catch (Exception e) {
                downloadFailed();
            }
            return;
        }
        Runnable go = () -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mime);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) r.addRequestHeader("Cookie", cookie);
                r.addRequestHeader("User-Agent", ua);
                String fn = URLUtil.guessFileName(url, cd, mime);
                r.setTitle(fn);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "ডাউনলোড শুরু হয়েছে", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "ডাউনলোড করা যায়নি", Toast.LENGTH_SHORT).show();
            }
        };
        withStorage(go, () -> Toast.makeText(this, "স্টোরেজ পারমিশন দরকার", Toast.LENGTH_SHORT).show());
    }

    /** Android 9 and older need the storage permission to write into Downloads. */
    private void withStorage(Runnable ok, Runnable fail) {
        if (Build.VERSION.SDK_INT < 29 && cfg.perms.contains("storage")) ensureKey("storage", ok, fail);
        else ok.run();
    }

    void downloadFailed() {
        runOnUiThread(() -> Toast.makeText(this, "ডাউনলোড করা যায়নি", Toast.LENGTH_SHORT).show());
    }

    /** Saves bytes (from a data: URL or Android.saveFile) into Downloads. Safe to call from any thread. */
    void saveBytes(String name, String mime, byte[] data) {
        runOnUiThread(() -> withStorage(() -> new Thread(() -> {
            try {
                String where = FileSaver.saveToDownloads(this, name, mime, data);
                fileSaved(name, where);
            } catch (Exception e) {
                downloadFailed();
            }
        }).start(), () -> Toast.makeText(this, "স্টোরেজ পারমিশন দরকার", Toast.LENGTH_SHORT).show()));
    }

    /** Saves a finished temp file (blob download) into Downloads and deletes the temp file. */
    void saveDownloadedFile(File f, String name, String mime) {
        runOnUiThread(() -> withStorage(() -> new Thread(() -> {
            try {
                String where = FileSaver.saveToDownloads(this, name, mime, f);
                fileSaved(name, where);
            } catch (Exception e) {
                downloadFailed();
            } finally {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }).start(), () -> {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            Toast.makeText(this, "স্টোরেজ পারমিশন দরকার", Toast.LENGTH_SHORT).show();
        }));
    }

    private void fileSaved(String name, String where) {
        runOnUiThread(() -> Toast.makeText(this, "সংরক্ষিত: " + where, Toast.LENGTH_LONG).show());
        js("window.onFileSaved&&window.onFileSaved(" + JSONObject.quote(name) + ")");
    }

    // ------------------------------------------------------------------ permissions

    boolean has(String key) {
        return cfg.perms.contains(key);
    }

    boolean granted(String key) {
        if (!has(key)) return false;
        String[] p = Perms.runtime(key);
        if (p.length == 0) return true;
        for (String s : p) {
            if (checkSelfPermission(s) == PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    void ensureKey(String key, Runnable ok, Runnable fail) {
        if (!has(key)) {
            fail.run();
            return;
        }
        ensure(Perms.runtime(key), ok, fail);
    }

    void ensure(String[] perms, Runnable ok, Runnable fail) {
        List<String> need = new ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (need.isEmpty()) {
            ok.run();
            return;
        }
        int code = ++permCounter;
        permCbs.put(code, new Runnable[]{ok, fail});
        requestPermissions(need.toArray(new String[0]), code);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        Runnable[] cb = permCbs.get(code);
        permCbs.remove(code);
        if (cb == null) return;
        boolean any = false;
        for (int r : results) {
            if (r == PackageManager.PERMISSION_GRANTED) any = true;
        }
        (any ? cb[0] : cb[1]).run();
    }

    private void requestStartPermissions() {
        Set<String> all = new LinkedHashSet<>();
        for (String key : cfg.perms) {
            for (String p : Perms.runtime(key)) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) all.add(p);
            }
        }
        if (!all.isEmpty()) requestPermissions(all.toArray(new String[0]), ++permCounter);
    }

    // ------------------------------------------------------------------ notifications

    void showNotification(String title, String text) {
        Runnable go = () -> Notifier.show(this, cfg, title, text, null);
        if (has("notifications")) ensureKey("notifications", go, () -> {});
        else go.run();
    }

    // ------------------------------------------------------------------ smart back button

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (locked) {
            finish();
            return;
        }
        if (scanner != null) {
            scanner.dismiss();
            return;
        }
        if (media != null) {
            closeMedia();
            return;
        }
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        if (popup != null) {
            if (popup.canGoBack()) popup.goBack();
            else closePopup();
            return;
        }
        if (offlineView.getVisibility() == View.VISIBLE) {
            if (web.canGoBack()) {
                hideOffline();
                web.goBack();
            } else {
                exitFlow();
            }
            return;
        }
        if (cfg.jsBridge) {
            // Let the page handle Back first: define window.onAndroidBack = () => true to consume it.
            web.evaluateJavascript(
                    "(function(){try{return typeof window.onAndroidBack==='function'&&window.onAndroidBack()===true}catch(e){return false}})()",
                    v -> {
                        if (!"true".equals(v)) navigateBack();
                    });
        } else {
            navigateBack();
        }
    }

    private void navigateBack() {
        if (web.canGoBack()) web.goBack();
        else exitFlow();
    }

    private void exitFlow() {
        long now = System.currentTimeMillis();
        if (cfg.doubleBackExit && now - lastBack > 2000) {
            lastBack = now;
            Toast.makeText(this, cfg.exitMessage, Toast.LENGTH_SHORT).show();
        } else {
            finish();
        }
    }
}
