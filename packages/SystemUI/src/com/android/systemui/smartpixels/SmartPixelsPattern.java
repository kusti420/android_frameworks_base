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

package com.android.systemui.smartpixels;

/**
 * Pure (framework-free) mask logic for Smart Pixels.
 *
 * <p>Smart Pixels reduces OLED burn-in and power draw by permanently blacking out a fraction of
 * the physical pixels. The set of blacked-out pixels is chosen from an 8x8 ordered-dither (Bayer)
 * matrix, which spreads them as evenly as an 8x8 tile allows, and is shifted over time so that,
 * across a full shift cycle, every physical pixel spends the same fraction of time blacked out.
 *
 * <p>Deliberately contains no {@code android.*} imports so the mask maths can be unit-tested on
 * the host.
 */
public final class SmartPixelsPattern {

    /** Edge length of the repeating tile. */
    public static final int GRID = 8;

    /** Number of cells in one tile. */
    public static final int CELLS = GRID * GRID;

    /** Lowest percentage the Settings UI can request. */
    public static final int MIN_PERCENT = 10;

    /** Highest percentage the Settings UI can request. */
    public static final int MAX_PERCENT = 75;

    /** Value used when the setting is missing or unparseable. Mirrors smart_pixels.xml. */
    public static final int DEFAULT_PERCENT = 25;

    /**
     * Standard 8x8 Bayer threshold matrix, indexed {@code [y][x]}. Its 64 entries are a
     * permutation of 0..63, so "cell is dimmed iff {@code BAYER[y][x] < n}" always dims exactly
     * {@code n} cells, spread as uniformly as the tile allows.
     */
    private static final int[][] BAYER = {
            { 0, 32,  8, 40,  2, 34, 10, 42},
            {48, 16, 56, 24, 50, 18, 58, 26},
            {12, 44,  4, 36, 14, 46,  6, 38},
            {60, 28, 52, 20, 62, 30, 54, 22},
            { 3, 35, 11, 43,  1, 33,  9, 41},
            {51, 19, 59, 27, 49, 17, 57, 25},
            {15, 47,  7, 39, 13, 45,  5, 37},
            {63, 31, 55, 23, 61, 29, 53, 21},
    };

    private SmartPixelsPattern() {}

    /**
     * Clamps a raw setting value into the range the UI can produce.
     *
     * @param percent raw value read from {@code Settings.Secure.SMART_PIXEL_FILTER_PERCENT}
     * @return a value in {@link #MIN_PERCENT}..{@link #MAX_PERCENT}
     */
    public static int clampPercent(int percent) {
        if (percent < MIN_PERCENT) return MIN_PERCENT;
        if (percent > MAX_PERCENT) return MAX_PERCENT;
        return percent;
    }

    /**
     * How many of the {@link #CELLS} cells in one tile are blacked out for the given percentage.
     *
     * <p>Rounds to nearest, so the realised percentage is never more than {@code 100/128}% away
     * from the requested one.
     */
    public static int dimmedCells(int percent) {
        final int p = clampPercent(percent);
        return (p * CELLS + 50) / 100;
    }

    /**
     * The mask for one tile at shift step 0, as a row-major {@code boolean[CELLS]} where
     * {@code true} means "this pixel is blacked out".
     */
    public static boolean[] mask(int percent) {
        return maskAtStep(percent, 0);
    }

    /**
     * The mask for one tile at a given shift step, row-major, {@code true} = blacked out.
     *
     * <p>Step {@code s} translates the base pattern by {@code (s % GRID, (s / GRID) % GRID)}, so
     * stepping through {@link #shiftCycleLength()} consecutive steps visits every translation of
     * the tile exactly once.
     */
    public static boolean[] maskAtStep(int percent, int step) {
        final int n = dimmedCells(percent);
        final int dx = Math.floorMod(step, GRID);
        final int dy = Math.floorMod(Math.floorDiv(step, GRID), GRID);
        final boolean[] out = new boolean[CELLS];
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                // Translating the *pattern* by (dx, dy) means cell (x, y) shows the base cell
                // that sits (dx, dy) before it.
                final int sx = Math.floorMod(x - dx, GRID);
                final int sy = Math.floorMod(y - dy, GRID);
                out[y * GRID + x] = BAYER[sy][sx] < n;
            }
        }
        return out;
    }

    /** Number of distinct shift steps before the pattern repeats. */
    public static int shiftCycleLength() {
        return CELLS;
    }
}
