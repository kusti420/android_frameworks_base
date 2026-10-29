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

package com.android.server.twilight;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.icu.util.Calendar;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.crdroid.OmniJawsClient;
import com.android.server.SystemService;

import com.ibm.icu.impl.CalendarAstronomer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Figures out whether it's twilight time based on the user's location.
 * <p>
 * Used by the UI mode manager and other components to adjust night mode
 * effects based on sunrise and sunset.
 */
public final class TwilightService extends SystemService
        implements AlarmManager.OnAlarmListener, Handler.Callback, LocationListener {

    private static final String TAG = "TwilightService";
    private static final String ATTRIBUTION_TAG = "TwilightService";
    private static final boolean DEBUG = false;

    private static final int MSG_START_LISTENING = 1;
    private static final int MSG_STOP_LISTENING = 2;

    @GuardedBy("mListeners")
    private final ArrayMap<TwilightListener, Handler> mListeners = new ArrayMap<>();

    private final Handler mHandler;

    protected AlarmManager mAlarmManager;
    private LocationManager mLocationManager;

    private boolean mBootCompleted;
    private boolean mHasListeners;

    private BroadcastReceiver mTimeChangedReceiver;
    protected Location mLastLocation;

    @GuardedBy("mListeners")
    protected TwilightState mLastTwilightState;

    /**
     * IchthysOS: today's sunset and sunrise as local "HH:mm,HH:mm", for the crDroid always-on
     * display schedule's sunset / sunrise modes and Living Sky (both run in SystemUI, which can't
     * reach this local service). Written whenever the twilight state is worked out; while either
     * needs it this service keeps listening on its own. Without a location fix the weather
     * provider's sunrise and sunset stand in (it has a place of its own: a set city or its last fix).
     */
    private static final String ICHTHYS_TWILIGHT_TIMES = "ichthys_twilight_times";
    /** crDroid DOZE_ALWAYS_ON_AUTO_MODE values that need twilight: sunset-sunrise, mixed. */
    private static final int AOD_MODE_NIGHT = 1;
    private static final int AOD_MODE_MIXED_SUNSET = 3;
    private static final int AOD_MODE_MIXED_SUNRISE = 4;
    private static final DateTimeFormatter TWILIGHT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");
    /** IchthysOS: Secure, 1 while Living Sky regrades the wallpaper's sky. */
    private static final String LIVING_SKY = "ichthys_living_sky";
    private static final Uri WEATHER_URI =
            Uri.parse("content://org.omnirom.omnijaws.provider/weather");
    private final TwilightListener mAodTwilightListener = state -> { };
    private boolean mAodListening;
    private String mPublishedTimes;
    private ContentObserver mWeatherObserver;
    /**
     * IchthysOS: the weather provider's last sunrise / sunset (epoch ms, 0 = none). Without a
     * location fix every listener (Night Light's sunset-to-sunrise schedule too, which otherwise
     * stayed in whatever state it was in) gets a twilight state made from them.
     */
    private volatile long mWeatherSunrise;
    private volatile long mWeatherSunset;
    /** IchthysOS: the last twilight state came from a location (not the weather's times). */
    private volatile boolean mHasFix;

    public TwilightService(Context context) {
        super(context.createAttributionContext(ATTRIBUTION_TAG));
        mHandler = new Handler(Looper.getMainLooper(), this);
    }

    @Override
    public void onStart() {
        publishLocalService(TwilightManager.class, new TwilightManager() {
            @Override
            public void registerListener(@NonNull TwilightListener listener,
                    @NonNull Handler handler) {
                synchronized (mListeners) {
                    final boolean wasEmpty = mListeners.isEmpty();
                    mListeners.put(listener, handler);

                    if (wasEmpty && !mListeners.isEmpty()) {
                        mHandler.sendEmptyMessage(MSG_START_LISTENING);
                    }
                }
            }

            @Override
            public void unregisterListener(@NonNull TwilightListener listener) {
                synchronized (mListeners) {
                    final boolean wasEmpty = mListeners.isEmpty();
                    mListeners.remove(listener);

                    if (!wasEmpty && mListeners.isEmpty()) {
                        mHandler.sendEmptyMessage(MSG_STOP_LISTENING);
                    }
                }
            }

            @Override
            public TwilightState getLastTwilightState() {
                synchronized (mListeners) {
                    return mLastTwilightState;
                }
            }
        });
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        // IchthysOS: until the unlock the weather provider's storage can't be read, so a boot
        // without a location fix found no stand-in times: ask again.
        mHandler.post(() -> {
            if (mHasListeners && mBootCompleted && !mHasFix) {
                postWeatherTimes();
            }
        });
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            final Context c = getContext();
            mAlarmManager = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            mLocationManager = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);

            mBootCompleted = true;
            if (mHasListeners) {
                startListening();
            }

            // IchthysOS: the AOD schedule's sunset / sunrise modes.
            final ContentObserver aodObserver = new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    updateAodListening();
                }
            };
            c.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DOZE_ALWAYS_ON_AUTO_MODE), false,
                    aodObserver, UserHandle.USER_ALL);
            c.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(LIVING_SKY), false, aodObserver,
                    UserHandle.USER_ALL);
            // The weather app's data is only readable once the user is unlocked: try again then.
            final IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
            userFilter.addAction(Intent.ACTION_USER_UNLOCKED);
            c.registerReceiverForAllUsers(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateAodListening();
                    if (mAodListening) {
                        publishWeatherTimesIfNoFix();
                    }
                }
            }, userFilter, null, mHandler);
            updateAodListening();
        }
    }

    /**
     * IchthysOS: listens for as long as the current user's AOD schedule (a sunset / sunrise mode)
     * or Living Sky needs twilight.
     */
    private void updateAodListening() {
        final int mode = Settings.Secure.getIntForUser(getContext().getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON_AUTO_MODE, 0, UserHandle.USER_CURRENT);
        final boolean need = mode == AOD_MODE_NIGHT || mode == AOD_MODE_MIXED_SUNSET
                || mode == AOD_MODE_MIXED_SUNRISE
                || Settings.Secure.getIntForUser(getContext().getContentResolver(), LIVING_SKY,
                        0, UserHandle.USER_CURRENT) == 1;
        if (need == mAodListening) {
            return;
        }
        mAodListening = need;
        final TwilightManager manager = getLocalService(TwilightManager.class);
        if (need) {
            // The weather's times stand in until there is a location fix; follow its updates.
            mWeatherObserver = new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    if (mHasFix) {
                        return;
                    }
                    postWeatherTimes();
                }
            };
            try {
                getContext().getContentResolver().registerContentObserver(WEATHER_URI, false,
                        mWeatherObserver, UserHandle.USER_ALL);
            } catch (SecurityException e) {
                // OmniJaws not installed: location is the only source.
                mWeatherObserver = null;
            }
            manager.registerListener(mAodTwilightListener, mHandler);
            publishWeatherTimesIfNoFix();
        } else {
            if (mWeatherObserver != null) {
                getContext().getContentResolver().unregisterContentObserver(mWeatherObserver);
                mWeatherObserver = null;
            }
            manager.unregisterListener(mAodTwilightListener);
        }
    }

    private void publishWeatherTimesIfNoFix() {
        if (mHasFix) {
            return;
        }
        postWeatherTimes();
    }

    /** Off the main thread: the weather app's provider may have to start first. */
    private void postWeatherTimes() {
        BackgroundThread.getHandler().post(this::publishWeatherTimes);
    }

    /** IchthysOS: the weather provider's sunrise / sunset (OpenMeteo fills them), if it has any. */
    private void publishWeatherTimes() {
        long sunrise = 0;
        long sunset = 0;
        try (Cursor c = getContext().getContentResolver().query(WEATHER_URI,
                OmniJawsClient.WEATHER_PROJECTION, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                final int rise = c.getColumnIndex("sunrise");
                final int set = c.getColumnIndex("sunset");
                if (rise != -1 && set != -1) {
                    sunrise = c.getLong(rise);
                    sunset = c.getLong(set);
                }
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "No weather sunrise/sunset", e);
        }
        if (sunrise > 0 && sunset > 0) {
            publishTimes(sunset, sunrise);
            if (sunrise != mWeatherSunrise || sunset != mWeatherSunset) {
                mWeatherSunrise = sunrise;
                mWeatherSunset = sunset;
                // Only on a change: updateTwilightState asks for the weather again while there
                // is no fix.
                mHandler.post(() -> {
                    if (mHasListeners && mBootCompleted) {
                        updateTwilightState();
                    }
                });
            }
        }
    }

    /**
     * IchthysOS: the weather's sunrise and sunset at their times of day on the days around
     * [timeMillis], laid out as {@link #calculateTwilightState} does; null without weather times.
     */
    private TwilightState weatherTwilightState(long timeMillis) {
        final long rise = mWeatherSunrise;
        final long set = mWeatherSunset;
        if (rise <= 0 || set <= 0) {
            return null;
        }
        final ZoneId zone = ZoneId.systemDefault();
        final LocalDate today = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate();
        final LocalTime riseTime = Instant.ofEpochMilli(rise).atZone(zone).toLocalTime();
        final LocalTime setTime = Instant.ofEpochMilli(set).atZone(zone).toLocalTime();
        long sunriseTimeMillis = epochMillis(today, riseTime, zone);
        long sunsetTimeMillis = epochMillis(today, setTime, zone);
        if (sunsetTimeMillis < timeMillis) {
            sunriseTimeMillis = epochMillis(today.plusDays(1), riseTime, zone);
        } else if (sunriseTimeMillis > timeMillis) {
            sunsetTimeMillis = epochMillis(today.minusDays(1), setTime, zone);
        }
        return new TwilightState(sunriseTimeMillis, sunsetTimeMillis);
    }

    private static long epochMillis(LocalDate date, LocalTime time, ZoneId zone) {
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli();
    }

    /** IchthysOS: see {@link #ICHTHYS_TWILIGHT_TIMES}. */
    private void publishTwilightTimes(TwilightState state) {
        publishTimes(state.sunsetTimeMillis(), state.sunriseTimeMillis());
    }

    private synchronized void publishTimes(long sunsetMillis, long sunriseMillis) {
        final ZoneId zone = ZoneId.systemDefault();
        final String times =
                TWILIGHT_TIME_FORMAT.format(Instant.ofEpochMilli(sunsetMillis).atZone(zone))
                + "," + TWILIGHT_TIME_FORMAT.format(Instant.ofEpochMilli(sunriseMillis)
                        .atZone(zone));
        if (times.equals(mPublishedTimes)) {
            return;
        }
        mPublishedTimes = times;
        Settings.Global.putString(getContext().getContentResolver(), ICHTHYS_TWILIGHT_TIMES,
                times);
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_START_LISTENING:
                if (!mHasListeners) {
                    mHasListeners = true;
                    if (mBootCompleted) {
                        startListening();
                    }
                }
                return true;
            case MSG_STOP_LISTENING:
                if (mHasListeners) {
                    mHasListeners = false;
                    if (mBootCompleted) {
                        stopListening();
                    }
                }
                return true;
        }
        return false;
    }

    private void startListening() {
        Slog.d(TAG, "startListening");

        // Start listening for location updates (default: low power, max 1h, min 10m).
        mLocationManager.requestLocationUpdates(
                null /* default */, this, Looper.getMainLooper());

        // Request the device's location immediately if a previous location isn't available.
        if (mLocationManager.getLastLocation() == null) {
            if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocationManager.getCurrentLocation(
                        LocationManager.NETWORK_PROVIDER, null, getContext().getMainExecutor(),
                        this::onLocationChanged);
            } else if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocationManager.getCurrentLocation(
                        LocationManager.GPS_PROVIDER, null, getContext().getMainExecutor(),
                        this::onLocationChanged);
            }
        }

        // Update whenever the system clock is changed.
        if (mTimeChangedReceiver == null) {
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Slog.d(TAG, "onReceive: " + intent);
                    updateTwilightState();
                }
            };

            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);
        }

        // Force an update now that we have listeners registered.
        updateTwilightState();
    }

    private void stopListening() {
        Slog.d(TAG, "stopListening");

        if (mTimeChangedReceiver != null) {
            getContext().unregisterReceiver(mTimeChangedReceiver);
            mTimeChangedReceiver = null;
        }

        if (mLastTwilightState != null) {
            mAlarmManager.cancel(this);
        }

        mLocationManager.removeUpdates(this);
        mLastLocation = null;
    }

    private void updateTwilightState() {
        // Calculate the twilight state based on the current time and location.
        final long currentTimeMillis = System.currentTimeMillis();
        final Location location = mLastLocation != null ? mLastLocation
                : mLocationManager.getLastLocation();
        final TwilightState fromLocation = calculateTwilightState(location, currentTimeMillis);
        mHasFix = fromLocation != null;
        final TwilightState state = fromLocation != null ? fromLocation
                : weatherTwilightState(currentTimeMillis);
        if (DEBUG) {
            Slog.d(TAG, "updateTwilightState: " + state);
        }

        // Notify listeners if the state has changed.
        synchronized (mListeners) {
            if (!Objects.equals(mLastTwilightState, state)) {
                mLastTwilightState = state;

                for (int i = mListeners.size() - 1; i >= 0; --i) {
                    final TwilightListener listener = mListeners.keyAt(i);
                    final Handler handler = mListeners.valueAt(i);
                    handler.post(() -> listener.onTwilightStateChanged(state));
                }
            }
        }

        // IchthysOS: no location fix yet: the weather's times (refreshed), while someone needs
        // them. A change comes back here through publishWeatherTimes.
        if (location == null && mHasListeners) {
            postWeatherTimes();
        }

        // Schedule an alarm to update the state at the next sunrise or sunset.
        if (state != null) {
            publishTwilightTimes(state);
            final long triggerAtMillis = state.isNight()
                    ? state.sunriseTimeMillis() : state.sunsetTimeMillis();
            mAlarmManager.setExact(AlarmManager.RTC, triggerAtMillis, TAG, this, mHandler);
        }
    }

    @Override
    public void onAlarm() {
        Slog.d(TAG, "onAlarm");
        updateTwilightState();
    }

    @Override
    public void onLocationChanged(@Nullable Location location) {
        if (location != null) {
            Slog.d(TAG, "onLocationChanged:"
                    + " provider=" + location.getProvider()
                    + " accuracy=" + location.getAccuracy()
                    + " time=" + location.getTime());
            mLastLocation = location;
            updateTwilightState();
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    /**
     * Calculates the twilight state for a specific location and time.
     *
     * @param location the location to use
     * @param timeMillis the reference time to use
     * @return the calculated {@link TwilightState}, or {@code null} if location is {@code null}
     */
    private static TwilightState calculateTwilightState(Location location, long timeMillis) {
        if (location == null) {
            return null;
        }

        final CalendarAstronomer ca = new CalendarAstronomer(
                location.getLongitude(), location.getLatitude());

        final Calendar noon = Calendar.getInstance();
        noon.setTimeInMillis(timeMillis);
        noon.set(Calendar.HOUR_OF_DAY, 12);
        noon.set(Calendar.MINUTE, 0);
        noon.set(Calendar.SECOND, 0);
        noon.set(Calendar.MILLISECOND, 0);
        ca.setTime(noon.getTimeInMillis());

        long sunriseTimeMillis = ca.getSunRiseSet(true /* rise */);
        long sunsetTimeMillis = ca.getSunRiseSet(false /* rise */);

        if (sunsetTimeMillis < timeMillis) {
            noon.add(Calendar.DATE, 1);
            ca.setTime(noon.getTimeInMillis());
            sunriseTimeMillis = ca.getSunRiseSet(true /* rise */);
        } else if (sunriseTimeMillis > timeMillis) {
            noon.add(Calendar.DATE, -1);
            ca.setTime(noon.getTimeInMillis());
            sunsetTimeMillis = ca.getSunRiseSet(false /* rise */);
        }

        return new TwilightState(sunriseTimeMillis, sunsetTimeMillis);
    }
}
