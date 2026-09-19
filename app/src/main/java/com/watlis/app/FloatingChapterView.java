package com.watlis.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

/** The overlay contains only minus, chapter/episode, and plus. Dragging never counts as a tap. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic-only view requires its action callbacks.
final class FloatingChapterView extends LinearLayout {
    interface Actions {
        void increment(int delta);
        void open();
        void close();
        void drag(float dx, float dy, boolean finished);
    }
    private final Actions actions;
    private final TextView minus, plus, unit, number;
    private final LinearLayout middle;
    private final int slop;
    private float lastX, lastY, startX, startY;
    private boolean dragging;
    FloatingChapterView(Context context, Actions actions) {
        super(context); this.actions = actions; slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL); setPadding(dp(8), dp(8), dp(8), dp(8));
        setBackground(surface(Color.rgb(16, 20, 18), Color.rgb(57, 66, 60), 22)); setElevation(dp(10));
        setContentDescription(context.getString(R.string.floating_controls));
        minus = button("−", R.string.floating_decrease); plus = button("+", R.string.floating_increase);
        middle = new LinearLayout(context); middle.setOrientation(VERTICAL); middle.setGravity(Gravity.CENTER);
        middle.setPadding(dp(6), dp(2), dp(6), dp(2)); middle.setMinimumHeight(dp(48)); middle.setFocusable(true);
        middle.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33C7ED9A), surface(Color.TRANSPARENT, Color.TRANSPARENT, 12), null));
        unit = label(11, Color.rgb(166, 176, 168)); unit.setText(R.string.floating_loading);
        number = label(20, Color.rgb(243, 246, 241)); number.setTypeface(Typeface.DEFAULT, Typeface.BOLD); number.setText("—");
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(number, 10, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        number.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); unit.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        middle.addView(unit, new LayoutParams(-1, -2)); middle.addView(number, new LayoutParams(-1, -2));
        addView(minus, new LayoutParams(dp(48), dp(48)));
        LayoutParams center = new LayoutParams(0, -2, 1); center.setMargins(dp(4), 0, dp(4), 0); addView(middle, center);
        addView(plus, new LayoutParams(dp(48), dp(48)));
        minus.setOnClickListener(v -> actions.increment(-1)); plus.setOnClickListener(v -> actions.increment(1));
        middle.setOnClickListener(v -> actions.open());
        middle.setOnLongClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); actions.close(); return true; });
        ViewCompat.replaceAccessibilityAction(middle, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                context.getString(R.string.floating_close), (v, args) -> { actions.close(); return true; });
        ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_DISMISS,
                context.getString(R.string.floating_close), (v, args) -> { actions.close(); return true; });
        loading();
    }
    void loading() { minus.setEnabled(false); plus.setEnabled(false); middle.setEnabled(false); number.setText("—"); }
    void render(FloatingChapterStore.State state, double shown, boolean enabled) {
        unit.setText(state.unit); number.setText(format(shown));
        middle.setContentDescription(getContext().getString(R.string.floating_open_description, state.title, state.unit, format(shown)));
        middle.setEnabled(enabled); minus.setEnabled(enabled && shown > 0); plus.setEnabled(enabled);
        minus.setAlpha(enabled && shown > 0 ? 1 : .35f); plus.setAlpha(enabled ? 1 : .35f);
        minus.setTextColor(state.accent);
        plus.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), surface(state.accent, Color.TRANSPARENT, 14), null));
        plus.setTextColor(Color.rgb(16, 20, 18));
    }
    static String format(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            startX = lastX = event.getRawX(); startY = lastY = event.getRawY(); dragging = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                && Math.hypot(event.getRawX() - startX, event.getRawY() - startY) > slop) {
            dragging = true; return true;
        }
        return false;
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Container handles only drags; all tap/hold actions belong to accessible children.
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (dragging) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                actions.drag(event.getRawX() - lastX, event.getRawY() - lastY, false);
                lastX = event.getRawX(); lastY = event.getRawY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                dragging = false; actions.drag(0, 0, true);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }
    private TextView button(String value, int description) {
        TextView view = label(25, Color.rgb(199, 237, 154)); view.setText(value); view.setGravity(Gravity.CENTER); view.setFocusable(true);
        view.setContentDescription(getContext().getString(description));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33C7ED9A), surface(Color.rgb(27, 33, 29), Color.TRANSPARENT, 14), null));
        return view;
    }
    private TextView label(int size, int color) {
        TextView view = new TextView(getContext()); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER);
        view.setSingleLine(true); view.setIncludeFontPadding(false); return view;
    }
    private GradientDrawable surface(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke); return drawable;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
