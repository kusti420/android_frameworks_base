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
 * Where the edge light takes its colour from ({@code edge_light_color_mode_values}).
 *
 * <p>Resolving the actual colour needs the theme, the notification and the wallpaper, so that part
 * lives in the controller. What lives here is the parsing and the parts that are pure maths: the
 * rainbow sweep, and the fallback chain that guarantees a visible colour even when the preferred
 * source has nothing to offer.
 *
 * <p>Contains no {@code android.*} imports so it can be unit-tested on the host.
 */
public enum EdgeLightColorMode {

    /** The system accent colour. */
    ACCENT("accent"),
    /** The colour of the notification that triggered the pulse. */
    NOTIFICATION("notification"),
    /** The dominant wallpaper colour. */
    WALLPAPER("wallpaper"),
    /** A hue sweep around the border. */
    RAINBOW("rainbow"),
    /** The colour picked in {@code edge_light_custom_color}. */
    CUSTOM("custom");

    /** Colour used when every source fails, so the light is never invisible. */
    public static final int FALLBACK_COLOR = 0xFFFFFFFF;

    private final String mValue;

    EdgeLightColorMode(String value) {
        mValue = value;
    }

    /** The value crDroidSettings stores in {@code edge_light_color_mode}. */
    public String settingsValue() {
        return mValue;
    }

    /** Parses a settings value, falling back to {@link #ACCENT} (the XML default). */
    public static EdgeLightColorMode fromSettings(String value) {
        if (value != null) {
            for (EdgeLightColorMode mode : values()) {
                if (mode.mValue.equals(value)) {
                    return mode;
                }
            }
        }
        return ACCENT;
    }

    /** True when the colour varies along the border and the view must shade per segment. */
    public boolean isPerSegment() {
        return this == RAINBOW;
    }

    /**
     * Forces a colour to be fully opaque and non-black.
     *
     * <p>Notification and wallpaper colours are frequently 0 (unset) or transparent, and a
     * transparent or black edge light on an off screen is indistinguishable from a broken feature.
     *
     * @param color the candidate colour, possibly 0 or partly transparent
     * @return {@code color} at full alpha, or {@link #FALLBACK_COLOR} if it carries no visible hue
     */
    public static int sanitize(int color) {
        final int opaque = color | 0xFF000000;
        return (opaque & 0x00FFFFFF) == 0 ? FALLBACK_COLOR : opaque;
    }

    /**
     * Parses the value crDroidSettings stores for a colour preference.
     *
     * <p>{@code ColorPickerPreference.onColorChanged} calls {@code persistInt(color)}, so the
     * stored value is normally a <em>decimal, possibly negative</em> ARGB integer -- not the
     * {@code #AARRGGBB} string the preference XML uses for its {@code defaultValue}. Both forms
     * are accepted here, matching {@code BatteryBarView.parseColor} elsewhere in this tree.
     *
     * @param value the raw stored string, or {@code null} when the setting has never been written
     * @param fallback returned when {@code value} is missing or unparseable
     */
    public static int parseSettingsColor(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            // Not a persisted int; fall through to the hex spellings.
        }
        String hex = trimmed;
        if (hex.charAt(0) == '#') {
            hex = hex.substring(1);
        }
        if (hex.length() != 6 && hex.length() != 8) {
            return fallback;
        }
        for (int i = 0; i < hex.length(); i++) {
            final char c = hex.charAt(i);
            final boolean isHexDigit = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!isHexDigit) {
                return fallback;
            }
        }
        final long parsed = Long.parseLong(hex, 16);
        // A six-digit spelling carries no alpha; make it opaque rather than fully transparent.
        return hex.length() == 6 ? (int) (parsed | 0xFF000000L) : (int) parsed;
    }

    /**
     * The rainbow colour at a point around the border.
     *
     * @param position where around the border, in {@code [0, 1)}
     * @param progress how far through the pulse, in {@code [0, 1)}; rotates the sweep
     * @return an opaque ARGB colour
     */
    public static int rainbowAt(float position, float progress) {
        return hsvToRgb(wrap(position + progress) * 360f);
    }

    /** Fully saturated, full value HSV to opaque ARGB. Avoids pulling in {@code android.graphics}. */
    private static int hsvToRgb(float hueDegrees) {
        final float h = hueDegrees / 60f;
        final int sector = ((int) Math.floor(h)) % 6;
        final float f = h - (float) Math.floor(h);
        final int max = 255;
        final int min = 0;
        final int rise = Math.round(255 * f);
        final int fall = 255 - rise;
        switch (sector < 0 ? sector + 6 : sector) {
            case 0:  return argb(max, rise, min);
            case 1:  return argb(fall, max, min);
            case 2:  return argb(min, max, rise);
            case 3:  return argb(min, fall, max);
            case 4:  return argb(rise, min, max);
            default: return argb(max, min, fall);
        }
    }

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static float wrap(float v) {
        if (!(v == v) || Float.isInfinite(v)) {
            return 0f;
        }
        final float f = v - (float) Math.floor(v);
        return f >= 1f ? 0f : f;
    }
}
