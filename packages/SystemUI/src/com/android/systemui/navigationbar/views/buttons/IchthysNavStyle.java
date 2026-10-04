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
 * {@value #KEY_STYLE}): the one navbar style system (stock, shoal, lattice, mono, gestures).
 * "stock" is the plain AOSP glyphs; shoal / lattice / mono redraw the 3-button keys in code and
 * "gestures" restyles the gesture handle.
 *
 * <p><b>This file exists twice, on purpose.</b> SystemUI draws the navigation bar itself
 * ({@code com.android.systemui.navigationbar.views.buttons.IchthysNavStyle}: NavigationBarView,
 * NavigationBarTransitions, NavigationHandle) and, with taskbar / navbar unification, the
 * Launcher3 taskbar draws it on phones
 * ({@code com.android.launcher3.taskbar.navbutton.IchthysNavStyle}: NavbarButtonsViewController,
 * TaskbarTransitions, StashedHandleView). Launcher3 cannot link SystemUI code and there is no
 * shared library both may depend on, so the class is duplicated. The two copies must stay
 * identical apart from the {@code package} line: change one, copy it over the other.
 *
 * <p>Colours are not defined here. Both copies read the shared Shoal tokens from the framework
 * ({@code com.android.internal.R.color.ichthys_*}, values/ichthys_tokens.xml, written per user by
 * the theme engine), so the nav bar follows the palette and the accent like every other surface.
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

    private IchthysNavStyle() {}

    /**
     * The Shoal tokens the nav bar draws with, resolved from the framework token resources. A
     * snapshot: build a new one when the keys are rebuilt (style / accent / font setting, night
     * mode or palette overlay change).
     *
     * <p>The bar sits over arbitrary app content, so it does not follow night mode but the bar's
     * dark intensity: every surface and glyph token is taken in both leaves, {@code *Dark} for
     * light keys on a dark bar (intensity 0) and {@code *Light} for dark keys on a light bar
     * (intensity 1), and blended with {@link IchthysNavStyle#mix}. The accent pair is
     * theme-resolved.
     */
    public static final class Tokens {
        /** Opaque ink bar behind the keys (always the dark ground). */
        public final int ink;
        /** Shoal key disc. */
        public final int cardDark;
        public final int cardLight;
        /** Lattice key disc, mono pressed pill. */
        public final int discDark;
        public final int discLight;
        /** 1 dp ring around a shoal key. */
        public final int hairlineDark;
        public final int hairlineLight;
        /** Key glyph strokes. */
        public final int glyphDark;
        public final int glyphLight;
        /** Mono key labels. */
        public final int mutedDark;
        public final int mutedLight;
        /** Gesture handle track. */
        public final int trackDark;
        public final int trackLight;
        /** Home key, gesture handle centre segment. */
        public final int accent;
        /** Glyph on an accent-filled key. */
        public final int onAccent;

        Tokens(Context context) {
            ink = context.getColor(com.android.internal.R.color.ichthys_ink_dark);
            // The key discs sit one step off the surface behind them. In the dark ramp that is
            // card / raised; the light ramp runs the other way (white cards on an off-white
            // ground), so the same step off a light bar is the light ground / tile rung.
            cardDark = context.getColor(com.android.internal.R.color.ichthys_card_dark);
            cardLight = context.getColor(com.android.internal.R.color.ichthys_ink_light);
            discDark = context.getColor(com.android.internal.R.color.ichthys_raised_dark);
            discLight = context.getColor(com.android.internal.R.color.ichthys_tile_light);
            hairlineDark = context.getColor(com.android.internal.R.color.ichthys_hairline_dark);
            hairlineLight = context.getColor(com.android.internal.R.color.ichthys_hairline_light);
            glyphDark = context.getColor(com.android.internal.R.color.ichthys_text_dark);
            glyphLight = context.getColor(com.android.internal.R.color.ichthys_text_light);
            mutedDark = context.getColor(com.android.internal.R.color.ichthys_muted_dark);
            mutedLight = context.getColor(com.android.internal.R.color.ichthys_muted_light);
            trackDark = context.getColor(com.android.internal.R.color.ichthys_track_dark);
            trackLight = context.getColor(com.android.internal.R.color.ichthys_track_light);
            accent = context.getColor(com.android.internal.R.color.ichthys_accent);
            onAccent = context.getColor(com.android.internal.R.color.ichthys_on_accent);
        }
    }

    /** The current Shoal tokens for {@code context} (see {@link Tokens}). */
    public static Tokens tokens(Context context) {
        return new Tokens(context);
    }

    /** The selected style for the current user; unknown/unset values give {@link #DEFAULT}. */
    public static int read(Context context) {
        final String v = secure(context, KEY_STYLE);
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

    /**
     * Settings.Secure string of the current user. SystemUI is one process for all users, so it
     * has to ask for the current user's value; Launcher3 already runs as that user, and if it is
     * not allowed to name a user explicitly its own value is the same one.
     */
    private static String secure(Context context, String key) {
        try {
            return Settings.Secure.getStringForUser(context.getContentResolver(), key,
                    UserHandle.USER_CURRENT);
        } catch (Exception ignored) {
        }
        try {
            return Settings.Secure.getString(context.getContentResolver(), key);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Styles that redraw the 3-button keys. */
    public static boolean isKeyStyle(int style) {
        return style == SHOAL || style == LATTICE || style == MONO;
    }

    /**
     * Settings the navbar style depends on (style, theme / accent seed, mono font). The token
     * colours themselves arrive with the palette overlay, i.e. as a configuration change
     * (assets paths), which the callers also restyle on.
     */
    public static Uri[] observedUris() {
        return new Uri[] {
                Settings.Secure.getUriFor(KEY_STYLE),
                Settings.Secure.getUriFor(KEY_THEME_OVERLAYS),
                Settings.Secure.getUriFor(KEY_FONT_MONO),
        };
    }

    /** The user's mono family (Shoal font token by default), else the system monospace. */
    public static Typeface monoTypeface(Context context) {
        String family = secure(context, KEY_FONT_MONO);
        if (TextUtils.isEmpty(family)) {
            family = context.getString(com.android.internal.R.string.ichthys_font_mono);
        }
        Typeface base = Typeface.create(family, Typeface.NORMAL);
        if (base == null || base.equals(Typeface.DEFAULT)) {
            base = Typeface.MONOSPACE;
        }
        return Typeface.create(base, 500, false);
    }

    /** Blend a dark-leaf colour towards its light-leaf counterpart by dark intensity. */
    public static int mix(int dark, int light, float darkIntensity) {
        if (darkIntensity <= 0f) return dark;
        if (darkIntensity >= 1f) return light;
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, dark, light);
    }
}
