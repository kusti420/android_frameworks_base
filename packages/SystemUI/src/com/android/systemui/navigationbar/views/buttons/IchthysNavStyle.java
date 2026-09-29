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

package com.android.systemui.navigationbar.views.buttons;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * IchthysOS navigation bar style ({@code navBar.style} in ichthys.nix, Settings.Secure
 * {@value #KEY_STYLE}). Shared by {@code NavigationBarView} (3-button keys),
 * {@code NavigationBarTransitions} (ink background) and {@code NavigationHandle} (gesture pill).
 */
public final class IchthysNavStyle {

    public static final String KEY_STYLE = "ichthys_navbar_style";
    public static final String KEY_THEME_OVERLAYS = "theme_customization_overlay_packages";
    public static final String KEY_FONT_MONO = "ichthys_theme_font_mono";

    public static final int STOCK = 0;
    public static final int SHOAL = 1;
    public static final int LATTICE = 2;
    public static final int MONO = 3;
    public static final int GESTURES = 4;

    public static final int DEFAULT = SHOAL;

    // Ink (dark) tokens.
    public static final int INK = 0xFF07090B;
    static final int D_CARD = 0xFF0C1013;
    static final int D_HAIRLINE = 0xFF20282D;
    static final int D_GLYPH = 0xFFF2F4F1;
    static final int D_DISC = 0xFF161C20;
    static final int D_MUTED = 0xFF8B938F;
    public static final int D_TRACK = 0xFF2A3136;

    // Light tokens.
    static final int L_CARD = 0xFFF7F8F6;
    static final int L_HAIRLINE = 0xFFD5D9D6;
    static final int L_GLYPH = 0xFF1B2226;
    static final int L_DISC = 0xFFE3E7E4;
    static final int L_MUTED = 0xFF5E6663;
    public static final int L_TRACK = 0xFFC9CFCB;

    private IchthysNavStyle() {}

    /** The selected style for the current user; unknown/unset values give {@link #DEFAULT}. */
    public static int read(Context context) {
        String v = null;
        try {
            v = Settings.Secure.getStringForUser(context.getContentResolver(), KEY_STYLE,
                    UserHandle.USER_CURRENT);
        } catch (Exception ignored) {
        }
        if (v == null) return DEFAULT;
        switch (v.trim()) {
            case "stock": return STOCK;
            case "shoal": return SHOAL;
            case "lattice": return LATTICE;
            case "mono": return MONO;
            case "gestures": return GESTURES;
            default: return DEFAULT;
        }
    }

    /** Styles that redraw the 3-button keys. */
    public static boolean isKeyStyle(int style) {
        return style == SHOAL || style == LATTICE || style == MONO;
    }

    /** Settings the navbar style depends on (style, accent seed, mono font). */
    public static Uri[] observedUris() {
        return new Uri[] {
                Settings.Secure.getUriFor(KEY_STYLE),
                Settings.Secure.getUriFor(KEY_THEME_OVERLAYS),
                Settings.Secure.getUriFor(KEY_FONT_MONO),
        };
    }

    /** JetBrains Mono (or the user's mono family), falling back to the system monospace. */
    public static Typeface monoTypeface(Context context) {
        String family = null;
        try {
            family = Settings.Secure.getStringForUser(context.getContentResolver(),
                    KEY_FONT_MONO, UserHandle.USER_CURRENT);
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(family)) family = "jetbrains-mono";
        Typeface base = Typeface.create(family, Typeface.NORMAL);
        if (base == null || base.equals(Typeface.DEFAULT)) {
            base = Typeface.MONOSPACE;
        }
        return Typeface.create(base, 500, false);
    }

    /** Blend a dark-token colour towards its light-token counterpart by dark intensity. */
    public static int mix(int dark, int light, float darkIntensity) {
        if (darkIntensity <= 0f) return dark;
        if (darkIntensity >= 1f) return light;
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, dark, light);
    }
}
