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
import android.graphics.Shader;
import android.util.AttributeSet;
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
        canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
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
