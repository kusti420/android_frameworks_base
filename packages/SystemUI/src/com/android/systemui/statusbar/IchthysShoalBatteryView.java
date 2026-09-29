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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;

import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import java.util.ArrayList;

/**
 * IchthysOS "Shoal": the status bar battery for the zig-zag layout (Settings.Secure
 * ichthys_statusbar_layout = "zigzag"). An upright cell as tall as both lattice rows (10 x 20 dp,
 * 1.5 dp outline, 3 dp corners) filling from the bottom in the accent colour, followed by the
 * percentage in the monospaced numeral face, outside the cell.
 *
 * <p>While the zig-zag layout is on it hides the stock battery view handed to
 * {@link #setStockBattery} and shows itself; otherwise it stays gone and restores the stock one.
 */
public class IchthysShoalBatteryView extends View implements DarkReceiver {

    private static final String LAYOUT_SETTING = "ichthys_statusbar_layout";
    private static final String FONT_MONO_SETTING = "ichthys_theme_font_mono";
    private static final float CELL_W_DP = 10f;
    private static final float CELL_H_DP = 20f;
    private static final float STROKE_DP = 1.5f;
    private static final float RADIUS_DP = 3f;
    /** Gap between the outline and the level fill. */
    private static final float INSET_DP = 1.5f;
    private static final float TEXT_SP = 11f;
    private static final float TEXT_GAP_DP = 4f;
    private static final float SIDE_PAD_DP = 2f;
    private static final int GLYPH_ON_ACCENT = 0xFF07090B;

    private final Paint mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mCell = new RectF();
    private final RectF mInner = new RectF();
    private final Path mInnerPath = new Path();
    private final Path mBolt = new Path();
    private final float mDensity;

    private View mStockBattery;
    private boolean mZigzag;
    private boolean mReceiving;
    private int mLevel = -1;
    private boolean mCharging;
    private String mText = "";
    private int mTint = Color.WHITE;

