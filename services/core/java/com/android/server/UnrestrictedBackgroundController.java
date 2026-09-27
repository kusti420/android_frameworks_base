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

package com.android.server;

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IDeviceIdleController;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.FeatureFlagUtils;
import android.util.IntArray;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.server.am.IchthysBackgroundExemptions;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Set;

/**
 * IchthysOS "Unrestricted background" (kusti420/ichthysos-tracker#39).
 *
 * <p>Modes, from Settings.Global {@link #KEY_MODE}:
 * <ul>
 * <li><b>Off</b> (0).
 * <li><b>Selected apps</b> (1): each package in {@link #KEY_PKGS} gets a Doze / battery
 *     optimization allowlist entry (which also puts it in the EXEMPTED standby bucket), is kept
 *     at perceptible OOM importance, is never frozen, and has no FGS time limits. The rest of the
 *     phone keeps normal battery management.
 * <li><b>Everything</b> (2): the above for the selected apps, plus Doze off (deep and light),
 *     app standby off, the cached-apps freezer off, the phantom process killer off, every app
 *     treated as allowlisted for background starts, and no FGS time limits for any app.
 * </ul>
 *
 * <p>Packages are handled by app id, so a selected app is covered in every user it's installed
 * in; the keys are Global, so every user sees and edits the same state.
 *
 * <p>Bookkeeping: before changing anything, the original value of every global setting it
 * touches is saved in {@link #KEY_SNAPSHOT} and every allowlist entry it adds is recorded in
 * {@link #KEY_ADDED}. Turning a mode off restores exactly those values and removes only those
 * entries, so allowlist entries the user made themselves survive. {@link #KEY_APPLIED} records
 * the mode actually in effect. The mode doesn't survive a reboot unless {@link #KEY_PERSIST} is
 * set. All work runs on the background thread.
 */
public final class UnrestrictedBackgroundController {
    private static final String TAG = "UnrestrictedBackground";

    // User-facing Settings.Global keys (written by crDroidSettings).
    static final String KEY_MODE = "ichthys_unrestricted_bg_mode";
    static final String KEY_PKGS = "ichthys_unrestricted_bg_pkgs";
    static final String KEY_PERSIST = "ichthys_unrestricted_bg_persist";
    // Internal bookkeeping (Settings.Global).
    private static final String KEY_APPLIED = "ichthys_unrestricted_bg_applied";
    private static final String KEY_ADDED = "ichthys_unrestricted_bg_added";
    private static final String KEY_SNAPSHOT = "ichthys_unrestricted_bg_snapshot";
    private static final String KEY_MIGRATED = "ichthys_unrestricted_bg_migrated";

    // Pre-rework Settings.Secure keys (user 0), migrated once.
    private static final String LEGACY_ENABLED = "unrestricted_background";
    private static final String LEGACY_PERSIST = "unrestricted_background_persist";
    private static final String LEGACY_ADDED = "unrestricted_background_added_pkgs";

    static final int MODE_OFF = 0;
    static final int MODE_SELECTED = 1;
    static final int MODE_EVERYTHING = 2;

    // What "Everything" changes, besides Doze. Global settings: name -> value while applied.
    private static final String[][] GLOBAL_OVERRIDES = {
        {Settings.Global.APP_STANDBY_ENABLED, "0"},
        {Settings.Global.CACHED_APPS_FREEZER_ENABLED, "disabled"},
        // Settings.Global wins over the sysprop/default in FeatureFlagUtils.isEnabled.
        {FeatureFlagUtils.SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS, "false"},
    };
    // ActivityManager only trims phantom processes above this DeviceConfig cap.
    private static final String PHANTOM_CAP = "max_phantom_processes";
    private static final String SNAP_PHANTOM_CAP = "deviceconfig:" + PHANTOM_CAP;
    private static final String SNAP_DEEP = "doze:deep";
    private static final String SNAP_LIGHT = "doze:light";

    private final Context mContext;
    private final Handler mHandler;
    private IDeviceIdleController mIdleController;

    public UnrestrictedBackgroundController(Context context) {
        mContext = context;
        mHandler = BackgroundThread.getHandler();
    }

    /** Called once the system is ready. All work happens later on the background thread. */
    public void onBootPhaseReady() {
        mHandler.post(this::init);
    }

