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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;

import com.android.settingslib.ichthys.ShoalContrast;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepositoryImpl;
import com.android.systemui.statusbar.pipeline.battery.shared.model.BatteryCustomization;

import java.util.ArrayList;

import lineageos.providers.LineageSettings;

/**
 * IchthysOS "Shoal": the status bar battery for the zig-zag layout (Settings.Secure
 * ichthys_statusbar_layout = "zigzag"). An upright cell as tall as both lattice rows (10 x 20 dp,
 * 1.5 dp outline, 3 dp corners) filling from the bottom in the accent colour, followed by the
 * percentage in the monospaced numeral face, outside the cell.
 *
 * <p>While the zig-zag layout is on it hides the stock battery view handed to
 * {@link #setStockBattery} and shows itself; otherwise it stays gone and restores the stock one.
 *
 * <p>IchthysOS: it honours the Status bar > Battery page (the same keys BatteryRepositoryImpl and
 * BatteryCustomization read): style "hidden" hides it and "text" drops the cell; the percent mode,
 * percent while charging, percent only while charging and bold percent; the charging and saver
 * marks; icon colour, state colours and low level; size and frame thickness; and the "battery"
 * entry of the hidden-icons list. The cell shape itself stays the zig-zag cell for every other
 * style. Two keys keep the Shoal look while they have never been written: an unset percent mode
 * shows the percentage and an unset icon colour uses the accent. In the QS header
 * ({@link #setQsHeader}) the qs_battery_style / qs_show_battery_percent overrides apply instead,
 * and the hidden-icons list does not.
 */
public class IchthysShoalBatteryView extends View implements DarkReceiver {

    private static final String LAYOUT_SETTING = "ichthys_statusbar_layout";
    private static final String FONT_MONO_SETTING = "ichthys_theme_font_mono";
    private static final String ICON_HIDE_LIST = "icon_blacklist";
    private static final String TEXT_CHARGING_SETTING = "status_bar_battery_text_charging";
    private static final String QS_PERCENT_SETTING = "qs_show_battery_percent";
    /** System keys this view reads; all of them re-read the settings on change. */
    private static final String[] SYSTEM_SETTINGS = {
            TEXT_CHARGING_SETTING,
            QS_PERCENT_SETTING,
            BatteryCustomization.KEY_QS_STYLE,
            BatteryCustomization.KEY_PERCENT_CHARGING_ONLY,
            BatteryCustomization.KEY_PERCENT_BOLD,
            BatteryCustomization.KEY_CHARGING_INDICATOR,
            BatteryCustomization.KEY_POWER_SAVE_INDICATOR,
            BatteryCustomization.KEY_FILL_MODE,
            BatteryCustomization.KEY_FILL_COLOR,
            BatteryCustomization.KEY_STATE_COLORS,
            BatteryCustomization.KEY_CHARGING_COLOR,
            BatteryCustomization.KEY_POWER_SAVE_COLOR,
            BatteryCustomization.KEY_LOW_COLOR,
            BatteryCustomization.KEY_LOW_LEVEL,
            BatteryCustomization.KEY_SCALE,
            BatteryCustomization.KEY_FRAME_THICKNESS,
    };
    /** Unset fill mode: the Shoal accent fill (see the class comment). */
    private static final int FILL_MODE_UNSET = -1;
    /** Stroke per ichthys_battery_frame_thickness step; the default step 3 is the 1.5 dp outline. */
    private static final float STROKE_STEP_DP = 0.5f;
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
    /** IchthysOS: the ring / pie of the circle styles, in the level colour. */
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mLandscapeInner = new RectF();
    private final float mDensity;

    private View mStockBattery;
    // IchthysOS: the stock battery's own binder sets it visible again whenever its lifecycle
    // restarts (UnifiedBatteryViewBinder), so the hide is re-checked before every frame; a frame
    // that would have shown both batteries is skipped rather than drawn.
    private final ViewTreeObserver.OnPreDrawListener mStockGuard = () -> !applyStockVisibility();
    private boolean mZigzag;
    private boolean mReceiving;
    private int mLevel = -1;
    private boolean mCharging;
    private String mText = "";
    private int mTint = Color.WHITE;
    private boolean mPowerSave;

