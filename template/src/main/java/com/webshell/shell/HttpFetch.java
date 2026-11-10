package com.webshell.shell;

import android.webkit.URLUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Small blocking HTTP(S) downloader that keeps the WebView session (cookies, user agent, referer). */
final class HttpFetch {
    private HttpFetch() {}

    interface Progress {
        /** percent is -1 when the length is unknown. */
        void onProgress(long done, long total, int percent);
    }

    static final class Result {
        String mime = "";
        String fileName = "";
        long size;
    }

    static final class Cancel {
        volatile boolean cancelled;
    }

    static Result get(String url, String ua, String cookie, String referer, boolean ignoreSsl,
                      File dest, Progress p, Cancel cancel) throws IOException {
        String cur = url;
        HttpURLConnection c = null;
        for (int hop = 0; hop < 6; hop++) {
            c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Accept", "*/*");
            if (ignoreSsl && c instanceof HttpsURLConnection) relax((HttpsURLConnection) c);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect without location");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            break;
        }
        if (c == null) throw new IOException("no connection");

        Result r = new Result();
        String ct = c.getContentType();
        if (ct != null) {
            int semi = ct.indexOf(';');
            r.mime = (semi >= 0 ? ct.substring(0, semi) : ct).trim().toLowerCase();
        }
        r.fileName = URLUtil.guessFileName(cur, c.getHeaderField("Content-Disposition"), r.mime.isEmpty() ? null : r.mime);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768];
            long done = 0;
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.cancelled) throw new IOException("cancelled");
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (p != null && now - lastReport > 150) {
                    lastReport = now;
                    p.onProgress(done, total, total > 0 ? (int) (done * 100 / total) : -1);
                }
            }
            r.size = done;
            if (p != null) p.onProgress(done, total, 100);
        } finally {
            c.disconnect();
        }
        return r;
    }

    /** Accept any certificate: only used when the build was made with "ignore SSL errors". */
    private static void relax(HttpsURLConnection c) {
        try {
            TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier((HostnameVerifier) (host, session) -> true);
        } catch (Exception ignored) {
        }
    }
}
