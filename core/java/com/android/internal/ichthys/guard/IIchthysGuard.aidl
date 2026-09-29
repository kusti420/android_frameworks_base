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

import com.android.internal.ichthys.guard.GuardEvent;
import com.android.internal.ichthys.guard.IGuardEventListener;

/**
 * Ichthys Guard platform service ("ichthys_guard", IchthysGuardService): per-uid network
 * firewall, DNS/connect monitor and install-time tracker code scan
 * (kusti420/ichthysos-tracker#27). Every call needs the signature permission
 * org.ichthys.permission.MANAGE_ICHTHYS_GUARD.
 * {@hide}
 */
interface IIchthysGuard {
    /** Network access: normal. */
    const int ACCESS_ALLOW = 0;
    /** Network access: no metered networks (Wi-Fi only), foreground included. */
    const int ACCESS_UNMETERED_ONLY = 1;
    /** Network access: none. */
    const int ACCESS_DENY = 2;

    /**
     * Sets the network access of {@code uid} (an app uid, any user). Persisted and re-applied
     * on boot. Throws IllegalArgumentException for system-critical uids (uid below
     * FIRST_APPLICATION_UID, phone/telephony, network stack, emergency packages) unless
     * {@code access} is ACCESS_ALLOW.
     */
    void setNetworkPolicy(int uid, int access);

    /**
     * Replaces the whole policy table: {@code uids[i]} gets {@code access[i]}; every uid not
     * listed goes back to ACCESS_ALLOW. Critical uids are skipped (logged), not thrown on.
     */
    void replaceNetworkPolicies(in int[] uids, in int[] access);

    /**
     * The non-default policies as flat pairs: {uid0, access0, uid1, access1, ...}.
     */
    int[] getNetworkPolicies();

    /**
     * Monitor events with seq > {@code sinceSeq}, oldest first, at most {@code max}
     * (capped at 2000 per call). Pass 0 to start from the oldest buffered event.
     */
    List<GuardEvent> getEvents(long sinceSeq, int max);

    /** The seq of the newest buffered event (0 if none yet). */
    long getLatestSeq();

    void registerListener(IGuardEventListener listener);

    void unregisterListener(IGuardEventListener listener);

    /**
     * Names of the tracker code signatures found in {@code packageName}'s dex class table
     * (installed for the calling user). Cached per package + versionCode; scanned on demand
     * if not cached yet. Empty if none matched, null if the package is not installed.
     */
    String[] getScan(String packageName);

    /**
     * Emergency pause: lifts every Guard firewall rule for {@code minutes} (1..1440), after
     * which the rules come back by themselves. {@code minutes} <= 0 resumes right away.
     */
    void pauseEnforcement(int minutes);

    /** When the current pause ends (wall clock ms), or 0 if enforcement is active. */
    long getPausedUntil();
}
