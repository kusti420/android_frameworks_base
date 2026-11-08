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

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

/**
 * IchthysOS: hands Guard's network deny set (the FIREWALL_CHAIN_OEM_DENY_3 uids) to mediaserver.
 * MediaPlayer's RTSP client (rtsp:// and http(s) .sdp) opens its sockets in mediaserver as uid
 * media, which the per-uid BPF firewall lets through, so MediaPlayerService refuses those URLs
 * for the uids on this set (frameworks/av MediaPlayerService::onTransact, code
 * FIRST_CALL_TRANSACTION + 1000, and Client::setDataSource).
 *
 * <p>One oneway call per change; system_server never waits on mediaserver. When mediaserver
 * restarts, the last set is sent to the new instance as soon as it registers.
 */
final class IchthysMediaFirewall {
    private static final String TAG = "IchthysMediaFirewall";
    private static final String SERVICE = "media.player";
    private static final String DESCRIPTOR = "android.media.IMediaPlayerService";
    /** Keep in sync with kIchthysSetBlockedUids in MediaPlayerService.cpp. */
    private static final int TRANSACTION_SET_BLOCKED_UIDS = IBinder.FIRST_CALL_TRANSACTION + 1000;
    private static final long RESEND_DELAY_MS = 1000;

    private static final Object sLock = new Object();
    @GuardedBy("sLock") private static int[] sUids = new int[0];
    @GuardedBy("sLock") @Nullable private static IBinder sLinked;
    @GuardedBy("sLock") private static boolean sWaiting;

    private IchthysMediaFirewall() {}

    /** Sends {@code denyUids} (the whole set) to mediaserver. Never throws, never blocks. */
    static void push(int[] denyUids) {
        synchronized (sLock) {
            sUids = denyUids == null ? new int[0] : denyUids.clone();
        }
        BackgroundThread.getHandler().post(IchthysMediaFirewall::sendCurrent);
    }

    private static void sendCurrent() {
        final IBinder binder = ServiceManager.checkService(SERVICE);
        if (binder == null) {
            waitAndResend();
            return;
        }
        final int[] uids;
        synchronized (sLock) {
            uids = sUids;
            if (sLinked != binder) {
                try {
                    binder.linkToDeath(IchthysMediaFirewall::onMediaServerDied, 0);
                    sLinked = binder;
                } catch (RemoteException e) {
                    // Already dead: the next registration is waited for below.
                    sLinked = null;
                }
            }
        }
        final Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeIntArray(uids);
            binder.transact(TRANSACTION_SET_BLOCKED_UIDS, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Could not hand the deny set to mediaserver: " + e);
            waitAndResend();
        } finally {
            data.recycle();
        }
    }

    private static void onMediaServerDied() {
        synchronized (sLock) {
            sLinked = null;
        }
        waitAndResend();
    }

    /** Waits for media.player on a thread of its own, then sends the current set again. */
    private static void waitAndResend() {
        synchronized (sLock) {
            if (sWaiting) {
                return;
            }
            sWaiting = true;
        }
        new Thread(() -> {
            try {
                ServiceManager.waitForService(SERVICE);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Gave up waiting for " + SERVICE + ": " + e);
            } finally {
                synchronized (sLock) {
                    sWaiting = false;
                }
            }
            // A second's pause, so a binder that is still on its way out can't make a loop.
            BackgroundThread.getHandler().postDelayed(IchthysMediaFirewall::sendCurrent,
                    RESEND_DELAY_MS);
        }, TAG).start();
    }
}
