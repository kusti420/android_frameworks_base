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

package com.android.systemui.edgelight;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.util.settings.SystemSettings;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * IchthysOS: backend for the crDroid "Edge light" screen
 * ({@code crDroidSettings/res/xml/edge_light_settings.xml}).
 *
 * <p>When a notification arrives while the screen is dozing, pulses a coloured border around the
 * display {@code edge_light_pulse_count} times. Everything about how it looks is decided by
 * {@link EdgeLightEffect} and {@link EdgeLightColorMode}, both of which are pure and unit-tested;
 * this class supplies the trigger, the window and the resolved colour.
 *
 * <p>The setting keys are spelled out rather than referenced through
 * {@code android.provider.Settings.System} so the feature stays a SystemUI-only change.
 */
@SysUISingleton
public class EdgeLightController implements CoreStartable {

    private static final String TAG = "EdgeLight";

    static final String KEY_ENABLED = "edge_light_enabled";
    static final String KEY_PULSE_COUNT = "edge_light_pulse_count";
    static final String KEY_STROKE_WIDTH = "edge_light_stroke_width";
    static final String KEY_COLOR_MODE = "edge_light_color_mode";
    static final String KEY_STYLE = "edge_light_style";
    static final String KEY_ANIMATION = "edge_light_animation_effect";
    static final String KEY_CUSTOM_COLOR = "edge_light_custom_color";

    /** Slider bounds from edge_light_settings.xml. */
    static final int MIN_PULSE_COUNT = 1;
    static final int MAX_PULSE_COUNT = 5;
    static final int DEFAULT_PULSE_COUNT = 3;
    static final int MIN_STROKE_WIDTH_DP = 2;
    static final int MAX_STROKE_WIDTH_DP = 32;
    static final int DEFAULT_STROKE_WIDTH_DP = 8;

    /** Duration of a single pulse. */
    static final long PULSE_DURATION_MS = 1_400L;

    /** The {@code rounded} value of {@code edge_light_style}. */
    private static final String STYLE_ROUNDED = "rounded";

    /** Corner radius used by the rounded style when the device reports none. */
    private static final float FALLBACK_CORNER_RADIUS_DP = 32f;

    private final Context mContext;
    private final SystemSettings mSystemSettings;
    private final Handler mHandler;
    private final NotificationListener mNotificationListener;
    private final StatusBarStateController mStatusBarStateController;

    @Nullable private WindowManager mWindowManager;
    @Nullable private EdgeLightView mView;
    @Nullable private ValueAnimator mAnimator;

    private boolean mEnabled;
    private int mPulseCount = DEFAULT_PULSE_COUNT;
    private int mStrokeWidthDp = DEFAULT_STROKE_WIDTH_DP;
    private EdgeLightColorMode mColorMode = EdgeLightColorMode.ACCENT;
    private EdgeLightEffect mEffect = EdgeLightEffect.NONE;
    private boolean mRoundedStyle;
    private int mCustomColor = EdgeLightColorMode.FALLBACK_COLOR;
    private long mPulsesShown;

    @Inject
    public EdgeLightController(
            @Application Context context,
            SystemSettings systemSettings,
            @Main Handler handler,
            NotificationListener notificationListener,
            StatusBarStateController statusBarStateController) {
        mContext = context;
        mSystemSettings = systemSettings;
        mHandler = handler;
        mNotificationListener = notificationListener;
        mStatusBarStateController = statusBarStateController;
    }

