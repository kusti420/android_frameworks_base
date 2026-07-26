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

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

/**
 * IchthysOS "Screen-on memory reclaim". When enabled (System setting
 * {@link Settings.System#SCREEN_ON_MEMORY_RECLAIM}, default on), a short delay after the screen
 * turns on the controller kills the <em>cached</em> background processes to free memory, exactly
 * as the user-facing summary promises ("Kill cached background apps shortly after the screen turns
 * on to free memory").
 *
 * <p>Only cached processes are killed -- those whose set process state is worse than
 * {@link ActivityManager#PROCESS_STATE_LAST_ACTIVITY} (i.e. the CACHED_ACTIVITY / _ACTIVITY_CLIENT
 * / _RECENT / _EMPTY band). Foreground apps, running services, the home app and the most recent
 * activity are left untouched, so the reclaim is safe to leave on by default. The work is
 * throttled to at most once per {@link #MIN_RECLAIM_INTERVAL_MS} to avoid thrashing on rapid
 * screen on/off, and runs off the main thread.
 *
 * <p>This is the deliberate opposite of the "Unrestricted background" mode; the two toggles are
 * mutually exclusive in Settings.
 */
public final class ScreenOnMemoryReclaimController {
    private static final String TAG = "ScreenOnMemReclaim";

    private static final String KEY_ENABLED = Settings.System.SCREEN_ON_MEMORY_RECLAIM;

    // "shortly after the screen turns on" -- give the just-resumed app a moment to settle first.
    private static final long RECLAIM_DELAY_MS = 5_000L;
    // Never reclaim more than once per this window, however often the screen is woken.
    private static final long MIN_RECLAIM_INTERVAL_MS = 60_000L;

    private final Context mContext;
    private final Handler mHandler;

    private volatile boolean mEnabled;
    // elapsedRealtime of the last reclaim; start "long ago" so the first wake isn't throttled.
    // NB: must NOT be Long.MIN_VALUE -- (now - Long.MIN_VALUE) overflows to a negative value, which
    // is always < MIN_RECLAIM_INTERVAL_MS, so the throttle would (permanently) swallow every reclaim.
    // -MIN_RECLAIM_INTERVAL_MS makes (now - this) == now + interval, i.e. never throttled on first use.
    private long mLastReclaimElapsed = -MIN_RECLAIM_INTERVAL_MS;

    private final Runnable mReclaimRunnable = this::doReclaim;

    public ScreenOnMemoryReclaimController(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread("ScreenOnMemReclaim");
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    /** Called once the system is ready. Starts observing the toggle + the screen-on broadcast. */
    public void onBootPhaseReady() {
        mEnabled = isEnabledForCurrentUser();

        // Re-read on toggle change and on user switch (USER_ALL so a switched-in user's value wins).
        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                mEnabled = isEnabledForCurrentUser();
                if (!mEnabled) {
                    mHandler.removeCallbacks(mReclaimRunnable);
                }
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(KEY_ENABLED), false, observer, UserHandle.USER_ALL);

        // ACTION_SCREEN_ON is a protected broadcast; it can only be received via a dynamic
        // receiver. Deliver it on our background handler so onReceive never touches the main thread.
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!mEnabled) return;
                mHandler.removeCallbacks(mReclaimRunnable);
                mHandler.postDelayed(mReclaimRunnable, RECLAIM_DELAY_MS);
            }
        }, filter, null /* broadcastPermission */, mHandler);
    }

    private void doReclaim() {
        if (!mEnabled) return;
        final long now = SystemClock.elapsedRealtime();
        if (now - mLastReclaimElapsed < MIN_RECLAIM_INTERVAL_MS) {
            return; // throttled
        }
        try {
            final ActivityManagerInternal ami =
                    LocalServices.getService(ActivityManagerInternal.class);
            if (ami == null) {
                Slog.w(TAG, "ActivityManagerInternal not available; skipping reclaim");
                return;
            }
            // Kill only cached processes: set proc state strictly worse than LAST_ACTIVITY, i.e.
            // the CACHED_* band. minTargetSdk = -1 means "ignore target SDK". This is the in-process
            // path, so it bypasses the KILL_ALL_BACKGROUND_PROCESSES permission check.
            ami.killAllBackgroundProcessesExcept(-1 /* minTargetSdk */,
                    ActivityManager.PROCESS_STATE_LAST_ACTIVITY);
            mLastReclaimElapsed = now;
            Slog.i(TAG, "reclaimed cached background processes after screen-on");
        } catch (Exception e) {
            Slog.e(TAG, "reclaim failed", e);
        }
    }

    private boolean isEnabledForCurrentUser() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                KEY_ENABLED, 1 /* default on */, UserHandle.USER_CURRENT) == 1;
    }
}
