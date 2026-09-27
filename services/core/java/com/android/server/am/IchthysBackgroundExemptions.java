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

package com.android.server.am;

import android.os.UserHandle;

import java.util.Arrays;

/**
 * IchthysOS "Unrestricted background" exemptions, consulted by the activity manager hot paths
 * (OomAdjuster, CachedAppOptimizer, ActiveServices, the doze allowlist checks). Written only by
 * {@link com.android.server.UnrestrictedBackgroundController}.
 *
 * <p>Apps are tracked by app id, so a picked package is covered in every user profile it's
 * installed in. Reads are lock-free: the arrays are replaced, never mutated.
 */
public final class IchthysBackgroundExemptions {
    private static volatile int[] sAppIds = new int[0];
    // Apps the user set to "Restricted" battery usage (RUN_ANY_IN_BACKGROUND not allowed).
    // "Everything" never overrides that choice.
    private static volatile int[] sUserRestrictedAppIds = new int[0];
    private static volatile boolean sGlobal;

    private IchthysBackgroundExemptions() {}

    /** Replaces the exemption state. {@code appIds} needn't be sorted. */
    public static void set(boolean global, int[] appIds) {
        final int[] sorted = appIds.clone();
        Arrays.sort(sorted);
        sAppIds = sorted;
        sGlobal = global;
    }

    /** Replaces the set of apps the user restricted. {@code appIds} needn't be sorted. */
    public static void setUserRestricted(int[] appIds) {
        final int[] sorted = appIds.clone();
        Arrays.sort(sorted);
        sUserRestrictedAppIds = sorted;
    }

    /**
     * "Everything" mode: the app is treated as doze-allowlisted, unless the user restricted it.
     */
    public static boolean isGlobalFor(int uid) {
        return sGlobal
                && Arrays.binarySearch(sUserRestrictedAppIds, UserHandle.getAppId(uid)) < 0;
    }

    /**
     * Picked apps ("Selected apps" mode): kept at perceptible importance and never frozen.
     * Deliberately not true for every app in global mode, which would make LMK useless.
     */
    public static boolean isPickedApp(int uid) {
        return Arrays.binarySearch(sAppIds, UserHandle.getAppId(uid)) >= 0;
    }

    /** No dataSync / mediaProcessing / short FGS time limits. */
    public static boolean isFgsTimeLimitExempt(int uid) {
        return isGlobalFor(uid) || isPickedApp(uid);
    }
}
