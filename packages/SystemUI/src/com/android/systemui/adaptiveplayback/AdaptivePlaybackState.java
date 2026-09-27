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

package com.android.systemui.adaptiveplayback;

/**
 * Pure state machine behind Adaptive playback (crDroid {@code adaptive_playback_settings.xml}).
 *
 * <p>"Pause media when volume is muted and resume it by increasing volume within the timeout."
 *
 * <p>The safety property this class exists to guarantee is that a {@link Action#RESUME} is only
 * ever produced after this same state machine produced a {@link Action#PAUSE} that has not yet
 * expired. Unmuting on its own must never start playback the user did not have running.
 *
 * <p>Contains no {@code android.*} imports so it can be unit-tested on the host.
 */
public final class AdaptivePlaybackState {

    /** What the caller should do to the active media session. */
    public enum Action {
        /** Do nothing. */
        NONE,
        /** Dispatch a media pause. */
        PAUSE,
        /** Dispatch a media play. */
        RESUME,
    }

    /** Lowest timeout the slider can produce, in seconds. */
    public static final int MIN_TIMEOUT_SECONDS = 10;

    /** Highest timeout the slider can produce, in seconds. */
    public static final int MAX_TIMEOUT_SECONDS = 600;

    /** Value used when the setting is missing. Mirrors adaptive_playback_settings.xml. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private boolean mMuted;
    private boolean mPausedByUs;

    /** Clamps a raw timeout setting value into the range the slider can produce. */
    public static int clampTimeoutSeconds(int seconds) {
        if (seconds < MIN_TIMEOUT_SECONDS) return MIN_TIMEOUT_SECONDS;
        if (seconds > MAX_TIMEOUT_SECONDS) return MAX_TIMEOUT_SECONDS;
        return seconds;
    }

    /**
     * Feeds in the media stream's mute state.
     *
     * @param enabled whether the feature is currently switched on
     * @param muted whether the media stream is now silent (muted or at volume zero)
     * @param musicActive whether media is actually playing right now
     * @return the action the caller should take
     */
    public Action onMuteStateChanged(boolean enabled, boolean muted, boolean musicActive) {
        if (!enabled) {
            // Turning the feature off drops any armed resume, so re-enabling it later cannot
            // resurrect playback the user muted minutes ago.
            mMuted = muted;
            mPausedByUs = false;
            return Action.NONE;
        }
        final boolean wasMuted = mMuted;
        mMuted = muted;

        if (muted && !wasMuted) {
            if (musicActive && !mPausedByUs) {
                mPausedByUs = true;
                return Action.PAUSE;
            }
            return Action.NONE;
        }
        if (!muted && wasMuted && mPausedByUs) {
            mPausedByUs = false;
            return Action.RESUME;
        }
        return Action.NONE;
    }

    /** The timeout expired: forget that we paused, so a later unmute will not resume. */
    public void onTimeout() {
        mPausedByUs = false;
    }

    /** Drops all armed state, e.g. when the feature is switched off or the user switches. */
    public void reset() {
        mMuted = false;
        mPausedByUs = false;
    }

    /** True while an unmute would resume playback. */
    public boolean isAwaitingResume() {
        return mPausedByUs;
    }

    /** Last known mute state of the media stream. */
    public boolean isMuted() {
        return mMuted;
    }
}
