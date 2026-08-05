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

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

import com.android.internal.policy.SystemBarUtils;

import java.util.List;

/**
 * SPIKE / PLACEHOLDER (IchthysOS): a diagnostic view used to answer one question -- can a view
 * living inside the status bar draw a ring around the display cutout (punch-hole), or does it get
 * clipped at the status bar / window boundary?
 *
 * <p>Draws, centred on the LIVE cutout bounding rect (never hardcoded):
 * <ul>
 *   <li>a cyan rectangle outlining the cutout bounding rect itself (sanity check on geometry),</li>
 *   <li>a green ring just outside the hole (radius = holeRadius + 6px),</li>
 *   <li>a red ring far larger than the status bar height (radius = holeRadius + 32px) to make any
 *       clipping boundary explicit.</li>
 * </ul>
 *
 * <p>Guarded by {@link #SPIKE_ENABLED}. Flip to {@code false} (or drop the view) once the answer is
 * recorded. Contains no resources of its own -- all sizes are Java constants scaled by density, on
 * purpose: adding a new {@code <dimen>} to SystemUI shifts the dimen resource-ID table and
 * crash-loops WindowManager-Shell.
 */
public class CutoutRingView extends View {

    /**
     * SPIKE flag. Left {@code false} after the spike concluded: nothing is drawn and no overlay
     * window is added. Flip to {@code true} to reproduce the experiment.
     */
    public static final boolean SPIKE_ENABLED = false;

    /**
     * SPIKE flag: also add the clipping-diagnostic ring inside the status bar itself. Round 1 of
     * the spike used this to establish that a status-bar-internal view is clipped at the status
     * bar window frame; leave it off for the placement evaluation.
     */
    public static final boolean SHOW_STATUS_BAR_RINGS = false;

    /** Hosted inside the status bar (PhoneStatusBarView). Draws cyan rect + green + red rings. */
    public static final int MODE_STATUS_BAR = 0;
    /** Hosted in a dedicated WindowManager overlay window. Draws magenta + blue rings. */
    public static final int MODE_OVERLAY_WINDOW = 1;
    /** Placeholder weather face + temperature string, for the placement evaluation. */
    public static final int MODE_WEATHER_PLACEHOLDER = 2;

