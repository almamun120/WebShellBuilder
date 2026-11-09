package com.webshell.shell;

import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Receives the bytes of a blob: download from the page, in chunks. Exposed to every page as
 * "WebShellDl", but each call must carry the one-time token of the download that is in progress,
 * so a page cannot use it on its own.
 */
final class BlobBridge {
    private final MainActivity a;
    private String token = "";
    private String name = "";
    private String mime = "";
    private File tmp;
    private OutputStream out;
    private boolean failed;

    BlobBridge(MainActivity a) {
        this.a = a;
    }

    /** Builds the script that makes the page hand over the blob. */
    synchronized String prepare(String url, String fileName, String mimeType) {
        close(true);
        token = UUID.randomUUID().toString();
        name = fileName;
        mime = mimeType == null ? "" : mimeType;
        failed = false;
        try {
            tmp = new File(a.getCacheDir(), "dl_" + System.nanoTime() + ".part");
            out = new FileOutputStream(tmp);
        } catch (IOException e) {
            failed = true;
        }
        String T = JSONObject.quote(token);
        String U = JSONObject.quote(url);
        return "(function(){var T=" + T + ",U=" + U + ";try{var x=new XMLHttpRequest();x.open('GET',U,true);"
                + "x.responseType='blob';x.onerror=function(){WebShellDl.fail(T)};"
                + "x.onload=function(){var b=x.response;if(!b){WebShellDl.fail(T);return}"
                + "WebShellDl.begin(T,b.type||'');var r=new FileReader();r.onerror=function(){WebShellDl.fail(T)};"
                + "r.onloadend=function(){var s=String(r.result||''),i=s.indexOf(',')+1,n=262144;"
                + "for(var p=i;p<s.length;p+=n){WebShellDl.chunk(T,s.substring(p,p+n))}WebShellDl.end(T)};"
                + "r.readAsDataURL(b)};x.send()}catch(e){WebShellDl.fail(T)}})();";
    }

    private boolean valid(String t) {
        return t != null && !token.isEmpty() && token.equals(t);
    }

    @JavascriptInterface
    public synchronized void begin(String t, String blobType) {
        if (!valid(t)) return;
        if (blobType != null && !blobType.isEmpty() && (mime.isEmpty() || mime.equals("application/octet-stream"))) {
            mime = blobType;
        }
    }

    @JavascriptInterface
    public synchronized void chunk(String t, String b64) {
        if (!valid(t) || failed || out == null) return;
        try {
            out.write(Base64.decode(b64, Base64.DEFAULT));
        } catch (Exception e) {
            failed = true;
        }
    }

    @JavascriptInterface
    public synchronized void end(String t) {
        if (!valid(t)) return;
        File f = tmp;
        tmp = null;
        String n = name;
        String m = mime;
        boolean bad = failed;
        close(false);
        token = "";
        if (n.endsWith(".bin") && !m.isEmpty() && !m.equals("application/octet-stream")) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(m);
            if (ext != null) n = n.substring(0, n.length() - 4) + "." + ext;
        }
        if (bad || f == null) {
            a.downloadFailed();
            return;
        }
        a.saveDownloadedFile(f, n, m);
    }

    @JavascriptInterface
    public synchronized void fail(String t) {
        if (!valid(t)) return;
        close(true);
        token = "";
        a.downloadFailed();
    }

    private void close(boolean deleteTmp) {
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        out = null;
        if (deleteTmp && tmp != null) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            tmp = null;
        }
    }
}
