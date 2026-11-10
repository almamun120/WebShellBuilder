package com.webshell.shell;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in PDF viewer: continuous vertical scrolling, pinch / double-tap zoom, "go to page",
 * and toolbar actions (download, share, print, open in another app). Uses the framework
 * PdfRenderer, so it needs no extra library. Text search / selection is not available.
 */
final class PdfViewerView extends LinearLayout {
    interface Listener {
        void onClose();

        void onDownload(File f, String name);

        void onShare(File f, String name);

        void onPrint(File f, String name);

        void onExternal(File f, String name);
    }

    /** True when the file starts with the "%PDF-" signature. */
    static boolean isPdf(File f) {
        if (f == null || !f.isFile() || f.length() < 8) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1024];
            int n = in.read(b);
            // some generators write a few bytes before the signature
            for (int i = 0; i + 4 < n; i++) {
                if (b[i] == '%' && b[i + 1] == 'P' && b[i + 2] == 'D' && b[i + 3] == 'F' && b[i + 4] == '-') return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int accent;
    private final Listener listener;
    private final String title;
    private final PageView pages;
    private final View loadingBox;
    private final TextView loadingText;
    private final ProgressBar loadingBar;
    private final LinearLayout errorBox;
    private final TextView errorText;
    private final TextView pill;
    private File file;
    private boolean released;

    PdfViewerView(Context c, int accent, String title, Listener l) {
        super(c);
        this.accent = accent;
        this.title = title;
        this.listener = l;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF2B2B2E);
        setClickable(true);

        // ---- toolbar
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1C1C1E);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView close = chip(c, "✕");
        close.setOnClickListener(v -> l.onClose());
        bar.addView(close, new LayoutParams(dp(42), dp(42)));

