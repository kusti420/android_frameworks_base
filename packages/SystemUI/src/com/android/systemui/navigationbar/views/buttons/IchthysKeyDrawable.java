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

package com.android.systemui.navigationbar.views.buttons;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

/**
 * IchthysOS navbar key art (shoal / lattice / mono), drawn in code.
 *
 * <p>Extends {@link KeyButtonDrawable} so it drops into the stock {@link KeyButtonView} /
 * {@link ButtonDispatcher} plumbing unchanged (rotation / translation animators, dark intensity).
 * Unlike the stock drawable it is multi-coloured, so instead of a single SRC_ATOP tint it blends
 * each Shoal token's dark leaf towards its light leaf by the bar's dark intensity
 * ({@link IchthysNavStyle.Tokens}), and it paints its own
 * pressed state (the stock ripple still runs behind it in the view background).
 */
public class IchthysKeyDrawable extends KeyButtonDrawable {

    public static final int KEY_BACK = 0;
    public static final int KEY_HOME = 1;
    public static final int KEY_RECENTS = 2;

    private final int mStyle;
    private final int mKey;
    private final IchthysNavStyle.Tokens mTokens;
    private final int mAccent;
    private final float mDp;
    private final boolean mMirror;
    private final String mLabel;
    private final int mWidth;
    private final int mHeight;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();

    private float mDarkIntensity;
    private int mAlpha = 255;
    private boolean mPressed;

