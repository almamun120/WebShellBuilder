package com.webshell.builder;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_ICON = 1;
    private static final int REQ_SPLASH = 2;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final Pattern PKG = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    private static final int BLUE = 0xFF3B82F6;

    private SharedPreferences sp;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private Spinner spOrient;
    private ImageView ivIcon;
    private ImageView ivSplash;
    private TextView tvStatus;
    private Button btnBuild;
    private LinearLayout resultBox;
    private TextView tvResult;
    private Button btnShare;

    private File lastApk;
    private Uri lastSaved;
    private static final String[] ORIENT_LABELS = {"অটো", "পোর্ট্রেট", "ল্যান্ডস্কেপ"};
    private static final String[] ORIENT_VALUES = {"auto", "portrait", "landscape"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("builder", MODE_PRIVATE);
        buildUi();
    }

    @Override
    protected void onPause() {
        savePrefs();
        super.onPause();
    }

    // ------------------------------------------------------------------ UI helpers

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(Color.WHITE, 0xFFE4E7EC, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF101828);
        c.addView(t);
        return c;
    }

    private EditText field(LinearLayout c, String key, String label, String hint, String def, int inputType) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(0xFF475467);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(12);
        c.addView(l, lp);

        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setBackground(rounded(0xFFF9FAFB, 0xFFD0D5DD, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setText(sp.getString("f_" + key, def));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(MATCH, WRAP);
        ep.topMargin = dp(4);
        c.addView(e, ep);
        fields.put(key, e);
        return e;
    }

    private Switch sw(LinearLayout c, String key, String text, boolean def) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextSize(14);
        s.setTextColor(0xFF1D2939);
        s.setChecked(sp.getBoolean("s_" + key, def));
        s.setPadding(0, dp(6), 0, dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(6);
        c.addView(s, lp);
        switches.put(key, s);
        return s;
    }

    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(color, 0, 12));
        return b;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFF2F4F7);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(32));
        sv.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        TextView h = new TextView(this);
        h.setText("WebShell Builder");
        h.setTextSize(24);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(0xFF101828);
        root.addView(h);
        TextView sub = new TextView(this);
        sub.setText("সার্ভার অ্যাড্রেস দিয়ে সাইন করা ব্রাউজার-ভিত্তিক APK তৈরি করুন");
        sub.setTextSize(13);
        sub.setTextColor(0xFF667085);
        root.addView(sub);

        // ---- app info
        LinearLayout c1 = card(root, "অ্যাপের তথ্য");
        field(c1, "name", "অ্যাপের নাম", "যেমন: My Shop", "", InputType.TYPE_CLASS_TEXT);
        field(c1, "pkg", "প্যাকেজ নেম", "com.mycompany.myapp", "com.mycompany.myapp", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field(c1, "url", "সার্ভার অ্যাড্রেস", "https://example.com বা http://192.168.0.10:8080", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field(c1, "verName", "ভার্সন নাম", "1.0", "1.0", InputType.TYPE_CLASS_TEXT);
        field(c1, "verCode", "ভার্সন কোড (আপডেটে বাড়াতে হবে)", "1", "1", InputType.TYPE_CLASS_NUMBER);

        // ---- icon & splash
        LinearLayout c2 = card(root, "আইকন ও স্প্ল্যাশ স্ক্রিন");
        ivIcon = imageRow(c2, "অ্যাপ আইকন বাছুন", REQ_ICON, "icon_src.png");
        ivSplash = imageRow(c2, "স্প্ল্যাশ ছবি বাছুন", REQ_SPLASH, "splash_src.png");
        field(c2, "splashBg", "স্প্ল্যাশ ব্যাকগ্রাউন্ড রং (hex)", "#FFFFFF", "#FFFFFF", InputType.TYPE_CLASS_TEXT);
        field(c2, "accent", "অ্যাকসেন্ট রং (লোডিং/বাটন)", "#3B82F6", "#3B82F6", InputType.TYPE_CLASS_TEXT);
        field(c2, "status", "স্ট্যাটাস বার রং (ফাঁকা = ডিফল্ট)", "#FFFFFF", "", InputType.TYPE_CLASS_TEXT);
        field(c2, "splashSec", "স্প্ল্যাশ সময় (সেকেন্ড)", "1.5", "1.5", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sw(c2, "splashName", "স্প্ল্যাশে অ্যাপের নাম দেখান", true);

        // ---- behaviour
        LinearLayout c3 = card(root, "আচরণ");
        TextView ol = new TextView(this);
        ol.setText("স্ক্রিন ঘোরানো");
        ol.setTextSize(13);
        ol.setTextColor(0xFF475467);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(WRAP, WRAP);
        olp.topMargin = dp(12);
        c3.addView(ol, olp);
        spOrient = new Spinner(this);
        spOrient.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ORIENT_LABELS));
        spOrient.setSelection(sp.getInt("orient", 0));
        c3.addView(spOrient);
        sw(c3, "fullscreen", "ফুলস্ক্রিন (স্ট্যাটাস/নেভিগেশন বার লুকাবে)", false);
        sw(c3, "doubleBack", "বের হতে দু'বার ব্যাক চাপতে হবে", true);
        sw(c3, "keepOn", "স্ক্রিন সবসময় চালু রাখুন", false);
        sw(c3, "js", "JavaScript ব্রিজ (window.Android) চালু", true);
        sw(c3, "desktop", "ডেস্কটপ সাইট মোড", false);
        sw(c3, "zoom", "পিঞ্চ জুম অনুমতি", true);
        sw(c3, "ext", "অন্য ডোমেইনের লিংক ব্রাউজারে খুলুন", false);
        sw(c3, "ssl", "SSL সার্টিফিকেট ত্রুটি উপেক্ষা (শুধু নিজের সার্ভারে)", false);
        sw(c3, "askStart", "অ্যাপ খুললেই পারমিশন চাও", true);
        field(c3, "ua", "User-Agent এর শেষে যোগ (ঐচ্ছিক)", "MyApp/1.0", "", InputType.TYPE_CLASS_TEXT);

        // ---- permissions
        LinearLayout c4 = card(root, "পারমিশন");
        for (Perms.Group g : Perms.ALL) {
            sw(c4, "p_" + g.key, g.label + " — " + g.desc, g.defaultOn);
        }

        // ---- build
        btnBuild = button("APK তৈরি করুন", BLUE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH, dp(52));
        bp.topMargin = dp(18);
        root.addView(btnBuild, bp);
        btnBuild.setOnClickListener(v -> onBuild());

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(0xFF475467);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(10), 0, 0);
        root.addView(tvStatus);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setBackground(rounded(0xFFECFDF3, 0xFFABEFC6, 14));
        resultBox.setPadding(dp(14), dp(12), dp(14), dp(14));
        resultBox.setVisibility(View.GONE);
        tvResult = new TextView(this);
        tvResult.setTextSize(13);
        tvResult.setTextColor(0xFF067647);
        resultBox.addView(tvResult);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button btnInstall = button("ইনস্টল করুন", 0xFF12B76A);
        btnShare = button("শেয়ার", 0xFF475467);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        rp.setMargins(0, dp(10), dp(6), 0);
        row.addView(btnInstall, rp);
        LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        rp2.setMargins(dp(6), dp(10), 0, 0);
        row.addView(btnShare, rp2);
        resultBox.addView(row);
        LinearLayout.LayoutParams rb = new LinearLayout.LayoutParams(MATCH, WRAP);
        rb.topMargin = dp(12);
        root.addView(resultBox, rb);
        btnInstall.setOnClickListener(v -> onInstall());
        btnShare.setOnClickListener(v -> onShare());

        setContentView(sv);
    }

    private ImageView imageRow(LinearLayout parent, String label, int req, String savedName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, WRAP);
        rl.topMargin = dp(12);
        parent.addView(row, rl);

        ImageView iv = new ImageView(this);
        iv.setBackground(rounded(0xFFF2F4F7, 0xFFD0D5DD, 12));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
        File saved = new File(getFilesDir(), savedName);
        if (saved.exists()) iv.setImageBitmap(BitmapFactory.decodeFile(saved.getAbsolutePath()));

        Button b = button(label, 0xFF475467);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(46), 1f);
        bl.leftMargin = dp(12);
        row.addView(b, bl);
        b.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, req);
        });
        return iv;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req != REQ_ICON && req != REQ_SPLASH) return;
        try {
            Uri u = data.getData();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 1400) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) throw new IllegalStateException("ছবি পড়া যায়নি");
            File dest = new File(getFilesDir(), req == REQ_ICON ? "icon_src.png" : "splash_src.png");
            try (OutputStream out = new FileOutputStream(dest)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            (req == REQ_ICON ? ivIcon : ivSplash).setImageBitmap(bmp);
        } catch (Exception e) {
            toast("ছবি লোড ব্যর্থ: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ prefs

    private void savePrefs() {
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, EditText> e : fields.entrySet()) ed.putString("f_" + e.getKey(), e.getValue().getText().toString());
        for (Map.Entry<String, Switch> e : switches.entrySet()) ed.putBoolean("s_" + e.getKey(), e.getValue().isChecked());
        ed.putInt("orient", spOrient.getSelectedItemPosition());
        ed.apply();
    }

    private String val(String key) {
        return fields.get(key).getText().toString().trim();
    }

    private boolean on(String key) {
        return switches.get(key).isChecked();
    }

    private static int parseColor(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Color.parseColor(s.startsWith("#") ? s : "#" + s);
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ actions

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private void status(String m) {
        tvStatus.setText(m);
    }

    private void onBuild() {
        String name = val("name");
        String pkg = val("pkg");
        String url = val("url");
        if (name.isEmpty()) { toast("অ্যাপের নাম দিন"); return; }
        if (!PKG.matcher(pkg).matches()) { toast("প্যাকেজ নেম সঠিক নয় (যেমন: com.mycompany.myapp, ছোট হাতের অক্ষর)"); return; }
        if (pkg.equals(AxmlEditor.OLD_PACKAGE) || pkg.equals(getPackageName())) { toast("অন্য একটি প্যাকেজ নেম দিন"); return; }
        if (url.isEmpty()) { toast("সার্ভার অ্যাড্রেস দিন"); return; }
        if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
        int code;
        try {
            code = Integer.parseInt(val("verCode"));
            if (code < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) { toast("ভার্সন কোড ১ বা তার বেশি হতে হবে"); return; }
        double sec;
        try { sec = Double.parseDouble(val("splashSec")); } catch (Exception e) { sec = 1.5; }

        BuildSpec s = new BuildSpec();
        s.appName = name;
        s.pkg = pkg;
        s.url = url;
        s.versionName = val("verName").isEmpty() ? "1.0" : val("verName");
        s.versionCode = code;
        File ic = new File(getFilesDir(), "icon_src.png");
        File sp2 = new File(getFilesDir(), "splash_src.png");
        s.iconFile = ic.exists() ? ic : null;
        s.splashFile = sp2.exists() ? sp2 : null;
        s.splashBg = parseColor(val("splashBg"), Color.WHITE);
        s.accent = parseColor(val("accent"), BLUE);
        s.statusColor = parseColor(val("status"), 0);
        s.splashMs = (int) Math.max(0, Math.min(10, sec) * 1000);
        s.splashName = on("splashName");
        s.orientation = ORIENT_VALUES[spOrient.getSelectedItemPosition()];
        s.fullscreen = on("fullscreen");
        s.doubleBackExit = on("doubleBack");
        s.keepScreenOn = on("keepOn");
        s.jsBridge = on("js");
        s.desktop = on("desktop");
        s.zoom = on("zoom");
        s.externalLinks = on("ext");
        s.ignoreSsl = on("ssl");
        s.askOnStart = on("askStart");
        s.userAgentSuffix = val("ua");
        for (Perms.Group g : Perms.ALL) if (on("p_" + g.key)) s.perms.add(g.key);

        savePrefs();
        btnBuild.setEnabled(false);
        resultBox.setVisibility(View.GONE);
        status("শুরু হচ্ছে...");

        new Thread(() -> {
            try {
                File apk = ApkGenerator.generate(this, s, m -> runOnUiThread(() -> status(m)));
                runOnUiThread(() -> status("ডাউনলোড ফোল্ডারে সেভ হচ্ছে..."));
                String where;
                Uri saved = null;
                try {
                    saved = saveToDownloads(apk);
                    where = Build.VERSION.SDK_INT >= 29
                            ? "Download/WebShellBuilder/" + apk.getName()
                            : saved.getPath();
                } catch (Exception e) {
                    where = apk.getAbsolutePath();
                }
                final Uri fs = saved;
                final String fw = where;
                runOnUiThread(() -> {
                    lastApk = apk;
                    lastSaved = fs;
                    btnBuild.setEnabled(true);
                    status("");
                    tvResult.setText("✔ APK তৈরি ও সাইন হয়েছে\n" + fw + "\nসাইজ: " + (apk.length() / 1024) + " KB");
                    btnShare.setVisibility(fs != null && Build.VERSION.SDK_INT >= 29 ? View.VISIBLE : View.GONE);
                    resultBox.setVisibility(View.VISIBLE);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    btnBuild.setEnabled(true);
                    status("ব্যর্থ: " + t);
                });
            }
        }).start();
    }

    private Uri saveToDownloads(File apk) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, apk.getName());
            v.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WebShellBuilder");
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IllegalStateException("MediaStore insert failed");
            try (InputStream in = new FileInputStream(apk); OutputStream out = getContentResolver().openOutputStream(u)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return u;
        }
        File dir = getExternalFilesDir(null);
        File f = new File(dir, apk.getName());
        try (InputStream in = new FileInputStream(apk); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return Uri.fromFile(f);
    }

    private void onInstall() {
        if (lastApk == null || !lastApk.exists()) { toast("আগে APK তৈরি করুন"); return; }
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("এই অ্যাপকে 'অজানা অ্যাপ ইনস্টল' অনুমতি দিন, তারপর আবার চাপুন");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            Installer.install(this, lastApk);
        } catch (Exception e) {
            toast("ইনস্টল শুরু করা যায়নি: " + e.getMessage());
        }
    }

    private void onShare() {
        if (lastSaved == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_STREAM, lastSaved);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "APK শেয়ার করুন"));
    }
}
