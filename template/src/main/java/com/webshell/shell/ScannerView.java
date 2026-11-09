package com.webshell.shell;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen QR / barcode scanner (Camera2 preview + ZXing core decoder).
 * Added on top of the WebView by MainActivity; the camera permission must already be granted.
 */
final class ScannerView extends FrameLayout {
    interface Callback {
        void onResult(String text, String format);

        void onCancel();
    }

    private final Callback cb;
    private final int accent;
    private final String hint;
    private final TextureView tv;
    private final FinderOverlay overlay;
    private final MultiFormatReader zx = new MultiFormatReader();

    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder req;
    private Size size;
    private boolean torch;
    private boolean flashAvailable;
    private volatile boolean closed;
    private volatile boolean done;
    private long lastDecode;
    private int frameNo;

    ScannerView(Context c, int accent, String hint, Callback cb) {
        super(c);
        this.cb = cb;
        this.accent = accent;
        this.hint = hint;
        setBackgroundColor(Color.BLACK);
        setClickable(true);

        tv = new TextureView(c);
        addView(tv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        overlay = new FinderOverlay(c);
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        TextView close = roundButton("✕");
        close.setOnClickListener(v -> cancel());
        LayoutParams cp = new LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.START);
        cp.setMargins(dp(16), dp(16), 0, 0);
        addView(close, cp);

        TextView flash = roundButton("🔦");
        flash.setOnClickListener(v -> toggleTorch(flash));
        LayoutParams fp = new LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.END);
        fp.setMargins(0, dp(16), dp(16), 0);
        addView(flash, fp);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> fmts = new ArrayList<>(Arrays.asList(
                BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
                BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.CODE_128,
                BarcodeFormat.ITF, BarcodeFormat.CODABAR));
        hints.put(DecodeHintType.POSSIBLE_FORMATS, fmts);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        zx.setHints(hints);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView roundButton(String label) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x66000000);
        t.setBackground(g);
        return t;
    }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        closed = false;
        done = false;
        thread = new HandlerThread("scanner");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (tv.isAvailable()) {
            openCamera();
        } else {
            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture s) {
                }
            });
        }
    }

    /** Releases the camera. Safe to call more than once. */
    void stop() {
        if (closed) return;
        closed = true;
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        session = null;
        camera = null;
        reader = null;
        if (thread != null) thread.quitSafely();
    }

    private void cancel() {
        if (done) return;
        done = true;
        stop();
        cb.onCancel();
    }

    void dismiss() {
        cancel();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    // ------------------------------------------------------------------ camera

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (closed) return;
        try {
            CameraManager m = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String cid : m.getCameraIdList()) {
                Integer facing = m.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    id = cid;
                    break;
                }
            }
            if (id == null && m.getCameraIdList().length > 0) id = m.getCameraIdList()[0];
            if (id == null) {
                fail();
                return;
            }
            CameraCharacteristics ch = m.getCameraCharacteristics(id);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                fail();
                return;
            }
            Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            flashAvailable = fl != null && fl;

            Set<Size> yuv = new HashSet<>(Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)));
            Size best = null;
            for (Size s : map.getOutputSizes(SurfaceTexture.class)) {
                if (!yuv.contains(s)) continue;
                long area = (long) s.getWidth() * s.getHeight();
                if (area > 1280L * 960L) continue;
                if (best == null || area > (long) best.getWidth() * best.getHeight()) best = s;
            }
            if (best == null) {
                Size[] all = map.getOutputSizes(ImageFormat.YUV_420_888);
                best = all[all.length - 1];
            }
            size = best;
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, bg);
            post(this::fit);

            m.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cd) {
                    if (closed) {
                        cd.close();
                        return;
                    }
                    camera = cd;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice cd) {
                    cd.close();
                }

                @Override
                public void onError(CameraDevice cd, int error) {
                    cd.close();
                    fail();
                }
            }, bg);
        } catch (Exception e) {
            fail();
        }
    }

    private void createSession() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture();
            if (st == null || camera == null || reader == null) {
                fail();
                return;
            }
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            Surface preview = new Surface(st);
            Surface rs = reader.getSurface();
            req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(preview);
            req.addTarget(rs);
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(preview, rs), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (closed) {
                        s.close();
                        return;
                    }
                    session = s;
                    try {
                        s.setRepeatingRequest(req.build(), null, bg);
                    } catch (Exception e) {
                        fail();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    fail();
                }
            }, bg);
        } catch (CameraAccessException | IllegalStateException | SecurityException e) {
            fail();
        }
    }

    private void fail() {
        if (done) return;
        done = true;
        post(() -> {
            stop();
            cb.onCancel();
        });
    }

    private void toggleTorch(TextView btn) {
        if (!flashAvailable || req == null || session == null) return;
        torch = !torch;
        btn.setAlpha(torch ? 1f : 0.7f);
        bg.post(() -> {
            try {
                req.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                if (session != null) session.setRepeatingRequest(req.build(), null, bg);
            } catch (Exception ignored) {
            }
        });
    }

    // ------------------------------------------------------------------ preview geometry

    @SuppressWarnings("deprecation")
    private int displayRotation() {
        try {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            return wm.getDefaultDisplay().getRotation();
        } catch (Exception e) {
            return Surface.ROTATION_0;
        }
    }

    /** Center-crops the preview to fill the view and fixes rotation for landscape. */
    private void fit() {
        if (size == null || getWidth() == 0 || getHeight() == 0) return;
        int vw = getWidth();
        int vh = getHeight();
        int rot = displayRotation();
        boolean land = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270;
        Matrix m = new Matrix();
        if (land) {
            tv.setLayoutParams(new LayoutParams(vw, vh));
            RectF viewRect = new RectF(0, 0, vw, vh);
            RectF bufRect = new RectF(0, 0, size.getHeight(), size.getWidth());
            float cx = viewRect.centerX();
            float cy = viewRect.centerY();
            bufRect.offset(cx - bufRect.centerX(), cy - bufRect.centerY());
            m.setRectToRect(viewRect, bufRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(vh / (float) size.getHeight(), vw / (float) size.getWidth());
            m.postScale(scale, scale, cx, cy);
            m.postRotate(90 * (rot - 2), cx, cy);
        } else {
            int bw = size.getHeight();
            int bh = size.getWidth();
            float s = Math.max(vw / (float) bw, vh / (float) bh);
            tv.setLayoutParams(new LayoutParams(Math.round(bw * s), Math.round(bh * s), Gravity.CENTER));
            if (rot == Surface.ROTATION_180) m.postRotate(180, Math.round(bw * s) / 2f, Math.round(bh * s) / 2f);
        }
        tv.setTransform(m);
    }

    // ------------------------------------------------------------------ decoding

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null || done || closed) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastDecode < 90) return;
            lastDecode = now;

            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int w = img.getWidth();
            int h = img.getHeight();
            int rs = p.getRowStride();
            byte[] y = new byte[w * h];
            for (int row = 0; row < h; row++) {
                buf.position(row * rs);
                buf.get(y, row * w, w);
            }
            Result res = decode(y, w, h);
            if (res == null && (++frameNo & 1) == 0) {
                // 1-D barcodes must be horizontal: also try the frame rotated by 90 degrees
                byte[] rot = new byte[w * h];
                for (int yy = 0; yy < h; yy++) {
                    for (int xx = 0; xx < w; xx++) rot[xx * h + (h - 1 - yy)] = y[yy * w + xx];
                }
                res = decode(rot, h, w);
            }
            if (res != null) deliver(res);
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private Result decode(byte[] data, int w, int h) {
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
            return zx.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
        } catch (NotFoundException e) {
            return null;
        } finally {
            zx.reset();
        }
    }

    private void deliver(Result res) {
        if (done) return;
        done = true;
        final String text = res.getText();
        final String fmt = res.getBarcodeFormat() == null ? "" : res.getBarcodeFormat().name();
        post(() -> {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            stop();
            cb.onResult(text, fmt);
        });
    }

    // ------------------------------------------------------------------ viewfinder

    private final class FinderOverlay extends View {
        private final Paint dim = new Paint();
        private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private long t0 = SystemClock.uptimeMillis();

        FinderOverlay(Context c) {
            super(c);
            dim.setColor(0x99000000);
            corner.setColor(accent);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(dp(4));
            corner.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(accent);
            line.setStrokeWidth(dp(2));
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(dp(14));
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            float side = Math.min(w, h) * 0.68f;
            float l = (w - side) / 2f;
            float t = h * 0.42f - side / 2f;
            box.set(l, t, l + side, t + side);

            c.drawRect(0, 0, w, box.top, dim);
            c.drawRect(0, box.bottom, w, h, dim);
            c.drawRect(0, box.top, box.left, box.bottom, dim);
            c.drawRect(box.right, box.top, w, box.bottom, dim);

            float k = dp(28);
            c.drawLine(box.left, box.top, box.left + k, box.top, corner);
            c.drawLine(box.left, box.top, box.left, box.top + k, corner);
            c.drawLine(box.right, box.top, box.right - k, box.top, corner);
            c.drawLine(box.right, box.top, box.right, box.top + k, corner);
            c.drawLine(box.left, box.bottom, box.left + k, box.bottom, corner);
            c.drawLine(box.left, box.bottom, box.left, box.bottom - k, corner);
            c.drawLine(box.right, box.bottom, box.right - k, box.bottom, corner);
            c.drawLine(box.right, box.bottom, box.right, box.bottom - k, corner);

            float phase = ((SystemClock.uptimeMillis() - t0) % 1800) / 1800f;
            float tri = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
            float ly = box.top + dp(8) + (side - dp(16)) * tri;
            c.drawLine(box.left + dp(10), ly, box.right - dp(10), ly, line);

            c.drawText(hint, w / 2f, box.bottom + dp(36), text);
            postInvalidateOnAnimation();
        }
    }
}
