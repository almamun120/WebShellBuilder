package com.webshell.shell;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/**
 * Minimal pull-to-refresh wrapper (no androidx). Wraps one scrollable child (the WebView) and
 * starts a refresh when the child is at the top and the user drags down far enough.
 */
final class PullRefreshLayout extends FrameLayout {
    interface Listener {
        void onRefresh();
    }

    private final View target;
    private final FrameLayout indicator;
    private final int size;
    private final int trigger;
    private final int maxPull;
    private final int slop;
    private final Runnable timeout = () -> setRefreshing(false);

    private Listener listener;
    private boolean pullEnabled = true;
    private boolean refreshing;
    private boolean pulling;
    private float downX;
    private float downY;

    PullRefreshLayout(Context c, View target, int accent) {
        super(c);
        this.target = target;
        float d = c.getResources().getDisplayMetrics().density;
        size = (int) (40 * d);
        trigger = (int) (72 * d);
        maxPull = (int) (130 * d);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();

        addView(target, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        indicator = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.WHITE);
        indicator.setBackground(bg);
        indicator.setElevation(6 * d);
        ProgressBar pb = new ProgressBar(c);
        pb.setIndeterminateTintList(ColorStateList.valueOf(accent));
        int pad = (int) (8 * d);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(size - 2 * pad, size - 2 * pad, Gravity.CENTER);
        indicator.addView(pb, pp);
        indicator.setVisibility(INVISIBLE);
        addView(indicator, new LayoutParams(size, size, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        indicator.setTranslationY(-size);
    }

    void setOnRefreshListener(Listener l) {
        listener = l;
    }

    void setPullEnabled(boolean on) {
        pullEnabled = on;
    }

    boolean isRefreshing() {
        return refreshing;
    }

    void setRefreshing(boolean on) {
        removeCallbacks(timeout);
        if (on) {
            refreshing = true;
            indicator.setVisibility(VISIBLE);
            indicator.animate().translationY(trigger - size).setDuration(150).start();
            postDelayed(timeout, 12000);
        } else {
            if (!refreshing && !pulling) return;
            refreshing = false;
            indicator.animate().translationY(-size).setDuration(200)
                    .withEndAction(() -> {
                        if (!refreshing && !pulling) indicator.setVisibility(INVISIBLE);
                    }).start();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!pullEnabled || refreshing) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                pulling = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getY() - downY;
                float dx = e.getX() - downX;
                if (dy > slop && dy > Math.abs(dx) * 1.5f && !target.canScrollVertically(-1)) {
                    pulling = true;
                    downY = e.getY() - slop; // start counting from the slop boundary
                    indicator.animate().cancel();
                    indicator.setVisibility(VISIBLE);
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!pulling) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float pull = Math.max(0, (e.getY() - downY) * 0.5f);
                pull = Math.min(pull, maxPull);
                indicator.setTranslationY(pull - size);
                indicator.setRotation(pull * 3f);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float pull = Math.max(0, (e.getY() - downY) * 0.5f);
                pulling = false;
                if (pull >= trigger) {
                    indicator.setRotation(0);
                    setRefreshing(true);
                    if (listener != null) listener.onRefresh();
                } else {
                    indicator.animate().translationY(-size).setDuration(150)
                            .withEndAction(() -> {
                                if (!refreshing) indicator.setVisibility(INVISIBLE);
                            }).start();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                indicator.animate().translationY(-size).setDuration(150)
                        .withEndAction(() -> {
                            if (!refreshing) indicator.setVisibility(INVISIBLE);
                        }).start();
                return true;
            default:
                return true;
        }
    }
}
