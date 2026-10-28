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

    // ---- Network behaviour (W8b decision 13) ------------------------------------------------

    /** Behaviour flag: no network while the screen is off or the keyguard is showing. */
    const int BEHAVIOUR_SCREEN_OFF_DENY = 1;
    /** Behaviour flag: network only while the app is on screen (top or bound to the top app). */
    const int BEHAVIOUR_FOREGROUND_ONLY = 2;
    /** Behaviour flag: local network (LAN) blocked through the stock ACCESS_LOCAL_NETWORK path. */
    const int BEHAVIOUR_LAN_DENY = 4;
    /** Behaviour flag: local network explicitly allowed (overrides the new-app default). */
    const int BEHAVIOUR_LAN_ALLOW = 8;

    /**
     * Replaces the whole behaviour table: {@code uids[i]} gets {@code flags[i]} (BEHAVIOUR_*);
     * uids not listed get 0. Screen-off and foreground-only are enforced on Guard's firewall
     * chain together with ACCESS_DENY. Critical uids are skipped.
     */
    void replaceBehaviours(in int[] uids, in int[] flags);

    /** The non-zero behaviours as flat pairs: {uid0, flags0, uid1, flags1, ...}. */
    int[] getBehaviours();

    /**
     * True when {@code uid}'s local network is blocked: BEHAVIOUR_LAN_DENY, or an app installed
     * after setup (blocked by default) without BEHAVIOUR_LAN_ALLOW.
     */
    boolean isLanDenied(int uid);

    /**
     * IchthysOS: whether Block and Unmetered only can be applied to {@code uid}: false for
     * system-critical uids (telephony, IMS, the network stack), whose rules setNetworkPolicy
     * refuses.
     */
    boolean isRestrictable(int uid);

    // ---- Behaviour alerts (W8b decisions 9, 35); alerts only, nothing is blocked -----------

    /** Camera, microphone or location used while the app is not visible. */
    const int ALERT_BACKGROUND_SENSOR = 1;
    /** Battery-optimisation exemption requested (manifest) or granted. */
    const int ALERT_BATTERY_EXEMPTION = 2;
    /** Registers for BOOT_COMPLETED / LOCKED_BOOT_COMPLETED. */
    const int ALERT_BOOT = 4;
    /** Wakes the device often while the screen is off (wakelocks, exact-alarm wakeups). */
    const int ALERT_WAKEUPS = 8;
    /** Holds a wakelock unusually long. */
    const int ALERT_LONG_WAKELOCK = 16;
    const int ALERT_ALL = 31;

    /**
     * Which alert types the service reports (ALERT_* bits). Alerts are delivered to Settings as
     * an explicit broadcast org.ichthys.guard.action.ALERT (extras: package, alert_type, detail).
     */
    void setAlertMask(int mask);

    int getAlertMask();
}
