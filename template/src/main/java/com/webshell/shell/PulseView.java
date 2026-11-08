package com.webshell.shell;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Animated expanding rings: used as the loading / waiting-for-network effect. */
final class PulseView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private ValueAnimator anim;
    private float t;

    PulseView(Context c, int color) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3f * density);
        ring.setColor(color);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(color);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1800);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float max = Math.min(getWidth(), getHeight()) / 2f;
        for (int i = 0; i < 3; i++) {
            float p = (t + i / 3f) % 1f;
            ring.setAlpha((int) (255 * (1f - p) * 0.8f));
            canvas.drawCircle(cx, cy, Math.max(1f, max * p), ring);
        }
        float pulse = 1f + 0.18f * (float) Math.sin(t * 2 * Math.PI);
        canvas.drawCircle(cx, cy, max * 0.13f * pulse, dot);
    }
}