        TextView name = new TextView(c);
        name.setText(title);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setPadding(dp(10), 0, dp(10), 0);
        bar.addView(name, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        addAction(bar, "⬇", () -> {
            if (file != null) l.onDownload(file, title);
        });
        addAction(bar, "⤴", () -> {
            if (file != null) l.onShare(file, title);
        });
        addAction(bar, "🖨", () -> {
            if (file != null) l.onPrint(file, title);
        });
        addAction(bar, "↗", () -> {
            if (file != null) l.onExternal(file, title);
        });
        addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- body
        FrameLayout body = new FrameLayout(c);
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pages = new PageView(c);
        body.addView(pages, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pill = new TextView(c);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(13);
        pill.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0xCC000000);
        pg.setCornerRadius(dp(18));
        pill.setBackground(pg);
        pill.setVisibility(GONE);
        pill.setOnClickListener(v -> askPage());
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pp.bottomMargin = dp(18);
        body.addView(pill, pp);

        // loading
        LinearLayout lb = new LinearLayout(c);
        lb.setOrientation(VERTICAL);
        lb.setGravity(Gravity.CENTER);
        loadingBar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setMax(100);
        loadingBar.setIndeterminate(true);
        loadingBar.setProgressTintList(ColorStateList.valueOf(accent));
        loadingBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        lb.addView(loadingBar, new LayoutParams(dp(180), dp(6)));
        loadingText = new TextView(c);
        loadingText.setTextColor(0xCCFFFFFF);
        loadingText.setTextSize(14);
        loadingText.setPadding(0, dp(12), 0, 0);
        loadingText.setText("PDF লোড হচ্ছে...");
        lb.addView(loadingText);
        loadingBox = lb;
        body.addView(lb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // error
        errorBox = new LinearLayout(c);
        errorBox.setOrientation(VERTICAL);
        errorBox.setGravity(Gravity.CENTER);
        errorBox.setPadding(dp(32), dp(32), dp(32), dp(32));
        errorBox.setVisibility(GONE);
        errorText = new TextView(c);
        errorText.setTextColor(Color.WHITE);
        errorText.setTextSize(15);
        errorText.setGravity(Gravity.CENTER);
        errorBox.addView(errorText);
        TextView ext = actionButton(c, "অন্য অ্যাপে খুলুন");
        ext.setOnClickListener(v -> {
            if (file != null) l.onExternal(file, title);
        });
        LayoutParams ep = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        ep.topMargin = dp(18);
        errorBox.addView(ext, ep);
        body.addView(errorBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView chip(Context c, String label) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x33FFFFFF);
        t.setBackground(g);
        return t;
    }

    private void addAction(LinearLayout bar, String label, Runnable r) {
        TextView t = chip(getContext(), label);
        t.setOnClickListener(v -> r.run());
        LayoutParams lp = new LayoutParams(dp(42), dp(42));
        lp.leftMargin = dp(6);
        bar.addView(t, lp);
    }

    private TextView actionButton(Context c, String label) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), 0, dp(22), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(accent);
        g.setCornerRadius(dp(22));
        t.setBackground(g);
        return t;
    }

    // ------------------------------------------------------------------ state shown to the user

    /** Shows download progress while the PDF is being fetched. percent &lt; 0 means unknown. */
    void setProgress(int percent) {
        if (released || pages.hasDocument()) return;
        if (percent < 0) {
            loadingBar.setIndeterminate(true);
            loadingText.setText("PDF ডাউনলোড হচ্ছে...");
        } else {
            loadingBar.setIndeterminate(false);
            loadingBar.setProgress(percent);
            loadingText.setText("PDF ডাউনলোড হচ্ছে... " + percent + "%");
        }
    }

    /** Opens the file. Safe to call from the UI thread; parsing happens in the background. */
    void load(File f) {
        file = f;
        loadingBar.setIndeterminate(true);
        loadingText.setText("PDF খোলা হচ্ছে...");
        loadingBox.setVisibility(VISIBLE);
        errorBox.setVisibility(GONE);
        pages.open(f);
    }

    void showError(String msg) {
        loadingBox.setVisibility(GONE);
        pill.setVisibility(GONE);
        errorText.setText(msg);
        errorBox.setVisibility(VISIBLE);
    }

    /** Called when the page count / current page changes. */
    private void pageChanged(int cur, int count) {
        pill.setText((cur + 1) + " / " + count);
        pill.setVisibility(count > 0 ? VISIBLE : GONE);
    }

    private void askPage() {
        final int count = pages.pageCount();
        if (count <= 0) return;
        EditText et = new EditText(getContext());
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("1 - " + count);
        new AlertDialog.Builder(getContext())
                .setTitle("পেজে যান")
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        int n = Integer.parseInt(et.getText().toString().trim());
                        pages.goTo(Math.max(1, Math.min(count, n)) - 1);
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    void release() {
        released = true;
        pages.close();
    }

    // ================================================================== the page canvas

    private final class PageView extends View {
        private final Object lock = new Object();
        private final ExecutorService exec = Executors.newSingleThreadExecutor();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint white = new Paint();
        private final RectF dst = new RectF();
        private final OverScroller scroller;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestures;
        private final LruCache<Integer, Entry> cache;
        private final Set<Integer> pending = new HashSet<>();
        private final Set<Integer> failedPages = new HashSet<>();
        private final int maxPixels;
        private final float gap;

        private PdfRenderer renderer;
        private ParcelFileDescriptor pfd;
        private int count;
        private float[] aspect = new float[0];
        private float[] top = new float[0];
        private float[] height = new float[0];
        private float totalH;
        private float zoom = 1f;
        private float sx;
        private float sy;
        private int lastReported = -1;
        private volatile int visFirst;
        private volatile int visLast;
        private volatile boolean closed;

        PageView(Context c) {
            super(c);
            gap = dp(10);
            white.setColor(Color.WHITE);
            scroller = new OverScroller(c);
            long maxBytes = Math.max(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
            maxPixels = (int) Math.min(8_000_000L, maxBytes / 2 / 4);
            cache = new LruCache<Integer, Entry>((int) Math.min(Integer.MAX_VALUE, maxBytes)) {
                @Override
                protected int sizeOf(Integer key, Entry e) {
                    return e.bmp.getByteCount();
                }
            };
            scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoomAbout(d.getFocusX(), d.getFocusY(), zoom * d.getScaleFactor());
                    return true;
                }
            });
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    scroller.forceFinished(true);
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    sx += dx;
                    sy += dy;
                    clampScroll();
                    invalidate();
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, maxX(), 0, maxY());
                    postInvalidateOnAnimation();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    zoomAbout(e.getX(), e.getY(), zoom > 1.05f ? 1f : 2.5f);
                    return true;
                }
            });
        }

        boolean hasDocument() {
            return renderer != null;
        }

        int pageCount() {
            return count;
        }

        // ------------------------------------------------------------ document

        void open(File f) {
            submit(() -> {
                PdfRenderer r = null;
                ParcelFileDescriptor p = null;
                try {
                    p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                    r = new PdfRenderer(p);
                    int n = r.getPageCount();
                    float[] asp = new float[n];
                    for (int i = 0; i < n; i++) {
                        PdfRenderer.Page pg = r.openPage(i);
                        try {
                            asp[i] = pg.getWidth() > 0 ? pg.getHeight() / (float) pg.getWidth() : 1.414f;
                        } finally {
                            pg.close();
                        }
                    }
                    final PdfRenderer fr = r;
                    final ParcelFileDescriptor fp = p;
                    ui.post(() -> {
                        if (closed || released) {
                            closeQuietly(fr, fp);
                            return;
                        }
                        synchronized (lock) {
                            renderer = fr;
                            pfd = fp;
                        }
                        count = n;
                        aspect = asp;
                        layoutPages();
                        loadingBox.setVisibility(GONE);
                        if (n == 0) showError("PDF-এ কোনো পেজ নেই");
                        invalidate();
                    });
                } catch (SecurityException e) {
                    closeQuietly(r, p);
                    ui.post(() -> showError("এই PDF পাসওয়ার্ড দিয়ে সুরক্ষিত, এখানে খোলা যাচ্ছে না।"));
                } catch (Exception | OutOfMemoryError e) {
                    closeQuietly(r, p);
                    ui.post(() -> showError("PDF খোলা যায়নি। ফাইলটি নষ্ট বা সাপোর্টেড নয়।"));
                }
            });
        }

        private void closeQuietly(PdfRenderer r, ParcelFileDescriptor p) {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
            try {
                if (p != null) p.close();
            } catch (Exception ignored) {
            }
        }

        void close() {
            closed = true;
            cache.evictAll();
            submit(() -> {
                synchronized (lock) {
                    closeQuietly(renderer, pfd);
                    renderer = null;
                    pfd = null;
                }
            });
            exec.shutdown();
        }

        // ------------------------------------------------------------ layout & scrolling

        private void layoutPages() {
            int w = getWidth();
            top = new float[count];
            height = new float[count];
            float y = gap;
            for (int i = 0; i < count; i++) {
                top[i] = y;
                height[i] = w * aspect[i];
                y += height[i] + gap;
            }
            totalH = y;
            clampScroll();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            cache.evictAll();
            pending.clear();
            if (count > 0) layoutPages();
        }

        private int maxX() {
            return (int) Math.max(0, getWidth() * zoom - getWidth());
        }

        private int maxY() {
            return (int) Math.max(0, totalH * zoom - getHeight());
        }

        private void clampScroll() {
            sx = Math.max(0, Math.min(sx, maxX()));
            sy = Math.max(0, Math.min(sy, maxY()));
        }

        private void zoomAbout(float fx, float fy, float target) {
            float nz = Math.max(1f, Math.min(4f, target));
            float cx = (sx + fx) / zoom;
            float cy = (sy + fy) / zoom;
            zoom = nz;
            sx = cx * zoom - fx;
            sy = cy * zoom - fy;
            clampScroll();
            invalidate();
        }

        void goTo(int page) {
            if (page < 0 || page >= count) return;
            scroller.forceFinished(true);
            sy = top[page] * zoom - gap;
            clampScroll();
            invalidate();
        }

        private int pageAt(float contentY) {
            int lo = 0;
            int hi = count - 1;
            int res = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (top[mid] <= contentY) {
                    res = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return res;
        }

        @Override
        public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                sx = scroller.getCurrX();
                sy = scroller.getCurrY();
                clampScroll();
                postInvalidateOnAnimation();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            if (!scaleDetector.isInProgress()) gestures.onTouchEvent(ev);
            return true;
        }

        // ------------------------------------------------------------ drawing & rendering

        private int levelFor(float z) {
            return Math.max(1, Math.min(4, (int) Math.ceil(z - 0.01f)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xFF3A3A3D);
            if (renderer == null || count == 0) return;
            int w = getWidth();
            int h = getHeight();
            int first = pageAt(sy / zoom);
            int last = pageAt((sy + h) / zoom);
            visFirst = first;
            visLast = last;
            int need = levelFor(zoom);
            for (int i = first; i <= last && i < count; i++) {
                float t = top[i] * zoom - sy;
                dst.set(-sx, t, -sx + w * zoom, t + height[i] * zoom);
                canvas.drawRect(dst, white);
                Entry e = cache.get(i);
                if (e != null) canvas.drawBitmap(e.bmp, null, dst, paint);
                if (e == null || e.level < need) schedule(i, need);
            }
            int cur = pageAt((sy + h / 2f) / zoom);
            if (cur != lastReported) {
                lastReported = cur;
                final int c = cur;
                post(() -> pageChanged(c, count));
            }
        }

        private void submit(Runnable r) {
            try {
                exec.execute(r);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // viewer already closed
            }
        }

        private void schedule(final int page, final int level) {
            if (closed || failedPages.contains(page) || !pending.add(page)) return;
            final int w = getWidth();
            final float ph = height[page];
            submit(() -> {
                Bitmap bmp = null;
                boolean skipped = false;
                try {
                    if (closed) return;
                    if (page < visFirst - 1 || page > visLast + 1) {
                        skipped = true;
                        return;
                    }
                    int bw = Math.max(1, Math.round(w * (float) level));
                    int bh = Math.max(1, Math.round(ph * level));
                    if ((long) bw * bh > maxPixels) {
                        float f = (float) Math.sqrt(maxPixels / (double) ((long) bw * bh));
                        bw = Math.max(1, (int) (bw * f));
                        bh = Math.max(1, (int) (bh * f));
                    }
                    synchronized (lock) {
                        if (renderer == null) return;
                        PdfRenderer.Page pg = renderer.openPage(page);
                        try {
                            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        } finally {
                            pg.close();
                        }
                    }
                } catch (Exception | OutOfMemoryError e) {
                    bmp = null;
                } finally {
                    final Bitmap done = bmp;
                    final boolean skip = skipped;
                    ui.post(() -> {
                        pending.remove(page);
                        if (closed) return;
                        if (done != null) {
                            cache.put(page, new Entry(done, level));
                        } else if (!skip) {
                            failedPages.add(page); // do not retry a page that cannot be rendered
                        }
                        invalidate();
                    });
                }
            });
        }
    }

    private static final class Entry {
        final Bitmap bmp;
        final int level;

        Entry(Bitmap b, int level) {
            this.bmp = b;
            this.level = level;
        }
    }
}