    private static final float STROKE_DP = 1.5f;   // ~4px at 2.8125 density
    private static final float SMALL_PAD_DP = 2f;  // ~6px  outside the hole edge (green)
    private static final float LARGE_PAD_DP = 11f; // ~31px outside the hole edge (red)
    private static final float OVL_SMALL_PAD_DP = 6f;  // ~17px outside the hole edge (magenta)
    private static final float OVL_LARGE_PAD_DP = 22f; // ~62px outside the hole edge (blue)

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] mLocation = new int[2];
    private final Rect mCutoutRect = new Rect();
    private int mMode = MODE_STATUS_BAR;

    public CutoutRingView(Context context) {
        this(context, null);
    }

    public CutoutRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaint.setStyle(Paint.Style.STROKE);
        mTextPaint.setStyle(Paint.Style.FILL);
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // shadowLayer needs SW rendering
    }

    /** Selects which diagnostic rings to draw. See {@link #MODE_STATUS_BAR}. */
    public void setMode(int mode) {
        mMode = mode;
        invalidate();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        invalidate();
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        invalidate();
    }

    /** Returns the live cutout bounding rect in screen coordinates, or null if there is none. */
    private Rect fetchCutoutRect() {
        WindowInsets insets = getRootWindowInsets();
        if (insets == null) {
            return null;
        }
        DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout == null) {
            return null;
        }
        List<Rect> rects = cutout.getBoundingRects();
        if (rects == null || rects.isEmpty()) {
            return null;
        }
        // Pick the largest non-empty rect; on a single punch-hole panel there is exactly one.
        Rect best = null;
        for (Rect r : rects) {
            if (r == null || r.isEmpty()) {
                continue;
            }
            if (best == null || (r.width() * r.height()) > (best.width() * best.height())) {
                best = r;
            }
        }
        return best;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!SPIKE_ENABLED) {
            return;
        }
        Rect screenRect = fetchCutoutRect();
        if (screenRect == null) {
            return;
        }
        mCutoutRect.set(screenRect);

        // The cutout rects from WindowInsets are relative to the window, so translate by this
        // view's offset *within the window*.
        getLocationInWindow(mLocation);
        final float cx = mCutoutRect.centerX() - mLocation[0];
        final float cy = mCutoutRect.centerY() - mLocation[1];
        final float holeRadius = Math.max(mCutoutRect.width(), mCutoutRect.height()) / 2f;

        final float density = getResources().getDisplayMetrics().density;
        mPaint.setStrokeWidth(STROKE_DP * density);

        if (mMode == MODE_WEATHER_PLACEHOLDER) {
            drawWeatherPlaceholder(canvas, cx, cy, holeRadius, density);
            return;
        }

        if (mMode == MODE_OVERLAY_WINDOW) {
            // Magenta: comfortably outside the status bar's 100px window bottom.
            mPaint.setColor(Color.MAGENTA);
            canvas.drawCircle(cx, cy, holeRadius + (OVL_SMALL_PAD_DP * density), mPaint);
            // Blue: far outside it, to prove the overlay window is not height-bound.
            mPaint.setColor(Color.BLUE);
            canvas.drawCircle(cx, cy, holeRadius + (OVL_LARGE_PAD_DP * density), mPaint);
            return;
        }

        // 1. Outline of the cutout bounding rect (geometry sanity check).
        mPaint.setColor(Color.CYAN);
        canvas.drawRect(
                mCutoutRect.left - mLocation[0],
                mCutoutRect.top - mLocation[1],
                mCutoutRect.right - mLocation[0],
                mCutoutRect.bottom - mLocation[1],
                mPaint);

        // 2. Tight ring, just outside the hole.
        mPaint.setColor(Color.GREEN);
        canvas.drawCircle(cx, cy, holeRadius + (SMALL_PAD_DP * density), mPaint);

        // 3. Deliberately oversized ring: taller than the status bar, to expose the clip boundary.
        mPaint.setColor(Color.RED);
        canvas.drawCircle(cx, cy, holeRadius + (LARGE_PAD_DP * density), mPaint);
    }

    /**
     * Placeholder "weather face": an annular sun drawn as radial rays hugging the hole, plus a
     * hardcoded temperature string beside it. Deliberately crude -- the point is to measure how
     * much usable arc and side space there really is, not to look good.
     */
    private void drawWeatherPlaceholder(Canvas canvas, float cx, float cy, float holeRadius,
            float density) {
        final int statusBarHeight = SystemBarUtils.getStatusBarHeight(getContext());
        // Where the status bar ends, in this view's coordinates.
        final float barBottom = statusBarHeight - mLocation[1];

        // --- Guide: the status bar's bottom edge, so the screenshot shows the real budget. ---
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(1f);
        mPaint.setColor(0x60FF0000);
        canvas.drawLine(0, barBottom, getWidth(), barBottom, mPaint);

        // --- Annular sun: rays in the radial band between the hole and the bar's bottom edge. ---
        final float inner = holeRadius + (4f);
        final float outer = Math.min(barBottom - cy - 1f, holeRadius + (14f * density));
        mPaint.setStrokeWidth(5f);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setColor(0xFFFFC107); // amber
        final int rays = 12;
        for (int i = 0; i < rays; i++) {
            final double a = (Math.PI * 2 * i) / rays;
            final float dx = (float) Math.cos(a);
            final float dy = (float) Math.sin(a);
            canvas.drawLine(cx + dx * inner, cy + dy * inner,
                    cx + dx * outer, cy + dy * outer, mPaint);
        }

        // --- Temperature string, placed just outside the hole on the end side. ---
        mTextPaint.setTextSize(12f * density);
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setShadowLayer(3f, 0f, 1f, 0xC0000000);
        final Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        final float baseline = cy - (fm.ascent + fm.descent) / 2f;
        final float textX = (mCutoutRect.right - mLocation[0]) + (outer - holeRadius) + (6f);
        canvas.drawText("20°C", textX, baseline, mTextPaint);

        // Outline the text box so the screenshot shows exactly how much side space it needs.
        final float textW = mTextPaint.measureText("20°C");
        mPaint.setStrokeWidth(1f);
        mPaint.setColor(0x8000FF00);
        canvas.drawRect(textX, baseline + fm.ascent, textX + textW, baseline + fm.descent, mPaint);
    }
}
