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

    static boolean isEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), "custom_pitch_black", 0) == 1;
    }

    /**
     * Whether to draw the accent-colored border around each notification row. Independent of the
     * pitch-black surface theme (Settings.System "notification_accent_border", default on) so the
     * border shows regardless of whether pitch black is enabled.
     */
    static boolean borderEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(),
                "notification_accent_border", 1) == 1;
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
