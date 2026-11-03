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
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.database.ContentObserver;
import android.location.Location;
import android.location.LocationResult;
import android.location.util.identity.CallerIdentity;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Range;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * IchthysOS "Spoofed values" (Guard, Rules -> Shared values): while the fake place is on, every
 * user-installed app gets a made-up fix at the fake place instead of its real position, except
 * the apps on the real-location list (a maps app). System apps keep the real position.
 *
 * <p>Settings.Secure, per user, hidden from apps (SettingsProvider.isIchthysAllowlist):
 * {@link #KEY_FAKE} "lat,lon" the fake place (setting one turns it on), {@link #KEY_ON} "0" to
 * turn it off while keeping the place, {@link #KEY_REAL_PKGS} the comma-separated real-location
 * list, and {@link #KEY_PROFILE} the spoof profile: a JSON object with the country, time zone,
 * carrier (mcc, mnc, operator), app locale, Samsung sales code and whether spoofed apps are
 * offline without a VPN ({@link Profile}). Without a profile only the position is spoofed.
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
 * <p>With a profile the app process asks for it at bind time
 * (ILocationManager.getIchthysSpoofProfile -> {@link #getProfileForCaller}) and rewrites what
 * it reads itself (telephony properties, Configuration, ServiceState, time zone); broadcasts
 * carrying the real country or zone are not delivered to spoofed apps (BroadcastSkipPolicy) and
 * CountryDetectorService answers with the profile's country. When the profile says "vpn", the
 * spoofed uids (and their SDK sandboxes) may only reach the network through a VPN
 * (ConnectivityManager.setRequireVpnForUids), so the ISP's address never shows. Spoofed apps are
 * restarted when the profile, the place or the on/off state changes: everything they read is
 * cached in their process.
 *
 * <p>Delivery paths call {@link #isSpoofed} with the multiplexer lock held: it reads in-memory
 * caches, refilled from a content observer and package broadcasts on a handler thread. All
 * users' settings are cached at init. The VPN rules and restarts are worked out on that handler
 * thread only, never under {@link #sLock}.
 */
public final class IchthysLocationSpoof {
    private static final String TAG = "IchthysLocationSpoof";

    public static final String KEY_ON = "ichthys_location_spoof";
    public static final String KEY_FAKE = "ichthys_location_fake";
    public static final String KEY_REAL_PKGS = "ichthys_location_real_pkgs";
    public static final String KEY_PROFILE = "ichthys_location_profile";

    /** Offset of an app's fixes from the fake place, metres: [MIN, MAX). */
    private static final double OFFSET_MIN_M = 150;
    private static final double OFFSET_MAX_M = 650;
    private static final double NOISE_M = 6;
    private static final float MIN_ACCURACY_M = 25;
    private static final double METRES_PER_DEGREE = 111_320;

    /**
     * Settings writes several keys in a row (place, then profile): changes are applied together
     * after this pause, so the apps are restarted once.
     */
    private static final long SETTLE_MS = 300;
    /** ConnectivityManager may not be there yet right at system-services-ready. */
    private static final long VPN_RETRY_MS = 5_000;

    private static final String KILL_REASON = "ichthys spoof profile changed";

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

    // Handler-thread state (the handler given to init; nothing else touches it).
    private static Handler sHandler;
    /** userId -> the uids spoofed at the last apply (sorted), for the VPN rules and restarts. */
    private static final SparseArray<int[]> sSpoofedUidsByUser = new SparseArray<>();
    /** The uid ranges ConnectivityManager currently requires a VPN for. */
    private static List<Range<Integer>> sVpnRanges = Collections.emptyList();
    /** Users whose uid sets must be worked out again at the next apply. */
    private static final SparseBooleanArray sDirtyUsers = new SparseBooleanArray();
    /** userId -> its settings before the pending change, when that change calls for restarts. */
    private static final SparseArray<Config> sConfigBeforeChange = new SparseArray<>();
    private static final Runnable sApply = IchthysLocationSpoof::applyPending;
    private static final Runnable sVpnRetry = IchthysLocationSpoof::applyVpn;

    /**
     * The spoof profile of a user (Guard > Spoofed values), as Settings wrote it. Everything may
     * be absent; {@link #EMPTY} stands for "the fake place is on, but no profile is set": the app
     * side then keeps today's GPS-only behaviour.
     */
    public static final class Profile {
        public static final Profile EMPTY = new Profile("{}", null, null, null, null, null,
                null, null, false);

        /** The JSON Settings wrote ("{}" for {@link #EMPTY}); what the app process receives. */
        @NonNull public final String json;
        /** ISO 3166-1 alpha-2, upper case, or null. */
        @Nullable public final String country;
        /** Olson time zone id, or null. */
        @Nullable public final String tz;
        /** 3 digits, or null. */
        @Nullable public final String mcc;
        /** 2 or 3 digits, or null. */
        @Nullable public final String mnc;
        /** Carrier display name, or null. */
        @Nullable public final String operator;
        /** BCP-47 app language, or null (= not spoofed). */
        @Nullable public final String locale;
        /** Samsung sales code, or null (= keep the real one). */
        @Nullable public final String sales;
        /** Spoofed apps are offline unless a VPN is up. */
        public final boolean vpn;

        private Profile(@NonNull String json, @Nullable String country, @Nullable String tz,
                @Nullable String mcc, @Nullable String mnc, @Nullable String operator,
                @Nullable String locale, @Nullable String sales, boolean vpn) {
            this.json = json;
            this.country = country;
            this.tz = tz;
            this.mcc = mcc;
            this.mnc = mnc;
            this.operator = operator;
            this.locale = locale;
            this.sales = sales;
            this.vpn = vpn;
        }

        /** The profile in [json], or null when it is empty or not valid JSON. */
        @Nullable
        static Profile parse(@Nullable String json) {
            if (TextUtils.isEmpty(json)) {
                return null;
            }
            try {
                final JSONObject o = new JSONObject(json);
                String country = field(o, "country");
                if (country != null) {
                    country = country.length() == 2 && Character.isLetter(country.charAt(0))
                            && Character.isLetter(country.charAt(1))
                            ? country.toUpperCase(java.util.Locale.ROOT) : null;
                }
                return new Profile(json, country, field(o, "tz"), field(o, "mcc"),
                        field(o, "mnc"), field(o, "operator"), field(o, "locale"),
                        field(o, "sales"), o.optBoolean("vpn", false));
            } catch (JSONException | RuntimeException e) {
                // Written by Settings; a bad value must never take system_server down.
                Slog.w(TAG, "Ignoring bad spoof profile: " + e);
                return null;
            }
        }

        @Nullable
        private static String field(JSONObject o, String key) {
            final String value = o.optString(key, "").trim();
            return value.isEmpty() ? null : value;
        }

        @Override
        public String toString() {
            return json;
        }
    }

    private static final class Config {
        final boolean on;
        final double lat;
        final double lon;
        final Set<String> real;
        /** The spoof profile, or null while only the place is set (position only, as before). */
        @Nullable final Profile profile;

        Config(boolean on, double lat, double lon, Set<String> real, @Nullable Profile profile) {
            this.on = on;
            this.lat = lat;
            this.lon = lon;
            this.real = real;
            this.profile = profile;
        }

        /** True when only the real-location list may differ from [other]. */
        boolean sameValues(Config other) {
            return on == other.on && lat == other.lat && lon == other.lon
                    && Objects.equals(profile == null ? null : profile.json,
                            other.profile == null ? null : other.profile.json);
        }

        boolean requiresVpn() {
            return on && profile != null && profile.vpn;
        }
    }

    private static final Config OFF = new Config(false, 0, 0, Collections.emptySet(), null);

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
        sHandler = handler;
        final ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                    int userId) {
                final Config config = read(context, userId);
                final Config previous;
                synchronized (sLock) {
                    previous = sConfigByUser.get(userId);
                    sConfigByUser.put(userId, config);
                }
                android.app.AppOpsManager.invalidateAppOpModeCache();
                // The settings before the first change of a burst decide who is restarted.
                if (previous != null && sConfigBeforeChange.get(userId) == null) {
                    sConfigBeforeChange.put(userId, previous);
                }
                scheduleApply(userId);
            }
        };
        for (String key : new String[] {KEY_ON, KEY_FAKE, KEY_REAL_PKGS, KEY_PROFILE}) {
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
                // An installed or removed app changes the uid set the VPN rules cover.
                scheduleApply(uid >= 0 ? UserHandle.getUserId(uid) : UserHandle.USER_ALL);
            }
        }, UserHandle.ALL, filter, null, handler);
        final IntentFilter users = new IntentFilter(Intent.ACTION_USER_ADDED);
        users.addAction(Intent.ACTION_USER_REMOVED);
        users.addAction(Intent.ACTION_USER_SWITCHED);
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                final String action = intent.getAction();
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL);
                if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    synchronized (sLock) {
                        sConfigByUser.delete(userId);
                    }
                    sSpoofedUidsByUser.delete(userId);
                    sDirtyUsers.delete(userId);
                    sConfigBeforeChange.delete(userId);
                    applyVpn();
                } else if (Intent.ACTION_USER_ADDED.equals(action)
                        && userId != UserHandle.USER_NULL) {
                    final Config config = read(context, userId);
                    synchronized (sLock) {
                        sConfigByUser.put(userId, config);
                    }
                    scheduleApply(userId);
                } else {
                    scheduleApply(UserHandle.USER_ALL);
                }
            }
        }, UserHandle.ALL, users, null, handler);
        // Every user now, so the first delivery doesn't read Settings under a lock, and the VPN
        // rules are in place before third-party apps start.
        handler.post(() -> {
            final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
            final int[] userIds = umi != null ? umi.getUserIds()
                    : new int[] {ActivityManager.getCurrentUser()};
            for (int userId : userIds) {
                final Config config = read(context, userId);
                synchronized (sLock) {
                    sConfigByUser.put(userId, config);
                }
                sDirtyUsers.put(userId, true);
            }
            applyPending();
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

    /**
     * For ILocationManager.getIchthysSpoofProfile: the profile JSON for the calling uid when it
     * is spoofed ("{}" when the fake place is on without a profile), else null.
     */
    @Nullable
    public static String getProfileForCaller() {
        return getProfileForUid(Binder.getCallingUid());
    }

    /** The profile JSON for [uid] when it is spoofed ("{}" without a profile), else null. */
    @Nullable
    public static String getProfileForUid(int uid) {
        final Profile profile = getSpoofForUid(uid);
        return profile == null ? null : profile.json;
    }

    /** The parsed profile for the calling uid when it is spoofed, else null. */
    @Nullable
    public static Profile getSpoofForCaller() {
        return getSpoofForUid(Binder.getCallingUid());
    }

    /**
     * The parsed profile for [uid] when it is spoofed ({@link Profile#EMPTY} when the fake place
     * is on without a profile), else null. May read Settings (not for delivery paths).
     */
    @Nullable
    public static Profile getSpoofForUid(int uid) {
        if (Process.isSdkSandboxUid(uid)) {
            uid = Process.getAppUidForSdkSandboxUid(uid);
        }
        if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            return null;
        }
        final Context context;
        synchronized (sLock) {
            context = sContext;
        }
        if (context == null) {
            return null;
        }
        final String[] packages = packagesForUid(context, uid);
        if (packages == null || packages.length == 0 || !isSpoofed(packages[0], uid, true)) {
            return null;
        }
        final Config config;
        synchronized (sLock) {
            config = sConfigByUser.get(UserHandle.getUserId(uid));
        }
        if (config == null || !config.on) {
            return null;
        }
        return config.profile != null ? config.profile : Profile.EMPTY;
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
            final Profile profile = Profile.parse(Settings.Secure.getStringForUser(
                    context.getContentResolver(), KEY_PROFILE, userId));
            return new Config(true, lat, lon, real, profile);
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

    // ---------------------------------------------------------------------------------------
    // VPN rules and restarts (handler thread only)

    /** Marks [userId] (or every cached user) for the next apply, a little later. */
    private static void scheduleApply(int userId) {
        if (sHandler == null) {
            return;
        }
        if (userId == UserHandle.USER_ALL) {
            synchronized (sLock) {
                for (int i = 0; i < sConfigByUser.size(); i++) {
                    sDirtyUsers.put(sConfigByUser.keyAt(i), true);
                }
            }
        } else {
            sDirtyUsers.put(userId, true);
        }
        sHandler.removeCallbacks(sApply);
        sHandler.postDelayed(sApply, SETTLE_MS);
    }

    /**
     * Works out the spoofed uids of every dirty user, puts the VPN rules in place and then
     * restarts the apps whose spoofing changed: whatever they read is cached in their process.
     * A change of the place, the on/off state or the profile restarts every app spoofed before or
     * after it; a change of the real-location list only the apps whose state flipped; package
     * and user events none.
     */
    private static void applyPending() {
        final Context context;
        synchronized (sLock) {
            context = sContext;
        }
        if (context == null) {
            return;
        }
        final ArraySet<Integer> toKill = new ArraySet<>();
        for (int i = 0; i < sDirtyUsers.size(); i++) {
            final int userId = sDirtyUsers.keyAt(i);
            final Config config;
            synchronized (sLock) {
                config = sConfigByUser.get(userId);
            }
            if (config == null) {
                continue; // removed meanwhile
            }
            final int[] before = sSpoofedUidsByUser.get(userId);
            final int[] after = config.on ? spoofedUids(context, userId, config) : new int[0];
            sSpoofedUidsByUser.put(userId, after);
            final Config previous = sConfigBeforeChange.get(userId);
            sConfigBeforeChange.delete(userId);
            if (previous == null || before == null) {
                continue;
            }
            if (previous.sameValues(config)) {
                for (int uid : before) {
                    if (Arrays.binarySearch(after, uid) < 0) toKill.add(uid);
                }
                for (int uid : after) {
                    if (Arrays.binarySearch(before, uid) < 0) toKill.add(uid);
                }
            } else {
                for (int uid : before) toKill.add(uid);
                for (int uid : after) toKill.add(uid);
            }
        }
        sDirtyUsers.clear();
        applyVpn();
        if (toKill.isEmpty()) {
            return;
        }
        Slog.i(TAG, "Restarting " + toKill.size() + " spoofed app uid(s)");
        final long token = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < toKill.size(); i++) {
                final int uid = toKill.valueAt(i);
                try {
                    ActivityManager.getService().killUid(UserHandle.getAppId(uid),
                            UserHandle.getUserId(uid), KILL_REASON);
                } catch (RemoteException | RuntimeException e) {
                    Slog.w(TAG, "Cannot restart uid " + uid, e);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * The uids of [userId] spoofed under [config], sorted: every installed package the rule in
     * {@link #isSpoofed} spoofs, collected by uid (a real-location sibling frees its shared uid).
     */
    private static int[] spoofedUids(Context context, int userId, Config config) {
        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        if (pmi == null) {
            return new int[0];
        }
        final List<ApplicationInfo> apps;
        final long token = Binder.clearCallingIdentity();
        try {
            apps = pmi.getInstalledApplications(0, userId, Process.SYSTEM_UID);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (apps == null) {
            return new int[0];
        }
        final SparseBooleanArray spoofed = new SparseBooleanArray();
        final SparseBooleanArray real = new SparseBooleanArray();
        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null
                    || UserHandle.getAppId(ai.uid) < Process.FIRST_APPLICATION_UID) {
                continue;
            }
            if (config.real.contains(ai.packageName)) {
                real.put(ai.uid, true);
                continue;
            }
            final boolean system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (system && !BROKERS.contains(ai.packageName)) {
                continue;
            }
            spoofed.put(ai.uid, true);
        }
        final int[] uids = new int[spoofed.size()];
        int n = 0;
        for (int i = 0; i < spoofed.size(); i++) {
            final int uid = spoofed.keyAt(i); // ascending
            if (!real.get(uid)) {
                uids[n++] = uid;
            }
        }
        return Arrays.copyOf(uids, n);
    }

    /**
     * "Fake IP": requires a VPN for every spoofed uid (and its SDK sandbox uid, which has a uid of
     * its own) of every user whose profile says so, and releases the uids that no longer need
     * one. The old set is released before the new one is required, so a uid in both is never
     * doubled in ConnectivityService's list.
     */
    private static void applyVpn() {
        final Context context;
        synchronized (sLock) {
            context = sContext;
        }
        if (context == null || sHandler == null) {
            return;
        }
        final ArrayList<Range<Integer>> ranges = new ArrayList<>();
        for (int i = 0; i < sSpoofedUidsByUser.size(); i++) {
            final Config config;
            synchronized (sLock) {
                config = sConfigByUser.get(sSpoofedUidsByUser.keyAt(i));
            }
            if (config == null || !config.requiresVpn()) {
                continue;
            }
            for (int uid : sSpoofedUidsByUser.valueAt(i)) {
                ranges.add(new Range<>(uid, uid));
                if (Process.isApplicationUid(uid)) {
                    final int sandbox = Process.toSdkSandboxUid(uid);
                    ranges.add(new Range<>(sandbox, sandbox));
                }
            }
        }
        sHandler.removeCallbacks(sVpnRetry);
        if (ranges.equals(sVpnRanges)) {
            return;
        }
        final ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            Slog.w(TAG, "ConnectivityManager unavailable; VPN rules pending");
            sHandler.postDelayed(sVpnRetry, VPN_RETRY_MS);
            return;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            if (!sVpnRanges.isEmpty()) {
                cm.setRequireVpnForUids(false, sVpnRanges);
            }
            if (!ranges.isEmpty()) {
                cm.setRequireVpnForUids(true, ranges);
            }
            sVpnRanges = ranges;
            Slog.i(TAG, "VPN required for " + ranges.size() + " uid range(s)");
        } catch (RuntimeException e) {
            Slog.e(TAG, "Cannot update the VPN rules", e);
            sHandler.postDelayed(sVpnRetry, VPN_RETRY_MS);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