    public IchthysKeyDrawable(Context context, int style, int key, IchthysNavStyle.Tokens tokens,
            Typeface monoTypeface) {
        super(null, 0, 0, false, null);
        mStyle = style;
        mKey = key;
        mTokens = tokens;
        mAccent = tokens.accent;
        mDp = context.getResources().getDisplayMetrics().density;
        mMirror = key == KEY_BACK && style != IchthysNavStyle.MONO
                && context.getResources().getConfiguration().getLayoutDirection()
                        == View.LAYOUT_DIRECTION_RTL;

        mFill.setStyle(Paint.Style.FILL);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeCap(Paint.Cap.ROUND);
        mStroke.setStrokeJoin(Paint.Join.ROUND);

        if (style == IchthysNavStyle.MONO) {
            mLabel = key == KEY_HOME ? "home" : key == KEY_BACK ? "back" : "apps";
            mText.setTypeface(monoTypeface != null ? monoTypeface : Typeface.MONOSPACE);
            mText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f,
                    context.getResources().getDisplayMetrics()));
            mText.setTextAlign(Paint.Align.CENTER);
            mWidth = Math.round(mText.measureText(mLabel) + dp(20));
            mHeight = Math.round(dp(28));
        } else {
            mLabel = null;
            final int size = Math.round(dp(style == IchthysNavStyle.LATTICE ? 26 : 38));
            mWidth = size;
            mHeight = size;
        }
    }

    private float dp(float v) {
        return v * mDp;
    }

    // --- KeyButtonDrawable overrides -------------------------------------------------------------

    @Override
    public void setDarkIntensity(float intensity) {
        if (mDarkIntensity != intensity) {
            mDarkIntensity = intensity;
            invalidateSelf();
        }
    }

    @Override
    public float getDarkIntensity() {
        return mDarkIntensity;
    }

    @Override
    public void setShadowProperties(int x, int y, int size, int color) {
        // No shadow: the keys carry their own surface.
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha != alpha) {
            mAlpha = alpha;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Multi-coloured art: ignore the stock single-colour tint.
    }

    @Override
    public int getIntrinsicWidth() {
        return mWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mHeight;
    }

    @Override
    public ConstantState getConstantState() {
        return null;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean pressed = false;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) {
                pressed = true;
                break;
            }
        }
        if (pressed != mPressed) {
            mPressed = pressed;
            invalidateSelf();
            return true;
        }
        return false;
    }

    // --- Drawing ---------------------------------------------------------------------------------

    private int c(int dark, int light) {
        return IchthysNavStyle.mix(dark, light, mDarkIntensity);
    }

    /** Pressed feedback: nudge a surface colour towards the glyph colour. */
    private int pressed(int surface) {
        if (!mPressed) return surface;
        return IchthysNavStyle.mix(surface, c(mTokens.glyphDark, mTokens.glyphLight),
                0.14f);
    }

    private void applyAlpha(Paint p) {
        p.setAlpha(Math.round(p.getAlpha() * (mAlpha / 255f)));
    }

    private void fill(Canvas canvas, float cx, float cy, float r, int color) {
        mFill.setColor(color);
        applyAlpha(mFill);
        canvas.drawCircle(cx, cy, r, mFill);
    }

    private void ring(Canvas canvas, float cx, float cy, float r, int color, float width) {
        mStroke.setColor(color);
        applyAlpha(mStroke);
        mStroke.setStrokeWidth(width);
        canvas.drawCircle(cx, cy, r, mStroke);
    }

    private void glyphPaint(int color, float width) {
        mStroke.setColor(color);
        applyAlpha(mStroke);
        mStroke.setStrokeWidth(width);
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect b = getBounds();
        if (b.isEmpty()) return;
        final float cx = b.exactCenterX();
        final float cy = b.exactCenterY();

        canvas.save();
        canvas.translate(getTranslationX(), getTranslationY());
        if (mStyle != IchthysNavStyle.MONO) {
            canvas.rotate(getRotation(), cx, cy);
        }
        if (mMirror) {
            canvas.scale(-1f, 1f, cx, cy);
        }
        switch (mStyle) {
            case IchthysNavStyle.LATTICE:
                drawLattice(canvas, cx, cy);
                break;
            case IchthysNavStyle.MONO:
                drawMono(canvas, cx, cy);
                break;
            default:
                drawShoal(canvas, cx, cy);
                break;
        }
        canvas.restore();
    }

    private void drawShoal(Canvas canvas, float cx, float cy) {
        final float hair = dp(1);
        final float r = dp(18) - hair / 2f;
        final int card = pressed(c(mTokens.cardDark, mTokens.cardLight));
        fill(canvas, cx, cy, r, card);
        if (mKey == KEY_HOME) {
            ring(canvas, cx, cy, r, mAccent, hair);
            fill(canvas, cx, cy, dp(5.5f), mAccent);
            return;
        }
        ring(canvas, cx, cy, r, c(mTokens.hairlineDark, mTokens.hairlineLight), hair);
        glyphPaint(c(mTokens.glyphDark, mTokens.glyphLight), dp(1.8f));
        if (mKey == KEY_RECENTS) {
            // Card stack: a 10x14 card with a short line either side.
            mRect.set(cx - dp(5), cy - dp(7), cx + dp(5), cy + dp(7));
            canvas.drawRoundRect(mRect, dp(1.5f), dp(1.5f), mStroke);
            final float side = dp(7.6f);
            canvas.drawLine(cx - side, cy - dp(4.5f), cx - side, cy + dp(4.5f), mStroke);
            canvas.drawLine(cx + side, cy - dp(4.5f), cx + side, cy + dp(4.5f), mStroke);
        } else {
            chevron(canvas, cx, cy, dp(3.5f), dp(6.5f));
        }
    }

    private void drawLattice(Canvas canvas, float cx, float cy) {
        final float r = dp(12);
        if (mKey == KEY_HOME) {
            fill(canvas, cx, cy, r, mPressed
                    ? IchthysNavStyle.mix(mAccent, mTokens.onAccent, 0.18f) : mAccent);
            ring(canvas, cx, cy, dp(3.5f) - dp(0.8f), mTokens.onAccent, dp(1.6f));
            return;
        }
        fill(canvas, cx, cy, r, pressed(c(mTokens.discDark, mTokens.discLight)));
        glyphPaint(c(mTokens.glyphDark, mTokens.glyphLight), dp(1.6f));
        if (mKey == KEY_RECENTS) {
            mRect.set(cx - dp(4), cy - dp(5), cx + dp(4), cy + dp(5));
            canvas.drawRoundRect(mRect, dp(2), dp(2), mStroke);
        } else {
            chevron(canvas, cx, cy, dp(2.75f), dp(5));
        }
    }

    /**
     * "<" optically centred on (cx, cy): half-width {@code hw}, half-height {@code hh}.
     *
     * <p>A chevron centred by its bounding box looks pushed towards its open side when it sits in
     * a circle: the eye reads the triangle the two strokes imply, whose centre of mass is a third
     * of the way from the open side, not half. So the glyph is drawn a quarter of its half-width
     * towards the tip. The canvas is already rotated and mirrored by the caller, so the nudge
     * follows the tip in every orientation.
     */
    private void chevron(Canvas canvas, float cx, float cy, float hw, float hh) {
        final float x = cx - hw * 0.25f;
        mPath.reset();
        mPath.moveTo(x + hw, cy - hh);
        mPath.lineTo(x - hw, cy);
        mPath.lineTo(x + hw, cy + hh);
        canvas.drawPath(mPath, mStroke);
    }

    private void drawMono(Canvas canvas, float cx, float cy) {
        if (mPressed) {
            mFill.setColor(c(mTokens.discDark, mTokens.discLight));
            applyAlpha(mFill);
            final Rect b = getBounds();
            mRect.set(b.left, cy - dp(13), b.right, cy + dp(13));
            canvas.drawRoundRect(mRect, dp(13), dp(13), mFill);
        }
        mText.setColor(mKey == KEY_HOME ? mAccent
                : c(mTokens.mutedDark, mTokens.mutedLight));
        applyAlpha(mText);
        final Paint.FontMetrics fm = mText.getFontMetrics();
        canvas.drawText(mLabel, cx, cy - (fm.ascent + fm.descent) / 2f, mText);
    }
}
