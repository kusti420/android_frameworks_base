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

package com.android.systemui.brightness;

/**
 * Corner geometry for the crDroid "Brightness slider style" setting
 * ({@code qs_brightness_slider_shape}, values from {@code qs_brightness_slider_shape_values}).
 *
 * <p>Kept free of Compose and {@code android.*} types so the mapping can be unit-tested on the
 * host; callers convert the returned dp value into whatever unit they need.
 */
public final class QsSliderShape {

    /** Leave the slider as SystemUI draws it. */
    public static final int DEFAULT = 0;
    /** Fully rounded ends. */
    public static final int CIRCLE = 1;
    /** Slightly rounded corners. */
    public static final int ROUNDED_SQUARE = 2;
    /** Hard corners. */
    public static final int SQUARE = 3;

    /**
     * The corner radius SystemUI uses when the setting is untouched.
     *
     * <p>On the stock track this clamps to half the height, i.e. a pill -- so {@link #DEFAULT} and
     * {@link #CIRCLE} deliberately look the same on a slider that is already fully rounded. The
     * distinction only shows up on a taller track.
     */
    public static final float DEFAULT_CORNER_DP = 12f;

    /**
     * {@link #ROUNDED_SQUARE}'s radius as a fraction of the track height.
     *
     * <p>A fixed dp value does not work here. SystemUI's brightness track is only about 16dp tall,
     * and a rounded rectangle cannot have a radius above half its height, so any constant at or
     * above 8dp collapses into the same pill as {@link #CIRCLE} and the option stops meaning
     * anything. A fraction keeps the four choices visually distinct at any track height.
     */
    public static final float ROUNDED_SQUARE_FRACTION = 0.25f;

    private QsSliderShape() {}

    /**
     * Maps a stored setting value onto a known shape, falling back to {@link #DEFAULT} for
     * anything unrecognised so a stale or corrupt value cannot deform the slider.
     */
    public static int sanitize(int shape) {
        switch (shape) {
            case CIRCLE:
            case ROUNDED_SQUARE:
            case SQUARE:
                return shape;
            case DEFAULT:
            default:
                return DEFAULT;
        }
    }

    /**
     * The corner radius to draw the slider track with.
     *
     * @param shape raw value of {@code qs_brightness_slider_shape}
     * @param trackHeightDp height of the track; {@link #CIRCLE} rounds to half of it
     * @return a corner radius in dp, never negative and never more than half the track height
     */
    public static float cornerDp(int shape, float trackHeightDp) {
        // A corner radius above half the height is meaningless -- the rounding is already a
        // semicircle at that point -- and a negative height would invert the clamp.
        final float height = trackHeightDp > 0f ? trackHeightDp : 0f;
        final float maxCorner = height / 2f;
        final float requested;
        switch (sanitize(shape)) {
            case CIRCLE:
                requested = maxCorner;
                break;
            case ROUNDED_SQUARE:
                requested = height * ROUNDED_SQUARE_FRACTION;
                break;
            case SQUARE:
                requested = 0f;
                break;
            case DEFAULT:
            default:
                requested = DEFAULT_CORNER_DP;
                break;
        }
        if (requested < 0f) return 0f;
        return requested > maxCorner ? maxCorner : requested;
    }
}
