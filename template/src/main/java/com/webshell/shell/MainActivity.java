package com.webshell.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.provider.MediaStore;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
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

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
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
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = AppConfig.load(this);
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
        if (offlineView.getVisibility() == View.VISIBLE) retry();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        web.onPause();
        super.onPause();
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
        root.removeView(web);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemUi(customView != null);
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
        if (cfg.fullscreen || forceFull) {
            flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        } else if (cfg.statusColor != 0 && !isDark(cfg.statusColor)
                && Build.VERSION.SDK_INT >= 23) {
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(MATCH, MATCH));

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

        setContentView(root);
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

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bmp = null;
        if (cfg.splashImage) {
            try (InputStream in = getAssets().open("splash.png")) {
                bmp = BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
            }
        }
        if (bmp != null) iv.setImageBitmap(bmp);
        else iv.setImageDrawable(getPackageManager().getApplicationIcon(getApplicationInfo()));
        l.addView(iv, new LinearLayout.LayoutParams(dp(180), dp(180)));

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

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(cfg.accent));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(30), dp(30));
        pp.topMargin = dp(28);
        l.addView(pb, pp);

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
                .withEndAction(() -> splashView.setVisibility(View.GONE)).start();
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
                ui.postDelayed(() -> {
                    if (offlineView.getVisibility() == View.VISIBLE) retry();
                }, 800);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Exception e) {
            netCb = null;
        }
    }

    private void loadMain() {
        if (!isOnline()) {
            failedUrl = cfg.url;
            mainError = true;
            showOffline();
        } else {
            web.loadUrl(cfg.url);
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
        if (!isOnline()) {
            showOffline();
            return;
        }
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
        web.setWebViewClient(new Client());
        chrome = new Chrome();
        web.setWebChromeClient(chrome);
        web.setDownloadListener((url, ua, cd, mime, len) -> startDownload(url, ua, cd, mime));
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
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            currentUrl = url;
            CookieManager.getInstance().flush();
            if (!mainError) hideOffline();
            pageDone = true;
            tryHideSplash();
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame()) return;
            if (String.valueOf(e.getDescription()).contains("ERR_ABORTED")) return;
            mainError = true;
            failedUrl = r.getUrl().toString();
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
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            if (customCb != null) customCb.onCustomViewHidden();
            web.setVisibility(View.VISIBLE);
            applySystemUi(false);
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
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "এই ধরনের ডাউনলোড সাপোর্টেড নয়", Toast.LENGTH_SHORT).show();
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
        if (Build.VERSION.SDK_INT < 29 && cfg.perms.contains("storage")) {
            ensureKey("storage", go, () -> Toast.makeText(this, "স্টোরেজ পারমিশন দরকার", Toast.LENGTH_SHORT).show());
        } else {
            go.run();
        }
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
        Runnable go = () -> {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("app", cfg.name, NotificationManager.IMPORTANCE_DEFAULT));
                nb = new Notification.Builder(this, "app");
            } else {
                nb = new Notification.Builder(this);
            }
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nb.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify((int) (System.currentTimeMillis() & 0x7FFFFFFF), nb.build());
        };
        if (has("notifications")) ensureKey("notifications", go, () -> {});
        else go.run();
    }

    // ------------------------------------------------------------------ smart back button

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
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
