/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.Flags.edtNotAllowedOnStatusBar;

import android.annotation.Nullable;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.flags.Flags;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.res.R;

import lineageos.providers.LineageSettings;
import com.android.systemui.statusbar.gesture.StatusBarLongPressGestureDetector;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherContainer;
import com.android.systemui.user.ui.binder.StatusBarUserChipViewBinder;
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel;
import com.android.systemui.util.leak.RotationUtils;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public class PhoneStatusBarView extends FrameLayout {
    private static final String TAG = "PhoneStatusBarView";

    private int mRotationOrientation = -1;
    @Nullable
    private View mCutoutSpace;
    @Nullable
    private DisplayCutout mDisplayCutout;
    @Nullable
    private Rect mDisplaySize;
    private int mStatusBarHeight;
    @Nullable
    private Gefingerpoken mTouchEventHandler;
    @Nullable
    private BooleanSupplier mIsStatusBarInteractiveSupplier;
    @Nullable
    private HasCornerCutoutFetcher mHasCornerCutoutFetcher;
    @Nullable
    private InsetsFetcher mInsetsFetcher;
    private int mDensity;
    private float mFontScale;
    private StatusBarLongPressGestureDetector mStatusBarLongPressGestureDetector;
    private final Region mTouchableRegion = Region.obtain();
    // Custom Options: double-tap anywhere on the status bar to sleep, when enabled.
    private final GestureDetector mDoubleTapGestureDetector;

    // Custom Options: user-configurable extra status bar padding (dp, -30..30).
    private static final String EXTRA_PADDING_START = "statusbar_extra_padding_start";
    private static final String EXTRA_PADDING_TOP = "statusbar_extra_padding_top";
    private static final String EXTRA_PADDING_END = "statusbar_extra_padding_end";
    private final ContentObserver mExtraPaddingObserver =
            new ContentObserver(new Handler()) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    updatePaddings();
                }
            };

    /**
     * Draw this many pixels into the left/right side of the cutout to optimally use the space
     */
    private int mCutoutSideNudge = 0;

    // Custom Options: swipe horizontally along the status bar to control screen brightness.
    // Gated on the "status_bar_brightness_control" System setting (crDroid).
    private static final String STATUS_BAR_BRIGHTNESS_CONTROL =
            "status_bar_brightness_control";
    // Gates whether the brightness-slide gesture is also allowed while the keyguard is up.
    private static final String STATUS_BAR_BRIGHTNESS_CONTROL_LOCKSCREEN =
            "status_bar_brightness_control_lockscreen";
    // Engage brightness control after this long-press timeout (ms) even without movement.
    private static final int BRIGHTNESS_CONTROL_LONG_PRESS_TIMEOUT = 750;
    // Or engage once a clearly-horizontal drag exceeds this many pixels.
    private static final int BRIGHTNESS_CONTROL_LINGER_THRESHOLD = 20;
    // Padding (as a fraction of screen width) on each edge so min/max are reachable.
    private static final float BRIGHTNESS_CONTROL_PADDING = 0.15f;

    private final Handler mBrightnessHandler = new Handler();
    // Whether the setting is enabled; sampled at ACTION_DOWN for the duration of the gesture.
    private boolean mBrightnessControlEnabled;
    // Whether we saw an ACTION_DOWN inside the bar this gesture (arming the control).
    private boolean mBrightnessTracking;
    // Whether we are actively sliding brightness (past the slop/timeout threshold).
    private boolean mBrightnessControlActive;
    // Whether we are consuming the current gesture for brightness (so the shade never sees it).
    private boolean mBrightnessConsuming;
    private int mBrightnessInitialTouchX;
    private int mBrightnessInitialTouchY;
    private int mBrightnessTouchSlop;
    private int mMinimumBacklight;
    private int mMaximumBacklight;
    @Nullable
    private DisplayManager mDisplayManager;
    @Nullable
    private KeyguardManager mKeyguardManager;

    // Custom Options: after the long-press timeout, latch into brightness-slide mode.
    private final Runnable mLongPressBrightnessChange = new Runnable() {
        @Override
        public void run() {
            mBrightnessControlActive = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            adjustBrightness(mBrightnessInitialTouchX);
        }
    };

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Custom Options: brightness-control bounds and slop.
        mBrightnessTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (powerManager != null) {
            mMinimumBacklight = powerManager.getMinimumScreenBrightnessSetting();
            mMaximumBacklight = powerManager.getMaximumScreenBrightnessSetting();
        } else {
            mMinimumBacklight = 0;
            mMaximumBacklight = PowerManager.BRIGHTNESS_ON;
        }
        // Custom Options: double-tap the status bar to sleep (LineageSettings-gated).
        mDoubleTapGestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (LineageSettings.System.getIntForUser(context.getContentResolver(),
                                LineageSettings.System.DOUBLE_TAP_SLEEP_GESTURE, 1,
                                UserHandle.USER_CURRENT) == 1) {
                            PowerManager pm = context.getSystemService(PowerManager.class);
                            if (pm != null) {
                                pm.goToSleep(SystemClock.uptimeMillis());
                            }
                            return true;
                        }
                        return false;
                    }
                });
    }

    void setLongPressGestureDetector(
            StatusBarLongPressGestureDetector statusBarLongPressGestureDetector) {
        mStatusBarLongPressGestureDetector = statusBarLongPressGestureDetector;
    }

    void setTouchEventHandler(Gefingerpoken handler) {
        mTouchEventHandler = handler;
    }

    void setIsStatusBarInteractiveSupplier(BooleanSupplier isStatusBarInteractiveSupplier) {
        mIsStatusBarInteractiveSupplier = isStatusBarInteractiveSupplier;
    }

    void setHasCornerCutoutFetcher(@NonNull HasCornerCutoutFetcher cornerCutoutFetcher) {
        mHasCornerCutoutFetcher = cornerCutoutFetcher;
        updateCutoutLocation();
    }

    void setInsetsFetcher(@NonNull InsetsFetcher insetsFetcher) {
        mInsetsFetcher = insetsFetcher;
        updateSafeInsets();
    }

    void init(StatusBarUserChipViewModel viewModel) {
        StatusBarUserSwitcherContainer container = findViewById(R.id.user_switcher_container);
        StatusBarUserChipViewBinder.bind(container, viewModel);
    }

    /** Updates the status bar's touchable region. */
    public void updateTouchableRegion(Region touchableRegion) {
        mTouchableRegion.set(touchableRegion);
        getViewRootImpl().setTouchableRegion(touchableRegion);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mCutoutSpace = findViewById(R.id.cutout_space_view);

        updateResources();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
        }
        if (edtNotAllowedOnStatusBar()) {
            // See b/482405546: the status bar sometimes uses a light theme, e.g. when the user's
            // wallpaper is light. The expanded dark theme feature inverts colors in light theme
            // view hierarchies so it may incorrectly invert the status bar in this case. To avoid
            // this we directly tell expanded dark theme to ignore this view hierarchy.
            getViewRootImpl().setForceInvertAllowed(false);
        }
        // Custom Options: observe the extra-padding settings for live updates.
        for (String key : new String[] {EXTRA_PADDING_START, EXTRA_PADDING_TOP, EXTRA_PADDING_END}) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, mExtraPaddingObserver,
                    UserHandle.USER_ALL);
        }
        updatePaddings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mDisplayCutout = null;
        mContext.getContentResolver().unregisterContentObserver(mExtraPaddingObserver);
        // Custom Options: stop any pending brightness-control callbacks.
        mBrightnessHandler.removeCallbacks(mLongPressBrightnessChange);
        mBrightnessTracking = false;
        mBrightnessControlActive = false;
    }

    // Custom Options: read the brightness-control toggle live (System namespace, crDroid).
    private boolean isBrightnessControlEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                STATUS_BAR_BRIGHTNESS_CONTROL, 0, UserHandle.USER_CURRENT) == 1;
    }

    // Custom Options: whether the brightness-slide gesture may currently arm. Requires the master
    // "status_bar_brightness_control" toggle; while the keyguard is showing it additionally
    // requires the "status_bar_brightness_control_lockscreen" sub-toggle (default off), so the
    // slider is off on the lockscreen unless the user opts in.
    private boolean isBrightnessControlAllowed() {
        if (!isBrightnessControlEnabled()) {
            return false;
        }
        if (isKeyguardShowing()) {
            return Settings.System.getIntForUser(mContext.getContentResolver(),
                    STATUS_BAR_BRIGHTNESS_CONTROL_LOCKSCREEN, 0, UserHandle.USER_CURRENT) == 1;
        }
        return true;
    }

    private boolean isKeyguardShowing() {
        if (mKeyguardManager == null) {
            mKeyguardManager = mContext.getSystemService(KeyguardManager.class);
        }
        return mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
    }

    /**
     * Custom Options: horizontal swipe along the status bar adjusts screen brightness.
     * Only clearly-horizontal drags engage; vertical drags (shade pulls) abandon the gesture.
     * This never consumes the event, so shade handling continues normally.
     */
    private boolean brightnessControl(MotionEvent event) {
        final int action = event.getActionMasked();
        final int x = (int) event.getRawX();
        final int y = (int) event.getRawY();
        if (action == MotionEvent.ACTION_DOWN) {
            mBrightnessControlEnabled = isBrightnessControlAllowed();
            mBrightnessControlActive = false;
            // Only arm when the touch starts within the status bar strip.
            if (mBrightnessControlEnabled && y < getBrightnessRegionBottom()) {
                mBrightnessTracking = true;
                mBrightnessInitialTouchX = x;
                mBrightnessInitialTouchY = y;
                mBrightnessHandler.removeCallbacks(mLongPressBrightnessChange);
                mBrightnessHandler.postDelayed(mLongPressBrightnessChange,
                        BRIGHTNESS_CONTROL_LONG_PRESS_TIMEOUT);
            } else {
                mBrightnessTracking = false;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!mBrightnessTracking) {
                return false;
            }
            final int deltaX = Math.abs(x - mBrightnessInitialTouchX);
            final int deltaY = Math.abs(y - mBrightnessInitialTouchY);
            if (mBrightnessControlActive) {
                adjustBrightness(x);
            } else if (deltaY > mBrightnessTouchSlop && deltaY > deltaX) {
                // Predominantly vertical drag: this is a shade pull, not a brightness slide.
                mBrightnessTracking = false;
                mBrightnessHandler.removeCallbacks(mLongPressBrightnessChange);
            } else if (deltaX > BRIGHTNESS_CONTROL_LINGER_THRESHOLD && deltaX >= deltaY) {
                // Clearly horizontal: engage brightness control.
                mBrightnessControlActive = true;
                mBrightnessHandler.removeCallbacks(mLongPressBrightnessChange);
                adjustBrightness(x);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mBrightnessTracking = false;
            mBrightnessControlActive = false;
            mBrightnessHandler.removeCallbacks(mLongPressBrightnessChange);
            commitBrightness();
        }
        return mBrightnessControlActive;
    }

    // IchthysOS: the value the slide last applied as a temporary brightness, persisted once when
    // the finger lifts instead of a settings write on every move.
    private boolean mBrightnessPending;
    private boolean mPendingAutomatic;
    private int mPendingBrightness;
    private float mPendingAutoAdj;

    private void commitBrightness() {
        if (!mBrightnessPending) {
            return;
        }
        mBrightnessPending = false;
        final ContentResolver cr = mContext.getContentResolver();
        if (mPendingAutomatic) {
            Settings.System.putFloatForUser(cr,
                    Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, mPendingAutoAdj,
                    UserHandle.USER_CURRENT);
        } else {
            Settings.System.putIntForUser(cr,
                    Settings.System.SCREEN_BRIGHTNESS, mPendingBrightness,
                    UserHandle.USER_CURRENT);
        }
    }

    // Custom Options: bottom edge (screen-space) of the region that arms brightness control.
    private int getBrightnessRegionBottom() {
        return mStatusBarHeight > 0 ? mStatusBarHeight : SystemBarUtils.getStatusBarHeight(mContext);
    }

    // Custom Options: map an x screen coordinate to a brightness value and apply it.
    private void adjustBrightness(int x) {
        if (mDisplayManager == null) {
            mDisplayManager = mContext.getSystemService(DisplayManager.class);
        }
        if (mDisplayManager == null) {
            return;
        }
        final float width = getResources().getDisplayMetrics().widthPixels;
        if (width <= 0f) {
            return;
        }
        float raw = ((float) x) / width;
        // Clamp into the padded range then normalize back to 0..1 so the extremes are
        // reachable before the very edge of the screen.
        float padded = Math.min(1.0f - BRIGHTNESS_CONTROL_PADDING,
                Math.max(BRIGHTNESS_CONTROL_PADDING, raw));
        float value = (padded - BRIGHTNESS_CONTROL_PADDING)
                / (1.0f - (2.0f * BRIGHTNESS_CONTROL_PADDING));

        final ContentResolver cr = mContext.getContentResolver();
        final int displayId =
                getDisplay() != null ? getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
        final boolean automatic = Settings.System.getIntForUser(cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        if (automatic) {
            // In auto-brightness mode, nudge the adjustment slider (-1..1) instead.
            float adj = Math.max(-1.0f, Math.min(1.0f, (2.0f * value) - 1.0f));
            mDisplayManager.setTemporaryAutoBrightnessAdjustment(adj);
            mPendingAutomatic = true;
            mPendingAutoAdj = adj;
            mBrightnessPending = true;
        } else {
            int newBrightness = mMinimumBacklight
                    + Math.round(value * (mMaximumBacklight - mMinimumBacklight));
            newBrightness = Math.max(mMinimumBacklight,
                    Math.min(mMaximumBacklight, newBrightness));
            // Apply immediately for live feedback; commitBrightness() persists it on lift.
            mDisplayManager.setTemporaryBrightness(displayId,
                    BrightnessSynchronizer.brightnessIntToFloat(newBrightness));
            mPendingAutomatic = false;
            mPendingBrightness = newBrightness;
            mBrightnessPending = true;
        }
    }

    // Custom Options: extra status bar padding value (dp -> px) for the given setting key.
    private int getExtraPaddingPx(String key) {
        int dp = Settings.System.getIntForUser(
                mContext.getContentResolver(), key, 0, UserHandle.USER_CURRENT);
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // Per b/300629388, we let the PhoneStatusBarView detect onConfigurationChanged to
    // updateResources, instead of letting the PhoneStatusBarViewController detect onConfigChanged
    // then notify PhoneStatusBarView.
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // May trigger cutout space layout-ing
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    /**
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateDisplayParameters() {
        boolean changed = false;
        int newRotation = RotationUtils.getExactRotation(mContext);
        if (newRotation != mRotationOrientation) {
            changed = true;
            mRotationOrientation = newRotation;
        }

        if (!Objects.equals(getRootWindowInsets().getDisplayCutout(), mDisplayCutout)) {
            changed = true;
            mDisplayCutout = getRootWindowInsets().getDisplayCutout();
        }

        Configuration newConfiguration = mContext.getResources().getConfiguration();
        final Rect newSize = newConfiguration.windowConfiguration.getMaxBounds();
        if (!Objects.equals(newSize, mDisplaySize)) {
            changed = true;
            mDisplaySize = newSize;
        }

        int density = newConfiguration.densityDpi;
        if (density != mDensity) {
            changed = true;
            mDensity = density;
        }
        float fontScale = newConfiguration.fontScale;
        if (fontScale != mFontScale) {
            changed = true;
            mFontScale = fontScale;
        }
        return changed;
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        if (mIsStatusBarInteractiveSupplier != null
                && !mIsStatusBarInteractiveSupplier.getAsBoolean()) {
            // Consume the event to prevent any calls to #onHoverEvent on status bar view or its
            // components, essentially making the status bar and its children completely
            // non-interactive.
            return true;
        }
        return super.dispatchHoverEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mIsStatusBarInteractiveSupplier != null
                && !mIsStatusBarInteractiveSupplier.getAsBoolean()) {
            // Consume the event to prevent any calls to #onTouchEvent on status bar view or its
            // components, essentially making the status bar and its children completely
            // non-interactive.
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Touch events outside of the touchable regions are still received by this view. Touch
        // events started within the view should not be handled to allow app handle views behind
        // the status bar to handle the event. ACTION_MOVE and ACTION_UP events outside the
        // touchable region should still be handled so that an open notification shade can be
        // correctly updated and closed.
        if (event.getAction() == MotionEvent.ACTION_DOWN
                && !mTouchableRegion.contains((int) event.getX(), (int) event.getY())) {
            return false;
        }

        // Custom Options: observe for a double-tap-to-sleep gesture without consuming the
        // event, so normal shade-expand touch handling is unaffected.
        mDoubleTapGestureDetector.onTouchEvent(event);

        // Custom Options: feed the brightness-control detector. Once a horizontal slide
        // actively engages brightness, take the gesture over: cancel any shade pull that
        // began during disambiguation and consume all further events so the same drag can't
        // also open Quick Settings / the shade.
        final int bAction = event.getActionMasked();
        if (bAction == MotionEvent.ACTION_DOWN) {
            mBrightnessConsuming = false;
        }
        final boolean brightnessActive = brightnessControl(event);
        if (brightnessActive && !mBrightnessConsuming) {
            mBrightnessConsuming = true;
            if (mTouchEventHandler != null) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                mTouchEventHandler.onTouchEvent(cancel);
                cancel.recycle();
            }
        }
        if (mBrightnessConsuming) {
            if (bAction == MotionEvent.ACTION_UP || bAction == MotionEvent.ACTION_CANCEL) {
                mBrightnessConsuming = false;
            }
            return true; // brightness owns this gesture; don't let the shade see it
        }

        if (mStatusBarLongPressGestureDetector != null && !Flags.scrollToTop()) {
            mStatusBarLongPressGestureDetector.handleTouch(event);
        }
        if (mTouchEventHandler == null) {
            if (Flags.scrollToTop()) {
                return false;
            }
            Log.w(
                    TAG,
                    String.format(
                            "onTouch: No touch handler provided; eating gesture at (%d,%d)",
                            (int) event.getX(),
                            (int) event.getY()
                    )
            );
            return true;
        }
        return mTouchEventHandler.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mTouchEventHandler != null
                ? mTouchEventHandler.onInterceptTouchEvent(event)
                : super.onInterceptTouchEvent(event);
    }

    public void updateResources() {
        mCutoutSideNudge = getResources().getDimensionPixelSize(
                R.dimen.display_cutout_margin_consumption);

        updateStatusBarHeight();
    }

    private void updateStatusBarHeight() {
        final int waterfallTopInset =
                mDisplayCutout == null ? 0 : mDisplayCutout.getWaterfallInsets().top;
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        layoutParams.height = mStatusBarHeight - waterfallTopInset;
        updateSystemIconsContainerHeight();
        updatePaddings();
        setLayoutParams(layoutParams);
    }

    private void updateSystemIconsContainerHeight() {
        View systemIconsContainer = findViewById(R.id.system_icons);
        ViewGroup.LayoutParams layoutParams = systemIconsContainer.getLayoutParams();
        int newSystemIconsHeight =
                getResources().getDimensionPixelSize(R.dimen.status_bar_icon_container_height);
        if (layoutParams.height != newSystemIconsHeight) {
            layoutParams.height = newSystemIconsHeight;
            systemIconsContainer.setLayoutParams(layoutParams);
        }
    }

    private void updatePaddings() {
        // Custom Options: add user-configurable extra padding on top of the base values.
        int statusBarPaddingStart = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_start) + getExtraPaddingPx(EXTRA_PADDING_START);

        findViewById(R.id.status_bar_contents).setPaddingRelative(
                statusBarPaddingStart,
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top)
                        + getExtraPaddingPx(EXTRA_PADDING_TOP),
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end)
                        + getExtraPaddingPx(EXTRA_PADDING_END),
                0);

        findViewById(R.id.notification_lights_out)
                .setPaddingRelative(0, statusBarPaddingStart, 0, 0);

        findViewById(R.id.system_icons).setPaddingRelative(
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_start),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_top),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_end),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_bottom)
        );
    }

    private void updateLayoutForCutout() {
        updateStatusBarHeight();
        updateCutoutLocation();
        updateSafeInsets();
    }

    private void updateCutoutLocation() {
        // Not all layouts have a cutout (e.g., Car)
        if (mCutoutSpace == null) {
            return;
        }

        boolean hasCornerCutout;
        if (mHasCornerCutoutFetcher != null) {
            hasCornerCutout = mHasCornerCutoutFetcher.fetchHasCornerCutout();
        } else {
            Log.e(TAG, "mHasCornerCutoutFetcher unexpectedly null");
            hasCornerCutout = true;
        }

        if (mDisplayCutout == null || mDisplayCutout.isEmpty() || hasCornerCutout) {
            mCutoutSpace.setVisibility(View.GONE);
            return;
        }

        mCutoutSpace.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mCutoutSpace.getLayoutParams();

        Rect bounds = mDisplayCutout.getBoundingRectTop();

        bounds.left = bounds.left + mCutoutSideNudge;
        bounds.right = bounds.right - mCutoutSideNudge;
        lp.width = bounds.width();
        lp.height = bounds.height();
    }

    private void updateSafeInsets() {
        if (mInsetsFetcher == null) {
            Log.e(TAG, "mInsetsFetcher unexpectedly null");
            return;
        }

        Insets insets = mInsetsFetcher.fetchInsets();
        setPadding(
                insets.left,
                insets.top,
                insets.right,
                getPaddingBottom());
    }

    interface HasCornerCutoutFetcher {
        boolean fetchHasCornerCutout();
    }

    interface InsetsFetcher {
        Insets fetchInsets();
    }
}
