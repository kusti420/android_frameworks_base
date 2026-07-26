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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * IchthysOS "Unrestricted background" mode. When the user enables it, background-killing
 * mechanisms are relaxed so a long-running, resource-heavy task isn't killed by Doze / app
 * standby / the phantom-process killer:
 *   - every installed app is added to the Doze / battery-optimization power-save allowlist
 *   - app standby is disabled
 *   - the phantom process killer is disabled
 *   - the device is forced out of idle
 * Disabling the mode reverts exactly what was changed (only the apps we added are removed from
 * the allowlist). The mode does NOT survive a reboot unless the "keep across reboots" companion
 * setting is also on.
 */
public final class UnrestrictedBackgroundController {
    private static final String TAG = "UnrestrictedBackground";

    // Settings.Secure keys.
    static final String KEY_ENABLED = "unrestricted_background";
    static final String KEY_PERSIST = "unrestricted_background_persist";
    // Internal bookkeeping: packages WE added to the allowlist (so revert removes only those).
    private static final String KEY_ADDED_PKGS = "unrestricted_background_added_pkgs";

    private final Context mContext;
    private final Handler mHandler;
    private IDeviceIdleController mIdleController;
    // Whether the mode is currently in effect. This is the source of truth that makes apply() and
    // revert() idempotent: every change notification triggers a re-evaluation, but only a real
    // transition does work. Without it the observer thrashes apply->revert->apply on the burst of
    // notifications that arrives around boot (settings DB init fires table-level notifications that
    // reach this observer, and the boot handoff below writes the key), churning the allowlist.
    // Guarded by this instance's monitor (apply/revert are synchronized; onBootPhaseReady seeds it
    // single-threaded before the observer is registered).
    private boolean mApplied;

    public UnrestrictedBackgroundController(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
    }

    /** Called once the system is ready. Handles boot state + starts observing the toggle. */
    public void onBootPhaseReady() {
        // Reconstruct whether the mode is actually in effect from our own bookkeeping (the added-
        // packages list is a Secure setting, so it survives reboot). This makes the apply()/revert()
        // calls below correct no-ops when nothing needs to change -- in particular, a normal boot
        // with the toggle off and no leftover state does NOTHING (no apply/revert churn).
        mApplied = !TextUtils.isEmpty(getSecureString(KEY_ADDED_PKGS));

        final boolean enabled = getSecureInt(KEY_ENABLED, 0) == 1;
        final boolean persist = getSecureInt(KEY_PERSIST, 0) == 1;
        if (enabled && persist) {
            Slog.i(TAG, "persistent unrestricted-background mode -> re-applying on boot");
            apply();
        } else if (enabled) {
            // Not persistent: undo it and clear the toggle so a reboot returns to normal.
            Slog.i(TAG, "non-persistent unrestricted-background mode -> reverting on boot");
            revert();
            putSecureInt(KEY_ENABLED, 0);
        } else if (mApplied) {
            // Toggle is off but we left the allowlist in place (e.g. crash before revert). Clean up.
            Slog.i(TAG, "stale unrestricted-background state on boot -> reverting");
            revert();
        }

        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (getSecureInt(KEY_ENABLED, 0) == 1) {
                    apply();   // no-op if already applied
                } else {
                    revert();  // no-op if not applied
                }
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_ENABLED), false, observer);
    }

    private IDeviceIdleController idle() {
        if (mIdleController == null) {
            mIdleController = IDeviceIdleController.Stub.asInterface(
                    ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
        }
        return mIdleController;
    }

    private synchronized void apply() {
        if (mApplied) {
            return; // already in effect; ignore redundant notifications
        }
        try {
            final IDeviceIdleController idle = idle();
            final List<String> added = new ArrayList<>();
            if (idle != null) {
                final PackageManager pm = mContext.getPackageManager();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    try {
                        if (!idle.isPowerSaveWhitelistApp(ai.packageName)) {
                            added.add(ai.packageName);
                        }
                    } catch (RemoteException ignore) {
                    }
                }
                if (!added.isEmpty()) {
                    idle.addPowerSaveWhitelistApps(added);
                }
                idle.exitIdle("unrestricted_background");
            }
            // Remember exactly which packages we added so revert removes only those.
            putSecureString(KEY_ADDED_PKGS, TextUtils.join(",", added));

            // Disable app standby.
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.APP_STANDBY_ENABLED, 0);
            // Disable the phantom process killer: ActivityManager only trims phantom processes
            // when their count exceeds max_phantom_processes (DeviceConfig), so a huge cap means
            // it never kills them.
            try {
                DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                        "max_phantom_processes", Integer.toString(Integer.MAX_VALUE), false);
            } catch (Exception e) {
                Slog.w(TAG, "could not raise max_phantom_processes", e);
            }
            mApplied = true;
            Slog.i(TAG, "unrestricted-background applied (allowlisted " + added.size() + " apps)");
        } catch (Exception e) {
            Slog.e(TAG, "apply failed", e);
        }
    }

    private synchronized void revert() {
        if (!mApplied) {
            return; // nothing applied; ignore redundant notifications (no boot churn when disabled)
        }
        try {
            final IDeviceIdleController idle = idle();
            final String stored = getSecureString(KEY_ADDED_PKGS);
            if (idle != null && !TextUtils.isEmpty(stored)) {
                for (String pkg : stored.split(",")) {
                    if (TextUtils.isEmpty(pkg)) continue;
                    try {
                        idle.removePowerSaveWhitelistApp(pkg);
                    } catch (Exception ignore) {
                    }
                }
            }
            putSecureString(KEY_ADDED_PKGS, "");

            // Restore app standby + the phantom process cap to their defaults.
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.APP_STANDBY_ENABLED, 1);
            try {
                DeviceConfig.deleteProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                        "max_phantom_processes");
            } catch (Exception ignore) {
            }
            mApplied = false;
            Slog.i(TAG, "unrestricted-background reverted");
        } catch (Exception e) {
            Slog.e(TAG, "revert failed", e);
        }
    }

    private int getSecureInt(String key, int def) {
        return Settings.Secure.getInt(mContext.getContentResolver(), key, def);
    }

    private void putSecureInt(String key, int value) {
        Settings.Secure.putInt(mContext.getContentResolver(), key, value);
    }

    private String getSecureString(String key) {
        return Settings.Secure.getString(mContext.getContentResolver(), key);
    }

    private void putSecureString(String key, String value) {
        Settings.Secure.putString(mContext.getContentResolver(), key, value);
    }
}
