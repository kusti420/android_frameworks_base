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

package com.android.server.ichthys.guard;

import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.UidObserver;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SuspendDialogInfo;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.net.metrics.IpConnectivityLog;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.Xml;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.ichthys.guard.GuardEvent;
import com.android.internal.ichthys.guard.IGuardEventListener;
import com.android.internal.ichthys.guard.IIchthysGuard;
import com.android.internal.util.DumpUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.IchthysPreinstallController;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.net.BaseNetdEventCallback;
import com.android.server.net.NetworkPolicyManagerInternal;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Ichthys Guard platform side (kusti420/ichthysos-tracker#27, phase 1): per-uid network policy
 * enforced with the Connectivity module's BPF firewall chains, a DNS/connect monitor fed by
 * netd's event stream, and an install-time tracker code scan. The Guard app
 * (org.ichthys.guard) is the only client; see ichthys-guard/docs/ARCHITECTURE.md.
 *
 * <ul>
 * <li>DENY: uid on FIREWALL_CHAIN_OEM_DENY_3, which Guard owns (rebuilt with
 *     replaceFirewallChain on boot and on every change).
 * <li>UNMETERED_ONLY: handed to NetworkPolicyManagerService, which owns the metered chains and
 *     would otherwise overwrite our rules on its next per-uid update; NPMS treats these uids as
 *     admin-restricted (FIREWALL_CHAIN_METERED_DENY_ADMIN, no foreground exemption).
 * <li>Behaviours (W8b decision 13): BEHAVIOUR_SCREEN_OFF_DENY and BEHAVIOUR_FOREGROUND_ONLY put
 *     the uid on the same OEM_DENY_3 chain while the screen is off/locked, or while the uid is not
 *     on screen (uid observer). Local network uses the stock ACCESS_LOCAL_NETWORK runtime
 *     permission, so it looks like a user denial; apps installed after setup start blocked.
 * <li>Alerts (W8b decisions 9, 35): AppOps active/noted callbacks (camera, mic, location while
 *     not visible; wakelocks), install-time manifest checks (boot receivers, battery-exemption
 *     requests) and the battery allowlist broadcast. Delivered to Settings as an explicit
 *     broadcast; nothing is blocked.
 * </ul>
 */
public final class IchthysGuardService extends SystemService {
    private static final String TAG = "IchthysGuard";
    /** Guard's UI lives in Settings; it is the suspending package of quarantined apps. */
    private static final String GUARD_HOST_PACKAGE = "com.android.settings";
    /** Ichthys Apps: installs the catalog apps the user picked on its Recommended screen. */
    private static final String ICHTHYS_APPS_PACKAGE = "org.ichthys.apps";

    public static final String SERVICE_NAME = "ichthys_guard";
    public static final String PERMISSION = "org.ichthys.permission.MANAGE_ICHTHYS_GUARD";

    private static final int RING_SIZE = 20_000;
    private static final int JOIN_CACHE_SIZE = 10_000;
    private static final int MAX_EVENTS_PER_CALL = 2_000;
    private static final int LISTENER_CHUNK = 500;
    private static final long DISPATCH_DELAY_MS = 500;
    private static final int MAX_PAUSE_MINUTES = 24 * 60;

    /** Explicit broadcast to Settings' Guard receiver carrying one behaviour alert. */
    private static final String ACTION_ALERT = "org.ichthys.guard.action.ALERT";
    private static final ComponentName ALERT_RECEIVER = new ComponentName(GUARD_HOST_PACKAGE,
            "com.android.settings.ichthys.guard.notify.AppScannedReceiver");
    private static final String PERMISSION_LOCAL_NETWORK =
            "android.permission.ACCESS_LOCAL_NETWORK";
    /** The same alert (type, app, cause) is reported at most this often. */
    private static final long ALERT_REPEAT_MS = 6 * 60 * 60_000L;
    private static final long LONG_WAKELOCK_MS = 30 * 60_000L;
    private static final long WAKELOCK_CHECK_MS = 15 * 60_000L;
    /** Wakelock starts per hour with the screen off that count as "wakes the device often". */
    private static final int WAKEUPS_PER_HOUR = 60;
    private static final int LAN_NONE = 0;
    private static final int LAN_DENY = 1;
    private static final int LAN_ALLOW = 2;

    /** Never firewalled, whatever their uid (on top of every uid below FIRST_APPLICATION_UID). */
    private static final Set<String> CRITICAL_PACKAGES = Set.of(
            "com.android.phone",
            "com.android.providers.telephony",
            "com.android.server.telecom",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.android.networkstack.tethering",
            "com.google.android.networkstack.tethering",
            "com.android.emergency",
            "com.android.cellbroadcastreceiver",
            "com.android.cellbroadcastreceiver.module",
            "com.google.android.cellbroadcastreceiver",
            "com.android.cellbroadcastservice",
            "com.google.android.cellbroadcastservice",
            "com.android.ims",
            "com.sec.imsservice",
            "com.android.stk",
            "com.android.location.fused");

    /**
     * IchthysOS: microG and its store hold MODIFY_PHONE_STATE (for device registration), but they
     * are no telephony infrastructure: the owner may cut their network like any app's. The
     * permission rule below used to skip their rules silently while Guard showed them blocked.
     */
    private static final java.util.Set<String> BLOCKABLE_PRIVILEGED_PACKAGES = java.util.Set.of(
            "com.google.android.gms",
            "com.android.vending");

    /** A uid holding any of these is telephony/IMS/network-stack infrastructure. */
    private static final String[] CRITICAL_PERMISSIONS = {
            android.Manifest.permission.MODIFY_PHONE_STATE,
            "android.permission.NETWORK_STACK",
            "android.permission.MAINLINE_NETWORK_STACK",
    };

    private final Object mLock = new Object();
    private final Object mApplyLock = new Object();

    private final File mPolicyFile =
            new File(Environment.getDataSystemDirectory(), "ichthys_guard_policy.xml");
    private final File mScansFile =
            new File(Environment.getDataSystemDirectory(), "ichthys_guard_scans.xml");

    /** uid -> ACCESS_*; only non-ALLOW entries are stored. */
    @GuardedBy("mLock")
    private final SparseIntArray mPolicy = new SparseIntArray();
    /** Wall clock ms the emergency pause ends at, 0 when enforcing (what Settings shows). */
    @GuardedBy("mLock")
    private long mPausedUntil;
    /**
     * IchthysOS: elapsedRealtime the pause ends at, 0 when enforcing. This one decides: it keeps
     * counting in deep sleep (the handler's uptime clock doesn't) and ignores wall clock changes.
     */
    @GuardedBy("mLock")
    private long mPauseEndElapsed;
    /** uid -> BEHAVIOUR_* flags; only non-zero entries. */
    @GuardedBy("mLock")
    private final SparseIntArray mBehaviour = new SparseIntArray();
    /** Uids of user apps installed after setup: local network blocked unless LAN_ALLOW. */
    @GuardedBy("mLock")
    private final SparseBooleanArray mLanDefaultDeny = new SparseBooleanArray();
    @GuardedBy("mLock")
    private int mAlertMask = IIchthysGuard.ALERT_ALL;
    /** Screen off or keyguard showing. Starts true: boot ends on the keyguard. */
    @GuardedBy("mLock")
    private boolean mScreenLocked = true;
    /** App uid -> process state, from the uid observer. Missing = not running. */
    @GuardedBy("mLock")
    private final SparseIntArray mProcState = new SparseIntArray();
    /** Uids currently on the deny chain (policy DENY plus active behaviours). */
    @GuardedBy("mLock")
    private SparseBooleanArray mDenyNow = new SparseBooleanArray();

    @GuardedBy("mLock")
    private final GuardEvent[] mRing = new GuardEvent[RING_SIZE];
    /** Seq of the newest event in {@link #mRing}; event s lives at (s - 1) % RING_SIZE. */
    @GuardedBy("mLock")
    private long mSeq;

    /** package -> scan result. */
    @GuardedBy("mLock")
    private final ArrayMap<String, ScanEntry> mScans = new ArrayMap<>();

    /**
     * IchthysOS: rules of uninstalled apps, so a reinstall gets them back right away (Settings
     * keys its rules by package, but only pushes them on its own start). "pkg|userId" ->
     * {access, behaviour flags}, taken when the package is removed. Saved with the policy, so an
     * uninstall, a reboot and a reinstall still bring the rule back; the newest 64 are kept.
     */
    @GuardedBy("mLock")
    private final LinkedHashMap<String, int[]> mRemovedRules =
            new LinkedHashMap<String, int[]>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
                    return size() > 64;
                }
            };

    // Handler-thread only.
    private final LinkedHashMap<String, String> mJoinCache =
            new LinkedHashMap<String, String>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > JOIN_CACHE_SIZE;
                }
            };
    private final ArrayList<GuardEvent> mPending = new ArrayList<>();
    private boolean mDispatchScheduled;

    private final RemoteCallbackList<IGuardEventListener> mListeners =
            new RemoteCallbackList<>();

    @GuardedBy("mApplyLock")
    private int[] mAppliedDeny;
    @GuardedBy("mApplyLock")
    private boolean mFirewallLogged;
    /** Set at PHASE_SYSTEM_SERVICES_READY: from then on a missing ConnectivityManager is a bug. */
    private volatile boolean mBootPhaseReached;
    @GuardedBy("mApplyLock")
    private int[] mAppliedMetered;

    // Handler-thread only (filled by loadPolicy() before that thread gets any work):
    // local-network state last written (LAN_*), persisted so a block applied before a reboot can
    // still be lifted and nothing is re-applied over the user's own choice on every boot.
    private final SparseIntArray mLanApplied = new SparseIntArray();
    // Handler-thread only: alert bookkeeping.
    private final ArrayMap<String, Long> mAlertTimes = new ArrayMap<>();
    /** "uid|pkg" -> elapsedRealtime the wakelock op went active. */
    private final ArrayMap<String, Long> mWakeStarts = new ArrayMap<>();
    /** "uid|pkg" -> {window start (elapsed), starts in window} while the screen is off. */
    private final ArrayMap<String, long[]> mWakeups = new ArrayMap<>();
    /** Non-system packages exempt from battery optimisation; null until the first snapshot. */
    @Nullable
    private ArraySet<String> mBatteryExempt;

    private HandlerThread mThread;
    private Handler mHandler;
    private HandlerThread mScanThread;
    private Handler mScanHandler;
    private volatile ConnectivityManager mCm;
    private volatile NetworkPolicyManagerInternal mNpmi;
    private volatile TrackerScanner mScanner;
    private volatile boolean mMonitorRegistered;

    private final Runnable mResumeRunnable = () -> {
        synchronized (mLock) {
            // A newer pause (or an early resume) already moved the end: not ours to end.
            if (mPauseEndElapsed == 0 || SystemClock.elapsedRealtime() < mPauseEndElapsed) {
                return;
            }
            mPausedUntil = 0;
            mPauseEndElapsed = 0;
        }
        Slog.i(TAG, "Emergency pause over, enforcing again");
        applyFirewall();
    };
    /** IchthysOS: ends the pause on time even in deep sleep. */
    private final AlarmManager.OnAlarmListener mResumeAlarm = () -> mResumeRunnable.run();

    private final Runnable mDispatchRunnable = this::dispatchPending;

    private static final class ScanEntry {
        final long versionCode;
        final String[] matches;

        ScanEntry(long versionCode, String[] matches) {
            this.versionCode = versionCode;
            this.matches = matches;
        }
    }

    public IchthysGuardService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mThread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        mThread.start();
        mHandler = new SafeHandler(mThread.getLooper());
        mScanThread = new HandlerThread(TAG + "Scan", Process.THREAD_PRIORITY_BACKGROUND);
        mScanThread.start();
        mScanHandler = new SafeHandler(mScanThread.getLooper());
        try {
            loadPolicy();
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "policy load failed; starting with an empty policy", e);
        }
        publishBinderService(SERVICE_NAME, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        // Guard must never take system_server (and so the whole phone) down: a failure here only
        // disables the failing part of Guard and is logged.
        try {
            onBootPhaseInternal(phase);
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Guard boot phase " + phase + " failed; continuing without it", e);
        }
    }

    private void onBootPhaseInternal(int phase) {
        // IchthysOS: SystemServer starts Guard after PHASE_SYSTEM_SERVICES_READY has already run,
        // so that phase is never delivered here; the first phase that is (ACTIVITY_MANAGER_READY,
        // still before any third-party app runs) sets the firewall up. Waiting for 500 left mCm
        // null forever: no deny rule and no enabled chain ever reached the network stack.
        if (phase >= PHASE_SYSTEM_SERVICES_READY && !mBootPhaseReached) {
            mCm = getContext().getSystemService(ConnectivityManager.class);
            mNpmi = LocalServices.getService(NetworkPolicyManagerInternal.class);
            mBootPhaseReached = true;
            // Synchronously, so the rules are in place before third-party apps start.
            applyFirewall();
        }
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            registerReceivers();
            registerLanWatcher();
            registerMonitor();
            registerBehaviourWatchers();
            registerAlertWatchers();
            mScanHandler.post(() -> {
                mScanner = TrackerScanner.load();
                Slog.i(TAG, "Tracker DB: " + mScanner.size() + " signatures from "
                        + mScanner.source);
                loadScans();
            });
        } else if (phase == PHASE_BOOT_COMPLETED) {
            if (!mMonitorRegistered) registerMonitor();
            // IchthysOS: again once the network stack is fully up; the enable at
            // PHASE_SYSTEM_SERVICES_READY may be dropped (applyFirewall reads the state back).
            // The rules are written again too, not skipped as unchanged: the BPF layer only logs
            // a failed write, so one at boot would otherwise leave blocked apps online until
            // the next rule change.
            mHandler.post(() -> {
                synchronized (mApplyLock) {
                    mAppliedDeny = null;
                    mAppliedMetered = null;
                }
                applyFirewall();
            });
            mScanHandler.post(this::releaseLauncherlessQuarantines);
            mScanHandler.post(this::backfillScans);
            mHandler.post(this::applyLan);
            // First snapshot only; later changes of the battery allowlist are alerted.
            mHandler.post(() -> checkBatteryExemptions(false));
            mHandler.postDelayed(mWakelockCheck, WAKELOCK_CHECK_MS);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Firewall

    private void applyFirewall() {
        final int[] deny;
        final int[] metered;
        synchronized (mLock) {
            final boolean paused = isPausedLocked();
            final SparseBooleanArray d = new SparseBooleanArray();
            int nMetered = 0;
            final int[] m = new int[mPolicy.size()];
            if (!paused) {
                for (int i = 0; i < mPolicy.size(); i++) {
                    final int uid = mPolicy.keyAt(i);
                    final int access = mPolicy.valueAt(i);
                    if (access == IIchthysGuard.ACCESS_DENY) {
                        d.put(uid, true);
                    } else if (access == IIchthysGuard.ACCESS_UNMETERED_ONLY) {
                        m[nMetered++] = uid;
                    }
                }
                for (int i = 0; i < mBehaviour.size(); i++) {
                    final int uid = mBehaviour.keyAt(i);
                    final int flags = mBehaviour.valueAt(i);
                    if ((flags & IIchthysGuard.BEHAVIOUR_SCREEN_OFF_DENY) != 0 && mScreenLocked) {
                        d.put(uid, true);
                    }
                    if ((flags & IIchthysGuard.BEHAVIOUR_FOREGROUND_ONLY) != 0
                            && !isVisibleLocked(uid)) {
                        d.put(uid, true);
                    }
                }
            }
            // IchthysOS: an app's SDK sandbox process shares its fate (it had its own uid and
            // kept the network the app was denied).
            final int nApps = d.size();
            for (int i = 0; i < nApps; i++) {
                final int uid = d.keyAt(i);
                if (Process.isApplicationUid(uid)) {
                    d.put(Process.toSdkSandboxUid(uid), true);
                }
            }
            mDenyNow = d;
            deny = new int[d.size()];
            for (int i = 0; i < deny.length; i++) deny[i] = d.keyAt(i);
            metered = Arrays.copyOf(m, nMetered);
            Arrays.sort(metered);
        }
        synchronized (mApplyLock) {
            // IchthysOS: fetched again when missing. The one fetch at PHASE_SYSTEM_SERVICES_READY
            // could come back null, and then every apply returned here silently: no deny rule
            // and no enabled chain ever reached the network stack.
            if (mCm == null && mBootPhaseReached) {
                mCm = getContext().getSystemService(ConnectivityManager.class);
            }
            if (mCm == null) {
                if (mBootPhaseReached) Slog.w(TAG, "ConnectivityManager unavailable; rules pending");
                return;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                // Behaviours re-run this on every screen/foreground change: skip no-op writes.
                // IchthysOS: the chain is (re)enabled at every apply, never trusted from a cache:
                // enabling it at PHASE_SYSTEM_SERVICES_READY did not stick (the BPF configuration
                // was still BACKGROUND only after boot), and anything else that turns the chain
                // off (cmd connectivity set-package-networking-enabled true) would leave every
                // deny rule in place but unenforced.
                try {
                    if (!Arrays.equals(deny, mAppliedDeny)) {
                        mCm.replaceFirewallChain(FIREWALL_CHAIN_OEM_DENY_3, deny);
                        mAppliedDeny = deny;
                    }
                    // Idempotent and cheap (one BPF configuration write); the getter is hidden
                    // from services, so the chain is simply switched on at every apply.
                    mCm.setFirewallChainEnabled(FIREWALL_CHAIN_OEM_DENY_3, true);
                    if (!mFirewallLogged) {
                        mFirewallLogged = true;
                        Slog.i(TAG, "Firewall applied: " + deny.length + " uids on OEM_DENY_3");
                    }
                } catch (RuntimeException e) {
                    Slog.e(TAG, "Failed to update FIREWALL_CHAIN_OEM_DENY_3", e);
                }
                if (mNpmi != null && !Arrays.equals(metered, mAppliedMetered)) {
                    try {
                        mNpmi.setIchthysMeteredDeniedUids(metered);
                        mAppliedMetered = metered;
                    } catch (RuntimeException e) {
                        Slog.e(TAG, "Failed to update metered restrictions", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /** On screen: top, bound to the top app, or persistent. */
    @GuardedBy("mLock")
    private boolean isVisibleLocked(int uid) {
        return mProcState.get(uid, ActivityManager.PROCESS_STATE_NONEXISTENT)
                <= ActivityManager.PROCESS_STATE_BOUND_TOP;
    }

    private boolean isVisible(int uid) {
        synchronized (mLock) {
            return isVisibleLocked(uid);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Behaviours: screen state, foreground state, local network

    private final UidObserver mUidObserver = new UidObserver() {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            onProcState(uid, procState);
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            onProcState(uid, ActivityManager.PROCESS_STATE_NONEXISTENT);
        }
    };

    /** AMS binder thread (oneway): only bookkeeping, the chain update goes to our thread. */
    private void onProcState(int uid, int procState) {
        if (!isAppUid(uid)) return;
        final boolean reapply;
        synchronized (mLock) {
            final boolean was = isVisibleLocked(uid);
            if (procState == ActivityManager.PROCESS_STATE_NONEXISTENT) {
                mProcState.delete(uid);
            } else {
                mProcState.put(uid, procState);
            }
            reapply = was != isVisibleLocked(uid)
                    && (mBehaviour.get(uid) & IIchthysGuard.BEHAVIOUR_FOREGROUND_ONLY) != 0;
        }
        // Front of the queue: the app is waiting for its network as it comes on screen.
        if (reapply) mHandler.postAtFrontOfQueue(this::applyFirewall);
    }

    private static boolean isAppUid(int uid) {
        final int appId = UserHandle.getAppId(uid);
        return appId >= Process.FIRST_APPLICATION_UID && appId <= Process.LAST_APPLICATION_UID;
    }

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED.equals(intent.getAction())) {
                checkBatteryExemptions(true);
            }
        }
    };

    /**
     * IchthysOS: screen off or keyguard showing, from the default display's own state and the
     * keyguard. The global SCREEN_OFF/ON and USER_PRESENT broadcasts follow the device-wide
     * interactive state, which an awake secondary display (Backstage) holds "on" while the main
     * screen is off, so screen-off rules never applied then.
     */
    private boolean computeScreenLocked() {
        final Context context = getContext();
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        final Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
        final KeyguardManager km = context.getSystemService(KeyguardManager.class);
        return (display != null && !Display.isOnState(display.getState()))
                || (km != null && km.isKeyguardLocked());
    }

    /** Handler thread. */
    private void updateScreenLocked() {
        final boolean locked = computeScreenLocked();
        final boolean changed;
        synchronized (mLock) {
            changed = mScreenLocked != locked;
            mScreenLocked = locked;
        }
        if (changed) applyFirewall();
    }

    private void registerBehaviourWatchers() {
        final Context context = getContext();
        synchronized (mLock) {
            mScreenLocked = computeScreenLocked();
        }
        final IntentFilter screen = new IntentFilter();
        screen.addAction(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
        context.registerReceiverForAllUsers(mScreenReceiver, screen, null, mHandler);
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        if (dm != null) {
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) updateScreenLocked();
                }
            }, mHandler, DisplayManager.EVENT_TYPE_DISPLAY_CHANGED
                    | DisplayManager.EVENT_TYPE_DISPLAY_STATE);
        }
        final KeyguardManager km = context.getSystemService(KeyguardManager.class);
        if (km != null) {
            km.addKeyguardLockedStateListener(new HandlerExecutor(mHandler),
                    locked -> updateScreenLocked());
        }
        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, "android");
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Failed to register the uid observer", e);
        }
        mHandler.post(this::applyFirewall);
    }

    /** LAN_* this uid should have now. */
    @GuardedBy("mLock")
    private int lanStateLocked(int uid) {
        final int flags = mBehaviour.get(uid);
        if ((flags & IIchthysGuard.BEHAVIOUR_LAN_DENY) != 0) return LAN_DENY;
        if ((flags & IIchthysGuard.BEHAVIOUR_LAN_ALLOW) != 0) return LAN_ALLOW;
        return mLanDefaultDeny.get(uid) ? LAN_DENY : LAN_NONE;
    }

    /**
     * Handler thread. Brings every uid's ACCESS_LOCAL_NETWORK grant in line with its LAN state.
     * Only uids whose state changed since the last write are touched (revoking kills the app);
     * what was written is saved with the policy.
     */
    private void applyLan() {
        final SparseIntArray want = new SparseIntArray();
        synchronized (mLock) {
            for (int i = 0; i < mBehaviour.size(); i++) {
                final int uid = mBehaviour.keyAt(i);
                want.put(uid, lanStateLocked(uid));
            }
            for (int i = 0; i < mLanDefaultDeny.size(); i++) {
                final int uid = mLanDefaultDeny.keyAt(i);
                want.put(uid, lanStateLocked(uid));
            }
        }
        for (int i = 0; i < mLanApplied.size(); i++) {
            if (want.indexOfKey(mLanApplied.keyAt(i)) < 0) want.put(mLanApplied.keyAt(i), LAN_NONE);
        }
        boolean changed = false;
        for (int i = 0; i < want.size(); i++) {
            final int uid = want.keyAt(i);
            final int state = want.valueAt(i);
            // A blocked app granted the permission elsewhere (stock permission settings, "Reset
            // app preferences") is blocked again, not skipped as already done.
            if (mLanApplied.get(uid, LAN_NONE) == state
                    && !(state == LAN_DENY && lanGranted(uid))) {
                continue;
            }
            setLanPermission(uid, state);
            if (state == LAN_NONE) {
                mLanApplied.delete(uid);
            } else {
                mLanApplied.put(uid, state);
            }
            changed = true;
        }
        if (changed) savePolicy();
    }

    private boolean lanGranted(int uid) {
        return getContext().checkPermission(PERMISSION_LOCAL_NETWORK, -1, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * IchthysOS: a grant of the local-network permission made outside Guard, to an app Guard
     * blocks from the LAN, is taken back right away (applyLan re-checks those uids).
     */
    private void registerLanWatcher() {
        getContext().getPackageManager().addOnPermissionsChangeListener(uid ->
                mHandler.post(() -> {
                    if (mLanApplied.get(uid, LAN_NONE) == LAN_DENY && lanGranted(uid)) applyLan();
                }));
    }

    /**
     * The stock local-network path: deny = revoked and user-fixed (the app can't prompt again),
     * allow = granted, none = the user-fixed flag lifted, grant left as it is. Packages that don't
     * request the permission (or builds without it) are left alone.
     */
    private void setLanPermission(int uid, int state) {
        final PackageManager pm = getContext().getPackageManager();
        final String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null) return;
        final UserHandle user = UserHandle.getUserHandleForUid(uid);
        final int fixed = PackageManager.FLAG_PERMISSION_USER_FIXED;
        for (String pkg : pkgs) {
            try {
                final PackageInfo pi = pm.getPackageInfoAsUser(pkg,
                        PackageManager.GET_PERMISSIONS, user.getIdentifier());
                if (pi.requestedPermissions == null
                        || !Arrays.asList(pi.requestedPermissions)
                                .contains(PERMISSION_LOCAL_NETWORK)) {
                    continue;
                }
                if (state == LAN_DENY) {
                    pm.updatePermissionFlags(PERMISSION_LOCAL_NETWORK, pkg, fixed, fixed, user);
                    pm.revokeRuntimePermission(pkg, PERMISSION_LOCAL_NETWORK, user);
                } else {
                    pm.updatePermissionFlags(PERMISSION_LOCAL_NETWORK, pkg, fixed, 0, user);
                    if (state == LAN_ALLOW) {
                        pm.grantRuntimePermission(pkg, PERMISSION_LOCAL_NETWORK, user);
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Gone.
            } catch (RuntimeException e) {
                Slog.w(TAG, "Local network state " + state + " not applied to " + pkg, e);
            }
        }
    }

    private void replaceBehavioursInternal(int[] uids, int[] flags, int callingUserId) {
        if (uids == null || flags == null || uids.length != flags.length) {
            throw new IllegalArgumentException("uids and flags differ in length");
        }
        final SparseIntArray next = new SparseIntArray();
        for (int i = 0; i < uids.length; i++) {
            final int f = flags[i] & 0xf;
            if (f == 0) continue;
            if (isCriticalUid(uids[i])) {
                Slog.w(TAG, "Skipping behaviours of system-critical uid " + uids[i]);
                continue;
            }
            next.put(uids[i], f);
        }
        final ArraySet<Integer> users = replaceScope(callingUserId, uids);
        synchronized (mLock) {
            removeUsersLocked(mBehaviour, users);
            for (int i = 0; i < next.size(); i++) {
                mBehaviour.put(next.keyAt(i), next.valueAt(i));
            }
        }
        mHandler.post(() -> {
            savePolicy();
            applyFirewall();
            applyLan();
        });
    }

    /** Must be called with the caller's identity cleared. */
    private boolean isCriticalUid(int uid) {
        final int appId = UserHandle.getAppId(uid);
        if (appId < Process.FIRST_APPLICATION_UID || appId > Process.LAST_APPLICATION_UID) {
            return true;
        }
        final String[] pkgs = getContext().getPackageManager().getPackagesForUid(uid);
        if (pkgs != null) {
            for (String p : pkgs) {
                if (CRITICAL_PACKAGES.contains(p)) return true;
            }
            boolean blockable = pkgs.length > 0;
            for (String p : pkgs) {
                blockable &= BLOCKABLE_PRIVILEGED_PACKAGES.contains(p);
            }
            if (blockable) return false;
        }
        for (String perm : CRITICAL_PERMISSIONS) {
            if (getContext().checkPermission(perm, -1, uid)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidAccess(int access) {
        return access == IIchthysGuard.ACCESS_ALLOW
                || access == IIchthysGuard.ACCESS_UNMETERED_ONLY
                || access == IIchthysGuard.ACCESS_DENY;
    }

    private void setPolicyInternal(int uid, int access) {
        if (!isValidAccess(access)) {
            throw new IllegalArgumentException("Bad access " + access);
        }
        if (access != IIchthysGuard.ACCESS_ALLOW && isCriticalUid(uid)) {
            throw new IllegalArgumentException("uid " + uid + " is system-critical");
        }
        synchronized (mLock) {
            if (mPolicy.get(uid, IIchthysGuard.ACCESS_ALLOW) == access) return;
            if (access == IIchthysGuard.ACCESS_ALLOW) {
                mPolicy.delete(uid);
            } else {
                mPolicy.put(uid, access);
            }
        }
        commitPolicy();
    }

    private void replacePoliciesInternal(int[] uids, int[] access, int callingUserId) {
        if (uids == null || access == null || uids.length != access.length) {
            throw new IllegalArgumentException("uids and access differ in length");
        }
        final SparseIntArray next = new SparseIntArray();
        for (int i = 0; i < uids.length; i++) {
            if (!isValidAccess(access[i])) {
                throw new IllegalArgumentException("Bad access " + access[i]);
            }
            if (access[i] == IIchthysGuard.ACCESS_ALLOW) continue;
            if (isCriticalUid(uids[i])) {
                Slog.w(TAG, "Skipping system-critical uid " + uids[i]);
                continue;
            }
            next.put(uids[i], access[i]);
        }
        final ArraySet<Integer> users = replaceScope(callingUserId, uids);
        synchronized (mLock) {
            removeUsersLocked(mPolicy, users);
            for (int i = 0; i < next.size(); i++) {
                mPolicy.put(next.keyAt(i), next.valueAt(i));
            }
        }
        commitPolicy();
    }

    /**
     * IchthysOS: the users whose rules a "replace the whole table" call rewrites: the caller's
     * own user and the users of the uids it lists. Settings runs per user (a work profile has
     * its own) and lists only its own apps, so one user's push must not wipe another's rules.
     */
    private static ArraySet<Integer> replaceScope(int callingUserId, int[] uids) {
        final ArraySet<Integer> users = new ArraySet<>();
        users.add(callingUserId);
        for (int uid : uids) {
            users.add(UserHandle.getUserId(uid));
        }
        return users;
    }

    /** Callers hold mLock (the tables are guarded by it). */
    private static void removeUsersLocked(SparseIntArray table, ArraySet<Integer> users) {
        for (int i = table.size() - 1; i >= 0; i--) {
            if (users.contains(UserHandle.getUserId(table.keyAt(i)))) {
                table.removeAt(i);
            }
        }
    }

    private void commitPolicy() {
        mHandler.post(() -> {
            savePolicy();
            applyFirewall();
        });
    }

    private void pauseInternal(int minutes) {
        final long ms = minutes <= 0 ? 0 : Math.min(minutes, MAX_PAUSE_MINUTES) * 60_000L;
        synchronized (mLock) {
            mPausedUntil = ms == 0 ? 0 : System.currentTimeMillis() + ms;
            mPauseEndElapsed = ms == 0 ? 0 : SystemClock.elapsedRealtime() + ms;
        }
        if (ms > 0) {
            Slog.w(TAG, "Enforcement paused for " + (ms / 60_000L) + " min");
        }
        // On the handler, so two pauses (or a pause and the end of the last one) can't race.
        mHandler.post(this::schedulePauseEnd);
    }

    /**
     * Handler thread. Re-arms the end of the pause: an ELAPSED_REALTIME_WAKEUP alarm (the
     * handler's own delay counts uptime only and overran by hours in deep sleep), with the
     * handler delay as a backstop.
     */
    private void schedulePauseEnd() {
        mHandler.removeCallbacks(mResumeRunnable);
        final AlarmManager am = getContext().getSystemService(AlarmManager.class);
        final long end;
        synchronized (mLock) {
            end = mPauseEndElapsed;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            if (am != null) {
                am.cancel(mResumeAlarm);
                if (end != 0) {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, end,
                            "*ichthys_guard_resume*", mResumeAlarm, mHandler);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (end != 0) {
            mHandler.postDelayed(mResumeRunnable,
                    Math.max(0, end - SystemClock.elapsedRealtime()));
        }
        applyFirewall();
    }

    @GuardedBy("mLock")
    private boolean isPausedLocked() {
        return mPauseEndElapsed != 0 && SystemClock.elapsedRealtime() < mPauseEndElapsed;
    }

    private void loadPolicy() {
        synchronized (mLock) {
            mPolicy.clear();
            mBehaviour.clear();
            mLanDefaultDeny.clear();
            mLanApplied.clear();
            mRemovedRules.clear();
            if (!mPolicyFile.exists()) return;
            try (FileInputStream in = new AtomicFile(mPolicyFile).openRead()) {
                final TypedXmlPullParser parser = Xml.resolvePullParser(in);
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG) continue;
                    final String tag = parser.getName();
                    if ("uid".equals(tag)) {
                        final int uid = parser.getAttributeInt(null, "id");
                        final int access = parser.getAttributeInt(null, "access");
                        if (isValidAccess(access) && access != IIchthysGuard.ACCESS_ALLOW
                                && UserHandle.getAppId(uid) >= Process.FIRST_APPLICATION_UID) {
                            mPolicy.put(uid, access);
                        }
                    } else if ("behaviour".equals(tag)) {
                        final int uid = parser.getAttributeInt(null, "id");
                        final int flags = parser.getAttributeInt(null, "flags", 0) & 0xf;
                        if (flags != 0 && isAppUid(uid)) mBehaviour.put(uid, flags);
                    } else if ("lan-new".equals(tag)) {
                        final int uid = parser.getAttributeInt(null, "id");
                        if (isAppUid(uid)) mLanDefaultDeny.put(uid, true);
                    } else if ("lan-applied".equals(tag)) {
                        final int uid = parser.getAttributeInt(null, "id");
                        final int state = parser.getAttributeInt(null, "state", LAN_NONE);
                        if (isAppUid(uid) && (state == LAN_DENY || state == LAN_ALLOW)) {
                            mLanApplied.put(uid, state);
                        }
                    } else if ("removed".equals(tag)) {
                        final String key = parser.getAttributeValue(null, "key");
                        final int access = parser.getAttributeInt(null, "access",
                                IIchthysGuard.ACCESS_ALLOW);
                        final int flags = parser.getAttributeInt(null, "flags", 0) & 0xf;
                        if (key != null && isValidAccess(access)
                                && (access != IIchthysGuard.ACCESS_ALLOW || flags != 0)) {
                            mRemovedRules.put(key, new int[] {access, flags});
                        }
                    } else if ("alerts".equals(tag)) {
                        mAlertMask = parser.getAttributeInt(null, "mask", IIchthysGuard.ALERT_ALL)
                                & IIchthysGuard.ALERT_ALL;
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to read " + mPolicyFile, e);
            }
        }
    }

    /** Handler thread. */
    private void savePolicy() {
        final SparseIntArray copy;
        final SparseIntArray behaviour;
        final SparseBooleanArray lanNew;
        final int alertMask;
        final ArrayList<Map.Entry<String, int[]>> removed;
        synchronized (mLock) {
            copy = mPolicy.clone();
            behaviour = mBehaviour.clone();
            lanNew = mLanDefaultDeny.clone();
            alertMask = mAlertMask;
            removed = new ArrayList<>(mRemovedRules.entrySet());
        }
        final AtomicFile file = new AtomicFile(mPolicyFile);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            final TypedXmlSerializer xml = Xml.resolveSerializer(out);
            xml.startDocument(null, true);
            xml.startTag(null, "guard-policy");
            for (int i = 0; i < copy.size(); i++) {
                xml.startTag(null, "uid");
                xml.attributeInt(null, "id", copy.keyAt(i));
                xml.attributeInt(null, "access", copy.valueAt(i));
                xml.endTag(null, "uid");
            }
            for (int i = 0; i < behaviour.size(); i++) {
                xml.startTag(null, "behaviour");
                xml.attributeInt(null, "id", behaviour.keyAt(i));
                xml.attributeInt(null, "flags", behaviour.valueAt(i));
                xml.endTag(null, "behaviour");
            }
            for (int i = 0; i < lanNew.size(); i++) {
                xml.startTag(null, "lan-new");
                xml.attributeInt(null, "id", lanNew.keyAt(i));
                xml.endTag(null, "lan-new");
            }
            for (int i = 0; i < mLanApplied.size(); i++) {
                xml.startTag(null, "lan-applied");
                xml.attributeInt(null, "id", mLanApplied.keyAt(i));
                xml.attributeInt(null, "state", mLanApplied.valueAt(i));
                xml.endTag(null, "lan-applied");
            }
            // Oldest first, so the reload keeps the newest when it trims to 64.
            for (Map.Entry<String, int[]> e : removed) {
                xml.startTag(null, "removed");
                xml.attribute(null, "key", e.getKey());
                xml.attributeInt(null, "access", e.getValue()[0]);
                xml.attributeInt(null, "flags", e.getValue()[1]);
                xml.endTag(null, "removed");
            }
            xml.startTag(null, "alerts");
            xml.attributeInt(null, "mask", alertMask);
            xml.endTag(null, "alerts");
            xml.endTag(null, "guard-policy");
            xml.endDocument();
            file.finishWrite(out);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to write " + mPolicyFile, e);
            if (out != null) file.failWrite(out);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Monitor

    private final INetdEventCallback mNetdCallback = new BaseNetdEventCallback() {
        // Called in-process on netd's reporting binder thread, inside
        // NetdEventListenerService's lock: only hand off to our thread.
        @Override
        public void onDnsEvent(int netId, int eventType, int returnCode, String hostname,
                String[] ipAddresses, int ipAddressesCount, long timestamp, int uid) {
            mHandler.post(() -> recordDns(returnCode, hostname, ipAddresses, timestamp, uid));
        }

        @Override
        public void onConnectEvent(String ipAddr, int port, long timestamp, int uid) {
            mHandler.post(() -> recordConnect(ipAddr, port, timestamp, uid));
        }
    };

    private void registerMonitor() {
        try {
            final IIpConnectivityMetrics metrics = IIpConnectivityMetrics.Stub.asInterface(
                    ServiceManager.getService(IpConnectivityLog.SERVICE_NAME));
            if (metrics == null) {
                Slog.w(TAG, "IpConnectivityMetrics not up yet");
                return;
            }
            mMonitorRegistered = metrics.addNetdEventCallback(
                    INetdEventCallback.CALLBACK_CALLER_ICHTHYS_GUARD, mNetdCallback);
            if (!mMonitorRegistered) Slog.w(TAG, "addNetdEventCallback refused");
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Failed to register the netd event callback", e);
        }
    }

    @Nullable
    private static String normalizeIp(@Nullable String ip) {
        if (ip != null && ip.startsWith("::ffff:") && ip.indexOf('.') > 0) {
            return ip.substring(7);
        }
        return ip;
    }

    private boolean isBlocked(int uid) {
        synchronized (mLock) {
            return mDenyNow.get(uid);
        }
    }

    /** Handler thread. */
    private void recordDns(int returnCode, String hostname, String[] ips, long timestamp,
            int uid) {
        final String[] norm = ips != null ? new String[ips.length] : new String[0];
        for (int i = 0; i < norm.length; i++) {
            norm[i] = normalizeIp(ips[i]);
            if (hostname != null && norm[i] != null) {
                mJoinCache.put(uid + "|" + norm[i], hostname);
            }
        }
        addEvent(timestamp, uid, GuardEvent.TYPE_DNS, hostname, norm, null, 0, returnCode);
    }

    /** Handler thread. */
    private void recordConnect(String ipAddr, int port, long timestamp, int uid) {
        final String ip = normalizeIp(ipAddr);
        final String host = ip != null ? mJoinCache.get(uid + "|" + ip) : null;
        addEvent(timestamp, uid, GuardEvent.TYPE_CONNECT, host, null, ip, port, 0);
    }

    /** Handler thread. */
    private void addEvent(long timestamp, int uid, int type, String host, String[] ips,
            String ip, int port, int returnCode) {
        final boolean blocked = isBlocked(uid);
        final GuardEvent ev;
        synchronized (mLock) {
            final long seq = ++mSeq;
            ev = new GuardEvent(seq, timestamp, uid, type, host, ips, ip, port, returnCode,
                    blocked);
            mRing[(int) ((seq - 1) % RING_SIZE)] = ev;
        }
        if (mListeners.getRegisteredCallbackCount() == 0) {
            mPending.clear();
            return;
        }
        mPending.add(ev);
        if (!mDispatchScheduled) {
            mDispatchScheduled = true;
            mHandler.postDelayed(mDispatchRunnable, DISPATCH_DELAY_MS);
        }
    }

    /** Handler thread. */
    private void dispatchPending() {
        mDispatchScheduled = false;
        if (mPending.isEmpty()) return;
        final ArrayList<GuardEvent> batch = new ArrayList<>(mPending);
        mPending.clear();
        final int n = mListeners.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                final IGuardEventListener l = mListeners.getBroadcastItem(i);
                for (int from = 0; from < batch.size(); from += LISTENER_CHUNK) {
                    final List<GuardEvent> chunk = batch.subList(from,
                            Math.min(batch.size(), from + LISTENER_CHUNK));
                    try {
                        l.onEvents(chunk);
                    } catch (RemoteException e) {
                        break; // Dead; RemoteCallbackList drops it.
                    }
                }
            }
        } finally {
            mListeners.finishBroadcast();
        }
    }

    private List<GuardEvent> getEventsInternal(long sinceSeq, int max) {
        max = Math.max(0, Math.min(max, MAX_EVENTS_PER_CALL));
        final ArrayList<GuardEvent> out = new ArrayList<>();
        synchronized (mLock) {
            final long oldest = Math.max(1, mSeq - RING_SIZE + 1);
            for (long s = Math.max(sinceSeq + 1, oldest); s <= mSeq && out.size() < max; s++) {
                out.add(mRing[(int) ((s - 1) % RING_SIZE)]);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // Packages: install scan and uid cleanup

    private void registerReceivers() {
        final IntentFilter pkg = new IntentFilter();
        pkg.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkg.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkg.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        pkg.addDataScheme("package");
        getContext().registerReceiverForAllUsers(mPackageReceiver, pkg, null, mScanHandler);
        // IchthysOS: removals on Guard's main thread, with their own receiver (one receiver
        // object can't take two handlers: that IllegalStateException crash-looped boot). The
        // install receiver below runs there too, so a reinstall always sees the removal first;
        // behind the scans on the scan thread it could come after it.
        final IntentFilter removed = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        removed.addDataScheme("package");
        getContext().registerReceiverForAllUsers(mRemovalReceiver, removed, null, mHandler);
        getContext().registerReceiverForAllUsers(mRemovalReceiver,
                new IntentFilter(Intent.ACTION_UID_REMOVED), null, mHandler);
        // IchthysOS: install-time work (quarantine, LAN default, restoring a reinstalled app's
        // rules) on Guard's main thread with its own receiver: behind the dex scans on the scan
        // thread it came late enough to force-stop an app the user had already opened.
        final IntentFilter added = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        added.addDataScheme("package");
        getContext().registerReceiverForAllUsers(mInstallReceiver, added, null, mHandler);
    }

    private final BroadcastReceiver mInstallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        || intent.getData() == null) {
                    return;
                }
                final String pkgName = intent.getData().getSchemeSpecificPart();
                if (pkgName == null) return;
                final int userId = getSendingUserId();
                restoreRemovedRules(pkgName, userId);
                quarantineUntilFirstLaunch(pkgName, userId);
                blockLanByDefault(pkgName, userId);
                checkInstallAlerts(pkgName, userId);
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "install broadcast failed: " + intent, e);
            }
        }
    };

    /**
     * IchthysOS: an app that comes back after an uninstall gets its old firewall rule and
     * behaviours again (Settings still shows them). Handler thread.
     */
    private void restoreRemovedRules(String pkgName, int userId) {
        final int uid;
        try {
            uid = getContext().getPackageManager().getPackageUidAsUser(pkgName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        boolean changed = false;
        synchronized (mLock) {
            final int[] rules = mRemovedRules.remove(pkgName + "|" + userId);
            if (rules == null) return;
            if (rules[0] != IIchthysGuard.ACCESS_ALLOW && mPolicy.indexOfKey(uid) < 0) {
                mPolicy.put(uid, rules[0]);
                changed = true;
            }
            if (rules[1] != 0 && mBehaviour.indexOfKey(uid) < 0) {
                mBehaviour.put(uid, rules[1]);
                changed = true;
            }
        }
        if (changed) {
            Slog.i(TAG, "Restored the rules of reinstalled " + pkgName + " (uid " + uid + ")");
            savePolicy();
            applyFirewall();
            applyLan();
        }
    }

    /**
     * IchthysOS: uninstalls. PACKAGE_REMOVED keeps the app's rules for a reinstall
     * (restoreRemovedRules); UID_REMOVED drops the uid's own, so a later app that gets the uid
     * doesn't inherit them. Handler thread, like mInstallReceiver.
     */
    private final BroadcastReceiver mRemovalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                if (uid < 0) return;
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                    final String pkgName = intent.getData() != null
                            ? intent.getData().getSchemeSpecificPart() : null;
                    if (pkgName == null) return;
                    boolean kept = false;
                    synchronized (mLock) {
                        final int access = mPolicy.get(uid, IIchthysGuard.ACCESS_ALLOW);
                        final int flags = mBehaviour.get(uid);
                        if (access != IIchthysGuard.ACCESS_ALLOW || flags != 0) {
                            mRemovedRules.put(pkgName + "|" + getSendingUserId(),
                                    new int[] {access, flags});
                            kept = true;
                        }
                    }
                    if (kept) savePolicy();
                    return;
                }
                boolean changed = false;
                synchronized (mLock) {
                    if (mPolicy.indexOfKey(uid) >= 0) {
                        mPolicy.delete(uid);
                        changed = true;
                    }
                    if (mBehaviour.indexOfKey(uid) >= 0) {
                        mBehaviour.delete(uid);
                        changed = true;
                    }
                    if (mLanDefaultDeny.get(uid)) {
                        mLanDefaultDeny.delete(uid);
                        changed = true;
                    }
                }
                if (changed) {
                    mLanApplied.delete(uid);
                    savePolicy();
                    applyFirewall();
                }
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "removal broadcast failed: " + intent, e);
            }
        }
    };

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                onReceiveInternal(intent);
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "package broadcast failed: " + intent, e);
            }
        }

        private void onReceiveInternal(Intent intent) {
            final String action = intent.getAction();
            final String pkgName = intent.getData() != null
                    ? intent.getData().getSchemeSpecificPart() : null;
            if (pkgName == null) return;
            if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
                boolean removed;
                synchronized (mLock) {
                    removed = mScans.remove(pkgName) != null;
                }
                if (removed) saveScans();
                return;
            }
            // Fresh installs: quarantine, LAN default and install alerts are mInstallReceiver's.
            // ADDED (fresh or replacing) and REPLACED: scanPackage dedupes on versionCode.
            scanPackage(pkgName, getSendingUserId(), true);
        }
    };

    /**
     * W8b decision 14: a user app installed after setup stays dormant until the user opens it.
     * It is quarantined with Settings (which hosts Guard) as the suspending package: its
     * components are hidden from other apps and it can't run jobs, alarms or services. The first
     * launch goes through the system's suspended-app dialog, whose Review button opens Guard's
     * first-launch sheet in Settings; the sheet lifts the quarantine and starts the app. Updates, system apps, the ROM's bundled preinstalls,
     * apps installed by Ichthys Apps and setup-time installs are left alone, and so are apps
     * without a launcher activity or already running. Identity must be cleared; runs on the
     * handler thread.
     */
    private void quarantineUntilFirstLaunch(String pkgName, int userId) {
        if (!android.content.pm.Flags.quarantinedEnabled()) return;
        if (!isNewUserApp(pkgName, userId)) return;
        final Context context = getContext();
        // IchthysOS: the only way out of the quarantine is launching the app from its icon.
        // Keyboards, wallpapers, TTS engines, accessibility- or widget-only apps have none and
        // would stay hidden from their system pickers for good.
        final PackageManager userPm = context.createContextAsUser(UserHandle.of(userId), 0)
                .getPackageManager();
        if (userPm.getLaunchIntentForPackage(pkgName) == null) {
            Slog.i(TAG, "Not quarantining " + pkgName + " (no launcher activity)");
            return;
        }
        // Already opened (store "Open", adb install && am start): a new app is in the stopped
        // state until something starts it, so a running process means the user did.
        try {
            final int uid = userPm.getPackageUid(pkgName, 0);
            synchronized (mLock) {
                if (mProcState.indexOfKey(uid) >= 0) {
                    Slog.i(TAG, "Not quarantining " + pkgName + " (already running)");
                    return;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        try {
            final SuspendDialogInfo dialog = new SuspendDialogInfo.Builder()
                    .setTitle(context.getString(R.string.ichthys_first_launch_title))
                    .setMessage(context.getString(R.string.ichthys_first_launch_message))
                    .setNeutralButtonText(context.getString(R.string.ichthys_first_launch_review))
                    // Opens Settings' first-launch review (ACTION_SHOW_SUSPENDED_APP_DETAILS),
                    // which applies the chosen profile, lifts the quarantine and starts the app.
                    .setNeutralButtonAction(SuspendDialogInfo.BUTTON_ACTION_MORE_DETAILS)
                    .build();
            final String[] failed = AppGlobals.getPackageManager().setPackagesSuspendedAsUser(
                    new String[] {pkgName}, true, null, null, dialog,
                    PackageManager.FLAG_SUSPEND_QUARANTINED, GUARD_HOST_PACKAGE, userId, userId);
            if (failed != null && failed.length > 0) {
                Slog.i(TAG, "Not quarantining " + pkgName + " (can't be suspended)");
            }
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Could not quarantine " + pkgName, e);
        }
    }

    /**
     * IchthysOS: lifts Guard's quarantine from apps without a launcher activity, quarantined
     * before that was skipped: launching the app was the only way out. Scan thread, at boot.
     */
    private void releaseLauncherlessQuarantines() {
        if (!android.content.pm.Flags.quarantinedEnabled()) return;
        final Context context = getContext();
        for (android.content.pm.UserInfo user
                : context.getSystemService(android.os.UserManager.class).getAliveUsers()) {
            final PackageManager pm = context.createContextAsUser(user.getUserHandle(), 0)
                    .getPackageManager();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                try {
                    if (!pm.isPackageQuarantined(ai.packageName)
                            || pm.getLaunchIntentForPackage(ai.packageName) != null) {
                        continue;
                    }
                    AppGlobals.getPackageManager().setPackagesSuspendedAsUser(
                            new String[] {ai.packageName}, false, null, null, null,
                            0 /* flags, as Settings' own release */, GUARD_HOST_PACKAGE, user.id,
                            user.id);
                    Slog.i(TAG, "Released " + ai.packageName + " (user " + user.id
                            + ") from quarantine: no launcher activity");
                } catch (PackageManager.NameNotFoundException | RemoteException
                        | RuntimeException e) {
                    Slog.w(TAG, "Could not check the quarantine of " + ai.packageName, e);
                }
            }
        }
    }

    /**
     * A user app the user installed after setup: not a system app or update of one, not one of
     * the Ichthys Apps catalog's apps (genuine copy, by certificate pin), not an app Ichthys Apps
     * installed (the user picked it there), not a setup-time install.
     */
    private boolean isNewUserApp(String pkgName, int userId) {
        if (IchthysPreinstallController.isBundledPreinstall(pkgName)) return false;
        final Context context = getContext();
        if (Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, userId) == 0) {
            return false;
        }
        if (isInstalledByIchthysApps(context, pkgName, userId)) return false;
        try {
            final ApplicationInfo ai = context.getPackageManager().getApplicationInfoAsUser(
                    pkgName, 0, userId);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false; // Uninstalled again before we got here.
        }
    }

    /**
     * Whether Ichthys Apps is {@code pkgName}'s installer of record. Only the system app of that
     * name counts. Identity must be cleared.
     */
    private static boolean isInstalledByIchthysApps(Context context, String pkgName, int userId) {
        try {
            final PackageManager pm = context.createContextAsUser(UserHandle.of(userId), 0)
                    .getPackageManager();
            if (!ICHTHYS_APPS_PACKAGE.equals(
                    pm.getInstallSourceInfo(pkgName).getInstallingPackageName())) {
                return false;
            }
            return (pm.getApplicationInfo(ICHTHYS_APPS_PACKAGE, 0).flags
                    & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /** W8b decision 13: a new user app starts with its local network blocked. Handler thread. */
    private void blockLanByDefault(String pkgName, int userId) {
        if (!isNewUserApp(pkgName, userId)) return;
        final int uid;
        try {
            uid = getContext().getPackageManager().getPackageUidAsUser(pkgName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        synchronized (mLock) {
            if (mLanDefaultDeny.get(uid)) return;
            mLanDefaultDeny.put(uid, true);
        }
        mHandler.post(() -> {
            savePolicy();
            applyLan();
        });
    }

    /**
     * Returns the cached or fresh scan of {@code pkgName} as installed for {@code userId}, or
     * null if it is not installed. Identity must be cleared. Runs on the caller's thread.
     */
    @Nullable
    private String[] scanPackage(String pkgName, int userId, boolean persist) {
        final PackageInfo pi;
        try {
            pi = getContext().getPackageManager().getPackageInfoAsUser(pkgName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        final ApplicationInfo ai = pi.applicationInfo;
        if (ai == null || ai.sourceDir == null) return null;
        final long version = pi.getLongVersionCode();
        synchronized (mLock) {
            final ScanEntry cached = mScans.get(pkgName);
            if (cached != null && cached.versionCode == version) return cached.matches;
        }
        TrackerScanner scanner = mScanner;
        if (scanner == null) {
            scanner = TrackerScanner.load();
            mScanner = scanner;
        }
        final ArrayList<String> paths = new ArrayList<>();
        paths.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) paths.addAll(Arrays.asList(ai.splitSourceDirs));
        final String[] matches = scanner.scan(paths);
        synchronized (mLock) {
            mScans.put(pkgName, new ScanEntry(version, matches));
        }
        if (persist) {
            if (mScanHandler.getLooper().isCurrentThread()) {
                saveScans();
            } else {
                mScanHandler.post(this::saveScans);
            }
        }
        return matches;
    }

    /** Scan thread: scans the third-party apps of the system user that aren't cached yet. */
    private void backfillScans() {
        final List<PackageInfo> pkgs;
        try {
            pkgs = getContext().getPackageManager().getInstalledPackagesAsUser(0,
                    UserHandle.USER_SYSTEM);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Backfill: cannot list packages", e);
            return;
        }
        int scanned = 0;
        for (PackageInfo pi : pkgs) {
            final ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            synchronized (mLock) {
                final ScanEntry cached = mScans.get(pi.packageName);
                if (cached != null && cached.versionCode == pi.getLongVersionCode()) continue;
            }
            scanPackage(pi.packageName, UserHandle.USER_SYSTEM, false);
            scanned++;
        }
        if (scanned > 0) {
            saveScans();
            Slog.i(TAG, "Backfill: scanned " + scanned + " apps");
        }
    }

    /** Scan thread. */
    private void loadScans() {
        if (!mScansFile.exists()) return;
        final ArrayMap<String, ScanEntry> loaded = new ArrayMap<>();
        try (FileInputStream in = new AtomicFile(mScansFile).openRead()) {
            final TypedXmlPullParser parser = Xml.resolvePullParser(in);
            String pkg = null;
            long version = 0;
            ArrayList<String> matches = null;
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    if ("pkg".equals(parser.getName())) {
                        pkg = parser.getAttributeValue(null, "name");
                        version = parser.getAttributeLong(null, "version", 0);
                        matches = new ArrayList<>();
                    } else if ("match".equals(parser.getName()) && matches != null) {
                        final String n = parser.getAttributeValue(null, "name");
                        if (n != null) matches.add(n);
                    }
                } else if (type == XmlPullParser.END_TAG && "pkg".equals(parser.getName())) {
                    if (pkg != null && matches != null) {
                        loaded.put(pkg, new ScanEntry(version, matches.toArray(new String[0])));
                    }
                    pkg = null;
                    matches = null;
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to read " + mScansFile, e);
            return;
        }
        synchronized (mLock) {
            for (int i = 0; i < loaded.size(); i++) {
                // Don't clobber anything scanned since boot.
                if (!mScans.containsKey(loaded.keyAt(i))) {
                    mScans.put(loaded.keyAt(i), loaded.valueAt(i));
                }
            }
        }
    }

    /** Scan thread. */
    private void saveScans() {
        final ArrayMap<String, ScanEntry> copy;
        synchronized (mLock) {
            copy = new ArrayMap<>(mScans);
        }
        final AtomicFile file = new AtomicFile(mScansFile);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            final TypedXmlSerializer xml = Xml.resolveSerializer(out);
            xml.startDocument(null, true);
            xml.startTag(null, "guard-scans");
            for (int i = 0; i < copy.size(); i++) {
                final ScanEntry e = copy.valueAt(i);
                xml.startTag(null, "pkg");
                xml.attribute(null, "name", copy.keyAt(i));
                xml.attributeLong(null, "version", e.versionCode);
                for (String m : e.matches) {
                    xml.startTag(null, "match");
                    xml.attribute(null, "name", m);
                    xml.endTag(null, "match");
                }
                xml.endTag(null, "pkg");
            }
            xml.endTag(null, "guard-scans");
            xml.endDocument();
            file.finishWrite(out);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to write " + mScansFile, e);
            if (out != null) file.failWrite(out);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Behaviour alerts (alerts only: nothing here blocks anything)

    private final AppOpsManager.OnOpActiveChangedListener mActiveListener =
            (op, uid, packageName, active) -> onOpActive(op, uid, packageName, active);

    private final AppOpsManager.OnOpNotedListener mNotedListener =
            (op, uid, packageName, attributionTag, flags, result) -> {
                if (result == AppOpsManager.MODE_ALLOWED && isAppUid(uid) && packageName != null
                        && !isVisible(uid)) {
                    sendAlert(IIchthysGuard.ALERT_BACKGROUND_SENSOR, uid, packageName, op,
                            "Read your location while not on screen");
                }
            };

    private final Runnable mWakelockCheck = new Runnable() {
        @Override
        public void run() {
            final long now = SystemClock.elapsedRealtime();
            for (int i = 0; i < mWakeStarts.size(); i++) {
                final long held = now - mWakeStarts.valueAt(i);
                if (held >= LONG_WAKELOCK_MS) {
                    final String key = mWakeStarts.keyAt(i);
                    final int bar = key.indexOf('|');
                    sendAlert(IIchthysGuard.ALERT_LONG_WAKELOCK,
                            Integer.parseInt(key.substring(0, bar)), key.substring(bar + 1),
                            "held", "Has kept the phone awake for " + (held / 60_000) + " min");
                }
            }
            mHandler.postDelayed(this, WAKELOCK_CHECK_MS);
        }
    };

    private void registerAlertWatchers() {
        final AppOpsManager aom = getContext().getSystemService(AppOpsManager.class);
        if (aom == null) return;
        final Executor executor = new HandlerExecutor(mHandler);
        try {
            aom.startWatchingActive(new String[] {
                    AppOpsManager.OPSTR_CAMERA,
                    AppOpsManager.OPSTR_RECORD_AUDIO,
                    AppOpsManager.OPSTR_MONITOR_LOCATION,
                    AppOpsManager.OPSTR_MONITOR_HIGH_POWER_LOCATION,
                    AppOpsManager.OPSTR_WAKE_LOCK,
            }, executor, mActiveListener);
            aom.startWatchingNoted(new String[] {
                    AppOpsManager.OPSTR_FINE_LOCATION,
                    AppOpsManager.OPSTR_COARSE_LOCATION,
            }, executor, mNotedListener);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Failed to watch app ops; behaviour alerts are off", e);
        }
    }

    /** Handler thread. */
    private void onOpActive(String op, int uid, String pkg, boolean active) {
        if (!isAppUid(uid) || pkg == null) return;
        if (AppOpsManager.OPSTR_WAKE_LOCK.equals(op)) {
            onWakeLock(uid, pkg, active);
            return;
        }
        if (!active || isVisible(uid)) return;
        final String what;
        if (AppOpsManager.OPSTR_CAMERA.equals(op)) {
            what = "Started the camera while not on screen";
        } else if (AppOpsManager.OPSTR_RECORD_AUDIO.equals(op)) {
            what = "Started the microphone while not on screen";
        } else {
            what = "Started tracking your location while not on screen";
        }
        sendAlert(IIchthysGuard.ALERT_BACKGROUND_SENSOR, uid, pkg, op, what);
    }

    /** Handler thread. */
    private void onWakeLock(int uid, String pkg, boolean active) {
        final String key = uid + "|" + pkg;
        final long now = SystemClock.elapsedRealtime();
        if (!active) {
            final Long start = mWakeStarts.remove(key);
            if (start != null && now - start >= LONG_WAKELOCK_MS) {
                sendAlert(IIchthysGuard.ALERT_LONG_WAKELOCK, uid, pkg, "held",
                        "Kept the phone awake for " + ((now - start) / 60_000) + " min");
            }
            return;
        }
        mWakeStarts.put(key, now);
        final boolean locked;
        synchronized (mLock) {
            locked = mScreenLocked;
        }
        if (!locked) return;
        long[] w = mWakeups.get(key);
        if (w == null || now - w[0] > 60 * 60_000L) {
            w = new long[] {now, 0};
            mWakeups.put(key, w);
        }
        if (++w[1] == WAKEUPS_PER_HOUR) {
            sendAlert(IIchthysGuard.ALERT_WAKEUPS, uid, pkg, "wakeups",
                    "Woke the phone " + WAKEUPS_PER_HOUR + " times within an hour with the "
                            + "screen off (alarms, wakelocks)");
        }
        if (mWakeups.size() > 500) mWakeups.clear(); // Bounded; counts restart.
    }

    /**
     * Scan thread, on a fresh install: what the manifest says it will do in the background. Only
     * for apps the user installed after setup, like the quarantine.
     */
    private void checkInstallAlerts(String pkgName, int userId) {
        if (!isNewUserApp(pkgName, userId)) return;
        final PackageManager pm = getContext().getPackageManager();
        final PackageInfo pi;
        try {
            pi = pm.getPackageInfoAsUser(pkgName, PackageManager.GET_PERMISSIONS, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        if (pi.applicationInfo == null || pi.applicationInfo.isSystemApp()) return;
        final int uid = pi.applicationInfo.uid;
        final List<String> perms = pi.requestedPermissions != null
                ? Arrays.asList(pi.requestedPermissions) : List.of();
        if (perms.contains(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) {
            sendAlert(IIchthysGuard.ALERT_BATTERY_EXEMPTION, uid, pkgName, "request",
                    "Can ask to be exempt from battery optimisation");
        }
        boolean boot = false;
        for (String action : new String[] {
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED}) {
            // The app was quarantined just before this: its receivers only resolve with
            // MATCH_QUARANTINED_COMPONENTS.
            if (!pm.queryBroadcastReceiversAsUser(new Intent(action).setPackage(pkgName),
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_QUARANTINED_COMPONENTS), userId).isEmpty()) {
                boot = true;
            }
        }
        if (boot) {
            sendAlert(IIchthysGuard.ALERT_BOOT, uid, pkgName, "boot",
                    "Starts itself every time the phone boots");
        }
    }

    /**
     * Handler thread. Diffs the non-system apps exempt from battery optimisation against the
     * last snapshot; with {@code alert}, every newly exempt app is reported.
     */
    private void checkBatteryExemptions(boolean alert) {
        final Context context = getContext();
        final PowerManager power = context.getSystemService(PowerManager.class);
        if (power == null) return;
        final List<ApplicationInfo> apps;
        try {
            apps = context.getPackageManager().getInstalledApplicationsAsUser(0,
                    UserHandle.USER_SYSTEM);
        } catch (RuntimeException e) {
            return;
        }
        final ArraySet<String> now = new ArraySet<>();
        for (ApplicationInfo ai : apps) {
            if (ai.isSystemApp()) continue;
            if (power.isIgnoringBatteryOptimizations(ai.packageName)) {
                now.add(ai.packageName);
                if (alert && mBatteryExempt != null && !mBatteryExempt.contains(ai.packageName)) {
                    sendAlert(IIchthysGuard.ALERT_BATTERY_EXEMPTION, ai.uid, ai.packageName,
                            "held", "Now runs without battery optimisation");
                }
            }
        }
        mBatteryExempt = now;
    }

    /**
     * Hands one alert to Settings, which posts it on the ichthys_guard_alerts channel. Only
     * non-system apps, only enabled types, and the same (type, app, cause) at most every
     * {@link #ALERT_REPEAT_MS}. Any thread; bookkeeping is synchronized on the map.
     */
    private void sendAlert(int type, int uid, String pkg, String cause, String detail) {
        synchronized (mLock) {
            if ((mAlertMask & type) == 0) return;
        }
        final String key = type + "|" + uid + "|" + pkg + "|" + cause;
        final long now = SystemClock.elapsedRealtime();
        synchronized (mAlertTimes) {
            final Long last = mAlertTimes.get(key);
            if (last != null && now - last < ALERT_REPEAT_MS) return;
            mAlertTimes.put(key, now);
            if (mAlertTimes.size() > 1000) mAlertTimes.clear();
        }
        final long token = Binder.clearCallingIdentity();
        try {
            final ApplicationInfo ai = getContext().getPackageManager()
                    .getApplicationInfoAsUser(pkg, 0, UserHandle.getUserId(uid));
            if (ai.isSystemApp()) return;
            final Intent intent = new Intent(ACTION_ALERT)
                    .setComponent(ALERT_RECEIVER)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("package", pkg)
                    .putExtra("alert_type", type)
                    .putExtra("detail", detail)
                    .putExtra(Intent.EXTRA_UID, uid);
            getContext().sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(uid));
        } catch (PackageManager.NameNotFoundException e) {
            // Gone.
        } catch (RuntimeException e) {
            Slog.w(TAG, "Alert not delivered: " + key, e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Binder

    private final class BinderService extends IIchthysGuard.Stub {
        private void enforce() {
            getContext().enforceCallingOrSelfPermission(PERMISSION, TAG);
        }

        @Override
        public void setNetworkPolicy(int uid, int access) {
            enforce();
            final long token = Binder.clearCallingIdentity();
            try {
                setPolicyInternal(uid, access);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void replaceNetworkPolicies(int[] uids, int[] access) {
            enforce();
            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                replacePoliciesInternal(uids, access, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int[] getNetworkPolicies() {
            enforce();
            synchronized (mLock) {
                final int[] out = new int[mPolicy.size() * 2];
                for (int i = 0; i < mPolicy.size(); i++) {
                    out[2 * i] = mPolicy.keyAt(i);
                    out[2 * i + 1] = mPolicy.valueAt(i);
                }
                return out;
            }
        }

        @Override
        public List<GuardEvent> getEvents(long sinceSeq, int max) {
            enforce();
            return getEventsInternal(sinceSeq, max);
        }

        @Override
        public long getLatestSeq() {
            enforce();
            synchronized (mLock) {
                return mSeq;
            }
        }

        @Override
        public void registerListener(IGuardEventListener listener) {
            enforce();
            if (listener != null) mListeners.register(listener);
        }

        @Override
        public void unregisterListener(IGuardEventListener listener) {
            enforce();
            if (listener != null) mListeners.unregister(listener);
        }

        @Override
        public String[] getScan(String packageName) {
            enforce();
            if (packageName == null) return null;
            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                return scanPackage(packageName, userId, true);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void pauseEnforcement(int minutes) {
            enforce();
            pauseInternal(minutes);
        }

        @Override
        public long getPausedUntil() {
            enforce();
            synchronized (mLock) {
                return isPausedLocked() ? mPausedUntil : 0;
            }
        }

        @Override
        public void replaceBehaviours(int[] uids, int[] flags) {
            enforce();
            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                replaceBehavioursInternal(uids, flags, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int[] getBehaviours() {
            enforce();
            synchronized (mLock) {
                final int[] out = new int[mBehaviour.size() * 2];
                for (int i = 0; i < mBehaviour.size(); i++) {
                    out[2 * i] = mBehaviour.keyAt(i);
                    out[2 * i + 1] = mBehaviour.valueAt(i);
                }
                return out;
            }
        }

        @Override
        public boolean isLanDenied(int uid) {
            enforce();
            synchronized (mLock) {
                return lanStateLocked(uid) == LAN_DENY;
            }
        }

        @Override
        public boolean isRestrictable(int uid) {
            enforce();
            final long token = Binder.clearCallingIdentity();
            try {
                return !isCriticalUid(uid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setAlertMask(int mask) {
            enforce();
            synchronized (mLock) {
                if (mAlertMask == (mask & IIchthysGuard.ALERT_ALL)) return;
                mAlertMask = mask & IIchthysGuard.ALERT_ALL;
            }
            mHandler.post(IchthysGuardService.this::savePolicy);
        }

        @Override
        public int getAlertMask() {
            enforce();
            synchronized (mLock) {
                return mAlertMask;
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;
            synchronized (mLock) {
                pw.println("IchthysGuardService");
                pw.println("  monitor registered: " + mMonitorRegistered);
                pw.println("  paused until: " + mPausedUntil);
                pw.println("  events: seq=" + mSeq + " buffered="
                        + Math.min(mSeq, RING_SIZE));
                pw.println("  listeners: " + mListeners.getRegisteredCallbackCount());
                pw.println("  policies (" + mPolicy.size() + "):");
                for (int i = 0; i < mPolicy.size(); i++) {
                    final int a = mPolicy.valueAt(i);
                    pw.println("    uid " + mPolicy.keyAt(i) + ": "
                            + (a == IIchthysGuard.ACCESS_DENY ? "DENY" : "UNMETERED_ONLY"));
                }
                pw.println("  screen locked: " + mScreenLocked + ", alert mask: " + mAlertMask);
                pw.println("  behaviours (" + mBehaviour.size() + "):");
                for (int i = 0; i < mBehaviour.size(); i++) {
                    final int uid = mBehaviour.keyAt(i);
                    pw.println("    uid " + uid + ": flags=" + mBehaviour.valueAt(i)
                            + (isVisibleLocked(uid) ? " visible" : ""));
                }
                pw.println("  LAN blocked by default: " + mLanDefaultDeny.size() + " uids");
                pw.println("  on deny chain now: " + mDenyNow.size() + " uids");
                final TrackerScanner s = mScanner;
                pw.println("  tracker DB: " + (s == null ? "not loaded"
                        : s.size() + " signatures from " + s.source));
                pw.println("  scans (" + mScans.size() + "):");
                for (int i = 0; i < mScans.size(); i++) {
                    final ScanEntry e = mScans.valueAt(i);
                    if (e.matches.length == 0) continue;
                    pw.println("    " + mScans.keyAt(i) + " v" + e.versionCode + ": "
                            + String.join(", ", e.matches));
                }
            }
        }
    }

    /**
     * Handler for Guard's own threads that logs instead of throwing: these threads live in
     * system_server, where an uncaught exception would reboot the phone.
     */
    private static final class SafeHandler extends Handler {
        SafeHandler(android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void dispatchMessage(android.os.Message msg) {
            try {
                super.dispatchMessage(msg);
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "Guard task failed", e);
            }
        }
    }
}
