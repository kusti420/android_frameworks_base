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
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.PathParser;
import android.view.View;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.BluetoothController;

import java.util.ArrayList;

/**
 * IchthysOS "Shoal": the charge of a connected Bluetooth device (headphones, watch...) as a small
 * accent-outlined pill in the status bar: a Bluetooth glyph plus the percentage in the monospaced
 * numeral face. Shown only in the zig-zag layout (Settings.Secure ichthys_statusbar_layout) and
 * only while a connected device reports a battery level; the lowest level wins when several do.
 */
public class IchthysBtBatteryView extends View implements DarkReceiver,
        BluetoothController.Callback {

    private static final String LAYOUT_SETTING = "ichthys_statusbar_layout";
    private static final String FONT_MONO_SETTING = "ichthys_theme_font_mono";
    // Design: 16 dp pill, 9 dp glyph, 9.5 dp numerals, 5 dp side padding, 1 dp outline.
    private static final float PILL_HEIGHT_DP = 16f;
    private static final float GLYPH_DP = 9f;
    private static final float TEXT_DP = 9.5f;
    private static final float PAD_DP = 5f;
    private static final float GAP_DP = 2f;
    private static final float STROKE_DP = 1f;
    private static final String BT_GLYPH = "M7 7l10 10-5 5V2l5 5L7 17";

    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mGlyphSource = PathParser.createPathFromPathData(BT_GLYPH);
    private final Path mGlyph = new Path();
    private final Matrix mGlyphMatrix = new Matrix();
    private final RectF mPill = new RectF();
    private final float mDensity;

    private BluetoothController mBluetoothController;
    private boolean mZigzag;
    private int mLevel = -1;
    private String mText = "";

    private final ContentObserver mSettingsObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    readSettings();
                }
            };

    public IchthysBtBatteryView(Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(STROKE_DP * mDensity);
        mGlyphPaint.setStyle(Paint.Style.STROKE);
        mGlyphPaint.setStrokeWidth(2.6f * GLYPH_DP / 24f * mDensity);
        mGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        mGlyphPaint.setStrokeJoin(Paint.Join.ROUND);
        mTextPaint.setTextSize(TEXT_DP * mDensity);
        mTextPaint.setFakeBoldText(true);
        updateColors();
        setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(LAYOUT_SETTING), false, mSettingsObserver,
                UserHandle.USER_ALL);
        getContext().getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(FONT_MONO_SETTING), false, mSettingsObserver,
                UserHandle.USER_ALL);
        mBluetoothController = Dependency.get(BluetoothController.class);
        mBluetoothController.addCallback(this);
        readSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().getContentResolver().unregisterContentObserver(mSettingsObserver);
        if (mBluetoothController != null) {
            mBluetoothController.removeCallback(this);
            mBluetoothController = null;
        }
        super.onDetachedFromWindow();
    }

    private void readSettings() {
        mZigzag = "zigzag".equals(Settings.Secure.getStringForUser(
                getContext().getContentResolver(), LAYOUT_SETTING, UserHandle.USER_CURRENT));
        final String family = Settings.Secure.getStringForUser(getContext().getContentResolver(),
                FONT_MONO_SETTING, UserHandle.USER_CURRENT);
        Typeface face = family == null || family.isEmpty()
                ? Typeface.MONOSPACE : Typeface.create(family, Typeface.NORMAL);
        if (face == null || face.equals(Typeface.DEFAULT)) {
            face = Typeface.MONOSPACE;
        }
        mTextPaint.setTypeface(face);
        updateLevel();
    }

    private void updateLevel() {
        int level = -1;
        if (mBluetoothController != null && mBluetoothController.isBluetoothConnected()) {
            for (CachedBluetoothDevice device : mBluetoothController.getConnectedDevices()) {
                final int deviceLevel = device.getMinBatteryLevelWithMemberDevices();
                if (deviceLevel >= 0 && (level < 0 || deviceLevel < level)) {
                    level = deviceLevel;
                }
            }
        }
        final boolean show = mZigzag && level >= 0;
        if (level != mLevel) {
            mLevel = level;
            mText = level >= 0 ? String.valueOf(level) : "";
            setContentDescription(level >= 0 ? "Bluetooth device battery " + level + "%" : null);
            requestLayout();
            invalidate();
        }
        final int visibility = show ? VISIBLE : GONE;
        if (getVisibility() != visibility) {
            setVisibility(visibility);
        }
    }

    private void updateColors() {
        final int accent = getContext().getColor(android.R.color.system_accent1_400);
        mRingPaint.setColor(accent);
        mGlyphPaint.setColor(accent);
        mTextPaint.setColor(accent);
    }

    @Override
    public void onBluetoothStateChange(boolean enabled) {
        updateLevel();
    }

    @Override
    public void onBluetoothDevicesChanged() {
        updateLevel();
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        // The pill stays in the accent colour on light and dark bars alike.
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateColors();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final float pillHeight = PILL_HEIGHT_DP * mDensity;
        final float width = PAD_DP * mDensity * 2 + GLYPH_DP * mDensity + GAP_DP * mDensity
                + mTextPaint.measureText(mText);
        final int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? (int) Math.ceil(pillHeight) : MeasureSpec.getSize(heightMeasureSpec);
        // A little breathing room from the icons on either side.
        setMeasuredDimension((int) Math.ceil(width + 4 * mDensity), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mLevel < 0) {
            return;
        }
        final float pillHeight = Math.min(PILL_HEIGHT_DP * mDensity, getHeight());
        final float stroke = mRingPaint.getStrokeWidth();
        final float left = 2 * mDensity;
        final float top = (getHeight() - pillHeight) / 2f;
        mPill.set(left + stroke / 2f, top + stroke / 2f, getWidth() - left - stroke / 2f,
                top + pillHeight - stroke / 2f);
        canvas.drawRoundRect(mPill, pillHeight / 2f, pillHeight / 2f, mRingPaint);

        final float glyph = GLYPH_DP * mDensity;
        final float glyphLeft = left + PAD_DP * mDensity;
        final float glyphTop = (getHeight() - glyph) / 2f;
        mGlyphMatrix.setScale(glyph / 24f, glyph / 24f);
        mGlyphMatrix.postTranslate(glyphLeft, glyphTop);
        mGlyphSource.transform(mGlyphMatrix, mGlyph);
        canvas.drawPath(mGlyph, mGlyphPaint);

        final Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        final float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(mText, glyphLeft + glyph + GAP_DP * mDensity, baseline, mTextPaint);
    }
}
