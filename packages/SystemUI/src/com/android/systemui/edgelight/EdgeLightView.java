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

package com.android.systemui.edgelight;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws the crDroid edge light: a stroked border around the screen, shaded by
 * {@link EdgeLightEffect} and coloured by {@link EdgeLightColorMode}.
 *
 * <p>Effects whose opacity is the same all the way round ({@code NONE}, {@code BREATHING}) and
 * whose colour is uniform are drawn as a single stroked path. Anything that varies along the
 * border is cut into {@link #SEGMENTS} pieces with a {@link PathMeasure}; the pieces are only
 * recomputed when the geometry changes, not every frame.
 */
public class EdgeLightView extends View {

    /** How finely the border is cut for effects that vary along it. */
    static final int SEGMENTS = 96;

    /** Extra length added to each segment so neighbours overlap and leave no hairline gaps. */
    private static final float SEGMENT_OVERLAP_PX = 1.5f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mBorder = new Path();
    private final RectF mBounds = new RectF();
    private final PathMeasure mMeasure = new PathMeasure();

    @Nullable private Path[] mSegments;

    private float mStrokeWidthPx = 8f;
    private float mCornerRadiusPx;
    private int mColor = EdgeLightColorMode.FALLBACK_COLOR;
    private EdgeLightEffect mEffect = EdgeLightEffect.NONE;
    private EdgeLightColorMode mColorMode = EdgeLightColorMode.ACCENT;
    private float mProgress;

    public EdgeLightView(Context context) {
        this(context, null);
    }

    public EdgeLightView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    /** Border thickness in pixels ({@code edge_light_stroke_width}, converted from dp). */
    public void setStrokeWidthPx(float px) {
        if (mStrokeWidthPx != px) {
            mStrokeWidthPx = px;
            rebuildGeometry();
            invalidate();
        }
    }

    /** Corner radius in pixels; 0 for the square {@code default} style. */
    public void setCornerRadiusPx(float px) {
        if (mCornerRadiusPx != px) {
            mCornerRadiusPx = px;
            rebuildGeometry();
            invalidate();
        }
    }

    /** The resolved base colour. Ignored while the colour mode is per-segment. */
    public void setColor(int color) {
        if (mColor != color) {
            mColor = color;
            invalidate();
        }
    }

    /** Sets the animation effect and the colour mode together. */
    public void setStyle(EdgeLightEffect effect, EdgeLightColorMode colorMode) {
        if (mEffect != effect || mColorMode != colorMode) {
            mEffect = effect;
            mColorMode = colorMode;
            invalidate();
        }
    }

    /** How far through the current pulse, in {@code [0, 1)}. */
    public void setProgress(float progress) {
        mProgress = progress;
        invalidate();
    }

    /** Whether the border has to be drawn piece by piece. Visible for tests. */
    boolean isSegmented() {
        return mColorMode.isPerSegment() || varies(mEffect);
    }

    private static boolean varies(EdgeLightEffect effect) {
        // BREATHING fades the whole border together, so it needs no segmentation.
        return effect != EdgeLightEffect.NONE && effect != EdgeLightEffect.BREATHING;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildGeometry();
    }

    private void rebuildGeometry() {
        final int w = getWidth();
        final int h = getHeight();
        mSegments = null;
        mBorder.reset();
        if (w <= 0 || h <= 0 || mStrokeWidthPx <= 0f) {
            return;
        }
        // Inset by half the stroke so the border sits fully on screen instead of being clipped.
        final float inset = mStrokeWidthPx / 2f;
        mBounds.set(inset, inset, w - inset, h - inset);
        if (mBounds.width() <= 0f || mBounds.height() <= 0f) {
            mBorder.reset();
            return;
        }
        final float maxRadius = Math.min(mBounds.width(), mBounds.height()) / 2f;
        final float radius = Math.max(0f, Math.min(mCornerRadiusPx, maxRadius));
        if (radius > 0f) {
            mBorder.addRoundRect(mBounds, radius, radius, Path.Direction.CW);
        } else {
            mBorder.addRect(mBounds, Path.Direction.CW);
        }
        mPaint.setStrokeWidth(mStrokeWidthPx);
        buildSegments();
    }

    private void buildSegments() {
        mMeasure.setPath(mBorder, false);
        final float length = mMeasure.getLength();
        if (length <= 0f) {
            return;
        }
        final Path[] segments = new Path[SEGMENTS];
        final float step = length / SEGMENTS;
        for (int i = 0; i < SEGMENTS; i++) {
            final Path piece = new Path();
            final float start = i * step;
            final float end = Math.min(length, start + step + SEGMENT_OVERLAP_PX);
            mMeasure.getSegment(start, end, piece, true);
            segments[i] = piece;
        }
        mSegments = segments;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mBorder.isEmpty() || mStrokeWidthPx <= 0f) {
            return;
        }
        if (!isSegmented()) {
            mPaint.setColor(mColor);
            mPaint.setAlpha(toAlpha(mEffect.alphaAt(0f, mProgress)));
            canvas.drawPath(mBorder, mPaint);
            return;
        }
        final Path[] segments = mSegments;
        if (segments == null) {
            return;
        }
        for (int i = 0; i < segments.length; i++) {
            final float position = i / (float) segments.length;
            final int alpha = toAlpha(mEffect.alphaAt(position, mProgress));
            if (alpha == 0) {
                continue;
            }
            mPaint.setColor(mColorMode.isPerSegment()
                    ? EdgeLightColorMode.rainbowAt(position, mProgress)
                    : mColor);
            mPaint.setAlpha(alpha);
            canvas.drawPath(segments[i], mPaint);
        }
    }

    private static int toAlpha(float fraction) {
        final int alpha = Math.round(fraction * 255f);
        return alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
    }
}
