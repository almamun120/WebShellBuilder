package com.webshell.shell;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Saves downloaded / generated files into the public Downloads folder and shares files. */
final class FileSaver {
    private FileSaver() {}

    static final class Data {
        String mime;
        byte[] bytes;
    }

    static String safeName(String n, String mime) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (s.length() > 120) s = s.substring(s.length() - 120);
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "file_" + System.currentTimeMillis();
        if (!s.contains(".") && mime != null) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) s = s + "." + ext;
        }
        return s;
    }

    static Data parseDataUrl(String url) throws IOException {
        int comma = url.indexOf(',');
        if (!url.startsWith("data:") || comma < 0) throw new IOException("bad data url");
        String meta = url.substring(5, comma);
        String payload = url.substring(comma + 1);
        boolean b64 = meta.toLowerCase().endsWith(";base64");
        String mime = b64 ? meta.substring(0, meta.length() - 7) : meta;
        int semi = mime.indexOf(';');
        if (semi >= 0) mime = mime.substring(0, semi);
        if (mime.isEmpty()) mime = "text/plain";
        Data d = new Data();
        d.mime = mime;
        d.bytes = b64 ? Base64.decode(payload, Base64.DEFAULT) : Uri.decode(payload).getBytes(StandardCharsets.UTF_8);
        return d;
    }

    /** @return a short human readable location, e.g. "Download/report.pdf". */
    static String saveToDownloads(Context c, String name, String mime, byte[] data) throws IOException {
        File tmp = new File(c.getCacheDir(), "save_" + System.nanoTime() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
        }
        try {
            return saveToDownloads(c, name, mime, tmp);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    static String saveToDownloads(Context c, String name, String mime, File src) throws IOException {
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        String fn = safeName(name, mime);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, fn);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("MediaStore insert failed");
            try (InputStream in = new FileInputStream(src); OutputStream out = c.getContentResolver().openOutputStream(u)) {
                if (out == null) throw new IOException("no output stream");
                copy(in, out);
            }
            return "Download/" + fn;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dest = new File(dir, fn);
        int n = 1;
        int dot = fn.lastIndexOf('.');
        String base = dot > 0 ? fn.substring(0, dot) : fn;
        String ext = dot > 0 ? fn.substring(dot) : "";
        while (dest.exists()) dest = new File(dir, base + " (" + (n++) + ")" + ext);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        MediaScannerConnection.scanFile(c, new String[]{dest.getAbsolutePath()}, new String[]{mime}, null);
        return "Download/" + dest.getName();
    }

    /** Writes the bytes into the share cache and opens the system share sheet. */
    static void share(Context c, String name, String mime, byte[] data) throws IOException {
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        File dir = ShareProvider.dir(c);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        long cutoff = System.currentTimeMillis() - 24L * 3600 * 1000;
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (f.lastModified() < cutoff) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        String fn = safeName(name, mime);
        File f = new File(dir, fn);
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
        Uri u = new Uri.Builder().scheme("content").authority(ShareProvider.authority(c)).appendPath(fn).build();
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(i, null);
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(chooser);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
