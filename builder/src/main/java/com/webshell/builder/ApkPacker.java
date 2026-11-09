package com.webshell.builder;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rebuilds the template APK: replaces the manifest, launcher icons and assets, drops the old
 * signature, and writes stored entries 4-byte aligned (required for resources.arsc on Android 11+).
 */
public final class ApkPacker {
    private static final long FIXED_TIME = 1230768000000L; // 2009-01-01
    private static final Pattern ICON = Pattern.compile("^res/mipmap-(l|m|h|xh|xxh|xxxh)dpi[^/]*/ic_launcher\\.png$");

    private ApkPacker() {}

    private static final class Counting extends FilterOutputStream {
        long count;

        Counting(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    /**
     * The template's launcher icons are the only square PNGs of 48/72/96/144/192 px in res/.
     * They are recognised by their image size, not by their path, because the resource compiler
     * may rename or move them (shortened resource paths, "-v4" suffixes).
     *
     * @return the pixel size to render, or 0 if the entry is not a launcher icon
     */
    static int launcherIconSize(String name, byte[] data) {
        if (!name.startsWith("res/") || !name.endsWith(".png") || data.length < 24) return 0;
        if ((data[1] & 0xFF) != 'P' || (data[2] & 0xFF) != 'N' || (data[3] & 0xFF) != 'G') return 0;
        int w = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16) | ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);
        int h = ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16) | ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
        if (w != h) return 0;
        return (w == 48 || w == 72 || w == 96 || w == 144 || w == 192) ? w : 0;
    }

    static int iconSize(String density) {
        switch (density) {
            case "l": return 36;
            case "m": return 48;
            case "h": return 72;
            case "xh": return 96;
            case "xxh": return 144;
            default: return 192;
        }
    }

    public static void pack(File template, File out, byte[] manifest,
                            Map<String, byte[]> extraFiles, IntFunction<byte[]> iconProvider) throws IOException {
        try (ZipFile zf = new ZipFile(template);
             Counting co = new Counting(new BufferedOutputStream(new FileOutputStream(out)));
             ZipOutputStream zos = new ZipOutputStream(co)) {

            put(zos, co, "AndroidManifest.xml", manifest, false);

            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory() || name.startsWith("META-INF/") || name.equals("AndroidManifest.xml")) continue;
                if (extraFiles.containsKey(name)) continue;

                byte[] data = readAll(zf.getInputStream(e));
                int px = launcherIconSize(name, data);
                if (px > 0) data = iconProvider.apply(px);
                boolean stored = name.endsWith(".arsc") || e.getMethod() == ZipEntry.STORED;
                put(zos, co, name, data, stored);
            }
            for (Map.Entry<String, byte[]> x : extraFiles.entrySet()) {
                put(zos, co, x.getKey(), x.getValue(), false);
            }
            zos.finish();
        }
    }

    private static void put(ZipOutputStream zos, Counting co, String name, byte[] data, boolean stored) throws IOException {
        zos.closeEntry(); // make sure the byte counter is exact before the next header
        ZipEntry e = new ZipEntry(name);
        e.setTime(FIXED_TIME);
        if (stored) {
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setCrc(crc.getValue());
            int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
            long base = co.count + 30 + nameLen;
            int pad = (int) ((4 - (base % 4)) % 4);
            if (pad > 0) {
                byte[] extra = new byte[4 + pad];
                extra[0] = 0x35;
                extra[1] = (byte) 0xD9;
                extra[2] = (byte) pad;
                extra[3] = 0;
                e.setExtra(extra);
            }
        } else {
            e.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
