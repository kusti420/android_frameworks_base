/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.icu.lang.UCharacter;
import android.icu.text.DateTimePatternGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Digital clock for the status bar.
 */
public class Clock extends TextView implements
        DemoModeCommandReceiver,
        Tunable,
        DarkReceiver {

    public static final String CLOCK_SECONDS = "clock_seconds";
    // Custom Options: crDroid status bar clock settings (TunerService prefix keys).
    private static final String STATUS_BAR_CLOCK_SECONDS =
            "system:status_bar_clock_seconds";
    private static final String STATUS_BAR_AM_PM =
            "lineagesystem:status_bar_am_pm";
    private static final String STATUS_BAR_CLOCK_DATE_DISPLAY =
            "system:status_bar_clock_date_display";
    private static final String STATUS_BAR_CLOCK_DATE_STYLE =
            "system:status_bar_clock_date_style";
    private static final String STATUS_BAR_CLOCK_DATE_POSITION =
            "system:status_bar_clock_date_position";
    private static final String STATUS_BAR_CLOCK_DATE_FORMAT =
            "system:status_bar_clock_date_format";
    private static final String STATUS_BAR_CLOCK_PERIOD_SEPARATOR =
            "system:status_bar_clock_period_separator";
    private static final String STATUS_BAR_CLOCK_BOLD =
            "system:status_bar_clock_bold";
    // IchthysOS "Shoal": in the zig-zag status bar layout the clock uses the theme's monospaced
    // numeral face (theme.font.mono), falling back to the platform monospace family.
    // Plain tuner keys are Settings.Secure.
    private static final String ICHTHYS_STATUSBAR_LAYOUT = "ichthys_statusbar_layout";
    private static final String ICHTHYS_FONT_MONO = "ichthys_theme_font_mono";
    // Custom Options: crDroid clock auto-hide (LineageSettings.System boolean).
    private static final String STATUS_BAR_CLOCK_AUTO_HIDE =
            "lineagesystem:status_bar_clock_auto_hide";
    // Custom Options: status bar clock position (LineageSettings.System.STATUS_BAR_CLOCK).
    private static final String STATUS_BAR_CLOCK_POSITION =
            "lineagesystem:status_bar_clock";
    // Custom Options: clock background chip (Settings.System.statusbar_clock_chip). 0 = off,
    // 1..12 map to R.drawable.sb_date_bg1..12. Only applied to the real status bar clock.
    private static final String STATUS_BAR_CLOCK_CHIP =
            "system:statusbar_clock_chip";
    private static final int[] CHIP_BACKGROUNDS = {
            0, // 0 = disabled
            R.drawable.sb_date_bg1, R.drawable.sb_date_bg2, R.drawable.sb_date_bg3,
            R.drawable.sb_date_bg4, R.drawable.sb_date_bg5, R.drawable.sb_date_bg6,
            R.drawable.sb_date_bg7, R.drawable.sb_date_bg8, R.drawable.sb_date_bg9,
            R.drawable.sb_date_bg10, R.drawable.sb_date_bg11, R.drawable.sb_date_bg12,
    };
    private static final String CLOCK_SUPER_PARCELABLE = "clock_super_parcelable";
    private static final String CURRENT_USER_ID = "current_user_id";
    private static final String SHOW_SECONDS = "show_seconds";
    private static final String VISIBILITY = "visibility";

    private final UserTracker mUserTracker;
    private int mCurrentUserId;

    private boolean mAttached;
    private boolean mScreenReceiverRegistered;
    private Calendar mCalendar;
    private String mContentDescriptionFormatString;
    private SimpleDateFormat mClockFormat;
    private SimpleDateFormat mContentDescriptionFormat;
    private Locale mLocale;
    private DateTimePatternGenerator mDateTimePatternGenerator;

    private static final int AM_PM_STYLE_NORMAL  = 0;
    private static final int AM_PM_STYLE_SMALL   = 1;
    private static final int AM_PM_STYLE_GONE    = 2;

    // Custom Options: date display modes / styles / position.
    private static final int CLOCK_DATE_DISPLAY_GONE = 0;
    private static final int CLOCK_DATE_DISPLAY_SMALL = 1;
    private static final int CLOCK_DATE_DISPLAY_NORMAL = 2;

    private static final int CLOCK_DATE_STYLE_REGULAR = 0;
    private static final int CLOCK_DATE_STYLE_LOWERCASE = 1;
    private static final int CLOCK_DATE_STYLE_UPPERCASE = 2;

    private static final int STYLE_DATE_LEFT = 0;
    private static final int STYLE_DATE_RIGHT = 1;

    // Custom Options: status bar clock position slots. Values match
    // LineageSettings.System.STATUS_BAR_CLOCK (0 = right, 1 = center, 2 = left).
    public static final int CLOCK_POSITION_RIGHT = 0;
    public static final int CLOCK_POSITION_CENTER = 1;
    public static final int CLOCK_POSITION_LEFT = 2;

    private int mAmPmStyle;
    private boolean mShowSeconds;
    private Handler mSecondsHandler;

    // Custom Options: crDroid clock customization state.
    private final boolean mIsStatusBar;
    private int mClockDateDisplay = CLOCK_DATE_DISPLAY_GONE;
    private int mClockDateStyle = CLOCK_DATE_STYLE_REGULAR;
    private int mClockDatePosition = STYLE_DATE_LEFT;
    private String mClockDateFormat = null;
    private boolean mPeriodHourMinuteSeparator;
    private boolean mBoldStatusBarClock;

    // Custom Options: clock auto-hide state.
    // Hide the clock this long (ms) after it was last shown by a relevant event.
    private static final long CLOCK_AUTO_HIDE_DELAY_MS = 5000;
    private boolean mClockAutoHide;
    // True while the clock is currently suppressed by the auto-hide timeout.
    private boolean mClockAutoHidden;
    // The visibility last requested by external (policy) callers, so auto-hide never
    // overrides a policy-driven hide; defaults to VISIBLE until told otherwise.
    private int mRequestedVisibility = VISIBLE;
    private final Handler mAutoHideHandler = new Handler();
    private final Runnable mClockAutoHideRunnable = this::hideClock;

    // Custom Options: status bar clock position (left/center/right).
    // The position slot this clock instance occupies. The inflated start-side clock
    // (R.id.clock) is LEFT by default; the center/right clocks declare their slot via
    // setClockSlot().
    private int mClockSlot = CLOCK_POSITION_LEFT;
    // The user-selected active position (lineagesystem:status_bar_clock, default left).
    private int mActiveClockPosition = CLOCK_POSITION_LEFT;
    // Sibling clocks that mirror this clock's policy-driven (DISABLE_CLOCK) visibility.
    // Only the start-side clock is driven by the view binder, so it forwards that policy
    // to the center/right clocks; each peer still self-gates by its own slot.
    private final ArrayList<Clock> mClockPositionPeers = new ArrayList<>();

    // Tracks config changes that will make the clock change dimensions
    private final InterestingConfigChanges mInterestingConfigChanges;

    private boolean mShouldApplyPadding = true;

    /**
     * Color to be set on this {@link TextView}, when wallpaperTextColor is <b>not</b> utilized.
     */
    private int mNonAdaptedColor;

    private final BroadcastDispatcher mBroadcastDispatcher;

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    mCurrentUserId = newUser;
                    updateClock();
                }
            };

    public Clock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Clock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.Clock,
                0, 0);
        try {
            mAmPmStyle = a.getInt(R.styleable.Clock_amPmStyle, AM_PM_STYLE_GONE);
            mIsStatusBar = a.getBoolean(R.styleable.Clock_isStatusBar, false);
            mNonAdaptedColor = getCurrentTextColor();
        } finally {
            a.recycle();
        }
        mBroadcastDispatcher = Dependency.get(BroadcastDispatcher.class);
        mUserTracker = Dependency.get(UserTracker.class);
        mInterestingConfigChanges = new InterestingConfigChanges(
                ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_DENSITY);

        setIncludeFontPadding(false);
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(CLOCK_SUPER_PARCELABLE, super.onSaveInstanceState());
        bundle.putInt(CURRENT_USER_ID, mCurrentUserId);
        bundle.putBoolean(SHOW_SECONDS, mShowSeconds);
        bundle.putInt(VISIBILITY, getVisibility());

        return bundle;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof Bundle bundle)) {
            super.onRestoreInstanceState(state);
            return;
        }

        Parcelable superState = bundle.getParcelable(CLOCK_SUPER_PARCELABLE);
        super.onRestoreInstanceState(superState);
        if (bundle.containsKey(CURRENT_USER_ID)) {
            mCurrentUserId = bundle.getInt(CURRENT_USER_ID);
        }
        mShowSeconds = bundle.getBoolean(SHOW_SECONDS, false);
        if (bundle.containsKey(VISIBILITY)) {
            super.setVisibility(bundle.getInt(VISIBILITY));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);

            // NOTE: This receiver could run before this method returns, as it's not dispatching
            // on the main thread and BroadcastDispatcher may not need to register with Context.
            // The receiver will return immediately if the view does not have a Handler yet.
            mBroadcastDispatcher.registerReceiverWithHandler(mIntentReceiver, filter,
                    Dependency.get(Dependency.TIME_TICK_HANDLER), UserHandle.ALL);
            Dependency.get(TunerService.class).addTunable(this,
                    StatusBarIconController.ICON_HIDE_LIST,
                    STATUS_BAR_CLOCK_SECONDS,
                    STATUS_BAR_AM_PM,
                    STATUS_BAR_CLOCK_DATE_DISPLAY,
                    STATUS_BAR_CLOCK_DATE_STYLE,
                    STATUS_BAR_CLOCK_DATE_POSITION,
                    STATUS_BAR_CLOCK_DATE_FORMAT,
                    STATUS_BAR_CLOCK_PERIOD_SEPARATOR,
                    STATUS_BAR_CLOCK_BOLD,
                    ICHTHYS_STATUSBAR_LAYOUT,
                    ICHTHYS_FONT_MONO,
                    STATUS_BAR_CLOCK_AUTO_HIDE,
                    STATUS_BAR_CLOCK_POSITION,
                    STATUS_BAR_CLOCK_CHIP);
            mUserTracker.addCallback(mUserChangedCallback, mContext.getMainExecutor());
            mCurrentUserId = mUserTracker.getUserId();
        }

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = Calendar.getInstance(TimeZone.getDefault());
        mContentDescriptionFormatString = "";
        mDateTimePatternGenerator = null;

        // Make sure we update to the current time
        updateClock();
        updateShowSeconds();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mScreenReceiverRegistered) {
            mScreenReceiverRegistered = false;
            mBroadcastDispatcher.unregisterReceiver(mScreenReceiver);
            if (mSecondsHandler != null) {
                mSecondsHandler.removeCallbacks(mSecondTick);
                mSecondsHandler = null;
            }
        }
        if (mAttached) {
            mBroadcastDispatcher.unregisterReceiver(mIntentReceiver);
            mAttached = false;
            Dependency.get(TunerService.class).removeTunable(this);
            mUserTracker.removeCallback(mUserChangedCallback);
        }
        // Custom Options: cancel any pending auto-hide.
        mAutoHideHandler.removeCallbacks(mClockAutoHideRunnable);
    }

    // Custom Options: capture externally-requested (policy) visibility so auto-hide and
    // clock-position gating can layer on top of it without ever overriding a policy-driven hide.
    @Override
    public void setVisibility(int visibility) {
        mRequestedVisibility = visibility;
        // Mirror the policy-driven visibility to sibling clocks so the center/right clocks
        // follow the same DISABLE_CLOCK policy the binder applies to the start-side clock.
        for (int i = 0; i < mClockPositionPeers.size(); i++) {
            mClockPositionPeers.get(i).onPolicyVisibilityChanged(visibility);
        }
        applyClockPositionVisibility();
    }

    // Custom Options: declares which position slot (CLOCK_POSITION_*) this clock occupies.
    public void setClockSlot(int slot) {
        mClockSlot = slot;
        applyClockPositionVisibility();
    }

    // Custom Options: registers a sibling clock that mirrors this clock's policy-driven
    // (DISABLE_CLOCK) visibility. The peer still only shows if it is the active-position clock.
    public void addClockPositionPeer(Clock peer) {
        if (peer != null && peer != this && !mClockPositionPeers.contains(peer)) {
            mClockPositionPeers.add(peer);
        }
    }

    // Custom Options: applies a policy visibility forwarded from the start-side clock, without
    // re-forwarding (avoids peer recursion).
    private void onPolicyVisibilityChanged(int visibility) {
        mRequestedVisibility = visibility;
        applyClockPositionVisibility();
    }

    // Custom Options: resolve the effective visibility from the position-slot gate, the last
    // policy request, and the auto-hide state. A clock that is not the active position never
    // takes part in layout.
    private void applyClockPositionVisibility() {
        if (mClockSlot != mActiveClockPosition) {
            super.setVisibility(GONE);
            return;
        }
        if (mClockAutoHide && mClockAutoHidden && mRequestedVisibility == VISIBLE) {
            // Policy wants us shown, but we are in the auto-hidden window: stay hidden.
            super.setVisibility(GONE);
            return;
        }
        super.setVisibility(mRequestedVisibility);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // Custom Options: reveal the clock (and restart the timer) whenever the status bar
        // window becomes visible again (e.g. screen on / shade shown) - a relevant event.
        if (mClockAutoHide && visibility == VISIBLE) {
            showClock();
        }
    }

    // Custom Options: entry point to (re)apply the auto-hide state after the setting changes.
    private void updateAutoHide() {
        if (mClockAutoHide) {
            // Show now, then arm the hide timer.
            showClock();
        } else {
            mAutoHideHandler.removeCallbacks(mClockAutoHideRunnable);
            mClockAutoHidden = false;
            // Restore whatever visibility policy last requested (respecting the slot gate).
            applyClockPositionVisibility();
        }
    }

    // Custom Options: reveal the clock (respecting policy) and schedule the next auto-hide.
    public void showClock() {
        if (!mClockAutoHide) {
            return;
        }
        mClockAutoHidden = false;
        applyClockPositionVisibility();
        mAutoHideHandler.removeCallbacks(mClockAutoHideRunnable);
        mAutoHideHandler.postDelayed(mClockAutoHideRunnable, CLOCK_AUTO_HIDE_DELAY_MS);
    }

    // Custom Options: suppress the clock via auto-hide, but only if policy currently allows it.
    private void hideClock() {
        if (!mClockAutoHide) {
            return;
        }
        mClockAutoHidden = true;
        applyClockPositionVisibility();
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // If the handler is null, it means we received a broadcast while the view has not
            // finished being attached or in the process of being detached.
            // In that case, do not post anything.
            Handler handler = getHandler();
            if (handler == null) return;

            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra(Intent.EXTRA_TIMEZONE);
                handler.post(() -> {
                    mCalendar = Calendar.getInstance(TimeZone.getTimeZone(tz));
                    if (mClockFormat != null) {
                        mClockFormat.setTimeZone(mCalendar.getTimeZone());
                    }
                    if (mContentDescriptionFormat != null) {
                        mContentDescriptionFormat.setTimeZone(mCalendar.getTimeZone());
                    }
                });
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                final Locale newLocale = getResources().getConfiguration().locale;
                handler.post(() -> {
                    if (!newLocale.equals(mLocale)) {
                        mLocale = newLocale;
                        // Force refresh of dependent variables.
                        mContentDescriptionFormatString = "";
                        mDateTimePatternGenerator = null;
                    }
                });
            }
            handler.post(() -> updateClock());
        }
    };

    final void updateClock() {
        if (mDemoMode) return;
        // Custom Options: onTuningChanged can fire during addTunable() (from
        // onAttachedToWindow) before mCalendar is initialized — guard against it.
        if (mCalendar == null) return;
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        CharSequence smallTime = getSmallTime();
        // Setting text actually triggers a layout pass (because the text view is set to
        // wrap_content width and TextView always relayouts for this). Avoid needless
        // relayout if the text didn't actually change.
        if (!TextUtils.equals(smallTime, getText())) {
            setText(smallTime);
        }
        setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case STATUS_BAR_CLOCK_SECONDS:
                // Custom Options: seconds default ON (preserves the ROM default) unless
                // the user turns it off.
                mShowSeconds = TunerService.parseIntegerSwitch(newValue, true);
                updateShowSeconds();
                break;
            case STATUS_BAR_AM_PM:
                mAmPmStyle = TunerService.parseInteger(newValue, AM_PM_STYLE_GONE);
                break;
            case STATUS_BAR_CLOCK_DATE_DISPLAY:
                mClockDateDisplay =
                        TunerService.parseInteger(newValue, CLOCK_DATE_DISPLAY_GONE);
                break;
            case STATUS_BAR_CLOCK_DATE_STYLE:
                mClockDateStyle =
                        TunerService.parseInteger(newValue, CLOCK_DATE_STYLE_REGULAR);
                break;
            case STATUS_BAR_CLOCK_DATE_POSITION:
                mClockDatePosition =
                        TunerService.parseInteger(newValue, STYLE_DATE_LEFT);
                break;
            case STATUS_BAR_CLOCK_DATE_FORMAT:
                mClockDateFormat = newValue;
                break;
            case STATUS_BAR_CLOCK_PERIOD_SEPARATOR:
                mPeriodHourMinuteSeparator =
                        TunerService.parseIntegerSwitch(newValue, false);
                break;
            case STATUS_BAR_CLOCK_BOLD:
                mBoldStatusBarClock =
                        TunerService.parseIntegerSwitch(newValue, false);
                applyBoldClockStyle();
                break;
            case ICHTHYS_STATUSBAR_LAYOUT:
                // Unset means the default, zigzag (see IchthysZigzag.isEnabled).
                mShoalLayout = !"single".equals(newValue);
                applyShoalClockTypeface();
                break;
            case ICHTHYS_FONT_MONO:
                mShoalMonoFamily = newValue;
                applyShoalClockTypeface();
                break;
            case STATUS_BAR_CLOCK_AUTO_HIDE:
                mClockAutoHide = TunerService.parseIntegerSwitch(newValue, false);
                updateAutoHide();
                return;
            case STATUS_BAR_CLOCK_POSITION:
                // Custom Options: default left (2). This fires during addTunable() before
                // mCalendar is set, but only touches visibility so no NPE guard is needed.
                mActiveClockPosition =
                        TunerService.parseInteger(newValue, CLOCK_POSITION_LEFT);
                applyClockPositionVisibility();
                return;
            case STATUS_BAR_CLOCK_CHIP:
                applyClockChip(TunerService.parseInteger(newValue, 0));
                return;
            default:
                return;
        }
        // Force refresh of dependent variables.
        mContentDescriptionFormatString = "";
        mDateTimePatternGenerator = null;
        updateClock();
    }

    // Custom Options: clock background chip. Sets one of the sb_date_bg* drawables as the clock's
    // background, with extra horizontal padding so the text sits inside the chip. Only the real
    // status bar clock gets a chip; the reused QS-header clock is left untouched.
    private boolean mChipPaddingCaptured;
    private int mBasePadStart, mBasePadTop, mBasePadEnd, mBasePadBottom;

    private void applyClockChip(int style) {
        if (!mIsStatusBar) {
            return;
        }
        if (!mChipPaddingCaptured) {
            mBasePadStart = getPaddingStart();
            mBasePadTop = getPaddingTop();
            mBasePadEnd = getPaddingEnd();
            mBasePadBottom = getPaddingBottom();
            mChipPaddingCaptured = true;
        }
        if (style <= 0 || style >= CHIP_BACKGROUNDS.length || CHIP_BACKGROUNDS[style] == 0) {
            setBackground(null);
            setPaddingRelative(mBasePadStart, mBasePadTop, mBasePadEnd, mBasePadBottom);
            return;
        }
        setBackgroundResource(CHIP_BACKGROUNDS[style]);
        final int h = (int) (8 * getResources().getDisplayMetrics().density);
        setPaddingRelative(mBasePadStart + h, mBasePadTop, mBasePadEnd + h, mBasePadBottom);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mNonAdaptedColor = DarkIconDispatcher.getTint(areas, this, tint);
        setTextColor(mNonAdaptedColor);
    }

    // Update text color based when shade scrim changes color.
    public void onColorsChanged(boolean lightTheme) {
        final Context context = new ContextThemeWrapper(mContext,
                lightTheme ? R.style.Theme_SystemUI_LightWallpaper : R.style.Theme_SystemUI);
        setTextColor(Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColor));
    }

    public void onDensityOrFontScaleChanged() {
        // Note that this class is not being registered as configuration listener when used
        // from compose. It will instead receive a normal "View#onConfigurationChanged".
        reloadDimens();
    }

    /**
     * Sets whether the clock should automatically apply padding from resources when configuration
     * changes. True by default.
     */
    public void setShouldApplyPadding(boolean shouldApply) {
        mShouldApplyPadding = shouldApply;
    }

    private void reloadDimens() {
        FontSizeUtils.updateFontSize(this, R.dimen.status_bar_clock_size);

        if (mShouldApplyPadding) {
            // Note: The padding for the clock in the shade is controlled by ShadeHeaderController
            // so this just affects the status bar clock.
            setPaddingRelative(
                    mContext.getResources().getDimensionPixelSize(
                            R.dimen.status_bar_clock_starting_padding),
                    0,
                    mContext.getResources().getDimensionPixelSize(
                            R.dimen.status_bar_clock_end_padding),
                    0);
        }
    }


    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final boolean shouldReloadDimensions =
                mInterestingConfigChanges.applyNewConfig(newConfig);
        if (shouldReloadDimensions) {
            reloadDimens();
        }
    }

    private void updateShowSeconds() {
        if (mShowSeconds) {
            // Wait until we have a display to start trying to show seconds.
            if (mSecondsHandler == null && getDisplay() != null) {
                mSecondsHandler = new Handler();
                if (getDisplay().getState() == Display.STATE_ON) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
                mScreenReceiverRegistered = true;
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                mBroadcastDispatcher.registerReceiver(mScreenReceiver, filter);
            }
            setFontFeatureSettings("tnum");
        } else {
            if (mSecondsHandler != null) {
                mScreenReceiverRegistered = false;
                mBroadcastDispatcher.unregisterReceiver(mScreenReceiver);
                mSecondsHandler.removeCallbacks(mSecondTick);
                mSecondsHandler = null;
                updateClock();
            }
            setFontFeatureSettings(null);
        }
    }

    private CharSequence getSmallTime() {
        Context context = getContext();
        boolean is24 = DateFormat.is24HourFormat(context, mCurrentUserId);
        if (mDateTimePatternGenerator == null) {
            // Despite its name, getInstance creates a cloned instance, so reuse the generator to
            // avoid unnecessary churn.
            mDateTimePatternGenerator = DateTimePatternGenerator.getInstance(
                    context.getResources().getConfiguration().locale);
        }

        final char MAGIC1 = '\uEF00';
        final char MAGIC2 = '\uEF01';

        final String formatSkeleton = mShowSeconds
                ? is24 ? "Hms" : "hms"
                : is24 ? "Hm" : "hm";
        String format = mDateTimePatternGenerator.getBestPattern(formatSkeleton);
        if (!format.equals(mContentDescriptionFormatString)) {
            mContentDescriptionFormatString = format;
            mContentDescriptionFormat = new SimpleDateFormat(format);
            /*
             * Search for an unquoted "a" in the format string, so we can
             * add marker characters around it to let us find it again after
             * formatting and change its size.
             */
            if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
                int a = -1;
                boolean quoted = false;
                for (int i = 0; i < format.length(); i++) {
                    char c = format.charAt(i);

                    if (c == '\'') {
                        quoted = !quoted;
                    }
                    if (!quoted && c == 'a') {
                        a = i;
                        break;
                    }
                }

                if (a >= 0) {
                    // Move a back so any whitespace before AM/PM is also in the alternate size.
                    final int b = a;
                    while (a > 0 && UCharacter.isUWhiteSpace(format.charAt(a - 1))) {
                        a--;
                    }
                    format = format.substring(0, a) + MAGIC1 + format.substring(a, b)
                            + "a" + MAGIC2 + format.substring(b + 1);
                }
            }
            mClockFormat = new SimpleDateFormat(format);
        }
        // Custom Options: optional date + period separator, adapted from crDroid.
        CharSequence dateString = null;
        String result = "";
        String timeResult = applyPeriodToFormattedTimeIfNeeded(
                mClockFormat.format(mCalendar.getTime()));
        String dateResult = "";

        if (mIsStatusBar && mClockDateDisplay != CLOCK_DATE_DISPLAY_GONE) {
            Date now = new Date();
            if (mClockDateFormat == null || mClockDateFormat.isEmpty()) {
                // Short weekday when no explicit format is set.
                dateString = DateFormat.format("EEE", now);
            } else {
                dateString = DateFormat.format(mClockDateFormat, now);
            }
            if (mClockDateStyle == CLOCK_DATE_STYLE_LOWERCASE) {
                dateResult = dateString.toString().toLowerCase();
            } else if (mClockDateStyle == CLOCK_DATE_STYLE_UPPERCASE) {
                dateResult = dateString.toString().toUpperCase();
            } else {
                dateResult = dateString.toString();
            }
            result = (mClockDatePosition == STYLE_DATE_LEFT) ? dateResult + " " + timeResult
                    : timeResult + " " + dateResult;
        } else {
            result = timeResult;
        }

        SpannableStringBuilder formatted = new SpannableStringBuilder(result);

        if (mClockDateDisplay != CLOCK_DATE_DISPLAY_NORMAL) {
            if (dateString != null) {
                int dateStringLen = dateString.length();
                int timeStringOffset = (mClockDatePosition == STYLE_DATE_RIGHT)
                        ? timeResult.length() + 1 : 0;
                if (mClockDateDisplay == CLOCK_DATE_DISPLAY_GONE) {
                    formatted.delete(0, dateStringLen);
                } else {
                    if (mClockDateDisplay == CLOCK_DATE_DISPLAY_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, timeStringOffset,
                                timeStringOffset + dateStringLen,
                                Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                }
            }
        }

        if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
            int magic1 = result.indexOf(MAGIC1);
            int magic2 = result.indexOf(MAGIC2);
            if (magic1 >= 0 && magic2 > magic1) {
                if (mAmPmStyle == AM_PM_STYLE_GONE) {
                    formatted.delete(magic1, magic2 + 1);
                } else {
                    if (mAmPmStyle == AM_PM_STYLE_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, magic1, magic2,
                                Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                    formatted.delete(magic2, magic2 + 1);
                    formatted.delete(magic1, magic1 + 1);
                }
            }
        }

        return formatted;
    }

    // Custom Options: replace the h:m separator with a period when enabled.
    private String applyPeriodToFormattedTimeIfNeeded(String time) {
        if (!mPeriodHourMinuteSeparator) {
            return time;
        }
        return time.replace(':', '.').replace('：', '.');
    }

    private boolean mShoalLayout;
    private String mShoalMonoFamily;
    private Typeface mStockTypeface;

    /** IchthysOS: monospaced numerals in the zig-zag layout; the stock face otherwise. */
    private void applyShoalClockTypeface() {
        if (!mIsStatusBar) {
            return;
        }
        if (mStockTypeface == null) {
            mStockTypeface = getTypeface() != null ? getTypeface() : Typeface.DEFAULT;
        }
        final int style = getTypeface() != null ? getTypeface().getStyle() : Typeface.NORMAL;
        final Typeface base;
        if (mShoalLayout) {
            // Unset means the Shoal default (ShoalShade), JetBrains Mono, like the rest of the
            // Shoal numerals.
            final String family = mShoalMonoFamily == null || mShoalMonoFamily.isEmpty()
                    ? "jetbrains-mono" : mShoalMonoFamily;
            final Typeface named = Typeface.create(family, Typeface.NORMAL);
            // An unknown family resolves to the default sans face; keep the numerals monospaced.
            base = named == null || named.equals(Typeface.DEFAULT) ? Typeface.MONOSPACE : named;
        } else {
            base = mStockTypeface;
        }
        setTypeface(Typeface.create(base, style));
        applyBoldClockStyle();
    }

    // Custom Options: bold the status bar clock typeface when enabled.
    private void applyBoldClockStyle() {
        if (!mIsStatusBar) {
            return;
        }
        Typeface tf = getTypeface();
        if (tf == null) {
            tf = Typeface.DEFAULT;
        }
        final int base = tf.getStyle() & ~Typeface.BOLD;
        final int target = mBoldStatusBarClock ? (base | Typeface.BOLD) : base;
        if (tf.getStyle() != target) {
            setTypeface(Typeface.create(tf, target));
        }
    }

    private boolean mDemoMode;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        // Only registered for COMMAND_CLOCK
        String millis = args.getString("millis");
        String hhmm = args.getString("hhmm");
        if (millis != null) {
            mCalendar.setTimeInMillis(Long.parseLong(millis));
        } else if (hhmm != null && hhmm.length() == 4) {
            int hh = Integer.parseInt(hhmm.substring(0, 2));
            int mm = Integer.parseInt(hhmm.substring(2));
            boolean is24 = DateFormat.is24HourFormat(getContext(), mCurrentUserId);
            if (is24) {
                mCalendar.set(Calendar.HOUR_OF_DAY, hh);
            } else {
                mCalendar.set(Calendar.HOUR, hh);
            }
            mCalendar.set(Calendar.MINUTE, mm);
        }
        setText(getSmallTime());
        setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
    }

    @Override
    public void onDemoModeStarted() {
        mDemoMode = true;
    }

    @Override
    public void onDemoModeFinished() {
        mDemoMode = false;
        updateClock();
    }

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (mSecondsHandler != null) {
                    mSecondsHandler.removeCallbacks(mSecondTick);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (mSecondsHandler != null) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
            }
        }
    };

    private final Runnable mSecondTick = new Runnable() {
        @Override
        public void run() {
            if (mCalendar != null) {
                updateClock();
            }
            mSecondsHandler.postAtTime(this, SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
        }
    };
}

