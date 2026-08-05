/*
 * Copyright (C) 2016 The CyanogenMod Project
 * Copyright (C) 2019-2024 crDroid Android Project
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

package com.android.server.notification;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.MathUtils;
import android.util.Slog;

import java.io.PrintWriter;

/**
 * Blinks the camera flash (torch) while an incoming call is ringing.
 *
 * <p>Backs the crDroid Settings preferences:
 * <ul>
 *   <li>{@link Settings.System#FLASHLIGHT_ON_CALL} - mode (0 off, 1 when the ringer is audible,
 *       2 when the ringer is silent, 3 when entirely silent, 4 always)</li>
 *   <li>{@link Settings.System#FLASHLIGHT_ON_CALL_IGNORE_DND} - blink even when zen mode is on</li>
 *   <li>{@link Settings.System#FLASHLIGHT_ON_CALL_RATE} - blink rate in Hz, 1..5</li>
 * </ul>
 *
 * <p>This device has no notification LED, so the torch is driven directly through
 * {@link CameraManager#setTorchMode} rather than through any {@code LightsManager} path.
 *
 * <p>Threading: every field below is confined to {@link #mHandler}'s thread. The only entry points
 * callable from other threads are {@link #onCallStateChanged}, {@link #stopBlinking()} and
 * {@link #dump}, which post onto that handler (dump reads volatile snapshots). Nothing here ever
 * touches the NotificationManagerService lock, so the torch work can never deadlock the notification
 * pipeline.
 *
 * @hide
 */
public class FlashlightBlinkController {
    private static final String TAG = "FlashlightBlink";
    private static final boolean DEBUG = false;

    // Settings.System.FLASHLIGHT_ON_CALL values, matching crDroid's flashlight_on_call_values.
    private static final int MODE_DISABLED = 0;
    private static final int MODE_RINGER_AUDIBLE = 1;
    private static final int MODE_RINGER_SILENT = 2;
    private static final int MODE_ENTIRELY_SILENT = 3;
    private static final int MODE_ALWAYS = 4;

    // FLASHLIGHT_ON_CALL_RATE is expressed in Hz (blinks per second), seekbar range 1..5.
    // A full blink cycle at N Hz is 1000/N ms, so each half cycle (on, then off) is 500/N ms.
    private static final int HALF_CYCLE_BASE_MS = 500;
    private static final int MIN_RATE_HZ = 1;
    private static final int MAX_RATE_HZ = 5;
    private static final int DEFAULT_RATE_HZ = 1;

    // Hard watchdog. Nothing should keep the torch blinking longer than a ring cycle; if the stop
    // signal is ever lost we still guarantee the torch goes off rather than cooking the phone.
    private static final long MAX_BLINK_DURATION_MS = 120 * 1000L;

    private final Context mContext;

    private volatile Handler mHandler;
    private CameraManager mCameraManager;
    private String mCameraId;
    private boolean mCameraInitAttempted;

    /** True between a successful start and the matching stop. */
    private boolean mBlinking;
    /** Our own idea of the torch state, only meaningful while {@link #mBlinking}. */
    private boolean mTorchOn;
    /** Torch was turned on by somebody else (QS tile, power-button gesture, camera app). */
    private boolean mTorchHeldByOther;
    /** The torch is currently claimed by a camera client, so setTorchMode would fail. */
    private boolean mTorchUnavailable;
    private long mHalfCycleMs = HALF_CYCLE_BASE_MS;

    // Snapshots for dumpsys, written from the handler thread only. dump() runs under the
    // NotificationManagerService lock, so it must not make any binder call (no settings reads).
    private volatile boolean mBlinkingForDump;
    private volatile String mCameraIdForDump;
    private volatile int mLastModeForDump = -1;
    private volatile int mLastRateHzForDump = -1;
    private volatile boolean mLastDecisionForDump;

    public FlashlightBlinkController(Context context) {
        // Deliberately does no work: this is constructed from the NotificationAttentionHelper
        // constructor, which also runs under test with a mock context. Everything is lazy.
        mContext = context;
    }

    /**
     * Pre-warm the camera lookup and start tracking torch ownership, so the first incoming call
     * neither pays for camera enumeration nor mistakes a user-enabled torch for a free flash unit.
     */
    public void onSystemReady() {
        getHandler().post(guard(this::ensureCamera));
    }

