/**
 * Copyright (C) 2019 The Android Open Source Project
 * Copyright (C) 2024 crDroid Android Project
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

package com.android.systemui.biometrics;

import static android.content.Intent.ACTION_USER_SWITCHED;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_ENROLLING;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_FIND_SENSOR;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricSourceType;
import android.os.UserHandle;
import android.util.Log;
import android.util.RotationUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.KeyguardStateController;

/**
 * Fingerprint (UDFPS) press effects: the "recognizing" animation around the sensor while the
 * finger is down, an optional pressed-icon ring/disc/glow, and success/failure effects.
 *
 * <p>IchthysOS rework of crDroid's animation window:
 * <ul>
 *   <li>Positioning. The old window centred itself on the sensor's TOP edge
 *       ({@code sensorY - radius}), horizontally on the screen centre and, lacking
 *       FLAG_LAYOUT_IN_SCREEN, measured y from below the status bar, so it landed far from the
 *       sensor unless a device overlay tuned {@code udfps_animation_offset}. It is now centred on
 *       {@link UdfpsOverlayParams#getSensorBounds()} (logical pixels, rotated with the display),
 *       exactly like the illumination disc.</li>
 *   <li>Stacking. It was a TYPE_VOLUME_OVERLAY window, BELOW the FOD dim layer, so the dim hid
 *       it. It is now a TYPE_NAVIGATION_BAR_PANEL window added after the dim and before the
 *       illumination disc (see UdfpsController#showIlluminationOverlay): visible, and never
 *       covering the light the sensor needs.</li>
 *   <li>Built-in procedural effects, colours, speed, size and result effects. The crDroid
 *       animation pack keeps working as effect 0.</li>
 * </ul>
 */
public class UdfpsAnimation extends View {

    private static final String LOG_TAG = "UdfpsAnimations";
    private static final String UDFPS_ANIMATIONS_PACKAGE = "com.crdroid.udfps.animations";

    /** Base period of one effect loop at 100% speed. */
    private static final long BASE_PERIOD_MS = 1400;
    private static final long RESULT_DURATION_MS = 420;
    private static final int RESULT_NONE = 0;
    private static final int RESULT_SUCCESS = 1;
    private static final int RESULT_FAIL = 2;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private final Matrix mMatrix = new Matrix();

    /** Sensor centre and radius in screen pixels (current rotation). */
    private final Rect mSensor = new Rect();
    private float mSensorR;

    private int mRequestReason = -1;
    /** Overlay went away while a result effect was running: release once it ends. */
    private boolean mPendingRelease;
    private boolean mRegistered;
    private boolean mShowing;
    private boolean mFingerDown;

    // Per-touch options, read in loadOptions().
    private int mEffect = IchthysUdfpsSettings.ANIM_EFFECT_DEFAULT;
    private int mColorMode;
    private int mColor;
    private float mScale = 1f;
    private long mPeriod = BASE_PERIOD_MS;
    private int mPressedStyle;
    private int mPressedColor;
    private float mPressedSize = 1.2f;
    private int mSuccessEffect;
    private int mFailEffect;
    private int mOffsetX;
    private int mOffsetY;

    private AnimationDrawable mPackAnim;
    private int mPackSize;

