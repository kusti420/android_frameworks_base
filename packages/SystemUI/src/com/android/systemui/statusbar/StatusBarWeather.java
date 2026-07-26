/**
 * Copyright (C) 2024-2026 IchthysOS Project
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
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.android.internal.util.crdroid.OmniJawsClient;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;

/**
 * Status bar weather indicator. A self-contained {@link TextView} that shows the
 * current temperature (optionally with the condition icon) served by the OmniJaws
 * weather provider via {@link OmniJawsClient}.
 *
 * <p>It observes the {@code status_bar_weather} system setting through the
 * {@link TunerService} ({@code system:} prefix). Values:
 * <ul>
 *   <li>0 - disabled</li>
 *   <li>1 - temperature only</li>
 *   <li>2 - temperature and condition icon</li>
 * </ul>
 *
 * <p>The view registers/unregisters its tunable and OmniJaws observer itself on
 * attach/detach. Tinting is driven by {@link DarkReceiver}; the host is responsible
 * for registering the view with the appropriate {@link DarkIconDispatcher}.
 *
 * @hide
 */
public class StatusBarWeather extends TextView implements TunerService.Tunable,
        DarkReceiver, OmniJawsClient.OmniJawsObserver {

    private static final int STYLE_DISABLED = 0;
    private static final int STYLE_TEMP_ONLY = 1;
    private static final int STYLE_TEMP_AND_ICON = 2;

    private static final String STATUS_BAR_WEATHER =
            "system:" + Settings.System.STATUS_BAR_WEATHER;

    private final OmniJawsClient mWeatherClient;
    private final int mIconSizePx;

    private int mStyle = STYLE_DISABLED;
    private int mTint = Color.WHITE;
    private boolean mAttached;
    private OmniJawsClient.WeatherInfo mWeatherInfo;

    public StatusBarWeather(Context context) {
        this(context, null);
    }

    public StatusBarWeather(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarWeather(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mWeatherClient = OmniJawsClient.get();
        mIconSizePx = getResources().getDimensionPixelSize(R.dimen.weather_image_max_size);
        setSingleLine(true);
        setGravity(Gravity.CENTER_VERTICAL);
        setVisibility(View.GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            Dependency.get(TunerService.class).addTunable(this, STATUS_BAR_WEATHER);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mAttached = false;
            Dependency.get(TunerService.class).removeTunable(this);
            mWeatherClient.removeObserver(getContext(), this);
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (STATUS_BAR_WEATHER.equals(key)) {
            mStyle = TunerService.parseInteger(newValue, STYLE_DISABLED);
            if (mStyle != STYLE_DISABLED) {
                mWeatherClient.addObserver(getContext(), this);
                queryAndUpdate();
            } else {
                mWeatherClient.removeObserver(getContext(), this);
                updateVisibility();
            }
        }
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdate();
    }

    @Override
    public void weatherError(int errorReason) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null;
            updateVisibility();
        }
    }

    private void queryAndUpdate() {
        if (mStyle == STYLE_DISABLED || !mWeatherClient.isOmniJawsEnabled(getContext())) {
            mWeatherInfo = null;
            updateVisibility();
            return;
        }
        try {
            mWeatherClient.queryWeather(getContext());
            mWeatherInfo = mWeatherClient.getWeatherInfo();
        } catch (Exception e) {
            mWeatherInfo = null;
        }
        updateContent();
        updateVisibility();
    }

    private void updateContent() {
        if (mWeatherInfo == null) {
            setText("");
            setCompoundDrawables(null, null, null, null);
            return;
        }

        final String temp = mWeatherInfo.temp + mWeatherInfo.tempUnits;
        if (!TextUtils.equals(temp, getText())) {
            setText(temp);
        }

        if (mStyle == STYLE_TEMP_AND_ICON) {
            Drawable icon = mWeatherClient.getWeatherConditionImage(
                    getContext(), mWeatherInfo.conditionCode);
            if (icon != null) {
                icon = icon.mutate();
                icon.setBounds(0, 0, mIconSizePx, mIconSizePx);
                icon.setColorFilter(mTint, PorterDuff.Mode.SRC_IN);
            }
            setCompoundDrawablePadding(mIconSizePx / 4);
            setCompoundDrawables(icon, null, null, null);
        } else {
            setCompoundDrawables(null, null, null, null);
        }
    }

    private void updateVisibility() {
        final boolean visible = mStyle != STYLE_DISABLED
                && mWeatherInfo != null
                && getText().length() != 0;
        setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mTint = DarkIconDispatcher.getTint(areas, this, tint);
        setTextColor(mTint);
        // Re-tint the condition icon to match.
        updateContent();
    }
}