    /**
     * Feed in telephony call state ({@link TelephonyManager#CALL_STATE_RINGING} and friends).
     * Safe to call from any thread and safe to call redundantly - starting is idempotent while
     * already blinking, and stopping is idempotent while already stopped.
     */
    public void onCallStateChanged(int state) {
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            getHandler().post(guard(this::handleRinging));
        } else {
            // CALL_STATE_OFFHOOK (answered / outgoing) and CALL_STATE_IDLE (missed, rejected,
            // cancelled by the caller, or the call ended) both mean "stop now".
            stopBlinking();
        }
    }

    /**
     * Stop blinking and force the torch off. Safe to call from any thread, at any time, however
     * many times. This is the single choke point every exit path goes through.
     */
    public void stopBlinking() {
        final Handler handler = mHandler;
        if (handler == null) {
            // The handler is only created on the way in, so nothing can be blinking yet.
            return;
        }
        handler.post(guard(this::handleStop));
    }

    // ------------------------------------------------------------------
    // Everything below runs on mHandler's thread.
    // ------------------------------------------------------------------

    /**
     * Wraps handler-thread work so that a stray runtime exception can never escape. This runs
     * inside system_server, where an uncaught exception on any thread kills the process and
     * boot-loops the device; and it drives the torch, which must not be left on. Both are handled
     * here: swallow, log, and force the torch off.
     */
    private Runnable guard(Runnable r) {
        return () -> runGuarded(r);
    }

    private void runGuarded(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            Slog.e(TAG, "Unexpected failure, aborting flashlight blink", e);
            mBlinking = false;
            mBlinkingForDump = false;
            mTorchOn = false;
            final Handler handler = mHandler;
            if (handler != null) {
                handler.removeCallbacks(mBlinkRunnable);
                handler.removeCallbacks(mWatchdogRunnable);
            }
            setTorchMode(false);
        }
    }

    private void handleRinging() {
        if (mBlinking) {
            return;
        }
        if (!shouldBlinkForIncomingCall()) {
            return;
        }
        if (!ensureCamera()) {
            return;
        }
        if (mTorchUnavailable) {
            if (DEBUG) Slog.d(TAG, "Torch unavailable, not blinking");
            return;
        }
        if (mTorchHeldByOther) {
            // The user already has the flashlight on deliberately. Blinking would end with us
            // switching off a torch we did not switch on, so leave it strictly alone.
            if (DEBUG) Slog.d(TAG, "Torch already on, not blinking");
            return;
        }

        mHalfCycleMs = readHalfCycleMs();
        mBlinking = true;
        mBlinkingForDump = true;
        mTorchOn = false;
        if (DEBUG) Slog.d(TAG, "Start blinking, half cycle " + mHalfCycleMs + "ms");
        mHandler.postDelayed(mWatchdogRunnable, MAX_BLINK_DURATION_MS);
        mHandler.post(mBlinkRunnable);
    }

    private void handleStop() {
        if (mHandler != null) {
            mHandler.removeCallbacks(mBlinkRunnable);
            mHandler.removeCallbacks(mWatchdogRunnable);
        }
        final boolean wasBlinking = mBlinking;
        mBlinking = false;
        mBlinkingForDump = false;
        if (wasBlinking) {
            // Unconditional: never trust mTorchOn here. If the last thing we did was switch the
            // torch on, or if we simply do not know, the torch must end up off.
            if (setTorchMode(false)) {
                // The torch is definitively off now, so ignore any in-flight "torch on" callback
                // from our own blinking that would otherwise look like a user-held torch.
                mTorchHeldByOther = false;
            }
        }
        mTorchOn = false;
    }

    // Kept as stable single instances so removeCallbacks() always finds them.
    private final Runnable mBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            runGuarded(FlashlightBlinkController.this::doBlinkStep);
        }
    };

    private final Runnable mWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            Slog.w(TAG, "Blink watchdog fired after " + MAX_BLINK_DURATION_MS
                    + "ms, forcing torch off");
            runGuarded(FlashlightBlinkController.this::handleStop);
        }
    };

    private void doBlinkStep() {
        if (!mBlinking) {
            return;
        }
        if (!setTorchMode(!mTorchOn)) {
            // Camera went away mid-blink. Bail out through the common stop path, which will
            // make one final best-effort attempt to switch the torch off.
            handleStop();
            return;
        }
        mTorchOn = !mTorchOn;
        mHandler.postDelayed(mBlinkRunnable, mHalfCycleMs);
    }

    /**
     * @return true if the torch was successfully driven to {@code on}.
     */
    private boolean setTorchMode(boolean on) {
        if (mCameraManager == null || mCameraId == null) {
            return false;
        }
        try {
            mCameraManager.setTorchMode(mCameraId, on);
            return true;
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            Slog.e(TAG, "Couldn't set torch mode to " + on, e);
            return false;
        }
    }

    private boolean ensureCamera() {
        if (mCameraId != null) {
            return true;
        }
        if (mCameraInitAttempted && mCameraManager != null) {
            // Enumeration already ran and found nothing usable; retry once per boot is enough.
            return false;
        }
        mCameraInitAttempted = true;

        final PackageManager pm = mContext.getPackageManager();
        if (pm == null || !pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            return false;
        }
        mCameraManager = mContext.getSystemService(CameraManager.class);
        if (mCameraManager == null) {
            return false;
        }
        try {
            for (String id : mCameraManager.getCameraIdList()) {
                final CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
                final Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                final Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
                if (flashAvailable != null && flashAvailable
                        && lensFacing != null
                        && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    mCameraId = id;
                    mCameraIdForDump = id;
                    break;
                }
            }
        } catch (CameraAccessException | IllegalStateException e) {
            Slog.e(TAG, "Couldn't enumerate cameras for flashlight-on-call", e);
            return false;
        }
        if (mCameraId == null) {
            return false;
        }
        // Delivered on our own handler, so the callback shares the same thread confinement as
        // everything else in this class.
        mCameraManager.registerTorchCallback(mTorchCallback, mHandler);
        return true;
    }

    private final CameraManager.TorchCallback mTorchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            runGuarded(() -> {
                if (!TextUtils.equals(cameraId, mCameraId)) {
                    return;
                }
                mTorchUnavailable = false;
                if (!mBlinking) {
                    mTorchHeldByOther = enabled;
                }
            });
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            runGuarded(() -> {
                if (!TextUtils.equals(cameraId, mCameraId)) {
                    return;
                }
                mTorchUnavailable = true;
                mTorchHeldByOther = false;
                if (mBlinking) {
                    // Somebody opened the camera. Give up rather than fight over the flash unit.
                    handleStop();
                }
            });
        }
    };

    // ------------------------------------------------------------------
    // Settings evaluation
    // ------------------------------------------------------------------

    private boolean shouldBlinkForIncomingCall() {
        final boolean decision = evaluateShouldBlink();
        mLastDecisionForDump = decision;
        return decision;
    }

    private boolean evaluateShouldBlink() {
        // Always read as USER_CURRENT: this is a per-user System setting and the notification
        // service itself runs as user 0.
        final int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.FLASHLIGHT_ON_CALL, MODE_DISABLED, UserHandle.USER_CURRENT);
        mLastModeForDump = mode;
        if (mode == MODE_DISABLED) {
            return false;
        }

        final boolean ringerAudible = isRingerAudible();
        final boolean shouldBlink;
        switch (mode) {
            case MODE_RINGER_AUDIBLE:
                shouldBlink = ringerAudible;
                break;
            case MODE_RINGER_SILENT:
                shouldBlink = !ringerAudible;
                break;
            case MODE_ENTIRELY_SILENT:
                shouldBlink = !ringerAudible && !isVibrationEnabledForRinging();
                break;
            case MODE_ALWAYS:
                shouldBlink = true;
                break;
            default:
                return false;
        }
        if (!shouldBlink) {
            return false;
        }

        final boolean ignoreDnd = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.FLASHLIGHT_ON_CALL_IGNORE_DND, 0, UserHandle.USER_CURRENT) == 1;
        if (!ignoreDnd) {
            // Read the persisted global value rather than calling into ZenModeHelper: that would
            // mean reaching for notification service state from this background thread.
            final int zenMode = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
            if (zenMode != Settings.Global.ZEN_MODE_OFF) {
                return false;
            }
        }
        return true;
    }

    private long readHalfCycleMs() {
        int rateHz = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.FLASHLIGHT_ON_CALL_RATE, DEFAULT_RATE_HZ, UserHandle.USER_CURRENT);
        rateHz = MathUtils.constrain(rateHz, MIN_RATE_HZ, MAX_RATE_HZ);
        mLastRateHzForDump = rateHz;
        return HALF_CYCLE_BASE_MS / rateHz;
    }

    private boolean isRingerAudible() {
        final AudioManager am = mContext.getSystemService(AudioManager.class);
        if (am == null) {
            return false;
        }
        return am.getRingerModeInternal() == AudioManager.RINGER_MODE_NORMAL
                && am.getStreamVolume(AudioManager.STREAM_RING) > 0;
    }

    private boolean isVibrationEnabledForRinging() {
        final Vibrator vibrator = mContext.getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return false;
        }
        final AudioManager am = mContext.getSystemService(AudioManager.class);
        if (am == null) {
            return false;
        }
        final int ringerMode = am.getRingerModeInternal();
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return false;
        }
        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            return true;
        }
        // RING_VIBRATION_INTENSITY is what Telecom's Ringer actually consults (the older
        // Settings.System.VIBRATE_WHEN_RINGING is deprecated), and a ramping ringer always starts
        // with a vibration even when the ring vibration itself is off.
        final int ringVibrationIntensity = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM, UserHandle.USER_CURRENT);
        return ringVibrationIntensity != Vibrator.VIBRATION_INTENSITY_OFF
                || am.isRampingRingerEnabled();
    }

    private Handler getHandler() {
        synchronized (this) {
            if (mHandler == null) {
                final HandlerThread thread = new HandlerThread(TAG,
                        Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                mHandler = new Handler(thread.getLooper());
            }
            return mHandler;
        }
    }

    /**
     * Called with the NotificationManagerService lock held, so this only prints cached snapshots
     * and never makes a binder call.
     */
    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "mFlashlightBlinking=" + mBlinkingForDump);
        pw.println(prefix + "mFlashlightCameraId=" + mCameraIdForDump);
        pw.println(prefix + "mFlashlightLastMode=" + mLastModeForDump
                + " lastRateHz=" + mLastRateHzForDump
                + " lastDecision=" + mLastDecisionForDump);
    }
}
