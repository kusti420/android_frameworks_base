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

package com.android.server.theming;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.android.server.theming.ShoalPalette.Surface;

import com.google.ux.material.libmonet.hct.Hct;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class ShoalPaletteTest {

    /** theme.accent of the shipped presets (data/etc/ichthys/presets/*.nix). */
    private static final int KOI = 0xFFFF6B3D;
    private static final int ABYSS = 0xFF5CC8C2;
    private static final int REEF = 0xFFF2A65A;
    private static final int KELP = 0xFF9FD36A;
    private static final int PAPER = 0xFF2F6F6A;
    private static final int[] PRESET_SEEDS = {KOI, ABYSS, REEF, KELP, PAPER};

    /** Material on-X / X pairs that must reach 4.5:1. */
    private static final String[][] ROLE_PAIRS = {
            {"on_surface", "surface"},
            {"on_surface", "surface_container_low"},
            {"on_surface", "surface_container_high"},
            {"on_surface", "surface_container_highest"},
            {"on_background", "background"},
            {"on_surface_variant", "surface_variant"},
            {"on_surface_variant", "surface"},
            {"inverse_on_surface", "inverse_surface"},
            {"on_primary", "primary"},
            {"on_secondary", "secondary"},
            {"on_tertiary", "tertiary"},
            {"on_primary_fixed", "primary_fixed"},
            {"on_primary_container", "primary_container"},
            {"on_secondary_container", "secondary_container"},
            {"on_tertiary_container", "tertiary_container"},
    };

    /** Token pairs that must reach 4.5:1. */
    private static final String[][] TOKEN_PAIRS = {
            {ShoalPalette.TEXT, ShoalPalette.INK},
            {ShoalPalette.TEXT, ShoalPalette.CARD},
            {ShoalPalette.TEXT, ShoalPalette.RAISED},
            {ShoalPalette.TEXT, ShoalPalette.TILE},
            {ShoalPalette.BODY, ShoalPalette.INK},
            {ShoalPalette.BODY, ShoalPalette.CARD},
            {ShoalPalette.MUTED, ShoalPalette.INK},
            {ShoalPalette.MUTED, ShoalPalette.CARD},
            {ShoalPalette.MUTED, ShoalPalette.RAISED},
            {ShoalPalette.ON_ACCENT, ShoalPalette.ACCENT},
    };

    @Test
    public void koi_neutral_reproducesDarkAnchors() {
        final Map<String, Integer> t = ShoalPalette.create(KOI, Surface.HAIRLINE,
                ShoalPalette.NEUTRAL, 4, false, true).tokens();
        assertThat(t.get(ShoalPalette.INK)).isEqualTo(0xFF07090B);
        assertThat(t.get(ShoalPalette.CARD)).isEqualTo(0xFF0C1013);
        assertThat(t.get(ShoalPalette.RAISED)).isEqualTo(0xFF161C20);
        assertThat(t.get(ShoalPalette.TILE)).isEqualTo(0xFF172125);
        assertThat(t.get(ShoalPalette.HAIRLINE)).isEqualTo(0xFF20282D);
        assertThat(t.get(ShoalPalette.TRACK)).isEqualTo(0xFF2A3136);
        assertThat(t.get(ShoalPalette.MUTED)).isEqualTo(0xFF8B938F);
        assertThat(t.get(ShoalPalette.BODY)).isEqualTo(0xFFC9CECA);
        assertThat(t.get(ShoalPalette.TEXT)).isEqualTo(0xFFF2F4F1);
        assertThat(t.get(ShoalPalette.ACCENT)).isEqualTo(KOI);
        assertThat(t.get(ShoalPalette.ON_ACCENT)).isEqualTo(0xFF000000);
        // The outline anchor (#3a4246) is only ~1.9:1 on ink, so the guard lifts its tone to
        // 3:1 while keeping its hue family.
        final int outline = t.get(ShoalPalette.OUTLINE);
        assertThat(ShoalPalette.contrast(outline, 0xFF07090B)).isAtLeast(3.0);
        assertThat(Math.abs(Hct.fromInt(outline).getHue() - Hct.fromInt(0xFF3A4246).getHue()))
                .isLessThan(15.0);
        // Accent mixes: ~3a2418 fill, ~1f1511 dim.
        assertClose(t.get(ShoalPalette.ACCENT_FILL), 0xFF3A2418, 6);
        assertClose(t.get(ShoalPalette.ACCENT_DIM), 0xFF1F1511, 8);
    }

    @Test
    public void koi_neutral_reproducesLightAnchors() {
        final Map<String, Integer> t = ShoalPalette.create(KOI, Surface.HAIRLINE,
                ShoalPalette.NEUTRAL, 4, false, false).tokens();
        assertThat(t.get(ShoalPalette.INK)).isEqualTo(ShoalPalette.LIGHT_ANCHORS[0]);
        assertThat(t.get(ShoalPalette.CARD)).isEqualTo(ShoalPalette.LIGHT_ANCHORS[1]);
        assertThat(t.get(ShoalPalette.HAIRLINE)).isEqualTo(ShoalPalette.LIGHT_ANCHORS[4]);
        assertThat(t.get(ShoalPalette.OUTLINE)).isEqualTo(ShoalPalette.LIGHT_ANCHORS[6]);
        assertThat(t.get(ShoalPalette.TEXT)).isEqualTo(ShoalPalette.LIGHT_ANCHORS[9]);
    }

    @Test
    public void koi_materialMapping() {
        final ShoalPalette p = ShoalPalette.create(KOI, Surface.HAIRLINE, ShoalPalette.NEUTRAL,
                4, false, true);
        final Map<String, Integer> r = p.roles();
        final Map<String, Integer> t = p.tokens();
        assertThat(r.get("background")).isEqualTo(t.get(ShoalPalette.INK));
        assertThat(r.get("surface_container")).isEqualTo(t.get(ShoalPalette.INK));
        assertThat(r.get("surface_container_low")).isEqualTo(t.get(ShoalPalette.CARD));
        assertThat(r.get("surface_container_high")).isEqualTo(t.get(ShoalPalette.RAISED));
        assertThat(r.get("surface_container_highest")).isEqualTo(t.get(ShoalPalette.TILE));
        assertThat(r.get("outline_variant")).isEqualTo(t.get(ShoalPalette.HAIRLINE));
        assertThat(r.get("primary")).isEqualTo(KOI);
        assertThat(r.get("primary_container")).isEqualTo(t.get(ShoalPalette.ACCENT_FILL));
        assertThat(r.get("secondary_container")).isEqualTo(t.get(ShoalPalette.ACCENT_TINT));
        assertThat(r.get("on_primary_container")).isEqualTo(KOI);
        assertThat(p.legacy("accent1_500", 0xFF123456)).isEqualTo(KOI);
        assertThat(p.legacy("accent1_800", 0xFF123456)).isEqualTo(t.get(ShoalPalette.ON_ACCENT));
        assertThat(p.legacy("accent2_100", 0xFF123456)).isEqualTo(t.get(ShoalPalette.ON_ACCENT));
        assertThat(p.legacy("accent1_100", 0xFF123456)).isEqualTo(KOI);
        assertThat(p.legacy("neutral1_800", 0xFF123456)).isEqualTo(t.get(ShoalPalette.INK));
        assertThat(p.legacy("error_500", 0xFF123456)).isNull();
    }

    @Test
    public void darkAccent_keepsItsDarkShadeDark() {
        // A pink darker than mid-tone: its on-colour is white. accentN_800 doubles as a dark
        // container in the tonal contract (Fossify apps paint dialogs with accent2_800), so it
        // must not follow on_accent to white.
        final int pink = 0xFFC2185B;
        final ShoalPalette p = ShoalPalette.create(pink, Surface.HAIRLINE, ShoalPalette.NEUTRAL,
                4, false, true);
        final Map<String, Integer> t = p.tokens();
        assertThat(t.get(ShoalPalette.ACCENT)).isEqualTo(pink);
        assertThat(t.get(ShoalPalette.ON_ACCENT)).isEqualTo(0xFFFFFFFF);
        assertThat(p.legacy("accent1_800", 0xFF123456)).isEqualTo(t.get(ShoalPalette.INK));
        assertThat(p.legacy("accent2_800", 0xFF123456)).isEqualTo(t.get(ShoalPalette.INK));
        assertThat(p.legacy("accent1_400", 0xFF123456)).isEqualTo(pink);
        // The on-container poles still follow on_accent: their containers are the flat accent.
        assertThat(p.legacy("accent2_100", 0xFF123456)).isEqualTo(0xFFFFFFFF);
    }

    @Test
    public void unreadableAccent_isMovedUntilItShows() {
        // Navy on ink is 1.5:1; a pale yellow on the white card is 1.2:1.
        final int navy = 0xFF1A237E;
        final Map<String, Integer> dark = ShoalPalette.create(navy, Surface.HAIRLINE,
                ShoalPalette.NEUTRAL, 4, false, true).tokens();
        assertThat(dark.get(ShoalPalette.ACCENT)).isNotEqualTo(navy);
        assertThat(ShoalPalette.contrast(dark.get(ShoalPalette.ACCENT),
                dark.get(ShoalPalette.INK))).isAtLeast(2.5);
        assertThat(ShoalPalette.contrast(dark.get(ShoalPalette.ACCENT),
                dark.get(ShoalPalette.CARD))).isAtLeast(2.5);
        final int yellow = 0xFFFFEB3B;
        final Map<String, Integer> light = ShoalPalette.create(yellow, Surface.HAIRLINE,
                ShoalPalette.NEUTRAL, 4, false, false).tokens();
        assertThat(ShoalPalette.contrast(light.get(ShoalPalette.ACCENT),
                light.get(ShoalPalette.CARD))).isAtLeast(2.5);
        // Koi passes untouched in both themes.
        for (boolean isDark : new boolean[] {true, false}) {
            assertThat(ShoalPalette.create(KOI, Surface.HAIRLINE, ShoalPalette.NEUTRAL, 4,
                    false, isDark).tokens().get(ShoalPalette.ACCENT)).isEqualTo(KOI);
        }
    }

    @Test
    public void pitchBlack_pinsInkInDarkOnly() {
        assertThat(ShoalPalette.create(KOI, Surface.HAIRLINE, ShoalPalette.TINTED, 3, true, true)
                .tokens().get(ShoalPalette.INK)).isEqualTo(0xFF000000);
        assertThat(ShoalPalette.create(KOI, Surface.HAIRLINE, ShoalPalette.TINTED, 3, true, false)
                .tokens().get(ShoalPalette.INK)).isNotEqualTo(0xFF000000);
    }

    @Test
    public void levels_collapseUpperRungs() {
        final Map<String, Integer> two = ShoalPalette.create(KOI, Surface.HAIRLINE,
                ShoalPalette.NEUTRAL, 2, false, true).tokens();
        assertThat(two.get(ShoalPalette.RAISED)).isEqualTo(two.get(ShoalPalette.CARD));
        assertThat(two.get(ShoalPalette.TILE)).isEqualTo(two.get(ShoalPalette.CARD));
        final Map<String, Integer> three = ShoalPalette.create(KOI, Surface.HAIRLINE,
                ShoalPalette.NEUTRAL, 3, false, true).tokens();
        assertThat(three.get(ShoalPalette.TILE)).isEqualTo(three.get(ShoalPalette.RAISED));
        assertThat(three.get(ShoalPalette.RAISED)).isNotEqualTo(three.get(ShoalPalette.CARD));
    }

    @Test
    public void tonal_cardIsRaisedAndHairlineIsCard() {
        final Map<String, Integer> t = ShoalPalette.create(ABYSS, Surface.TONAL,
                ShoalPalette.NEUTRAL, 4, false, true).tokens();
        assertThat(t.get(ShoalPalette.CARD)).isEqualTo(0xFF161C20);
        assertThat(t.get(ShoalPalette.HAIRLINE)).isEqualTo(t.get(ShoalPalette.CARD));
    }

    @Test
    public void tinted_followsSeedHueAndKeepsTone() {
        final int seed = 0xFF2DE2E6; // cyan
        final Map<String, Integer> t = ShoalPalette.create(seed, Surface.HAIRLINE,
                ShoalPalette.TINTED, 4, false, true).tokens();
        final Hct raised = Hct.fromInt(t.get(ShoalPalette.RAISED));
        assertThat(Math.abs(raised.getTone() - Hct.fromInt(0xFF161C20).getTone()))
                .isLessThan(1.5);
        final double seedHue = Hct.fromInt(seed).getHue();
        final double d = Math.abs(((raised.getHue() - seedHue + 540.0) % 360.0) - 180.0);
        assertThat(d).isAtMost(35.0);
    }

    @Test
    public void stock_isEmpty() {
        final ShoalPalette p = ShoalPalette.create(KOI, Surface.STOCK, ShoalPalette.TINTED, 3,
                true, true);
        assertThat(p.isStock()).isTrue();
        assertThat(p.roles()).isEmpty();
        assertThat(p.tokens()).isEmpty();
        assertThat(p.legacy("accent1_500", 0xFF123456)).isNull();
        assertThat(p.hash()).isNotEqualTo(0);
    }

    @Test
    public void hash_changesWithInputs() {
        final int a = ShoalPalette.create(KOI, Surface.HAIRLINE, ShoalPalette.TINTED, 3, false,
                true).hash();
        assertThat(a).isEqualTo(ShoalPalette.create(KOI, Surface.HAIRLINE, ShoalPalette.TINTED,
                3, false, true).hash());
        assertThat(a).isNotEqualTo(ShoalPalette.create(KOI, Surface.TONAL, ShoalPalette.TINTED,
                3, false, true).hash());
        assertThat(a).isNotEqualTo(ShoalPalette.create(KOI, Surface.HAIRLINE,
                ShoalPalette.NEUTRAL, 3, false, true).hash());
        assertThat(a).isNotEqualTo(ShoalPalette.create(KOI, Surface.HAIRLINE,
                ShoalPalette.TINTED, 4, false, true).hash());
        assertThat(a).isNotEqualTo(ShoalPalette.create(KOI, Surface.HAIRLINE,
                ShoalPalette.TINTED, 3, true, true).hash());
        assertThat(a).isNotEqualTo(ShoalPalette.create(ABYSS, Surface.HAIRLINE,
                ShoalPalette.TINTED, 3, false, true).hash());
    }

    @Test
    public void contrast_holdsAcrossSeedsHuesAndModes() {
        final List<Integer> seeds = new ArrayList<>();
        for (int s : PRESET_SEEDS) {
            seeds.add(s);
            // The same seed rotated through 12 hues at its own chroma/tone.
            final Hct h = Hct.fromInt(s);
            for (int i = 0; i < 12; i++) {
                seeds.add(Hct.from(i * 30.0, h.getChroma(), h.getTone()).toInt() | 0xFF000000);
            }
        }
        for (int seed : seeds) {
            for (Surface surface : new Surface[] {Surface.HAIRLINE, Surface.TONAL}) {
                for (boolean tinted : new boolean[] {ShoalPalette.TINTED, ShoalPalette.NEUTRAL}) {
                    for (boolean pitchBlack : new boolean[] {false, true}) {
                        for (boolean dark : new boolean[] {true, false}) {
                            for (int levels = ShoalPalette.MIN_LEVELS;
                                    levels <= ShoalPalette.MAX_LEVELS; levels++) {
                                checkContrast(ShoalPalette.create(seed, surface, tinted, levels,
                                        pitchBlack, dark), String.format(
                                        "seed=%08x %s tinted=%b pb=%b dark=%b levels=%d", seed,
                                        surface, tinted, pitchBlack, dark, levels));
                            }
                        }
                    }
                }
            }
        }
    }

    private static void checkContrast(ShoalPalette p, String what) {
        final Map<String, Integer> r = p.roles();
        final Map<String, Integer> t = p.tokens();
        for (String[] pair : ROLE_PAIRS) {
            assertWithMessage(what + " " + pair[0] + "/" + pair[1])
                    .that(ShoalPalette.contrast(r.get(pair[0]), r.get(pair[1])))
                    .isAtLeast(4.5);
        }
        for (String[] pair : TOKEN_PAIRS) {
            assertWithMessage(what + " " + pair[0] + "/" + pair[1])
                    .that(ShoalPalette.contrast(t.get(pair[0]), t.get(pair[1])))
                    .isAtLeast(4.5);
        }
        assertWithMessage(what + " outline/ink")
                .that(ShoalPalette.contrast(t.get(ShoalPalette.OUTLINE), t.get(ShoalPalette.INK)))
                .isAtLeast(3.0);
        for (Map.Entry<String, Integer> e : t.entrySet()) {
            assertWithMessage(what + " " + e.getKey() + " opaque")
                    .that(e.getValue() >>> 24).isEqualTo(0xFF);
        }
    }

    private static void assertClose(int actual, int expected, int tolerance) {
        for (int shift = 0; shift <= 16; shift += 8) {
            final int a = (actual >> shift) & 0xFF;
            final int e = (expected >> shift) & 0xFF;
            assertWithMessage(String.format("%08x vs %08x", actual, expected))
                    .that(Math.abs(a - e)).isAtMost(tolerance);
        }
    }
}
