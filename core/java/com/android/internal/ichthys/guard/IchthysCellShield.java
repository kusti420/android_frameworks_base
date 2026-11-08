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

package com.android.internal.ichthys.guard;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.Set;

/**
 * IchthysOS: the "Scans" shield ({@code ichthys_scan_empty_pkgs}, IchthysShields.SCANS) for cell
 * data. The Wi-Fi and Bluetooth stacks empty the scan results of the listed apps; the serving and
 * neighbouring cells (cell info, cell location, the cell part of ServiceState) are the same kind
 * of location fingerprint, so telephony (TelephonyRegistry in system_server,
 * PhoneInterfaceManager in the phone process) treats a listed app as if it had no location
 * permission for them: empty lists, a location-sanitized ServiceState.
 *
 * <p>Same rules as {@code com.android.bluetooth.ichthys.RadioPolicy}: a uid is listed when any of
 * its packages is on the per-user Secure list; core uids, platform-signed apps and preinstalled
 * apps are exempt. Any failure means stock behaviour. Answers are reused per uid for a second,
 * since the registry asks once per listener on every cell change.
 */
public final class IchthysCellShield {
    /** Per-user Settings.Secure key; the one the Wi-Fi and Bluetooth stacks read. */
    public static final String KEY_SCAN_EMPTY_PKGS = "ichthys_scan_empty_pkgs";

    private static final long TTL_MS = 1000;
    private static final int MAX_DECISIONS = 64;

    private static final class Decision {
        final boolean mHidden;
        final long mTimeMs;

        Decision(boolean hidden, long timeMs) {
            mHidden = hidden;
            mTimeMs = timeMs;
        }
    }

    @GuardedBy("sDecisions")
    private static final SparseArray<Decision> sDecisions = new SparseArray<>();

    private IchthysCellShield() {}

    /** Whether {@code uid} must get no cell location data. Never throws. */
    public static boolean isCellLocationHidden(@Nullable Context context, int uid) {
        if (context == null || UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        final long now = SystemClock.elapsedRealtime();
        synchronized (sDecisions) {
            final Decision d = sDecisions.get(uid);
            if (d != null && now - d.mTimeMs < TTL_MS) {
                return d.mHidden;
            }
        }
        final boolean hidden = compute(context, uid);
        synchronized (sDecisions) {
            if (sDecisions.size() >= MAX_DECISIONS) {
                sDecisions.clear();
            }
            sDecisions.put(uid, new Decision(hidden, now));
        }
        return hidden;
    }

    /**
     * The listed, non-exempt packages of {@code userId}: the ones a broadcast carrying cell
     * location must leave out. Empty when nobody is listed or on any failure.
     */
    @NonNull
    public static Set<String> hiddenPackages(@NonNull Context context, int userId) {
        final Set<String> out = new ArraySet<>();
        final long token = Binder.clearCallingIdentity();
        try {
            final Set<String> listed = readList(context, userId);
            if (listed.isEmpty()) {
                return out;
            }
            final PackageManager pm = context.createContextAsUser(UserHandle.of(userId), 0)
                    .getPackageManager();
            for (String pkg : listed) {
                try {
                    final ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if (!isExempt(pm, ai.uid)) {
                        out.add(pkg);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // Not installed for this user.
                }
            }
        } catch (RuntimeException e) {
            out.clear();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return out;
    }

    private static boolean compute(Context context, int uid) {
        final long token = Binder.clearCallingIdentity();
        try {
            final Set<String> listed = readList(context, UserHandle.getUserId(uid));
            if (listed.isEmpty()) {
                return false;
            }
            final PackageManager pm = context.createContextAsUser(
                    UserHandle.getUserHandleForUid(uid), 0).getPackageManager();
            final String[] packages = pm.getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            boolean onList = false;
            for (String pkg : packages) {
                if (listed.contains(pkg)) {
                    onList = true;
                    break;
                }
            }
            return onList && !isExempt(pm, uid);
        } catch (RuntimeException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean isExempt(PackageManager pm, int uid) {
        if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
            return true;
        }
        if (pm.checkSignatures(Process.SYSTEM_UID, uid) == PackageManager.SIGNATURE_MATCH) {
            return true;
        }
        final String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) {
            return true;
        }
        for (String pkg : packages) {
            try {
                final ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Raced with an uninstall.
            }
        }
        return false;
    }

    private static Set<String> readList(Context context, int userId) {
        final Set<String> out = new ArraySet<>();
        final String raw = Settings.Secure.getStringForUser(context.getContentResolver(),
                KEY_SCAN_EMPTY_PKGS, userId);
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        for (String pkg : raw.split(",")) {
            final String trimmed = pkg.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
