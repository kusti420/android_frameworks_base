/*
 * Copyright (C) 2017-2025 crDroid Android Project
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

package com.android.internal.util.crdroid;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;

import java.util.List;

public class Utils {

    private static final String TAG = "crDroidUtils";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    /** Minimum time between two SystemUI restarts requested by this process. */
    private static final long MIN_RESTART_INTERVAL_MS = 12_000;
    private static long sLastRestartUptime;
    private static boolean sRestartQueued;

    public static boolean isPackageInstalled(Context context, String packageName, boolean ignoreState) {
        if (packageName != null) {
            try {
                PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
                if (!pi.applicationInfo.enabled && !ignoreState) {
                    return false;
                }
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
        return true;
    }

    public static boolean isPackageInstalled(Context context, String packageName) {
        return isPackageInstalled(context, packageName, true);
    }

    public static boolean isPackageEnabled(Context context, String packageName) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            return pi.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException notFound) {
            return false;
        }
    }

    public static void switchScreenOff(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm!= null) {
            pm.goToSleep(SystemClock.uptimeMillis());
        }
    }

    public static boolean deviceHasFlashlight(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
    }

    public static boolean hasNavbarByDefault(Context context) {
        boolean needsNav = context.getResources().getBoolean(
                com.android.internal.R.bool.config_showNavigationBar);
        String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
        if ("1".equals(navBarOverride)) {
            needsNav = false;
        } else if ("0".equals(navBarOverride)) {
            needsNav = true;
        }
        return needsNav;
    }

    /**
     * Restarts SystemUI by asking its process to exit; ActivityManager restarts it at once
     * because the app is persistent (the same path as after a SystemUI crash, which is how the
     * navigation bar / taskbar, wallpaper and keyguard already recover).
     *
     * <p>This must never be a force-stop. {@code forceStopPackage()} does not kill a persistent
     * process, so SystemUI kept running with the old settings, while everything else a
     * force-stop does still happened to it: the package was flagged stopped, its alarms, jobs
     * and PendingIntents were cancelled, its notifications removed and - because the image
     * wallpaper service lives in SystemUI - the user's wallpaper was reset.
     *
     * <p>The caller must run as the system uid (Settings does), or be SystemUI itself.
     */
    public static void restartSystemUI(Context context) {
        if (SYSTEMUI_PACKAGE.equals(ActivityThread.currentProcessName())) {
            // Called from inside SystemUI: just exit.
            Process.killProcess(Process.myPid());
            return;
        }
        // Space restarts out. The image wallpaper service lives in SystemUI, and
        // WallpaperManagerService reverts to the built-in wallpaper when that service dies twice
        // within 10 seconds, so two quick restarts would cost the user their wallpaper.
        synchronized (Utils.class) {
            if (sRestartQueued) {
                return; // The queued restart covers this request too.
            }
            final long wait = sLastRestartUptime == 0 ? 0
                    : sLastRestartUptime + MIN_RESTART_INTERVAL_MS - SystemClock.uptimeMillis();
            if (wait > 0) {
                sRestartQueued = true;
                // Don't hold on to an activity while waiting.
                final Context appContext = context == null ? null : context.getApplicationContext();
                final Context queuedContext = appContext != null ? appContext : context;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    synchronized (Utils.class) {
                        sRestartQueued = false;
                    }
                    restartSystemUI(queuedContext);
                }, wait);
                return;
            }
            sLastRestartUptime = SystemClock.uptimeMillis();
        }
        final IPackageManager pm = AppGlobals.getPackageManager();
        // Builds that restarted SystemUI with a force-stop can have left the package flagged
        // stopped. Clear that first so the new process does not start out stopped.
        clearStoppedState(pm, SYSTEMUI_PACKAGE, UserHandle.USER_SYSTEM);
        if (context != null && context.getUserId() != UserHandle.USER_SYSTEM) {
            clearStoppedState(pm, SYSTEMUI_PACKAGE, context.getUserId());
        }
        try {
            // SystemUI's main process always belongs to the system user.
            final int uid = pm.getPackageUid(SYSTEMUI_PACKAGE,
                    PackageManager.MATCH_SYSTEM_ONLY, UserHandle.USER_SYSTEM);
            if (uid < 0) {
                Log.e(TAG, "restartSystemUI: " + SYSTEMUI_PACKAGE + " not found");
                return;
            }
            ActivityManager.getService().killApplicationProcess(SYSTEMUI_PACKAGE, uid);
        } catch (RemoteException e) {
            // system_server died; SystemUI restarts with it.
        } catch (SecurityException e) {
            Log.e(TAG, "restartSystemUI: caller is not a system uid", e);
        }
    }

    private static void clearStoppedState(IPackageManager pm, String packageName, int userId) {
        try {
            pm.setPackageStoppedState(packageName, false, userId);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Could not clear the stopped state of " + packageName + " u" + userId, e);
        }
    }
}
