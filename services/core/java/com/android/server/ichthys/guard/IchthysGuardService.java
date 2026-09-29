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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.net.metrics.IpConnectivityLog;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.ichthys.guard.GuardEvent;
import com.android.internal.ichthys.guard.IGuardEventListener;
import com.android.internal.ichthys.guard.IIchthysGuard;
import com.android.internal.util.DumpUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
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
 * </ul>
 */
public final class IchthysGuardService extends SystemService {
    private static final String TAG = "IchthysGuard";

    public static final String SERVICE_NAME = "ichthys_guard";
    public static final String PERMISSION = "org.ichthys.permission.MANAGE_ICHTHYS_GUARD";

    private static final int RING_SIZE = 20_000;
    private static final int JOIN_CACHE_SIZE = 10_000;
    private static final int MAX_EVENTS_PER_CALL = 2_000;
    private static final int LISTENER_CHUNK = 500;
    private static final long DISPATCH_DELAY_MS = 500;
    private static final int MAX_PAUSE_MINUTES = 24 * 60;

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
    /** Wall clock ms the emergency pause ends at, 0 when enforcing. */
    @GuardedBy("mLock")
    private long mPausedUntil;

    @GuardedBy("mLock")
    private final GuardEvent[] mRing = new GuardEvent[RING_SIZE];
    /** Seq of the newest event in {@link #mRing}; event s lives at (s - 1) % RING_SIZE. */
    @GuardedBy("mLock")
    private long mSeq;

    /** package -> scan result. */
    @GuardedBy("mLock")
    private final ArrayMap<String, ScanEntry> mScans = new ArrayMap<>();

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
    private boolean mOemChainEnabled;

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
            mPausedUntil = 0;
        }
        Slog.i(TAG, "Emergency pause over, enforcing again");
        applyFirewall();
    };

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
        mHandler = new Handler(mThread.getLooper());
        mScanThread = new HandlerThread(TAG + "Scan", Process.THREAD_PRIORITY_BACKGROUND);
        mScanThread.start();
        mScanHandler = new Handler(mScanThread.getLooper());
        loadPolicy();
        publishBinderService(SERVICE_NAME, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            mCm = getContext().getSystemService(ConnectivityManager.class);
            mNpmi = LocalServices.getService(NetworkPolicyManagerInternal.class);
            // Synchronously, so the rules are in place before third-party apps start.
            applyFirewall();
        } else if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            registerReceivers();
            registerMonitor();
            mScanHandler.post(() -> {
                mScanner = TrackerScanner.load();
                Slog.i(TAG, "Tracker DB: " + mScanner.size() + " signatures from "
                        + mScanner.source);
                loadScans();
            });
        } else if (phase == PHASE_BOOT_COMPLETED) {
            if (!mMonitorRegistered) registerMonitor();
            mScanHandler.post(this::backfillScans);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Firewall

    private void applyFirewall() {
        final int[] deny;
        final int[] metered;
        synchronized (mLock) {
            final boolean paused = mPausedUntil != 0;
            int nDeny = 0;
            int nMetered = 0;
            final int[] d = new int[mPolicy.size()];
            final int[] m = new int[mPolicy.size()];
            if (!paused) {
                for (int i = 0; i < mPolicy.size(); i++) {
                    final int uid = mPolicy.keyAt(i);
                    final int access = mPolicy.valueAt(i);
                    if (access == IIchthysGuard.ACCESS_DENY) {
                        d[nDeny++] = uid;
                    } else if (access == IIchthysGuard.ACCESS_UNMETERED_ONLY) {
                        m[nMetered++] = uid;
                    }
                }
            }
            deny = Arrays.copyOf(d, nDeny);
            metered = Arrays.copyOf(m, nMetered);
        }
        synchronized (mApplyLock) {
            if (mCm == null) return; // Before PHASE_SYSTEM_SERVICES_READY; applied there.
            final long token = Binder.clearCallingIdentity();
            try {
                try {
                    mCm.replaceFirewallChain(FIREWALL_CHAIN_OEM_DENY_3, deny);
                    if (!mOemChainEnabled) {
                        mCm.setFirewallChainEnabled(FIREWALL_CHAIN_OEM_DENY_3, true);
                        mOemChainEnabled = true;
                    }
                } catch (RuntimeException e) {
                    Slog.e(TAG, "Failed to update FIREWALL_CHAIN_OEM_DENY_3", e);
                }
                if (mNpmi != null) {
                    try {
                        mNpmi.setIchthysMeteredDeniedUids(metered);
                    } catch (RuntimeException e) {
                        Slog.e(TAG, "Failed to update metered restrictions", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
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

    private void replacePoliciesInternal(int[] uids, int[] access) {
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
        synchronized (mLock) {
            mPolicy.clear();
            for (int i = 0; i < next.size(); i++) {
                mPolicy.put(next.keyAt(i), next.valueAt(i));
            }
        }
        commitPolicy();
    }

    private void commitPolicy() {
        mHandler.post(() -> {
            savePolicy();
            applyFirewall();
        });
    }

    private void pauseInternal(int minutes) {
        mHandler.removeCallbacks(mResumeRunnable);
        if (minutes <= 0) {
            mHandler.post(mResumeRunnable);
            return;
        }
        minutes = Math.min(minutes, MAX_PAUSE_MINUTES);
        final long ms = minutes * 60_000L;
        synchronized (mLock) {
            mPausedUntil = System.currentTimeMillis() + ms;
        }
        Slog.w(TAG, "Enforcement paused for " + minutes + " min");
        mHandler.post(this::applyFirewall);
        mHandler.postDelayed(mResumeRunnable, ms);
    }

    private void loadPolicy() {
        synchronized (mLock) {
            mPolicy.clear();
            if (!mPolicyFile.exists()) return;
            try (FileInputStream in = new AtomicFile(mPolicyFile).openRead()) {
                final TypedXmlPullParser parser = Xml.resolvePullParser(in);
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type == XmlPullParser.START_TAG && "uid".equals(parser.getName())) {
                        final int uid = parser.getAttributeInt(null, "id");
                        final int access = parser.getAttributeInt(null, "access");
                        if (isValidAccess(access) && access != IIchthysGuard.ACCESS_ALLOW
                                && UserHandle.getAppId(uid) >= Process.FIRST_APPLICATION_UID) {
                            mPolicy.put(uid, access);
                        }
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
        synchronized (mLock) {
            copy = mPolicy.clone();
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
            return mPausedUntil == 0
                    && mPolicy.get(uid, IIchthysGuard.ACCESS_ALLOW) == IIchthysGuard.ACCESS_DENY;
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
        getContext().registerReceiverForAllUsers(mPackageReceiver,
                new IntentFilter(Intent.ACTION_UID_REMOVED), null, mHandler);
    }

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_UID_REMOVED.equals(action)) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                boolean changed = false;
                synchronized (mLock) {
                    if (uid >= 0 && mPolicy.indexOfKey(uid) >= 0) {
                        mPolicy.delete(uid);
                        changed = true;
                    }
                }
                if (changed) {
                    // A later install may reuse the uid; it must not inherit the rule.
                    savePolicy();
                    applyFirewall();
                }
                return;
            }
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
            // ADDED (fresh or replacing) and REPLACED: scanPackage dedupes on versionCode.
            scanPackage(pkgName, getSendingUserId(), true);
        }
    };

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
            final long token = Binder.clearCallingIdentity();
            try {
                replacePoliciesInternal(uids, access);
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
                return mPausedUntil;
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
}
