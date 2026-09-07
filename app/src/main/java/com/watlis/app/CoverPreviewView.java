package com.watlis.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.appcompat.widget.AppCompatImageView;

/** Fits the complete image initially; zoom and pan are matrix operations only. */
public class CoverPreviewView extends AppCompatImageView {
    private final Matrix previewMatrix = new Matrix();
    private float zoom = 1f, panX, panY;
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;

    public CoverPreviewView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        setClickable(true);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoomAt(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { return performClick(); }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (zoom > 1.05f) resetZoom(); else zoomAt(2.5f, event.getX(), event.getY());
                return true;
            }
            @Override public boolean onScroll(MotionEvent first, MotionEvent last, float dx, float dy) {
                if (!pinch.isInProgress() && zoom > 1) {
                    panX -= dx; panY -= dy; updateMatrix();
                }
                return true;
            }
        });
    }

    public void resetZoom() { zoom = 1; panX = 0; panY = 0; updateMatrix(); }
    public void zoomIn() { zoomAt(zoom * 1.5f, getWidth() / 2f, getHeight() / 2f); }
    public float getZoom() { return zoom; }

    private float fitScale() {
        Drawable drawable = getDrawable();
        if (drawable == null || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) return 0;
        return Math.min((float) getWidth() / drawable.getIntrinsicWidth(),
                (float) getHeight() / drawable.getIntrinsicHeight());
    }

    private void zoomAt(float next, float x, float y) {
        float fit = fitScale();
        if (fit <= 0 || !Float.isFinite(next)) return;
        float newZoom = Math.max(1, Math.min(5, next));
        float ratio = newZoom / zoom;
        panX = (x - getWidth() / 2f) * (1 - ratio) + panX * ratio;
        panY = (y - getHeight() / 2f) * (1 - ratio) + panY * ratio;
        zoom = newZoom;
        updateMatrix();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        pinch.onTouchEvent(event);
        gestures.onTouchEvent(event);
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        resetZoom();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetZoom();
    }

    private void updateMatrix() {
        if (previewMatrix == null || getDrawable() == null) return;
        float scale = fitScale() * zoom;
        if (scale <= 0) return;
        float width = getDrawable().getIntrinsicWidth() * scale;
        float height = getDrawable().getIntrinsicHeight() * scale;
        float limitX = Math.max(0, (width - getWidth()) / 2);
        float limitY = Math.max(0, (height - getHeight()) / 2);
        panX = Math.max(-limitX, Math.min(limitX, panX));
        panY = Math.max(-limitY, Math.min(limitY, panY));
        previewMatrix.setScale(scale, scale);
        previewMatrix.postTranslate((getWidth() - width) / 2 + panX, (getHeight() - height) / 2 + panY);
        setImageMatrix(previewMatrix);
    }
}
