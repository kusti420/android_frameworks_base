/*
 * Copyright (C) 2016 The Dirty Unicorns Project
 * Copyright (C) 2026 crDroid Android / IchthysOS
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
 *
 * Drives the lockscreen / AOD music visualizer ("Pulse"). Owns the PulseView,
 * watches the relevant Settings.Secure keys, follows keyguard / doze state, and
 * links the audio Visualizer only while the visualizer should actually draw.
 */
package com.android.systemui.pulse;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;

public class PulseController implements VisualizerStreamHandler.Listener,
        PulseColorController.InvalidateCallback {

    // Global output mix. Requires CAPTURE_AUDIO_OUTPUT (held by SystemUI).
    private static final int AUDIO_SESSION_OUTPUT_MIX = 0;

    // Bass-haptic tuning.
    private static final float BASS_HAPTIC_THRESHOLD = 0.62f;
    private static final long BASS_HAPTIC_MIN_INTERVAL_MS = 130L;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final StatusBarStateController mStatusBarStateController;
    private final Handler mHandler;
    private final Vibrator mVibrator;

    private PulseView mView;
    private PulseColorController mColorController;
    private VisualizerStreamHandler mStreamHandler;
    private Renderer mRenderer;

    // Settings-backed state.
    private boolean mMasterEnabled;
    private boolean mAmbientEnabled = true;
    private int mBarCount = 32;
    private boolean mRounded;
    private String mColorMode = PulseColorController.MODE_LAVALAMP;
    private String mRendererName = "solid";
    private int mBassHaptics;

    // Runtime state.
    private boolean mAttached;
    private boolean mKeyguardShowing;
    private boolean mDozing;
    private boolean mShowing;
    private long mLastHapticTime;
    private boolean mBassArmed = true;

    public PulseController(Context context,
            StatusBarStateController statusBarStateController) {
        mContext = context;
        mResolver = context.getContentResolver();
        mStatusBarStateController = statusBarStateController;
        mHandler = new Handler(Looper.getMainLooper());
        mVibrator = context.getSystemService(Vibrator.class);
    }

    /** Attaches the Pulse view behind everything in the given panel container. */
    public void attachTo(FrameLayout parent) {
        if (mAttached || parent == null) {
            return;
        }
        PulseView view = new PulseView(mContext);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        // index 0 -> drawn first, i.e. behind the notifications / clock.
        parent.addView(view, 0, lp);
        attachToView(view);
    }

    /**
     * Binds this controller to an already-created {@link PulseView}. Used by the
     * Compose SceneContainer lockscreen, where the view is hosted by an AndroidView
     * inside the Compose tree (the legacy shade panel is dead code on this build).
     */
    public void attachToView(PulseView view) {
        if (mAttached || view == null) {
            return;
        }
        mAttached = true;
        mView = view;
        mView.setVisibility(View.GONE);

        mColorController = new PulseColorController(mContext, this);
        mStreamHandler = new VisualizerStreamHandler(AUDIO_SESSION_OUTPUT_MIX, this);

        loadSettings();
        registerObservers();

        mKeyguardShowing =
                mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
        mDozing = mStatusBarStateController.isDozing();
        mStatusBarStateController.addCallback(mStateListener);

        updateShowing();
    }

    /** Tears down observers, the audio link and the renderer. Safe to call repeatedly. */
    public void detach() {
        if (!mAttached) {
            return;
        }
        mAttached = false;
        mStatusBarStateController.removeCallback(mStateListener);
        mResolver.unregisterContentObserver(mObserver);
        if (mStreamHandler != null) {
            mStreamHandler.unlink();
        }
        if (mColorController != null) {
            mColorController.stop();
        }
        if (mRenderer != null) {
            mRenderer.destroy();
            mRenderer = null;
        }
        mShowing = false;
        mView = null;
    }

    private void registerObservers() {
        registerUri(Settings.Secure.LOCKSCREEN_PULSE_ENABLED);
        registerUri(Settings.Secure.AMBIENT_PULSE_ENABLED);
        registerUri(Settings.Secure.PULSE_BAR_COUNT);
        registerUri(Settings.Secure.PULSE_ROUNDED_BARS);
        registerUri(Settings.Secure.PULSE_COLOR);
        registerUri(Settings.Secure.PULSE_RENDERER);
        registerUri(Settings.Secure.PULSE_BASS_HAPTICS);
    }

    private void registerUri(String key) {
        mResolver.registerContentObserver(Settings.Secure.getUriFor(key),
                false, mObserver, UserHandle.USER_ALL);
    }

    private final ContentObserver mObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            boolean rendererWasBuilt = mRenderer != null;
            String oldRenderer = mRendererName;
            int oldBars = mBarCount;
            boolean oldRounded = mRounded;

            loadSettings();

            if (mColorController != null) {
                mColorController.setMode(mColorMode);
            }
            // Rebuild the renderer if its identity/geometry changed.
            if (mShowing && (!mRendererName.equals(oldRenderer)
                    || mBarCount != oldBars || mRounded != oldRounded
                    || !rendererWasBuilt)) {
                buildRenderer();
            }
            updateShowing();
        }
    };

    private void loadSettings() {
        mMasterEnabled = getSecureInt(Settings.Secure.LOCKSCREEN_PULSE_ENABLED, 0) != 0;
        mAmbientEnabled = getSecureInt(Settings.Secure.AMBIENT_PULSE_ENABLED, 1) != 0;
        int bars = getSecureInt(Settings.Secure.PULSE_BAR_COUNT, 32);
        mBarCount = Math.max(4, Math.min(96, bars));
        mRounded = getSecureInt(Settings.Secure.PULSE_ROUNDED_BARS, 0) != 0;
        mBassHaptics = getSecureInt(Settings.Secure.PULSE_BASS_HAPTICS, 0);

        String color = getSecureString(Settings.Secure.PULSE_COLOR);
        mColorMode = color != null ? color : PulseColorController.MODE_LAVALAMP;
        String renderer = getSecureString(Settings.Secure.PULSE_RENDERER);
        mRendererName = renderer != null ? renderer : "solid";
    }

    private int getSecureInt(String key, int def) {
        return Settings.Secure.getIntForUser(mResolver, key, def, UserHandle.USER_CURRENT);
    }

    private String getSecureString(String key) {
        return Settings.Secure.getStringForUser(mResolver, key, UserHandle.USER_CURRENT);
    }

    private boolean shouldShow() {
        if (!mMasterEnabled || !mKeyguardShowing) {
            return false;
        }
        if (mDozing) {
            return mAmbientEnabled;
        }
        return true;
    }

    private void updateShowing() {
        if (!mAttached) {
            return;
        }
        boolean show = shouldShow();
        if (show == mShowing) {
            return;
        }
        mShowing = show;
        if (show) {
            if (mRenderer == null) {
                buildRenderer();
            }
            if (mColorController != null) {
                mColorController.setMode(mColorMode);
                // The color animation (lavalamp) is only started once the audio
                // stream validates, in onStreamValidityChanged().
            }
            mView.setVisibility(View.VISIBLE);
            if (mStreamHandler != null) {
                mStreamHandler.link();
            }
        } else {
            if (mStreamHandler != null) {
                mStreamHandler.unlink();
            }
            if (mColorController != null) {
                mColorController.stop();
            }
            mView.setVisibility(View.GONE);
        }
    }

    private void buildRenderer() {
        Renderer renderer;
        switch (mRendererName) {
            case "fading":
                renderer = new FadingBlockRenderer(this, mView, mColorController);
                break;
            case "neon":
                renderer = new SolidLineRenderer(this, mView, mColorController,
                        SolidLineRenderer.MODE_NEON);
                break;
            case "retro":
                renderer = new SolidLineRenderer(this, mView, mColorController,
                        SolidLineRenderer.MODE_RETRO);
                break;
            case "minimal":
                renderer = new SolidLineRenderer(this, mView, mColorController,
                        SolidLineRenderer.MODE_MINIMAL);
                break;
            case "sparkle":
                renderer = new SolidLineRenderer(this, mView, mColorController,
                        SolidLineRenderer.MODE_SPARKLE);
                break;
            case "matrix":
                renderer = new SolidLineRenderer(this, mView, mColorController,
                        SolidLineRenderer.MODE_MATRIX);
                break;
            case "particle":
                renderer = new SolidLineRenderer(this, mView, mColorController,
                        SolidLineRenderer.MODE_PARTICLE);
                break;
            case "waveform":
                renderer = new SolidLineRenderer(this, mView, mColorController,
                        SolidLineRenderer.MODE_WAVEFORM);
                break;
            case "solid":
            default:
                renderer = new SolidLineRenderer(this, mView, mColorController,
                        SolidLineRenderer.MODE_SOLID);
                break;
        }
        renderer.setBarCount(mBarCount);
        renderer.setRounded(mRounded);
        if (mView.getWidth() > 0 && mView.getHeight() > 0) {
            renderer.onSizeChanged(mView.getWidth(), mView.getHeight());
        }
        Renderer old = mRenderer;
        mRenderer = renderer;
        mView.setRenderer(renderer);
        if (old != null) {
            old.destroy();
        }
    }

    // ===== VisualizerStreamHandler.Listener (delegates to the active renderer) =====

    @Override
    public void onStreamValidityChanged(boolean isValid) {
        if (mColorController != null) {
            if (isValid && mShowing) {
                mColorController.start();
            } else {
                mColorController.stop();
            }
        }
        Renderer r = mRenderer;
        if (r != null) {
            r.onStreamValidityChanged(isValid);
        }
    }

    @Override
    public void onFFTUpdate(byte[] fft) {
        Renderer r = mRenderer;
        if (r != null) {
            r.onFFTUpdate(fft);
        }
    }

    @Override
    public void onWaveFormUpdate(byte[] waveform) {
        Renderer r = mRenderer;
        if (r != null) {
            r.onWaveFormUpdate(waveform);
        }
    }

    // ===== PulseColorController.InvalidateCallback =====

    @Override
    public void onColorChanged() {
        if (mShowing && mView != null) {
            mView.postInvalidate();
        }
    }

    // ===== Bass haptics (called from Renderer) =====

    void onBassLevel(float level) {
        if (mBassHaptics == 0 || mVibrator == null || !mVibrator.hasVibrator()) {
            return;
        }
        // mode 1 = "running" (only while awake), mode 2 = "always".
        if (mBassHaptics == 1 && mDozing) {
            return;
        }
        if (level < BASS_HAPTIC_THRESHOLD) {
            mBassArmed = true;
            return;
        }
        if (!mBassArmed) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - mLastHapticTime < BASS_HAPTIC_MIN_INTERVAL_MS) {
            return;
        }
        mBassArmed = false;
        mLastHapticTime = now;
        int amplitude = Math.max(1, Math.min(255, (int) (level * 255f)));
        try {
            mVibrator.vibrate(VibrationEffect.createOneShot(14, amplitude));
        } catch (Exception ignored) {
        }
    }

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {
            mKeyguardShowing = newState == StatusBarState.KEYGUARD;
            updateShowing();
        }

        @Override
        public void onDozingChanged(boolean isDozing) {
            mDozing = isDozing;
            updateShowing();
        }
    };
}