    // IchthysOS: Status bar > Battery options, see readSettings().
    private boolean mQsHeader;
    private boolean mHidden;
    /** "battery" in the hidden-icons list; also hides the stock battery in the one-row layout. */
    private boolean mHiddenByList;
    private boolean mTextOnly;
    /** IchthysOS: STATUS_BAR_BATTERY_STYLE (portrait, landscape, circle, dotted, filled circle). */
    private int mStyle = BatteryRepositoryImpl.BATTERY_STYLE_PORTRAIT;
    private boolean mPercentEnabled = true;
    private boolean mPercentBefore;
    private boolean mTextCharging = true;
    private boolean mPercentChargingOnly;
    private boolean mPercentBold;
    private boolean mChargingIndicator = true;
    private boolean mPowerSaveIndicator = true;
    private int mFillMode = FILL_MODE_UNSET;
    private int mFillColor = BatteryCustomization.FILL_COLOR_UNSET;
    private int mStateColors = BatteryCustomization.STATE_COLORS_THEME;
    private int mChargingColor = BatteryCustomization.DEFAULT_CHARGING_COLOR;
    private int mPowerSaveColor = BatteryCustomization.DEFAULT_POWER_SAVE_COLOR;
    private int mLowColor = BatteryCustomization.DEFAULT_LOW_COLOR;
    private int mLowLevel = BatteryCustomization.DEFAULT_LOW_LEVEL;
    private float mScale = 1f;
    private float mFrameStrokeDp = STROKE_DP;

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
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                updatePowerSave();
            } else {
                onBatteryIntent(intent);
            }
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

    /**
     * IchthysOS: this instance sits in the QS header, so the QS header battery style and percent
     * overrides (qs_battery_style, qs_show_battery_percent) apply instead of the status bar ones.
     */
    public void setQsHeader(boolean qsHeader) {
        if (mQsHeader != qsHeader) {
            mQsHeader = qsHeader;
            if (isAttachedToWindow()) {
                readSettings();
            }
        }
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
        getContext().getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(ICON_HIDE_LIST), false, mSettingsObserver,
                UserHandle.USER_ALL);
        for (String key : SYSTEM_SETTINGS) {
            getContext().getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, mSettingsObserver,
                    UserHandle.USER_ALL);
        }
        getContext().getContentResolver().registerContentObserver(
                LineageSettings.System.getUriFor(LineageSettings.System.STATUS_BAR_BATTERY_STYLE),
                false, mSettingsObserver, UserHandle.USER_ALL);
        getContext().getContentResolver().registerContentObserver(
                LineageSettings.System.getUriFor(
                        LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT),
                false, mSettingsObserver, UserHandle.USER_ALL);
        final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        final Intent sticky = getContext().registerReceiver(mBatteryReceiver, filter,
                Context.RECEIVER_EXPORTED);
        mReceiving = true;
        if (sticky != null) {
            onBatteryIntent(sticky);
        }
        updatePowerSave();
        readSettings();
        getViewTreeObserver().addOnPreDrawListener(mStockGuard);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(mStockGuard);
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
            mLevel = level;
            mCharging = charging;
            setContentDescription(level >= 0 ? "Battery " + level + "%" : null);
            updateText();
            updateColors();
            invalidate();
        }
    }

    private void updatePowerSave() {
        final PowerManager pm = getContext().getSystemService(PowerManager.class);
        final boolean powerSave = pm != null && pm.isPowerSaveMode();
        if (powerSave != mPowerSave) {
            mPowerSave = powerSave;
            updateColors();
            invalidate();
        }
    }

    /** IchthysOS: the percentage text for the current level, charging state and options. */
    private void updateText() {
        final boolean show;
        if (mTextOnly) {
            show = true;
        } else if (mCharging) {
            show = mTextCharging || mPercentEnabled;
        } else {
            show = mPercentEnabled && !mPercentChargingOnly;
        }
        final String text = show && mLevel >= 0 ? String.valueOf(mLevel) : "";
        if (!text.equals(mText)) {
            final boolean widthChanged = text.length() != mText.length();
            mText = text;
            if (widthChanged) {
                requestLayout();
            }
        }
    }

    private void readSettings() {
        // Unset means the default, zigzag (see IchthysZigzag.isEnabled).
        mZigzag = !"single".equals(Settings.Secure.getStringForUser(
                getContext().getContentResolver(), LAYOUT_SETTING, UserHandle.USER_CURRENT));
        final String family = Settings.Secure.getStringForUser(getContext().getContentResolver(),
                FONT_MONO_SETTING, UserHandle.USER_CURRENT);
        Typeface face = family == null || family.isEmpty()
                ? Typeface.MONOSPACE : Typeface.create(family, Typeface.NORMAL);
        if (face == null || face.equals(Typeface.DEFAULT)) {
            face = Typeface.MONOSPACE;
        }
        readBatteryOptions();
        // Semibold numerals, bold with "Bold percentage"
        mTextPaint.setTypeface(Typeface.create(face, mPercentBold ? 800 : 600, false));
        mFramePaint.setStrokeWidth(mFrameStrokeDp * mDensity);
        updateText();
        updateColors();
        applyVisibility();
        requestLayout();
        invalidate();
    }

    /** IchthysOS: the Status bar > Battery page; semantics as in BatteryRepositoryImpl. */
    private void readBatteryOptions() {
        final ContentResolver cr = getContext().getContentResolver();
        final int user = UserHandle.USER_CURRENT;
        int style = LineageSettings.System.getIntForUser(cr,
                LineageSettings.System.STATUS_BAR_BATTERY_STYLE,
                BatteryRepositoryImpl.BATTERY_STYLE_PORTRAIT, user);
        int percentMode = LineageSettings.System.getIntForUser(cr,
                LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT,
                BatteryRepositoryImpl.PERCENT_MODE_UNSET, user);
        if (mQsHeader) {
            final int qsStyle = Settings.System.getIntForUser(cr,
                    BatteryCustomization.KEY_QS_STYLE, BatteryCustomization.QS_STYLE_FOLLOW, user);
            if (qsStyle != BatteryCustomization.QS_STYLE_FOLLOW) {
                style = qsStyle;
            }
            final int qsPercent = Settings.System.getIntForUser(cr, QS_PERCENT_SETTING,
                    BatteryRepositoryImpl.QS_PERCENT_MODE_DEFAULT, user);
            // Default: the QS header always shows the percent, on the status bar's side.
            if (qsPercent != BatteryRepositoryImpl.QS_PERCENT_MODE_DEFAULT) {
                percentMode = qsPercent;
            } else if (percentMode == 0) {
                percentMode = BatteryRepositoryImpl.PERCENT_MODE_UNSET;
            }
        }
        mTextOnly = style == BatteryRepositoryImpl.BATTERY_STYLE_TEXT;
        // IchthysOS: a new style has another cell width; the old measure clipped the percent.
        if (mStyle != style) requestLayout();
        mStyle = style;
        mHiddenByList = false;
        if (!mQsHeader) {
            final String hideList = Settings.Secure.getStringForUser(cr, ICON_HIDE_LIST, user);
            mHiddenByList = StatusBarIconController.getIconHideList(getContext(), hideList)
                    .contains(getContext().getString(
                            com.android.internal.R.string.status_bar_battery));
        }
        mHidden = mHiddenByList || style == BatteryRepositoryImpl.BATTERY_STYLE_HIDDEN;
        // 0 = hidden; 1 (inside) has no room in the narrow cell, so it is drawn beside it like 3.
        mPercentEnabled = percentMode != 0;
        mPercentBefore = percentMode == BatteryRepositoryImpl.PERCENT_MODE_NEXT_TO;
        mTextCharging = Settings.System.getIntForUser(cr, TEXT_CHARGING_SETTING, 1, user) != 0;
        mPercentChargingOnly = systemInt(cr, BatteryCustomization.KEY_PERCENT_CHARGING_ONLY, 0)
                != 0;
        mPercentBold = systemInt(cr, BatteryCustomization.KEY_PERCENT_BOLD, 0) != 0;
        mChargingIndicator = systemInt(cr, BatteryCustomization.KEY_CHARGING_INDICATOR, 0) != 0;
        mPowerSaveIndicator = systemInt(cr, BatteryCustomization.KEY_POWER_SAVE_INDICATOR, 1)
                != 0;
        mFillMode = systemInt(cr, BatteryCustomization.KEY_FILL_MODE, FILL_MODE_UNSET);
        mFillColor = systemInt(cr, BatteryCustomization.KEY_FILL_COLOR,
                BatteryCustomization.FILL_COLOR_UNSET);
        mStateColors = systemInt(cr, BatteryCustomization.KEY_STATE_COLORS,
                BatteryCustomization.STATE_COLORS_THEME);
        mChargingColor = systemInt(cr, BatteryCustomization.KEY_CHARGING_COLOR,
                BatteryCustomization.DEFAULT_CHARGING_COLOR);
        mPowerSaveColor = systemInt(cr, BatteryCustomization.KEY_POWER_SAVE_COLOR,
                BatteryCustomization.DEFAULT_POWER_SAVE_COLOR);
        mLowColor = systemInt(cr, BatteryCustomization.KEY_LOW_COLOR,
                BatteryCustomization.DEFAULT_LOW_COLOR);
        mLowLevel = Math.max(BatteryCustomization.MIN_LOW_LEVEL,
                Math.min(BatteryCustomization.MAX_LOW_LEVEL,
                        systemInt(cr, BatteryCustomization.KEY_LOW_LEVEL,
                                BatteryCustomization.DEFAULT_LOW_LEVEL)));
        mScale = Math.max(BatteryCustomization.MIN_SCALE,
                Math.min(BatteryCustomization.MAX_SCALE,
                        systemInt(cr, BatteryCustomization.KEY_SCALE,
                                BatteryCustomization.DEFAULT_SCALE))) / 100f;
        // IchthysOS: the QS header's icon row is one fixed height; a size above 100 % made the
        // cell taller than the row and the pill / circle were clipped top and bottom. The user's
        // size applies to the status bar; the header shows the icon at its stock size.
        if (mQsHeader) {
            mScale = Math.min(mScale, 1f);
        }
        mFrameStrokeDp = STROKE_STEP_DP * Math.max(BatteryCustomization.MIN_FRAME_THICKNESS,
                Math.min(BatteryCustomization.MAX_FRAME_THICKNESS,
                        systemInt(cr, BatteryCustomization.KEY_FRAME_THICKNESS,
                                BatteryCustomization.DEFAULT_FRAME_THICKNESS)));
    }

    private static int systemInt(ContentResolver cr, String key, int def) {
        return Settings.System.getIntForUser(cr, key, def, UserHandle.USER_CURRENT);
    }

    private void applyVisibility() {
        final int visibility = mZigzag && !mHidden ? VISIBLE : GONE;
        if (getVisibility() != visibility) {
            setVisibility(visibility);
        }
        applyStockVisibility();
    }

    /** Returns whether the stock battery's visibility had to be changed. */
    private boolean applyStockVisibility() {
        if (mStockBattery == null) {
            return false;
        }
        // IchthysOS: the Status bar icons > Battery switch also applies to the stock battery
        // of the one-row layout, which does not read the hidden-icons list itself.
        final int stock = mZigzag || mHiddenByList ? GONE : VISIBLE;
        if (mStockBattery.getVisibility() == stock) {
            return false;
        }
        mStockBattery.setVisibility(stock);
        return true;
    }

    private void updateColors() {
        final int accent = getContext().getColor(android.R.color.system_accent1_400);
        // IchthysOS: icon colour (unset keeps the Shoal accent), then the state colours.
        int fill;
        switch (mFillMode) {
            case BatteryCustomization.FILL_MODE_TINT:
                fill = mTint;
                break;
            case BatteryCustomization.FILL_MODE_CUSTOM:
                fill = mFillColor == BatteryCustomization.FILL_COLOR_UNSET ? accent : mFillColor;
                break;
            default:
                fill = accent;
                break;
        }
        if (mStateColors != BatteryCustomization.STATE_COLORS_OFF) {
            final boolean custom = mStateColors == BatteryCustomization.STATE_COLORS_CUSTOM;
            if (mCharging) {
                fill = custom ? mChargingColor : BatteryCustomization.DEFAULT_CHARGING_COLOR;
            } else if (mPowerSave) {
                fill = custom ? mPowerSaveColor : BatteryCustomization.DEFAULT_POWER_SAVE_COLOR;
            } else if (mLevel >= 0 && mLevel <= mLowLevel) {
                fill = custom ? mLowColor : BatteryCustomization.DEFAULT_LOW_COLOR;
            }
        }
        mFillPaint.setColor(fill);
        mFramePaint.setColor(mTint);
        mTextPaint.setColor(mTint);
    }

    /**
     * IchthysOS: a glyph colour that reads on top of the level fill. The Shoal dark glyph, except
     * on a fill that white contrasts with more (a dark accent such as indigo, or icon colour
     * "match status bar icons" on a light bar); chosen by WCAG contrast, not a fixed luminance cut.
     */
    private int glyphOnFill() {
        return ShoalContrast.onFill(mFillPaint.getColor()) == Color.WHITE
                ? Color.WHITE : GLYPH_ON_ACCENT;
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
        final float cell = mTextOnly ? 0f : cellWidthFor(CELL_H_DP * mScale * mDensity);
        final float gap = mTextOnly ? 0f : TEXT_GAP_DP * mDensity;
        final float width = SIDE_PAD_DP * mDensity * 2 + cell
                + (mText.isEmpty() ? 0f : gap + mTextPaint.measureText(mText));
        final int wanted = (int) Math.ceil(CELL_H_DP * mScale * mDensity);
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int height = heightMode == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec)
                : heightMode == MeasureSpec.AT_MOST
                        ? Math.min(wanted, MeasureSpec.getSize(heightMeasureSpec)) : wanted;
        setMeasuredDimension((int) Math.ceil(width), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        final float textWidth = mText.isEmpty() ? 0f : mTextPaint.measureText(mText);
        if (mTextOnly) {
            // IchthysOS: battery style "text": the percentage alone.
            if (!mText.isEmpty()) {
                drawText(canvas, SIDE_PAD_DP * mDensity);
            }
            return;
        }
        final float stroke = mFramePaint.getStrokeWidth();
        final float cellH = Math.min(CELL_H_DP * mScale * mDensity, getHeight() - stroke);
        final float cellW = cellWidthFor(cellH);
        // IchthysOS: percent mode 2 puts the text before the cell (mirrored under RTL).
        final boolean cellAtStart = !mPercentBefore;
        final boolean cellOnLeft = cellAtStart != rtl;
        final float left = cellOnLeft
                ? SIDE_PAD_DP * mDensity
                : getWidth() - SIDE_PAD_DP * mDensity - cellW;
        final float top = (getHeight() - cellH) / 2f;
        mCell.set(left + stroke / 2f, top + stroke / 2f, left + cellW - stroke / 2f,
                top + cellH - stroke / 2f);
        final float radius = RADIUS_DP * mDensity * cellH / (CELL_H_DP * mDensity);
        final boolean round = isCircleStyle();
        // IchthysOS 2026-10-29: the styles that draw their own level (no bottom-up fill below).
        final boolean ownFill = round || isNewStyle()
                || mStyle == BatteryRepositoryImpl.BATTERY_STYLE_LANDSCAPE;
        if (round) {
            drawCircleCell(canvas, stroke);
        } else if (mStyle == BatteryRepositoryImpl.BATTERY_STYLE_LANDSCAPE) {
            drawLandscapeCell(canvas, stroke, cellH);
        } else if (isNewStyle()) {
            drawNewStyleCell(canvas, stroke, cellH);
        } else {
            canvas.drawRoundRect(mCell, radius, radius, mFramePaint);
        }

        if (mLevel > 0 && !ownFill) {
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

        // On the circle styles the glyph sits on the dark centre, never on the fill.
        final int glyph = isCircleStyle() && mStyle != BatteryRepositoryImpl.BATTERY_STYLE_FULL_CIRCLE
                ? mTint : mLevel >= 50 ? glyphOnFill() : mTint;
        if (mCharging && mChargingIndicator) {
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
            mBoltPaint.setColor(glyph);
            canvas.drawPath(mBolt, mBoltPaint);
        } else if (mPowerSave && mPowerSaveIndicator && !mCharging) {
            // IchthysOS: battery saver mark, a plus across the cell.
            final float arm = mCell.width() * 0.3f;
            final float bar = Math.max(stroke, mCell.width() * 0.16f);
            final float cx = mCell.centerX();
            final float cy = mCell.centerY();
            mBoltPaint.setColor(glyph);
            canvas.drawRect(cx - arm, cy - bar / 2f, cx + arm, cy + bar / 2f, mBoltPaint);
            canvas.drawRect(cx - bar / 2f, cy - arm, cx + bar / 2f, cy + arm, mBoltPaint);
        }

        if (!mText.isEmpty()) {
            final float x = cellOnLeft
                    ? left + cellW + TEXT_GAP_DP * mDensity
                    : left - TEXT_GAP_DP * mDensity - textWidth;
            drawText(canvas, x);
        }
    }

    private boolean isCircleStyle() {
        return mStyle == BatteryRepositoryImpl.BATTERY_STYLE_CIRCLE
                || mStyle == BatteryRepositoryImpl.BATTERY_STYLE_DOTTED_CIRCLE
                || mStyle == BatteryRepositoryImpl.BATTERY_STYLE_FULL_CIRCLE;
    }

    /** IchthysOS 2026-10-29: segments, arc gauge, bar, hexagon (IchthysBatteryStyles). */
    private boolean isNewStyle() {
        return mStyle == BatteryRepositoryImpl.BATTERY_STYLE_SEGMENTS
                || mStyle == BatteryRepositoryImpl.BATTERY_STYLE_ARC
                || mStyle == BatteryRepositoryImpl.BATTERY_STYLE_BAR
                || mStyle == BatteryRepositoryImpl.BATTERY_STYLE_HEXAGON;
    }

    /** IchthysOS: the cell's width for a cell [cellH] tall, per battery style. */
    private float cellWidthFor(float cellH) {
        if (isCircleStyle()) return cellH * 0.9f;
        if (mStyle == BatteryRepositoryImpl.BATTERY_STYLE_LANDSCAPE) return cellH * 1.55f;
        if (mStyle == BatteryRepositoryImpl.BATTERY_STYLE_SEGMENTS) return cellH * 1.7f;
        if (mStyle == BatteryRepositoryImpl.BATTERY_STYLE_BAR) return cellH * 1.9f;
        if (mStyle == BatteryRepositoryImpl.BATTERY_STYLE_ARC
                || mStyle == BatteryRepositoryImpl.BATTERY_STYLE_HEXAGON) return cellH * 0.95f;
        return CELL_W_DP * cellH / CELL_H_DP;
    }

    /**
     * IchthysOS 2026-10-29: the four newer styles, matching IchthysBatteryStyles. Segments: a
     * landscape cell of four bars, each a quarter of the charge. Arc: a 270-degree gauge open at
     * the bottom. Bar: a bare track and fill. Hexagon: a flat-topped outline filling from the
     * bottom.
     */
    private void drawNewStyleCell(Canvas canvas, float stroke, float cellH) {
        final float cx = mCell.centerX();
        final float cy = mCell.centerY();
        final int level = Math.max(0, Math.min(mLevel, 100));
        switch (mStyle) {
            case BatteryRepositoryImpl.BATTERY_STYLE_SEGMENTS: {
                final float h = cellH * 0.62f;
                final float nub = mCell.width() * 0.07f;
                final float top = cy - h / 2f;
                final float right = mCell.right - nub;
                final float radius = h * 0.22f;
                mInner.set(mCell.left, top, right, top + h);
                canvas.drawRoundRect(mInner, radius, radius, mFramePaint);
                canvas.drawRect(right + stroke / 2f, cy - h * 0.18f, mCell.right, cy + h * 0.18f,
                        nubPaint(mTint));
                final float inset = stroke / 2f + INSET_DP * mDensity * 0.6f;
                final float gap = Math.max(1f, inset * 0.8f);
                final float l = mInner.left + inset;
                final float r = mInner.right - inset;
                final float cellW = (r - l - gap * 3f) / 4f;
                final int lit = mLevel < 0 ? 0 : Math.min(4, (level + 12) / 25);
                for (int i = 0; i < 4; i++) {
                    final float x = l + i * (cellW + gap);
                    final Paint p = i < lit ? mFillPaint : mRingPaint;
                    if (i >= lit) {
                        mRingPaint.setStyle(Paint.Style.FILL);
                        mRingPaint.setPathEffect(null);
                        mRingPaint.setColor(mTint);
                        mRingPaint.setAlpha(60);
                    }
                    mLandscapeInner.set(x, mInner.top + inset, x + cellW, mInner.bottom - inset);
                    canvas.drawRoundRect(mLandscapeInner, gap * 0.6f, gap * 0.6f, p);
                }
                return;
            }
            case BatteryRepositoryImpl.BATTERY_STYLE_ARC: {
                final float size = Math.min(mCell.width(), mCell.height());
                final float ring = Math.max(stroke * 1.4f, size * 0.15f);
                final float r = size / 2f - ring / 2f;
                mInner.set(cx - r, cy - r, cx + r, cy + r);
                mRingPaint.setStyle(Paint.Style.STROKE);
                mRingPaint.setStrokeWidth(ring);
                mRingPaint.setStrokeCap(Paint.Cap.ROUND);
                mRingPaint.setPathEffect(null);
                mRingPaint.setColor(mTint);
                mRingPaint.setAlpha(70);
                canvas.drawArc(mInner, 135f, 270f, false, mRingPaint);
                if (level > 0) {
                    mRingPaint.setColor(mFillPaint.getColor());
                    canvas.drawArc(mInner, 135f, 270f * level / 100f, false, mRingPaint);
                }
                return;
            }
            case BatteryRepositoryImpl.BATTERY_STYLE_BAR: {
                final float h = Math.max(stroke * 1.6f, cellH * 0.3f);
                final float top = cy - h / 2f;
                mInner.set(mCell.left, top, mCell.right, top + h);
                mRingPaint.setStyle(Paint.Style.FILL);
                mRingPaint.setPathEffect(null);
                mRingPaint.setColor(mTint);
                mRingPaint.setAlpha(70);
                canvas.drawRoundRect(mInner, h / 2f, h / 2f, mRingPaint);
                if (level > 0) {
                    final float w = Math.max(h, mInner.width() * level / 100f);
                    mLandscapeInner.set(mInner.left, top, mInner.left + w, top + h);
                    canvas.drawRoundRect(mLandscapeInner, h / 2f, h / 2f, mFillPaint);
                }
                return;
            }
            default: { // HEXAGON
                final float size = Math.min(mCell.width(), mCell.height());
                final float r = size / 2f - stroke / 2f;
                mInnerPath.reset();
                for (int i = 0; i < 6; i++) {
                    final double a = Math.toRadians(60.0 * i - 30.0);
                    final float x = cx + r * (float) Math.cos(a);
                    final float y = cy + r * (float) Math.sin(a);
                    if (i == 0) mInnerPath.moveTo(x, y); else mInnerPath.lineTo(x, y);
                }
                mInnerPath.close();
                if (level > 0) {
                    final float fillTop = cy + r - 2f * r * level / 100f;
                    canvas.save();
                    canvas.clipRect(cx - r, fillTop, cx + r, cy + r);
                    canvas.drawPath(mInnerPath, mFillPaint);
                    canvas.restore();
                }
                canvas.drawPath(mInnerPath, mFramePaint);
            }
        }
    }

    /**
     * IchthysOS: circle styles. Circle: a faint track and a ring that fills clockwise from the
     * top with the level. Dotted: the same, dashed. Filled: an outline with a pie of the level.
     */
    private void drawCircleCell(Canvas canvas, float stroke) {
        final float size = Math.min(mCell.width(), mCell.height());
        final float ring = Math.max(stroke * 1.4f, size * 0.15f);
        final float cx = mCell.centerX();
        final float cy = mCell.centerY();
        final float r = size / 2f - ring / 2f;
        mInner.set(cx - r, cy - r, cx + r, cy + r);
        final float sweep = 360f * Math.max(0, Math.min(mLevel, 100)) / 100f;
        if (mStyle == BatteryRepositoryImpl.BATTERY_STYLE_FULL_CIRCLE) {
            canvas.drawCircle(cx, cy, size / 2f - stroke / 2f, mFramePaint);
            final float pr = size / 2f - stroke - INSET_DP * mDensity * 0.6f;
            mInner.set(cx - pr, cy - pr, cx + pr, cy + pr);
            canvas.drawArc(mInner, -90f, sweep, true, mFillPaint);
            return;
        }
        final boolean dotted = mStyle == BatteryRepositoryImpl.BATTERY_STYLE_DOTTED_CIRCLE;
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(ring);
        mRingPaint.setStrokeCap(dotted ? Paint.Cap.BUTT : Paint.Cap.ROUND);
        final float circumference = (float) (2 * Math.PI * r);
        // 12 dashes around the ring for the dotted style.
        mRingPaint.setPathEffect(dotted
                ? new DashPathEffect(new float[] {circumference / 24f, circumference / 24f}, 0f)
                : null);
        mRingPaint.setColor(mTint);
        mRingPaint.setAlpha(70);
        canvas.drawArc(mInner, 0f, 360f, false, mRingPaint);
        if (sweep > 0f) {
            mRingPaint.setColor(mFillPaint.getColor());
            canvas.drawArc(mInner, -90f, sweep, false, mRingPaint);
        }
    }

    /** IchthysOS: landscape style, a horizontal cell with a nub, filling left to right. */
    private void drawLandscapeCell(Canvas canvas, float stroke, float cellH) {
        final float nub = mCell.width() * 0.08f;
        final float h = cellH * 0.62f;
        final float top = mCell.centerY() - h / 2f;
        final float right = mCell.right - nub;
        final float radius = h * 0.28f;
        mInner.set(mCell.left, top, right, top + h);
        canvas.drawRoundRect(mInner, radius, radius, mFramePaint);
        canvas.drawRect(right + stroke / 2f, mCell.centerY() - h * 0.18f, mCell.right,
                mCell.centerY() + h * 0.18f, nubPaint(mTint));
        if (mLevel > 0) {
            final float inset = stroke / 2f + INSET_DP * mDensity * 0.7f;
            final float l = mInner.left + inset;
            final float r = mInner.right - inset;
            final float fillRight = l + (r - l) * Math.min(mLevel, 100) / 100f;
            mInnerPath.reset();
            mLandscapeInner.set(l, mInner.top + inset, r, mInner.bottom - inset);
            mInnerPath.addRoundRect(mLandscapeInner, Math.max(0f, radius - inset),
                    Math.max(0f, radius - inset), Path.Direction.CW);
            canvas.save();
            canvas.clipRect(l, mLandscapeInner.top, fillRight, mLandscapeInner.bottom);
            canvas.drawPath(mInnerPath, mFillPaint);
            canvas.restore();
        }
    }

    private Paint nubPaint(int color) {
        mRingPaint.setPathEffect(null);
        mRingPaint.setStyle(Paint.Style.FILL);
        mRingPaint.setColor(color);
        return mRingPaint;
    }

    private void drawText(Canvas canvas, float x) {
        final Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        final float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(mText, x, baseline, mTextPaint);
    }
}
