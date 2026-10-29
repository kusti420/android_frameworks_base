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

package com.android.server.location;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.SystemService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;

/**
 * IchthysOS shared places (Guard, Rules -> Shared values): the owner's home city and their trips.
 * The system's own location-based features (weather, sunset / sunrise for Night Light, the AOD
 * schedule and Living Sky) use the place this service publishes instead of asking GPS.
 *
 * <p>Settings.Secure, per user, hidden from apps (SettingsProvider.isIchthysAllowlist):
 * <ul>
 *   <li>{@link #KEY_HOME} "lat,lon" and {@link #KEY_HOME_NAME}: the home city.
 *   <li>{@link #KEY_TRIPS}: a JSON array of trips, each {@code {id, name, lat, lon, start, end}}
 *       with start / end in epoch ms (0 = not set). A trip is under way from its start until its
 *       end (or for as long as it has none): "Start now" sets start, "End now" sets end, and a
 *       scheduled trip has both in the future. Of overlapping trips the latest started wins.
 *   <li>{@link #KEY_ACTIVE}: written here, "lat,lon|name|kind" with kind "trip" or "home", or
 *       absent when neither is set. Readers only need this one.
 * </ul>
 * An alarm wakes this service at the next trip start or end; OmniJaws is asked for new weather
 * whenever the active place changes.
 */
public final class IchthysPlacesService extends SystemService {
    private static final String TAG = "IchthysPlaces";

    public static final String KEY_HOME = "ichthys_location_home";
    public static final String KEY_HOME_NAME = "ichthys_location_home_name";
    public static final String KEY_TRIPS = "ichthys_location_trips";
    public static final String KEY_ACTIVE = "ichthys_location_active";

    private static final String OMNIJAWS = "org.omnirom.omnijaws";
    private static final String OMNIJAWS_FORCE_UPDATE = "org.omnirom.omnijaws.FORCE_UPDATE";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final AlarmManager.OnAlarmListener mAlarm = this::update;
    private AlarmManager mAlarmManager;
    private boolean mAlarmSet;

    public IchthysPlacesService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != PHASE_BOOT_COMPLETED) {
            return;
        }
        final Context context = getContext();
        mAlarmManager = context.getSystemService(AlarmManager.class);
        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };
        final ContentResolver cr = context.getContentResolver();
        for (String key : new String[] {KEY_HOME, KEY_HOME_NAME, KEY_TRIPS}) {
            cr.registerContentObserver(Settings.Secure.getUriFor(key), false, observer,
                    UserHandle.USER_ALL);
        }
        final IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                update();
            }
        }, filter, null, mHandler);
        update();
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        mHandler.post(this::update);
    }

    /** Works out the active place, publishes it and sets the alarm for the next change. */
    private void update() {
        final long now = System.currentTimeMillis();
        Trip current = null;
        long next = Long.MAX_VALUE;
        for (Trip trip : readTrips()) {
            if (trip.isUnderWay(now)) {
                if (current == null || trip.start > current.start) {
                    current = trip;
                }
            }
            if (trip.start > now) {
                next = Math.min(next, trip.start);
            }
            if (trip.end > now) {
                next = Math.min(next, trip.end);
            }
        }
        final String active;
        if (current != null) {
            active = format(current.lat, current.lon) + "|" + current.name + "|trip";
        } else {
            final String home = getSecure(KEY_HOME);
            active = isLatLon(home)
                    ? home + "|" + Objects.toString(getSecure(KEY_HOME_NAME), "") + "|home"
                    : null;
        }
        if (!Objects.equals(active, getSecure(KEY_ACTIVE))) {
            Settings.Secure.putStringForUser(getContext().getContentResolver(), KEY_ACTIVE,
                    active, UserHandle.USER_CURRENT);
            Slog.i(TAG, "Active place: " + (active == null ? "none"
                    : active.substring(active.lastIndexOf('|') + 1)));
            refreshWeather();
        }
        if (mAlarmSet) {
            mAlarmManager.cancel(mAlarm);
            mAlarmSet = false;
        }
        if (next != Long.MAX_VALUE) {
            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, next, TAG, mAlarm, mHandler);
            mAlarmSet = true;
        }
    }

    private void refreshWeather() {
        getContext().sendBroadcastAsUser(
                new Intent(OMNIJAWS_FORCE_UPDATE).setPackage(OMNIJAWS), UserHandle.CURRENT);
    }

    private Trip[] readTrips() {
        final String json = getSecure(KEY_TRIPS);
        if (json == null || json.isEmpty()) {
            return new Trip[0];
        }
        try {
            final JSONArray array = new JSONArray(json);
            final Trip[] trips = new Trip[array.length()];
            for (int i = 0; i < trips.length; i++) {
                final JSONObject o = array.getJSONObject(i);
                trips[i] = new Trip(o.optString("name"), o.getDouble("lat"), o.getDouble("lon"),
                        o.optLong("start"), o.optLong("end"));
            }
            return trips;
        } catch (JSONException e) {
            Slog.w(TAG, "Bad trips: " + e.getMessage());
            return new Trip[0];
        }
    }

    @Nullable
    private String getSecure(String key) {
        return Settings.Secure.getStringForUser(getContext().getContentResolver(), key,
                UserHandle.USER_CURRENT);
    }

    private static boolean isLatLon(@Nullable String value) {
        if (value == null) {
            return false;
        }
        final int comma = value.indexOf(',');
        try {
            Double.parseDouble(value.substring(0, comma));
            Double.parseDouble(value.substring(comma + 1));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String format(double lat, double lon) {
        return String.format(Locale.ROOT, "%.4f,%.4f", lat, lon);
    }

    private static final class Trip {
        final String name;
        final double lat;
        final double lon;
        final long start;
        final long end;

        Trip(String name, double lat, double lon, long start, long end) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.start = start;
            this.end = end;
        }

        boolean isUnderWay(long now) {
            return start > 0 && start <= now && (end == 0 || now < end);
        }
    }
}
