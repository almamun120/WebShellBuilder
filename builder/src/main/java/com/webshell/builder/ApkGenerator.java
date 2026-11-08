package com.webshell.builder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.android.apksig.ApkSigner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Turns a BuildSpec into a signed, installable APK. */
final class ApkGenerator {
    interface Progress {
        void log(String msg);
    }

    private ApkGenerator() {}

    static File generate(Context ctx, BuildSpec s, Progress p) throws Exception {
        File work = new File(ctx.getCacheDir(), "work");
        deleteRec(work);
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();

        p.log("টেমপ্লেট লোড হচ্ছে...");
        File tpl = new File(work, "template.apk");
        try (InputStream in = ctx.getAssets().open("template.apk"); OutputStream out = new FileOutputStream(tpl)) {
            copy(in, out);
        }

        p.log("ম্যানিফেস্ট প্যাচ হচ্ছে (প্যাকেজ, নাম, পারমিশন)...");
        byte[] manifest;
        try (ZipFile zf = new ZipFile(tpl)) {
            ZipEntry me = zf.getEntry("AndroidManifest.xml");
            if (me == null) throw new IllegalStateException("টেমপ্লেটে AndroidManifest.xml নেই");
            manifest = ApkPacker.readAll(zf.getInputStream(me));
        }
        Set<String> allowed = new HashSet<>();
        allowed.add("android.permission.INTERNET");
        allowed.add("android.permission.ACCESS_NETWORK_STATE");
        for (String key : s.perms) Collections.addAll(allowed, Perms.manifestNames(key));
        manifest = AxmlEditor.patch(manifest, s.pkg, s.appName, s.versionName, s.versionCode, allowed);

        p.log("কনফিগ ও ছবি তৈরি হচ্ছে...");
        Map<String, byte[]> extra = new LinkedHashMap<>();
        boolean hasSplash = false;
        if (s.splashFile != null && s.splashFile.exists()) {
            Bitmap sb = BitmapFactory.decodeFile(s.splashFile.getAbsolutePath());
            if (sb != null) {
                extra.put("assets/splash.png", png(scaleDown(sb, 720)));
                hasSplash = true;
            }
        }
        extra.put("assets/config.json", config(s, hasSplash).getBytes(StandardCharsets.UTF_8));

        final Bitmap iconSrc = (s.iconFile != null && s.iconFile.exists())
                ? BitmapFactory.decodeFile(s.iconFile.getAbsolutePath()) : null;
        final Map<Integer, byte[]> iconCache = new HashMap<>();

        p.log("APK প্যাক হচ্ছে...");
        File unsigned = new File(work, "unsigned.apk");
        ApkPacker.pack(tpl, unsigned, manifest, extra, size -> {
            byte[] cached = iconCache.get(size);
            if (cached == null) {
                cached = png(renderIcon(iconSrc, s.appName, s.accent, size));
                iconCache.put(size, cached);
            }
            return cached;
        });

        p.log("সাইন করা হচ্ছে...");
        SigningKey sk = SigningKey.loadOrCreate(ctx, s.pkg, s.appName);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(
                "webshell", sk.key, Collections.singletonList(sk.cert)).build();
        File signed = new File(work, fileName(s));
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(24)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();
        //noinspection ResultOfMethodCallIgnored
        tpl.delete();
        return signed;
    }

    static String fileName(BuildSpec s) {
        String base = s.appName.replaceAll("[^A-Za-z0-9_\\-]", "");
        if (base.isEmpty()) base = s.pkg.replace('.', '_');
        return base + "_" + s.versionName + ".apk";
    }

    private static String color(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    private static String config(BuildSpec s, boolean hasSplash) throws Exception {
        JSONObject o = new JSONObject();
        o.put("url", s.url);
        o.put("name", s.appName);
        o.put("splashBg", color(s.splashBg));
        o.put("accent", color(s.accent));
        if (s.statusColor != 0) o.put("statusColor", color(s.statusColor));
        o.put("splashImage", hasSplash);
        o.put("splashName", s.splashName);
        o.put("splashMs", s.splashMs);
        o.put("orientation", s.orientation);
        o.put("fullscreen", s.fullscreen);
        o.put("doubleBackExit", s.doubleBackExit);
        o.put("keepScreenOn", s.keepScreenOn);
        o.put("ignoreSsl", s.ignoreSsl);
        o.put("desktop", s.desktop);
        o.put("externalLinks", s.externalLinks);
        o.put("jsBridge", s.jsBridge);
        o.put("askOnStart", s.askOnStart);
        o.put("zoom", s.zoom);
        o.put("userAgentSuffix", s.userAgentSuffix);
        JSONArray perms = new JSONArray();
        for (String k : s.perms) perms.put(k);
        o.put("permissions", perms);
        return o.toString();
    }

    // ---------------------------------------------------------------- images

    private static Bitmap scaleDown(Bitmap b, int max) {
        int m = Math.max(b.getWidth(), b.getHeight());
        if (m <= max) return b;
        float f = max / (float) m;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * f), Math.round(b.getHeight() * f), true);
    }

    private static byte[] png(Bitmap b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return bo.toByteArray();
    }

    /** Square icon: the picked image (center-cropped) or a generated letter icon. */
    private static Bitmap renderIcon(Bitmap src, String name, int accent, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (src != null) {
            int side = Math.min(src.getWidth(), src.getHeight());
            Rect from = new Rect((src.getWidth() - side) / 2, (src.getHeight() - side) / 2,
                    (src.getWidth() + side) / 2, (src.getHeight() + side) / 2);
            c.drawBitmap(src, from, new Rect(0, 0, size, size), p);
        } else {
            p.setColor(accent);
            c.drawRoundRect(new RectF(0, 0, size, size), size * 0.22f, size * 0.22f, p);
            p.setColor(Color.WHITE);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(size * 0.55f);
            String letter = name.isEmpty() ? "A" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase();
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p);
        }
        return out;
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void deleteRec(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