    private ValueAnimator mLoop;
    private float mT;
    private ValueAnimator mResultAnim;
    private int mResult = RESULT_NONE;
    private float mResultT;

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onKeyguardGoingAwayChanged() {
            // Let a running success effect finish; removal follows its end.
            if (mResult != RESULT_SUCCESS) removeAnimation();
        }
    };

    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType type,
                boolean isStrongBiometric) {
            if (type == BiometricSourceType.FINGERPRINT) playResult(RESULT_SUCCESS);
        }

        @Override
        public void onBiometricAuthFailed(BiometricSourceType type) {
            if (type == BiometricSourceType.FINGERPRINT) playResult(RESULT_FAIL);
        }
    };

    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USER_SWITCHED.equals(intent.getAction())) removeAnimation();
        }
    };

    public UdfpsAnimation(Context context, WindowManager windowManager,
            KeyguardStateController keyguardStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor) {
        super(context);
        mContext = context;
        mWindowManager = windowManager;
        mKeyguardStateController = keyguardStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;

        mParams.format = PixelFormat.TRANSLUCENT;
        // Same layer as the FOD dim and the illumination disc; added between them.
        mParams.type = WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;
        mParams.setFitInsetsTypes(0);
        mParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mParams.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        mParams.setTitle("UdfpsEffects");

        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mFillPaint.setStyle(Paint.Style.FILL);
    }

    /** Sensor geometry, taken from the same params the illumination disc uses. */
    public void setOverlayParams(@NonNull UdfpsOverlayParams params) {
        final Rect bounds = new Rect(params.getSensorBounds());
        final int rotation = params.getRotation();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            RotationUtils.rotateBounds(bounds, params.getNaturalDisplayWidth(),
                    params.getNaturalDisplayHeight(), rotation);
        }
        mSensor.set(bounds);
        mSensorR = Math.min(bounds.width(), bounds.height()) / 2f;
        if (mShowing) {
            updateWindowGeometry();
            mWindowManager.updateViewLayout(this, mParams);
        }
    }

    /** Called when the UDFPS overlay is shown (reason) or hidden (-1). */
    public void setRequestReason(int reason) {
        mRequestReason = reason;
        if (reason == REASON_AUTH_KEYGUARD) {
            mPendingRelease = false;
            register();
        } else if (reason < 0) {
            // The overlay is torn down right after a successful auth; let the effect finish.
            if (mResultAnim != null && mResultAnim.isRunning()) {
                mPendingRelease = true;
                mFingerDown = false;
            } else {
                removeAnimation();
            }
        }
    }

    /** Compatibility with the old API: keyguard on/off. */
    public void setIsKeyguard(boolean isKeyguard) {
        setRequestReason(isKeyguard ? REASON_AUTH_KEYGUARD : -1);
    }

    /** Kept for API compatibility; geometry now comes from {@link #setOverlayParams}. */
    public void updatePosition() {}

    private boolean enabledForReason() {
        switch (mRequestReason) {
            case REASON_AUTH_KEYGUARD:
                return true;
            case REASON_AUTH_BP:
                return IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.ANIM_IN_PROMPT,
                        IchthysUdfpsSettings.ANIM_IN_PROMPT_DEFAULT) != 0;
            case REASON_ENROLL_FIND_SENSOR:
            case REASON_ENROLL_ENROLLING:
                return IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.ANIM_IN_ENROLL,
                        IchthysUdfpsSettings.ANIM_IN_ENROLL_DEFAULT) != 0;
            default:
                return false;
        }
    }

    public boolean isAnimationEnabled() {
        loadOptions();
        return hasAnythingToDraw();
    }

    private boolean hasAnythingToDraw() {
        return (mEffect == IchthysUdfpsSettings.EFFECT_PACK && mPackAnim != null)
                || mEffect > IchthysUdfpsSettings.EFFECT_PACK
                || mPressedStyle != 0
                || (mRequestReason == REASON_AUTH_KEYGUARD
                        && (mSuccessEffect != 0 || mFailEffect != 0));
    }

    private void register() {
        if (mRegistered) return;
        mContext.registerReceiverAsUser(mUserReceiver, UserHandle.ALL,
                new IntentFilter(ACTION_USER_SWITCHED), null, null);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
        mKeyguardUpdateMonitor.registerCallback(mUpdateMonitorCallback);
        mRegistered = true;
    }

    private void unregister() {
        if (!mRegistered) return;
        mContext.unregisterReceiver(mUserReceiver);
        mKeyguardStateController.removeCallback(mKeyguardStateCallback);
        mKeyguardUpdateMonitor.removeCallback(mUpdateMonitorCallback);
        mRegistered = false;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void loadOptions() {
        mEffect = IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.ANIM_EFFECT,
                IchthysUdfpsSettings.ANIM_EFFECT_DEFAULT);
        mColorMode = IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.ANIM_COLOR_MODE,
                IchthysUdfpsSettings.ANIM_COLOR_MODE_DEFAULT);
        mColor = IchthysUdfpsSettings.resolveColor(mContext, mColorMode,
                IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.ANIM_COLOR,
                        IchthysUdfpsSettings.ANIM_COLOR_DEFAULT));
        final int speed = clamp(IchthysUdfpsSettings.get(mContext,
                IchthysUdfpsSettings.ANIM_SPEED, IchthysUdfpsSettings.ANIM_SPEED_DEFAULT), 25, 300);
        mPeriod = BASE_PERIOD_MS * 100 / speed;
        mScale = clamp(IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.ANIM_SCALE,
                IchthysUdfpsSettings.ANIM_SCALE_DEFAULT), 50, 250) / 100f;
        mOffsetX = dp(clamp(IchthysUdfpsSettings.get(mContext,
                IchthysUdfpsSettings.ANIM_OFFSET_X, 0), -40, 40));
        mOffsetY = dp(clamp(IchthysUdfpsSettings.get(mContext,
                IchthysUdfpsSettings.ANIM_OFFSET_Y, 0), -40, 40));
        mPressedStyle = IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.PRESSED_STYLE,
                IchthysUdfpsSettings.PRESSED_STYLE_DEFAULT);
        mPressedColor = IchthysUdfpsSettings.resolveColor(mContext,
                IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.PRESSED_COLOR_MODE,
                        IchthysUdfpsSettings.PRESSED_COLOR_MODE_DEFAULT),
                IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.PRESSED_COLOR,
                        IchthysUdfpsSettings.PRESSED_COLOR_DEFAULT));
        mPressedSize = clamp(IchthysUdfpsSettings.get(mContext,
                IchthysUdfpsSettings.PRESSED_SIZE, IchthysUdfpsSettings.PRESSED_SIZE_DEFAULT),
                100, 200) / 100f;
        mSuccessEffect = IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.SUCCESS_EFFECT,
                IchthysUdfpsSettings.SUCCESS_EFFECT_DEFAULT);
        mFailEffect = IchthysUdfpsSettings.get(mContext, IchthysUdfpsSettings.FAIL_EFFECT,
                IchthysUdfpsSettings.FAIL_EFFECT_DEFAULT);
        loadPackAnimation();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** crDroid animation pack frame set selected by udfps_anim_style (0 = none). */
    private void loadPackAnimation() {
        if (mPackAnim != null) {
            mPackAnim.stop();
            mPackAnim.setCallback(null);
            mPackAnim = null;
        }
        if (mEffect != IchthysUdfpsSettings.EFFECT_PACK) return;
        final int styleIdx = android.provider.Settings.System.getIntForUser(
                mContext.getContentResolver(), android.provider.Settings.System.UDFPS_ANIM_STYLE,
                0, UserHandle.USER_CURRENT);
        if (styleIdx <= 0) return;
        try {
            final Resources res = mContext.getPackageManager()
                    .getResourcesForApplication(UDFPS_ANIMATIONS_PACKAGE);
            final int arrayId = res.getIdentifier("udfps_animation_styles", "array",
                    UDFPS_ANIMATIONS_PACKAGE);
            if (arrayId == 0) return;
            final String[] names = res.getStringArray(arrayId);
            if (styleIdx >= names.length) return;
            final int resId = res.getIdentifier(names[styleIdx], "drawable",
                    UDFPS_ANIMATIONS_PACKAGE);
            if (resId == 0) return;
            final Drawable d = res.getDrawable(resId, mContext.getTheme());
            if (d instanceof AnimationDrawable) {
                mPackAnim = (AnimationDrawable) d;
                mPackAnim.setCallback(this);
            }
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            Log.w(LOG_TAG, "crDroid animation pack unavailable", e);
        }
    }

    /** Outer radius of everything drawn, in px. */
    private float outerRadius() {
        float r = mSensorR * 2.2f * mScale;
        if (mPressedStyle == 3) r = Math.max(r, mSensorR * mPressedSize * 1.6f);
        else if (mPressedStyle != 0) r = Math.max(r, mSensorR * mPressedSize + dp(4));
        if (mPackAnim != null) r = Math.max(r, mPackSize / 2f);
        return r;
    }

    private void updateWindowGeometry() {
        if (mPackAnim != null) {
            final int base = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.udfps_animation_size);
            mPackSize = (int) (Math.max(base, mSensorR * 2.7f) * mScale);
        }
        final int size = (int) Math.ceil(outerRadius() * 2f);
        mParams.width = size;
        mParams.height = size;
        mParams.x = mSensor.centerX() - size / 2 + mOffsetX;
        mParams.y = mSensor.centerY() - size / 2 + mOffsetY;
    }

    /** Finger down on the sensor. Called before the illumination disc is added. */
    public void show() {
        if (mSensor.isEmpty() || !enabledForReason()) return;
        mFingerDown = true;
        if (mShowing) return;
        loadOptions();
        if (!hasAnythingToDraw()) return;
        updateWindowGeometry();
        try {
            if (getWindowToken() == null) {
                mWindowManager.addView(this, mParams);
            } else {
                mWindowManager.updateViewLayout(this, mParams);
            }
            mShowing = true;
            mResult = RESULT_NONE;
            startLoop();
            if (mPackAnim != null) mPackAnim.start();
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "Error adding effects window", e);
        }
    }

    /** Finger lifted. A running result effect finishes before the window goes away. */
    public void hide() {
        mFingerDown = false;
        if (mResultAnim != null && mResultAnim.isRunning()) return;
        removeWindow();
    }

    private void removeWindow() {
        stopLoop();
        if (mPackAnim != null) {
            mPackAnim.stop();
            mPackAnim.selectDrawable(0);
        }
        if (mResultAnim != null) {
            mResultAnim.removeAllListeners();
            mResultAnim.cancel();
            mResultAnim = null;
        }
        mResult = RESULT_NONE;
        if (!mShowing && getWindowToken() == null) return;
        try {
            mWindowManager.removeView(this);
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "Error removing effects window", e);
        }
        mShowing = false;
    }

    public void removeAnimation() {
        removeWindow();
        unregister();
    }

    private void startLoop() {
        stopLoop();
        if (mEffect <= IchthysUdfpsSettings.EFFECT_PACK && mPressedStyle == 0) return;
        mLoop = ValueAnimator.ofFloat(0f, 1f);
        mLoop.setDuration(mPeriod);
        mLoop.setRepeatCount(ValueAnimator.INFINITE);
        mLoop.setInterpolator(new LinearInterpolator());
        mLoop.addUpdateListener(a -> {
            mT = (float) a.getAnimatedValue();
            invalidate();
        });
        mLoop.start();
    }

    private void stopLoop() {
        if (mLoop != null) {
            mLoop.cancel();
            mLoop = null;
        }
    }

    private void playResult(int result) {
        if (!mShowing) return;
        final int effect = result == RESULT_SUCCESS ? mSuccessEffect : mFailEffect;
        if (effect == 0) {
            if (result == RESULT_SUCCESS) removeWindow();
            return;
        }
        mResult = result;
        if (mResultAnim != null) mResultAnim.cancel();
        mResultAnim = ValueAnimator.ofFloat(0f, 1f);
        mResultAnim.setDuration(RESULT_DURATION_MS);
        mResultAnim.addUpdateListener(a -> {
            mResultT = (float) a.getAnimatedValue();
            invalidate();
        });
        mResultAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                final boolean success = mResult == RESULT_SUCCESS;
                mResult = RESULT_NONE;
                if (success || !mFingerDown) {
                    removeWindow();
                    if (mPendingRelease) {
                        mPendingRelease = false;
                        unregister();
                    }
                } else {
                    invalidate();
                }
            }
        });
        mResultAnim.start();
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == mPackAnim || super.verifyDrawable(who);
    }

    // ---- Drawing ----

    private int colorAt(float phase) {
        if (mColorMode == 3) {
            return Color.HSVToColor(new float[] {(phase * 360f) % 360f, 0.65f, 1f});
        }
        return mColor;
    }

    private static int withAlpha(int color, float alpha) {
        final int a = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        // The window itself is centred on sensor + user offset.
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float r = mSensorR;
        final float outer = mSensorR * 2.2f * mScale;

        canvas.save();
        if (mResult == RESULT_FAIL && mFailEffect == 1) {
            final float damp = 1f - mResultT;
            canvas.translate((float) Math.sin(mResultT * Math.PI * 6) * r * 0.22f * damp, 0);
        }

        drawPressed(canvas, cx, cy, r);

        if (mEffect == IchthysUdfpsSettings.EFFECT_PACK && mPackAnim != null) {
            final int half = mPackSize / 2;
            mPackAnim.setBounds((int) cx - half, (int) cy - half, (int) cx + half,
                    (int) cy + half);
            mPackAnim.draw(canvas);
        } else if (mResult != RESULT_SUCCESS) {
            drawEffect(canvas, cx, cy, r, outer);
        }

        if (mResult == RESULT_SUCCESS) drawSuccess(canvas, cx, cy, r);
        else if (mResult == RESULT_FAIL && mFailEffect == 2) drawFailFlash(canvas, cx, cy, r);
        canvas.restore();
    }

    private void drawPressed(Canvas c, float cx, float cy, float r) {
        if (mPressedStyle == 0) return;
        final float pr = r * mPressedSize;
        switch (mPressedStyle) {
            case 1:
                mFillPaint.setShader(null);
                mFillPaint.setColor(mPressedColor);
                c.drawCircle(cx, cy, pr, mFillPaint);
                break;
            case 2:
                mPaint.setShader(null);
                mPaint.setColor(mPressedColor);
                mPaint.setStrokeWidth(r * 0.08f);
                c.drawCircle(cx, cy, pr, mPaint);
                break;
            case 3:
                mFillPaint.setShader(new RadialGradient(cx, cy, pr * 1.6f,
                        new int[] {withAlpha(mPressedColor, 0.85f), withAlpha(mPressedColor, 0.35f),
                                Color.TRANSPARENT},
                        new float[] {r / (pr * 1.6f), 0.7f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, pr * 1.6f, mFillPaint);
                mFillPaint.setShader(null);
                break;
            default:
                break;
        }
    }

    private void drawEffect(Canvas c, float cx, float cy, float r, float outer) {
        final float t = mT;
        final float inner = r * 1.08f;
        mPaint.setShader(null);
        mFillPaint.setShader(null);
        switch (mEffect) {
            case IchthysUdfpsSettings.EFFECT_RIPPLE: {
                mPaint.setStrokeWidth(r * 0.06f);
                for (int i = 0; i < 3; i++) {
                    final float p = (t + i / 3f) % 1f;
                    mPaint.setColor(withAlpha(colorAt(p), (1f - p) * 0.9f));
                    c.drawCircle(cx, cy, inner + (outer - inner) * p, mPaint);
                }
                break;
            }
            case IchthysUdfpsSettings.EFFECT_RADAR: {
                final float mid = (inner + outer * 0.85f) / 2f;
                final float band = outer * 0.85f - inner;
                final int col = colorAt(t);
                final SweepGradient sweep = new SweepGradient(cx, cy,
                        new int[] {Color.TRANSPARENT, withAlpha(col, 0.15f), withAlpha(col, 0.9f),
                                Color.TRANSPARENT},
                        new float[] {0f, 0.55f, 0.98f, 1f});
                mMatrix.setRotate(t * 360f, cx, cy);
                sweep.setLocalMatrix(mMatrix);
                mPaint.setShader(sweep);
                mPaint.setStrokeWidth(band);
                c.drawCircle(cx, cy, mid, mPaint);
                mPaint.setShader(null);
                mPaint.setStrokeWidth(r * 0.03f);
                mPaint.setColor(withAlpha(col, 0.5f));
                c.drawCircle(cx, cy, outer * 0.85f, mPaint);
                break;
            }
            case IchthysUdfpsSettings.EFFECT_PARTICLES: {
                final int n = 20;
                for (int i = 0; i < n; i++) {
                    final float p = (t + (i * 0.618f) % 1f) % 1f;
                    final double ang = i * (2 * Math.PI / n) + i * 0.37;
                    final float d = inner + (outer - inner) * p;
                    mFillPaint.setColor(withAlpha(colorAt((float) i / n), 1f - p));
                    c.drawCircle(cx + (float) Math.cos(ang) * d, cy + (float) Math.sin(ang) * d,
                            r * (0.07f - 0.04f * p), mFillPaint);
                }
                break;
            }
            case IchthysUdfpsSettings.EFFECT_SHOAL: {
                // Offset (honeycomb) lattice of dots; a wave of light travels outwards.
                final float dot = r * 0.075f;
                final float pitch = dot * 3.2f;
                final float rowH = pitch * 0.866f;
                final int rows = (int) Math.ceil(outer / rowH);
                for (int row = -rows; row <= rows; row++) {
                    final float y = row * rowH;
                    final float shift = (Math.abs(row) % 2) * pitch / 2f;
                    final int cols = (int) Math.ceil(outer / pitch) + 1;
                    for (int col = -cols; col <= cols; col++) {
                        final float x = col * pitch + shift;
                        final float d = (float) Math.hypot(x, y);
                        if (d < inner || d > outer) continue;
                        final float wave = 1f - Math.min(1f,
                                Math.abs((d - inner) / (outer - inner) - t) * 4f);
                        if (wave <= 0.02f) continue;
                        mFillPaint.setColor(withAlpha(colorAt(d / outer), wave));
                        c.drawCircle(cx + x, cy + y, dot * (0.6f + 0.4f * wave), mFillPaint);
                    }
                }
                break;
            }
            case IchthysUdfpsSettings.EFFECT_PULSE: {
                final float s = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI);
                final float pr = inner + (outer * 0.8f - inner) * s;
                final int col = colorAt(t);
                final float a0 = Math.min(inner / pr, 0.85f);
                mFillPaint.setShader(new RadialGradient(cx, cy, pr,
                        new int[] {Color.TRANSPARENT, withAlpha(col, 0.55f), Color.TRANSPARENT},
                        new float[] {a0, Math.max(a0 + 0.05f, 0.8f), 1f},
                        Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, pr, mFillPaint);
                mFillPaint.setShader(null);
                break;
            }
            case IchthysUdfpsSettings.EFFECT_AURORA: {
                mPaint.setStrokeWidth(r * 0.09f);
                for (int i = 0; i < 3; i++) {
                    final float rad = inner + (outer - inner) * (0.25f + 0.3f * i);
                    mRect.set(cx - rad, cy - rad, cx + rad, cy + rad);
                    final float start = (t * 360f * (i % 2 == 0 ? 1 : -1.4f)) + i * 70f;
                    final float sweep = 90f + 60f * (float) Math.sin((t + i * 0.3f) * 2 * Math.PI);
                    mPaint.setColor(withAlpha(colorAt(t + i * 0.2f), 0.75f - i * 0.15f));
                    c.drawArc(mRect, start, sweep, false, mPaint);
                }
                break;
            }
            case IchthysUdfpsSettings.EFFECT_SPINNER: {
                final float rad = inner + (outer - inner) * 0.25f;
                mRect.set(cx - rad, cy - rad, cx + rad, cy + rad);
                final int col = colorAt(t);
                final SweepGradient sweep = new SweepGradient(cx, cy,
                        new int[] {Color.TRANSPARENT, col}, new float[] {0.25f, 1f});
                mMatrix.setRotate(t * 360f - 90f, cx, cy);
                sweep.setLocalMatrix(mMatrix);
                mPaint.setShader(sweep);
                mPaint.setStrokeWidth(r * 0.1f);
                c.drawArc(mRect, t * 360f - 360f + 90f, 270f, false, mPaint);
                mPaint.setShader(null);
                break;
            }
            case IchthysUdfpsSettings.EFFECT_SCANLINE: {
                // Horizontal scan lines inside the ring with a bright band sweeping down and a
                // little glitch jitter.
                c.save();
                mPath.reset();
                mPath.addCircle(cx, cy, outer * 0.8f, Path.Direction.CW);
                mPath.addCircle(cx, cy, inner, Path.Direction.CCW);
                c.clipPath(mPath);
                final float top = cy - outer * 0.8f;
                final float h = outer * 1.6f;
                final float bandY = top + h * t;
                final float gap = r * 0.09f;
                mPaint.setStrokeWidth(r * 0.03f);
                for (float y = top; y < top + h; y += gap) {
                    final float near = 1f - Math.min(1f, Math.abs(y - bandY) / (h * 0.18f));
                    final float jitter = (near > 0.6f && ((int) (y / gap + t * 40) % 3 == 0))
                            ? r * 0.12f : 0f;
                    mPaint.setColor(withAlpha(colorAt(y / h), 0.15f + 0.8f * near));
                    c.drawLine(cx - outer + jitter, y, cx + outer + jitter, y, mPaint);
                }
                c.restore();
                break;
            }
            default:
                break;
        }
    }

    private void drawSuccess(Canvas c, float cx, float cy, float r) {
        final float p = mResultT;
        final int col = mColorMode == 3 ? colorAt(p) : mColor;
        final float rad = r * 1.3f;
        mPaint.setShader(null);
        switch (mSuccessEffect) {
            case 1: {
                mPaint.setStrokeWidth(r * 0.12f);
                mPaint.setColor(col);
                mRect.set(cx - rad, cy - rad, cx + rad, cy + rad);
                c.drawArc(mRect, -90f, 360f * Math.min(1f, p * 1.4f), false, mPaint);
                break;
            }
            case 2: {
                mPaint.setStrokeWidth(r * 0.12f);
                mPaint.setColor(col);
                c.drawCircle(cx, cy, rad, mPaint);
                // Check mark drawn progressively inside the ring.
                final float k = Math.min(1f, p * 1.5f);
                final float x0 = cx - rad * 0.45f, y0 = cy;
                final float x1 = cx - rad * 0.1f, y1 = cy + rad * 0.35f;
                final float x2 = cx + rad * 0.5f, y2 = cy - rad * 0.35f;
                if (k < 0.4f) {
                    final float q = k / 0.4f;
                    c.drawLine(x0, y0, x0 + (x1 - x0) * q, y0 + (y1 - y0) * q, mPaint);
                } else {
                    final float q = (k - 0.4f) / 0.6f;
                    c.drawLine(x0, y0, x1, y1, mPaint);
                    c.drawLine(x1, y1, x1 + (x2 - x1) * q, y1 + (y2 - y1) * q, mPaint);
                }
                break;
            }
            case 3: {
                final int n = 16;
                for (int i = 0; i < n; i++) {
                    final double ang = i * 2 * Math.PI / n;
                    final float d = r * (1.1f + 1.3f * p);
                    mFillPaint.setColor(withAlpha(col, 1f - p));
                    c.drawCircle(cx + (float) Math.cos(ang) * d, cy + (float) Math.sin(ang) * d,
                            r * 0.08f * (1f - p * 0.5f), mFillPaint);
                }
                break;
            }
            default:
                break;
        }
    }

    private void drawFailFlash(Canvas c, float cx, float cy, float r) {
        final float a = 1f - mResultT;
        mPaint.setShader(null);
        mPaint.setStrokeWidth(r * 0.14f);
        mPaint.setColor(withAlpha(0xFFFF4D4D, a));
        c.drawCircle(cx, cy, r * 1.3f, mPaint);
    }
}
