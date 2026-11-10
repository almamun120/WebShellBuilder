package com.webshell.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in audio / video player.
 *
 * Features: play / pause, seek bar, +-10 s, previous / next (playlist), speed, loop, aspect modes
 * (fit / fill / stretch / 16:9), double-tap to seek, swipe for brightness / volume / seek, pinch to
 * fill, long-press for 2x, screen lock, sleep timer, audio-track and subtitle selection, remembers the
 * position of every file, audio focus + headset-unplug handling, lock-screen / notification controls
 * (MediaSession + MediaService, so audio keeps playing in the background) and Picture-in-Picture.
 */
final class MediaPlayerView extends FrameLayout implements TextureView.SurfaceTextureListener {
    interface Listener {
        void onClose();

        void onError(String url, String title);

        void onExternal(String url);

        void onDownload(String url, String title);

        void onPip();

        void onRotate();

        /** Playing state, title or video size changed (used to refresh PiP params and the notification). */
        void onStateChanged();
    }

    static final class Item {
        final String url;
        final String title;

        Item(String url, String title) {
            this.url = url;
            this.title = title == null || title.isEmpty() ? "" : title;
        }
    }

    /** The player that is currently on screen (read by MediaService). */
    static MediaPlayerView active;

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final String[] ASPECT_LABELS = {"ফিট", "ফিল", "স্ট্রেচ", "16:9"};
    private static int speedIdx = 2;

    private static final int M_NONE = 0;
    private static final int M_SEEK = 1;
    private static final int M_BRIGHT = 2;
    private static final int M_VOL = 3;

    private final Context ctx;
    private final Listener l;
    private final int accent;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final AudioManager am;
    private final SharedPreferences prefs;

    // views
    private final TextureView tv;
    private final LinearLayout artBox;
    private final TextView artTitle;
    private final TextView subtitle;
    private final View touch;
    private final FrameLayout controls;
    private final TextView titleView;
    private final TextView playBtn;
    private final TextView prevBtn;
    private final TextView nextBtn;
    private final TextView pipBtn;
    private final TextView curTime;
    private final TextView durTime;
    private final SeekBar seek;
    private final TextView speedBtn;
    private final TextView aspectBtn;
    private final TextView loopBtn;
    private final TextView lockBtn;
    private final TextView unlockBtn;
    private final TextView hud;
    private final ProgressBar spinner;

    // player state
    private final List<Item> items = new ArrayList<>();
    private int index;
    private Map<String, String> headers;
    private ExoPlayer mp;
    private Surface surface;
    private boolean prepared;
    private boolean released;
    private boolean audioOnly;
    private boolean loop;
    private boolean locked;
    private boolean pip;
    private boolean controlsOn = true;
    private boolean userSeeking;
    private boolean pausedByFocus;
    private boolean sleepAtEnd;
    private boolean longPressSpeed;
    private int aspectMode;
    private int videoW;
    private int videoH;
    private int duration;
    private long lastSave;
    private final List<TrackEntry> tracks = new ArrayList<>();
    private boolean live;
    private int attempt;

    /** One selectable audio / video / subtitle track of the current stream. */
    private static final class TrackEntry {
        int type;
        Tracks.Group group;
        int index;
        String label;
    }

    // gesture state
    private int gMode = M_NONE;
    private int gSeekStart;
    private int gSeekTarget;
    private float gBrightStart;
    private int gVolStart;
    private float pinch = 1f;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;

