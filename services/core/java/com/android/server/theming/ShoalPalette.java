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

import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import com.google.ux.material.libmonet.hct.Hct;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * IchthysOS "Shoal" palette: the single place that turns the handful of ichthys.nix theme options
 * (theme.accent, theme.surface, theme.neutral, theme.surfaces, theme.pitchBlack) into colours.
 * <p>
 * This is the ONLY file that may hold Shoal hex anchors. Everything else (SystemUI, Settings,
 * SettingsLib, apps) reads the result as resources: the Material {@code system_*} roles and the
 * {@code ichthys_*} tokens that {@link ThemeOverlayHelper} writes into the {@code dynamic_<user>}
 * fabricated overlay.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>The ramp is a fixed list of rungs (ink, card, raised, tile, hairline, track, outline,
 *       muted, body, text) with a dark and a light anchor set. Each rung keeps its anchor's HCT
 *       tone in every mode, so the brightness steps never change.</li>
 *   <li>{@link #NEUTRAL}: every rung is its anchor verbatim (koi reproduces the design exactly).
 *       {@link #TINTED}: the seed hue, nudged a capped number of degrees toward the cold side of
 *       the wheel, at chroma ~9 (surfaces) and lower for text, at the anchor's tone.</li>
 *   <li>pitchBlack pins ink to #000000 (dark only).</li>
 *   <li>surfaces = N collapses the upper surface rungs: 2 → raised = tile = card, 3 → tile =
 *       raised, 4 and 5 keep all four surface rungs (there are only four; 5 is accepted for the
 *       option's range and behaves like 4).</li>
 *   <li>{@link Surface#TONAL}: card takes the raised rung's colour and the hairline token equals
 *       card (no stroke, the fill carries the shape).</li>
 *   <li>Accent tokens: accent = seed; on_accent = black or white, whichever contrasts more;
 *       accent_tint = 10% seed over ink; accent_fill = 20% seed over card; accent_dim = 8% seed
 *       over card.</li>
 *   <li>Contrast guard: every on-X/X pair is at least 4.5:1 (the on-colour's tone moves away from
 *       its background until it is), and outline is at least 3:1 against ink.</li>
 *   <li>{@link Surface#STOCK}: no roles and no tokens: Monet is emitted untouched and the token
 *       resources keep their defaults, which alias the Material roles.</li>
 * </ul>
 * Pure Java on top of libmonet's {@link Hct}; no Android framework state, so it is unit-tested
 * directly (ShoalPaletteTest).
 *
 * @hide
 */
public final class ShoalPalette {

    /** theme.surface. The ordinal is the {@code ichthys_surface_style} integer resource. */
    public enum Surface {
        STOCK, HAIRLINE, TONAL;

        /** Parses the Settings.Secure ichthys_theme_surface value; unset/unknown = HAIRLINE. */
        public static Surface fromSetting(@Nullable String value) {
            if ("stock".equals(value)) return STOCK;
            if ("tonal".equals(value)) return TONAL;
            return HAIRLINE;
        }
    }

    /** theme.neutral = "neutral": anchors verbatim. */
    public static final boolean NEUTRAL = false;
    /** theme.neutral = "tinted": rungs take the (cold-shifted) seed hue. */
    public static final boolean TINTED = true;

    public static final int MIN_LEVELS = 2;
    public static final int MAX_LEVELS = 5;
    public static final int DEFAULT_LEVELS = 4;

    // ---------------------------------------------------------------------------------------------
    // Token names: resources are "ichthys_<token>_dark" / "ichthys_<token>_light" (colors).

    public static final String INK = "ink";
    public static final String CARD = "card";
    public static final String RAISED = "raised";
    public static final String TILE = "tile";
    public static final String HAIRLINE = "hairline";
    public static final String TRACK = "track";
    public static final String OUTLINE = "outline";
    public static final String TEXT = "text";
    public static final String BODY = "body";
    public static final String MUTED = "muted";
    public static final String ACCENT = "accent";
    public static final String ON_ACCENT = "on_accent";
    public static final String ACCENT_TINT = "accent_tint";
    public static final String ACCENT_FILL = "accent_fill";
    public static final String ACCENT_DIM = "accent_dim";

    /** Every colour token, in resource order. */
    public static final List<String> TOKENS = Collections.unmodifiableList(Arrays.asList(
            INK, CARD, RAISED, TILE, HAIRLINE, TRACK, OUTLINE, TEXT, BODY, MUTED,
            ACCENT, ON_ACCENT, ACCENT_TINT, ACCENT_FILL, ACCENT_DIM));

    // ---------------------------------------------------------------------------------------------
    // Anchors. Rung order: ink, card, raised, tile, hairline, track, outline, muted, body, text.

    private static final int R_INK = 0;
    private static final int R_CARD = 1;
    private static final int R_RAISED = 2;
    private static final int R_TILE = 3;
    private static final int R_HAIRLINE = 4;
    private static final int R_TRACK = 5;
    private static final int R_OUTLINE = 6;
    private static final int R_MUTED = 7;
    private static final int R_BODY = 8;
    private static final int R_TEXT = 9;
    private static final int RUNGS = 10;

    /** Koi dark ramp (the design canvas). */
    @VisibleForTesting
    static final int[] DARK_ANCHORS = {
            0xFF07090B, // ink: ground
            0xFF0C1013, // card
            0xFF161C20, // raised
            0xFF172125, // tile
            0xFF20282D, // hairline
            0xFF2A3136, // track
            0xFF3A4246, // outline (raised to 3:1 against ink by the guard)
            0xFF8B938F, // muted
            0xFFC9CECA, // body
            0xFFF2F4F1, // text
    };

    /**
     * Light ramp: off-white ground, white cards (lighter = nearer in light), then progressively
     * darker containers, matching the light values the nav bar and Settings Shoal styles drew.
     */
    @VisibleForTesting
    static final int[] LIGHT_ANCHORS = {
            0xFFF7F8F6, // ink: ground
            0xFFFFFFFF, // card
            0xFFEEF1EE, // raised
            0xFFE3E7E4, // tile
            0xFFD5D9D6, // hairline
            0xFFC9CFCB, // track
            0xFF858C89, // outline (3:1 against the ground)
            0xFF5E6663, // muted
            0xFF353D40, // body
            0xFF1B2226, // text
    };

    /** TINTED: share of {@link #COLD_SHIFT_MAX} each rung is rotated (darkest coolest). */
    private static final double[] COLD_FACTOR = {1.0, 0.9, 0.75, 0.6, 0.8, 0.7, 0.7, 0.5, 0.3, 0.2};
    /** TINTED: chroma per rung. 8-10 reads "tinted dark neutral"; text rungs stay near-neutral. */
    private static final double[] TINT_CHROMA = {9, 9, 9, 9, 10, 10, 10, 8, 5, 3};

    private static final double COLD_HUE_ANCHOR = 265.0;
    private static final double COLD_SHIFT_MAX = 30.0;

    private static final double TEXT_CONTRAST = 4.5;
    private static final double OUTLINE_CONTRAST = 3.0;

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    // ---------------------------------------------------------------------------------------------

    private final Surface mSurface;
    private final int mLevels;
    private final boolean mDark;
    private final Map<String, Integer> mTokens;
    private final Map<String, Integer> mRoles;
    /** Hue/chroma source for the legacy neutral retint. */
    private final double mNeutralHue;
    private final double mNeutralChroma;
    private final int mHash;

    private ShoalPalette(int seed, Surface surface, boolean tinted, int levels, boolean pitchBlack,
            boolean dark) {
        mSurface = surface;
        mLevels = clampLevels(levels);
        mDark = dark;
        if (surface == Surface.STOCK) {
            mTokens = Collections.emptyMap();
            mRoles = Collections.emptyMap();
            mNeutralHue = 0;
            mNeutralChroma = 0;
            mHash = nonZero(31 * (surface.ordinal() + 1) + mLevels);
            return;
        }
        final int accent = BLACK | (seed & 0x00FFFFFF);
        final double seedHue = Hct.fromInt(accent).getHue();
        final int[] anchors = dark ? DARK_ANCHORS : LIGHT_ANCHORS;

        // 1. Rungs: anchors (NEUTRAL) or the cold-shifted seed hue at the anchor tone (TINTED).
        final int[] r = new int[RUNGS];
        for (int i = 0; i < RUNGS; i++) {
            if (tinted) {
                final double tone = Hct.fromInt(anchors[i]).getTone();
                r[i] = opaque(Hct.from(coldShiftedHue(seedHue, COLD_FACTOR[i]), TINT_CHROMA[i],
                        tone).toInt());
            } else {
                r[i] = anchors[i];
            }
        }
        if (tinted) {
            mNeutralHue = coldShiftedHue(seedHue, 0.75);
            mNeutralChroma = TINT_CHROMA[R_CARD];
        } else {
            final Hct card = Hct.fromInt(anchors[R_CARD]);
            mNeutralHue = card.getHue();
            mNeutralChroma = card.getChroma();
        }

        // 2. Pitch black (dark only).
        if (dark && pitchBlack) {
            r[R_INK] = BLACK;
        }

        // 3. Tonal: the card is a fill (the raised rung). Before collapsing, so tonal with two
        //    levels still gets a visible fill.
        if (surface == Surface.TONAL) {
            r[R_CARD] = r[R_RAISED];
        }

        // 4. Collapse upper surface rungs.
        if (mLevels <= 2) {
            r[R_RAISED] = r[R_CARD];
            r[R_TILE] = r[R_CARD];
        } else if (mLevels == 3) {
            r[R_TILE] = r[R_RAISED];
        }

        // 5. Tonal: no stroke.
        if (surface == Surface.TONAL) {
            r[R_HAIRLINE] = r[R_CARD];
        }

        // 6. Contrast guard for the ramp itself.
        final int[] surfaces = {r[R_INK], r[R_CARD], r[R_RAISED], r[R_TILE]};
        r[R_TEXT] = ensureContrast(r[R_TEXT], surfaces, TEXT_CONTRAST);
        r[R_BODY] = ensureContrast(r[R_BODY], surfaces, TEXT_CONTRAST);
        r[R_MUTED] = ensureContrast(r[R_MUTED], surfaces, TEXT_CONTRAST);
        r[R_OUTLINE] = ensureContrast(r[R_OUTLINE], new int[] {r[R_INK]}, OUTLINE_CONTRAST);

        // 7. Accent tokens.
        final int onAccent = onColor(accent);
        final int accentTint = blend(accent, r[R_INK], 0.10);
        final int accentFill = blend(accent, r[R_CARD], 0.20);
        final int accentDim = blend(accent, r[R_CARD], 0.08);

        final LinkedHashMap<String, Integer> t = new LinkedHashMap<>();
        t.put(INK, r[R_INK]);
        t.put(CARD, r[R_CARD]);
        t.put(RAISED, r[R_RAISED]);
        t.put(TILE, r[R_TILE]);
        t.put(HAIRLINE, r[R_HAIRLINE]);
        t.put(TRACK, r[R_TRACK]);
        t.put(OUTLINE, r[R_OUTLINE]);
        t.put(TEXT, r[R_TEXT]);
        t.put(BODY, r[R_BODY]);
        t.put(MUTED, r[R_MUTED]);
        t.put(ACCENT, accent);
        t.put(ON_ACCENT, onAccent);
        t.put(ACCENT_TINT, accentTint);
        t.put(ACCENT_FILL, accentFill);
        t.put(ACCENT_DIM, accentDim);
        mTokens = Collections.unmodifiableMap(t);
        mRoles = Collections.unmodifiableMap(materialRoles(t, dark));
        mHash = computeHash();
    }

    /**
     * Builds the palette for one theme (dark or light).
     *
     * @param seed       the accent seed (alpha ignored)
     * @param surface    theme.surface
     * @param tinted     {@link #TINTED} or {@link #NEUTRAL}
     * @param levels     theme.surfaces (clamped to 2..5)
     * @param pitchBlack theme.pitchBlack (dark only)
     * @param dark       whether this is the dark palette
     */
    public static ShoalPalette create(int seed, Surface surface, boolean tinted, int levels,
            boolean pitchBlack, boolean dark) {
        return new ShoalPalette(seed, surface, tinted, levels, pitchBlack, dark);
    }

    public Surface surface() {
        return mSurface;
    }

    public boolean isStock() {
        return mSurface == Surface.STOCK;
    }

    /** The {@code ichthys_surface_levels} value (2..5). */
    public int levels() {
        return mLevels;
    }

    public boolean isDark() {
        return mDark;
    }

    /** Token name ({@link #TOKENS}) to opaque ARGB; empty for STOCK. */
    public Map<String, Integer> tokens() {
        return mTokens;
    }

    /**
     * Material role name (as in {@code system_<role>_dark}, e.g. "surface_container_low") to
     * opaque ARGB; empty for STOCK. Roles not in the map keep their Monet value.
     */
    public Map<String, Integer> roles() {
        return mRoles;
    }

    /** Stable fingerprint of everything this palette emits (never 0). */
    public int hash() {
        return mHash;
    }

    /** The {@code ichthys_palette_hash} of a dark/light pair (never 0). */
    public static int combinedHash(ShoalPalette dark, ShoalPalette light) {
        return nonZero(31 * dark.hash() + light.hash());
    }

    /**
     * Value for a legacy tonal token ({@code accentN_*}, {@code neutralN_*}) given its Monet value,
     * or {@code null} to keep Monet. Only the dark theme's legacy tokens are rewritten (as before):
     * <ul>
     *   <li>{@code accentN_100..700}: the flat accent, so every legacy accent use is one colour.</li>
     *   <li>{@code accentN_800} and {@code accent2_100}/{@code accent3_100}: the dark on-accent and
     *       on-container poles. Their containers are the flat accent, so they take on_accent
     *       (the same contrast rule as the Material on_* roles). {@code accent1_100} stays the
     *       accent: it is {@code ?android:attr/colorAccent} in dark.</li>
     *   <li>{@code neutralN_800..1000}: ink ({@code neutral1_800} must equal system_surface since
     *       SettingsLib paints its content area from it); {@code neutralN_700}: raised.</li>
     *   <li>{@code neutralN_0..600}: kept at their tone, moved to the ramp's hue/chroma, so
     *       androidx Material (which re-lightens neutral1_600) derives matching surfaces.</li>
     * </ul>
     */
    @Nullable
    public Integer legacy(String name, int monetArgb) {
        if (isStock() || !mDark) {
            return null;
        }
        final boolean accent = name.startsWith("accent1_") || name.startsWith("accent2_")
                || name.startsWith("accent3_");
        final boolean neutral = name.startsWith("neutral1_") || name.startsWith("neutral2_");
        if (!accent && !neutral) {
            return null;
        }
        final int shade;
        try {
            shade = Integer.parseInt(name.substring(name.indexOf('_') + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (accent) {
            if (shade == 800 || (shade == 100 && !name.startsWith("accent1_"))) {
                return mTokens.get(ON_ACCENT);
            }
            if (shade >= 100 && shade <= 700) {
                return mTokens.get(ACCENT);
            }
            return null;
        }
        if (shade >= 800) {
            return mTokens.get(INK);
        }
        if (shade == 700) {
            return mTokens.get(RAISED);
        }
        final double tone = Hct.fromInt(monetArgb).getTone();
        final int c = Hct.from(mNeutralHue, mNeutralChroma, tone).toInt();
        return (monetArgb & 0xFF000000) | (c & 0x00FFFFFF);
    }

    // ---------------------------------------------------------------------------------------------
    // Material role mapping

    private static Map<String, Integer> materialRoles(Map<String, Integer> t, boolean dark) {
        final LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        final int ink = t.get(INK);
        final int card = t.get(CARD);
        final int raised = t.get(RAISED);
        final int tile = t.get(TILE);
        final int text = t.get(TEXT);
        final int accent = t.get(ACCENT);
        final int onAccent = t.get(ON_ACCENT);
        final int fill = t.get(ACCENT_FILL);
        final int tint = t.get(ACCENT_TINT);

        for (String s : new String[] {"background", "surface", "surface_dim",
                "surface_container_lowest", "surface_container"}) {
            m.put(s, ink);
        }
        m.put("surface_container_low", card);
        m.put("surface_bright", card);
        m.put("surface_container_high", raised);
        m.put("surface_variant", raised);
        m.put("surface_container_highest", tile);
        m.put("outline_variant", t.get(HAIRLINE));
        m.put("outline", t.get(OUTLINE));
        m.put("on_surface", text);
        m.put("on_background", text);
        m.put("inverse_surface", text);
        m.put("on_surface_variant", t.get(MUTED));
        m.put("inverse_on_surface", ink);

        for (String a : new String[] {"primary", "secondary", "tertiary"}) {
            m.put(a, accent);
            m.put(a + "_dim", accent);
            m.put(a + "_fixed", accent);
            m.put(a + "_fixed_dim", accent);
            m.put("on_" + a, onAccent);
            m.put("on_" + a + "_fixed", onAccent);
            m.put("on_" + a + "_fixed_variant", onAccent);
        }
        m.put("inverse_primary", accent);

        final int onFill = ensureContrast(
                contrast(accent, fill) >= TEXT_CONTRAST ? accent : text,
                new int[] {fill}, TEXT_CONTRAST);
        m.put("primary_container", fill);
        m.put("tertiary_container", fill);
        m.put("on_primary_container", onFill);
        m.put("on_tertiary_container", onFill);
        m.put("secondary_container", tint);
        m.put("on_secondary_container", ensureContrast(text, new int[] {tint}, TEXT_CONTRAST));

        if (dark) {
            // Translucent tinted Monet overlays (shade panel, QS tile / notification backgrounds)
            // would bleed the Monet tint over the ramp: make them opaque rungs.
            m.put("surface_effect_0", ink);
            m.put("surface_effect_0_fallback", ink);
            m.put("surface_effect_1", card);
            m.put("surface_effect_2", card);
            m.put("surface_effect_3", card);
        }
        return m;
    }

    private int computeHash() {
        final TreeMap<String, Integer> all = new TreeMap<>();
        for (Map.Entry<String, Integer> e : mTokens.entrySet()) {
            all.put("t:" + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Integer> e : mRoles.entrySet()) {
            all.put("r:" + e.getKey(), e.getValue());
        }
        int h = 17;
        h = 31 * h + mSurface.ordinal();
        h = 31 * h + mLevels;
        h = 31 * h + (mDark ? 1 : 0);
        h = 31 * h + Double.hashCode(mNeutralHue);
        h = 31 * h + Double.hashCode(mNeutralChroma);
        for (Map.Entry<String, Integer> e : all.entrySet()) {
            h = 31 * h + e.getKey().hashCode();
            h = 31 * h + e.getValue();
        }
        return nonZero(h);
    }

    // ---------------------------------------------------------------------------------------------
    // Colour maths

    static int clampLevels(int levels) {
        return Math.max(MIN_LEVELS, Math.min(MAX_LEVELS, levels));
    }

    private static int nonZero(int h) {
        return h == 0 ? 1 : h;
    }

    private static int opaque(int argb) {
        return BLACK | (argb & 0x00FFFFFF);
    }

    /**
     * Rotates {@code seedHue} toward the cold anchor along the shorter arc, capped at
     * {@link #COLD_SHIFT_MAX} degrees and scaled by {@code factor}. The cap keeps each surface in
     * its seed's family (a fuchsia seed leans violet, a green one teal) instead of converging on
     * one blue for every accent.
     */
    static double coldShiftedHue(double seedHue, double factor) {
        final double delta = ((COLD_HUE_ANCHOR - seedHue + 540.0) % 360.0) - 180.0;
        final double capped = Math.max(-COLD_SHIFT_MAX, Math.min(COLD_SHIFT_MAX, delta));
        final double result = (seedHue + capped * factor) % 360.0;
        return result < 0 ? result + 360.0 : result;
    }

    /** {@code amount} of {@code top} composited over opaque {@code base}, in sRGB. */
    static int blend(int top, int base, double amount) {
        final int r = mix((top >> 16) & 0xFF, (base >> 16) & 0xFF, amount);
        final int g = mix((top >> 8) & 0xFF, (base >> 8) & 0xFF, amount);
        final int b = mix(top & 0xFF, base & 0xFF, amount);
        return BLACK | (r << 16) | (g << 8) | b;
    }

    private static int mix(int top, int base, double amount) {
        return (int) Math.round(top * amount + base * (1.0 - amount));
    }

    /** Black or white, whichever contrasts more with {@code bg} (always at least ~4.58:1). */
    static int onColor(int bg) {
        return contrast(BLACK, bg) >= contrast(WHITE, bg) ? BLACK : WHITE;
    }

    /** WCAG 2.x contrast ratio of two opaque colours, in [1, 21]. */
    public static double contrast(int a, int b) {
        final double la = relativeLuminance(a);
        final double lb = relativeLuminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG 2.x relative luminance of an opaque colour, in [0, 1]. */
    static double relativeLuminance(int argb) {
        return 0.2126 * linearize((argb >> 16) & 0xFF)
                + 0.7152 * linearize((argb >> 8) & 0xFF)
                + 0.0722 * linearize(argb & 0xFF);
    }

    private static double linearize(int channel) {
        final double s = channel / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static double minContrast(int fg, int[] bgs) {
        double min = Double.MAX_VALUE;
        for (int bg : bgs) {
            min = Math.min(min, contrast(fg, bg));
        }
        return min;
    }

    /**
     * Returns {@code fg}, or {@code fg} with its HCT tone moved away from {@code bgs} (lighter on
     * dark backgrounds, darker on light ones) until it reaches {@code ratio} against every one of
     * them. Hue and chroma are kept as far as the tone allows; falls back to white/black.
     */
    static int ensureContrast(int fg, int[] bgs, double ratio) {
        if (minContrast(fg, bgs) >= ratio) {
            return fg;
        }
        double lum = 0;
        for (int bg : bgs) {
            lum += relativeLuminance(bg);
        }
        final boolean lighten = contrast(WHITE, bgs[0]) >= contrast(BLACK, bgs[0])
                && lum / bgs.length < 0.5;
        final Hct hct = Hct.fromInt(fg);
        double tone = hct.getTone();
        while (lighten ? tone < 100.0 : tone > 0.0) {
            tone = lighten ? Math.min(100.0, tone + 1.0) : Math.max(0.0, tone - 1.0);
            final int c = opaque(Hct.from(hct.getHue(), hct.getChroma(), tone).toInt());
            if (minContrast(c, bgs) >= ratio) {
                return c;
            }
        }
        return lighten ? WHITE : BLACK;
    }

}
