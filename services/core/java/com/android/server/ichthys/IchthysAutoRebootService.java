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

package com.android.server.ichthys;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.trust.ITrustManager;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.Slog;

import com.android.internal.policy.IDeviceLockedStateListener;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemService;

/**
 * IchthysOS: auto reboot (GrapheneOS-style). When the phone has stayed locked, with no
 * successful unlock, for the chosen time, it restarts. A restart drops every credential-encrypted
 * key from memory, so user data is back at rest (before-first-unlock) until the next unlock.
 *
 * <ul>
 *   <li><b>Setting</b>: Settings.Global {@value #KEY}, the timeout in milliseconds; 0 is off.
 *       Unset means {@link #DEFAULT_TIMEOUT_MS} (18 hours, as on GrapheneOS). Settings offers
 *       Off and 1-72 hours (IchthysOS &gt; System &gt; Security); ichthys.nix calls it
 *       security.autoReboot.</li>
 *   <li><b>Locked</b> is TrustManagerService's "device locked" state of the main user (user 0),
 *       through its device-locked listener. It is the state keystore uses to lock the
 *       unlocked-device-required keys: true only with a secure lock (PIN / pattern / password),
 *       once the keyguard is actually shown (so after the "lock after" delay, not at screen off),
 *       and false again on every successful unlock, by credential or by fingerprint. The timer
 *       starts at the change to locked and is cancelled at the change to unlocked.</li>
 *   <li><b>Nothing to protect</b>: until the first unlock after boot the user's storage is still
 *       encrypted at rest, so the timer is not armed then (a phone in a drawer doesn't restart
 *       every 18 hours).</li>
 *   <li><b>Calls</b>: when the time is up during a call (ringing, dialing, active or held,
 *       including VoIP apps), the restart waits and is checked again every
 *       {@link #DEFER_MS}; calls are never cut. The same happens while another user has the
 *       phone unlocked.</li>
 *   <li><b>Alarm</b>: AlarmManager ELAPSED_REALTIME_WAKEUP, exact. ELAPSED_REALTIME counts deep
 *       sleep, and alarms of core uids always fire in Doze (AlarmManagerService gives them
 *       FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED).</li>
 *   <li><b>Restart</b>: PowerManager.reboot({@value #REBOOT_REASON}); the reason ends up in
 *       sys.boot.reason ("reboot,ichthys_auto_reboot"), so a restart can be told apart from a
 *       crash afterwards. The Samsung kernel treats the unknown reason as a normal restart.</li>
 * </ul>
 *
 * Test: {@code adb shell settings put global ichthys_auto_reboot_timeout 120000}, unlock once,
 * lock: the phone restarts two minutes later. {@code settings delete} restores the default.
 * Logs under the tag {@value #TAG}.
 */
public final class IchthysAutoRebootService extends SystemService {
    static final String TAG = "IchthysAutoReboot";

    /** IchthysOS: Settings.Global key, timeout in ms; 0 = off, unset = the default. */
    public static final String KEY = "ichthys_auto_reboot_timeout";
    /** IchthysOS: 18 hours, the GrapheneOS default. */
    public static final long DEFAULT_TIMEOUT_MS = 18 * 60 * 60 * 1000L;
    /** IchthysOS: a shorter value (an adb test) still leaves a minute to unlock. */
    private static final long MIN_TIMEOUT_MS = 60 * 1000L;
    /** IchthysOS: how long a due restart waits behind a call before it is checked again. */
    private static final long DEFER_MS = 5 * 60 * 1000L;
    static final String REBOOT_REASON = "ichthys_auto_reboot";
    /**
     * IchthysOS: whose lock counts. On a phone (no headless system user) the main user is user
     * 0, and TrustManagerService reports the locked state of the registering uid's user, which
     * for system_server is user 0 too.
     */
    private static final int MAIN_USER = UserHandle.USER_SYSTEM;

    private Handler mHandler;
    private AlarmManager mAlarmManager;
    private ITrustManager mTrust;
    private LockPatternUtils mLockPatternUtils;

    // IchthysOS: everything below is only touched on mHandler's thread.
    /** Whether the main user is locked, as last reported. */
    private boolean mLocked;
    /** elapsedRealtime of the change to locked, 0 while unlocked. */
    private long mLockedSince;
    private boolean mAlarmSet;

    private final AlarmManager.OnAlarmListener mAlarmListener = this::onAlarm;

    private final IDeviceLockedStateListener mLockListener =
            new IDeviceLockedStateListener.Stub() {
                @Override
                public void onDeviceLockedStateChanged(boolean isDeviceLocked) {
                    // IchthysOS: called by TrustManagerService with its listener list locked;
                    // only hand the change over.
                    mHandler.post(() -> onLockedChanged(isDeviceLocked));
                }
            };

