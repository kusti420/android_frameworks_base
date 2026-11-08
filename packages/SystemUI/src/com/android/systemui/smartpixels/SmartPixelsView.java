/*
 * Copyright (C) 2026 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.smartpixels;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.RotationUtils;
import android.view.Display;
import android.view.Surface;
import android.view.View;

/**
 * Full-screen view that blacks out a fraction of the physical pixels, tiling the 8x8 mask from
 * {@link SmartPixelsPattern}.
 *
 * <p>The tile is uploaded once per percentage; shifting only updates the shader's local matrix, so
 * a shift costs one {@code invalidate()} and no allocation.
 */
public class SmartPixelsView extends View {

    private final Paint mPaint = new Paint();
    private final Matrix mShaderMatrix = new Matrix();

    @Nullable private Bitmap mTile;
    private int mPercent = -1;
    private int mStep;

    /**
     * IchthysOS: how much larger than the sensor the free circle is, in percent of the sensor's
     * size: room for the icon and the illumination disc's edge. The disc itself can be scaled
     * (persist.sys.udfps.circle_scale, UdfpsController), so the hole stays a little larger.
     */
    private static final int HOLE_SCALE_PERCENT = 125;
    private static final int HOLE_MARGIN_PERCENT = 15;

    /** IchthysOS: the sensor bounds in natural orientation (null = no hole). */
    @Nullable private Rect mHoleNatural;
    private int mNaturalWidth;
    private int mNaturalHeight;
    private final Rect mHoleRect = new Rect();
    private final Path mHolePath = new Path();

    public SmartPixelsView(Context context) {
        this(context, null);
    }

    public SmartPixelsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaint.setAntiAlias(false);
        mPaint.setFilterBitmap(false);
        mPaint.setDither(false);
        setWillNotDraw(false);
        // Purely decorative and never interactive.
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    /** Sets the percentage of pixels to black out. No-op if the resulting tile is unchanged. */
    public void setPercent(int percent) {
        final int clamped = SmartPixelsPattern.clampPercent(percent);
        if (mTile != null && SmartPixelsPattern.dimmedCells(clamped)
                == SmartPixelsPattern.dimmedCells(mPercent)) {
            mPercent = clamped;
            return;
        }
        mPercent = clamped;
        rebuildTile();
        invalidate();
    }

    /** Advances the pattern to the given shift step. */
    public void setStep(int step) {
        final int wrapped = Math.floorMod(step, SmartPixelsPattern.shiftCycleLength());
        if (wrapped == mStep) {
            return;
        }
        mStep = wrapped;
        applyStep();
        invalidate();
    }

    /**
     * IchthysOS: leaves a circle around the fingerprint sensor free of the pattern, or none with
     * {@code null}. [sensorBounds] are in natural orientation at the current resolution
     * (UdfpsOverlayParams); the window is full screen, so they are rotated to the display's
     * current rotation when drawn.
     */
    public void setHole(@Nullable Rect sensorBounds, int naturalWidth, int naturalHeight) {
        final Rect hole = sensorBounds == null || sensorBounds.isEmpty()
                ? null : new Rect(sensorBounds);
        if (java.util.Objects.equals(hole, mHoleNatural) && naturalWidth == mNaturalWidth
                && naturalHeight == mNaturalHeight) {
            return;
        }
        mHoleNatural = hole;
        mNaturalWidth = naturalWidth;
        mNaturalHeight = naturalHeight;
        invalidate();
    }

    /** Whether a sensor hole is set. Visible for tests. */
    public boolean hasHole() {
        return mHoleNatural != null;
    }

    /** Current percentage, clamped. Visible for tests. */
    public int getPercent() {
        return mPercent;
    }

    /** Current shift step. Visible for tests. */
    public int getStep() {
        return mStep;
    }

    private void rebuildTile() {
        final int grid = SmartPixelsPattern.GRID;
        final boolean[] mask = SmartPixelsPattern.mask(mPercent);
        final int[] pixels = new int[mask.length];
        for (int i = 0; i < mask.length; i++) {
            pixels[i] = mask[i] ? Color.BLACK : Color.TRANSPARENT;
        }
        final Bitmap old = mTile;
        mTile = Bitmap.createBitmap(pixels, grid, grid, Bitmap.Config.ARGB_8888);
        mPaint.setShader(new BitmapShader(mTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        applyStep();
        if (old != null) {
            old.recycle();
        }
    }

    private void applyStep() {
        final Shader shader = mPaint.getShader();
        if (shader == null) {
            return;
        }
        final int grid = SmartPixelsPattern.GRID;
        mShaderMatrix.setTranslate(mStep % grid, (mStep / grid) % grid);
        shader.setLocalMatrix(mShaderMatrix);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mPaint.getShader() == null) {
            return;
        }
        final boolean hole = buildHolePath();
        if (hole) {
            canvas.save();
            canvas.clipOutPath(mHolePath);
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
        if (hole) {
            canvas.restore();
        }
    }

    /** IchthysOS: mHolePath for the current rotation; false without a hole. */
    private boolean buildHolePath() {
        if (mHoleNatural == null) {
            return false;
        }
        mHoleRect.set(mHoleNatural);
        final Display display = getDisplay();
        final int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        if (rotation != Surface.ROTATION_0 && mNaturalWidth > 0 && mNaturalHeight > 0) {
            RotationUtils.rotateBounds(mHoleRect, mNaturalWidth, mNaturalHeight, rotation);
        }
        final int scale = Math.max(HOLE_SCALE_PERCENT,
                SystemProperties.getInt("persist.sys.udfps.circle_scale", 100)
                        + HOLE_MARGIN_PERCENT);
        final float radius = Math.max(mHoleRect.width(), mHoleRect.height()) * scale / 200f;
        mHolePath.rewind();
        mHolePath.addCircle(mHoleRect.exactCenterX(), mHoleRect.exactCenterY(), radius,
                Path.Direction.CW);
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mPaint.setShader(null);
        if (mTile != null) {
            mTile.recycle();
            mTile = null;
        }
    }
}
