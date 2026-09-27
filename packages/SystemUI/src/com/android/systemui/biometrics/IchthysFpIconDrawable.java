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

package com.android.systemui.biometrics;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * IchthysOS: built-in lock screen / AOD fingerprint icons (Settings.System
 * {@code ichthys_udfps_icon_style} 1..8), drawn procedurally so they stay crisp at any size and
 * take the lock screen's tint. Styles: 1 classic, 2 minimal, 3 dotted, 4 ring, 5 split,
 * 6 radar, 7 pulse ring, 8 shoal (honeycomb dot lattice).
 */
public class IchthysFpIconDrawable extends Drawable {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mArc = new RectF();
    private final Path mPath = new Path();

    private final int mStyle;
    private final boolean mAod;
    private final int mColorMode;
    private final int mCustomColor;
    private final float mOpacity;
    @Nullable private ColorStateList mTint;
    private int mAlpha = 255;

    public IchthysFpIconDrawable(Context context, boolean aod) {
        mStyle = IchthysUdfpsSettings.get(context, IchthysUdfpsSettings.ICON_STYLE,
                IchthysUdfpsSettings.ICON_STYLE_DEFAULT);
        mAod = aod;
        mColorMode = IchthysUdfpsSettings.get(context, IchthysUdfpsSettings.ICON_COLOR_MODE,
                IchthysUdfpsSettings.ICON_COLOR_MODE_DEFAULT);
        mCustomColor = mColorMode == 0 ? Color.WHITE : IchthysUdfpsSettings.resolveColor(context,
                mColorMode == 1 ? 0 : 2,
                IchthysUdfpsSettings.get(context, IchthysUdfpsSettings.ICON_COLOR,
                        IchthysUdfpsSettings.ICON_COLOR_DEFAULT));
        mOpacity = Math.max(10, Math.min(100, IchthysUdfpsSettings.get(context,
                IchthysUdfpsSettings.ICON_OPACITY,
                IchthysUdfpsSettings.ICON_OPACITY_DEFAULT))) / 100f;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mFill.setStyle(Paint.Style.FILL);
    }

    /** Whether a built-in style is selected (else the stock/pack icon is used). */
    public static boolean isEnabled(Context context) {
        return IchthysUdfpsSettings.get(context, IchthysUdfpsSettings.ICON_STYLE,
                IchthysUdfpsSettings.ICON_STYLE_DEFAULT) > 0;
    }

    public static boolean isEnabledOnAod(Context context) {
        return isEnabled(context) && IchthysUdfpsSettings.get(context,
                IchthysUdfpsSettings.ICON_ON_AOD, IchthysUdfpsSettings.ICON_ON_AOD_DEFAULT) != 0;
    }

    private int currentColor() {
        int color;
        if (mColorMode == 0) {
            color = mTint != null
                    ? mTint.getColorForState(getState(), mTint.getDefaultColor())
                    : Color.WHITE;
        } else {
            color = mCustomColor;
        }
        final float a = (Color.alpha(color) / 255f) * mOpacity * (mAlpha / 255f)
                * (mAod ? 0.85f : 1f);
        return (color & 0x00FFFFFF) | (Math.round(a * 255) << 24);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        if (b.isEmpty()) return;
        final float size = Math.min(b.width(), b.height());
        final float cx = b.exactCenterX();
        final float cy = b.exactCenterY();
        final float u = size / 24f; // work in a 24-unit box like the stock vector icons
        final int color = currentColor();
        mPaint.setColor(color);
        mFill.setColor(color);
        mPaint.setPathEffect(null);
        mPaint.setStrokeWidth((mAod ? 1.2f : 1.6f) * u);

        switch (mStyle) {
            case 1: // classic: concentric fingerprint arcs
                fingerprintArcs(canvas, cx, cy, u, 5, 2.2f, 40f);
                break;
            case 2: // minimal: three arcs
                fingerprintArcs(canvas, cx, cy, u, 3, 3.2f, 60f);
                break;
            case 3: // dotted
                mPaint.setPathEffect(new DashPathEffect(new float[] {0.01f, 2.4f * u}, 0));
                mPaint.setStrokeWidth((mAod ? 1.6f : 2.1f) * u);
                fingerprintArcs(canvas, cx, cy, u, 4, 2.6f, 30f);
                break;
            case 4: // ring with a small print
                canvas.drawCircle(cx, cy, 10.5f * u, mPaint);
                fingerprintArcs(canvas, cx, cy, u * 0.6f, 4, 2.4f, 40f);
                break;
            case 5: { // split: left and right halves offset vertically
                for (int i = 0; i < 4; i++) {
                    final float r = (3f + i * 2.4f) * u;
                    mArc.set(cx - r, cy - r - u, cx + r, cy + r - u);
                    canvas.drawArc(mArc, 110f, 120f, false, mPaint);
                    mArc.offset(0, 2 * u);
                    canvas.drawArc(mArc, -70f, 120f, false, mPaint);
                }
                break;
            }
            case 6: { // radar: rings and a sweep line
                for (int i = 1; i <= 3; i++) canvas.drawCircle(cx, cy, i * 3.4f * u, mPaint);
                canvas.drawLine(cx, cy, cx + 7.2f * u, cy - 7.2f * u, mPaint);
                canvas.drawCircle(cx + 5f * u, cy - 5f * u, 1.2f * u, mFill);
                break;
            }
            case 7: { // pulse ring: thick inner ring, thin outer ring
                mPaint.setStrokeWidth((mAod ? 1.8f : 2.6f) * u);
                canvas.drawCircle(cx, cy, 6f * u, mPaint);
                mPaint.setStrokeWidth((mAod ? 0.8f : 1f) * u);
                canvas.drawCircle(cx, cy, 10.5f * u, mPaint);
                canvas.drawCircle(cx, cy, 1.4f * u, mFill);
                break;
            }
            case 8: { // shoal: honeycomb dots clipped to a circle
                final float dot = 0.9f * u;
                final float pitch = 2.9f * u;
                final float rowH = pitch * 0.866f;
                final float lim = 10.5f * u;
                for (int row = -4; row <= 4; row++) {
                    final float y = row * rowH;
                    final float shift = (Math.abs(row) % 2) * pitch / 2f;
                    for (int col = -4; col <= 4; col++) {
                        final float x = col * pitch + shift;
                        final float d = (float) Math.hypot(x, y);
                        if (d > lim) continue;
                        canvas.drawCircle(cx + x, cy + y, dot * (1f - 0.35f * d / lim), mFill);
                    }
                }
                break;
            }
            default:
                fingerprintArcs(canvas, cx, cy, u, 5, 2.2f, 40f);
                break;
        }
    }

    /** Stylised fingerprint: `count` concentric arcs with a gap at the bottom. */
    private void fingerprintArcs(Canvas c, float cx, float cy, float u, int count, float step,
            float gapDeg) {
        for (int i = 0; i < count; i++) {
            final float r = (2.2f + i * step) * u;
            mArc.set(cx - r, cy - r, cx + r, cy + r);
            // Alternate the gap position slightly so it reads as ridges, not a target.
            final float start = 90f + gapDeg / 2f + (i % 2 == 0 ? 0f : 12f);
            c.drawArc(mArc, start, 360f - gapDeg, false, mPaint);
        }
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        mTint = tint;
        invalidateSelf();
    }

    @Override
    public boolean isStateful() {
        return mTint != null && mTint.isStateful();
    }

    @Override
    protected boolean onStateChange(@NonNull int[] state) {
        return mTint != null && mTint.isStateful();
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        mFill.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return -1;
    }
}
