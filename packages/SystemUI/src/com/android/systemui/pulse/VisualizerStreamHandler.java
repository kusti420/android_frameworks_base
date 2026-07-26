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
 * Wraps android.media.audiofx.Visualizer on the global output mix (session 0)
 * and feeds FFT / waveform data to a listener. Also performs a light "is the
 * stream actually producing sound" validation so the visualizer stops drawing
 * on silence instead of freezing on the last frame.
 */
package com.android.systemui.pulse;

import android.media.audiofx.Visualizer;
import android.util.Log;

public class VisualizerStreamHandler {

    private static final String TAG = "Pulse.StreamHandler";
    private static final boolean DEBUG = false;

    // Number of consecutive frames that must contain "silence" before we
    // report the stream as invalid (and stop drawing).
    private static final int EMPTY_FRAME_TOLERANCE = 8;
    // A frame whose summed FFT magnitude is below this is treated as silence.
    private static final long SILENCE_MAGNITUDE = 0;

    public interface Listener {
        /** Called when the stream transitions between producing / not producing sound. */
        void onStreamValidityChanged(boolean isValid);
        /** Raw FFT bytes (real/imag interleaved). */
        void onFFTUpdate(byte[] fft);
        /** Raw PCM waveform bytes. */
        void onWaveFormUpdate(byte[] waveform);
    }

    private final Listener mListener;
    private final int mAudioSessionId;

    private Visualizer mVisualizer;
    private boolean mLinked;
    private boolean mValid;
    private int mEmptyFrames;

    public VisualizerStreamHandler(int audioSessionId, Listener listener) {
        mAudioSessionId = audioSessionId;
        mListener = listener;
    }

    public boolean isLinked() {
        return mLinked;
    }

    public synchronized void link() {
        if (mLinked) {
            return;
        }
        try {
            mVisualizer = new Visualizer(mAudioSessionId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to create Visualizer", e);
            mVisualizer = null;
            return;
        }
        try {
            // Must be disabled while (re)configuring.
            if (mVisualizer.getEnabled()) {
                mVisualizer.setEnabled(false);
            }
            int[] range = Visualizer.getCaptureSizeRange();
            mVisualizer.setCaptureSize(range[1]);
            mVisualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            // ~75% of the max rate keeps the animation smooth without hammering
            // the audio HAL.
            int rate = (int) (Visualizer.getMaxCaptureRate() * 0.75f);
            mVisualizer.setDataCaptureListener(mCaptureListener, rate,
                    true /* waveform */, true /* fft */);
            mVisualizer.setEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "Failed to configure Visualizer", e);
            releaseInternal();
            return;
        }
        mLinked = true;
        mValid = false;
        mEmptyFrames = 0;
        if (DEBUG) Log.d(TAG, "linked to session " + mAudioSessionId);
    }

    public synchronized void unlink() {
        if (!mLinked) {
            return;
        }
        releaseInternal();
        mLinked = false;
        if (mValid) {
            mValid = false;
            mListener.onStreamValidityChanged(false);
        }
        if (DEBUG) Log.d(TAG, "unlinked");
    }

    private void releaseInternal() {
        if (mVisualizer != null) {
            try {
                mVisualizer.setEnabled(false);
            } catch (Exception ignored) {
            }
            try {
                mVisualizer.release();
            } catch (Exception ignored) {
            }
            mVisualizer = null;
        }
    }

    private void analyze(byte[] fft) {
        long magnitude = 0;
        // Skip DC (0) and Nyquist (1); sum |bin| for the rest.
        for (int i = 2; i < fft.length; i += 2) {
            byte re = fft[i];
            byte im = fft[i + 1];
            magnitude += (long) (re * re + im * im);
        }
        boolean nowValid;
        if (magnitude > SILENCE_MAGNITUDE) {
            mEmptyFrames = 0;
            nowValid = true;
        } else {
            if (mEmptyFrames < EMPTY_FRAME_TOLERANCE) {
                mEmptyFrames++;
            }
            nowValid = mEmptyFrames < EMPTY_FRAME_TOLERANCE;
        }
        if (nowValid != mValid) {
            mValid = nowValid;
            mListener.onStreamValidityChanged(mValid);
        }
    }

    private final Visualizer.OnDataCaptureListener mCaptureListener =
            new Visualizer.OnDataCaptureListener() {
        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] bytes, int samplingRate) {
            if (!mLinked || bytes == null) {
                return;
            }
            mListener.onWaveFormUpdate(bytes);
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] bytes, int samplingRate) {
            if (!mLinked || bytes == null) {
                return;
            }
            analyze(bytes);
            if (mValid) {
                mListener.onFFTUpdate(bytes);
            }
        }
    };
}
