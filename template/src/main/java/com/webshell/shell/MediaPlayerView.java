package com.webshell.shell;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Map;

/**
 * Built-in audio/video player (framework VideoView + MediaController: play/pause, seek bar,
 * rewind/forward) with speed, loop and "open in another app" buttons.
 */
final class MediaPlayerView extends FrameLayout {
    interface Listener {
        void onClose();

        void onError();

        void onExternal();
    }

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private final VideoView vv;
    private final MediaController mc;
    private final ProgressBar spinner;
    private final TextView speedBtn;
    private final TextView loopBtn;
    private final boolean audio;
    private MediaPlayer mp;
    private int speedIdx = 2;
    private boolean loop;
    private boolean released;

    MediaPlayerView(Context c, int accent, String title, boolean audio, Listener l) {
        super(c);
        this.audio = audio;
        setBackgroundColor(Color.BLACK);
        setClickable(true);

        vv = new VideoView(c);
        addView(vv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        mc = new MediaController(c);
        vv.setMediaController(mc);

        if (audio) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            TextView note = new TextView(c);
            note.setText("♪");
            note.setTextColor(accent);
            note.setTextSize(96);
            note.setGravity(Gravity.CENTER);
            col.addView(note);
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextColor(Color.WHITE);
            t.setTextSize(16);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(32), dp(8), dp(32), 0);
            col.addView(t);
            addView(col, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }

        spinner = new ProgressBar(c);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
        addView(spinner, new LayoutParams(dp(44), dp(44), Gravity.CENTER));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x88000000);
        top.setPadding(dp(8), dp(6), dp(8), dp(6));

        TextView close = chip(c, "✕");
        close.setOnClickListener(v -> l.onClose());
        top.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView name = new TextView(c);
        name.setText(title);
        name.setTextColor(Color.WHITE);
        name.setTextSize(14);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setPadding(dp(10), 0, dp(10), 0);
        top.addView(name, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        speedBtn = chip(c, "1x");
        speedBtn.setOnClickListener(v -> {
            speedIdx = (speedIdx + 1) % SPEEDS.length;
            applySpeed();
        });
        top.addView(speedBtn, new LinearLayout.LayoutParams(dp(52), dp(42)));

        loopBtn = chip(c, "🔁");
        loopBtn.setAlpha(0.45f);
        loopBtn.setOnClickListener(v -> {
            loop = !loop;
            loopBtn.setAlpha(loop ? 1f : 0.45f);
            if (mp != null) mp.setLooping(loop);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.leftMargin = dp(6);
        top.addView(loopBtn, lp);

        TextView ext = chip(c, "↗");
        ext.setOnClickListener(v -> l.onExternal());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(42), dp(42));
        ep.leftMargin = dp(6);
        top.addView(ext, ep);

        addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP));

        vv.setOnPreparedListener(m -> {
            mp = m;
            spinner.setVisibility(View.GONE);
            m.setLooping(loop);
            applySpeed();
            m.setOnInfoListener((x, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) spinner.setVisibility(View.VISIBLE);
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                        || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) spinner.setVisibility(View.GONE);
                return false;
            });
            vv.start();
            if (audio) mc.show(0);
        });
        vv.setOnErrorListener((m, what, extra) -> {
            spinner.setVisibility(View.GONE);
            if (!released) l.onError();
            return true;
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView chip(Context c, String label) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x55FFFFFF);
        if (label.endsWith("x")) {
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(dp(21));
        }
        t.setBackground(g);
        return t;
    }

    private void applySpeed() {
        float sp = SPEEDS[speedIdx];
        speedBtn.setText((sp == (int) sp ? String.valueOf((int) sp) : String.valueOf(sp)) + "x");
        if (mp == null) return;
        try {
            boolean was = vv.isPlaying();
            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(sp));
            if (!was && mp.isPlaying()) mp.pause(); // some devices start playing when params change
        } catch (Exception ignored) {
        }
    }

    void play(Uri uri, Map<String, String> headers) {
        spinner.setVisibility(View.VISIBLE);
        if (headers != null && !headers.isEmpty()) vv.setVideoURI(uri, headers);
        else vv.setVideoURI(uri);
    }

    void pause() {
        try {
            if (vv.isPlaying()) vv.pause();
        } catch (Exception ignored) {
        }
    }

    void release() {
        released = true;
        try {
            mc.hide();
        } catch (Exception ignored) {
        }
        try {
            vv.stopPlayback();
        } catch (Exception ignored) {
        }
    }
}
