/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.hardware.display;

import static android.provider.Settings.Secure.SCREEN_OFF_UNLOCK_UDFPS_ENABLED;

import android.annotation.TestApi;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.biometrics.Flags;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * AmbientDisplayConfiguration encapsulates reading access to the configuration of ambient display.
 *
 * @hide
 */
@TestApi
public class AmbientDisplayConfiguration {
    private static final String TAG = "AmbientDisplayConfig";

    /**
     * Used to poll the sticky ACTION_BATTERY_CHANGED broadcast for the "keep AOD on while charging"
     * option. Kept static because this class is instantiated in several processes and the filter is
     * immutable.
     */
    private static final IntentFilter BATTERY_CHANGED_FILTER =
            new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

    /** Values of {@code Settings.Secure.DOZE_ALWAYS_ON_AUTO_MODE}. */
    private static final int AOD_SCHEDULE_DISABLED = 0;
    private static final int AOD_SCHEDULE_TIME = 2;

    private static final String DEFAULT_AOD_SCHEDULE_TIME = "20:00,07:00";
    private static final DateTimeFormatter AOD_SCHEDULE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    private final Context mContext;
    private final boolean mAlwaysOnByDefault;
    private final boolean mPickupGestureEnabledByDefault;
    private final boolean mScreenOffUdfpsAvailable;
    private final boolean mDozeEnabledByDefault;
    private final boolean mTapGestureEnabledByDefault;
    private final boolean mDoubleTapGestureEnabledByDefault;

    /** Copied from android.provider.Settings.Secure since these keys are hidden. */
    private static final String[] DOZE_SETTINGS = {
            Settings.Secure.DOZE_ENABLED,
            Settings.Secure.DOZE_ALWAYS_ON,
            Settings.Secure.DOZE_PICK_UP_GESTURE,
            Settings.Secure.DOZE_PULSE_ON_LONG_PRESS,
            Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
            Settings.Secure.DOZE_WAKE_LOCK_SCREEN_GESTURE,
            Settings.Secure.DOZE_WAKE_DISPLAY_GESTURE,
            Settings.Secure.DOZE_TAP_SCREEN_GESTURE,
            // crDroid doze gestures, so that disableDozeSettings()/restoreDozeSettings() (used by
            // CTS and by battery-saver style flows) covers them like the AOSP ones.
            Settings.Secure.DOZE_TILT_GESTURE,
            Settings.Secure.DOZE_HANDWAVE_GESTURE,
            Settings.Secure.DOZE_POCKET_GESTURE,
            Settings.Secure.DOZE_ON_CHARGE
    };

    /** Non-user configurable doze settings */
    private static final String[] NON_USER_CONFIGURABLE_DOZE_SETTINGS = {
            Settings.Secure.DOZE_QUICK_PICKUP_GESTURE
    };

    final SparseArray<Map<String, String>> mUsersInitialValues = new SparseArray<>();

    /** @hide */
    @TestApi
    public AmbientDisplayConfiguration(Context context) {
        mContext = context;
        mAlwaysOnByDefault = mContext.getResources().getBoolean(R.bool.config_dozeAlwaysOnEnabled);
        mPickupGestureEnabledByDefault =
                mContext.getResources().getBoolean(R.bool.config_dozePickupGestureEnabled);
        mScreenOffUdfpsAvailable =
                mContext.getResources().getBoolean(R.bool.config_screen_off_udfps_enabled);
        mDozeEnabledByDefault =
                !com.android.server.display.feature.flags.Flags.configurableDefaultDozeValues()
                        || mContext.getResources().getBoolean(R.bool.config_dozeEnabled);
        mTapGestureEnabledByDefault =
                !com.android.server.display.feature.flags.Flags.configurableDefaultDozeValues()
                        || mContext.getResources().getBoolean(R.bool.config_dozeTapGestureEnabled);
        mDoubleTapGestureEnabledByDefault =
                !com.android.server.display.feature.flags.Flags.configurableDefaultDozeValues()
                        || mContext.getResources().getBoolean(
                        R.bool.config_dozeDoubleTapGestureEnabled);
    }

