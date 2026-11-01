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

import com.android.server.LocalServices;

/**
 * IchthysOS: the in-process side of IchthysGuardService for other system_server parts. The
 * location service reports here what it delivered to an app (a real fix, the fake place, or
 * nothing), so Guard can show evidence that the location shields work. Everything is cheap and
 * never throws: the service rate-limits per uid and code, and a missing service is a no-op.
 */
public abstract class IchthysGuardInternal {

    /** Records a shield decision for {@code uid}; {@code code} is a GuardEvent.SHIELD_* value. */
    public abstract void noteShield(int uid, int code, @Nullable String detail);

    /** Appends a journal entry; {@code code} is a GuardEvent.JOURNAL_* value. */
    public abstract void addJournal(int uid, int code, @Nullable String detail);

    @Nullable private static volatile IchthysGuardInternal sCached;

    /**
     * {@link #noteShield} on the registered service, or nothing when Guard isn't up yet. Looked up
     * once; LocalServices never replaces a registered instance.
     */
    public static void note(int uid, int code, @Nullable String detail) {
        IchthysGuardInternal s = sCached;
        if (s == null) {
            s = LocalServices.getService(IchthysGuardInternal.class);
            if (s == null) {
                return;
            }
            sCached = s;
        }
        try {
            s.noteShield(uid, code, detail);
        } catch (RuntimeException e) {
            // Evidence only: never let it reach the caller's path.
        }
    }
}
