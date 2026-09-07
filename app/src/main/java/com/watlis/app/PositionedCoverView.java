package com.watlis.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import androidx.appcompat.widget.AppCompatImageView;

/** Moves the visible crop without creating a second bitmap or modifying the source. */
public class PositionedCoverView extends AppCompatImageView {
    private final Matrix cropMatrix = new Matrix();
    private float positionX = 0.5f, positionY = 0.5f;

    public PositionedCoverView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
    }

    public void setCoverPosition(float x, float y) {
        positionX = Float.isFinite(x) ? Math.max(0, Math.min(1, x)) : 0.5f;
        positionY = Float.isFinite(y) ? Math.max(0, Math.min(1, y)) : 0.5f;
        updateCrop();
    }

    public float getCoverPositionX() { return positionX; }
    public float getCoverPositionY() { return positionY; }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        updateCrop();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCrop();
    }

    private void updateCrop() {
        Drawable drawable = getDrawable();
        if (cropMatrix == null || drawable == null) return;
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0 || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) return;
        float scale = Math.max(width / drawable.getIntrinsicWidth(), height / drawable.getIntrinsicHeight());
        cropMatrix.setScale(scale, scale);
        cropMatrix.postTranslate((width - drawable.getIntrinsicWidth() * scale) * positionX,
                (height - drawable.getIntrinsicHeight() * scale) * positionY);
        setImageMatrix(cropMatrix);
    }
}
