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

package com.android.systemui.biometrics;

import android.content.Context;
import android.graphics.Color;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.systemui.statusbar.phone.IchthysZigzag;

/**
 * IchthysOS: keys and defaults of the fingerprint (UDFPS) customisation options. All keys live in
 * Settings.System for the current user. The crDroid Settings "Fingerprint effects" page writes
 * them, and they are read when an effect starts, so changes apply on the next touch.
 */
public final class IchthysUdfpsSettings {

    private IchthysUdfpsSettings() {}

    // ---- Press animation (played around the sensor while the finger is down) ----
    /** -1 none, 0 crDroid animation pack (udfps_anim_style), 1..8 built-in effects. */
    public static final String ANIM_EFFECT = "ichthys_udfps_anim_effect";
    public static final int ANIM_EFFECT_DEFAULT = 0;
    public static final int EFFECT_NONE = -1;
    public static final int EFFECT_PACK = 0;
    public static final int EFFECT_RIPPLE = 1;
    public static final int EFFECT_RADAR = 2;
    public static final int EFFECT_PARTICLES = 3;
    public static final int EFFECT_SHOAL = 4;
    public static final int EFFECT_PULSE = 5;
    public static final int EFFECT_AURORA = 6;
    public static final int EFFECT_SPINNER = 7;
    public static final int EFFECT_SCANLINE = 8;
    /** Animation speed in percent (25..300). */
    public static final String ANIM_SPEED = "ichthys_udfps_anim_speed";
    public static final int ANIM_SPEED_DEFAULT = 100;
    /** 0 accent, 1 white, 2 custom, 3 rainbow. */
    public static final String ANIM_COLOR_MODE = "ichthys_udfps_anim_color_mode";
    public static final int ANIM_COLOR_MODE_DEFAULT = 0;
    /** Custom colour (mode 2); the Shoal accent while never picked. */
    public static final String ANIM_COLOR = "ichthys_udfps_anim_color";
    /** Effect size in percent of the default (50..250). */
    public static final String ANIM_SCALE = "ichthys_udfps_anim_scale";
    public static final int ANIM_SCALE_DEFAULT = 100;
    /** Fine position offsets in dp (-40..40). */
    public static final String ANIM_OFFSET_X = "ichthys_udfps_anim_offset_x";
    public static final String ANIM_OFFSET_Y = "ichthys_udfps_anim_offset_y";
    /** Also play in app fingerprint prompts (BiometricPrompt). */
    public static final String ANIM_IN_PROMPT = "ichthys_udfps_anim_bp";
    public static final int ANIM_IN_PROMPT_DEFAULT = 1;
    /** Also play while enrolling a fingerprint. */
    public static final String ANIM_IN_ENROLL = "ichthys_udfps_anim_enroll";
    public static final int ANIM_IN_ENROLL_DEFAULT = 0;

    // ---- Pressed icon (drawn around the illumination disc while pressing) ----
    /** 0 none, 1 solid disc, 2 ring, 3 glow. */
    public static final String PRESSED_STYLE = "ichthys_udfps_pressed_style";
    public static final int PRESSED_STYLE_DEFAULT = 0;
    /** 0 accent, 1 white, 2 custom. */
    public static final String PRESSED_COLOR_MODE = "ichthys_udfps_pressed_color_mode";
    public static final int PRESSED_COLOR_MODE_DEFAULT = 0;
    /** Custom colour (mode 2); the Shoal accent while never picked. */
    public static final String PRESSED_COLOR = "ichthys_udfps_pressed_color";
    /** Size in percent of the sensor radius (100..200). */
    public static final String PRESSED_SIZE = "ichthys_udfps_pressed_size";
    public static final int PRESSED_SIZE_DEFAULT = 120;

    // ---- Result effects (lock screen) ----
    /** 0 none, 1 ring fill, 2 check mark, 3 burst. */
    public static final String SUCCESS_EFFECT = "ichthys_udfps_success_effect";
    public static final int SUCCESS_EFFECT_DEFAULT = 1;
    /** 0 none, 1 shake, 2 red flash. */
    public static final String FAIL_EFFECT = "ichthys_udfps_fail_effect";
    public static final int FAIL_EFFECT_DEFAULT = 2;

    // ---- Idle icon (lock screen / AOD) ----
    /** 0 system (or crDroid icon pack), 1..8 built-in styles. */
    public static final String ICON_STYLE = "ichthys_udfps_icon_style";
    public static final int ICON_STYLE_DEFAULT = 0;
    /** 0 follow theme, 1 accent, 2 custom. */
    public static final String ICON_COLOR_MODE = "ichthys_udfps_icon_color_mode";
    public static final int ICON_COLOR_MODE_DEFAULT = 0;
    public static final String ICON_COLOR = "ichthys_udfps_icon_color";
    public static final int ICON_COLOR_DEFAULT = 0xFFFFFFFF;
    /** Opacity in percent (10..100). */
    public static final String ICON_OPACITY = "ichthys_udfps_icon_opacity";
    public static final int ICON_OPACITY_DEFAULT = 100;
    /** Use the chosen style on AOD too (else the stock AOD fingerprint). */
    public static final String ICON_ON_AOD = "ichthys_udfps_icon_aod";
    public static final int ICON_ON_AOD_DEFAULT = 1;

    // ---- Haptics ----
    /** crDroid switches (existing keys), now honoured. */
    public static final String HAPTIC_SUCCESS_ENABLED = "fp_success_vibrate";
    public static final String HAPTIC_ERROR_ENABLED = "fp_error_vibrate";
    /** 0 system default, 1 tick, 2 double click, 3 heavy click, 4 long. */
    public static final String HAPTIC_SUCCESS = "ichthys_udfps_haptic_success";
    /** 0 system default, 1 buzz, 2 double click, 3 heavy click, 4 long buzz. */
    public static final String HAPTIC_ERROR = "ichthys_udfps_haptic_error";

    public static int get(Context context, String key, int def) {
        return Settings.System.getIntForUser(context.getContentResolver(), key, def,
                UserHandle.USER_CURRENT);
    }

    /** The Shoal accent, the one the status bar and the notification dot draw with. */
    public static int accent(Context context) {
        return IchthysZigzag.accent(context);
    }

    /**
     * A stored colour (the picker persists an ARGB int), or the Shoal accent while the key was
     * never written, so an untouched "custom" colour follows the theme instead of a fixed hue.
     */
    public static int getColorOrAccent(Context context, String key) {
        final String stored = Settings.System.getStringForUser(context.getContentResolver(), key,
                UserHandle.USER_CURRENT);
        if (stored != null) {
            try {
                return Integer.parseInt(stored.trim());
            } catch (NumberFormatException ignored) {
                // Not a persisted int: treat as unset.
            }
        }
        return accent(context);
    }

    /** Resolves a color mode: 0 accent, 1 white, 2 custom (3 rainbow is handled by callers). */
    public static int resolveColor(Context context, int mode, int custom) {
        switch (mode) {
            case 1:
                return Color.WHITE;
            case 2:
                return custom;
            default:
                return accent(context);
        }
    }
}
