package com.webshell.builder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal binary-XML (AXML) patcher for a compiled AndroidManifest.xml.
 *
 * It can: replace placeholder strings (package, label, versionName, deep-link host, share MIME types), change versionCode,
 * and drop {@code <uses-permission>} elements that are not in the allowed set.
 * Everything else is copied byte-for-byte, so the resource map and all string indices stay valid.
 */
public final class AxmlEditor {
    public static final String OLD_PACKAGE = "com.webshell.template";
    public static final String OLD_LABEL = "WebShellApp";
    public static final String OLD_VERSION_NAME = "0.0.0-template";

    private static final int CH_FILE = 0x0003;
    private static final int CH_STRINGS = 0x0001;
    private static final int CH_EL_START = 0x0102;
    private static final int CH_EL_END = 0x0103;

    private AxmlEditor() {}

    public static byte[] patch(byte[] in, String pkg, String label, String versionName,
                               int versionCode, Set<String> allowedPermissions) {
        return patch(in, pkg, label, versionName, versionCode, allowedPermissions, java.util.Collections.emptyMap());
    }

    /**
     * @param extra exact-match string replacements (placeholder to value), used for the deep-link
     *              hosts and the share-target MIME types.
     */
    public static byte[] patch(byte[] in, String pkg, String label, String versionName,
                               int versionCode, Set<String> allowedPermissions, Map<String, String> extra) {
        ByteBuffer b = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xFFFF) != CH_FILE) throw new IllegalArgumentException("Not a binary XML file");
        int fileSize = b.getInt(4);

        List<String> strings = null;
        List<byte[]> chunks = new ArrayList<>();
        int poolSlot = -1;
        int skipDepth = 0;
        int pos = 8;

        while (pos + 8 <= fileSize) {
            int type = b.getShort(pos) & 0xFFFF;
            int size = b.getInt(pos + 4);
            if (size <= 0) throw new IllegalArgumentException("Corrupt chunk");

            if (type == CH_STRINGS) {
                strings = readPool(b, pos);
                poolSlot = chunks.size();
                chunks.add(null);
            } else if (type == CH_EL_START) {
                if (strings == null) throw new IllegalArgumentException("Missing string pool");
                if (skipDepth > 0) {
                    skipDepth++;
                } else {
                    String el = strings.get(b.getInt(pos + 20));
                    int attrBase = pos + 16 + (b.getShort(pos + 24) & 0xFFFF);
                    int attrSize = b.getShort(pos + 26) & 0xFFFF;
                    int attrCount = b.getShort(pos + 28) & 0xFFFF;
                    byte[] raw = new byte[size];
                    System.arraycopy(in, pos, raw, 0, size);

                    if ("uses-permission".equals(el)) {
                        String name = null;
                        for (int i = 0; i < attrCount; i++) {
                            int a = attrBase + i * attrSize;
                            if ("name".equals(strings.get(b.getInt(a + 4)))) {
                                int rawIdx = b.getInt(a + 8);
                                if (rawIdx >= 0) name = strings.get(rawIdx);
                            }
                        }
                        if (name != null && !allowedPermissions.contains(name)) {
                            skipDepth = 1; // drop this element and its end tag
                            pos += size;
                            continue;
                        }
                    } else if ("manifest".equals(el)) {
                        ByteBuffer rb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                        for (int i = 0; i < attrCount; i++) {
                            int a = attrBase - pos + i * attrSize;
                            if ("versionCode".equals(strings.get(b.getInt(attrBase + i * attrSize + 4)))) {
                                rb.putInt(a + 16, versionCode);
                            }
                        }
                    }
                    chunks.add(raw);
                }
            } else if (type == CH_EL_END) {
                if (skipDepth > 0) {
                    skipDepth--;
                } else {
                    chunks.add(slice(in, pos, size));
                }
            } else {
                chunks.add(slice(in, pos, size));
            }
            pos += size;
        }
        if (strings == null) throw new IllegalArgumentException("No string pool");

        boolean foundPkg = false;
        for (int i = 0; i < strings.size(); i++) {
            String s = strings.get(i);
            if (s.equals(OLD_PACKAGE)) {
                strings.set(i, pkg);
                foundPkg = true;
            } else if (s.startsWith(OLD_PACKAGE + ".")) {
                // e.g. the content provider authority "<package>.share"
                strings.set(i, pkg + s.substring(OLD_PACKAGE.length()));
            } else if (extra.containsKey(s)) {
                strings.set(i, extra.get(s));
            } else if (s.equals(OLD_LABEL)) {
                strings.set(i, label);
            } else if (s.equals(OLD_VERSION_NAME)) {
                strings.set(i, versionName);
            }
        }
        if (!foundPkg) throw new IllegalArgumentException("Template manifest does not look like the expected template");

        chunks.set(poolSlot, writePool(strings));
        int total = 8;
        for (byte[] c : chunks) total += c.length;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) CH_FILE);
        out.putShort((short) 8);
        out.putInt(total);
        for (byte[] c : chunks) out.put(c);
        return out.array();
    }

    /** Returns the string pool of a binary XML file (useful for tests). */
    public static List<String> strings(byte[] in) {
        ByteBuffer b = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 8;
        int fileSize = b.getInt(4);
        while (pos + 8 <= fileSize) {
            int type = b.getShort(pos) & 0xFFFF;
            int size = b.getInt(pos + 4);
            if (type == CH_STRINGS) return readPool(b, pos);
            pos += size;
        }
        throw new IllegalArgumentException("No string pool");
    }

    private static byte[] slice(byte[] in, int pos, int size) {
        byte[] r = new byte[size];
        System.arraycopy(in, pos, r, 0, size);
        return r;
    }

    private static List<String> readPool(ByteBuffer b, int pos) {
        int count = b.getInt(pos + 8);
        int flags = b.getInt(pos + 16);
        int strStart = b.getInt(pos + 20);
        boolean utf8 = (flags & 0x100) != 0;
        int offTable = pos + (b.getShort(pos + 2) & 0xFFFF);
        List<String> list = new ArrayList<>(count);
        byte[] arr = b.array();
        for (int i = 0; i < count; i++) {
            int p = pos + strStart + b.getInt(offTable + i * 4);
            if (utf8) {
                int l = arr[p++] & 0xFF;
                if ((l & 0x80) != 0) l = ((l & 0x7F) << 8) | (arr[p++] & 0xFF);
                int bl = arr[p++] & 0xFF;
                if ((bl & 0x80) != 0) bl = ((bl & 0x7F) << 8) | (arr[p++] & 0xFF);
                list.add(new String(arr, p, bl, StandardCharsets.UTF_8));
            } else {
                int l = b.getShort(p) & 0xFFFF;
                p += 2;
                if ((l & 0x8000) != 0) {
                    l = ((l & 0x7FFF) << 16) | (b.getShort(p) & 0xFFFF);
                    p += 2;
                }
                char[] cs = new char[l];
                for (int k = 0; k < l; k++) cs[k] = (char) (b.getShort(p + k * 2) & 0xFFFF);
                list.add(new String(cs));
            }
        }
        return list;
    }

    private static byte[] writePool(List<String> s) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offs = new int[s.size()];
        for (int i = 0; i < s.size(); i++) {
            offs[i] = data.size();
            String str = s.get(i);
            int len = str.length();
            if (len > 0x7FFF) {
                w16(data, 0x8000 | (len >>> 16));
                w16(data, len & 0xFFFF);
            } else {
                w16(data, len);
            }
            for (int k = 0; k < len; k++) w16(data, str.charAt(k));
            w16(data, 0);
        }
        while (data.size() % 4 != 0) data.write(0);
        int strStart = 28 + s.size() * 4;
        int size = strStart + data.size();
        ByteBuffer o = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        o.putShort((short) CH_STRINGS);
        o.putShort((short) 28);
        o.putInt(size);
        o.putInt(s.size());
        o.putInt(0);        // style count
        o.putInt(0);        // flags: UTF-16, unsorted
        o.putInt(strStart);
        o.putInt(0);        // styles start
        for (int off : offs) o.putInt(off);
        o.put(data.toByteArray());
        return o.array();
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }
}
