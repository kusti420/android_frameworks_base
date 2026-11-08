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

package com.android.server.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.INotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * IchthysOS Guard decision 36, accessibility scoping: per accessibility service, which apps'
 * windows and events it may see.
 *
 * <p>By default a third-party (non-system) accessibility service never sees Settings, the
 * permission dialogs, the lock screen and SystemUI's credential screens, password managers,
 * authenticators, the credential manager and banking/payment apps. Password managers are the
 * curated list below plus whatever the user picked as autofill service or credential provider;
 * banking apps are the curated list plus package names with a "bank"/"pank" segment.
 *
 * <p>Settings.Secure {@link #SETTING} (per user) holds the user's choices as JSON:
 * <pre>
 * {"com.example/com.example.Service": {"allow": ["pkg", ...], "deny": ["pkg", ...]}}
 * </pre>
 * "allow" lifts the default protection for those apps, "deny" hides further apps. Both lists
 * apply to any service, system ones included; the default protection only to third-party ones.
 * The Settings page (com.android.settings.ichthys.protect) repeats the default lists; keep
 * them in sync.
 *
 * <p>All methods are called with the accessibility lock held and only read caches; the
 * settings are re-read lazily after a content observer marks them stale.
 */
final class IchthysA11yScopes {
    private static final String LOG_TAG = "IchthysA11yScopes";

    /** Settings.Secure key, per user: the JSON map described above. */
    static final String SETTING = "ichthys_a11y_scopes";

    static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /** Settings, Guard (inside Settings) and the permission dialogs. */
    private static final String[] SETTINGS_PACKAGES = {
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.credentialmanager",
    };

    /** Password managers and authenticators. */
    private static final String[] PASSWORD_MANAGERS = {
            "com.x8bit.bitwarden",
            "com.bitwarden.authenticator",
            "com.agilebits.onepassword",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "com.callpod.android_apps.keeper",
            "proton.android.pass",
            "keepass2android.keepass2android",
            "keepass2android.keepass2android_nonet",
            "com.kunzisoft.keepass.free",
            "com.kunzisoft.keepass.libre",
            "com.kunzisoft.keepass.pro",
            "io.enpass.app",
            "com.beemdevelopment.aegis",
            "org.fedorahosted.freeotp",
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.authy.authy",
            "ee.sk.smartid",
    };

    /** Banking and payment apps that the "bank" name rule below does not catch. */
    private static final String[] PAYMENT_APPS = {
            "com.revolut.revolut",
            "com.transferwise.android",
            "com.paypal.android.p2pmobile",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user",
            "de.number26.android",
            "co.uk.getmondo",
            "com.klarna.android",
            "se.bankgirot.swish",
            "com.squareup.cash",
            "com.venmo",
            "com.coinbase.android",
            "com.binance.dev",
    };

    /** Package name segments that mark banking apps ("pank" is Estonian for bank). */
    private static final String[] BANK_SEGMENT_WORDS = {"bank", "pank", "banking"};

    private static final ArraySet<String> DEFAULT_PROTECTED = new ArraySet<>();
    static {
        for (String pkg : SETTINGS_PACKAGES) DEFAULT_PROTECTED.add(pkg);
        for (String pkg : PASSWORD_MANAGERS) DEFAULT_PROTECTED.add(pkg);
        for (String pkg : PAYMENT_APPS) DEFAULT_PROTECTED.add(pkg);
    }

    /** The user's choice for one service. */
    private static final class Scope {
        final ArraySet<String> mAllowed = new ArraySet<>();
        final ArraySet<String> mDenied = new ArraySet<>();
    }

    /** Everything read from settings for one user. */
    private static final class UserState {
        final ArrayMap<ComponentName, Scope> mScopes = new ArrayMap<>();
        /** Default autofill service and credential providers. */
        final ArraySet<String> mCredentialProviders = new ArraySet<>();
    }

    private final Context mContext;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseArray<UserState> mUserStates = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, Boolean> mBankNameCache = new ArrayMap<>();
    @GuardedBy("mLock")
    private boolean mObserving;

    /**
     * Packages with an active notification (current user, its profiles, all users), for the
     * notification shade and heads-up (attack-surface sweep A3). Read on the background thread:
     * NotificationManagerService sends accessibility events under its own lock, so it must not
     * be called under the accessibility lock.
     */
    @GuardedBy("mLock")
    private ArraySet<String> mNotificationPackages = new ArraySet<>();
    /** Uptime at which the last successful read of {@link #mNotificationPackages} started. */
    @GuardedBy("mLock")
    private long mNotificationsReadUptime;
    /** Uptime of the last sign that the notifications changed (a posting event). */
    @GuardedBy("mLock")
    private long mNotificationsChangedUptime;
    @GuardedBy("mLock")
    private boolean mNotificationReadPending;
    /** Uptime at which the last read was posted: reads are at least this far apart. */
    @GuardedBy("mLock")
    private long mNotificationReadPostedUptime;
    private static final long NOTIFICATION_READ_MIN_INTERVAL_MS = 200;

    IchthysA11yScopes(@NonNull Context context) {
        mContext = context;
    }

    /** True for services the default protection applies to. */
    static boolean isThirdPartyService(@Nullable AccessibilityServiceInfo info) {
        if (info == null) {
            return false;
        }
        final ResolveInfo resolveInfo = info.getResolveInfo();
        if (resolveInfo == null || resolveInfo.serviceInfo == null
                || resolveInfo.serviceInfo.applicationInfo == null) {
            return false;
        }
        return !resolveInfo.serviceInfo.applicationInfo.isSystemApp();
    }

    /**
     * Whether {@code service} must not see windows or events of {@code targetPackage}.
     *
     * @param systemUiSensitive for SystemUI, whether the window or event belongs to the lock
     *        screen or a credential screen (SystemUI is otherwise visible: shade, toasts, ...).
     */
    boolean isHidden(@Nullable ComponentName service, boolean thirdPartyService,
            @Nullable String targetPackage, int userId, boolean systemUiSensitive) {
        if (service == null || TextUtils.isEmpty(targetPackage)
                || targetPackage.equals(service.getPackageName())) {
            return false;
        }
        final UserState state = getUserState(userId);
        final Scope scope = state.mScopes.get(service);
        if (scope != null) {
            if (scope.mDenied.contains(targetPackage)) {
                return true;
            }
            if (scope.mAllowed.contains(targetPackage)) {
                return false;
            }
        }
        if (!thirdPartyService) {
            return false;
        }
        if (SYSTEMUI_PACKAGE.equals(targetPackage)) {
            return systemUiSensitive;
        }
        return DEFAULT_PROTECTED.contains(targetPackage)
                || state.mCredentialProviders.contains(targetPackage)
                || isBankName(targetPackage);
    }

    /**
     * Whether {@code service} may have anything hidden from it at all: the default protection
     * (third-party services) or a "deny" list. The fail-closed paths (windows whose owner can't
     * be told, a stale notification list) only apply then, so an unrestricted service never
     * loses anything to them.
     */
    boolean hidesAnything(@Nullable ComponentName service, boolean thirdPartyService,
            int userId) {
        if (service == null) {
            return false;
        }
        if (thirdPartyService) {
            return true;
        }
        final Scope scope = getUserState(userId).mScopes.get(service);
        return scope != null && !scope.mDenied.isEmpty();
    }

    /** A notification was posted (TYPE_NOTIFICATION_STATE_CHANGED): read the list again. */
    void onNotificationPosted() {
        synchronized (mLock) {
            mNotificationsChangedUptime = SystemClock.uptimeMillis();
            scheduleNotificationReadLocked();
        }
    }

    /**
     * The packages with an active notification, or null while that list may be older than
     * {@code shownSinceUptime} (when the shade or heads-up appeared or changed) or than the last
     * posted notification. A fresh read is then on its way; callers treat null as "hidden".
     */
    @Nullable
    ArraySet<String> getNotificationPackages(long shownSinceUptime) {
        synchronized (mLock) {
            final long since = Math.max(shownSinceUptime, mNotificationsChangedUptime);
            if (mNotificationsReadUptime == 0 || mNotificationsReadUptime <= since) {
                scheduleNotificationReadLocked();
                return null;
            }
            return mNotificationPackages;
        }
    }

    @GuardedBy("mLock")
    private void scheduleNotificationReadLocked() {
        if (mNotificationReadPending) {
            return;
        }
        mNotificationReadPending = true;
        final long now = SystemClock.uptimeMillis();
        final long delay = Math.max(0,
                mNotificationReadPostedUptime + NOTIFICATION_READ_MIN_INTERVAL_MS - now);
        mNotificationReadPostedUptime = now + delay;
        BackgroundThread.getHandler().postDelayed(this::readNotificationPackages, delay);
    }

    /** On the background thread, no accessibility lock held. */
    private void readNotificationPackages() {
        final long start = SystemClock.uptimeMillis();
        ArraySet<String> packages = null;
        final long token = Binder.clearCallingIdentity();
        try {
            final INotificationManager nm = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
            if (nm != null) {
                final StatusBarNotification[] active =
                        nm.getActiveNotificationsWithAttribution(mContext.getOpPackageName(),
                                null);
                packages = new ArraySet<>();
                if (active != null) {
                    for (StatusBarNotification sbn : active) {
                        if (sbn != null && sbn.getPackageName() != null) {
                            packages.add(sbn.getPackageName());
                        }
                    }
                }
            }
        } catch (RemoteException | RuntimeException e) {
            Slog.w(LOG_TAG, "Failed to read the active notifications", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        synchronized (mLock) {
            mNotificationReadPending = false;
            if (packages != null) {
                mNotificationPackages = packages;
                mNotificationsReadUptime = start;
            }
        }
    }

    private boolean isBankName(String packageName) {
        synchronized (mLock) {
            final Boolean cached = mBankNameCache.get(packageName);
            if (cached != null) {
                return cached;
            }
            boolean bank = false;
            for (String segment : packageName.toLowerCase(Locale.ROOT).split("\\.")) {
                for (String word : BANK_SEGMENT_WORDS) {
                    if (segment.equals(word) || segment.startsWith(word)
                            || segment.endsWith(word)) {
                        bank = true;
                        break;
                    }
                }
                if (bank) break;
            }
            if (mBankNameCache.size() > 512) {
                mBankNameCache.clear();
            }
            mBankNameCache.put(packageName, bank);
            return bank;
        }
    }

    private UserState getUserState(int userId) {
        synchronized (mLock) {
            ensureObservingLocked();
            UserState state = mUserStates.get(userId);
            if (state == null) {
                state = readUserState(userId);
                mUserStates.put(userId, state);
            }
            return state;
        }
    }

    @GuardedBy("mLock")
    private void ensureObservingLocked() {
        if (mObserving) {
            return;
        }
        mObserving = true;
        final Handler handler = BackgroundThread.getHandler();
        final ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                    int userId) {
                synchronized (mLock) {
                    mUserStates.remove(userId);
                }
            }
        };
        final long token = Binder.clearCallingIdentity();
        try {
            for (String key : new String[] {SETTING, Settings.Secure.AUTOFILL_SERVICE,
                    Settings.Secure.CREDENTIAL_SERVICE,
                    Settings.Secure.CREDENTIAL_SERVICE_PRIMARY}) {
                mContext.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(key), false, observer,
                        UserHandle.USER_ALL);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private UserState readUserState(int userId) {
        final UserState state = new UserState();
        final long token = Binder.clearCallingIdentity();
        try {
            final String json = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(), SETTING, userId);
            if (!TextUtils.isEmpty(json)) {
                parseScopes(json, state);
            }
            addProviderPackages(Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.AUTOFILL_SERVICE, userId), state);
            addProviderPackages(Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.CREDENTIAL_SERVICE, userId), state);
            addProviderPackages(Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.CREDENTIAL_SERVICE_PRIMARY, userId), state);
        } catch (RuntimeException e) {
            Slog.w(LOG_TAG, "Failed to read accessibility scopes for user " + userId, e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return state;
    }

    private static void parseScopes(String json, UserState state) {
        try {
            final JSONObject root = new JSONObject(json);
            final JSONArray names = root.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                final String key = names.getString(i);
                final ComponentName service = ComponentName.unflattenFromString(key);
                final JSONObject entry = root.optJSONObject(key);
                if (service == null || entry == null) {
                    continue;
                }
                final Scope scope = new Scope();
                addAll(entry.optJSONArray("allow"), scope.mAllowed);
                addAll(entry.optJSONArray("deny"), scope.mDenied);
                state.mScopes.put(service, scope);
            }
        } catch (JSONException e) {
            Slog.w(LOG_TAG, "Ignoring malformed " + SETTING, e);
        }
    }

    private static void addAll(@Nullable JSONArray array, Set<String> out) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            final String pkg = array.optString(i, null);
            if (!TextUtils.isEmpty(pkg)) {
                out.add(pkg);
            }
        }
    }

    /** Settings values holding one or more flattened components, separated by ':'. */
    private static void addProviderPackages(@Nullable String value, UserState state) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        for (String flat : value.split(":")) {
            final ComponentName component = ComponentName.unflattenFromString(flat);
            if (component != null) {
                state.mCredentialProviders.add(component.getPackageName());
            }
        }
    }
}