    private AudioFocusRequest focusReq;
    private boolean noisyRegistered;
    private MediaSession session;
    private Runnable sleepTask;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) pausePlayback();
        }
    };

    private final Runnable hideControls = () -> {
        if (isPlaying() && !userSeeking) setControlsVisible(false);
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            if (prepared && mp != null) {
                try {
                    int pos = (int) Math.max(0, mp.getCurrentPosition());
                    if (duration == 0 && !live) refreshDuration();
                    if (duration > 0) seek.setSecondaryProgress((int) Math.min(duration, mp.getBufferedPosition()));
                    if (!userSeeking) {
                        seek.setProgress(pos);
                        curTime.setText(fmt(pos));
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastSave > 5000) {
                        lastSave = now;
                        savePosition(pos);
                    }
                } catch (IllegalStateException ignored) {
                }
            }
            h.postDelayed(this, 500);
        }
    };

    MediaPlayerView(Context c, int accent, boolean audioHint, Listener listener) {
        super(c);
        this.ctx = c;
        this.l = listener;
        this.accent = accent;
        this.audioOnly = audioHint;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        prefs = c.getSharedPreferences("media_pos", Context.MODE_PRIVATE);
        setBackgroundColor(Color.BLACK);
        setClickable(true);

        // ---- video surface
        tv = new TextureView(c);
        tv.setSurfaceTextureListener(this);
        addView(tv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- audio artwork
        artBox = new LinearLayout(c);
        artBox.setOrientation(LinearLayout.VERTICAL);
        artBox.setGravity(Gravity.CENTER);
        TextView note = new TextView(c);
        note.setText("♪");
        note.setTextColor(accent);
        note.setTextSize(96);
        note.setGravity(Gravity.CENTER);
        artBox.addView(note);
        artTitle = new TextView(c);
        artTitle.setTextColor(Color.WHITE);
        artTitle.setTextSize(17);
        artTitle.setGravity(Gravity.CENTER);
        artTitle.setPadding(dp(32), dp(8), dp(32), 0);
        artBox.addView(artTitle);
        addView(artBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // ---- subtitles
        subtitle = new TextView(c);
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        subtitle.setPadding(dp(24), 0, dp(24), dp(96));
        addView(subtitle, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // ---- gesture layer
        touch = new View(c);
        addView(touch, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- controls
        controls = new FrameLayout(c);
        addView(controls, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(gradient(0xCC000000, 0x00000000));
        top.setPadding(dp(8), dp(8), dp(8), dp(18));
        TextView close = chip("✕");
        close.setOnClickListener(v -> l.onClose());
        top.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        titleView = new TextView(c);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleView.setPadding(dp(10), 0, dp(10), 0);
        top.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pipBtn = chip("⧉");
        pipBtn.setOnClickListener(v -> l.onPip());
        top.addView(pipBtn, margin(new LinearLayout.LayoutParams(dp(42), dp(42)), 6));
        TextView more = chip("⋮");
        more.setOnClickListener(v -> showMenu());
        top.addView(more, margin(new LinearLayout.LayoutParams(dp(42), dp(42)), 6));
        controls.addView(top, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout center = new LinearLayout(c);
        center.setOrientation(LinearLayout.HORIZONTAL);
        center.setGravity(Gravity.CENTER);
        prevBtn = bigChip("⏮", 48);
        prevBtn.setOnClickListener(v -> prev());
        center.addView(prevBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView back = bigChip("⏪", 52);
        back.setOnClickListener(v -> seekBy(-10000));
        center.addView(back, margin(new LinearLayout.LayoutParams(dp(52), dp(52)), 14));
        playBtn = bigChip("▶", 68);
        playBtn.setOnClickListener(v -> togglePlay());
        center.addView(playBtn, margin(new LinearLayout.LayoutParams(dp(68), dp(68)), 14));
        TextView fwd = bigChip("⏩", 52);
        fwd.setOnClickListener(v -> seekBy(10000));
        center.addView(fwd, margin(new LinearLayout.LayoutParams(dp(52), dp(52)), 14));
        nextBtn = bigChip("⏭", 48);
        nextBtn.setOnClickListener(v -> next());
        center.addView(nextBtn, margin(new LinearLayout.LayoutParams(dp(48), dp(48)), 14));
        controls.addView(center, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(gradient(0x00000000, 0xCC000000));
        bottom.setPadding(dp(12), dp(24), dp(12), dp(10));
        LinearLayout row1 = new LinearLayout(c);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        curTime = timeText();
        row1.addView(curTime);
        seek = new SeekBar(c);
        seek.setProgressTintList(ColorStateList.valueOf(accent));
        seek.setThumbTintList(ColorStateList.valueOf(accent));
        seek.setSecondaryProgressTintList(ColorStateList.valueOf(0x66FFFFFF));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) curTime.setText(fmt(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                userSeeking = true;
                h.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                seekTo(s.getProgress());
                scheduleHide();
            }
        });
        row1.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        durTime = timeText();
        row1.addView(durTime);
        bottom.addView(row1);

        LinearLayout row2 = new LinearLayout(c);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        lockBtn = pill("🔒");
        lockBtn.setOnClickListener(v -> setLocked(true));
        row2.addView(lockBtn, new LinearLayout.LayoutParams(0, dp(38), 1f));
        speedBtn = pill("1x");
        speedBtn.setOnClickListener(v -> {
            speedIdx = (speedIdx + 1) % SPEEDS.length;
            applySpeed();
            scheduleHide();
        });
        row2.addView(speedBtn, margin(new LinearLayout.LayoutParams(0, dp(38), 1f), 8));
        aspectBtn = pill(ASPECT_LABELS[0]);
        aspectBtn.setOnClickListener(v -> {
            aspectMode = (aspectMode + 1) % ASPECT_LABELS.length;
            applyAspect();
            toastHud("▭ " + ASPECT_LABELS[aspectMode]);
            scheduleHide();
        });
        row2.addView(aspectBtn, margin(new LinearLayout.LayoutParams(0, dp(38), 1f), 8));
        loopBtn = pill("🔁");
        loopBtn.setAlpha(0.45f);
        loopBtn.setOnClickListener(v -> {
            loop = !loop;
            loopBtn.setAlpha(loop ? 1f : 0.45f);
            if (mp != null) mp.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            toastHud(loop ? "🔁 লুপ চালু" : "🔁 লুপ বন্ধ");
            scheduleHide();
        });
        row2.addView(loopBtn, margin(new LinearLayout.LayoutParams(0, dp(38), 1f), 8));
        TextView rot = pill("⟳");
        rot.setOnClickListener(v -> l.onRotate());
        row2.addView(rot, margin(new LinearLayout.LayoutParams(0, dp(38), 1f), 8));
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2.topMargin = dp(6);
        bottom.addView(row2, r2);
        controls.addView(bottom, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // ---- unlock button (screen lock) and HUD
        unlockBtn = bigChip("🔓", 52);
        unlockBtn.setVisibility(GONE);
        unlockBtn.setOnClickListener(v -> setLocked(false));
        LayoutParams up = new LayoutParams(dp(52), dp(52), Gravity.CENTER_VERTICAL | Gravity.START);
        up.leftMargin = dp(18);
        addView(unlockBtn, up);

        hud = new TextView(c);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(20);
        hud.setGravity(Gravity.CENTER);
        hud.setPadding(dp(22), dp(12), dp(22), dp(12));
        hud.setBackground(round(0xAA000000, 14));
        hud.setVisibility(GONE);
        LayoutParams hp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = dp(90);
        addView(hud, hp);

        spinner = new ProgressBar(c);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(accent));
        addView(spinner, new LayoutParams(dp(44), dp(44), Gravity.CENTER));

        // ---- gestures
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                gMode = M_NONE;
                gBrightStart = currentBrightness();
                gVolStart = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (locked) {
                    unlockBtn.setVisibility(VISIBLE);
                    h.removeCallbacks(hideUnlock);
                    h.postDelayed(hideUnlock, 2500);
                } else {
                    setControlsVisible(!controlsOn);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (locked) return true;
                float x = e.getX();
                int w = getWidth();
                if (x < w / 3f) {
                    seekBy(-10000);
                    toastHud("⏪ 10s");
                } else if (x > w * 2f / 3f) {
                    seekBy(10000);
                    toastHud("10s ⏩");
                } else {
                    togglePlay();
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (locked || !isPlaying()) return;
                longPressSpeed = true;
                setRawSpeed(2f);
                toastHud("⏩ 2x");
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (locked || pip || e1 == null) return false;
                float tx = e2.getX() - e1.getX();
                float ty = e2.getY() - e1.getY();
                if (gMode == M_NONE) {
                    if (Math.abs(tx) > Math.abs(ty) * 1.3f && Math.abs(tx) > dp(12) && duration > 0) {
                        gMode = M_SEEK;
                        gSeekStart = mp == null ? 0 : safePos();
                    } else if (Math.abs(ty) > Math.abs(tx) * 1.3f && Math.abs(ty) > dp(12)) {
                        gMode = e1.getX() < getWidth() / 2f ? M_BRIGHT : M_VOL;
                    } else {
                        return true;
                    }
                }
                int vh = Math.max(1, getHeight());
                if (gMode == M_SEEK) {
                    int span = Math.min(duration, 180000);
                    gSeekTarget = clamp(gSeekStart + (int) (tx / Math.max(1, getWidth()) * span), 0, duration);
                    toastHud("⏩ " + fmt(gSeekTarget) + " / " + fmt(duration));
                } else if (gMode == M_BRIGHT) {
                    float b = Math.max(0.02f, Math.min(1f, gBrightStart - ty / vh * 1.4f));
                    setBrightness(b);
                    toastHud("☀ " + Math.round(b * 100) + "%");
                } else if (gMode == M_VOL) {
                    int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                    int v = clamp(Math.round(gVolStart - ty / vh * 1.4f * max), 0, max);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                    toastHud("🔊 " + Math.round(v * 100f / max) + "%");
                }
                return true;
            }
        });
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                pinch = 1f;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                pinch *= d.getScaleFactor();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                if (audioOnly) return;
                if (pinch > 1.12f && aspectMode != 1) {
                    aspectMode = 1;
                } else if (pinch < 0.88f && aspectMode != 0) {
                    aspectMode = 0;
                } else {
                    return;
                }
                applyAspect();
                toastHud("▭ " + ASPECT_LABELS[aspectMode]);
            }
        });
        touch.setOnTouchListener((v, ev) -> {
            if (pip) return false;
            if (!locked) scaler.onTouchEvent(ev);
            if (!scaler.isInProgress()) gestures.onTouchEvent(ev);
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endGesture();
            return true;
        });

        setKeepScreenOn(false);
        updateAudioLayout();
        applySpeedLabel();
    }

    // ------------------------------------------------------------------ small UI helpers

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private LinearLayout.LayoutParams margin(LinearLayout.LayoutParams p, int leftDp) {
        p.leftMargin = dp(leftDp);
        return p;
    }

    private GradientDrawable gradient(int from, int to) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{from, to});
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView chip(String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x44FFFFFF);
        t.setBackground(g);
        return t;
    }

    private TextView bigChip(String label, int sizeDp) {
        TextView t = chip(label);
        t.setTextSize(sizeDp >= 60 ? 28 : 20);
        ((GradientDrawable) t.getBackground()).setColor(0x66000000);
        return t;
    }

    private TextView pill(String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(0x44FFFFFF, 19));
        return t;
    }

    private TextView timeText() {
        TextView t = new TextView(ctx);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setText("0:00");
        t.setPadding(dp(4), 0, dp(4), 0);
        return t;
    }

    private static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        int hh = s / 3600;
        int mm = (s % 3600) / 60;
        int ss = s % 60;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss) : String.format(Locale.US, "%d:%02d", mm, ss);
    }

    // anonymous classes (not lambdas): they read blank final fields that are assigned in the constructor
    private final Runnable hideHud = new Runnable() {
        @Override
        public void run() {
            hud.setVisibility(GONE);
        }
    };
    private final Runnable hideUnlock = new Runnable() {
        @Override
        public void run() {
            unlockBtn.setVisibility(GONE);
        }
    };

    private void toastHud(String text) {
        hud.setText(text);
        hud.setVisibility(VISIBLE);
        h.removeCallbacks(hideHud);
        h.postDelayed(hideHud, 900);
    }

    private Activity activity() {
        Context c = ctx;
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private float currentBrightness() {
        Activity a = activity();
        if (a == null) return 0.5f;
        float b = a.getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            return android.provider.Settings.System.getInt(a.getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void setBrightness(float b) {
        Activity a = activity();
        if (a == null) return;
        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        lp.screenBrightness = b;
        a.getWindow().setAttributes(lp);
    }

    /** Puts the screen brightness back to the system value (called when the player closes). */
    private void restoreBrightness() {
        Activity a = activity();
        if (a == null) return;
        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        a.getWindow().setAttributes(lp);
    }

    // ------------------------------------------------------------------ controls visibility / lock

    private void setControlsVisible(boolean on) {
        controlsOn = on;
        if (pip || locked) {
            controls.setVisibility(GONE);
            return;
        }
        controls.setVisibility(on ? VISIBLE : GONE);
        h.removeCallbacks(hideControls);
        if (on) scheduleHide();
    }

    private void scheduleHide() {
        h.removeCallbacks(hideControls);
        if (controlsOn && isPlaying()) h.postDelayed(hideControls, 3800);
    }

    private void setLocked(boolean on) {
        locked = on;
        if (on) {
            controls.setVisibility(GONE);
            unlockBtn.setVisibility(VISIBLE);
            h.removeCallbacks(hideUnlock);
            h.postDelayed(hideUnlock, 2500);
            toastHud("🔒 লক করা হয়েছে");
        } else {
            unlockBtn.setVisibility(GONE);
            controlsOn = true;
            controls.setVisibility(VISIBLE);
            scheduleHide();
        }
    }

    private void endGesture() {
        if (gMode == M_SEEK && prepared) seekTo(gSeekTarget);
        gMode = M_NONE;
        if (longPressSpeed) {
            longPressSpeed = false;
            applySpeed();
        }
        h.removeCallbacks(hideHud);
        h.postDelayed(hideHud, 600);
    }

    /** Picture-in-Picture: hide every control, the system draws its own buttons. */
    void setPipMode(boolean on) {
        pip = on;
        if (on) {
            controls.setVisibility(GONE);
            unlockBtn.setVisibility(GONE);
            hud.setVisibility(GONE);
        } else if (!locked) {
            controls.setVisibility(controlsOn ? VISIBLE : GONE);
            scheduleHide();
        }
        applyAspect();
    }

    private void updateAudioLayout() {
        tv.setVisibility(audioOnly ? INVISIBLE : VISIBLE);
        artBox.setVisibility(audioOnly ? VISIBLE : GONE);
        pipBtn.setVisibility(audioOnly ? GONE : VISIBLE);
        aspectBtn.setVisibility(audioOnly ? GONE : VISIBLE);
        subtitle.setVisibility(audioOnly ? GONE : VISIBLE);
    }

    private void updateNavButtons() {
        boolean list = items.size() > 1;
        prevBtn.setVisibility(list ? VISIBLE : GONE);
        nextBtn.setVisibility(list ? VISIBLE : GONE);
        prevBtn.setAlpha(index > 0 ? 1f : 0.4f);
        nextBtn.setAlpha(index < items.size() - 1 ? 1f : 0.4f);
    }

    // ------------------------------------------------------------------ playlist

    /** Starts playing the list from {@code start}. */
    void play(List<Item> list, int start, Map<String, String> hdrs) {
        items.clear();
        items.addAll(list);
        headers = hdrs;
        index = clamp(start, 0, Math.max(0, items.size() - 1));
        active = this;
        h.removeCallbacks(tick);
        h.post(tick);
        startItem();
    }

    private void startItem() {
        if (items.isEmpty() || released) return;
        releasePlayer();
        Item it = items.get(index);
        prepared = false;
        duration = 0;
        videoW = 0;
        videoH = 0;
        pausedByFocus = false;
        tracks.clear();
        live = false;
        attempt = 0;
        subtitle.setText("");
        titleView.setText(it.title);
        artTitle.setText(it.title);
        seek.setProgress(0);
        seek.setSecondaryProgress(0);
        curTime.setText("0:00");
        durTime.setText("0:00");
        playBtn.setText("▶");
        spinner.setVisibility(VISIBLE);
        updateNavButtons();

        buildPlayer(it);
        l.onStateChanged();
    }

    /** (Re)creates the ExoPlayer for {@code it}; {@link #attempt} picks a forced stream type (0 = auto, 1 = HLS, 2 = DASH). */
    private void buildPlayer(Item it) {
        releasePlayer();
        prepared = false;
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(20000)
                    .setReadTimeoutMs(30000);
            if (headers != null && !headers.isEmpty()) {
                Map<String, String> hh = new HashMap<>(headers);
                String ua = hh.remove("User-Agent");
                if (ua != null) http.setUserAgent(ua);
                http.setDefaultRequestProperties(hh);
            }
            ExoPlayer.Builder b = new ExoPlayer.Builder(ctx)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(ctx, http)))
                    .setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
                    .setHandleAudioBecomingNoisy(false);
            if (ctx.checkSelfPermission(android.Manifest.permission.WAKE_LOCK) == PackageManager.PERMISSION_GRANTED) {
                b.setWakeMode(C.WAKE_MODE_NETWORK);
            }
            final ExoPlayer p = b.build();
            mp = p;
            p.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (released || p != mp) return;
                    if (state == Player.STATE_BUFFERING) {
                        spinner.setVisibility(VISIBLE);
                    } else if (state == Player.STATE_READY) {
                        spinner.setVisibility(GONE);
                        if (!prepared) onReady();
                    } else if (state == Player.STATE_ENDED) {
                        h.post(() -> {
                            if (!released && p == mp) onCompleted();
                        });
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize vs) {
                    if (released || p != mp) return;
                    videoW = Math.round(vs.width * (vs.pixelWidthHeightRatio > 0 ? vs.pixelWidthHeightRatio : 1f));
                    videoH = vs.height;
                    applyAspect();
                    l.onStateChanged();
                }

                @Override
                public void onTracksChanged(Tracks t) {
                    if (released || p != mp) return;
                    collectTracks(t);
                }

                @Override
                public void onCues(CueGroup cg) {
                    if (released || p != mp) return;
                    StringBuilder sb = new StringBuilder();
                    for (Cue c : cg.cues) {
                        if (c.text == null) continue;
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(c.text);
                    }
                    subtitle.setText(sb.toString());
                }

                @Override
                public void onPlayerError(PlaybackException e) {
                    if (released || p != mp) return;
                    int code = e.errorCode;
                    if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        p.seekToDefaultPosition();
                        p.prepare();
                        return;
                    }
                    // the URL has no recognisable extension: retry as HLS, then as DASH
                    boolean parse = code >= 3000 && code < 4000;
                    if (parse && attempt < 2) {
                        attempt++;
                        h.post(() -> {
                            if (!released && !items.isEmpty()) buildPlayer(items.get(index));
                        });
                        return;
                    }
                    spinner.setVisibility(GONE);
                    l.onError(items.get(index).url, items.get(index).title);
                }
            });
            MediaItem.Builder mi = new MediaItem.Builder().setUri(Uri.parse(it.url));
            if (attempt == 1) mi.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8);
            else if (attempt == 2) mi.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD);
            p.setMediaItem(mi.build());
            p.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            if (surface != null) p.setVideoSurface(surface);
            p.prepare();
        } catch (Exception e) {
            spinner.setVisibility(GONE);
            l.onError(it.url, it.title);
        }
    }

    private void onReady() {
        if (released || mp == null) return;
        prepared = true;
        spinner.setVisibility(GONE);
        try {
            live = mp.isCurrentMediaItemLive();
            long d = mp.getDuration();
            duration = live || d == C.TIME_UNSET || d < 0 ? 0 : (int) Math.min(d, Integer.MAX_VALUE);
            audioOnly = !mp.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO);
            collectTracks(mp.getCurrentTracks());
        } catch (Exception ignored) {
        }
        updateAudioLayout();
        seek.setMax(duration);
        durTime.setText(live ? "LIVE" : duration > 0 ? fmt(duration) : "0:00");
        seek.setEnabled(duration > 0);
        applyAspect();

        int saved = (int) prefs.getLong(posKey(), 0);
        if (saved > 5000 && duration > 0 && saved < duration - 8000) {
            try {
                mp.seekTo(saved);
                toastHud("▶ " + fmt(saved) + " থেকে চলছে");
            } catch (Exception ignored) {
            }
        }
        ensureSession();
        startPlayback();
        updateNavButtons();
    }

    /** Duration of a stream that only became known after the first frame (some DASH / HLS VOD streams). */
    private void refreshDuration() {
        try {
            long d = mp.getDuration();
            if (d > 0 && d != C.TIME_UNSET && !mp.isCurrentMediaItemLive()) {
                duration = (int) Math.min(d, Integer.MAX_VALUE);
                seek.setMax(duration);
                seek.setEnabled(true);
                durTime.setText(fmt(duration));
            }
        } catch (Exception ignored) {
        }
    }

    private void collectTracks(Tracks t) {
        tracks.clear();
        for (Tracks.Group g : t.getGroups()) {
            int type = g.getType();
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT && type != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                TrackEntry e = new TrackEntry();
                e.type = type;
                e.group = g;
                e.index = i;
                StringBuilder sb = new StringBuilder();
                if (type == C.TRACK_TYPE_VIDEO) {
                    sb.append(f.height > 0 ? f.height + "p" : "ভিডিও");
                    if (f.frameRate > 0 && f.frameRate != Format.NO_VALUE) sb.append(' ').append(Math.round(f.frameRate)).append("fps");
                    if (f.bitrate > 0) sb.append("  ").append(f.bitrate / 1000).append(" kbps");
                } else {
                    sb.append(f.label != null ? f.label : type == C.TRACK_TYPE_AUDIO ? "অডিও" : "সাবটাইটেল");
                    if (f.language != null && !"und".equals(f.language)) sb.append(" (").append(f.language).append(')');
                    if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 2) sb.append(' ').append(f.channelCount).append("ch");
                }
                if (g.isTrackSelected(i)) sb.append("  ✓");
                e.label = sb.toString();
                tracks.add(e);
            }
        }
    }

    private void onCompleted() {
        if (released) return;
        savePosition(0);
        if (sleepAtEnd) {
            sleepAtEnd = false;
            playBtn.setText("▶");
            toastHud("😴 স্লিপ টাইমারে বন্ধ");
            updateSession();
            l.onStateChanged();
            return;
        }
        if (index < items.size() - 1) {
            index++;
            startItem();
        } else {
            playBtn.setText("▶");
            setControlsVisible(true);
            setKeepScreenOn(false);
            updateSession();
            l.onStateChanged();
        }
    }

    void next() {
        if (index < items.size() - 1) {
            savePosition(safePos());
            index++;
            startItem();
        }
    }

    void prev() {
        if (prepared && safePos() > 4000) {
            seekTo(0);
        } else if (index > 0) {
            savePosition(safePos());
            index--;
            startItem();
        }
    }

    boolean hasNext() {
        return index < items.size() - 1;
    }

    boolean hasPrev() {
        return index > 0;
    }

    // ------------------------------------------------------------------ transport

    private int safePos() {
        try {
            return mp != null && prepared ? (int) Math.max(0, mp.getCurrentPosition()) : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    boolean isPlaying() {
        try {
            return mp != null && prepared && mp.getPlayWhenReady() && mp.getPlaybackState() != Player.STATE_ENDED;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    boolean isVideo() {
        return prepared && !audioOnly;
    }

    int videoWidth() {
        return videoW;
    }

    int videoHeight() {
        return videoH;
    }

    String title() {
        return items.isEmpty() ? "" : items.get(index).title;
    }

    int position() {
        return safePos();
    }

    int durationMs() {
        return duration;
    }

    void togglePlay() {
        if (!prepared) return;
        if (isPlaying()) {
            pausePlayback();
        } else {
            if (duration > 0 && safePos() >= duration - 300) seekTo(0);
            startPlayback();
        }
    }

    private void startPlayback() {
        if (mp == null || !prepared || released) return;
        if (!requestFocus()) {
            toastHud("অডিও ফোকাস পাওয়া যায়নি");
        }
        try {
            mp.play();
            applySpeed();
        } catch (Exception e) {
            return;
        }
        playBtn.setText("⏸");
        setKeepScreenOn(!audioOnly);
        registerNoisy();
        scheduleHide();
        if (audioOnly) MediaService.start(ctx);
        updateSession();
        MediaService.refresh();
        l.onStateChanged();
    }

    void pausePlayback() {
        if (mp == null || !prepared) return;
        try {
            if (mp.getPlayWhenReady()) mp.pause();
        } catch (Exception ignored) {
        }
        savePosition(safePos());
        playBtn.setText("▶");
        setKeepScreenOn(false);
        unregisterNoisy();
        h.removeCallbacks(hideControls);
        if (!pip && !locked) setControlsVisible(true);
        updateSession();
        MediaService.refresh();
        l.onStateChanged();
    }

    /** Called by the activity when it is no longer visible (and not in PiP): video pauses, audio keeps going. */
    void onHostStopped() {
        if (!audioOnly) pausePlayback();
    }

    void seekBy(int deltaMs) {
        if (!prepared) return;
        seekTo(clamp(safePos() + deltaMs, 0, duration > 0 ? duration : Integer.MAX_VALUE));
    }

    private void seekTo(int ms) {
        if (mp == null || !prepared) return;
        try {
            mp.seekTo(ms);
            seek.setProgress(ms);
            curTime.setText(fmt(ms));
        } catch (Exception ignored) {
        }
        updateSession();
    }

    // ------------------------------------------------------------------ speed / aspect

    private void applySpeedLabel() {
        float sp = SPEEDS[speedIdx];
        speedBtn.setText((sp == (int) sp ? String.valueOf((int) sp) : String.valueOf(sp)) + "x");
    }

    private void applySpeed() {
        applySpeedLabel();
        setRawSpeed(SPEEDS[speedIdx]);
    }

    private void setRawSpeed(float sp) {
        if (mp == null || !prepared) return;
        try {
            mp.setPlaybackParameters(new PlaybackParameters(sp));
        } catch (Exception ignored) {
        }
    }

    private void applyAspect() {
        aspectBtn.setText(ASPECT_LABELS[aspectMode]);
        int vw = getWidth();
        int vh = getHeight();
        if (vw == 0 || vh == 0 || videoW == 0 || videoH == 0) return;
        float viewAr = vw / (float) vh;
        float ar = videoW / (float) videoH;
        float sx = 1f;
        float sy = 1f;
        if (pip || aspectMode == 0 || aspectMode == 3) {
            if (!pip && aspectMode == 3) ar = 16f / 9f;
            if (ar > viewAr) sy = viewAr / ar;
            else sx = ar / viewAr;
        } else if (aspectMode == 1) {
            if (ar > viewAr) sx = ar / viewAr;
            else sy = viewAr / ar;
        }
        Matrix m = new Matrix();
        m.setScale(sx, sy, vw / 2f, vh / 2f);
        tv.setTransform(m);
    }

    @Override
    protected void onSizeChanged(int w, int hh, int ow, int oh) {
        super.onSizeChanged(w, hh, ow, oh);
        applyAspect();
    }

    // ------------------------------------------------------------------ menu (speed, tracks, sleep ...)

    private void showMenu() {
        h.removeCallbacks(hideControls);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("প্লেব্যাক স্পিড");
        acts.add(this::chooseSpeed);
        if (countTracks(C.TRACK_TYPE_VIDEO) > 1) {
            labels.add("ভিডিও কোয়ালিটি");
            acts.add(() -> chooseTrack(C.TRACK_TYPE_VIDEO));
        }
        if (countTracks(C.TRACK_TYPE_AUDIO) > 1) {
            labels.add("অডিও ট্র্যাক");
            acts.add(() -> chooseTrack(C.TRACK_TYPE_AUDIO));
        }
        if (countTracks(C.TRACK_TYPE_TEXT) > 0) {
            labels.add("সাবটাইটেল");
            acts.add(() -> chooseTrack(C.TRACK_TYPE_TEXT));
        }
        labels.add("স্লিপ টাইমার");
        acts.add(this::chooseSleep);
        labels.add("অন্য অ্যাপে খুলুন");
        acts.add(() -> l.onExternal(items.get(index).url));
        labels.add("ডাউনলোড");
        acts.add(() -> l.onDownload(items.get(index).url, items.get(index).title));
        new AlertDialog.Builder(ctx)
                .setItems(labels.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .setOnDismissListener(d -> scheduleHide())
                .show();
    }

    private void chooseSpeed() {
        String[] names = new String[SPEEDS.length];
        for (int i = 0; i < SPEEDS.length; i++) names[i] = SPEEDS[i] + "x" + (i == speedIdx ? "  ✓" : "");
        new AlertDialog.Builder(ctx).setTitle("স্পিড").setItems(names, (d, which) -> {
            speedIdx = which;
            applySpeed();
        }).show();
    }

    private int countTracks(int type) {
        int n = 0;
        for (TrackEntry t : tracks) if (t.type == type) n++;
        return n;
    }

    private void chooseTrack(final int type) {
        if (mp == null) return;
        final List<TrackEntry> list = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        if (type == C.TRACK_TYPE_TEXT) names.add("বন্ধ");
        if (type == C.TRACK_TYPE_VIDEO) names.add("অটো");
        final int offset = names.size();
        for (TrackEntry t : tracks) {
            if (t.type != type) continue;
            list.add(t);
            names.add(t.label);
        }
        String title = type == C.TRACK_TYPE_VIDEO ? "ভিডিও কোয়ালিটি" : type == C.TRACK_TYPE_AUDIO ? "অডিও ট্র্যাক" : "সাবটাইটেল";
        new AlertDialog.Builder(ctx).setTitle(title)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    try {
                        TrackSelectionParameters.Builder b = mp.getTrackSelectionParameters().buildUpon();
                        if (which < offset) {
                            if (type == C.TRACK_TYPE_TEXT) {
                                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                                subtitle.setText("");
                            } else {
                                b.clearOverridesOfType(type);
                            }
                        } else {
                            TrackEntry e = list.get(which - offset);
                            b.setTrackTypeDisabled(type, false);
                            b.setOverrideForType(new TrackSelectionOverride(e.group.getMediaTrackGroup(), e.index));
                        }
                        mp.setTrackSelectionParameters(b.build());
                    } catch (Exception e) {
                        Toast.makeText(ctx, "ট্র্যাক বদলানো যায়নি", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void chooseSleep() {
        final String[] names = {"বন্ধ", "১৫ মিনিট", "৩০ মিনিট", "১ ঘণ্টা", "এই ফাইল শেষ হলে"};
        final int[] mins = {0, 15, 30, 60, -1};
        new AlertDialog.Builder(ctx).setTitle("স্লিপ টাইমার").setItems(names, (d, which) -> {
            if (sleepTask != null) h.removeCallbacks(sleepTask);
            sleepTask = null;
            sleepAtEnd = false;
            if (mins[which] > 0) {
                sleepTask = () -> {
                    pausePlayback();
                    toastHud("😴 স্লিপ টাইমারে বন্ধ");
                };
                h.postDelayed(sleepTask, mins[which] * 60_000L);
                toastHud("😴 " + names[which] + " পরে বন্ধ হবে");
            } else if (mins[which] < 0) {
                sleepAtEnd = true;
                toastHud("😴 ফাইল শেষে বন্ধ হবে");
            }
        }).show();
    }

    // ------------------------------------------------------------------ resume position

    private String posKey() {
        return "p" + (items.isEmpty() ? 0 : items.get(index).url.hashCode());
    }

    private void savePosition(int pos) {
        if (items.isEmpty()) return;
        try {
            prefs.edit().putLong(posKey(), pos).apply();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ audio focus / noisy / session

    private boolean requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusReq == null) {
                    focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                            .setOnAudioFocusChangeListener(focusListener)
                            .build();
                }
                return am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
            //noinspection deprecation
            return am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
            return true;
        }
    }

    private void abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusReq != null) am.abandonAudioFocusRequest(focusReq);
            } else {
                //noinspection deprecation
                am.abandonAudioFocus(focusListener);
            }
        } catch (Exception ignored) {
        }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (released) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pausedByFocus = false;
                pausePlayback();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying()) {
                    pausedByFocus = true;
                    pausePlayback();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mp != null) mp.setVolume(0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mp != null) mp.setVolume(1f);
                if (pausedByFocus) {
                    pausedByFocus = false;
                    startPlayback();
                }
                break;
            default:
                break;
        }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        try {
            ctx.registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try {
            ctx.unregisterReceiver(noisy);
        } catch (Exception ignored) {
        }
        noisyRegistered = false;
    }

    @SuppressWarnings("deprecation")
    private void ensureSession() {
        if (session != null) return;
        try {
            session = new MediaSession(ctx, "WebShellPlayer");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    if (!isPlaying()) startPlayback();
                }

                @Override
                public void onPause() {
                    pausePlayback();
                }

                @Override
                public void onSkipToNext() {
                    next();
                }

                @Override
                public void onSkipToPrevious() {
                    prev();
                }

                @Override
                public void onSeekTo(long pos) {
                    seekTo((int) pos);
                }

                @Override
                public void onFastForward() {
                    seekBy(10000);
                }

                @Override
                public void onRewind() {
                    seekBy(-10000);
                }

                @Override
                public void onStop() {
                    l.onClose();
                }
            });
            session.setActive(true);
        } catch (Exception e) {
            session = null;
        }
    }

    private void updateSession() {
        if (session == null) return;
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND
                    | PlaybackState.ACTION_STOP;
            if (hasNext()) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
            if (hasPrev()) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            boolean playing = isPlaying();
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            safePos(), playing ? SPEEDS[speedIdx] : 0f)
                    .build());
            session.setMetadata(new android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title())
                    .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, duration)
                    .build());
        } catch (Exception ignored) {
        }
    }

    MediaSession.Token sessionToken() {
        return session == null ? null : session.getSessionToken();
    }

    /** Asks the host to close the player (used by the notification's close button). */
    void requestClose() {
        h.post(() -> l.onClose());
    }

    // ------------------------------------------------------------------ surface

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
        surface = new Surface(st);
        if (mp != null) {
            try {
                mp.setVideoSurface(surface);
            } catch (Exception ignored) {
            }
        }
        applyAspect();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) {
        applyAspect();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        try {
            if (mp != null) mp.clearVideoSurface();
        } catch (Exception ignored) {
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    // ------------------------------------------------------------------ teardown

    private void releasePlayer() {
        unregisterNoisy();
        if (mp != null) {
            ExoPlayer old = mp;
            mp = null;
            prepared = false;
            try {
                old.release();
            } catch (Exception ignored) {
            }
        }
    }

    void release() {
        if (released) return;
        savePosition(safePos());
        released = true;
        h.removeCallbacksAndMessages(null);
        if (sleepTask != null) sleepTask = null;
        releasePlayer();
        abandonFocus();
        restoreBrightness();
        setKeepScreenOn(false);
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (active == this) active = null;
        MediaService.stop(ctx);
    }
}
