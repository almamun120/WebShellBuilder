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

                byte[] data;
                Matcher m = ICON.matcher(name);
                if (m.matches()) {
                    data = iconProvider.apply(iconSize(m.group(1)));
                } else {
                    data = readAll(zf.getInputStream(e));
                }
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
