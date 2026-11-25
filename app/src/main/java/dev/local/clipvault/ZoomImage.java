package dev.local.clipvault;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

final class ZoomImage extends ImageView {
    private static final float MAX_ZOOM = 6;
    private final Bitmap bitmap;
    private final Matrix transform = new Matrix();
    private final ScaleGestureDetector scaleGesture;
    private final GestureDetector panGesture;
    private float fit = 1;
    private float zoom = 1;
    private float left;
    private float top;
    private boolean scaledInGesture;

    ZoomImage(Context context, Bitmap bitmap) {
        super(context);
        this.bitmap = bitmap;
        setScaleType(ScaleType.MATRIX);
        setImageBitmap(bitmap);
        setContentDescription("圖片，雙指縮放，拖曳移動");
        scaleGesture = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                scaledInGesture = true;
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoomAt(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        scaleGesture.setQuickScaleEnabled(false);
        panGesture = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (!scaledInGesture && current.getPointerCount() == 1) {
                    left -= dx;
                    top -= dy;
                    applyMatrix();
                }
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                if (zoom > 1) reset();
                else zoomAt(2.5f, event.getX(), event.getY());
                return true;
            }
        });
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width == 0 || height == 0) return;
        fit = Math.min((float) width / bitmap.getWidth(), (float) height / bitmap.getHeight());
        reset();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) scaledInGesture = false;
        scaleGesture.onTouchEvent(event);
        panGesture.onTouchEvent(event);
        return true;
    }

    void reset() {
        zoom = 1;
        applyMatrix();
    }

    private void zoomAt(float target, float x, float y) {
        float next = Math.max(1, Math.min(MAX_ZOOM, target));
        float ratio = next / zoom;
        left = x - (x - left) * ratio;
        top = y - (y - top) * ratio;
        zoom = next;
        applyMatrix();
    }

    private void applyMatrix() {
        float scale = fit * zoom;
        left = clampOffset(left, getWidth(), bitmap.getWidth() * scale);
        top = clampOffset(top, getHeight(), bitmap.getHeight() * scale);
        transform.setScale(scale, scale);
        transform.postTranslate(left, top);
        setImageMatrix(transform);
    }

    private static float clampOffset(float offset, int viewport, float extent) {
        return extent <= viewport ? (viewport - extent) / 2 : Math.max(viewport - extent, Math.min(0, offset));
    }
}
