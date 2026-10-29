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

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.location.Location;
import android.location.LocationResult;
import android.location.util.identity.CallerIdentity;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * IchthysOS "Fake location" (Guard, Rules -> Shared values): while it is on, every user-installed
 * app gets a made-up fix at the fake place instead of its real position, except the apps on the
 * real-location list (a maps app). System apps keep the real position.
 *
 * <p>Settings.Secure, per user, hidden from apps (SettingsProvider.isIchthysAllowlist):
 * {@link #KEY_FAKE} "lat,lon" the fake place (setting one turns it on), {@link #KEY_ON} "0" to
 * turn it off while keeping the place,
 * {@link #KEY_REAL_PKGS} the comma-separated real-location list.
 *
 * <p>What a spoofed app gets: fixes at the fake place, offset by a few hundred metres that stay
 * the same for this app until the next boot (no two apps meet at one exact point) plus a few
 * metres of noise per fix; no altitude, speed or bearing (they would give the real position or
 * movement away). The time of each real fix is kept, so fixes still arrive and age normally.
 * Raw GNSS (satellite status, NMEA, measurements, navigation messages) is withheld, and the
 * location app-ops read as ignored outside the location service ({@link #isSpoofedCached}), so
 * Wi-Fi scans and connection info, cell info and Bluetooth scans come back empty: each of
 * them places the phone on its own. Emergency (bypass) requests always get the real position.
 *
 * <p>Delivery paths call {@link #isSpoofed} with the multiplexer lock held: it reads in-memory
 * caches, refilled from a content observer and package broadcasts on a handler thread.
 */
public final class IchthysLocationSpoof {
    private static final String TAG = "IchthysLocationSpoof";

    public static final String KEY_ON = "ichthys_location_spoof";
    public static final String KEY_FAKE = "ichthys_location_fake";
    public static final String KEY_REAL_PKGS = "ichthys_location_real_pkgs";

    /** Offset of an app's fixes from the fake place, metres: [MIN, MAX). */
    private static final double OFFSET_MIN_M = 150;
    private static final double OFFSET_MAX_M = 650;
    private static final double NOISE_M = 6;
    private static final float MIN_ACCURACY_M = 25;
    private static final double METRES_PER_DEGREE = 111_320;

    /** Set while the location service itself asks app-ops (SystemAppOpsHelper). */
    private static final ThreadLocal<Boolean> sLocationServiceCall = new ThreadLocal<>();

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static Context sContext;
    /** userId -> settings; a missing entry means "not read yet". */
    @GuardedBy("sLock")
    private static final SparseArray<Config> sConfigByUser = new SparseArray<>();
    /** uid -> the uid is a system app (FLAG_SYSTEM / updated system app): never spoofed. */
    @GuardedBy("sLock")
    private static final SparseBooleanArray sSystemUid = new SparseBooleanArray();
    /** uid -> its offset from the fake place, {north, east} metres, for this boot. */
    @GuardedBy("sLock")
    private static final SparseArray<double[]> sOffsetByUid = new SparseArray<>();
    private static final SecureRandom sRandom = new SecureRandom();

    private static final class Config {
        final boolean on;
        final double lat;
        final double lon;
        final Set<String> real;

        Config(boolean on, double lat, double lon, Set<String> real) {
            this.on = on;
            this.lat = lat;
            this.lon = lon;
            this.real = real;
        }
    }

    private static final Config OFF = new Config(false, 0, 0, Collections.emptySet());

    /**
     * Preinstalled apps that hand positions on to other apps through their own API (Play
     * services' / microG's fused location): spoofed like user apps unless on the real-location
     * list, or every app using that API would get the real position from them.
     */
    private static final Set<String> BROKERS = Set.of("com.google.android.gms");

    private IchthysLocationSpoof() {}

    /** Starts watching the settings and package changes. Called once settings are up. */
    public static void init(Context context, Handler handler) {
        synchronized (sLock) {
            sContext = context;
            sConfigByUser.clear();
        }
        final ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                    int userId) {
                final Config config = read(context, userId);
                synchronized (sLock) {
                    sConfigByUser.put(userId, config);
                }
                android.app.AppOpsManager.invalidateAppOpModeCache();
            }
        };
        for (String key : new String[] {KEY_ON, KEY_FAKE, KEY_REAL_PKGS}) {
            context.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_ALL);
        }
        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                synchronized (sLock) {
                    if (uid >= 0) {
                        sSystemUid.delete(uid);
                    } else {
                        sSystemUid.clear();
                    }
                }
            }
        }, UserHandle.ALL, filter, null, handler);
        // The current user now, so the first delivery doesn't read Settings under a lock.
        handler.post(() -> {
            final int user = android.app.ActivityManager.getCurrentUser();
            final Config config = read(context, user);
            synchronized (sLock) {
                sConfigByUser.put(user, config);
            }
        });
    }

    /**
     * Runs the location service's own app-op call: inside it, the spoof doesn't make location
     * app-ops read as ignored (the service delivers the fake fix itself).
     */
    public static boolean asLocationService(java.util.function.BooleanSupplier call) {
        final Boolean previous = sLocationServiceCall.get();
        sLocationServiceCall.set(Boolean.TRUE);
        try {
            return call.getAsBoolean();
        } finally {
            sLocationServiceCall.set(previous);
        }
    }

    /** True when location deliveries to this caller must carry the fake place. */
    public static boolean isSpoofed(@Nullable CallerIdentity identity) {
        if (identity == null || identity.getPackageName() == null) {
            return false;
        }
        return isSpoofed(identity.getPackageName(), identity.getUid(), true);
    }

    /**
     * For AppOpsService: true when this app's location app-ops must read as ignored, so Wi-Fi,
     * cell and Bluetooth results don't place the phone. Never inside the location service's own
     * calls. Reads no Settings (a user not cached yet reads as false): AppOpsService may hold
     * locks SettingsProvider also takes. Whether the uid is a system app comes from [isSystem]
     * (PackageManagerInternal there) when it isn't cached yet.
     */
    public static boolean isSpoofedCached(String packageName, int uid,
            java.util.function.BooleanSupplier isSystem) {
        if (Boolean.TRUE.equals(sLocationServiceCall.get())) {
            return false;
        }
        return isSpoofed(packageName, uid, false, isSystem);
    }

    private static boolean isSpoofed(String packageName, int uid, boolean mayLoad) {
        return isSpoofed(packageName, uid, mayLoad, null);
    }

    private static boolean isSpoofed(String packageName, int uid, boolean mayLoad,
            @Nullable java.util.function.BooleanSupplier isSystem) {
        if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        final int userId = UserHandle.getUserId(uid);
        Context context;
        Config config;
        boolean systemKnown;
        boolean system;
        synchronized (sLock) {
            context = sContext;
            if (context == null) {
                return false;
            }
            config = sConfigByUser.get(userId);
            final int index = sSystemUid.indexOfKey(uid);
            systemKnown = index >= 0;
            system = systemKnown && sSystemUid.valueAt(index);
        }
        if (config == null) {
            if (!mayLoad) {
                return false;
            }
            config = read(context, userId);
            synchronized (sLock) {
                sConfigByUser.put(userId, config);
            }
        }
        if (!config.on) {
            return false;
        }
        if (!systemKnown) {
            if (isSystem != null) {
                system = isSystem.getAsBoolean();
            } else if (!mayLoad) {
                return false;
            } else {
                system = isSystemUid(context, uid, packageName);
            }
            synchronized (sLock) {
                sSystemUid.put(uid, system);
            }
        }
        if ((system && !BROKERS.contains(packageName)) || config.real.contains(packageName)) {
            return false;
        }
        if (!mayLoad) {
            return true;
        }
        // A shared uid: an allowed sibling package lets the whole uid see the real position
        // (they share one process and permissions anyway).
        final String[] packages = packagesForUid(context, uid);
        if (packages != null) {
            for (String pkg : packages) {
                if (config.real.contains(pkg)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The fake place's fixes for [identity], one for each fix of [real]. */
    public static LocationResult fake(LocationResult real, CallerIdentity identity) {
        final List<Location> fakes = new ArrayList<>(real.size());
        for (int i = 0; i < real.size(); i++) {
            final Location location = fake(real.get(i), identity);
            if (location == null) {
                return null;
            }
            fakes.add(location);
        }
        return LocationResult.wrap(fakes);
    }

    /** The fake place's fix for [identity] in place of [real], or null if there is none. */
    @Nullable
    public static Location fake(@Nullable Location real, CallerIdentity identity) {
        if (real == null) {
            return null;
        }
        final Config config;
        final double[] offset;
        synchronized (sLock) {
            config = sConfigByUser.get(identity.getUserId());
            if (config == null || !config.on) {
                return null;
            }
            double[] o = sOffsetByUid.get(identity.getUid());
            if (o == null) {
                final double angle = sRandom.nextDouble() * 2 * Math.PI;
                final double distance = OFFSET_MIN_M
                        + sRandom.nextDouble() * (OFFSET_MAX_M - OFFSET_MIN_M);
                o = new double[] {Math.cos(angle) * distance, Math.sin(angle) * distance};
                sOffsetByUid.put(identity.getUid(), o);
            }
            offset = o;
        }
        final double north = offset[0] + (sRandom.nextDouble() * 2 - 1) * NOISE_M;
        final double east = offset[1] + (sRandom.nextDouble() * 2 - 1) * NOISE_M;
        final double lat = config.lat + north / METRES_PER_DEGREE;
        final double lon = config.lon
                + east / (METRES_PER_DEGREE * Math.max(0.01, Math.cos(Math.toRadians(lat))));

        final Location fake = new Location(real.getProvider());
        fake.setTime(real.getTime());
        fake.setElapsedRealtimeNanos(real.getElapsedRealtimeNanos());
        fake.setLatitude(lat);
        fake.setLongitude(lon);
        fake.setAccuracy(Math.max(MIN_ACCURACY_M,
                real.hasAccuracy() ? real.getAccuracy() : MIN_ACCURACY_M));
        return fake;
    }

    private static Config read(Context context, int userId) {
        final long token = Binder.clearCallingIdentity();
        try {
            final boolean on = !"0".equals(Settings.Secure.getStringForUser(
                    context.getContentResolver(), KEY_ON, userId));
            final String fake = Settings.Secure.getStringForUser(context.getContentResolver(),
                    KEY_FAKE, userId);
            if (!on || fake == null) {
                return OFF;
            }
            final int comma = fake.indexOf(',');
            final double lat = Double.parseDouble(fake.substring(0, comma));
            final double lon = Double.parseDouble(fake.substring(comma + 1));
            final ArraySet<String> real = new ArraySet<>();
            final String list = Settings.Secure.getStringForUser(context.getContentResolver(),
                    KEY_REAL_PKGS, userId);
            if (!TextUtils.isEmpty(list)) {
                for (String pkg : list.split(",")) {
                    pkg = pkg.trim();
                    if (!pkg.isEmpty()) {
                        real.add(pkg);
                    }
                }
            }
            return new Config(true, lat, lon, real);
        } catch (RuntimeException e) {
            // Called from delivery paths: a bad value turns the spoof off, never crashes them.
            Slog.w(TAG, "Cannot read fake location settings for user " + userId, e);
            return OFF;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean isSystemUid(Context context, int uid, String packageName) {
        final long token = Binder.clearCallingIdentity();
        try {
            final ApplicationInfo ai = context.getPackageManager().getApplicationInfoAsUser(
                    packageName, 0, UserHandle.getUserId(uid));
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Nullable
    private static String[] packagesForUid(Context context, int uid) {
        final long token = Binder.clearCallingIdentity();
        try {
            return context.getPackageManager().getPackagesForUid(uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
