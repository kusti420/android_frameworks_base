/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.recents;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * A {@link RecentsImplementation} that revives the pre-Quickstep (Android 7.1.1-style) vertical
 * card-stack recents inside SystemUI, instead of proxying overview to the launcher.
 *
 * <p>Selected via {@code R.string.config_recentsComponent}. Instantiated reflectively by
 * {@link RecentsModule#provideRecentsImpl}, so it must keep a public no-arg constructor.
 */
public class LegacyRecentsImpl implements RecentsImplementation {

    private static final String TAG = "LegacyRecents";

    private Context mContext;

    public LegacyRecentsImpl() {}

    @Override
    public void onStart(Context context) {
        mContext = context;
    }

    @Override
    public void toggleRecentApps() {
        showRecents();
    }

    @Override
    public void showRecentApps(boolean triggeredFromAltTab) {
        showRecents();
    }

    /**
     * IchthysOS: Alt released after Alt+Tab. Like stock's Keyboard Quick Switch, that picks an
     * app: the focused card, or the previous app. A release caused by the Home key does not.
     */
    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        if (triggeredFromAltTab && !triggeredFromHomeKey) {
            LegacyRecentsActivity.onAltTabReleased();
        }
    }

    private void showRecents() {
        if (mContext == null) {
            return;
        }
        Intent intent = new Intent(mContext, LegacyRecentsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start LegacyRecentsActivity", e);
        }
    }
}
