/*
 * Copyright (C) 2016 The Dirty Unicorns Project
 * Copyright (C) 2026 crDroid Android / IchthysOS
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
 *
 * Bar-based renderer that covers most of the Pulse render styles offered in
 * the Settings menu. Each style is a variation on drawing per-bar magnitudes
 * anchored to the bottom of the view.
 */
package com.android.systemui.pulse;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Random;

public class SolidLineRenderer extends Renderer {

    public static final int MODE_SOLID = 0;
    public static final int MODE_NEON = 1;
    public static final int MODE_RETRO = 2;
    public static final int MODE_MINIMAL = 3;
    public static final int MODE_SPARKLE = 4;
    public static final int MODE_MATRIX = 5;
    public static final int MODE_PARTICLE = 6;
    public static final int MODE_WAVEFORM = 7;

    // Fraction of the view height a full-scale bar occupies.
    private static final float MAX_FRACTION = 0.62f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Random mRandom = new Random();

    private final int mMode;

    // For the waveform mode.
    private byte[] mWaveform;

    // Lightweight particle pool for the particle mode.
    private float[] mParticleY;
    private float[] mParticleV;

    public SolidLineRenderer(PulseController controller, PulseView view,
            PulseColorController colorController, int mode) {
        super(controller, view, colorController);
        mMode = mode;
        mPaint.setStyle(Paint.Style.FILL);
        mGlowPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void setBarCount(int count) {
        super.setBarCount(count);
        mParticleY = new float[count];
        mParticleV = new float[count];
    }

    @Override
    public void onWaveFormUpdate(byte[] waveform) {
        if (mMode == MODE_WAVEFORM) {
            mWaveform = waveform;
            mView.postInvalidate();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        updateEnvelope();
        final int color = mColorController.getColor();

        if (mMode == MODE_WAVEFORM) {
            drawWaveform(canvas, color);
        } else {
            drawBars(canvas, color);
        }

        // Keep animating while there is anything to settle / animate.
        boolean animated = mMode == MODE_SPARKLE || mMode == MODE_MATRIX
                || mMode == MODE_PARTICLE || mMode == MODE_WAVEFORM;
        if (hasEnergy() || (animated && isValidStream())) {
            mView.postInvalidateOnAnimation();
        }
    }

    private void drawBars(Canvas canvas, int color) {
        final float slot = (float) mWidth / mBarCount;
        final float barWidth = slot * 0.6f;
        final float pad = (slot - barWidth) / 2f;
        final float maxH = mHeight * MAX_FRACTION;
        final float baseline = mHeight;
        final float radius = mRounded ? barWidth / 2f : 0f;

        mPaint.setColor(color);
        mPaint.setAlpha(220);

        for (int i = 0; i < mBarCount; i++) {
            float h = mHeights[i] * maxH;
            if (h <= 0f) {
                continue;
            }
            float left = i * slot + pad;
            float right = left + barWidth;

            switch (mMode) {
                case MODE_MINIMAL: {
                    float cx = left + barWidth / 2f;
                    mPaint.setStrokeWidth(Math.max(2f, barWidth * 0.25f));
                    mPaint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(cx, baseline, cx, baseline - h, mPaint);
                    // dot cap
                    mPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, baseline - h, Math.max(2f, barWidth * 0.18f), mPaint);
                    break;
                }
                case MODE_NEON: {
                    mGlowPaint.setColor(color);
                    mGlowPaint.setAlpha(150);
                    mGlowPaint.setMaskFilter(new BlurMaskFilter(
                            Math.max(4f, barWidth), BlurMaskFilter.Blur.NORMAL));
                    mRect.set(left, baseline - h, right, baseline);
                    canvas.drawRoundRect(mRect, radius, radius, mGlowPaint);
                    mPaint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(mRect, radius, radius, mPaint);
                    break;
                }
                case MODE_RETRO: {
                    // Segmented VU meter with green->red gradient.
                    float segH = maxH / 16f;
                    int segs = (int) Math.ceil(h / segH);
                    for (int s = 0; s < segs; s++) {
                        float top = baseline - (s + 1) * segH + segH * 0.15f;
                        float bot = baseline - s * segH - segH * 0.15f;
                        float frac = (float) s / 16f;
                        mPaint.setColor(vuColor(frac));
                        mPaint.setStyle(Paint.Style.FILL);
                        mRect.set(left, top, right, bot);
                        canvas.drawRoundRect(mRect, radius, radius, mPaint);
                    }
                    break;
                }
                case MODE_SPARKLE: {
                    mPaint.setStyle(Paint.Style.FILL);
                    mRect.set(left, baseline - h, right, baseline);
                    mPaint.setAlpha(160);
                    canvas.drawRoundRect(mRect, radius, radius, mPaint);
                    // sparkle at the tip
                    if (mHeights[i] > 0.15f && mRandom.nextFloat() < 0.6f) {
                        mPaint.setAlpha(255);
                        float sx = left + mRandom.nextFloat() * barWidth;
                        float sy = baseline - h - mRandom.nextFloat() * barWidth;
                        canvas.drawCircle(sx, sy, Math.max(1.5f, barWidth * 0.12f), mPaint);
                    }
                    break;
                }
                case MODE_MATRIX: {
                    // Falling stacked cells.
                    float cellH = barWidth;
                    int cells = (int) (h / cellH);
                    mPaint.setStyle(Paint.Style.FILL);
                    for (int c = 0; c <= cells; c++) {
                        float top = baseline - (c + 1) * cellH + cellH * 0.2f;
                        float bot = baseline - c * cellH;
                        int a = 60 + (int) (195f * ((float) c / Math.max(1, cells)));
                        mPaint.setAlpha(Math.min(255, a));
                        mRect.set(left, top, right, bot);
                        canvas.drawRect(mRect, mPaint);
                    }
                    break;
                }
                case MODE_PARTICLE: {
                    mPaint.setStyle(Paint.Style.FILL);
                    mPaint.setAlpha(90);
                    float cx = left + barWidth / 2f;
                    mRect.set(left, baseline - h, right, baseline);
                    canvas.drawRoundRect(mRect, radius, radius, mPaint);
                    // emit a particle from the tip that floats up and fades
                    float targetY = baseline - h;
                    if (mParticleY[i] > targetY) {
                        mParticleY[i] = targetY;
                        mParticleV[i] = -(2f + mHeights[i] * 8f);
                    }
                    mParticleY[i] += mParticleV[i];
                    mParticleV[i] += 0.15f; // gravity
                    float travel = (baseline - mParticleY[i]);
                    float maxTravel = maxH + barWidth * 4f;
                    int alpha = (int) (200f * (1f - Math.min(1f, travel / maxTravel)));
                    if (alpha > 0) {
                        mPaint.setAlpha(alpha);
                        canvas.drawCircle(cx, mParticleY[i],
                                Math.max(2f, barWidth * 0.22f), mPaint);
                    }
                    break;
                }
                case MODE_SOLID:
                default: {
                    mPaint.setStyle(Paint.Style.FILL);
                    mPaint.setAlpha(220);
                    mRect.set(left, baseline - h, right, baseline);
                    if (mRounded) {
                        canvas.drawRoundRect(mRect, radius, radius, mPaint);
                    } else {
                        canvas.drawRect(mRect, mPaint);
                    }
                    break;
                }
            }
        }
    }

    private void drawWaveform(Canvas canvas, int color) {
        byte[] wf = mWaveform;
        if (wf == null || wf.length == 0) {
            return;
        }
        mPaint.setColor(color);
        mPaint.setAlpha(230);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(3f, mHeight * 0.006f));
        final float mid = mHeight * 0.75f;
        final float amp = mHeight * 0.22f;
        final int n = wf.length;
        final float dx = (float) mWidth / (n - 1);
        float prevX = 0f;
        // 8-bit unsigned PCM centered at 128.
        float prevY = mid + ((wf[0] & 0xFF) - 128) / 128f * amp;
        for (int i = 1; i < n; i++) {
            float x = i * dx;
            float y = mid + ((wf[i] & 0xFF) - 128) / 128f * amp;
            canvas.drawLine(prevX, prevY, x, y, mPaint);
            prevX = x;
            prevY = y;
        }
    }

    private int vuColor(float frac) {
        // 0 -> green, 0.6 -> yellow, 1 -> red
        if (frac < 0.6f) {
            float t = frac / 0.6f;
            return Color.rgb((int) (255 * t), 220, 40);
        } else {
            float t = (frac - 0.6f) / 0.4f;
            return Color.rgb(255, (int) (220 * (1f - t)), 40);
        }
    }

    @Override
    public void destroy() {
        mGlowPaint.setMaskFilter(null);
    }
}