    @Override
    public void start() {
        mWindowManager = mContext.getSystemService(WindowManager.class);

        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                readSettings();
                if (!mEnabled) {
                    hide();
                }
            }
        };
        for (String key : new String[] {KEY_ENABLED, KEY_PULSE_COUNT, KEY_STROKE_WIDTH,
                KEY_COLOR_MODE, KEY_STYLE, KEY_ANIMATION, KEY_CUSTOM_COLOR}) {
            mSystemSettings.registerContentObserverForUserAsync(key, observer, UserHandle.USER_ALL);
        }

        mNotificationListener.addNotificationHandler(new NotificationListener.NotificationHandler() {
            @Override
            public void onNotificationPosted(StatusBarNotification sbn, RankingMap ranking) {
                mHandler.post(() -> onNotification(sbn, ranking));
            }

            @Override
            public void onNotificationRemoved(StatusBarNotification sbn, RankingMap ranking) {
            }

            @Override
            public void onNotificationRemoved(StatusBarNotification sbn, RankingMap ranking,
                    int reason) {
            }

            @Override
            public void onNotificationRankingUpdate(RankingMap ranking) {
            }

            @Override
            public void onNotificationsInitialized() {
            }
        });

        readSettings();
    }

    private void readSettings() {
        mEnabled = mSystemSettings.getInt(KEY_ENABLED, 0) != 0;
        mPulseCount = clamp(mSystemSettings.getInt(KEY_PULSE_COUNT, DEFAULT_PULSE_COUNT),
                MIN_PULSE_COUNT, MAX_PULSE_COUNT);
        mStrokeWidthDp = clamp(mSystemSettings.getInt(KEY_STROKE_WIDTH, DEFAULT_STROKE_WIDTH_DP),
                MIN_STROKE_WIDTH_DP, MAX_STROKE_WIDTH_DP);
        mColorMode = EdgeLightColorMode.fromSettings(mSystemSettings.getString(KEY_COLOR_MODE));
        mEffect = EdgeLightEffect.fromSettings(mSystemSettings.getString(KEY_ANIMATION));
        mRoundedStyle = STYLE_ROUNDED.equals(mSystemSettings.getString(KEY_STYLE));
        mCustomColor = EdgeLightColorMode.sanitize(EdgeLightColorMode.parseSettingsColor(
                mSystemSettings.getString(KEY_CUSTOM_COLOR),
                EdgeLightColorMode.FALLBACK_COLOR));
    }

    static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private void onNotification(StatusBarNotification sbn, RankingMap rankingMap) {
        if (!mEnabled || sbn == null) {
            return;
        }
        // Only while the screen is dozing: the edge light exists to be seen on an idle display,
        // and flashing a border over live content would be obnoxious.
        if (!mStatusBarStateController.isDozing()) {
            return;
        }
        if (isSuppressed(sbn, rankingMap)) {
            return;
        }
        show(resolveColor(sbn));
    }

    /**
     * Skips notifications that should not light up the screen.
     *
     * <p>Importance comes from the {@link RankingMap}, i.e. from the notification's channel.
     * {@link Notification#priority} looks like the obvious field but has been deprecated since
     * API 26 and modern apps leave it at {@link Notification#PRIORITY_DEFAULT} whatever their
     * channel says, so gating on it would let silent notifications -- "Charging this device via
     * USB" and friends -- light up the whole screen.
     */
    private static boolean isSuppressed(StatusBarNotification sbn, @Nullable RankingMap rankingMap) {
        final Notification notification = sbn.getNotification();
        if (notification == null) {
            return true;
        }
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return true;  // the children pulse instead; the summary would double up
        }
        if (notification.isMediaNotification()) {
            return true;  // transport controls update constantly
        }
        return importanceOf(sbn, rankingMap) < NotificationManager.IMPORTANCE_DEFAULT;
    }

    /**
     * The channel importance the shade ranked this notification at, or
     * {@link NotificationManager#IMPORTANCE_DEFAULT} when the ranking is unavailable -- an absent
     * ranking should not silently disable the feature.
     */
    static int importanceOf(StatusBarNotification sbn, @Nullable RankingMap rankingMap) {
        if (rankingMap == null) {
            return NotificationManager.IMPORTANCE_DEFAULT;
        }
        final Ranking ranking = new Ranking();
        if (!rankingMap.getRanking(sbn.getKey(), ranking)) {
            return NotificationManager.IMPORTANCE_DEFAULT;
        }
        return ranking.getImportance();
    }

    private int resolveColor(StatusBarNotification sbn) {
        switch (mColorMode) {
            case CUSTOM:
                return mCustomColor;
            case NOTIFICATION: {
                final Notification notification = sbn.getNotification();
                return EdgeLightColorMode.sanitize(notification == null ? 0 : notification.color);
            }
            case WALLPAPER:
                return EdgeLightColorMode.sanitize(wallpaperColor());
            case RAINBOW:
                return EdgeLightColorMode.FALLBACK_COLOR;  // the view shades per segment
            case ACCENT:
            default:
                return EdgeLightColorMode.sanitize(accentColor());
        }
    }

    private int accentColor() {
        final TypedValue value = new TypedValue();
        if (mContext.getTheme().resolveAttribute(
                android.R.attr.colorAccent, value, true)) {
            return value.data;
        }
        return 0;
    }

    private int wallpaperColor() {
        final WallpaperManager wallpaperManager = mContext.getSystemService(WallpaperManager.class);
        if (wallpaperManager == null) {
            return 0;
        }
        try {
            final WallpaperColors colors =
                    wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_LOCK);
            if (colors != null) {
                return colors.getPrimaryColor().toArgb();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read wallpaper colours", e);
        }
        return 0;
    }

    private void show(int color) {
        if (mWindowManager == null) {
            return;
        }
        if (mView == null) {
            final EdgeLightView view = new EdgeLightView(mContext);
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                    | WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                    | WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
            lp.setFitInsetsTypes(0);
            lp.setTitle("EdgeLight");
            try {
                mWindowManager.addView(view, lp);
            } catch (RuntimeException e) {
                Log.e(TAG, "failed to add the edge light overlay", e);
                return;
            }
            mView = view;
        }
        final EdgeLightView view = mView;
        view.setStrokeWidthPx(dpToPx(mStrokeWidthDp));
        view.setCornerRadiusPx(mRoundedStyle ? cornerRadiusPx() : 0f);
        view.setStyle(mEffect, mColorMode);
        view.setColor(color);
        startAnimation(view);
    }

    private void startAnimation(EdgeLightView view) {
        cancelAnimation();
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(PULSE_DURATION_MS);
        animator.setRepeatCount(mPulseCount - 1);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> view.setProgress((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                hide();
            }
        });
        mAnimator = animator;
        mPulsesShown++;
        animator.start();
    }

    private void cancelAnimation() {
        if (mAnimator != null) {
            mAnimator.removeAllListeners();
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    private void hide() {
        cancelAnimation();
        if (mView != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(mView);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to remove the edge light overlay", e);
            }
        }
        mView = null;
    }

    private float dpToPx(int dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                mContext.getResources().getDisplayMetrics());
    }

    private float cornerRadiusPx() {
        final Resources res = mContext.getResources();
        final int id = res.getIdentifier("rounded_corner_radius", "dimen", "android");
        if (id != 0) {
            final float radius = res.getDimension(id);
            if (radius > 0f) {
                return radius;
            }
        }
        return dpToPx((int) FALLBACK_CORNER_RADIUS_DP);
    }

    /**
     * Runs one pulse with the current settings and no notification behind it.
     *
     * <p>Exists because the edge light's normal trigger -- a notification arriving while the
     * screen dozes -- is awkward to produce on demand, which makes the feature hard to inspect.
     * Reachable with
     * {@code adb shell dumpsys activity service com.android.systemui/.SystemUIService
     * EdgeLightController pulse}.
     */
    private void testPulse(PrintWriter pw) {
        if (!mEnabled) {
            pw.println("  test pulse ignored: edge light is disabled");
            return;
        }
        pw.println("  test pulse: effect=" + mEffect.settingsValue()
                + " colorMode=" + mColorMode.settingsValue()
                + " pulses=" + mPulseCount);
        // dump() runs on a binder thread; the window and the animator are main-thread only.
        mHandler.post(() -> show(resolveColorWithoutNotification()));
    }

    /** The colour the current mode resolves to when no notification supplied one. */
    private int resolveColorWithoutNotification() {
        switch (mColorMode) {
            case CUSTOM:
                return mCustomColor;
            case WALLPAPER:
                return EdgeLightColorMode.sanitize(wallpaperColor());
            case RAINBOW:
                return EdgeLightColorMode.FALLBACK_COLOR;
            case NOTIFICATION:
                // No notification to take a colour from; fall back rather than draw nothing.
                return EdgeLightColorMode.FALLBACK_COLOR;
            case ACCENT:
            default:
                return EdgeLightColorMode.sanitize(accentColor());
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("EdgeLightController:");
        pw.println("  enabled=" + mEnabled);
        pw.println("  pulseCount=" + mPulseCount);
        pw.println("  strokeWidthDp=" + mStrokeWidthDp);
        pw.println("  colorMode=" + mColorMode.settingsValue());
        pw.println("  effect=" + mEffect.settingsValue());
        pw.println("  roundedStyle=" + mRoundedStyle);
        pw.println("  customColor=#" + Integer.toHexString(mCustomColor));
        pw.println("  dozing=" + mStatusBarStateController.isDozing());
        pw.println("  showing=" + (mView != null));
        pw.println("  pulsesShown=" + mPulsesShown);
        if (args != null) {
            for (String arg : args) {
                if ("pulse".equals(arg)) {
                    testPulse(pw);
                    break;
                }
            }
        }
    }
}
