package com.watlis.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.ContentViewCallback;

/** Small transient feedback; durable Undo remains available in History and Statistics. */
final class ProgressUndoBar extends BaseTransientBottomBar<ProgressUndoBar> {
    private final SwipeRow row;
    private final TextView progress, title, undo;

    ProgressUndoBar(ViewGroup parent) {
        this(parent, new SwipeRow(parent.getContext()));
    }

    private ProgressUndoBar(ViewGroup parent, SwipeRow content) {
        super(parent, content, new ContentViewCallback() {
            public void animateContentIn(int delay, int duration) { }
            public void animateContentOut(int delay, int duration) { }
        });
        row = content;
        Context context = parent.getContext();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setContentDescription(context.getString(R.string.history_notification));
        row.dismiss = this::dismiss;
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        progress = label(context, 14, "#F3F6F1");
        progress.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title = label(context, 12, "#A6B0A8");
        labels.addView(progress);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(4);
        labels.addView(title, titleParams);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        undo = label(context, 14, "#C7ED9A");
        undo.setText(R.string.history_undo);
        undo.setGravity(Gravity.CENTER);
        undo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        undo.setMinWidth(dp(64));
        undo.setMinHeight(dp(48));
        undo.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.setMarginStart(dp(12));
        row.addView(undo, actionParams);
        getView().setPadding(0, 0, 0, 0);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.parseColor("#101311"));
        surface.setCornerRadius(dp(16));
        surface.setStroke(dp(1), Color.parseColor("#282D29"));
        getView().setBackgroundTintList(null); // Do not let the theme's inverse snackbar tint override charcoal.
        getView().setBackground(surface);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) getView().getLayoutParams();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.leftMargin = params.rightMargin = dp(16);
        params.bottomMargin = dp(12);
        getView().setLayoutParams(params);
        setDuration(3000);
    }

    void update(String progressText, String mediaTitle, int accent, Runnable action) {
        row.resetDrag();
        progress.setText(progressText);
        title.setText(mediaTitle);
        undo.setTextColor(accent);
        GradientDrawable button = new GradientDrawable();
        button.setColor(Color.parseColor("#1B211D"));
        button.setCornerRadius(dp(10));
        undo.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((accent & 0x00ffffff) | 0x33000000), button, null));
        undo.setOnClickListener(v -> { dismiss(); action.run(); });
        show(); // Resets the three-second timeout and replaces the action on rapid updates.
    }

    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }

    private static TextView label(Context context, int size, String color) {
        TextView label = new TextView(context);
        label.setTextSize(size);
        label.setTextColor(Color.parseColor(color));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        return label;
    }

    private static final class SwipeRow extends LinearLayout {
        private float startX, startY, distance;
        private boolean dragging;
        private final int slop;
        private Runnable dismiss;

        SwipeRow(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClickable(true);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startX = event.getRawX(); startY = event.getRawY(); dragging = false; distance = 0;
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dy = event.getRawY() - startY;
                if (dy > slop && dy > Math.abs(event.getRawX() - startX)) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
            }
            return super.onInterceptTouchEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                distance = Math.max(0, event.getRawY() - startY);
                if (distance > slop && distance > Math.abs(event.getRawX() - startX)) dragging = true;
                if (dragging) {
                    setTranslationY(Math.min(distance, getHeight()));
                    setAlpha(Math.max(.35f, 1 - distance / Math.max(1, getHeight())));
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (dragging && distance >= Math.max(slop * 2, getHeight() * .3f)) dismiss.run();
                else { if (!dragging) performClick(); resetDrag(); }
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) resetDrag();
            return true;
        }

        void resetDrag() {
            dragging = false; distance = 0;
            setTranslationY(0); setAlpha(1);
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
