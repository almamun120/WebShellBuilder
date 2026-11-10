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
 * Download bridge, exposed to pages as "WebShellDl".
 *
 * request()/view() are called by the injected hooks (see PageHooks) when a page wants to save or open
 * a blob:/data:/PDF URL. The blob bytes themselves are received in chunks through begin/chunk/end,
 * and every one of those calls must carry the one-time token of the transfer that is in progress,
 * so a page cannot feed data in on its own.
 */
public final class BlobBridge {
    private final MainActivity a;
    private String token = "";
    private String name = "";
    private String mime = "";
    private boolean viewMode;
    private File tmp;
    private OutputStream out;
    private boolean failed;

    BlobBridge(MainActivity a) {
        this.a = a;
    }

    /** Builds the script that makes the page hand over the blob (cached Blob object first, XHR as fallback). */
    synchronized String prepare(String url, String fileName, String mimeType, boolean view) {
        close(true);
        token = UUID.randomUUID().toString();
        name = fileName;
        mime = mimeType == null ? "" : mimeType;
        viewMode = view;
        failed = false;
        try {
            tmp = new File(a.getCacheDir(), "dl_" + System.nanoTime() + ".part");
            out = new FileOutputStream(tmp);
        } catch (IOException e) {
            failed = true;
        }
        String T = JSONObject.quote(token);
        String U = JSONObject.quote(url);
        return "(function(){var T=" + T + ",U=" + U + ";"
                + "function F(){WebShellDl.fail(T)}"
                + "function S(b){try{WebShellDl.begin(T,b.type||'');var r=new FileReader();r.onerror=F;"
                + "r.onloadend=function(){try{var s=String(r.result||''),i=s.indexOf(',')+1,n=262144;"
                + "for(var p=i;p<s.length;p+=n){WebShellDl.chunk(T,s.substring(p,p+n))}WebShellDl.end(T)}catch(e){F()}};"
                + "r.readAsDataURL(b)}catch(e){F()}}"
                + "try{var c=window.__wsBlobs&&window.__wsBlobs[U];if(c){S(c);return}"
                + "var x=new XMLHttpRequest();x.open('GET',U,true);x.responseType='blob';x.onerror=F;"
                + "x.onload=function(){x.response?S(x.response):F()};x.send()}catch(e){F()}})();";
    }

    private boolean valid(String t) {
        return t != null && !token.isEmpty() && token.equals(t);
    }

    // ---------------------------------------------------------------- called by the page hooks

    /** The page wants to SAVE this url (a[download], jsPDF.save(), FileSaver...). */
    @JavascriptInterface
    public void request(String url, String fileName, String mimeType) {
        a.onPageDownload(url, fileName, mimeType, false);
    }

    /** The page wants to OPEN this url (window.open of a PDF). */
    @JavascriptInterface
    public void view(String url, String fileName, String mimeType) {
        a.onPageDownload(url, fileName, mimeType, true);
    }

    // ---------------------------------------------------------------- token protected transfer

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
        boolean view = viewMode;
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
        // a blob with no declared type may still be a PDF: look at the first bytes
        if (PdfViewerView.isPdf(f)) {
            m = "application/pdf";
            if (!n.toLowerCase().endsWith(".pdf")) n = n + ".pdf";
        }
        if (view && "application/pdf".equals(m)) a.openPdfFile(f, n, true);
        else a.saveDownloadedFile(f, n, m);
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
