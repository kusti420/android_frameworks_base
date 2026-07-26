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
 * Base class for the Pulse renderers. Handles the common work of turning the
 * raw FFT byte stream into a set of normalized (0..1) bar heights with an
 * attack/decay envelope, plus bass-level extraction for haptics.
 */
package com.android.systemui.pulse;

import android.graphics.Canvas;

public abstract class Renderer implements VisualizerStreamHandler.Listener {

    // Perceptual scale factor applied to raw FFT magnitudes.
    private static final float MAGNITUDE_SCALE = 52f;
    // Envelope coefficients: bars rise quickly, fall slowly.
    private static final float ATTACK = 0.55f;
    private static final float RELEASE = 0.14f;
    // Number of low bins summed for the bass/haptic signal.
    private static final int BASS_BINS = 6;

    protected final PulseController mController;
    protected final PulseView mView;
    protected final PulseColorController mColorController;

    protected int mWidth;
    protected int mHeight;
    protected boolean mLeftInLandscape;

    protected int mBarCount = 32;
    protected boolean mRounded;

    // Current (rendered) and target (latest FFT) heights, 0..1.
    protected float[] mHeights = new float[mBarCount];
    protected float[] mTargets = new float[mBarCount];

    private boolean mValid;

    public Renderer(PulseController controller, PulseView view,
            PulseColorController colorController) {
        mController = controller;
        mView = view;
        mColorController = colorController;
    }

    public void setBarCount(int count) {
        if (count < 1) {
            count = 1;
        }
        if (count == mBarCount && mHeights.length == count) {
            return;
        }
        mBarCount = count;
        mHeights = new float[count];
        mTargets = new float[count];
    }

    public void setRounded(boolean rounded) {
        mRounded = rounded;
    }

    public void onSizeChanged(int w, int h) {
        mWidth = w;
        mHeight = h;
    }

    public void setLeftInLandscape(boolean left) {
        mLeftInLandscape = left;
    }

    public boolean isValidStream() {
        return mValid;
    }

    @Override
    public void onStreamValidityChanged(boolean isValid) {
        mValid = isValid;
        if (!isValid) {
            // Let the bars decay to zero, then stop.
            for (int i = 0; i < mTargets.length; i++) {
                mTargets[i] = 0f;
            }
        }
        mView.postInvalidate();
    }

    @Override
    public void onWaveFormUpdate(byte[] waveform) {
        // Only the waveform renderer cares; ignored by default.
    }

    @Override
    public void onFFTUpdate(byte[] fft) {
        computeTargets(fft);
        computeBass(fft);
        mView.postInvalidate();
    }

    /** Maps the interleaved (re, im) FFT bytes to per-bar target heights. */
    private void computeTargets(byte[] fft) {
        final int bins = (fft.length / 2) - 1; // skip DC + Nyquist pairing
        if (bins <= 0) {
            return;
        }
        final int perBar = Math.max(1, bins / mBarCount);
        for (int bar = 0; bar < mBarCount; bar++) {
            float peak = 0f;
            int start = bar * perBar;
            for (int j = 0; j < perBar; j++) {
                int bin = start + j;
                int idx = (bin + 1) * 2; // +1 to skip DC
                if (idx + 1 >= fft.length) {
                    break;
                }
                float re = fft[idx];
                float im = fft[idx + 1];
                float mag = (float) Math.hypot(re, im);
                if (mag > peak) {
                    peak = mag;
                }
            }
            // Perceptual (sqrt) scaling, clamped to 0..1.
            float norm = (float) Math.sqrt(peak / MAGNITUDE_SCALE);
            if (norm > 1f) {
                norm = 1f;
            }
            mTargets[bar] = norm;
        }
    }

    private void computeBass(byte[] fft) {
        float sum = 0f;
        int count = 0;
        for (int bin = 0; bin < BASS_BINS; bin++) {
            int idx = (bin + 1) * 2;
            if (idx + 1 >= fft.length) {
                break;
            }
            float re = fft[idx];
            float im = fft[idx + 1];
            sum += (float) Math.hypot(re, im);
            count++;
        }
        if (count == 0) {
            return;
        }
        float bass = (float) Math.sqrt((sum / count) / MAGNITUDE_SCALE);
        if (bass > 1f) {
            bass = 1f;
        }
        mController.onBassLevel(bass);
    }

    /** Advances the rendered heights toward the latest targets. Call once per frame. */
    protected void updateEnvelope() {
        for (int i = 0; i < mBarCount; i++) {
            float target = mTargets[i];
            float cur = mHeights[i];
            if (target > cur) {
                cur += (target - cur) * ATTACK;
            } else {
                cur += (target - cur) * RELEASE;
            }
            if (cur < 0.001f) {
                cur = 0f;
            }
            mHeights[i] = cur;
        }
    }

    /** True while any bar still has height worth drawing. */
    protected boolean hasEnergy() {
        for (int i = 0; i < mBarCount; i++) {
            if (mHeights[i] > 0.004f) {
                return true;
            }
        }
        return false;
    }

    public abstract void draw(Canvas canvas);

    public void destroy() {
    }
}
