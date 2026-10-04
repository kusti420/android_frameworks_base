/*
 * Copyright (C) 2016-2025 crDroid Android Project
 * Copyright (C) 2025 IchthysOS
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

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.BatteryManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.phone.IchthysZigzag;
import com.android.systemui.tuner.TunerService;

/**
 * IchthysOS / crDroid status bar battery bar: a thin, full-width bar drawn along the bottom edge of
 * the status bar whose length tracks the battery level. Added imperatively to the
 * {@code PhoneStatusBarView} (a {@link FrameLayout}) by the status bar host, so it does not collide
 * with the Compose start/end-side icon overlays.
 *
 * <p>All configuration is observed through the {@link TunerService} ({@code system:} prefix) and
 * the battery state through a {@link BatteryManager#ACTION_BATTERY_CHANGED} receiver.
 */
public class BatteryBarView extends View implements TunerService.Tunable {

    private static final String ENABLED =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR;
    private static final String THICKNESS =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_THICKNESS;
    private static final String STYLE =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_STYLE;
    private static final String BLEND_COLOR =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_BLEND_COLOR;
    private static final String BLEND_COLOR_REVERSE =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_BLEND_COLOR_REVERSE;
    private static final String COLOR =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_COLOR;
    private static final String LOW_COLOR =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_BATTERY_LOW_COLOR;
    private static final String ANIMATE =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_ANIMATE;
    private static final String CHARGING_COLOR_ENABLE =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_ENABLE_CHARGING_COLOR;
    private static final String CHARGING_COLOR =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_CHARGING_COLOR;

    private static final int STYLE_REGULAR = 0;
    private static final int STYLE_CENTER_MIRRORED = 1;
    private static final int STYLE_REVERSED = 2;

    private static final int LOW_LEVEL = 15;

    // Defaults mirror crDroidSettings/res/xml/battery_bar.xml.
    private boolean mEnabled = false;
    private int mThicknessDp = 2;
    private int mStyle = STYLE_REGULAR;
    private boolean mBlendColor = true;
    private boolean mBlendColorReverse = false;
    // COLOR_UNSET while statusbar_battery_bar_color was never picked: the bar then follows the
    // Shoal accent (resolved when drawing, so it tracks the theme).
    private static final int COLOR_UNSET = 0;
    private int mColor = COLOR_UNSET;
    private int mLowColor = 0xfff90028;
    private boolean mAnimate = true;
    private boolean mChargingColorEnable = true;
    private int mChargingColor = 0xffffc90f;

    private int mLevel = 0;
    private boolean mCharging = false;
    private float mDrawnLevel = 0f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator mAnimator;

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                return;
            }
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            mLevel = scale > 0 ? Math.round(level * 100f / scale) : level;
            mCharging = plugged != 0
                    || status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            updateLevel();
        }
    };

    public BatteryBarView(Context context) {
        this(context, null);
    }

    public BatteryBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaint.setStyle(Paint.Style.FILL);
        setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().registerReceiver(mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        Dependency.get(TunerService.class).addTunable(this, ENABLED, THICKNESS, STYLE,
                BLEND_COLOR, BLEND_COLOR_REVERSE, COLOR, LOW_COLOR, ANIMATE,
                CHARGING_COLOR_ENABLE, CHARGING_COLOR);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        try {
            getContext().unregisterReceiver(mBatteryReceiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        Dependency.get(TunerService.class).removeTunable(this);
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case ENABLED:
                mEnabled = TunerService.parseIntegerSwitch(newValue, false);
                updateVisibility();
                break;
            case THICKNESS:
                mThicknessDp = Math.max(1, TunerService.parseInteger(newValue, 2));
                updateThickness();
                break;
            case STYLE:
                mStyle = TunerService.parseInteger(newValue, STYLE_REGULAR);
                invalidate();
                break;
            case BLEND_COLOR:
                mBlendColor = TunerService.parseIntegerSwitch(newValue, true);
                invalidate();
                break;
            case BLEND_COLOR_REVERSE:
                mBlendColorReverse = TunerService.parseIntegerSwitch(newValue, false);
                invalidate();
                break;
            case COLOR:
                mColor = parseColor(newValue, COLOR_UNSET);
                invalidate();
                break;
            case LOW_COLOR:
                mLowColor = parseColor(newValue, 0xfff90028);
                invalidate();
                break;
            case ANIMATE:
                mAnimate = TunerService.parseIntegerSwitch(newValue, true);
                break;
            case CHARGING_COLOR_ENABLE:
                mChargingColorEnable = TunerService.parseIntegerSwitch(newValue, true);
                invalidate();
                break;
            case CHARGING_COLOR:
                mChargingColor = parseColor(newValue, 0xffffc90f);
                invalidate();
                break;
            default:
                break;
        }
    }

    /** The picked bar colour, or the status bar's accent while none was ever picked. */
    private int barColor() {
        return mColor != COLOR_UNSET ? mColor : IchthysZigzag.accent(getContext());
    }

    private static int parseColor(String value, int def) {
        if (value == null) {
            return def;
        }
        try {
            // Color picker persists a decimal (possibly negative) ARGB int.
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            try {
                return Color.parseColor(value);
            } catch (IllegalArgumentException e2) {
                return def;
            }
        }
    }

    private void updateVisibility() {
        setVisibility(mEnabled ? VISIBLE : GONE);
        if (mEnabled) {
            updateThickness();
            // Sync the drawn level to the last known battery level (the sticky
            // ACTION_BATTERY_CHANGED is delivered on receiver registration).
            updateLevel();
        }
    }

    private void updateThickness() {
        int px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                mThicknessDp, getResources().getDisplayMetrics());
        if (getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            if (lp.height != px) {
                lp.height = px;
                setLayoutParams(lp);
            }
        }
    }

    private void updateLevel() {
        if (!mEnabled) {
            return;
        }
        float target = mLevel;
        if (mAnimate && isAttachedToWindow()) {
            if (mAnimator != null) {
                mAnimator.cancel();
            }
            mAnimator = ValueAnimator.ofFloat(mDrawnLevel, target);
            mAnimator.setDuration(500);
            mAnimator.setInterpolator(new DecelerateInterpolator());
            mAnimator.addUpdateListener(a -> {
                mDrawnLevel = (float) a.getAnimatedValue();
                invalidate();
            });
            mAnimator.start();
        } else {
            mDrawnLevel = target;
            invalidate();
        }
    }

    private int currentColor() {
        if (mCharging && mChargingColorEnable) {
            return mChargingColor;
        }
        if (mBlendColor) {
            float frac = clamp(mDrawnLevel / 100f);
            // Regular blend: low color at empty -> normal color at full.
            float t = mBlendColorReverse ? (1f - frac) : frac;
            return blend(mLowColor, barColor(), t);
        }
        return mDrawnLevel <= LOW_LEVEL ? mLowColor : barColor();
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int blend(int from, int to, float t) {
        t = clamp(t);
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mEnabled) {
            return;
        }
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        final float frac = clamp(mDrawnLevel / 100f);
        final float barWidth = w * frac;
        mPaint.setColor(currentColor());
        switch (mStyle) {
            case STYLE_REVERSED:
                canvas.drawRect(w - barWidth, 0, w, h, mPaint);
                break;
            case STYLE_CENTER_MIRRORED: {
                final float center = w / 2f;
                final float half = barWidth / 2f;
                canvas.drawRect(center - half, 0, center + half, h, mPaint);
                break;
            }
            case STYLE_REGULAR:
            default:
                canvas.drawRect(0, 0, barWidth, h, mPaint);
                break;
        }
    }
}