    public IchthysAutoRebootService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        // IchthysOS: own thread, because the restart itself blocks the calling thread until the
        // phone goes down; it holds the alarm's wakelock all the way.
        final HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            mHandler.post(this::init);
        }
    }

    private void init() {
        final Context context = getContext();
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mTrust = ITrustManager.Stub.asInterface(ServiceManager.getService(Context.TRUST_SERVICE));
        mLockPatternUtils = new LockPatternUtils(context);
        if (mAlarmManager == null || mTrust == null) {
            Slog.e(TAG, "AlarmManager or TrustManager missing, auto reboot is off");
            return;
        }
        context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY),
                false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        update("setting changed");
                    }
                }, UserHandle.USER_ALL);
        // IchthysOS: TrustManagerService's IDeviceLockedStateListener (behind
        // KeyguardManager.addDeviceLockedStateListener). Called directly, because the
        // KeyguardManager wrapper only registers while the android.app.device_unlock_listener
        // flag is on; the service side has no flag.
        try {
            mTrust.registerDeviceLockedStateListener(mLockListener, Context.DEVICE_ID_DEFAULT);
            // The state at boot: no change is reported for it.
            onLockedChanged(mTrust.isDeviceLocked(MAIN_USER, Context.DEVICE_ID_DEFAULT));
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "can't follow the lock state, auto reboot is off", e);
        }
    }

    private void onLockedChanged(boolean locked) {
        if (locked == mLocked) {
            return;
        }
        mLocked = locked;
        mLockedSince = locked ? SystemClock.elapsedRealtime() : 0;
        update(locked ? "locked" : "unlocked");
    }

    /** IchthysOS: the timeout in ms, 0 when off. */
    private long timeout() {
        final long t = Settings.Global.getLong(getContext().getContentResolver(), KEY,
                DEFAULT_TIMEOUT_MS);
        return t <= 0 ? 0 : Math.max(t, MIN_TIMEOUT_MS);
    }

    /** IchthysOS: whether a restart would put anything back at rest. */
    private boolean armable() {
        if (!mLocked || timeout() == 0) {
            return false;
        }
        if (!mLockPatternUtils.isSecure(MAIN_USER)) {
            return false;
        }
        // Not unlocked since boot: the storage is still encrypted at rest.
        final UserManager um = getContext().getSystemService(UserManager.class);
        return um != null && um.isUserUnlocked(MAIN_USER);
    }

    /** IchthysOS: (re)arms the alarm for lock time + timeout, or cancels it. */
    private void update(String why) {
        if (mAlarmManager == null) {
            return;
        }
        if (!armable()) {
            cancel();
            Slog.i(TAG, why + ": not armed (locked=" + mLocked + ", timeout=" + timeout() + ")");
            return;
        }
        // From the moment of locking: a changed setting counts from there too, but never fires
        // sooner than a minute from now.
        final long now = SystemClock.elapsedRealtime();
        final long at = Math.max(mLockedSince + timeout(), now + MIN_TIMEOUT_MS);
        setAlarm(at);
        Slog.i(TAG, why + ": restart in " + ((at - now) / 1000) + " s unless unlocked");
    }

    private void setAlarm(long elapsedAt) {
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedAt, TAG,
                mAlarmListener, mHandler);
        mAlarmSet = true;
    }

    private void cancel() {
        if (mAlarmSet) {
            mAlarmManager.cancel(mAlarmListener);
            mAlarmSet = false;
        }
    }

    private void onAlarm() {
        mAlarmSet = false;
        // IchthysOS: ask again instead of trusting the last callback.
        try {
            final boolean locked = mTrust.isDeviceLocked(MAIN_USER, Context.DEVICE_ID_DEFAULT);
            if (!locked) {
                onLockedChanged(false);
                return;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "lock state unknown, not restarting", e);
            return;
        }
        if (!armable()) {
            Slog.i(TAG, "due, but nothing to do any more");
            return;
        }
        if (inCall()) {
            Slog.i(TAG, "due during a call, checking again in " + (DEFER_MS / 1000) + " s");
            setAlarm(SystemClock.elapsedRealtime() + DEFER_MS);
            return;
        }
        // The main user's lock only says that user is locked; while another user has the
        // keyguard down somebody is using the phone.
        final KeyguardManager km = getContext().getSystemService(KeyguardManager.class);
        if (km != null && !km.isKeyguardLocked()) {
            Slog.i(TAG, "due, but the phone is in use, checking again in "
                    + (DEFER_MS / 1000) + " s");
            setAlarm(SystemClock.elapsedRealtime() + DEFER_MS);
            return;
        }
        Slog.w(TAG, "locked for " + ((SystemClock.elapsedRealtime() - mLockedSince) / 60000)
                + " min, restarting to put user data at rest");
        final PowerManager pm = getContext().getSystemService(PowerManager.class);
        if (pm != null) {
            // Doesn't return; this thread and the alarm's wakelock stay held until power-off.
            pm.reboot(REBOOT_REASON);
        }
    }

    private boolean inCall() {
        final TelecomManager telecom = getContext().getSystemService(TelecomManager.class);
        if (telecom == null) {
            return false;
        }
        try {
            return telecom.isInCall();
        } catch (RuntimeException e) {
            // IchthysOS: if Telecom can't answer, don't risk cutting a call.
            Slog.w(TAG, "isInCall failed, assuming a call", e);
            return true;
        }
    }
}
