/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar.gestural;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.ColorInt;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.app.animation.Interpolators;
import com.android.settingslib.Utils;
import com.android.settingslib.ichthys.ShoalContrast;
import com.android.systemui.navigationbar.views.buttons.ButtonInterface;
import com.android.systemui.navigationbar.views.buttons.IchthysNavStyle;
import com.android.systemui.res.R;

public class NavigationHandle extends View implements ButtonInterface {

    protected final Paint mPaint = new Paint();
    private @ColorInt final int mLightColor;
    private @ColorInt final int mDarkColor;
    protected final float mRadius;
    protected final float mBottom;
    private final float mAdditionalWidthForAnimation;
    private final float mAdditionalHeightForAnimation;
    private final float mShrinkWidthForAnimation;
    private boolean mRequiresInvalidate;
    private boolean mShrink;

    // IchthysOS: user styling of the gesture pill (Settings.System, observed live).
    private static final String KEY_HANDLE_HIDE = "ichthys_nav_handle_hide";
    private static final String KEY_HANDLE_WIDTH = "ichthys_nav_handle_width";
    private static final String KEY_HANDLE_THICKNESS = "ichthys_nav_handle_thickness";
    private static final String KEY_HANDLE_OPACITY = "ichthys_nav_handle_opacity";
    private boolean mStyleHidden;
    private float mStyleWidthScale = 1f;
    private float mStyleThicknessScale = 1f;
    private float mStyleOpacity = 1f;
    // IchthysOS navBar.style = "gestures": a track with an accent segment in the centre.
    private boolean mIchthysGestures;
    private int mIchthysAccent;
    private int mIchthysTrackDark;
    private int mIchthysTrackLight;
    private float mIchthysDarkIntensity;
    private final Paint mIchthysPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ContentObserver mStyleObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    readStyle();
                    invalidate();
                }
            };

    private ObjectAnimator mPulseAnimator = null;
    private float mPulseAnimationProgress;

    private static final FloatProperty<NavigationHandle> PULSE_ANIMATION_PROGRESS =
            new FloatProperty<>("pulseAnimationProgress") {
                @Override
                public Float get(NavigationHandle controller) {
                    return controller.getPulseAnimationProgress();
                }

                @Override
                public void setValue(NavigationHandle controller, float progress) {
                    controller.setPulseAnimationProgress(progress);
                }
            };

    public NavigationHandle(Context context) {
        this(context, null);
    }

    public NavigationHandle(Context context, AttributeSet attr) {
        super(context, attr);
        final Resources res = context.getResources();
        mRadius = res.getDimension(R.dimen.navigation_handle_radius);
        mBottom = res.getDimension(R.dimen.navigation_handle_bottom);
        mAdditionalWidthForAnimation =
                res.getDimension(R.dimen.navigation_home_handle_additional_width_for_animation);
        mAdditionalHeightForAnimation =
                res.getDimension(R.dimen.navigation_home_handle_additional_height_for_animation);
        mShrinkWidthForAnimation =
                res.getDimension(R.dimen.navigation_home_handle_shrink_width_for_animation);

        final int dualToneDarkTheme = Utils.getThemeAttr(context, R.attr.darkIconTheme);
        final int dualToneLightTheme = Utils.getThemeAttr(context, R.attr.lightIconTheme);
        Context lightContext = new ContextThemeWrapper(context, dualToneLightTheme);
        Context darkContext = new ContextThemeWrapper(context, dualToneDarkTheme);
        mLightColor = Utils.getColorAttrDefaultColor(lightContext, R.attr.homeHandleColor);
        mDarkColor = Utils.getColorAttrDefaultColor(darkContext, R.attr.homeHandleColor);
        mPaint.setAntiAlias(true);
        setFocusable(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (String key : new String[] {KEY_HANDLE_HIDE, KEY_HANDLE_WIDTH, KEY_HANDLE_THICKNESS,
                KEY_HANDLE_OPACITY}) {
            getContext().getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, mStyleObserver, UserHandle.USER_ALL);
        }
        for (Uri uri : IchthysNavStyle.observedUris()) {
            getContext().getContentResolver().registerContentObserver(uri, false, mStyleObserver,
                    UserHandle.USER_ALL);
        }
        readStyle();
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().getContentResolver().unregisterContentObserver(mStyleObserver);
        super.onDetachedFromWindow();
    }

    private void readStyle() {
        final android.content.ContentResolver cr = getContext().getContentResolver();
        mStyleHidden = Settings.System.getIntForUser(cr, KEY_HANDLE_HIDE, 0,
                UserHandle.USER_CURRENT) != 0;
        mStyleWidthScale = Math.max(30, Math.min(150, Settings.System.getIntForUser(cr,
                KEY_HANDLE_WIDTH, 100, UserHandle.USER_CURRENT))) / 100f;
        mStyleThicknessScale = Math.max(50, Math.min(250, Settings.System.getIntForUser(cr,
                KEY_HANDLE_THICKNESS, 100, UserHandle.USER_CURRENT))) / 100f;
        mStyleOpacity = Math.max(10, Math.min(100, Settings.System.getIntForUser(cr,
                KEY_HANDLE_OPACITY, 100, UserHandle.USER_CURRENT))) / 100f;
        mIchthysGestures = IchthysNavStyle.read(getContext()) == IchthysNavStyle.GESTURES;
        if (mIchthysGestures) {
            final IchthysNavStyle.Tokens tokens = IchthysNavStyle.tokens(getContext());
            mIchthysAccent = tokens.accent;
            mIchthysTrackDark = tokens.trackDark;
            mIchthysTrackLight = tokens.trackLight;
        }
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // IchthysOS: the Shoal tokens arrive as a resource overlay (palette / accent / night).
        if (mIchthysGestures) {
            readStyle();
            invalidate();
        }
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (alpha > 0f && mRequiresInvalidate) {
            mRequiresInvalidate = false;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw that bar
        int navHeight = getHeight();
        float additionalHeight;
        float additionalWidth;
        if (mShrink) {
            additionalHeight = 0;
            additionalWidth = -mShrinkWidthForAnimation * mPulseAnimationProgress;
        } else {
            additionalHeight = mAdditionalHeightForAnimation * mPulseAnimationProgress;
            additionalWidth = mAdditionalWidthForAnimation * mPulseAnimationProgress;
        }

        // IchthysOS: hidden pill (the gesture area still works), and width/thickness/opacity scales
        // applied around the pill's centre.
        if (mStyleHidden) {
            return;
        }
        float height = mRadius * 2 * mStyleThicknessScale + additionalHeight;
        float fullWidth = getWidth() + 2 * additionalWidth;
        float styledWidth = fullWidth * mStyleWidthScale;
        float x = -additionalWidth + (fullWidth - styledWidth) / 2f;
        float width = x + styledWidth;
        float y = navHeight - mBottom - height + (additionalHeight / 2);
        float adjustedRadius = height / 2;
        if (mIchthysGestures) {
            // 108 x 5 dp track with a 22 dp accent segment in the centre.
            final float dp = getResources().getDisplayMetrics().density;
            final float ichHeight = 5 * dp * mStyleThicknessScale + additionalHeight;
            final float ichY = navHeight - mBottom - ichHeight + (additionalHeight / 2);
            final float ichRadius = ichHeight / 2;
            final int alpha = Math.round(255 * mStyleOpacity);
            final int track = IchthysNavStyle.mix(mIchthysTrackDark, mIchthysTrackLight,
                    mIchthysDarkIntensity);
            mIchthysPaint.setColor(track);
            mIchthysPaint.setAlpha(alpha);
            canvas.drawRoundRect(x, ichY, width, ichY + ichHeight, ichRadius, ichRadius,
                    mIchthysPaint);
            final float cx = (x + width) / 2f;
            final float half = Math.min(11 * dp, (width - x) / 2f);
            // IchthysOS: the accent segment is a thin indicator on the track: contrast-guarded
            // against it, so a dark accent shows as a lighter tone of itself.
            final int segment = ShoalContrast.forGraphic(mIchthysAccent, track);
            mIchthysPaint.setColor(segment);
            mIchthysPaint.setAlpha(Math.round(Color.alpha(segment) * mStyleOpacity));
            canvas.drawRoundRect(cx - half, ichY, cx + half, ichY + ichHeight, ichRadius,
                    ichRadius, mIchthysPaint);
            return;
        }
        final int baseAlpha = mPaint.getAlpha();
        mPaint.setAlpha(Math.round(baseAlpha * mStyleOpacity));
        canvas.drawRoundRect(x, y, width, y + height, adjustedRadius, adjustedRadius, mPaint);
        mPaint.setAlpha(baseAlpha);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {}

    @Override
    public void abortCurrentGesture() {}

    @Override
    public void setVertical(boolean vertical) {}

    @Override
    public void setDarkIntensity(float intensity) {
        if (mIchthysDarkIntensity != intensity) {
            mIchthysDarkIntensity = intensity;
            if (mIchthysGestures) {
                if (getVisibility() == VISIBLE && getAlpha() > 0) {
                    invalidate();
                } else {
                    mRequiresInvalidate = true;
                }
            }
        }
        int color = (int) ArgbEvaluator.getInstance().evaluate(intensity, mLightColor, mDarkColor);
        if (mPaint.getColor() != color) {
            mPaint.setColor(color);
            if (getVisibility() == VISIBLE && getAlpha() > 0) {
                invalidate();
            } else {
                // If we are currently invisible, then invalidate when we are next made visible
                mRequiresInvalidate = true;
            }
        }
    }

    @Override
    public void setDelayTouchFeedback(boolean shouldDelay) {}

    @Override
    public void animateLongPress(boolean isTouchDown, boolean shrink, long durationMs) {
        if (mPulseAnimator != null) {
            mPulseAnimator.cancel();
        }

        mShrink = shrink;
        Interpolator interpolator;
        if (shrink) {
            interpolator = Interpolators.LEGACY_DECELERATE;
        } else {
            if (isTouchDown) {
                // For now we animate the navbar expanding and contracting so that the navbar is
                // the original size by the end of {@code duration}. This is because a screenshot
                // is taken at that point and we don't want to capture the larger navbar.
                // TODO(b/306400785): Determine a way to exclude navbar from the screenshot.

                // Fraction of the touch down animation to expand; remaining is used to contract
                // again.
                float expandFraction = 0.9f;
                interpolator = t -> t <= expandFraction
                        ? Interpolators.clampToProgress(Interpolators.LEGACY, t, 0, expandFraction)
                        : 1 - Interpolators.clampToProgress(
                                Interpolators.LINEAR, t, expandFraction, 1);
            } else {
                interpolator = Interpolators.LEGACY_DECELERATE;
            }
        }

        mPulseAnimator =
                ObjectAnimator.ofFloat(this, PULSE_ANIMATION_PROGRESS, isTouchDown ? 1 : 0);
        mPulseAnimator.setDuration(durationMs).setInterpolator(interpolator);
        mPulseAnimator.start();
    }

    private void setPulseAnimationProgress(float pulseAnimationProgress) {
        mPulseAnimationProgress = pulseAnimationProgress;
        invalidate();
    }

    private float getPulseAnimationProgress() {
        return mPulseAnimationProgress;
    }
}