    private final ContentObserver mSettingsObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    readSettings();
                }
            };

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onBatteryIntent(intent);
        }
    };

    public IchthysShoalBatteryView(Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mFramePaint.setStyle(Paint.Style.STROKE);
        mFramePaint.setStrokeWidth(STROKE_DP * mDensity);
        mBoltPaint.setColor(GLYPH_ON_ACCENT);
        mTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP,
                context.getResources().getDisplayMetrics()));
        updateColors();
        setVisibility(GONE);
    }

    /** The stock battery this view stands in for while the zig-zag layout is on. */
    public void setStockBattery(View stockBattery) {
        mStockBattery = stockBattery;
        applyVisibility();
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
        final Intent sticky = getContext().registerReceiver(mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED);
        mReceiving = true;
        if (sticky != null) {
            onBatteryIntent(sticky);
        }
        readSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().getContentResolver().unregisterContentObserver(mSettingsObserver);
        if (mReceiving) {
            mReceiving = false;
            getContext().unregisterReceiver(mBatteryReceiver);
        }
        super.onDetachedFromWindow();
    }

    private void onBatteryIntent(Intent intent) {
        final int raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final int level = raw < 0 || scale <= 0 ? -1 : Math.round(raw * 100f / scale);
        final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        final boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        final boolean charging = plugged && (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        if (level != mLevel || charging != mCharging) {
            final boolean widthChanged = String.valueOf(level).length() != mText.length();
            mLevel = level;
            mCharging = charging;
            mText = level >= 0 ? String.valueOf(level) : "";
            setContentDescription(level >= 0 ? "Battery " + level + "%" : null);
            if (widthChanged) {
                requestLayout();
            }
            invalidate();
        }
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
        // Semibold numerals
        mTextPaint.setTypeface(Typeface.create(face, 600, false));
        applyVisibility();
        requestLayout();
        invalidate();
    }

    private void applyVisibility() {
        final int visibility = mZigzag ? VISIBLE : GONE;
        if (getVisibility() != visibility) {
            setVisibility(visibility);
        }
        if (mStockBattery != null) {
            final int stock = mZigzag ? GONE : VISIBLE;
            if (mStockBattery.getVisibility() != stock) {
                mStockBattery.setVisibility(stock);
            }
        }
    }

    private void updateColors() {
        mFillPaint.setColor(getContext().getColor(android.R.color.system_accent1_400));
        mFramePaint.setColor(mTint);
        mTextPaint.setColor(mTint);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mTint = DarkIconDispatcher.getTint(areas, this, tint);
        updateColors();
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateColors();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final float width = SIDE_PAD_DP * mDensity * 2 + CELL_W_DP * mDensity
                + (mText.isEmpty() ? 0f : TEXT_GAP_DP * mDensity + mTextPaint.measureText(mText));
        final int wanted = (int) Math.ceil(CELL_H_DP * mDensity);
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int height = heightMode == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec)
                : heightMode == MeasureSpec.AT_MOST
                        ? Math.min(wanted, MeasureSpec.getSize(heightMeasureSpec)) : wanted;
        setMeasuredDimension((int) Math.ceil(width), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float stroke = mFramePaint.getStrokeWidth();
        final float cellH = Math.min(CELL_H_DP * mDensity, getHeight() - stroke);
        final float cellW = CELL_W_DP * mDensity * cellH / (CELL_H_DP * mDensity);
        final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        final float left = rtl
                ? getWidth() - SIDE_PAD_DP * mDensity - cellW
                : SIDE_PAD_DP * mDensity;
        final float top = (getHeight() - cellH) / 2f;
        mCell.set(left + stroke / 2f, top + stroke / 2f, left + cellW - stroke / 2f,
                top + cellH - stroke / 2f);
        final float radius = RADIUS_DP * mDensity;
        canvas.drawRoundRect(mCell, radius, radius, mFramePaint);

        if (mLevel > 0) {
            final float inset = stroke / 2f + INSET_DP * mDensity * cellH / (CELL_H_DP * mDensity);
            mInner.set(mCell.left + inset, mCell.top + inset, mCell.right - inset,
                    mCell.bottom - inset);
            final float innerRadius = Math.max(0f, radius - inset);
            final float fillTop = mInner.bottom - mInner.height() * Math.min(mLevel, 100) / 100f;
            mInnerPath.reset();
            mInnerPath.addRoundRect(mInner, innerRadius, innerRadius, Path.Direction.CW);
            canvas.save();
            canvas.clipRect(mInner.left, fillTop, mInner.right, mInner.bottom);
            canvas.drawPath(mInnerPath, mFillPaint);
            canvas.restore();
        }

        if (mCharging) {
            // A small bolt across the cell, dark where it crosses the fill.
            final float w = mCell.width() * 0.7f;
            final float h = mCell.height() * 0.5f;
            final float cx = mCell.centerX();
            final float cy = mCell.centerY();
            mBolt.reset();
            mBolt.moveTo(cx + w * 0.15f, cy - h / 2f);
            mBolt.lineTo(cx - w / 2f, cy + h * 0.08f);
            mBolt.lineTo(cx - w * 0.02f, cy + h * 0.08f);
            mBolt.lineTo(cx - w * 0.15f, cy + h / 2f);
            mBolt.lineTo(cx + w / 2f, cy - h * 0.08f);
            mBolt.lineTo(cx + w * 0.02f, cy - h * 0.08f);
            mBolt.close();
            mBoltPaint.setColor(mLevel >= 50 ? GLYPH_ON_ACCENT : mTint);
            canvas.drawPath(mBolt, mBoltPaint);
        }

        if (!mText.isEmpty()) {
            final Paint.FontMetrics fm = mTextPaint.getFontMetrics();
            final float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            final float x = rtl
                    ? left - TEXT_GAP_DP * mDensity - mTextPaint.measureText(mText)
                    : left + cellW + TEXT_GAP_DP * mDensity;
            canvas.drawText(mText, x, baseline, mTextPaint);
        }
    }
}
