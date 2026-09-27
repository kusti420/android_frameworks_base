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

package com.android.systemui.smartpixels;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import com.android.systemui.CoreStartable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * IchthysOS: backend for the crDroid "Smart Pixels" screen
 * ({@code crDroidSettings/res/xml/smart_pixels.xml}).
 *
 * <p>Hosts a full-screen, untouchable overlay window that blacks out
 * {@code Settings.Secure.SMART_PIXEL_FILTER_PERCENT}% of the physical pixels whenever
 * {@code Settings.Secure.SMART_PIXEL_FILTER_ENABLED} is on. The blacked-out set is shifted
 * periodically so no pixel is dimmed more than any other over time -- see
 * {@link SmartPixelsPattern}.
 *
 * <p>The overlay is torn down while the screen is off: there is nothing to protect, and it would
 * otherwise keep posting shift callbacks.
 */
@SysUISingleton
public class SmartPixelsController implements CoreStartable {

    private static final String TAG = "SmartPixels";

    /** Time between pattern shifts while the screen is on. */
    static final long SHIFT_INTERVAL_MS = 30_000L;

    private final Context mContext;
    private final SecureSettings mSecureSettings;
    private final Handler mHandler;
    private final Executor mMainExecutor;
    private final BroadcastDispatcher mBroadcastDispatcher;

    @Nullable private WindowManager mWindowManager;
    @Nullable private SmartPixelsView mView;

    private boolean mEnabled;
    private boolean mScreenOn = true;
    private int mPercent = SmartPixelsPattern.DEFAULT_PERCENT;
    private int mStep;

    private final Runnable mShiftRunnable = new Runnable() {
        @Override
        public void run() {
            if (mView != null) {
                mStep = (mStep + 1) % SmartPixelsPattern.shiftCycleLength();
                mView.setStep(mStep);
            }
            mHandler.postDelayed(this, SHIFT_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mScreenOn = true;
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mScreenOn = false;
            } else {
                return;
            }
            updateOverlay();
        }
    };

    @Inject
    public SmartPixelsController(
            @Application Context context,
            SecureSettings secureSettings,
            @Main Handler handler,
            @Main Executor mainExecutor,
            BroadcastDispatcher broadcastDispatcher) {
        mContext = context;
        mSecureSettings = secureSettings;
        mHandler = handler;
        mMainExecutor = mainExecutor;
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    public void start() {
        mWindowManager = mContext.getSystemService(WindowManager.class);

        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                readSettings();
                updateOverlay();
            }
        };
        mSecureSettings.registerContentObserverForUserAsync(
                Settings.Secure.SMART_PIXEL_FILTER_ENABLED, observer, UserHandle.USER_ALL);
        mSecureSettings.registerContentObserverForUserAsync(
                Settings.Secure.SMART_PIXEL_FILTER_PERCENT, observer, UserHandle.USER_ALL);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mBroadcastDispatcher.registerReceiver(mScreenReceiver, filter, mMainExecutor, UserHandle.ALL,
                Context.RECEIVER_NOT_EXPORTED, null);

        readSettings();
        updateOverlay();
    }

    private void readSettings() {
        mEnabled = mSecureSettings.getInt(Settings.Secure.SMART_PIXEL_FILTER_ENABLED, 0) != 0;
        mPercent = SmartPixelsPattern.clampPercent(mSecureSettings.getInt(
                Settings.Secure.SMART_PIXEL_FILTER_PERCENT,
                SmartPixelsPattern.DEFAULT_PERCENT));
    }

    /** Adds, updates or removes the overlay so it matches the current settings + screen state. */
    private void updateOverlay() {
        final boolean wanted = mEnabled && mScreenOn;
        if (wanted) {
            if (mView == null) {
                addOverlay();
            } else {
                mView.setPercent(mPercent);
            }
        } else if (mView != null) {
            removeOverlay();
        }
    }

    private void addOverlay() {
        if (mWindowManager == null) {
            Log.w(TAG, "no WindowManager; cannot show the pixel filter");
            return;
        }
        final SmartPixelsView view = new SmartPixelsView(mContext);
        view.setPercent(mPercent);
        view.setStep(mStep);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                | WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
        lp.setFitInsetsTypes(0);
        lp.setTitle("SmartPixels");

        try {
            mWindowManager.addView(view, lp);
        } catch (RuntimeException e) {
            Log.e(TAG, "failed to add the pixel filter overlay", e);
            return;
        }
        mView = view;
        mHandler.removeCallbacks(mShiftRunnable);
        mHandler.postDelayed(mShiftRunnable, SHIFT_INTERVAL_MS);
        Log.i(TAG, "pixel filter on at " + mPercent + "%");
    }

    private void removeOverlay() {
        mHandler.removeCallbacks(mShiftRunnable);
        if (mView != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(mView);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to remove the pixel filter overlay", e);
            }
        }
        mView = null;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("SmartPixelsController:");
        pw.println("  enabled=" + mEnabled);
        pw.println("  screenOn=" + mScreenOn);
        pw.println("  percent=" + mPercent);
        pw.println("  dimmedCellsPerTile=" + SmartPixelsPattern.dimmedCells(mPercent)
                + "/" + SmartPixelsPattern.CELLS);
        pw.println("  step=" + mStep);
        pw.println("  overlayAttached=" + (mView != null));
    }
}
