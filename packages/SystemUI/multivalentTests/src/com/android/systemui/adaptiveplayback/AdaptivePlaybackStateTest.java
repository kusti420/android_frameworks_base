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

import static com.android.systemui.adaptiveplayback.AdaptivePlaybackState.Action.NONE;
import static com.android.systemui.adaptiveplayback.AdaptivePlaybackState.Action.PAUSE;
import static com.android.systemui.adaptiveplayback.AdaptivePlaybackState.Action.RESUME;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AdaptivePlaybackStateTest extends SysuiTestCase {

    private AdaptivePlaybackState mState;

    @Before
    public void setUp() {
        mState = new AdaptivePlaybackState();
    }

    @Test
    public void mutingWhilePlayingPauses_andUnmutingResumes() {
        assertThat(mState.onMuteStateChanged(true, true, true)).isEqualTo(PAUSE);
        assertThat(mState.isAwaitingResume()).isTrue();
        assertThat(mState.onMuteStateChanged(true, false, false)).isEqualTo(RESUME);
        assertThat(mState.isAwaitingResume()).isFalse();
    }

    @Test
    public void unmutingAfterTheTimeoutDoesNotResume() {
        assertThat(mState.onMuteStateChanged(true, true, true)).isEqualTo(PAUSE);
        mState.onTimeout();
        assertThat(mState.isAwaitingResume()).isFalse();
        assertThat(mState.onMuteStateChanged(true, false, false)).isEqualTo(NONE);
    }

    @Test
    public void mutingWithNothingPlayingNeverArmsAResume() {
        assertThat(mState.onMuteStateChanged(true, true, false)).isEqualTo(NONE);
        assertThat(mState.onMuteStateChanged(true, false, true)).isEqualTo(NONE);
    }

    @Test
    public void repeatedMuteEventsAreIdempotent() {
        assertThat(mState.onMuteStateChanged(true, true, true)).isEqualTo(PAUSE);
        assertThat(mState.onMuteStateChanged(true, true, true)).isEqualTo(NONE);
        assertThat(mState.onMuteStateChanged(true, false, false)).isEqualTo(RESUME);
        assertThat(mState.onMuteStateChanged(true, false, false)).isEqualTo(NONE);
    }

    @Test
    public void disablingWhileArmedDropsTheArm() {
        assertThat(mState.onMuteStateChanged(true, true, true)).isEqualTo(PAUSE);
        assertThat(mState.onMuteStateChanged(false, true, true)).isEqualTo(NONE);
        assertThat(mState.isAwaitingResume()).isFalse();
        assertThat(mState.onMuteStateChanged(true, false, true)).isEqualTo(NONE);
    }

    @Test
    public void resetClearsEverything() {
        mState.onMuteStateChanged(true, true, true);
        mState.reset();
        assertThat(mState.isAwaitingResume()).isFalse();
        assertThat(mState.isMuted()).isFalse();
        assertThat(mState.onMuteStateChanged(true, false, true)).isEqualTo(NONE);
    }

    @Test
    public void clampTimeoutSeconds_staysInsideTheSliderRange() {
        assertThat(AdaptivePlaybackState.clampTimeoutSeconds(Integer.MIN_VALUE))
                .isEqualTo(AdaptivePlaybackState.MIN_TIMEOUT_SECONDS);
        assertThat(AdaptivePlaybackState.clampTimeoutSeconds(0))
                .isEqualTo(AdaptivePlaybackState.MIN_TIMEOUT_SECONDS);
        assertThat(AdaptivePlaybackState.clampTimeoutSeconds(30)).isEqualTo(30);
        assertThat(AdaptivePlaybackState.clampTimeoutSeconds(600)).isEqualTo(600);
        assertThat(AdaptivePlaybackState.clampTimeoutSeconds(Integer.MAX_VALUE))
                .isEqualTo(AdaptivePlaybackState.MAX_TIMEOUT_SECONDS);
    }

    @Test
    public void resumeOnlyEverFollowsAPauseThisMachineIssued() {
        // Exhaustively drive every four-step sequence of (enabled, muted, musicActive) and check
        // the safety invariant: playback is never started unless we stopped it first.
        for (int bits = 0; bits < (1 << 12); bits++) {
            AdaptivePlaybackState state = new AdaptivePlaybackState();
            boolean armed = false;
            for (int step = 0; step < 4; step++) {
                boolean enabled = ((bits >> (step * 3)) & 1) != 0;
                boolean muted = ((bits >> (step * 3 + 1)) & 1) != 0;
                boolean musicActive = ((bits >> (step * 3 + 2)) & 1) != 0;
                AdaptivePlaybackState.Action action =
                        state.onMuteStateChanged(enabled, muted, musicActive);
                if (action == PAUSE) {
                    assertThat(enabled && muted && musicActive && !armed).isTrue();
                    armed = true;
                } else if (action == RESUME) {
                    assertThat(armed).isTrue();
                    armed = false;
                }
                if (!enabled) {
                    armed = false;
                }
                assertThat(state.isAwaitingResume()).isEqualTo(armed);
            }
        }
    }
}
