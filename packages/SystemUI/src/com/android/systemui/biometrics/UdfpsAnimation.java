/**
 * Copyright (C) 2019 The Android Open Source Project
 * Copyright (C) 2024 crDroid Android Project
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

import static android.content.Intent.ACTION_USER_SWITCHED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.KeyguardStateController;

/**
 * Optional under-display fingerprint "recognizing" animation that is played, behind the UDFPS
 * icon, while the sensor is illuminated during authentication on the keyguard.
 *
 * <p>The animation drawables are loaded by name from the external resource package
 * {@link #UDFPS_ANIMATIONS_PACKAGE}. The selected style is stored in
 * {@link Settings.System#UDFPS_ANIM_STYLE} (index into the {@code udfps_animation_styles} array,
 * where index {@code 0} means "no animation").
 *
 * <p>Adapted from crDroid Android. Unlike the upstream version this implementation derives the
 * sensor position purely from {@link FingerprintSensorPropertiesInternal} rather than depending on
 * AuthController / DisplayUtils.getScaleFactor (neither of which is wired into this tree's
 * UdfpsController), so it stays compile-safe against AOSP android15-tests.
 */
public class UdfpsAnimation extends ImageView {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "UdfpsAnimations";

    private static final String UDFPS_ANIMATIONS_PACKAGE = "com.crdroid.udfps.animations";

    private boolean mShowing = false;
    private boolean mIsKeyguard;
    private boolean mAnimationAdded = false;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final FingerprintSensorPropertiesInternal mProps;
    private final KeyguardStateController mKeyguardStateController;

    private final int mAnimationSize;
    private final WindowManager.LayoutParams mAnimParams = new WindowManager.LayoutParams();

    private AnimationDrawable recognizingAnim;

    private final KeyguardStateController.Callback keyguardStateCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onKeyguardFadingAwayChanged() {
            removeAnimation();
        }
        @Override
        public void onKeyguardGoingAwayChanged() {
            removeAnimation();
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USER_SWITCHED.equals(intent.getAction())) {
                updateAnimationStyle();
            }
        }
    };

    public UdfpsAnimation(Context context, WindowManager windowManager,
            FingerprintSensorPropertiesInternal props,
            KeyguardStateController keyguardStateController) {
        super(context);
        mContext = context;
        mWindowManager = windowManager;
        mProps = props;
        mKeyguardStateController = keyguardStateController;

        mAnimationSize = mContext.getResources()
                .getDimensionPixelSize(R.dimen.udfps_animation_size);

        mAnimParams.height = mAnimationSize;
        mAnimParams.width = mAnimationSize;
        mAnimParams.format = PixelFormat.TRANSLUCENT;
        // Must sit behind the UDFPS icon window.
        mAnimParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
        mAnimParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;

        updatePosition();

        setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    }

    private void addAnimation() {
        if (!mAnimationAdded) {
            IntentFilter filter = new IntentFilter(ACTION_USER_SWITCHED);
            mContext.registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, filter, null, null);
            updateAnimationStyle();
            mKeyguardStateController.addCallback(keyguardStateCallback);
            mAnimationAdded = true;
        }
    }

    private void updateAnimationStyle() {
        int value = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.UDFPS_ANIM_STYLE, 0, UserHandle.USER_CURRENT);
        mContext.getMainExecutor().execute(() -> updateAnimationStyle(value));
    }

    private void updateAnimationStyle(int styleIdx) {
        Drawable bgDrawable = getBgDrawable(styleIdx);
        if (styleIdx == 0 || bgDrawable == null) {
            setBackground(null);
            recognizingAnim = null;
        } else {
            setBackground(bgDrawable);
            recognizingAnim = bgDrawable instanceof AnimationDrawable
                    ? (AnimationDrawable) bgDrawable : null;
        }
    }

    private Drawable getBgDrawable(int styleIdx) {
        Resources apkResources;
        try {
            apkResources = mContext.getPackageManager()
                    .getResourcesForApplication(UDFPS_ANIMATIONS_PACKAGE);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Failed to load package resources", e);
            return null;
        }
        int arrayId = apkResources.getIdentifier("udfps_animation_styles",
                "array", UDFPS_ANIMATIONS_PACKAGE);
        if (arrayId == 0) {
            return null;
        }
        String[] styleNames = apkResources.getStringArray(arrayId);
        if (styleNames == null || styleIdx < 0 || styleIdx >= styleNames.length) {
            return null;
        }
        String drawableName = styleNames[styleIdx];
        if (DEBUG) Log.i(LOG_TAG, "Updating animation style to: " + drawableName);
        try {
            int resId = apkResources.getIdentifier(drawableName, "drawable",
                    UDFPS_ANIMATIONS_PACKAGE);
            if (resId == 0) {
                return null;
            }
            return apkResources.getDrawable(resId, mContext.getTheme());
        } catch (Resources.NotFoundException e) {
            Log.w(LOG_TAG, "Drawable resource not found: " + drawableName, e);
            return null;
        }
    }

    public boolean isAnimationEnabled() {
        return recognizingAnim != null;
    }

    public void updatePosition() {
        final float sensorLocationY = mProps.getLocation().sensorLocationY;
        final float sensorRadius = mProps.getLocation().sensorRadius;
        final int animationOffset = mContext.getResources()
                .getDimensionPixelSize(R.dimen.udfps_animation_offset);
        mAnimParams.y = (int) sensorLocationY - (int) sensorRadius
                - (mAnimationSize / 2) + animationOffset;
    }

    public void show() {
        if (mShowing || !mIsKeyguard || recognizingAnim == null) return;
        try {
            if (getWindowToken() == null) {
                mWindowManager.addView(this, mAnimParams);
            } else {
                mWindowManager.updateViewLayout(this, mAnimParams);
            }
            mShowing = true;
            recognizingAnim.start();
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "Error adding view to WindowManager", e);
        }
    }

    public void hide() {
        if (!mShowing && getWindowToken() == null) return;
        try {
            if (recognizingAnim != null) {
                clearAnimation();
                recognizingAnim.stop();
                recognizingAnim.selectDrawable(0);
            }
            mWindowManager.removeView(this);
            mShowing = false;
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "Error removing view from WindowManager", e);
        }
    }

    public void removeAnimation() {
        hide();
        if (mAnimationAdded) {
            mContext.unregisterReceiver(mIntentReceiver);
            mKeyguardStateController.removeCallback(keyguardStateCallback);
            mAnimationAdded = false;
        }
    }

    public void setIsKeyguard(boolean isKeyguard) {
        mIsKeyguard = isKeyguard;
        if (mIsKeyguard) {
            addAnimation();
        } else {
            removeAnimation();
        }
    }
}
