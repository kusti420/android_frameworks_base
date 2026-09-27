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

import java.util.Random;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class EdgeLightColorModeTest extends SysuiTestCase {

    /** Distinctive fallback so a wrong result is obvious. */
    private static final int FALLBACK = 0x11223344;

    @Test
    public void fromSettings_roundTripsAndFallsBackToAccent() {
        for (EdgeLightColorMode mode : EdgeLightColorMode.values()) {
            assertThat(EdgeLightColorMode.fromSettings(mode.settingsValue())).isEqualTo(mode);
        }
        assertThat(EdgeLightColorMode.fromSettings(null)).isEqualTo(EdgeLightColorMode.ACCENT);
        assertThat(EdgeLightColorMode.fromSettings("")).isEqualTo(EdgeLightColorMode.ACCENT);
        assertThat(EdgeLightColorMode.fromSettings("Rainbow")).isEqualTo(EdgeLightColorMode.ACCENT);
    }

    @Test
    public void onlyRainbowVariesAlongTheBorder() {
        for (EdgeLightColorMode mode : EdgeLightColorMode.values()) {
            assertThat(mode.isPerSegment()).isEqualTo(mode == EdgeLightColorMode.RAINBOW);
        }
    }

    @Test
    public void sanitize_neverProducesAnInvisibleLight() {
        assertThat(EdgeLightColorMode.sanitize(0)).isEqualTo(EdgeLightColorMode.FALLBACK_COLOR);
        assertThat(EdgeLightColorMode.sanitize(0xFF000000))
                .isEqualTo(EdgeLightColorMode.FALLBACK_COLOR);
        assertThat(EdgeLightColorMode.sanitize(0x00FF0000)).isEqualTo(0xFFFF0000);
        assertThat(EdgeLightColorMode.sanitize(0x80336699)).isEqualTo(0xFF336699);

        Random random = new Random(12345);
        for (int i = 0; i < 20000; i++) {
            int sanitized = EdgeLightColorMode.sanitize(random.nextInt());
            assertThat(sanitized >>> 24).isEqualTo(255);
            assertThat(sanitized & 0x00FFFFFF).isNotEqualTo(0);
        }
    }

    @Test
    public void rainbow_isOpaqueFullySaturatedAndCoversTheHueCircle() {
        boolean sawRed = false;
        boolean sawGreen = false;
        boolean sawBlue = false;
        for (int i = 0; i < 3600; i++) {
            int color = EdgeLightColorMode.rainbowAt(i / 3600f, 0f);
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            assertThat(color >>> 24).isEqualTo(255);
            assertThat(Math.max(r, Math.max(g, b))).isEqualTo(255);
            assertThat(Math.min(r, Math.min(g, b))).isEqualTo(0);
            sawRed |= r == 255 && g < 40 && b < 40;
            sawGreen |= g == 255 && r < 40 && b < 40;
            sawBlue |= b == 255 && r < 40 && g < 40;
        }
        assertThat(sawRed).isTrue();
        assertThat(sawGreen).isTrue();
        assertThat(sawBlue).isTrue();
    }

    @Test
    public void rainbow_hasNoSeamAndProgressOnlyRotatesIt() {
        assertThat(EdgeLightColorMode.rainbowAt(0f, 0f))
                .isEqualTo(EdgeLightColorMode.rainbowAt(1f, 0f));
        assertThat(EdgeLightColorMode.rainbowAt(0.25f, 0f))
                .isEqualTo(EdgeLightColorMode.rainbowAt(0f, 0.25f));
        assertThat(EdgeLightColorMode.rainbowAt(0.3f, 0f))
                .isEqualTo(EdgeLightColorMode.rainbowAt(0.1f, 0.2f));
    }

    @Test
    public void parseSettingsColor_readsWhatTheColourPickerActuallyWrites() {
        // ColorPickerPreference.persistInt stores a decimal, usually negative, ARGB int.
        assertThat(EdgeLightColorMode.parseSettingsColor("-65536", FALLBACK)).isEqualTo(0xFFFF0000);
        assertThat(EdgeLightColorMode.parseSettingsColor("-1", FALLBACK)).isEqualTo(0xFFFFFFFF);
        assertThat(EdgeLightColorMode.parseSettingsColor("0", FALLBACK)).isEqualTo(0);
        assertThat(EdgeLightColorMode.parseSettingsColor(
                String.valueOf(Integer.MIN_VALUE), FALLBACK)).isEqualTo(Integer.MIN_VALUE);

        // The preference XML default is a hex string, so that spelling must work too.
        assertThat(EdgeLightColorMode.parseSettingsColor("#FFFF0000", FALLBACK))
                .isEqualTo(0xFFFF0000);
        assertThat(EdgeLightColorMode.parseSettingsColor("#FFFFFF", FALLBACK)).isEqualTo(0xFFFFFFFF);
        assertThat(EdgeLightColorMode.parseSettingsColor("#000000", FALLBACK)).isEqualTo(0xFF000000);
    }

    @Test
    public void parseSettingsColor_fallsBackRatherThanGuessing() {
        String[] bad = {null, "", "   ", "#", "#12345", "#1234567", "#GGGGGG", "not-a-color",
                "12.5", "1e5", "--1", "#-65536", "999999999999999999999"};
        for (String value : bad) {
            assertThat(EdgeLightColorMode.parseSettingsColor(value, FALLBACK)).isEqualTo(FALLBACK);
        }
    }

    @Test
    public void parseSettingsColor_roundTripsEveryPersistableInt() {
        Random random = new Random(99);
        for (int i = 0; i < 20000; i++) {
            int color = random.nextInt();
            assertThat(EdgeLightColorMode.parseSettingsColor(Integer.toString(color), FALLBACK))
                    .isEqualTo(color);
        }
    }

    @Test
    public void rainbow_survivesHostileInput() {
        float[] hostile = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                -1f, 2f, 1e9f, -1e9f};
        for (float position : hostile) {
            for (float progress : hostile) {
                assertThat(EdgeLightColorMode.rainbowAt(position, progress) >>> 24).isEqualTo(255);
            }
        }
    }
}