    /** @hide */
    public boolean enabled(int user) {
        return pulseOnNotificationEnabled(user)
                || pulseOnLongPressEnabled(user)
                || alwaysOnEnabled(user)
                || wakeLockScreenGestureEnabled(user)
                || wakeDisplayGestureEnabled(user)
                || pickupGestureEnabled(user)
                || tiltGestureEnabled(user)
                || handwaveGestureEnabled(user)
                || pocketGestureEnabled(user)
                || tapGestureEnabled(user)
                || doubleTapGestureEnabled(user)
                || quickPickupSensorEnabled(user)
                || screenOffUdfpsEnabled(user);
    }

    /** @hide */
    public boolean pulseOnNotificationEnabled(int user) {
        return boolSetting(Settings.Secure.DOZE_ENABLED, user,
                mDozeEnabledByDefault ? 1 : 0)
                && pulseOnNotificationAvailable();
    }

    /** @hide */
    public boolean pulseOnNotificationAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_pulseOnNotificationsAvailable)
                && ambientDisplayAvailable();
    }

    /** @hide */
    public boolean pickupGestureEnabled(int user) {
        return boolSetting(Settings.Secure.DOZE_PICK_UP_GESTURE, user,
                mPickupGestureEnabledByDefault ? 1 : 0)
                && dozePickupSensorAvailable();
    }

    /** @hide */
    public boolean dozePickupSensorAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_dozePulsePickup);
    }

    /**
     * Doze gesture: raise/tilt the device to show ambient display. Backed by
     * {@code Sensor.TYPE_TILT_DETECTOR}.
     *
     * @hide
     */
    public boolean tiltGestureEnabled(int user) {
        return boolSettingDefaultOff(Settings.Secure.DOZE_TILT_GESTURE, user)
                && dozeTiltSensorAvailable();
    }

    /** @hide */
    public boolean dozeTiltSensorAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_dozePulseTilt);
    }

    /**
     * Doze gesture: wave a hand over the proximity sensor (short near->far) to show ambient
     * display.
     *
     * @hide
     */
    public boolean handwaveGestureEnabled(int user) {
        return boolSettingDefaultOff(Settings.Secure.DOZE_HANDWAVE_GESTURE, user)
                && dozeProximitySensorAvailable();
    }

    /**
     * Doze gesture: take the device out of a pocket/bag (long near->far) to show ambient display.
     *
     * @hide
     */
    public boolean pocketGestureEnabled(int user) {
        return boolSettingDefaultOff(Settings.Secure.DOZE_POCKET_GESTURE, user)
                && dozeProximitySensorAvailable();
    }

    /** @hide */
    public boolean dozeProximitySensorAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_dozePulseProximity);
    }

    /**
     * Whether a doze gesture should fully wake the device instead of only pulsing the ambient
     * display.
     *
     * @hide
     */
    public boolean raiseToWakeGestureEnabled(int user) {
        return boolSettingDefaultOff(Settings.Secure.RAISE_TO_WAKE_GESTURE, user);
    }

    /**
     * Haptic duration in milliseconds to play when a doze gesture pulses the display, or 0 when the
     * user has the haptic disabled.
     *
     * @hide
     */
    public int dozeGestureVibrationDuration(int user) {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_GESTURE_VIBRATE, 0, user);
    }

    /** @hide */
    public boolean tapGestureEnabled(int user) {
        return boolSetting(Settings.Secure.DOZE_TAP_SCREEN_GESTURE, user,
                mTapGestureEnabledByDefault ? 1 : 0)
                && tapSensorAvailable();
    }

    /** @hide */
    public boolean tapSensorAvailable() {
        for (String tapType : tapSensorTypeMapping()) {
            if (!TextUtils.isEmpty(tapType)) {
                return true;
            }
        }
        return false;
    }

    /** @hide */
    public boolean doubleTapGestureEnabled(int user) {
        return boolSetting(Settings.Secure.DOZE_DOUBLE_TAP_GESTURE, user,
                mDoubleTapGestureEnabledByDefault ? 1 : 0)
                && doubleTapSensorAvailable();
    }

    /** @hide */
    public boolean doubleTapSensorAvailable() {
        return !TextUtils.isEmpty(doubleTapSensorType());
    }

    /** @hide */
    public boolean quickPickupSensorEnabled(int user) {
        return boolSettingDefaultOn(Settings.Secure.DOZE_QUICK_PICKUP_GESTURE, user)
                && !TextUtils.isEmpty(quickPickupSensorType())
                && pickupGestureEnabled(user)
                && !alwaysOnEnabled(user);
    }

    /** @hide */
    public boolean screenOffUdfpsEnabled(int user) {
        if (TextUtils.isEmpty(udfpsLongPressSensorType())) {
            return false;
        }

        boolean isScreenOffUnlockEnabled = mScreenOffUdfpsAvailable && Flags.screenOffUnlockUdfps();
        if (!isScreenOffUnlockEnabled) {
            return false;
        } else {
            return boolSetting(SCREEN_OFF_UNLOCK_UDFPS_ENABLED, user,
                    mContext.getResources().getBoolean(R.bool.config_screen_off_udfps_default_on)
                            ? 1 : 0);
        }
    }

    /** @hide */
    public boolean wakeScreenGestureAvailable() {
        return mContext.getResources()
                .getBoolean(R.bool.config_dozeWakeLockScreenSensorAvailable);
    }

    /** @hide */
    public boolean wakeLockScreenGestureEnabled(int user) {
        return boolSettingDefaultOn(Settings.Secure.DOZE_WAKE_LOCK_SCREEN_GESTURE, user)
                && wakeScreenGestureAvailable();
    }

    /** @hide */
    public boolean wakeDisplayGestureEnabled(int user) {
        return boolSettingDefaultOn(Settings.Secure.DOZE_WAKE_DISPLAY_GESTURE, user)
                && wakeScreenGestureAvailable();
    }

    /** @hide */
    public long getWakeLockScreenDebounce() {
        return mContext.getResources().getInteger(R.integer.config_dozeWakeLockScreenDebounce);
    }

    /** @hide */
    public String doubleTapSensorType() {
        return mContext.getResources().getString(R.string.config_dozeDoubleTapSensorType);
    }

    /** @hide
     * May support multiple postures.
     */
    public String[] tapSensorTypeMapping() {
        String[] postureMapping =
                mContext.getResources().getStringArray(R.array.config_dozeTapSensorPostureMapping);
        if (ArrayUtils.isEmpty(postureMapping)) {
            return new String[] {
                    mContext.getResources().getString(R.string.config_dozeTapSensorType)
            };
        }
        return postureMapping;
    }

    /** @hide */
    public String longPressSensorType() {
        return mContext.getResources().getString(R.string.config_dozeLongPressSensorType);
    }

    /** @hide */
    public String udfpsLongPressSensorType() {
        return mContext.getResources().getString(R.string.config_dozeUdfpsLongPressSensorType);
    }

    /** @hide */
    public String quickPickupSensorType() {
        return mContext.getResources().getString(R.string.config_quickPickupSensorType);
    }

    /** @hide */
    public boolean pulseOnLongPressEnabled(int user) {
        return pulseOnLongPressAvailable() && boolSettingDefaultOff(
                Settings.Secure.DOZE_PULSE_ON_LONG_PRESS, user);
    }

    private boolean pulseOnLongPressAvailable() {
        return !TextUtils.isEmpty(longPressSensorType());
    }

    /**
     * Returns if Always-on-Display functionality is enabled on the display for a specified user.
     *
     * @hide
     */
    @TestApi
    public boolean alwaysOnEnabled(int user) {
        return (alwaysOnEnabledSetting(user) && alwaysOnScheduleAllowsNow(user))
                || alwaysOnChargingEnabled(user);
    }

    /**
     * Evaluates the crDroid always-on-display schedule
     * ({@code Settings.Secure.DOZE_ALWAYS_ON_AUTO_MODE}) for the current wall
     * clock time.
     *
     * <p>Only the "custom time" mode can be answered here. The sunset/sunrise modes need twilight
     * data, which lives behind {@code TwilightManager} -- a system_server local service with no
     * binder API -- and cannot be computed in the processes this class runs in. Those modes
     * therefore impose no restriction rather than guessing at a wrong window.
     *
     * @return whether always-on-display is allowed to be on right now
     * @hide
     */
    public boolean alwaysOnScheduleAllowsNow(int user) {
        if (!alwaysOnCustomTimeScheduleEnabled(user)) {
            return true;
        }
        final LocalTime[] window = alwaysOnScheduleWindow(user);
        if (window == null) {
            return true;
        }
        return isWithinWindow(LocalTime.now(), window[0], window[1]);
    }

    /**
     * Whether the always-on-display schedule is set to the "custom time" mode -- the only mode that
     * can be evaluated outside of system_server.
     *
     * @hide
     */
    public boolean alwaysOnCustomTimeScheduleEnabled(int user) {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON_AUTO_MODE, AOD_SCHEDULE_DISABLED, user)
                == AOD_SCHEDULE_TIME;
    }

    /**
     * The user's custom always-on-display window as {since, till}, or {@code null} if it cannot be
     * parsed.
     *
     * @hide
     */
    public LocalTime[] alwaysOnScheduleWindow(int user) {
        String value = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON_AUTO_TIME, user);
        if (TextUtils.isEmpty(value)) {
            value = DEFAULT_AOD_SCHEDULE_TIME;
        }
        final String[] parts = value.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new LocalTime[] {
                    LocalTime.parse(parts[0], AOD_SCHEDULE_TIME_FORMAT),
                    LocalTime.parse(parts[1], AOD_SCHEDULE_TIME_FORMAT),
            };
        } catch (DateTimeParseException e) {
            Log.w(TAG, "Unparsable " + Settings.Secure.DOZE_ALWAYS_ON_AUTO_TIME + ": " + value);
            return null;
        }
    }

    /** Handles windows that wrap past midnight, which is the normal case here. @hide */
    private static boolean isWithinWindow(LocalTime now, LocalTime since, LocalTime till) {
        if (since.equals(till)) {
            // A zero-length window would mean "never on", which is never what the user meant.
            return true;
        }
        if (since.isBefore(till)) {
            return !now.isBefore(since) && now.isBefore(till);
        }
        return !now.isBefore(since) || now.isBefore(till);
    }

    /** @hide */
    public boolean alwaysOnEnabledSetting(int user) {
        return boolSetting(Settings.Secure.DOZE_ALWAYS_ON, user, mAlwaysOnByDefault ? 1 : 0)
                && alwaysOnAvailable() && !accessibilityInversionEnabled(user);
    }

    /**
     * Whether the user asked for the ambient display to stay on for as long as the device is
     * charging, regardless of the plain always-on setting.
     *
     * @hide
     */
    public boolean alwaysOnChargingEnabledSetting(int user) {
        return boolSettingDefaultOff(Settings.Secure.DOZE_ON_CHARGE, user);
    }

    private boolean alwaysOnChargingEnabled(int user) {
        if (!alwaysOnChargingEnabledSetting(user)
                || !alwaysOnAvailable()
                || accessibilityInversionEnabled(user)) {
            return false;
        }
        // Polls the sticky battery broadcast rather than keeping a receiver alive: this class is a
        // stateless config reader instantiated in several processes, and callers already re-query
        // it whenever the charging state changes.
        final Intent intent = mContext.registerReceiver(null /* receiver */, BATTERY_CHANGED_FILTER,
                Context.RECEIVER_NOT_EXPORTED);
        if (intent == null) {
            return false;
        }
        final int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged == 0) {
            return false;
        }
        final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        // BATTERY_STATUS_FULL still counts: a device left on the charger overnight reports FULL but
        // is very much still plugged in, which is exactly when this option is wanted.
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    /**
     * Returns if Always-on-Display functionality is available on the display.
     *
     * @hide
     */
    @TestApi
    public boolean alwaysOnAvailable() {
        return (alwaysOnDisplayDebuggingEnabled() || alwaysOnDisplayAvailable())
                && ambientDisplayAvailable();
    }

    /**
     * Returns if Always-on-Display functionality is available on the display for a specified user.
     *
     *  @hide
     */
    @TestApi
    public boolean alwaysOnAvailableForUser(int user) {
        return alwaysOnAvailable() && !accessibilityInversionEnabled(user);
    }

    /** @hide */
    public String ambientDisplayComponent() {
        return mContext.getResources().getString(R.string.config_dozeComponent);
    }

    /** @hide */
    public boolean accessibilityInversionEnabled(int user) {
        return boolSettingDefaultOff(Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, user);
    }

    /** @hide */
    public boolean ambientDisplayAvailable() {
        return !TextUtils.isEmpty(ambientDisplayComponent());
    }

    /** @hide */
    public boolean dozeSuppressed(int user) {
        return boolSettingDefaultOff(Settings.Secure.SUPPRESS_DOZE, user);
    }

    private boolean alwaysOnDisplayAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_dozeAlwaysOnDisplayAvailable);
    }

    private boolean alwaysOnDisplayDebuggingEnabled() {
        return SystemProperties.getBoolean("debug.doze.aod", false) && Build.IS_DEBUGGABLE;
    }

    private boolean boolSettingDefaultOn(String name, int user) {
        return boolSetting(name, user, 1);
    }

    private boolean boolSettingDefaultOff(String name, int user) {
        return boolSetting(name, user, 0);
    }

    private boolean boolSetting(String name, int user, int def) {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(), name, def, user) != 0;
    }

    /** @hide */
    @TestApi
    public void disableDozeSettings(int userId) {
        disableDozeSettings(false /* shouldDisableNonUserConfigurable */, userId);
    }

    /** @hide */
    @TestApi
    public void disableDozeSettings(boolean shouldDisableNonUserConfigurable, int userId) {
        Map<String, String> initialValues = mUsersInitialValues.get(userId);
        if (initialValues != null && !initialValues.isEmpty()) {
            throw new IllegalStateException("Don't call #disableDozeSettings more than once,"
                    + "without first calling #restoreDozeSettings");
        }
        initialValues = new ArrayMap<>();
        for (String name : DOZE_SETTINGS) {
            initialValues.put(name, getDozeSetting(name, userId));
            putDozeSetting(name, "0", userId);
        }
        if (shouldDisableNonUserConfigurable) {
            for (String name : NON_USER_CONFIGURABLE_DOZE_SETTINGS) {
                initialValues.put(name, getDozeSetting(name, userId));
                putDozeSetting(name, "0", userId);
            }
        }
        mUsersInitialValues.put(userId, initialValues);
    }

    /** @hide */
    @TestApi
    public void restoreDozeSettings(int userId) {
        final Map<String, String> initialValues = mUsersInitialValues.get(userId);
        if (initialValues != null && !initialValues.isEmpty()) {
            for (String name : DOZE_SETTINGS) {
                putDozeSetting(name, initialValues.get(name), userId);
            }
            mUsersInitialValues.remove(userId);
        }
    }

    private String getDozeSetting(String name, int userId) {
        return Settings.Secure.getStringForUser(mContext.getContentResolver(), name, userId);
    }

    private void putDozeSetting(String name, String value, int userId) {
        Settings.Secure.putStringForUser(mContext.getContentResolver(), name, value, userId);
    }
}
