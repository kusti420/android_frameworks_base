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

package com.android.systemui.edgelight;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class EdgeLightEffectTest extends SysuiTestCase {

    private static final int POSITIONS = 360;
    private static final int STEPS = 120;

    @Test
    public void fromSettings_roundTripsAndFallsBackToNone() {
        for (EdgeLightEffect effect : EdgeLightEffect.values()) {
            assertThat(EdgeLightEffect.fromSettings(effect.settingsValue())).isEqualTo(effect);
        }
        assertThat(EdgeLightEffect.fromSettings(null)).isEqualTo(EdgeLightEffect.NONE);
        assertThat(EdgeLightEffect.fromSettings("")).isEqualTo(EdgeLightEffect.NONE);
        assertThat(EdgeLightEffect.fromSettings("Breathing")).isEqualTo(EdgeLightEffect.NONE);
        assertThat(EdgeLightEffect.fromSettings("nonsense")).isEqualTo(EdgeLightEffect.NONE);
    }

    @Test
    public void alphaAt_alwaysProducesAUsableOpacity() {
        for (EdgeLightEffect effect : EdgeLightEffect.values()) {
            for (int i = 0; i < POSITIONS; i++) {
                for (int j = 0; j < STEPS; j++) {
                    float alpha = effect.alphaAt(i / (float) POSITIONS, j / (float) STEPS);
                    assertThat(Float.isNaN(alpha)).isFalse();
                    assertThat(alpha).isAtLeast(0f);
                    assertThat(alpha).isAtMost(1f);
                }
            }
        }
    }

    @Test
    public void alphaAt_survivesHostileInput() {
        float[] hostile = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                -1f, -1234.5f, 2f, 1e9f, -1e9f};
        for (EdgeLightEffect effect : EdgeLightEffect.values()) {
            for (float position : hostile) {
                for (float progress : hostile) {
                    float alpha = effect.alphaAt(position, progress);
                    assertThat(Float.isNaN(alpha)).isFalse();
                    assertThat(alpha).isAtLeast(0f);
                    assertThat(alpha).isAtMost(1f);
                }
            }
        }
    }

    @Test
    public void theBorderHasNoSeamWhereItJoins() {
        for (EdgeLightEffect effect : EdgeLightEffect.values()) {
            for (int j = 0; j < STEPS; j++) {
                float progress = j / (float) STEPS;
                assertThat(effect.alphaAt(0f, progress))
                        .isWithin(1e-4f).of(effect.alphaAt(1f, progress));
            }
        }
    }

    @Test
    public void repeatedPulsesJoinSmoothly() {
        for (EdgeLightEffect effect : EdgeLightEffect.values()) {
            for (int i = 0; i < POSITIONS; i += 7) {
                float position = i / (float) POSITIONS;
                assertThat(effect.alphaAt(position, 0f))
                        .isWithin(1e-4f).of(effect.alphaAt(position, 1f));
            }
        }
    }

    @Test
    public void everyEffectGetsBright_andOnlyNoneIsConstant() {
        for (EdgeLightEffect effect : EdgeLightEffect.values()) {
            float min = 2f;
            float max = -1f;
            for (int i = 0; i < POSITIONS; i++) {
                for (int j = 0; j < STEPS; j++) {
                    float alpha = effect.alphaAt(i / (float) POSITIONS, j / (float) STEPS);
                    min = Math.min(min, alpha);
                    max = Math.max(max, alpha);
                }
            }
            assertThat(max).isGreaterThan(0.9f);
            if (effect == EdgeLightEffect.NONE) {
                assertThat(min).isEqualTo(1f);
            } else {
                assertThat(max - min).isGreaterThan(0.3f);
            }
        }
    }

    @Test
    public void breathingFadesTheWholeBorderTogether_andNeverGoesDark() {
        for (int j = 0; j < STEPS; j++) {
            float progress = j / (float) STEPS;
            float reference = EdgeLightEffect.BREATHING.alphaAt(0f, progress);
            assertThat(reference).isAtLeast(0.14f);
            for (int i = 0; i < POSITIONS; i += 11) {
                assertThat(EdgeLightEffect.BREATHING.alphaAt(i / (float) POSITIONS, progress))
                        .isWithin(1e-5f).of(reference);
            }
        }
    }

    @Test
    public void travellingEffectsActuallyTravel() {
        for (EdgeLightEffect effect : new EdgeLightEffect[] {
                EdgeLightEffect.WAVE, EdgeLightEffect.CHASE, EdgeLightEffect.COMET}) {
            int previousPeak = -1;
            int advances = 0;
            for (int j = 0; j < STEPS; j++) {
                float progress = j / (float) STEPS;
                int peak = 0;
                float best = -1f;
                for (int i = 0; i < POSITIONS; i++) {
                    float alpha = effect.alphaAt(i / (float) POSITIONS, progress);
                    if (alpha > best) {
                        best = alpha;
                        peak = i;
                    }
                }
                if (previousPeak >= 0 && peak > previousPeak) {
                    advances++;
                }
                previousPeak = peak;
            }
            assertThat(advances).isGreaterThan(STEPS / 2);
        }
    }

    @Test
    public void sparkleIsDeterministicAndLightsAHealthyFraction() {
        int lit = 0;
        int total = 0;
        for (int i = 0; i < POSITIONS; i++) {
            for (int j = 0; j < STEPS; j++) {
                float position = i / (float) POSITIONS;
                float progress = j / (float) STEPS;
                float first = EdgeLightEffect.SPARKLE.alphaAt(position, progress);
                assertThat(EdgeLightEffect.SPARKLE.alphaAt(position, progress)).isEqualTo(first);
                if (first > 0f) {
                    lit++;
                }
                total++;
            }
        }
        double ratio = lit / (double) total;
        assertThat(ratio).isGreaterThan(0.5);
        assertThat(ratio).isLessThan(0.8);
    }
}
