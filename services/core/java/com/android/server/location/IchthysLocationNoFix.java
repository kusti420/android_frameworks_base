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
import android.app.ActivityManager;
import android.content.Context;
import android.database.ContentObserver;
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

import com.android.internal.annotations.GuardedBy;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * IchthysOS Guard decision 10, "No location": packages listed in Settings.Secure
 * {@link #SETTING} (comma-separated, per user) keep their location permission, and their
 * requests stay registered so providers keep searching, but no fix is ever delivered to them,
 * just like being indoors without signal. getLastKnownLocation() answers null,
 * getCurrentLocation() times out with null, and listeners never fire.
 *
 * <p>Delivery paths consult {@link #isNoFix} while holding the multiplexer lock, so it only
 * reads an in-memory cache; the cache is refilled from a content observer on a handler thread.
 */
public final class IchthysLocationNoFix {
    private static final String TAG = "IchthysLocationNoFix";

    /** Settings.Secure key, per user: packages that never receive a location fix. */
    public static final String SETTING = "ichthys_location_nofix_pkgs";

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static Context sContext;

    /** userId -> packages; a missing entry means "not read yet". */
    @GuardedBy("sLock")
    private static final SparseArray<Set<String>> sNoFixByUser = new SparseArray<>();

    private IchthysLocationNoFix() {}

    /** Starts watching the setting. Called once the settings provider is up. */
    public static void init(Context context, Handler handler) {
        synchronized (sLock) {
            sContext = context;
            sNoFixByUser.clear();
        }
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SETTING), false,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange, Collection<Uri> uris,
                            int flags, int userId) {
                        Set<String> value = read(context, userId);
                        synchronized (sLock) {
                            sNoFixByUser.put(userId, value);
                        }
                        // IchthysOS: AppOpsService answers location ops from this list too;
                        // drop the clients' cached op modes so the change applies at once.
                        android.app.AppOpsManager.invalidateAppOpModeCache();
                    }
                }, UserHandle.USER_ALL);
        // Pre-warm the current user, so the first delivery doesn't read Settings while holding
        // the multiplexer lock.
        final int userId = ActivityManager.getCurrentUser();
        Set<String> value = read(context, userId);
        synchronized (sLock) {
            if (sNoFixByUser.get(userId) == null) {
                sNoFixByUser.put(userId, value);
            }
        }
    }

    /**
     * True when location deliveries to this caller must be withheld. The system and platform
     * uids are never affected.
     */
    public static boolean isNoFix(@Nullable CallerIdentity identity) {
        if (identity == null || identity.getPackageName() == null
                || UserHandle.getAppId(identity.getUid()) < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        if (isNoFix(identity.getPackageName(), identity.getUserId())) {
            return true;
        }
        // IchthysOS: and by the uid's other packages: with a shared uid, a listed app could name
        // an unlisted sibling package (it only has to belong to the uid).
        final Context context;
        synchronized (sLock) {
            context = sContext;
        }
        if (context == null) {
            return false;
        }
        final String[] packages;
        final long token = Binder.clearCallingIdentity();
        try {
            packages = context.getPackageManager().getPackagesForUid(identity.getUid());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (packages == null || packages.length < 2) {
            return false;
        }
        for (String pkg : packages) {
            if (!pkg.equals(identity.getPackageName()) && isNoFix(pkg, identity.getUserId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * IchthysOS: like {@link #isNoFix(String, int)} but never reads Settings: for callers that may
     * hold locks SettingsProvider also takes (AppOpsService). A user not cached yet reads as
     * false; the current user is cached at init and every user once its list changes or its
     * first location delivery happens.
     */
    public static boolean isNoFixCached(String packageName, int userId) {
        synchronized (sLock) {
            Set<String> cached = sNoFixByUser.get(userId);
            return cached != null && cached.contains(packageName);
        }
    }

    /** True when {@code packageName} is set to "No location" for {@code userId}. */
    public static boolean isNoFix(String packageName, int userId) {
        Context context;
        synchronized (sLock) {
            if (sContext == null) {
                return false;
            }
            Set<String> cached = sNoFixByUser.get(userId);
            if (cached != null) {
                return cached.contains(packageName);
            }
            context = sContext;
        }
        // First query for this user: read once, then the observer keeps it fresh.
        Set<String> value = read(context, userId);
        synchronized (sLock) {
            if (sNoFixByUser.get(userId) == null) {
                sNoFixByUser.put(userId, value);
            }
            return sNoFixByUser.get(userId).contains(packageName);
        }
    }

    private static Set<String> read(Context context, int userId) {
        final long token = Binder.clearCallingIdentity();
        try {
            String value = Settings.Secure.getStringForUser(context.getContentResolver(),
                    SETTING, userId);
            if (TextUtils.isEmpty(value)) {
                return Collections.emptySet();
            }
            ArraySet<String> packages = new ArraySet<>();
            for (String pkg : value.split(",")) {
                pkg = pkg.trim();
                if (!pkg.isEmpty()) {
                    packages.add(pkg);
                }
            }
            return packages;
        } catch (RuntimeException e) {
            // Called from the delivery paths: never let a failed read take them down.
            Slog.w(TAG, "Cannot read " + SETTING + " for user " + userId, e);
            return Collections.emptySet();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
