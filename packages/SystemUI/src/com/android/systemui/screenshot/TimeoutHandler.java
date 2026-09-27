/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import javax.inject.Inject;

/**
 * Starts a configurable runnable on timeout. Can be cancelled. Used for automatically dismissing
 * floating overlays.
 */
public class TimeoutHandler extends Handler {
    private static final String TAG = "TimeoutHandler";

    private static final int MESSAGE_CORNER_TIMEOUT = 2;
    private static final int DEFAULT_TIMEOUT_MILLIS = 6000;

    private final Context mContext;

    private Runnable mOnTimeout;
    int mDefaultTimeout = DEFAULT_TIMEOUT_MILLIS;

    @Inject
    public TimeoutHandler(Context context) {
        super(Looper.getMainLooper());
        mContext = context;
        mOnTimeout = () -> {
        };
    }

    public void setOnTimeoutRunnable(Runnable onTimeout) {
        mOnTimeout = onTimeout;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MESSAGE_CORNER_TIMEOUT:
                mOnTimeout.run();
                break;
            default:
                break;
        }
    }

    /**
     * Set the default timeout (if not overridden by accessibility)
     */
    public void setDefaultTimeoutMillis(int timeout) {
        mDefaultTimeout = timeout;
    }

    int getDefaultTimeoutMillis() {
        return mDefaultTimeout;
    }

    /**
     * Cancel the current timeout, if any. To reset the delayed runnable use resetTimeout instead.
     */
    public void cancelTimeout() {
        if (DEBUG_DISMISS) {
            Log.d(TAG, "cancel timeout");
        }
        removeMessages(MESSAGE_CORNER_TIMEOUT);
    }

    /**
     * Reset the timeout.
     */
    public void resetTimeout() {
        cancelTimeout();

        AccessibilityManager accessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        // IchthysOS: Settings.System "ichthys_screenshot_preview_timeout" (seconds, 2-60) overrides
        // how long the screenshot preview stays; 0/unset keeps the default.
        final int userSeconds = Settings.System.getIntForUser(mContext.getContentResolver(),
                "ichthys_screenshot_preview_timeout", 0, UserHandle.USER_CURRENT);
        final int baseTimeout = userSeconds > 0
                ? Math.max(2, Math.min(60, userSeconds)) * 1000 : mDefaultTimeout;
        long timeoutMs = accessibilityManager.getRecommendedTimeoutMillis(
                baseTimeout,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);

        sendMessageDelayed(obtainMessage(MESSAGE_CORNER_TIMEOUT), timeoutMs);
        if (DEBUG_DISMISS) {
            Log.d(TAG, "dismiss timeout: " + timeoutMs + " ms");
        }
    }
}
