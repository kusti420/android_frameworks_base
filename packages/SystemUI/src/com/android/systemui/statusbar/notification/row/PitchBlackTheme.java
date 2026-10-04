/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.provider.Settings;

/** Helpers for the Custom Options pitch-black + vivid-accent theme in notifications. */
final class PitchBlackTheme {

    private PitchBlackTheme() {}

    /**
     * IchthysOS: whether the Abyss palette is selected in the Theme Engine
     * (Settings.Secure {@code theme_engine_style_mode}: 0 = Monet, 1 = Abyss).
     * <p>
     * This used to read {@code custom_pitch_black}, which conflated "use this ROM's palette" with
     * "make the background pure black". The palette is now the thing being asked about; pitch black
     * is only a modifier on top of it and is handled in the surface ladder itself.
     */
    static boolean isEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                "theme_engine_style_mode", 0) == 1;
    }

    /**
     * IchthysOS "Shoal": theme.surface = hairline (Settings.Secure ichthys_theme_surface) draws a
     * 1 dp outline around each notification card instead of relying on a tonal fill. Unset keeps
     * the stock look.
     */
    static boolean hairlineEnabled(Context context) {
        // Shared rule (hairline set, or unset with the rest of the Shoal theme on).
        return com.android.systemui.shade.ui.ShoalShade.isActive(context);
    }

    /**
     * Whether to draw the accent-colored border around each notification row.
     * <p>
     * IchthysOS: this is an Abyss-palette flourish, so it follows the Abyss palette and nothing
     * else. It used to have its own switch as well (Settings.System "notification_accent_border"),
     * but no screen or option ever wrote that key, so the read is gone.
     */
    static boolean borderEnabled(Context context) {
        return isEnabled(context);
    }

    /**
     * The exact accent seed the user explicitly picked (vivid), if any; otherwise the live system
     * accent (system_accent1_500), which tracks wallpaper-based Monet theming. Falls back to
     * fuchsia only if even that cannot be resolved.
     */
    static int accentColor(Context context) {
        try {
            String json =
                    Settings.Secure.getString(
                            context.getContentResolver(),
                            "theme_customization_overlay_packages");
            if (json != null) {
                int i = json.indexOf("system_palette");
                if (i >= 0) {
                    int q = json.indexOf('"', json.indexOf(':', i)) + 1;
                    int e = json.indexOf('"', q);
                    int c = (int) Long.parseLong(json.substring(q, e), 16);
                    // The seed is stored as RRGGBB (no alpha), so parseLong yields 0x00RRGGBB —
                    // alpha 0 = fully transparent (this is why the border drew in invisible ink).
                    // Force it opaque when no alpha byte was supplied.
                    if ((c >>> 24) == 0) {
                        c |= 0xFF000000;
                    }
                    return c;
                }
            }
        } catch (Exception ignored) {
        }
        // No explicit seed (default / wallpaper-based theming): use the real system accent so the
        // border matches the user's accent instead of a hardcoded color.
        try {
            return context.getColor(android.R.color.system_accent1_500);
        } catch (Exception ignored) {
        }
        return 0xFFFF0080;
    }
}
