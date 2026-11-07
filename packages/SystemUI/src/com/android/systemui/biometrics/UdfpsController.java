/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static android.app.StatusBarManager.SESSION_BIOMETRIC_PROMPT;
import static android.app.StatusBarManager.SESSION_KEYGUARD;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_ENROLLING;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_FIND_SENSOR;

import static com.android.internal.util.LatencyTracker.ACTION_UDFPS_ILLUMINATE;
import static com.android.internal.util.LatencyTracker.ACTION_UDFPS_OVERLAY_ATTACHED_AFTER_GOING_TO_SLEEP;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.systemui.classifier.Classifier.UDFPS_AUTHENTICATION;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.SensorProperties;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.UserActivityNotifier;
import com.android.systemui.Dumpable;
import com.android.systemui.Flags;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.biometrics.dagger.BiometricsBackground;
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor;
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams;
import com.android.systemui.biometrics.udfps.InteractionEvent;
import com.android.systemui.biometrics.udfps.NormalizedTouchData;
import com.android.systemui.biometrics.udfps.SinglePointerTouchProcessor;
import com.android.systemui.biometrics.udfps.TouchProcessor;
import com.android.systemui.biometrics.udfps.TouchProcessorResult;
import com.android.systemui.biometrics.ui.viewmodel.DefaultUdfpsTouchOverlayViewModel;
import com.android.systemui.biometrics.ui.viewmodel.DeviceEntryUdfpsTouchOverlayViewModel;
import com.android.systemui.biometrics.ui.viewmodel.PromptUdfpsTouchOverlayViewModel;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.scene.domain.interactor.SceneInteractor;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.Execution;
import com.android.systemui.util.time.SystemClock;

import com.google.android.msdl.data.model.MSDLToken;
import com.google.android.msdl.domain.MSDLPlayer;

import dagger.Lazy;

import kotlin.Unit;

