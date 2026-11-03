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

import android.annotation.Nullable;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.LongSparseArray;

/**
 * IchthysOS: lets the platform parts that run outside system_server (ContactsProvider,
 * CalendarProvider, TelephonyProvider) tell IchthysGuardService that a data shield answered an
 * app, so Guard can show the evidence ("Contacts · shielded for X, 09:12"). One binder call per
 * (uid, shield) per minute at most; everything else is dropped here, before the binder. Never
 * throws and never blocks on the shield's own path for more than a oneway-style call.
 */
public final class GuardShieldReporter {
    private static final String SERVICE_NAME = "ichthys_guard";
    private static final long REPEAT_MS = 60_000L;

    private static final Object sLock = new Object();
    @Nullable private static IIchthysGuard sService;
    /** (uid << 8 | code) -> elapsedRealtime of the last report. */
    private static final LongSparseArray<Long> sLast = new LongSparseArray<>();

    private GuardShieldReporter() {}

    /**
     * Reports that the shield {@code code} (GuardEvent.SHIELD_*) answered {@code uid}. Call it
     * with the caller's identity still in place or cleared; the service only looks at the
     * reporting process.
     */
    public static void note(int uid, int code, @Nullable String detail) {
        final long key = ((long) uid << 8) | (code & 0xff);
        final long now = SystemClock.elapsedRealtime();
        final IIchthysGuard service;
        synchronized (sLock) {
            final Long last = sLast.get(key);
            if (last != null && now - last < REPEAT_MS) {
                return;
            }
            if (sService == null) {
                final IBinder b = ServiceManager.getService(SERVICE_NAME);
                if (b == null) {
                    // Not up yet (boot): nothing recorded, so the first report after it comes
                    // up is not swallowed by the rate limit.
                    return;
                }
                sService = IIchthysGuard.Stub.asInterface(b);
            }
            service = sService;
            sLast.put(key, now);
            if (sLast.size() > 512) {
                sLast.clear();
                sLast.put(key, now);
            }
        }
        final long token = Binder.clearCallingIdentity();
        try {
            service.noteShield(uid, code, detail);
        } catch (RemoteException | RuntimeException e) {
            synchronized (sLock) {
                sService = null;
                sLast.remove(key);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
