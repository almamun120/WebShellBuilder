package com.webshell.shell;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Sends an existing PDF file to the Android print dialog. */
final class PdfPrintAdapter extends PrintDocumentAdapter {
    private final File file;
    private final String name;

    PdfPrintAdapter(File file, String name) {
        this.file = file;
        this.name = name;
    }

    @Override
    public void onLayout(PrintAttributes oldAttrs, PrintAttributes newAttrs, CancellationSignal cancel,
                         LayoutResultCallback cb, Bundle extras) {
        if (cancel.isCanceled()) {
            cb.onLayoutCancelled();
            return;
        }
        PrintDocumentInfo info = new PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build();
        cb.onLayoutFinished(info, true);
    }

    @Override
    public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal cancel, WriteResultCallback cb) {
        new Thread(() -> {
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel.isCanceled()) {
                        cb.onWriteCancelled();
                        return;
                    }
                    out.write(buf, 0, n);
                }
                cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) {
                cb.onWriteFailed(String.valueOf(e.getMessage()));
            }
        }).start();
    }
}