import kotlinx.coroutines.CoroutineScope;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Shows and hides the under-display fingerprint sensor (UDFPS) overlay, handles UDFPS touch events,
 * and toggles the UDFPS display mode.
 *
 * Note that the current architecture is designed so that a single {@link UdfpsController}
 * controls/manages all UDFPS sensors. In other words, a single controller is registered with
 * {@link com.android.server.biometrics.sensors.fingerprint.FingerprintService}, and interfaces such
 * as {@link FingerprintManager#onPointerDown(long, int, int, int, float, float)} or
 * {@link IUdfpsOverlayController#showUdfpsOverlay} should all have
 * {@code sensorId} parameters.
 */
@SuppressWarnings("deprecation")
@SysUISingleton
public class UdfpsController implements DozeReceiver, Dumpable {
    private static final String TAG = "UdfpsController";
    private static final long AOD_SEND_FINGER_UP_DELAY_MILLIS = 1000;
    /** IchthysOS: the longest onUiReady waits for the dim / illumination to draw (onFingerDown). */
    private static final long UI_READY_FALLBACK_MS = 250;

    private static final long MIN_UNCHANGED_INTERACTION_LOG_INTERVAL = 50;

    private final Context mContext;
    /** IchthysOS: for the panel's current brightness, the dim layer's input (computeAutoDimAlpha). */
    private final DisplayManager mDimDisplayManager;
    private final Execution mExecution;
    private final FingerprintManager mFingerprintManager;
    @NonNull private final LayoutInflater mInflater;
    private final UserActivityNotifier mUserActivityNotifier;
    private final WindowManager mWindowManager;
    private final DelayableExecutor mFgExecutor;
    @NonNull private final Executor mBiometricExecutor;
    @NonNull private final StatusBarStateController mStatusBarStateController;
    @NonNull private final KeyguardStateController mKeyguardStateController;
    @NonNull private final StatusBarKeyguardViewManager mKeyguardViewManager;
    @NonNull private final DumpManager mDumpManager;
    @NonNull private final SystemUIDialogManager mDialogManager;
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final DeviceEntryFaceAuthInteractor mDeviceEntryFaceAuthInteractor;
    @NonNull private final VibratorHelper mVibrator;
    @NonNull private final FalsingManager mFalsingManager;
    @NonNull private final PowerManager mPowerManager;
    @NonNull private final AccessibilityManager mAccessibilityManager;
    @NonNull private final ConfigurationController mConfigurationController;
    @NonNull private final SystemClock mSystemClock;
    @NonNull private final LatencyTracker mLatencyTracker;
    @VisibleForTesting @NonNull final BiometricDisplayListener mOrientationListener;
    @NonNull private final ActivityTransitionAnimator mActivityTransitionAnimator;
    @NonNull private final PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    @NonNull private final ShadeInteractor mShadeInteractor;
    @Nullable private final TouchProcessor mTouchProcessor;
    @NonNull private final SessionTracker mSessionTracker;
    @NonNull private final Lazy<DeviceEntryUdfpsTouchOverlayViewModel>
            mDeviceEntryUdfpsTouchOverlayViewModel;
    @NonNull private final Lazy<DefaultUdfpsTouchOverlayViewModel>
            mDefaultUdfpsTouchOverlayViewModel;
    @NonNull private final Lazy<PromptUdfpsTouchOverlayViewModel>
            mPromptUdfpsTouchOverlayViewModel;
    @NonNull private final AlternateBouncerInteractor mAlternateBouncerInteractor;
    @NonNull private final UdfpsOverlayInteractor mUdfpsOverlayInteractor;
    @NonNull private final PowerInteractor mPowerInteractor;
    @NonNull private final CoroutineScope mScope;
    @NonNull private final InputManager mInputManager;
    @NonNull private final SelectedUserInteractor mSelectedUserInteractor;
    @NonNull private final MSDLPlayer mMsdlPlayer;
    private final Lazy<SceneInteractor> mSceneInteractorLazy;
    private final boolean mIgnoreRefreshRate;
    private final KeyguardTransitionInteractor mKeyguardTransitionInteractor;

    // Currently the UdfpsController supports a single UDFPS sensor. If devices have multiple
    // sensors, this, in addition to a lot of the code here, will be updated.
    @VisibleForTesting @NonNull FingerprintSensorPropertiesInternal mSensorProps;
    @VisibleForTesting @NonNull UdfpsOverlayParams mOverlayParams = new UdfpsOverlayParams();
    // TODO(b/229290039): UDFPS controller should manage its dimensions on its own. Remove this.
    @Nullable private Runnable mAuthControllerUpdateUdfpsLocation;
    @Nullable private UdfpsDisplayModeProvider mUdfpsDisplayMode;

    // Optional custom "recognizing" animation played behind the UDFPS icon on the keyguard.
    // Loaded from the external com.crdroid.udfps.animations package; null when unavailable.
    @Nullable private UdfpsAnimation mUdfpsAnimation;

    // White circle overlay for optical UDFPS illumination (replaces removed UdfpsSurfaceView).
    @Nullable private View mIlluminationView;
    @Nullable private View mFodDimView;
    /** IchthysOS: invalidates a pending gated onUiReady when the finger lifts (see onFingerDown). */
    private long mUiReadyToken;
    // FOD dim layer: full-screen black overlay to reduce light bleed into the optical sensor.
    // Samsung's kernel DRM pipeline would normally create this, but the SurfaceFlinger UDFPS
    // extension is not present in this tree, so we create it manually here.

    // The ID of the pointer for which ACTION_DOWN has occurred. -1 means no pointer is active.
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    // Whether a pointer has been pilfered for current gesture
    private boolean mPointerPilfered = false;
    // The timestamp of the most recent touch log.
    private long mTouchLogTime;
    // The timestamp of the most recent log of a touch InteractionEvent.
    private long mLastTouchInteractionTime;
    // Sensor has a capture (good or bad) for this touch. No need to enable the UDFPS display mode
    // anymore for this particular touch event. In other words, do not enable the UDFPS mode until
    // the user touches the sensor area again.
    private boolean mAcquiredReceived;

    // The current request from FingerprintService. Null if no current request.
    @Nullable UdfpsControllerOverlay mOverlay;

    // The fingerprint AOD trigger doesn't provide an ACTION_UP/ACTION_CANCEL event to tell us when
    // to turn off high brightness mode. To get around this limitation, the state of the AOD
    // interrupt is being tracked and a timeout is used as a last resort to turn off high brightness
    // mode.
    private boolean mIsAodInterruptActive;
    @Nullable private Runnable mCancelAodFingerUpAction;
    private boolean mScreenOn;
    private Runnable mAodInterruptRunnable;
    private boolean mOnFingerDown;
    private boolean mAttemptedToDismissKeyguard;
    private final Set<Callback> mCallbacks = new HashSet<>();

    @VisibleForTesting
    public static final VibrationAttributes UDFPS_VIBRATION_ATTRIBUTES =
            new VibrationAttributes.Builder()
                    // vibration will bypass battery saver mode:
                    .setUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
                    .build();
    @VisibleForTesting
    public static final VibrationAttributes LOCK_ICON_VIBRATION_ATTRIBUTES =
            new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build();

    // haptic to use for successful device entry
    public static final VibrationEffect EFFECT_CLICK =
            VibrationEffect.get(VibrationEffect.EFFECT_CLICK);

    public static final int LONG_PRESS = HapticFeedbackConstants.LONG_PRESS;

    private final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOn() {
            mScreenOn = true;
            if (mAodInterruptRunnable != null) {
                mAodInterruptRunnable.run();
                mAodInterruptRunnable = null;
            }
        }

        @Override
        public void onScreenTurnedOff() {
            mScreenOn = false;
        }
    };

    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final WakefulnessLifecycle.Observer mWakefulnessLifecycleObserver =
            new WakefulnessLifecycle.Observer() {
                @Override
                public void onStartedGoingToSleep() {
                    mLatencyTracker.onActionCancel(
                            ACTION_UDFPS_OVERLAY_ATTACHED_AFTER_GOING_TO_SLEEP);
                    mLatencyTracker.onActionStart(
                            ACTION_UDFPS_OVERLAY_ATTACHED_AFTER_GOING_TO_SLEEP);
                }
            };

    private final View.OnAttachStateChangeListener mOverlayAttachStateListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View v) {
                    mLatencyTracker.onActionEnd(ACTION_UDFPS_OVERLAY_ATTACHED_AFTER_GOING_TO_SLEEP);
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View v) {}
            };

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        final int touchConfigId = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_selected_udfps_touch_detection);
        pw.println("mSensorProps=(" + mSensorProps + ")");
        pw.println("touchConfigId: " + touchConfigId);
    }

    public class UdfpsOverlayController extends IUdfpsOverlayController.Stub {
        @Override
        public void showUdfpsOverlay(long requestId, int sensorId, int reason,
                @NonNull IUdfpsOverlayControllerCallback callback) {
            mUdfpsOverlayInteractor.setRequestId(requestId);
            mFgExecutor.execute(() -> UdfpsController.this.showUdfpsOverlay(
                    new UdfpsControllerOverlay(
                        mInflater,
                        mWindowManager,
                        mAccessibilityManager,
                        mKeyguardUpdateMonitor,
                        mKeyguardStateController,
                        mUdfpsDisplayMode,
                        requestId,
                        reason,
                        callback,
                        (view, event) -> onTouch(
                            requestId,
                            event
                        ),
                        mKeyguardTransitionInteractor,
                        mDeviceEntryUdfpsTouchOverlayViewModel,
                        mDefaultUdfpsTouchOverlayViewModel,
                        mPromptUdfpsTouchOverlayViewModel,
                        mUdfpsOverlayInteractor,
                        mPowerInteractor,
                        mScope,
                        mSceneInteractorLazy
                    )));
        }

        @Override
        public void hideUdfpsOverlay(int sensorId) {
            mFgExecutor.execute(() -> {
                if (mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
                    // if we get here, we expect keyguardUpdateMonitor's fingerprintRunningState
                    // to be updated shortly afterwards
                    Log.d(TAG, "hiding udfps overlay when "
                            + "mKeyguardUpdateMonitor.isFingerprintDetectionRunning()=true");
                }

                UdfpsController.this.hideUdfpsOverlay();
            });
        }

        @Override
        public void onAcquired(
                int sensorId,
                @BiometricFingerprintConstants.FingerprintAcquired int acquiredInfo
        ) {
            if (isUltrasonic()) {
                if (acquiredInfo == FINGERPRINT_ACQUIRED_START) {
                    mFgExecutor.execute(() -> {
                        for (Callback cb : mCallbacks) {
                            cb.onFingerDown();
                        }
                    });
                } else {
                    mFgExecutor.execute(() -> {
                        for (Callback cb : mCallbacks) {
                            cb.onFingerUp();
                        }
                    });
                }
            }

            if (BiometricFingerprintConstants.shouldDisableUdfpsDisplayMode(acquiredInfo)) {
                boolean acquiredGood = acquiredInfo == FINGERPRINT_ACQUIRED_GOOD;
                mFgExecutor.execute(() -> {
                    if (mOverlay == null) {
                        Log.e(TAG, "Null request when onAcquired for sensorId: " + sensorId
                                + " acquiredInfo=" + acquiredInfo);
                        return;
                    }
                    mAcquiredReceived = true;
                    final View view = mOverlay.getTouchOverlay();
                    unconfigureDisplay(view);
                    tryAodSendFingerUp();
                });
            }
        }

        @Override
        public void onEnrollmentProgress(int sensorId, int remaining) { }

        @Override
        public void onEnrollmentHelp(int sensorId) { }

        @Override
        public void setDebugMessage(int sensorId, String message) {
            mFgExecutor.execute(() -> {
                if (mOverlay == null || mOverlay.isHiding()) {
                    return;
                }
            });
        }

        public Rect getSensorBounds() {
            return mOverlayParams.getSensorBounds();
        }

        /**
         * Passes a mocked MotionEvent to OnTouch.
         *
         * @param event MotionEvent to simulate in onTouch
         */
        public void debugOnTouch(MotionEvent event) {
            final long requestId = (mOverlay != null) ? mOverlay.getRequestId() : 0L;
            UdfpsController.this.onTouch(requestId, event);
        }

        /**
         * Debug to run onUiReady
         */
        public void debugOnUiReady(int sensorId) {
            final long requestId = (mOverlay != null) ? mOverlay.getRequestId() : 0L;
            UdfpsController.this.mFingerprintManager.onUdfpsUiEvent(
                    FingerprintManager.UDFPS_UI_READY, requestId, sensorId);
        }

        /**
         * Debug to show biometric prompt
         */
        public void debugBiometricPrompt() {
            final BiometricPrompt.AuthenticationCallback authenticationCallback =
                    new BiometricPrompt.AuthenticationCallback() {
                    };

            final BiometricPrompt biometricPrompt = new BiometricPrompt.Builder(mContext)
                    .setTitle("Test")
                    .setDeviceCredentialAllowed(true)
                    .setAllowBackgroundAuthentication(true)
                    .build();
            final Handler handler = new Handler(Looper.getMainLooper());
            biometricPrompt.authenticate(
                    new CancellationSignal(),
                    handler::post,
                    authenticationCallback);
        }

        /**
         * Debug to run setIgnoreDisplayTouches
         */
        public void debugSetIgnoreDisplayTouches(boolean ignoreTouch) {
            final long requestId = (mOverlay != null) ? mOverlay.getRequestId() : 0L;
            UdfpsController.this.mFingerprintManager.setIgnoreDisplayTouches(
                    requestId, mSensorProps.sensorId, ignoreTouch);
        }
    }

    /**
     * Updates the overlay parameters and reconstructs or redraws the overlay, if necessary.
     *
     * @param sensorProps   sensor for which the overlay is getting updated.
     * @param overlayParams See {@link UdfpsOverlayParams}.
     */
    public void updateOverlayParams(@NonNull FingerprintSensorPropertiesInternal sensorProps,
            @NonNull UdfpsOverlayParams overlayParams) {
        if (mSensorProps.sensorId != sensorProps.sensorId) {
            mSensorProps = sensorProps;
            Log.w(TAG, "updateUdfpsParams | sensorId has changed");
        }

        if (!mOverlayParams.equals(overlayParams)) {
            mOverlayParams = overlayParams;
            if (mUdfpsAnimation != null) {
                mUdfpsAnimation.setOverlayParams(mOverlayParams);
            }

            if (mOverlay != null && mOverlay.getRequestReason() == REASON_AUTH_KEYGUARD) {
                mOverlay.updateOverlayParams(mOverlayParams);
            } else {
                redrawOverlay();
            }
        }
    }

    // TODO(b/229290039): UDFPS controller should manage its dimensions on its own. Remove this.
    public void setAuthControllerUpdateUdfpsLocation(@Nullable Runnable r) {
        mAuthControllerUpdateUdfpsLocation = r;
    }

    public void setUdfpsDisplayMode(@NonNull UdfpsDisplayModeProvider udfpsDisplayMode) {
        mUdfpsDisplayMode = udfpsDisplayMode;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mOverlay != null
                    && mOverlay.getRequestReason() != REASON_AUTH_KEYGUARD
                    && Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                String reason = intent.getStringExtra("reason");
                reason = (reason != null) ? reason : "unknown";
                Log.d(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS received, reason: " + reason
                        + ", mRequestReason: " + mOverlay.getRequestReason());

                mOverlay.cancel();
                hideUdfpsOverlay();
            }
        }
    };

    private void tryDismissingKeyguard() {
        if (!mOnFingerDown) {
            playStartHaptic();
        }
        mKeyguardViewManager.notifyKeyguardAuthenticated(false /* primaryAuth */);
        mAttemptedToDismissKeyguard = true;
    }

    private int getBiometricSessionType() {
        if (mOverlay == null) {
            return -1;
        }
        switch (mOverlay.getRequestReason()) {
            case REASON_AUTH_KEYGUARD:
                return SESSION_KEYGUARD;
            case REASON_AUTH_BP:
                return SESSION_BIOMETRIC_PROMPT;
            case REASON_ENROLL_FIND_SENSOR:
            case REASON_ENROLL_ENROLLING:
                // TODO(b/255634916): create a reason for enrollment (or an "unknown" reason).
                return SESSION_BIOMETRIC_PROMPT << 1;
            default:
                return -1;
        }
    }

    private static int toBiometricTouchReportedTouchType(InteractionEvent event) {
        switch (event) {
            case DOWN:
                return SysUiStatsLog.BIOMETRIC_TOUCH_REPORTED__TOUCH_TYPE__TOUCH_TYPE_DOWN;
            case UP:
                return SysUiStatsLog.BIOMETRIC_TOUCH_REPORTED__TOUCH_TYPE__TOUCH_TYPE_UP;
            case CANCEL:
                return SysUiStatsLog.BIOMETRIC_TOUCH_REPORTED__TOUCH_TYPE__TOUCH_TYPE_CANCEL;
            default:
                return SysUiStatsLog.BIOMETRIC_TOUCH_REPORTED__TOUCH_TYPE__TOUCH_TYPE_UNCHANGED;
        }
    }

    private void logBiometricTouch(InteractionEvent event, NormalizedTouchData data) {
        if (event == InteractionEvent.UNCHANGED) {
            long sinceLastLog = mSystemClock.elapsedRealtime() - mLastTouchInteractionTime;
            if (sinceLastLog < MIN_UNCHANGED_INTERACTION_LOG_INTERVAL) {
                return;
            }
        }
        mLastTouchInteractionTime = mSystemClock.elapsedRealtime();

        final int biometricTouchReportedTouchType = toBiometricTouchReportedTouchType(event);
        final InstanceId sessionIdProvider = mSessionTracker.getSessionId(
                getBiometricSessionType());
        final int sessionId = (sessionIdProvider != null) ? sessionIdProvider.getId() : -1;
        final int touchConfigId = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_selected_udfps_touch_detection);

        SysUiStatsLog.write(SysUiStatsLog.BIOMETRIC_TOUCH_REPORTED, biometricTouchReportedTouchType,
                touchConfigId, sessionId, data.getX(), data.getY(), data.getMinor(),
                data.getMajor(), data.getOrientation(), data.getTime(), data.getGestureStart(),
                mStatusBarStateController.isDozing());

        if (Build.isDebuggable()) {
            Log.d(TAG, data.toPrettyString(event.toString()));
            Log.d(TAG, "sessionId: " + sessionId
                    + ", isAod: " + mStatusBarStateController.isDozing()
                    + ", touchConfigId: " + touchConfigId);
        }
    }

    private boolean onTouch(long requestId, @NonNull MotionEvent event) {
        if (mOverlay == null) {
            Log.w(TAG, "ignoring onTouch with null overlay");
            return false;
        }
        if (!mOverlay.matchesRequestId(requestId)) {
            Log.w(TAG, "ignoring stale touch event: " + requestId + " current: "
                    + mOverlay.getRequestId());
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN
                || event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
            // Reset on ACTION_DOWN, start of new gesture
            mPointerPilfered = false;
            if (mActivePointerId != MotionEvent.INVALID_POINTER_ID) {
                Log.w(TAG, "onTouch down received without a preceding up");
            }
            mActivePointerId = MotionEvent.INVALID_POINTER_ID;

            // It's possible on some devices to get duplicate touches from both doze and the
            // normal touch listener. Don't reset the down in this case to avoid duplicate downs
            if (!mIsAodInterruptActive) {
                // IchthysOS: a new gesture while the last press never got its UP. Clearing the
                // flag alone made the next UP skip onPointerUp, so the HAL kept the finger down
                // and the mask HBM could stay on; finish the old press properly first.
                if (mOnFingerDown && mOverlay.getTouchOverlay() != null) {
                    Log.w(TAG, "touch down while the last press is still down: ending it");
                    onFingerUp(requestId, mOverlay.getTouchOverlay());
                }
                mOnFingerDown = false;
            }
        }

        final TouchProcessorResult result = mTouchProcessor.processTouch(event, mActivePointerId,
                mOverlayParams);
        if (result instanceof TouchProcessorResult.Failure) {
            Log.w(TAG, ((TouchProcessorResult.Failure) result).getReason());
            return false;
        }

        final TouchProcessorResult.ProcessedTouch processedTouch =
                (TouchProcessorResult.ProcessedTouch) result;
        final NormalizedTouchData data = processedTouch.getTouchData();

        boolean shouldPilfer = false;
        mActivePointerId = processedTouch.getPointerOnSensorId();
        switch (processedTouch.getEvent()) {
            case DOWN:
                if (shouldTryToDismissKeyguard()) {
                    tryDismissingKeyguard();
                }
                if (!mOnFingerDown) {
                    onFingerDown(requestId,
                            data.getPointerId(),
                            data.getX(),
                            data.getY(),
                            data.getMinor(),
                            data.getMajor(),
                            data.getOrientation(),
                            data.getTime(),
                            data.getGestureStart(),
                            mStatusBarStateController.isDozing());
                }

                // Pilfer if valid overlap, don't allow following events to reach keyguard
                shouldPilfer = true;

                // Touch is a valid UDFPS touch. Inform the falsing manager so that the touch
                // isn't counted against the falsing algorithm as an accidental touch.
                // We do this on the DOWN event instead of CANCEL/UP because the CANCEL/UP events
                // get sent too late to this receiver (after the actual cancel/up motions occur),
                // and therefore wouldn't end up being used as part of the falsing algo.
                mFalsingManager.isFalseTouch(UDFPS_AUTHENTICATION);
                break;

            case UP:
            case CANCEL:
                if (InteractionEvent.CANCEL.equals(processedTouch.getEvent())) {
                    Log.w(TAG, "This is a CANCEL event that's reported as an UP event!");
                }
                mAttemptedToDismissKeyguard = false;
                onFingerUp(requestId,
                        mOverlay.getTouchOverlay(),
                        data.getPointerId(),
                        data.getX(),
                        data.getY(),
                        data.getMinor(),
                        data.getMajor(),
                        data.getOrientation(),
                        data.getTime(),
                        data.getGestureStart(),
                        mStatusBarStateController.isDozing());
                break;

            default:
                break;
        }
        logBiometricTouch(processedTouch.getEvent(), data);

        // Always pilfer pointers that are within sensor area or when alternate bouncer is showing
        if (mActivePointerId != MotionEvent.INVALID_POINTER_ID) {
            shouldPilfer = true;
        }

        // Pilfer only once per gesture, don't pilfer for BP
        if (shouldPilfer && !mPointerPilfered
                && getBiometricSessionType() != SESSION_BIOMETRIC_PROMPT) {
            mInputManager.pilferPointers(
                    mOverlay.getTouchOverlay().getViewRootImpl().getInputToken());
            mPointerPilfered = true;
        }

        return mActivePointerId != MotionEvent.INVALID_POINTER_ID;
    }

    private boolean shouldTryToDismissKeyguard() {
        boolean onKeyguard = mKeyguardStateController.isShowing();
        return onKeyguard
                && mKeyguardStateController.canDismissLockScreen()
                && !mAttemptedToDismissKeyguard;
    }

    @Inject
    public UdfpsController(@NonNull @Main Context context,
            @NonNull Execution execution,
            @NonNull LayoutInflater inflater,
            @Nullable FingerprintManager fingerprintManager,
            @NonNull @Main WindowManager windowManager,
            @NonNull StatusBarStateController statusBarStateController,
            @Main DelayableExecutor fgExecutor,
            @NonNull StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            @NonNull DumpManager dumpManager,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull FalsingManager falsingManager,
            @NonNull PowerManager powerManager,
            @NonNull AccessibilityManager accessibilityManager,
            @NonNull ScreenLifecycle screenLifecycle,
            @NonNull VibratorHelper vibrator,
            @NonNull UdfpsHapticsSimulator udfpsHapticsSimulator,
            @NonNull UdfpsShell udfpsShell,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull DisplayManager displayManager,
            @Main Handler mainHandler,
            @NonNull @Main ConfigurationController configurationController,
            @NonNull SystemClock systemClock,
            @NonNull SystemUIDialogManager dialogManager,
            @NonNull LatencyTracker latencyTracker,
            @NonNull ActivityTransitionAnimator activityTransitionAnimator,
            @NonNull @BiometricsBackground Executor biometricsExecutor,
            @NonNull PrimaryBouncerInteractor primaryBouncerInteractor,
            @NonNull ShadeInteractor shadeInteractor,
            @NonNull SinglePointerTouchProcessor singlePointerTouchProcessor,
            @NonNull SessionTracker sessionTracker,
            @NonNull AlternateBouncerInteractor alternateBouncerInteractor,
            @NonNull InputManager inputManager,
            @NonNull DeviceEntryFaceAuthInteractor deviceEntryFaceAuthInteractor,
            @NonNull SelectedUserInteractor selectedUserInteractor,
            @NonNull KeyguardTransitionInteractor keyguardTransitionInteractor,
            Lazy<DeviceEntryUdfpsTouchOverlayViewModel> deviceEntryUdfpsTouchOverlayViewModel,
            Lazy<DefaultUdfpsTouchOverlayViewModel> defaultUdfpsTouchOverlayViewModel,
            Lazy<PromptUdfpsTouchOverlayViewModel> promptUdfpsTouchOverlayViewModel,
            @NonNull UdfpsOverlayInteractor udfpsOverlayInteractor,
            @NonNull PowerInteractor powerInteractor,
            @Application CoroutineScope scope,
            UserActivityNotifier userActivityNotifier,
            Lazy<WakefulnessLifecycle> wakefulnessLifecycle,
            MSDLPlayer msdlPlayer,
            Lazy<SceneInteractor> sceneInteractorLazy) {
        mContext = context;
        mDimDisplayManager = displayManager;
        mExecution = execution;
        mVibrator = vibrator;
        mInflater = inflater;
        mUserActivityNotifier = userActivityNotifier;
        mIgnoreRefreshRate = mContext.getResources()
                    .getBoolean(R.bool.config_ignoreUdfpsVote);
        // The fingerprint manager is queried for UDFPS before this class is constructed, so the
        // fingerprint manager should never be null.
        mFingerprintManager = checkNotNull(fingerprintManager);
        mWindowManager = windowManager;
        mFgExecutor = fgExecutor;
        mStatusBarStateController = statusBarStateController;
        mKeyguardStateController = keyguardStateController;
        mKeyguardViewManager = statusBarKeyguardViewManager;
        mDumpManager = dumpManager;
        mDialogManager = dialogManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mFalsingManager = falsingManager;
        mPowerManager = powerManager;
        mAccessibilityManager = accessibilityManager;
        screenLifecycle.addObserver(mScreenObserver);
        mScreenOn = screenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_ON;
        mConfigurationController = configurationController;
        mSystemClock = systemClock;
        mLatencyTracker = latencyTracker;
        mActivityTransitionAnimator = activityTransitionAnimator;
        mSensorProps = new FingerprintSensorPropertiesInternal(
                -1 /* sensorId */,
                SensorProperties.STRENGTH_CONVENIENCE,
                0 /* maxEnrollmentsPerUser */,
                new ArrayList<>() /* componentInfo */,
                FingerprintSensorProperties.TYPE_UNKNOWN,
                false /* resetLockoutRequiresHardwareAuthToken */);

        mBiometricExecutor = biometricsExecutor;
        mPrimaryBouncerInteractor = primaryBouncerInteractor;
        mShadeInteractor = shadeInteractor;
        mAlternateBouncerInteractor = alternateBouncerInteractor;
        mUdfpsOverlayInteractor = udfpsOverlayInteractor;
        mPowerInteractor = powerInteractor;
        mScope = scope;
        mInputManager = inputManager;
        mSelectedUserInteractor = selectedUserInteractor;
        mKeyguardTransitionInteractor = keyguardTransitionInteractor;

        mTouchProcessor = singlePointerTouchProcessor;
        mSessionTracker = sessionTracker;
        mDeviceEntryUdfpsTouchOverlayViewModel = deviceEntryUdfpsTouchOverlayViewModel;
        mDefaultUdfpsTouchOverlayViewModel = defaultUdfpsTouchOverlayViewModel;
        mPromptUdfpsTouchOverlayViewModel = promptUdfpsTouchOverlayViewModel;
        mMsdlPlayer = msdlPlayer;
        mSceneInteractorLazy = sceneInteractorLazy;

        mDumpManager.registerDumpable(TAG, this);

        mOrientationListener = new BiometricDisplayListener(
                context,
                displayManager,
                mainHandler,
                BiometricDisplayListener.SensorType.UnderDisplayFingerprint.INSTANCE,
                () -> {
                    if (mAuthControllerUpdateUdfpsLocation != null) {
                        mAuthControllerUpdateUdfpsLocation.run();
                    }
                    return Unit.INSTANCE;
                });
        mDeviceEntryFaceAuthInteractor = deviceEntryFaceAuthInteractor;

        final UdfpsOverlayController mUdfpsOverlayController = new UdfpsOverlayController();
        mFingerprintManager.setUdfpsOverlayController(mUdfpsOverlayController);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mBroadcastReceiver, filter,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        udfpsHapticsSimulator.setUdfpsController(this);
        udfpsShell.setUdfpsOverlayController(mUdfpsOverlayController);

        mWakefulnessLifecycle = wakefulnessLifecycle.get();
        if (mWakefulnessLifecycle != null) {
            mWakefulnessLifecycle.addObserver(mWakefulnessLifecycleObserver);
        }

        // IchthysOS: lift nothing until a finger is really down (see mIchthysUdfpsScanning).
        setIchthysUdfpsScanning(false);
    }

    /**
     * If a11y touchExplorationEnabled, play haptic to signal UDFPS scanning started.
     */
    @VisibleForTesting
    public void playStartHaptic() {
        if (mAccessibilityManager.isTouchExplorationEnabled()) {
            if (mOverlay != null && mOverlay.getTouchOverlay() != null) {
                mVibrator.performHapticFeedback(
                        mOverlay.getTouchOverlay(),
                        HapticFeedbackConstants.CONTEXT_CLICK
                );
            } else {
                Log.e(TAG, "No haptics played. Could not obtain overlay view to perform"
                        + "vibration. Either the controller overlay is null or has no view");
            }
        }
    }

    @Override
    public void dozeTimeTick() {

    }

    private void redrawOverlay() {
        UdfpsControllerOverlay overlay = mOverlay;
        if (overlay != null) {
            hideUdfpsOverlay();
            showUdfpsOverlay(overlay);
        }
    }

    private void showUdfpsOverlay(@NonNull UdfpsControllerOverlay overlay) {
        mExecution.assertIsMainThread();

        if (mOverlay != null) {
            Log.d(TAG, "showUdfpsOverlay | the overlay is already showing");
            return;
        }

        mOverlay = overlay;
        final int requestReason = overlay.getRequestReason();
        if (requestReason == REASON_AUTH_KEYGUARD
                && !mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
            Log.d(TAG, "Attempting to showUdfpsOverlay when fingerprint detection"
                    + " isn't running on keyguard. Skip show.");
            return;
        }
        final View.OnAttachStateChangeListener listener =
                mWakefulnessLifecycle == null ? null : mOverlayAttachStateListener;
        if (overlay.show(mOverlayParams, listener)) {
            Log.d(TAG, "showUdfpsOverlay | adding window reason=" + requestReason);
            mOnFingerDown = false;
            mAttemptedToDismissKeyguard = false;
            mOrientationListener.enable();
            // IchthysOS: (re)create the press-effects window with the current sensor geometry.
            // It decides per request reason (keyguard / app prompt / enrollment) whether to play.
            updateUdfpsAnimation();
            if (mUdfpsAnimation != null) {
                mUdfpsAnimation.setRequestReason(requestReason);
            }
            if (mFingerprintManager != null) {
                mFingerprintManager.onUdfpsUiEvent(FingerprintManager.UDFPS_UI_OVERLAY_SHOWN,
                        overlay.getRequestId(), mSensorProps.sensorId);
            }
            // Put the stray-light dim up once for the whole FOD session so it
            // never flashes per touch. Only the illumination cycles per touch.
            if (isOptical() && isDimSessionHeld()) {
                showDimLayer();
            }
        } else {
            Log.d(TAG, "showUdfpsOverlay | the overlay is already showing");
        }
    }

    private void hideUdfpsOverlay() {
        com.android.systemui.keyguard.data.repository.IchthysDepthRepository.fingerOnSensor
                .setValue(false);
        setIchthysUdfpsScanning(false);
        mExecution.assertIsMainThread();

        if (mOverlay != null) {
            // Reset the controller back to its starting state.
            final View oldView = mOverlay.getTouchOverlay();
            if (oldView != null) {
                onFingerUp(mOverlay.getRequestId(), oldView);
            }
            if (mUdfpsAnimation != null) {
                mUdfpsAnimation.setRequestReason(-1);
            }
            final boolean removed = mOverlay.hide();
            hideIlluminationOverlay();
            hideDimLayer();
            mKeyguardViewManager.hideAlternateBouncer(
                    /* updateScrim */ true,
                    /* clearDismissAction */ false
            );
            Log.v(TAG, "hideUdfpsOverlay | removing window: " + removed);
        } else {
            Log.v(TAG, "hideUdfpsOverlay | the overlay is already hidden");
            // IchthysOS: nothing of a touch may outlive its overlay (an illumination disc or dim
            // left over the launcher after the unlock).
            if (mIlluminationView != null || mFodDimView != null) {
                Log.w(TAG, "hideUdfpsOverlay | removing stray illumination/dim windows");
                hideIlluminationOverlay();
                hideDimLayer();
            }
        }

        mOverlay = null;
        mOrientationListener.disable();
    }

    private void unconfigureDisplay(View view) {
        if (!isOptical()) {
            return;
        }
        hideIlluminationOverlay();
        if (mUdfpsDisplayMode != null) {
            mUdfpsDisplayMode.disable(null);
        }
    }

    /**
     * Request fingerprint scan.
     *
     * This is intended to be called in response to a sensor that triggers an AOD interrupt for the
     * fingerprint sensor.
     */
    void onAodInterrupt(int screenX, int screenY, float major, float minor) {
        if (mIsAodInterruptActive) {
            return;
        }

        if (!mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
            if (mFalsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)) {
                Log.v(TAG, "aod lock icon long-press rejected by the falsing manager.");
                return;
            }
            mKeyguardViewManager.showPrimaryBouncer(true, "UdfpsController#onAodInterrupt");

            // play the same haptic as the DeviceEntryIcon longpress
            if (Flags.msdlFeedback()) {
                mMsdlPlayer.playToken(MSDLToken.LONG_PRESS, null);
            } else {
                if (mOverlay != null && mOverlay.getTouchOverlay() != null) {
                    mVibrator.performHapticFeedback(
                            mOverlay.getTouchOverlay(),
                            UdfpsController.LONG_PRESS
                    );
                } else {
                    Log.e(TAG, "No haptics played. Could not obtain overlay view to perform"
                            + "vibration. Either the controller overlay is null or has no view");
                }
            }
            return;
        }

        // TODO(b/225068271): this may not be correct but there isn't a way to track it
        final long requestId = mOverlay != null ? mOverlay.getRequestId() : -1;
        mAodInterruptRunnable = () -> {
            mIsAodInterruptActive = true;
            // Since the sensor that triggers the AOD interrupt doesn't provide
            // ACTION_UP/ACTION_CANCEL,  we need to be careful about not letting the screen
            // accidentally remain in high brightness mode. As a mitigation, queue a call to
            // cancel the fingerprint scan.
            mCancelAodFingerUpAction = mFgExecutor.executeDelayed(this::tryAodSendFingerUp,
                    AOD_SEND_FINGER_UP_DELAY_MILLIS);
            // using a hard-coded value for orientation, time and gestureStart until they are
            // available from the sensor.
            onFingerDown(
                    requestId,
                    MotionEvent.INVALID_POINTER_ID /* pointerId */,
                    screenX,
                    screenY,
                    minor,
                    major,
                    0f /* orientation */,
                    0L /* time */,
                    0L /* gestureStart */,
                    true /* isAod */);
        };

        if (isScreenOffUnlockEnabled() || mScreenOn) {
            mAodInterruptRunnable.run();
            mAodInterruptRunnable = null;
        }
    }

    private boolean isScreenOffUnlockEnabled() {
        return mContext.getResources().getBoolean(R.bool.config_screen_off_udfps_enabled)
                && Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.SCREEN_OFF_UNLOCK_UDFPS_ENABLED,
                        0,
                        mContext.getUserId()) != 0;
    }

    /**
     * Add a callback for fingerUp and fingerDown events
     */
    public void addCallback(Callback cb) {
        mCallbacks.add(cb);
    }

    /**
     * Remove callback
     */
    public void removeCallback(Callback cb) {
        mCallbacks.remove(cb);
    }

    /**
     * The sensor that triggers {@link #onAodInterrupt} doesn't emit ACTION_UP or ACTION_CANCEL
     * events, which means the fingerprint gesture created by the AOD interrupt needs to be
     * cancelled manually.
     * This should be called when authentication either succeeds or fails. Failing to cancel the
     * scan will leave the display in the UDFPS mode until the user lifts their finger. On optical
     * sensors, this can result in illumination persisting for longer than necessary.
     */
    @VisibleForTesting
    void tryAodSendFingerUp() {
        if (!mIsAodInterruptActive) {
            return;
        }
        cancelAodSendFingerUpAction();
        if (mOverlay != null && mOverlay.getTouchOverlay() != null) {
            onFingerUp(mOverlay.getRequestId(), mOverlay.getTouchOverlay());
        }
    }

    /**
     * Cancels any scheduled AoD finger-up actions without triggered the finger-up action. Only
     * call this method if the finger-up event has been guaranteed to have already occurred.
     */
    @VisibleForTesting
    void cancelAodSendFingerUpAction() {
        mIsAodInterruptActive = false;
        if (mCancelAodFingerUpAction != null) {
            mCancelAodFingerUpAction.run();
            mCancelAodFingerUpAction = null;
        }
    }

    private boolean isOptical() {
        return mSensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL;
    }

    private boolean isUltrasonic() {
        return mSensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC;
    }

    public boolean isFingerDown() {
        return mOnFingerDown;
    }

    private void dispatchOnUiReady(long requestId) {
        mFingerprintManager.onUdfpsUiEvent(FingerprintManager.UDFPS_UI_READY, requestId,
                mSensorProps.sensorId);
        mLatencyTracker.onActionEnd(LatencyTracker.ACTION_UDFPS_ILLUMINATE);
    }

    private void onFingerDown(
            long requestId,
            int x,
            int y,
            float minor,
            float major) {
        onFingerDown(
                requestId,
                MotionEvent.INVALID_POINTER_ID /* pointerId */,
                x,
                y,
                minor,
                major,
                0f /* orientation */,
                0L /* time */,
                0L /* gestureStart */,
                false /* isAod */);
    }

    private void onFingerDown(
            long requestId,
            int pointerId,
            float x,
            float y,
            float minor,
            float major,
            float orientation,
            long time,
            long gestureStart,
            boolean isAod) {
        mExecution.assertIsMainThread();

        if (mOverlay == null) {
            Log.w(TAG, "Null request in onFingerDown");
            return;
        }
        if (!mOverlay.matchesRequestId(requestId)) {
            Log.w(TAG, "Mismatched fingerDown: " + requestId
                    + " current: " + mOverlay.getRequestId());
            return;
        }
        if (isOptical()) {
            mLatencyTracker.onActionStart(ACTION_UDFPS_ILLUMINATE);
        }
        // Refresh screen timeout and boost process priority if possible.
        if (Flags.bouncerUiRevamp()) {
            mUserActivityNotifier.notifyUserActivity(mSystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        } else {
            mPowerManager.userActivity(mSystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
        }

        if (!mOnFingerDown) {
            playStartHaptic();

            mDeviceEntryFaceAuthInteractor.onUdfpsSensorTouched();
        }
        mOnFingerDown = true;
        // IchthysOS depth lock screen: its own wallpaper copy and cut-out step aside while a
        // finger is on the sensor, leaving only the real wallpaper and the sensor's light.
        // Set only past the early returns above: nothing would clear them for a dropped down.
        com.android.systemui.keyguard.data.repository.IchthysDepthRepository.fingerOnSensor
                .setValue(true);
        // IchthysOS: Night Light / Extra dim lift while the sensor scans (ColorDisplayService):
        // tinted light over the finger makes every optical capture fail.
        setIchthysUdfpsScanning(true);
        if (isOptical()) {
            Log.e(TAG, "onFingerDown | isOptical=true, calling showIlluminationOverlay");
            showIlluminationOverlay();
        }
        mFingerprintManager.onPointerDown(requestId, mSensorProps.sensorId, pointerId, x, y,
                minor, major, orientation, time, gestureStart, isAod);
        Trace.endAsyncSection("UdfpsController.e2e.onPointerDown", 0);

        final View view = mOverlay.getTouchOverlay();
        if (view != null && isOptical()) {
            // IchthysOS: onUiReady is what makes the HAL raise the whole-panel mask HBM. The dim
            // and the disc were only just added as windows; dispatching now lets HBM land a frame
            // before them, which the eye sees as a full-screen flash. Wait until the dim (or the
            // disc, when there is no dim) has drawn once, then one more frame for SurfaceFlinger
            // to latch it.
            final Runnable ready = () -> {
                if (mIgnoreRefreshRate) {
                    dispatchOnUiReady(requestId);
                } else {
                    mUdfpsDisplayMode.enable(() -> dispatchOnUiReady(requestId));
                }
            };
            final View gate = mFodDimView != null ? mFodDimView : mIlluminationView;
            if (gate != null) {
                // The windows were just added, so the view is not attached yet: wait for the
                // first draw, then a frame. If the finger lifted meanwhile (the overlay is gone)
                // do nothing: a late onUiReady would raise HBM and request the UDFPS display mode
                // with nothing to release them.
                final long token = ++mUiReadyToken;
                final android.view.ViewTreeObserver.OnDrawListener[] self =
                        new android.view.ViewTreeObserver.OnDrawListener[1];
                // IchthysOS: once per touch, whichever comes first (the gate's frame or the
                // fallback below), and only while this touch's illumination is still up.
                final boolean[] fired = new boolean[1];
                final Runnable fire = () -> {
                    if (fired[0] || token != mUiReadyToken || mIlluminationView == null) {
                        return;
                    }
                    fired[0] = true;
                    if (gate.getViewTreeObserver().isAlive()) {
                        gate.getViewTreeObserver().removeOnDrawListener(self[0]);
                    }
                    ready.run();
                };
                self[0] = () -> gate.post(() -> {
                    if (gate.getViewTreeObserver().isAlive()) {
                        gate.getViewTreeObserver().removeOnDrawListener(self[0]);
                    }
                    gate.postOnAnimation(fire);
                });
                // IchthysOS: never wait for ever. A gate that does not draw again (a session-held
                // dim layer that drew long ago and is static, a window that is not drawing) left
                // the sensor scanning without HBM: no match until the next lock. Then onUiReady
                // goes out a little late instead.
                mFgExecutor.executeDelayed(fire, UI_READY_FALLBACK_MS);
                if (gate.isAttachedToWindow()) {
                    gate.getViewTreeObserver().addOnDrawListener(self[0]);
                } else {
                    gate.addOnAttachStateChangeListener(
                            new View.OnAttachStateChangeListener() {
                                @Override
                                public void onViewAttachedToWindow(View v) {
                                    v.removeOnAttachStateChangeListener(this);
                                    v.getViewTreeObserver().addOnDrawListener(self[0]);
                                }

                                @Override
                                public void onViewDetachedFromWindow(View v) {
                                    v.removeOnAttachStateChangeListener(this);
                                }
                            });
                }
            } else {
                ready.run();
            }
        }

        if (isOptical()) {
            for (Callback cb : mCallbacks) {
                cb.onFingerDown();
            }
            showUdfpsAnimation();
        }
    }

    /**
     * See ColorDisplayService#ICHTHYS_UDFPS_SCANNING. Only writes on a change. Starts true so the
     * constructor's write of 0 always goes out: a SystemUI that died mid-scan left a 1 behind.
     */
    private boolean mIchthysUdfpsScanning = true;

    private void setIchthysUdfpsScanning(boolean scanning) {
        if (scanning == mIchthysUdfpsScanning) {
            return;
        }
        mIchthysUdfpsScanning = scanning;
        // IchthysOS: written synchronously on purpose. The lift must be under way before the
        // illumination and the first capture (the async write saved ~1 ms and risked a tinted
        // capture, which is exactly the failure this flag exists to prevent).
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                "ichthys_udfps_scanning", scanning ? 1 : 0);
    }

    private void onFingerUp(long requestId, @NonNull View view) {
        onFingerUp(
                requestId,
                view,
                MotionEvent.INVALID_POINTER_ID /* pointerId */,
                0f /* x */,
                0f /* y */,
                0f /* minor */,
                0f /* major */,
                0f /* orientation */,
                0L /* time */,
                0L /* gestureStart */,
                false /* isAod */);
    }

    private void onFingerUp(
            long requestId,
            View view,
            int pointerId,
            float x,
            float y,
            float minor,
            float major,
            float orientation,
            long time,
            long gestureStart,
            boolean isAod) {
        com.android.systemui.keyguard.data.repository.IchthysDepthRepository.fingerOnSensor
                .setValue(false);
        setIchthysUdfpsScanning(false);
        mExecution.assertIsMainThread();
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mAcquiredReceived = false;
        if (mOnFingerDown) {
            mFingerprintManager.onPointerUp(requestId, mSensorProps.sensorId, pointerId, x,
                    y, minor, major, orientation, time, gestureStart, isAod);
            if (isOptical()) {
                hideIlluminationOverlay();
                for (Callback cb : mCallbacks) {
                    cb.onFingerUp();
                }
                hideUdfpsAnimation();
            }
        }
        mOnFingerDown = false;
        unconfigureDisplay(view);
        cancelAodSendFingerUpAction();
    }

    private void updateUdfpsAnimation() {
        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.removeAnimation();
            mUdfpsAnimation = null;
        }
        // IchthysOS: always available; built-in effects don't need the crDroid animation pack.
        mUdfpsAnimation = new UdfpsAnimation(mContext, mWindowManager, mKeyguardStateController,
                mKeyguardUpdateMonitor);
        mUdfpsAnimation.setOverlayParams(mOverlayParams);
    }

    public boolean isAnimationEnabled() {
        return mUdfpsAnimation != null && mUdfpsAnimation.isAnimationEnabled();
    }

    private void showUdfpsAnimation() {
        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.show();
        }
    }

    private void hideUdfpsAnimation() {
        if (mUdfpsAnimation != null) {
            mUdfpsAnimation.hide();
        }
    }

    private void showIlluminationOverlay() {
        if (mIlluminationView != null) {
            Log.e(TAG, "showIlluminationOverlay | skipped, view already exists");
            return;
        }

        final Rect bounds = mOverlayParams.getSensorBounds();
        if (bounds.isEmpty()) {
            Log.e(TAG, "showIlluminationOverlay | sensor bounds empty, overlayParams=" + mOverlayParams);
            return;
        }

        Log.e(TAG, "showIlluminationOverlay | creating white circle at bounds=" + bounds);

        // Optional dim layer, added BEFORE the circle so the circle stays on top.
        // Blocks stray light from the surrounding screen (the whole panel sits at
        // 547 nits during finger-mask HBM), which measurably matters: the only run
        // that ever cleared the rem=92 enrollment stage had this layer.
        // Held for the whole enrollment session (see showUdfpsOverlay) so it does
        // not flash per touch — only the illumination itself needs to cycle.
        if (!isDimSessionHeld()) {
            showDimLayer();
        }

        // IchthysOS: press effects go between the dim (below) and the illumination disc (above),
        // so they are visible and never cover the light the sensor needs.
        showUdfpsAnimation();

        // === ILLUMINATION CIRCLE ===
        // The sensor sees light from this circle reflected off the finger.
        View view = new View(mContext);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(getCircleColor());
        view.setBackground(circle);

        final int scale = SystemProperties.getInt(PROP_CIRCLE_SCALE, 100);
        if (scale != 100) {
            final int dx = bounds.width() * (scale - 100) / 200;
            final int dy = bounds.height() * (scale - 100) / 200;
            bounds.inset(-dx, -dy);
            Log.e(TAG, "showIlluminationOverlay | circle scaled to " + scale + "% -> " + bounds);
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                bounds.width(),
                bounds.height(),
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        // Without FLAG_LAYOUT_IN_SCREEN the y offset is measured from
                        // below the status bar, drawing the illumination disc ~100px
                        // BELOW the sensor. Matches Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS.
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = bounds.left;
        params.y = bounds.top;
        // Do not let system-bar insets shift the disc, and allow it to sit under
        // a display cutout region if the panel geometry ever requires it.
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle("UdfpsIllumination");
        params.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        // Keep the illumination disc out of screenshots/recordings, matching
        // OneUI (whose FOD light never appears in a screengrab). Purely
        // cosmetic — it does not change what the sensor sees.
        params.flags |= WindowManager.LayoutParams.FLAG_SECURE;

        try {
            mWindowManager.addView(view, params);
            mIlluminationView = view;
            // Report where the disc ACTUALLY landed on screen vs where the sensor is.
            final Rect want = new Rect(bounds);
            view.post(() -> {
                int[] loc = new int[2];
                view.getLocationOnScreen(loc);
                Log.e(TAG, "illumination placement | requested=" + want
                        + " actualOnScreen=[" + loc[0] + "," + loc[1] + " "
                        + view.getWidth() + "x" + view.getHeight() + "]"
                        + (loc[0] == want.left && loc[1] == want.top ? " MATCH" : " *** OFFSET ***"));
            });
            Log.e(TAG, "showIlluminationOverlay | SUCCESS bounds=" + bounds
                    + " size=" + bounds.width() + "x" + bounds.height());
        } catch (RuntimeException e) {
            Log.e(TAG, "showIlluminationOverlay | failed", e);
        }
    }

    // ---- Runtime-tunable UDFPS optics (no rebuild needed to sweep these) ----
    //   persist.sys.udfps.dim_alpha    0 = no dim (default), -1 = auto (nits-matched, 0-245),
    //                                  1-255 = fixed alpha                      (default -1)
    //   persist.sys.udfps.dim_session  bool   dim held for whole enroll session (default false:
    //                                         dim only while the finger is down)
    //   persist.sys.udfps.circle_scale int %  illumination disc size            (default 100)
    //   persist.sys.udfps.circle_color hex    ARGB circle colour                (default FFFFFFFF)
    // Apply with e.g.  adb shell setprop persist.sys.udfps.dim_alpha 230
    // then leave and re-enter the enrollment screen (no reboot required).
    private static final String PROP_DIM_ALPHA = "persist.sys.udfps.dim_alpha";
    private static final String PROP_DIM_SESSION = "persist.sys.udfps.dim_session";
    private static final String PROP_CIRCLE_SCALE = "persist.sys.udfps.circle_scale";
    private static final String PROP_CIRCLE_COLOR = "persist.sys.udfps.circle_color";

    private boolean isDimSessionHeld() {
        // IchthysOS: in sync mode the dim IS the HBM trigger, so it must follow the finger.
        return !isUdfpsSync() && SystemProperties.getBoolean(PROP_DIM_SESSION, false);
    }

    /**
     * IchthysOS: frame-synced mask HBM ({@code persist.ichthys.udfps_sync}, default off; read by
     * the composer and the fingerprint HAL too). On: the dim below is always shown while the finger
     * is down, nits-matched, as the window {@link #UDFPS_MASK_WINDOW_TITLE}. SurfaceFlinger flags
     * that layer and the composer switches the panel's mask HBM in the same atomic commit that
     * first shows (and later first drops) it, so dim and HBM change on the same frame and the
     * screen keeps its brightness. Off: exactly the old behaviour (HAL raises HBM at onUiReady).
     */
    private static boolean isUdfpsSync() {
        return SystemProperties.getBoolean(PROP_UDFPS_SYNC, false);
    }

    private static final String PROP_UDFPS_SYNC = "persist.ichthys.udfps_sync";
    /** Matched by name in SurfaceFlinger (OutputLayer.cpp, "UdfpsFodMask#"); keep both in sync. */
    private static final String UDFPS_MASK_WINDOW_TITLE = "UdfpsFodMask";

    private int getCircleColor() {
        try {
            // IchthysOS: Settings.Global ichthys_udfps_light_color ("RRGGBB" or "AARRGGBB") wins,
            // so the light source can be switched from adb (One UI lights this sensor with
            // #00ff00, its calibration colour).
            final String g = android.provider.Settings.Global.getString(
                    mContext.getContentResolver(), "ichthys_udfps_light_color");
            if (g != null && (g.length() == 6 || g.length() == 8)) {
                return (int) Long.parseLong(g.length() == 6 ? "FF" + g : g, 16);
            }
            return (int) Long.parseLong(
                    SystemProperties.get(PROP_CIRCLE_COLOR, "FFFFFFFF"), 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }

    /**
     * IchthysOS: how dark the surround has to be so the screen looks unchanged when the panel
     * jumps to fingerprint HBM.
     *
     * <p>{@code mask_brightness} drives the WHOLE panel to a fixed ~547 nits — there is no hardware
     * way to light only the sensor area (verified: writing mask_brightness with no software circle
     * brightens the screen uniformly). So to keep the rest of the screen looking the same, the
     * overlay has to cancel out the ratio between the user's brightness and the HBM level. At low
     * brightness that jump is huge, so the dim must be near-opaque; at max brightness it is small,
     * so barely any dim is needed. Without this the screen visibly flashes on every touch, worst at
     * low brightness.
     */
    private int computeAutoDimAlpha() {
        // IchthysOS: a measured calibration table wins over every model (calibratedDimAlpha).
        final int calibrated = calibratedDimAlpha();
        if (calibrated >= 0) {
            return calibrated;
        }
        // IchthysOS 2026-11-02: One UI's own model for this device (stock A52s BiometricSetting,
        // UdfpsMaskWindow.updateBackgroundColor fallback): the panel's light at brightness level
        // bl (0..255) is 1.644*bl - (bl <= 128 ? 0.23*bl - 2 : (255 - bl) * 0.215) nits, the
        // optical mask level is 525 nits, alpha = 1 - (current / 525)^(1 / 1.85), capped at 0.93.
        // Tunable from adb: Global ichthys_udfps_hbm_nits (525), ichthys_udfps_gamma (1.85).
        if (android.provider.Settings.Global.getInt(
                mContext.getContentResolver(), "ichthys_udfps_model", 1) == 1) {
            // IchthysOS: One UI's bl is 0..255 over the normal range (1.644 * 255 = 420 nits, the
            // top of config_screenBrightnessNits). The measured best alpha at slider 8 and 22
            // (213, 199) matches this scale, not the 0..486 HBM-inclusive panel level.
            final float bl = Math.max(0f, Math.min(1f,
                    mDimDisplayManager.getBrightness(Display.DEFAULT_DISPLAY))) * 255f;
            final float sub = bl <= 128f ? 0.23f * bl - 2f : (255f - bl) * 0.215f;
            final float current = 1.644f * bl - sub;
            final float optical = android.provider.Settings.Global.getFloat(
                    mContext.getContentResolver(), "ichthys_udfps_hbm_nits", 525f);
            final float gamma = android.provider.Settings.Global.getFloat(
                    mContext.getContentResolver(), "ichthys_udfps_gamma", 1.85f);
            if (current >= optical) {
                return 0;
            }
            final double a = 1.0 - Math.pow(Math.max(0f, current) / optical,
                    1.0 / Math.max(1f, gamma));
            return (int) Math.round(Math.min(0.93, a) * 255.0);
        }
        try {
            // The panel's backlight -> nits table (the one DisplayDeviceConfig splines), so the
            // dim is computed in light, not in slider units: slider 50 % is ~170 nits here, not
            // half of 547, which is why a linear guess under-dimmed and the screen flashed.
            final android.content.res.Resources res = mContext.getResources();
            final int[] backlight = res.getIntArray(
                    com.android.internal.R.array.config_screenBrightnessBacklight);
            final android.content.res.TypedArray nitsArray = res.obtainTypedArray(
                    com.android.internal.R.array.config_screenBrightnessNits);
            final float[] nits = new float[nitsArray.length()];
            for (int i = 0; i < nits.length; i++) {
                nits[i] = nitsArray.getFloat(i, 0f);
            }
            nitsArray.recycle();
            if (backlight.length < 2 || nits.length != backlight.length) {
                return DEFAULT_DIM_ALPHA;
            }
            final int maxBacklight = backlight[backlight.length - 1];
            final float brightness = mDimDisplayManager.getBrightness(Display.DEFAULT_DISPLAY);
            final float current = nitsAt(brightness * maxBacklight, backlight, nits);
            // What the panel shows while the fingerprint mask is on: the HAL writes
            // mask_brightness = hbm_level (331 on this panel = 547 nits in the same table).
            // IchthysOS 2026-11-02: the mask level (331) lies above this table's end (255 = 420
            // nits), so looking it up returned 420 and under-dimmed. The mask's light is given in
            // nits instead: Settings.Global ichthys_udfps_hbm_nits (writable from adb shell for live
            // calibration), default 547.
            final float hbm = android.provider.Settings.Global.getFloat(
                    mContext.getContentResolver(), "ichthys_udfps_hbm_nits", DEFAULT_HBM_NITS);
            if (hbm <= 0f || current >= hbm) {
                return 0; // already brighter than the mask level: nothing to hide
            }
            // A black layer at alpha a scales the sRGB value by (1 - a); the light by about
            // (1 - a)^2.2. Solve (1 - a)^2.2 * hbm = current.
            // IchthysOS: the panel's gamma, Settings.Global ichthys_udfps_gamma for live
            // calibration (default 2.2).
            final float gamma = android.provider.Settings.Global.getFloat(
                    mContext.getContentResolver(), "ichthys_udfps_gamma", 2.2f);
            final double keep = Math.pow(current / hbm, 1.0 / Math.max(1f, gamma));
            final int alpha = (int) Math.round((1.0 - keep) * 255.0);
            // Never fully black: the user must still see the lock screen / enrolment UI.
            return Math.max(0, Math.min(MAX_AUTO_DIM_ALPHA, alpha));
        } catch (RuntimeException e) {
            Log.w(TAG, "computeAutoDimAlpha failed, using default", e);
            return DEFAULT_DIM_ALPHA;
        }
    }

    /**
     * IchthysOS: the dim alpha from the camera calibration table, or -1 when there is none.
     *
     * <p>Settings.Global {@code ichthys_udfps_cal_alpha} = "brightness:alpha,..." (e.g.
     * "8:213,128:150,230:90"), brightness in Android's 0..255 setting unit (screen_brightness;
     * float f = (v - 1) / 254), alpha 0..255. Interpolated linearly in brightness between the two
     * nearest entries, clamped to the end entries. It must hold, per brightness, the alpha the
     * display HAL's mask curve for that brightness (persist.ichthys.udfps_lut*, levels in
     * persist.ichthys.udfps_lut_bl, same unit) was recorded with: the curve maps the codes
     * composed under that dim, so a different alpha invalidates it. Malformed entries are skipped.
     */
    private int calibratedDimAlpha() {
        final String table = android.provider.Settings.Global.getString(
                mContext.getContentResolver(), "ichthys_udfps_cal_alpha");
        if (table == null || table.trim().isEmpty()) {
            return -1;
        }
        final java.util.TreeMap<Float, Float> points = new java.util.TreeMap<>();
        for (String entry : table.split(",")) {
            final int colon = entry.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                final float level = Float.parseFloat(entry.substring(0, colon).trim());
                final float alpha = Float.parseFloat(entry.substring(colon + 1).trim());
                if (level >= 0f && alpha >= 0f && alpha <= 255f) {
                    points.put(level, alpha);
                }
            } catch (NumberFormatException e) {
                // IchthysOS: skip a malformed entry, keep the rest of the table
            }
        }
        if (points.isEmpty()) {
            return -1;
        }
        final float level = currentBrightnessSetting();
        final java.util.Map.Entry<Float, Float> lo = points.floorEntry(level);
        final java.util.Map.Entry<Float, Float> hi = points.ceilingEntry(level);
        final float alpha;
        if (lo == null) {
            alpha = hi.getValue();
        } else if (hi == null || hi.getKey().equals(lo.getKey())) {
            alpha = lo.getValue();
        } else {
            final float t = (level - lo.getKey()) / (hi.getKey() - lo.getKey());
            alpha = lo.getValue() + (hi.getValue() - lo.getValue()) * t;
        }
        final int result = Math.max(0, Math.min(255, Math.round(alpha)));
        Log.i(TAG, "udfps cal alpha: brightness=" + level + " -> alpha=" + result);
        return result;
    }

    /**
     * IchthysOS: the screen brightness in Android's 0..255 setting unit (1 + 254 * f), from the
     * brightness actually applied (BrightnessInfo.adjustedBrightness: after auto-brightness and
     * the dim before screen-off), which is what the display HAL sees in the panel's backlight node
     * when it picks the mask curve. Falls back to the brightness setting.
     */
    private float currentBrightnessSetting() {
        float f = Float.NaN;
        try {
            final Display display = mDimDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            final android.hardware.display.BrightnessInfo info =
                    display != null ? display.getBrightnessInfo() : null;
            if (info != null) {
                f = info.adjustedBrightness;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "currentBrightnessSetting: no BrightnessInfo", e);
        }
        if (!(f >= 0f && f <= 1f)) {
            f = mDimDisplayManager.getBrightness(Display.DEFAULT_DISPLAY);
        }
        if (!(f >= 0f)) {
            return 0f;
        }
        return 1f + 254f * Math.min(1f, f);
    }

    /** Linear interpolation of the backlight -> nits table at [level]. */
    private static float nitsAt(float level, int[] backlight, float[] nits) {
        if (level <= backlight[0]) {
            return nits[0];
        }
        for (int i = 1; i < backlight.length; i++) {
            if (level <= backlight[i]) {
                final float span = backlight[i] - backlight[i - 1];
                final float t = span <= 0f ? 1f : (level - backlight[i - 1]) / span;
                return nits[i - 1] + (nits[i] - nits[i - 1]) * t;
            }
        }
        return nits[nits.length - 1];
    }

    /** IchthysOS: the mask_brightness level the fingerprint HAL writes (Session.cpp setHBM). */
    private static final String PROP_HBM_LEVEL = "persist.sys.udfps.hbm_level";
    private static final int DEFAULT_HBM_LEVEL = 331;
    /** IchthysOS: the panel's light while the fingerprint mask HBM is on (mask level 331). */
    private static final float DEFAULT_HBM_NITS = 547f;
    /** The darkest the auto dim goes: alpha 245 keeps ~1 % of the light, enough to see the UI. */
    private static final int MAX_AUTO_DIM_ALPHA = 245;
    private static final int DEFAULT_DIM_ALPHA = 180;

    private void showDimLayer() {
        mDimHideToken++; // IchthysOS: a deferred removal still pending must keep the dim now
        if (mFodDimView != null) {
            return;
        }
        // Default -1 = brightness-scaled dim (computeAutoDimAlpha). A fully black surround (255)
        // hid the HBM jump completely but also hid the lockscreen and the enrollment UI, so the
        // user could not see what they were unlocking into. Any value >= 0 pins the alpha.
        // IchthysOS 2026-11-01: default is NO dim. On this panel the fingerprint mask does not
        // visibly brighten the surround (the owner only ever saw the dim itself as "the screen
        // changes brightness"), so the compensation only darkened the screen. -1 keeps the
        // nits-matched dim available for experiments.
        final int configured = SystemProperties.getInt(PROP_DIM_ALPHA, 0);
        final boolean sync = isUdfpsSync();
        int alpha = configured >= 0 ? configured : computeAutoDimAlpha();
        if (sync) {
            // IchthysOS: the mask window must exist whenever the finger is down (it is what turns
            // the HBM on), so a pinned alpha wins, otherwise nits-matched; never fully transparent
            // (alpha 1 when already brighter than the mask level: invisible, still a layer).
            // Settings.Global ichthys_udfps_dim_alpha (> 0) pins it for live calibration from adb.
            // IchthysOS: unpinned, computeAutoDimAlpha takes the calibration table
            // (Settings.Global ichthys_udfps_cal_alpha) when set, else the One UI model.
            final int pinned = android.provider.Settings.Global.getInt(
                    mContext.getContentResolver(), "ichthys_udfps_dim_alpha", 0);
            if (isUdfpsHalCurveActive()) {
                // IchthysOS: the display HAL's mask curve (parametric persist.ichthys.udfps_model,
                // else the default physics curve) does all the darkening in 10 bits; the dim is
                // only the small separator that keeps the content below the illumination disc's
                // code. Sync mode only: without it the HAL puts no curve on the mask frames.
                alpha = udfpsSeparatorAlpha();
                Log.i(TAG, "udfps_sync dim: separator alpha=" + alpha + " (HAL mask curve)");
            } else {
                alpha = Math.max(1, pinned > 0 ? pinned
                        : configured > 0 ? configured : computeAutoDimAlpha());
                Log.i(TAG, "udfps_sync dim: alpha=" + alpha + " (pinned=" + pinned + ")");
            }
        }
        if (alpha <= 0) {
            return;
        }
        try {
            android.util.DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
            View dimView = new View(mContext);
            dimView.setBackgroundColor((Math.min(alpha, 255) << 24));

            WindowManager.LayoutParams dimParams = new WindowManager.LayoutParams(
                    dm.widthPixels,
                    dm.heightPixels,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            // NOTE: deliberately NOT FLAG_SECURE. This layer is full-screen, so marking it secure
            // blanks the entire screenshot while it is up. Only the illumination disc itself is
            // FLAG_SECURE (see showIlluminationOverlay), which is what OneUI hides.
            dimParams.gravity = Gravity.TOP | Gravity.LEFT;
            dimParams.x = 0;
            dimParams.y = 0;
            dimParams.setFitInsetsTypes(0);
            dimParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            dimParams.setTitle(sync ? UDFPS_MASK_WINDOW_TITLE : "UdfpsFodDimLayer");
            dimParams.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

            if (sync) {
                // IchthysOS: tell the display HAL the dim this mask window carries, before the
                // window exists, so its first frame's curve is built for it.
                publishAppliedDimAlpha(Math.min(alpha, 255));
            }
            mWindowManager.addView(dimView, dimParams);
            mFodDimView = dimView;
            Log.e(TAG, "showDimLayer | added, alpha=" + alpha + (sync ? " (udfps_sync mask)" : ""));
        } catch (RuntimeException e) {
            Log.e(TAG, "showDimLayer | failed", e);
        }
    }

    private void hideDimLayer() {
        mDimHideToken++;
        if (mFodDimView == null) {
            return;
        }
        try {
            mWindowManager.removeView(mFodDimView);
        } catch (RuntimeException e) {
            Log.e(TAG, "hideDimLayer | remove failed", e);
        }
        mFodDimView = null;
    }

    private void hideIlluminationOverlay() {
        mUiReadyToken++; // IchthysOS: a gated onUiReady still pending must not fire now
        if (mIlluminationView != null) {
            Log.e(TAG, "hideIlluminationOverlay | removing illumination view");
            try {
                mWindowManager.removeView(mIlluminationView);
            } catch (RuntimeException e) {
                Log.e(TAG, "hideIlluminationOverlay | illumination remove failed", e);
            }
            mIlluminationView = null;
        }
        // Only tear the dim down here when it is NOT session-held; otherwise it
        // is removed once at hideUdfpsOverlay so it never flashes per touch.
        if (!isDimSessionHeld()) {
            if (isUdfpsSync() && mFodDimView != null) {
                // IchthysOS: in sync mode the dim IS the mask HBM switch. A failed capture ("bad
                // quality") makes the sensor report the finger as lifted and the retry re-touches
                // ~150 ms later; tearing the dim down in between switched dim + HBM off and on
                // again, a visible flicker while the finger was held. Keep it a moment: a new
                // touch reuses it (showDimLayer returns early), otherwise it goes, together with
                // the HBM, on one frame.
                final int token = ++mDimHideToken;
                mFgExecutor.executeDelayed(() -> {
                    if (token == mDimHideToken && mIlluminationView == null) {
                        hideDimLayer();
                    }
                }, DIM_RETOUCH_GRACE_MS);
            } else {
                hideDimLayer();
            }
        }
    }

    /** IchthysOS: the display HAL's parametric mask curve model (hw_device_drm.cpp). */
    private static final String PROP_UDFPS_MODEL = "persist.ichthys.udfps_model";
    /** IchthysOS: the display HAL's physics mask curve, default on (hw_device_drm.cpp). */
    private static final String PROP_UDFPS_PHYSICS = "persist.ichthys.udfps_physics";
    /**
     * IchthysOS: the separator's keep factor, read by the display HAL too. Default 254/255 = alpha
     * 1, the smallest dim that still keeps content (<= 254) apart from the disc (255): no visible
     * dim, the HAL's curve does all of it.
     */
    private static final String PROP_UDFPS_SEP_KEEP = "persist.ichthys.udfps_sep_keep";
    /** IchthysOS: the dim alpha the current mask window carries, for the display HAL. */
    private static final String PROP_UDFPS_APPLIED_ALPHA = "ichthys.udfps_applied_alpha";
    // IchthysOS: 3 steps, not 1: with 1 the dimmed white (254) rounded onto the disc ramp in the
    // 10-bit GC and white UI flared to full mask HBM (measured 2026-11-03).
    private static final double DEFAULT_SEP_KEEP = 252.0 / 255.0;
    private static final String UDFPS_LUT_B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /**
     * IchthysOS: whether the display HAL puts its separator-based mask curve on the mask frames,
     * exactly its rule (hw_device_drm.cpp SetupFingerprintLut): no knot calibration point
     * (persist.ichthys.udfps_lut_{r,g,b}, udfps_lut1..3_*) has all three props valid (66 base64url
     * characters each), and either the parametric model is valid or the physics curve is on
     * ({@code persist.ichthys.udfps_physics}, default true; off only for 0 / n / no / off / false,
     * the same ParseBool rule as the HAL). Callers check sync mode.
     */
    private static boolean isUdfpsHalCurveActive() {
        for (int point = 0; point < 4; point++) {
            final String prefix = "persist.ichthys.udfps_lut" + (point == 0 ? "" : point) + "_";
            if (isUdfpsKnotProp(prefix + "r") && isUdfpsKnotProp(prefix + "g")
                    && isUdfpsKnotProp(prefix + "b")) {
                return false;
            }
        }
        return isUdfpsModelValid() || SystemProperties.getBoolean(PROP_UDFPS_PHYSICS, true);
    }

    /**
     * IchthysOS: {@code persist.ichthys.udfps_model} holds 12 finite numbers (|v| <= 100, separated
     * by spaces, commas or tabs), as the HAL's ParseUdfpsModel accepts them.
     */
    private static boolean isUdfpsModelValid() {
        final String model = SystemProperties.get(PROP_UDFPS_MODEL, "")
                .replaceAll("^[ ,\\t]+|[ ,\\t]+$", "");
        if (model.isEmpty()) {
            return false;
        }
        final String[] fields = model.split("[ ,\\t]+");
        if (fields.length != 12) {
            return false;
        }
        for (String field : fields) {
            // Plain decimals only (also ".5" / "5."), the same grammar the HAL's ParseUdfpsModel
            // accepts (UdfpsStrtodPlain), so both always agree on whether the model is valid.
            if (!field.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
                    || !(Math.abs(Double.parseDouble(field)) <= 100.0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUdfpsKnotProp(String name) {
        final String value = SystemProperties.get(name, "");
        if (value.length() != 66) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (UDFPS_LUT_B64.indexOf(value.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * IchthysOS: the separator dim for the HAL mask curve (model or physics). Settings.Global
     * {@code ichthys_udfps_sep_alpha} (1..255) pins it; otherwise from
     * {@code persist.ichthys.udfps_sep_keep} (0.5..1, default 254/255 = alpha 1) as
     * max(1, round((1 - keep) * 255)), the same formula the display HAL falls back to.
     */
    private int udfpsSeparatorAlpha() {
        final int pinned = android.provider.Settings.Global.getInt(
                mContext.getContentResolver(), "ichthys_udfps_sep_alpha", 0);
        if (pinned > 0) {
            return Math.min(255, pinned);
        }
        double keep = DEFAULT_SEP_KEEP;
        try {
            final double v = Double.parseDouble(SystemProperties.get(PROP_UDFPS_SEP_KEEP, ""));
            if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                keep = Math.max(0.5, Math.min(1.0, v));
            }
        } catch (NumberFormatException e) {
            // IchthysOS: unset or malformed, keep the default
        }
        return (int) Math.max(1, Math.round((1.0 - keep) * 255.0));
    }

    /** IchthysOS: publishes the mask window's dim alpha for the display HAL's mask curve. */
    private static void publishAppliedDimAlpha(int alpha) {
        try {
            SystemProperties.set(PROP_UDFPS_APPLIED_ALPHA, Integer.toString(alpha));
        } catch (RuntimeException e) {
            Log.w(TAG, "publishAppliedDimAlpha failed", e);
        }
    }

    /** IchthysOS: how long the sync-mode dim outlives a lifted finger (see hideIlluminationOverlay). */
    private static final long DIM_RETOUCH_GRACE_MS = 300;
    /** Bumped by every deferred dim removal and by showDimLayer, so a stale removal does nothing. */
    private int mDimHideToken;

    /**
     * Callback for fingerUp and fingerDown events.
     */
    public interface Callback {
        /**
         * Called onFingerUp events. Will only be called if the finger was previously down.
         */
        void onFingerUp();

        /**
         * Called onFingerDown events.
         */
        void onFingerDown();
    }
}
