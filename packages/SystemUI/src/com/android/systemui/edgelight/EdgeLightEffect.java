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

/**
 * The animation effects offered by the crDroid Edge light screen
 * ({@code edge_light_animation_values}).
 *
 * <p>An edge light is drawn as a ring of short segments around the screen border. Each effect is
 * just a function from "how far around the border are we" and "how far through the pulse are we"
 * to an opacity, so all six share a single draw path and can be reasoned about -- and tested --
 * without a screen.
 *
 * <p>Contains no {@code android.*} imports so the maths can be unit-tested on the host.
 */
public enum EdgeLightEffect {

    /** Steady light, no animation. */
    NONE("none"),
    /** The whole border fades in and out together. */
    BREATHING("breathing"),
    /** A smooth sine wave travels around the border. */
    WAVE("wave"),
    /** Segments twinkle on and off in a fixed pseudo-random order. */
    SPARKLE("sparkle"),
    /** A hard-edged arc runs around the border. */
    CHASE("chase"),
    /** A bright head with a fading tail runs around the border. */
    COMET("comet");

    /** Fraction of the border lit by {@link #CHASE}. */
    private static final float CHASE_ARC = 0.25f;

    /** How sharply {@link #COMET}'s tail falls off. Larger is shorter. */
    private static final float COMET_FALLOFF = 7f;

    /** Lowest opacity {@link #BREATHING} dims to, so the border never disappears mid-pulse. */
    private static final float BREATHING_FLOOR = 0.15f;

    /** How many times a second {@link #SPARKLE} re-rolls. */
    private static final int SPARKLE_STEPS = 12;

    private final String mValue;

    EdgeLightEffect(String value) {
        mValue = value;
    }

    /** The value crDroidSettings stores in {@code edge_light_animation_effect}. */
    public String settingsValue() {
        return mValue;
    }

    /** Parses a settings value, falling back to {@link #NONE} for anything unrecognised. */
    public static EdgeLightEffect fromSettings(String value) {
        if (value != null) {
            for (EdgeLightEffect effect : values()) {
                if (effect.mValue.equals(value)) {
                    return effect;
                }
            }
        }
        return NONE;
    }

    /**
     * Opacity of the border at a point, as a fraction of the light's full brightness.
     *
     * @param position where around the border, in {@code [0, 1)}; 0 and 1 are the same point
     * @param progress how far through the current pulse, in {@code [0, 1)}
     * @return an opacity in {@code [0, 1]}
     */
    public float alphaAt(float position, float progress) {
        final float p = wrap(position);
        final float t = wrap(progress);
        switch (this) {
            case NONE:
                return 1f;
            case BREATHING:
                // One full fade down and back up per pulse.
                return BREATHING_FLOOR + (1f - BREATHING_FLOOR)
                        * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * t));
            case WAVE:
                return (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * (p - t)));
            case SPARKLE:
                return sparkle(p, t);
            case CHASE:
                return wrap(p - t) < CHASE_ARC ? 1f : 0f;
            case COMET: {
                // Distance travelled *behind* the head, so the tail trails it.
                final float behind = wrap(t - p);
                return (float) Math.exp(-behind * COMET_FALLOFF);
            }
            default:
                return 1f;
        }
    }

    /**
     * Deterministic twinkle: the border is cut into {@link #SPARKLE_STEPS} slices per step and
     * each slice is hashed with the step index, so the pattern is stable frame to frame within a
     * step and unrelated between steps.
     */
    private static float sparkle(float p, float t) {
        final int step = (int) (t * SPARKLE_STEPS);
        final int slice = (int) (p * SPARKLE_STEPS * 4);
        int h = slice * 73_856_093 ^ step * 19_349_663;
        h ^= (h >>> 13);
        h *= 0x5bd1e995;
        h ^= (h >>> 15);
        final float unit = (h >>> 8) / (float) (1 << 24);
        // Two thirds of the slices are lit at any moment, at varying brightness.
        return unit < 0.34f ? 0f : (unit - 0.34f) / 0.66f;
    }

    /** Maps any finite float onto {@code [0, 1)}. */
    private static float wrap(float v) {
        if (!(v == v) || Float.isInfinite(v)) {
            // NaN or infinity: treat as the start of the cycle rather than propagating garbage
            // into the draw path.
            return 0f;
        }
        final float f = v - (float) Math.floor(v);
        // Guard against f == 1f from float rounding when v is a tiny negative number.
        return f >= 1f ? 0f : f;
    }
}
