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
 * Classic "fading blocks" Pulse style: each bar is drawn as a stack of blocks
 * that fade out toward the top, leaving a soft trailing glow.
 */
package com.android.systemui.pulse;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

public class FadingBlockRenderer extends Renderer {

    private static final float MAX_FRACTION = 0.62f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    public FadingBlockRenderer(PulseController controller, PulseView view,
            PulseColorController colorController) {
        super(controller, view, colorController);
        mPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        updateEnvelope();
        final int color = mColorController.getColor();
        final int r = Color.red(color);
        final int g = Color.green(color);
        final int b = Color.blue(color);

        final float slot = (float) mWidth / mBarCount;
        final float barWidth = slot * 0.6f;
        final float pad = (slot - barWidth) / 2f;
        final float maxH = mHeight * MAX_FRACTION;
        final float baseline = mHeight;
        final float radius = mRounded ? barWidth / 2f : 0f;

        final float blockH = Math.max(4f, barWidth * 0.55f);
        final float gap = blockH * 0.35f;

        for (int i = 0; i < mBarCount; i++) {
            float h = mHeights[i] * maxH;
            if (h <= 0f) {
                continue;
            }
            float left = i * slot + pad;
            float right = left + barWidth;
            int blocks = (int) (h / (blockH + gap));
            for (int blk = 0; blk <= blocks; blk++) {
                float top = baseline - (blk + 1) * (blockH + gap) + gap;
                float bot = baseline - blk * (blockH + gap);
                // Fade toward the top of the stack.
                float frac = 1f - (float) blk / Math.max(1, blocks);
                int alpha = (int) (60 + 195 * frac);
                mPaint.setColor(Color.argb(Math.min(255, alpha), r, g, b));
                mRect.set(left, top, right, bot);
                if (mRounded) {
                    canvas.drawRoundRect(mRect, radius, radius, mPaint);
                } else {
                    canvas.drawRect(mRect, mPaint);
                }
            }
        }

        if (hasEnergy()) {
            mView.postInvalidateOnAnimation();
        }
    }
}