    private void init() {
        migrateLegacy();

        if (getGlobalInt(KEY_MODE, MODE_OFF) != MODE_OFF
                && getGlobalInt(KEY_PERSIST, 0) != 1) {
            // Non-persistent: a reboot returns the phone to normal battery management. The
            // reconcile below undoes whatever survived the reboot (settings, allowlist entries).
            Slog.i(TAG, "non-persistent mode -> off after reboot");
            putGlobalInt(KEY_MODE, MODE_OFF);
        }
        reconcile();

        final ContentObserver modeObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                reconcile();
            }
        };
        registerGlobalObserver(KEY_MODE, modeObserver);
        registerGlobalObserver(KEY_PKGS, modeObserver);

        // Something else (RescueParty, adb, another settings screen) reverting what "Everything"
        // changed: put it back while the mode is on.
        final ContentObserver revertObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                reenforceIfEverything();
            }
        };
        for (String[] override : GLOBAL_OVERRIDES) {
            registerGlobalObserver(override[0], revertObserver);
        }
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                new HandlerExecutor(mHandler), properties -> {
                    if (properties.getKeyset().contains(PHANTOM_CAP)) {
                        reenforceIfEverything();
                    }
                });

        // Selected apps installed / reinstalled / removed while a mode is on.
        final IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addDataScheme("package");
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    return; // an update: nothing changes for us
                }
                final Uri data = intent.getData();
                final String pkg = data != null ? data.getSchemeSpecificPart() : null;
                if (pkg != null && selectedPackages().contains(pkg)) {
                    reconcile();
                }
            }
        }, packages, null, mHandler);

        // "Everything" never overrides an app the user set to Restricted battery usage.
        mContext.getSystemService(AppOpsManager.class).startWatchingMode(
                AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, null,
                (op, pkg) -> mHandler.post(this::refreshUserRestricted));
    }

    /** Apps whose RUN_ANY_IN_BACKGROUND app-op isn't allowed, i.e. set to "Restricted". */
    private void refreshUserRestricted() {
        final IntArray appIds = new IntArray();
        try {
            final List<AppOpsManager.PackageOps> ops = mContext.getSystemService(
                    AppOpsManager.class).getPackagesForOps(
                            new int[] {AppOpsManager.OP_RUN_ANY_IN_BACKGROUND});
            if (ops != null) {
                for (AppOpsManager.PackageOps pkgOps : ops) {
                    for (AppOpsManager.OpEntry entry : pkgOps.getOps()) {
                        if (entry.getMode() != AppOpsManager.MODE_ALLOWED) {
                            appIds.add(UserHandle.getAppId(pkgOps.getUid()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "could not read restricted apps", e);
        }
        IchthysBackgroundExemptions.setUserRestricted(appIds.toArray());
    }

    /** Brings every effect in line with the requested mode. Idempotent. */
    private synchronized void reconcile() {
        final int mode = getGlobalInt(KEY_MODE, MODE_OFF);
        try {
            refreshUserRestricted();
            if (mode == MODE_EVERYTHING) {
                applyEverything();
            } else {
                restoreEverything();
            }
            final IntArray appIds = syncAllowlist(mode == MODE_OFF
                    ? new ArraySet<String>() : installedSelectedPackages());
            IchthysBackgroundExemptions.set(mode == MODE_EVERYTHING, appIds.toArray());
            final ActivityManagerInternal ami =
                    LocalServices.getService(ActivityManagerInternal.class);
            if (ami != null) {
                // Re-rank (and unfreeze) processes against the new exemptions now.
                ami.updateOomAdj(ActivityManagerInternal.OOM_ADJ_REASON_ALLOWLIST);
            }
            putGlobalInt(KEY_APPLIED, mode);
            Slog.i(TAG, "mode " + mode + " in effect, " + appIds.size() + " selected apps");
        } catch (Exception e) {
            Slog.e(TAG, "reconcile to mode " + mode + " failed", e);
        }
    }

    // ---- "Everything" --------------------------------------------------------------------------

    private void applyEverything() throws JSONException {
        if (getGlobalString(KEY_SNAPSHOT) == null) {
            // Save the originals before the first change, so a crash half-way still restores.
            final JSONObject snapshot = new JSONObject();
            for (String[] override : GLOBAL_OVERRIDES) {
                snapshot.put(override[0], jsonValue(getGlobalString(override[0])));
            }
            snapshot.put(SNAP_PHANTOM_CAP, jsonValue(DeviceConfig.getProperty(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, PHANTOM_CAP)));
            final DeviceIdleInternal idle = LocalServices.getService(DeviceIdleInternal.class);
            snapshot.put(SNAP_DEEP, idle == null || idle.isDeepIdleModeEnabled());
            snapshot.put(SNAP_LIGHT, idle == null || idle.isLightIdleModeEnabled());
            putGlobalString(KEY_SNAPSHOT, snapshot.toString());
        }
        enforceEverything();
    }

    /** Sets every "Everything" override that isn't in effect. Writes only on a difference. */
    private void enforceEverything() {
        for (String[] override : GLOBAL_OVERRIDES) {
            if (!override[1].equals(getGlobalString(override[0]))) {
                putGlobalString(override[0], override[1]);
            }
        }
        final String cap = Integer.toString(Integer.MAX_VALUE);
        if (!cap.equals(DeviceConfig.getProperty(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, PHANTOM_CAP))) {
            try {
                DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, PHANTOM_CAP,
                        cap, false);
            } catch (Exception e) {
                Slog.w(TAG, "could not raise " + PHANTOM_CAP, e);
            }
        }
        final DeviceIdleInternal idle = LocalServices.getService(DeviceIdleInternal.class);
        if (idle != null && (idle.isDeepIdleModeEnabled() || idle.isLightIdleModeEnabled())) {
            idle.setIdleModesEnabled(false, false);
        }
    }

    private void restoreEverything() throws JSONException {
        final String stored = getGlobalString(KEY_SNAPSHOT);
        if (stored == null) {
            return; // nothing of ours in effect
        }
        final JSONObject snapshot = new JSONObject(stored);
        for (String[] override : GLOBAL_OVERRIDES) {
            if (snapshot.has(override[0])) {
                putGlobalString(override[0], stringValue(snapshot, override[0]));
            }
        }
        if (snapshot.has(SNAP_PHANTOM_CAP)) {
            final String cap = stringValue(snapshot, SNAP_PHANTOM_CAP);
            try {
                if (cap == null) {
                    DeviceConfig.deleteProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                            PHANTOM_CAP);
                } else {
                    DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                            PHANTOM_CAP, cap, false);
                }
            } catch (Exception e) {
                Slog.w(TAG, "could not restore " + PHANTOM_CAP, e);
            }
        }
        final DeviceIdleInternal idle = LocalServices.getService(DeviceIdleInternal.class);
        if (idle != null) {
            // After a reboot Doze is back at the config default already; this is then a no-op.
            idle.setIdleModesEnabled(snapshot.optBoolean(SNAP_DEEP, true),
                    snapshot.optBoolean(SNAP_LIGHT, true));
        }
        putGlobalString(KEY_SNAPSHOT, null);
    }

    private synchronized void reenforceIfEverything() {
        if (getGlobalInt(KEY_APPLIED, MODE_OFF) == MODE_EVERYTHING
                && getGlobalString(KEY_SNAPSHOT) != null) {
            enforceEverything();
        }
    }

    // ---- Selected apps ---------------------------------------------------------------------------

    /**
     * Makes our Doze allowlist entries match {@code wanted}: adds the ones not allowlisted yet
     * (recording them first), removes the ones we added that aren't wanted any more. Entries we
     * didn't add are never touched.
     *
     * @return the app ids of {@code wanted}
     */
    private IntArray syncAllowlist(Set<String> wanted) {
        final IDeviceIdleController idle = idle();
        final ArraySet<String> added = split(getGlobalString(KEY_ADDED));
        for (int i = added.size() - 1; i >= 0; i--) {
            final String pkg = added.valueAt(i);
            if (wanted.contains(pkg)) {
                continue;
            }
            if (idle != null) {
                try {
                    idle.removePowerSaveWhitelistApp(pkg);
                } catch (Exception e) {
                    Slog.w(TAG, "could not un-allowlist " + pkg, e);
                }
            }
            added.removeAt(i);
            putGlobalString(KEY_ADDED, join(added));
        }
        final IntArray appIds = new IntArray();
        final PackageManager pm = mContext.getPackageManager();
        for (String pkg : wanted) {
            try {
                appIds.add(UserHandle.getAppId(
                        pm.getPackageUid(pkg, PackageManager.MATCH_ANY_USER)));
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            if (idle == null) {
                continue;
            }
            try {
                if (!idle.isPowerSaveWhitelistApp(pkg)) {
                    // Record before adding, so a crash in between can't leave an entry we'd
                    // never remove.
                    added.add(pkg);
                    putGlobalString(KEY_ADDED, join(added));
                    idle.addPowerSaveWhitelistApp(pkg);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "could not allowlist " + pkg, e);
            }
        }
        return appIds;
    }

    private ArraySet<String> selectedPackages() {
        return split(getGlobalString(KEY_PKGS));
    }

    private ArraySet<String> installedSelectedPackages() {
        final ArraySet<String> installed = new ArraySet<>();
        final PackageManager pm = mContext.getPackageManager();
        for (String pkg : selectedPackages()) {
            try {
                pm.getPackageUid(pkg, PackageManager.MATCH_ANY_USER);
                installed.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Stays selected; covered again if it's reinstalled.
            }
        }
        return installed;
    }

    // ---- Migration from the old single toggle ------------------------------------------------------

    private void migrateLegacy() {
        if (getGlobalInt(KEY_MIGRATED, 0) == 1) {
            return;
        }
        final String legacyAdded = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), LEGACY_ADDED, UserHandle.USER_SYSTEM);
        if (!TextUtils.isEmpty(legacyAdded)) {
            // The old controller allowlisted every app and forced these; undo it.
            final IDeviceIdleController idle = idle();
            if (idle != null) {
                for (String pkg : split(legacyAdded)) {
                    try {
                        idle.removePowerSaveWhitelistApp(pkg);
                    } catch (Exception ignore) {
                    }
                }
            }
            putGlobalString(Settings.Global.APP_STANDBY_ENABLED, "1");
            try {
                DeviceConfig.deleteProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, PHANTOM_CAP);
            } catch (Exception ignore) {
            }
        }
        final boolean enabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                LEGACY_ENABLED, 0, UserHandle.USER_SYSTEM) == 1;
        final boolean persist = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                LEGACY_PERSIST, 0, UserHandle.USER_SYSTEM) == 1;
        if (enabled && persist) {
            // The old toggle meant "everything".
            putGlobalInt(KEY_MODE, MODE_EVERYTHING);
            putGlobalInt(KEY_PERSIST, 1);
        }
        for (String key : new String[] {LEGACY_ENABLED, LEGACY_PERSIST, LEGACY_ADDED}) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(), key, null,
                    UserHandle.USER_SYSTEM);
        }
        putGlobalInt(KEY_MIGRATED, 1);
        Slog.i(TAG, "migrated legacy unrestricted_background (enabled=" + enabled
                + ", persist=" + persist + ")");
    }

    // ---- Helpers -----------------------------------------------------------------------------------

    private IDeviceIdleController idle() {
        if (mIdleController == null) {
            mIdleController = IDeviceIdleController.Stub.asInterface(
                    ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
        }
        return mIdleController;
    }

    private void registerGlobalObserver(String key, ContentObserver observer) {
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(key), false, observer, UserHandle.USER_ALL);
    }

    private static ArraySet<String> split(String list) {
        final ArraySet<String> set = new ArraySet<>();
        if (!TextUtils.isEmpty(list)) {
            for (String pkg : list.split(",")) {
                if (!TextUtils.isEmpty(pkg.trim())) {
                    set.add(pkg.trim());
                }
            }
        }
        return set;
    }

    private static String join(ArraySet<String> set) {
        return TextUtils.join(",", set);
    }

    private static Object jsonValue(String value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static String stringValue(JSONObject snapshot, String key) throws JSONException {
        return snapshot.isNull(key) ? null : snapshot.getString(key);
    }

    private int getGlobalInt(String key, int def) {
        return Settings.Global.getInt(mContext.getContentResolver(), key, def);
    }

    private void putGlobalInt(String key, int value) {
        Settings.Global.putInt(mContext.getContentResolver(), key, value);
    }

    private String getGlobalString(String key) {
        return Settings.Global.getString(mContext.getContentResolver(), key);
    }

    private void putGlobalString(String key, String value) {
        Settings.Global.putString(mContext.getContentResolver(), key, value);
    }
}
