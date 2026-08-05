/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.util.TypedValue.TYPE_INT_COLOR_ARGB8;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManagerTransaction;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.om.OverlayManagerInternal;
import com.android.systemui.monet.ColorScheme;
import com.android.systemui.monet.DynamicColors;

import com.google.ux.material.libmonet.dynamiccolor.DynamicColor;
import com.google.ux.material.libmonet.hct.Hct;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * A utility class responsible for creating and applying color-based theme overlays.
 * <p>
 * This class encapsulates the logic for generating {@link FabricatedOverlay} instances
 * from a {@link ColorScheme} and committing them to the {@link OverlayManagerInternal}.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class ThemeOverlayHelper {
    private static final String TAG = "ThemeOverlayHelper";
    private static final String ANDROID_PACKAGE = "android";

    private static final String OVERLAY_NAME_DYNAMIC = "dynamic";

    // Custom Options "pitch black": graded near-black palette applied to the dark surface roles
    // instead of forcing every surface to pure #000. Stepping the shades by elevation gives the
    // UI visual separation and subtle borders while staying clearly a very dark, accent-forward
    // theme. Values are opaque ARGB8.
    //
    // These are the FALLBACK ladder, used only when the seed is unusable (fully transparent, e.g.
    // pure wallpaper theming). Normally the ladder is derived from the user's accent seed by
    // {@link SurfaceLadder}, which tints these same rungs instead of leaving them neutral grey.
    private static final int PB_BLACK           = 0xFF000000; // background / dim / lowest surface
    private static final int PB_SURFACE         = 0xFF0A0A0A; // base surface / container low
    private static final int PB_CONTAINER       = 0xFF141414; // surface container / surface variant
    private static final int PB_ELEVATED        = 0xFF1C1C1C; // elevated container / surface bright
    private static final int PB_ELEVATED_HIGH   = 0xFF242424; // highest container
    private static final int PB_OUTLINE         = 0xFF333333; // subtle border
    private static final int PB_OUTLINE_VARIANT = 0xFF1F1F1F; // very subtle divider

    // IchthysOS surface ladder: the dark rungs are derived from the user's accent seed rather than
    // being flat greys, so blacks read blue/violet instead of dead grey. Two properties matter:
    //
    //  1. The ladder is HUE-AGNOSTIC. Whatever accent the user picks is the seed; nothing here is
    //     tuned for one hue.
    //  2. The surfaces stay in the SEED'S OWN hue family and are only NUDGED toward the cold side
    //     of the wheel -- by a capped number of DEGREES, not by blending a fraction of the way to
    //     a fixed anchor. An earlier version blended 55-85% toward blue, which drowned the seed:
    //     every accent produced essentially the same blue-violet surfaces, which is exactly the
    //     "far too blue" complaint. Capping the rotation keeps a fuchsia accent's surfaces plum,
    //     a green accent's surfaces deep teal, and so on - a monochromatic ramp with a cold lean,
    //     rather than a blue theme with a hint of accent.
    //  3. The shift is LARGEST at the darkest rungs and eases off as they get lighter, so the
    //     deepest surfaces read coolest and the elevated ones sit closest to the accent.
    //
    // Tone is HCT lightness, which is perceptually uniform, so rotating hue at a fixed tone keeps
    // the rung's apparent brightness - the ramp's brightness steps stay even without hand-tuning.
    private static final double SURFACE_HUE_ANCHOR = 265.0; // the "cold" direction (blue/indigo)
    private static final double SURFACE_COLD_SHIFT_MAX = 30.0; // max degrees away from the seed
    private static final double SURFACE_CHROMA = 10.0;      // research-backed: 8-12 gives "clearly
                                                             // tinted on inspection, reads dark neutral"
                                                             // without the muddy/brown risk of higher
                                                             // values at low tones. 32 was far too high.
    private static final double OUTLINE_CHROMA = 12.0;      // borders: visible tint, lower than before

    // Abyss palette: two distinct surface ramps. Research-backed from Material Design 3 color
    // science, CIEDE2000 perceptibility measurements, and AMOLED dark theme best practices.
    //
    //   DEFAULT (pitch-black OFF): tinted near-black surfaces — the cold-shifted hue rotation
    //   is visible at these tones with SURFACE_CHROMA 10. Wider tone gaps than before give
    //   clearer spatial hierarchy (research: stock MD3 uses +2/+4/+2/+5/+5 gaps; we shift the
    //   whole ramp ~4 tones darker and widen the upper steps for more punch).
    //
    //   PITCH BLACK ON: OLED-black with neutral grey steps so elevated elements (search bar,
    //   cards, dialogs) are distinguishable from the pixel-off background without any tint.
    //   Research: at tone 0, the jump to tone 4 is already clearly visible on OLED because the
    //   pixel-off-to-pixel-on transition creates a hard perceptual edge.
    //
    // Per-rung {tone when pitch black is ON, tone by default, share of SURFACE_COLD_SHIFT_MAX}.
    // PB ON uses chroma 0 (neutral grey). PB OFF uses SURFACE_CHROMA with a cold-shifted hue.
    private static final double[][] LADDER = {
            {0.0,   4.0,  1.00}, // black:          window background / dim / lowest — OLED black
            {0.0,   6.0,  0.90}, // surface:        base surface / container low — OLED black
            {10.0, 10.0,  0.75}, // container:      surface container / search bar / cards
            {14.0, 15.0,  0.60}, // elevated:       elevated container / surface bright
            {18.0, 20.0,  0.50}, // elevated high:  dialogs, popups
            {40.0, 50.0,  0.70}, // outline:        subtle border
            {6.0,  25.0,  0.80}, // outline variant: very subtle divider
    };
    private static final int RUNG_BLACK = 0;
    private static final int RUNG_SURFACE = 1;
    private static final int RUNG_CONTAINER = 2;
    private static final int RUNG_ELEVATED = 3;
    private static final int RUNG_ELEVATED_HIGH = 4;
    private static final int RUNG_OUTLINE = 5;
    private static final int RUNG_OUTLINE_VARIANT = 6;

    private final OverlayManagerInternal mOverlayManager;

    ThemeOverlayHelper() {
        mOverlayManager = LocalServices.getService(OverlayManagerInternal.class);
    }

    /**
     * Applies color overlays for a given user based on their current theme state.
     *
     * @param snapshot       The snapshot containing all necessary user, profile, and color info.
     * @param applyToSystem  Whether to apply overlays to the system user as well.
     * @param shouldRegister Whether to register the overlays (true) or just enable them (false).
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void applyCurrentStateOverlays(ThemeStatePair.OverlaySnapshot snapshot,
            boolean applyToSystem, boolean shouldRegister) throws CancellationException {
        if (shouldRegister || areOverlaysMissing(snapshot.userId())) {
            registerAndEnableOverlays(snapshot, applyToSystem);
        } else if (applyToSystem) {
            enableOverlaysOnly(snapshot);
        }
    }

    private boolean areOverlaysMissing(int userId) {
        final OverlayIdentifier identifier = new OverlayIdentifier(ANDROID_PACKAGE,
                OVERLAY_NAME_DYNAMIC + "_" + userId);
        try {
            if (mOverlayManager.getOverlayInfo(identifier, UserHandle.of(userId)) == null) {
                return true;
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to check if overlay exists: " + identifier, e);
            return true;
        }

        return false;
    }

    /**
     * Checks if the dynamic overlay for the given user is correctly enabled in OMS.
     *
     * @param userId The user ID to check.
     * @return {@code true} if the overlay is registered and enabled for the user.
     */
    public boolean isOverlayEnabled(int userId) {
        final OverlayIdentifier identifier = new OverlayIdentifier(ANDROID_PACKAGE,
                OVERLAY_NAME_DYNAMIC + "_" + userId);
        try {
            OverlayInfo info = mOverlayManager.getOverlayInfo(identifier, UserHandle.of(userId));
            return info != null && info.isEnabled();
        } catch (Exception e) {
            Slog.w(TAG, "Failed to check if overlay is enabled: " + identifier, e);
            return false;
        }
    }

    /**
     * Checks if the given ColorSchemes are correctly applied to the user's resources.
     * <p>
     * Note: This is a heuristic check and does not verify every single color. It checks a
     * representative subset of colors to determine if the ColorScheme is generally applied.
     *
     * @param context     The system context.
     * @param userId      The user ID to check.
     * @param darkScheme  The expected dark color scheme.
     * @param lightScheme The expected light color scheme.
     * @return {@code true} if the colors match the expected schemes.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean isColorSchemeApplied(Context context, int userId, ColorScheme darkScheme,
            ColorScheme lightScheme) {
        Resources res = context.createContextAsUser(UserHandle.of(userId), 0).getResources();

        try {
            // Dark ACCENT and dark NEUTRAL roles are verified in colorsMatchPitchBlackState because
            // pitch black rewrites them (accents flatten to the seed, neutrals lose their tint);
            // asserting the stock Monet tone here would spuriously fail (and loop-regenerate) while
            // pitch black is on. Light values are never rewritten, so they stay asserted inline
            // against the light Monet scheme.
            return res.getColor(R.color.system_accent1_500_light)
                    == lightScheme.getAccent1().getS500()

                    && res.getColor(R.color.system_accent2_500_light)
                    == lightScheme.getAccent2().getS500()

                    && res.getColor(R.color.system_accent3_500_light)
                    == lightScheme.getAccent3().getS500()

                    && res.getColor(R.color.system_neutral1_500_light)
                    == lightScheme.getNeutral1().getS500()

                    && res.getColor(R.color.system_neutral2_500_light)
                    == lightScheme.getNeutral2().getS500()

                    && res.getColor(android.R.color.system_outline_variant_dark)
                    == darkScheme.getMaterialScheme().getOutlineVariant()
                    && res.getColor(android.R.color.system_outline_variant_light)
                    == lightScheme.getMaterialScheme().getOutlineVariant()

                    && res.getColor(android.R.color.system_primary_container_light)
                    == lightScheme.getMaterialScheme().getPrimaryContainer()

                    // Custom Options "pitch black": the applied dark surfaces AND dark accent fills
                    // must agree with the current custom_pitch_black flag. When enabled we render the
                    // graded near-black surfaces plus the single flat seed accent (accent fills all
                    // collapse to the picked seed, on-accent tones flip to contrasting black/white);
                    // when disabled the stock Monet surfaces and tonal accent spread apply unchanged.
                    // If what is on screen disagrees with the flag, treat the scheme as not applied
                    // so the overlay is regenerated. This is what makes the flag take effect at boot
                    // and toggle live.
                    && colorsMatchPitchBlackState(res, darkScheme, isIchthysPaletteEnabled(userId),
                            isIchthysPaletteEnabled(userId) && isPitchBlackEnabled(userId));
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }

    /**
     * Checks if the dynamic overlay for the given user is registered.
     *
     * @param userId The user ID to check.
     * @return {@code true} if the overlay is registered, {@code false} otherwise.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean isOverlayRegistered(int userId) {
        OverlayIdentifier identifier = new OverlayIdentifier(ANDROID_PACKAGE,
                OVERLAY_NAME_DYNAMIC + "_" + userId);
        return mOverlayManager.getOverlayInfo(identifier, UserHandle.of(userId)) != null;
    }

    /**
     * The "Light" path: Only enables existing overlays.
     * Avoids expensive color calculation and object allocation.
     */
    private void enableOverlaysOnly(ThemeStatePair.OverlaySnapshot snapshot) {
        final int userId = snapshot.userId();

        Slog.d(TAG, "Enabling existing overlays for user " + userId);
        if (userId != UserHandle.SYSTEM.getIdentifier()) {
            Slog.d(TAG, "Enabling existing overlays for System User");
        }

        final OverlayManagerTransaction.Builder transaction =
                new OverlayManagerTransaction.Builder();

        final OverlayIdentifier identifier = new OverlayIdentifier(ANDROID_PACKAGE,
                OVERLAY_NAME_DYNAMIC + "_" + userId);
        addToTransaction(transaction, identifier, userId, /* applyToSystem */ true,
                snapshot.profiles());
        checkCancellation();

        commitTransaction(transaction);
    }

    /**
     * The "Heavy" path: Creates, Registers, and Enables overlays.
     */
    private void registerAndEnableOverlays(ThemeStatePair.OverlaySnapshot snapshot,
            boolean applyToSystem) {
        final int userId = snapshot.userId();
        final FabricatedOverlay overlay = createDynamicOverlay(snapshot.lightScheme(),
                snapshot.darkScheme(), userId);

        Slog.d(TAG, "Registering and Enabling overlay " + OVERLAY_NAME_DYNAMIC + " for user "
                + userId);

        final OverlayManagerTransaction.Builder transaction =
                new OverlayManagerTransaction.Builder();

        transaction.registerFabricatedOverlay(overlay);
        addToTransaction(transaction, overlay.getIdentifier(), userId, applyToSystem,
                snapshot.profiles());
        checkCancellation();

        commitTransaction(transaction);
    }

    private void addToTransaction(OverlayManagerTransaction.Builder transaction,
            OverlayIdentifier identifier, int userId, boolean applyToSystem,
            Set<Integer> profileIds) {
        transaction.setEnabled(identifier, true, userId);

        // All generated color overlays must also be applied to the system user for SystemUI.
        if (applyToSystem && userId != UserHandle.SYSTEM.getIdentifier()) {
            transaction.setEnabled(identifier, true, UserHandle.SYSTEM.getIdentifier());
        }

        // And to all associated managed profiles.
        for (int profileId : profileIds) {
            transaction.setEnabled(identifier, true, profileId);
        }
    }

    private boolean commitTransaction(OverlayManagerTransaction.Builder transaction) {
        try {
            mOverlayManager.commit(transaction.build());
            return true;
        } catch (SecurityException | IllegalStateException e) {
            Slog.w(TAG, "Could not commit overlays to OverlayManager");
            return false;
        }
    }

    /**
     * Cleans up legacy overlays from previous controllers.
     * This ensures we don't have duplicate or orphaned overlays persisting.
     *
     * @param legacyOverlays A list of legacy overlay identifiers in the format
     *                       "packageName:overlayName".
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void cleanupLegacyOverlays(List<String> legacyOverlays) {
        if (legacyOverlays == null || legacyOverlays.isEmpty()) {
            return;
        }

        final OverlayManagerTransaction.Builder transaction =
                new OverlayManagerTransaction.Builder();
        boolean hasRemovals = false;

        for (String overlay : legacyOverlays) {
            String[] split = overlay.split("\\|");
            if (split.length != 2) {
                Slog.w(TAG, "Invalid legacy overlay format: " + overlay);
                continue;
            }

            OverlayIdentifier identifier = new OverlayIdentifier(split[0], split[1]);
            if (mOverlayManager.getOverlayInfo(identifier, UserHandle.SYSTEM) != null) {
                Slog.d(TAG, "Cleaning up legacy overlay: " + overlay);
                transaction.unregisterFabricatedOverlay(identifier);
                hasRemovals = true;
            }
        }

        if (!hasRemovals) {
            return;
        }

        try {
            mOverlayManager.commit(transaction.build());
        } catch (SecurityException | IllegalStateException e) {
            Slog.w(TAG, "Failed to cleanup legacy overlays (this is likely harmless): " + e);
        }
    }


    /**
     * Creates a fabricated overlay for dynamic colors.
     *
     * @param lightColorScheme The color scheme for light theme.
     * @param darkColorScheme  The color scheme for dark theme.
     * @return A fabricated overlay containing dynamic colors.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public FabricatedOverlay createDynamicOverlay(ColorScheme lightColorScheme,
            ColorScheme darkColorScheme, int userId) {
        FabricatedOverlay overlay = new FabricatedOverlay.Builder(ANDROID_PACKAGE,
                OVERLAY_NAME_DYNAMIC + "_" + userId, ANDROID_PACKAGE).build();

        // IchthysOS "Abyss" palette (Theme Engine > palette style). When selected, the dark
        // surface/background roles take a tinted near-black ladder derived from the user's accent
        // seed instead of Monet's tonal surfaces, so the whole system (QS, Settings and any app
        // that follows the system dark theme) gets cool, accent-flavoured blacks. Additionally
        // EVERY accent FILL role -- both the legacy tonal band (accent1/2/3 tones 100..700) and the
        // Material dynamic primary/secondary/tertiary roles (incl. their dim/container/fixed
        // variants) -- is flattened to the single user-picked seed so the accent is ONE flat color
        // everywhere (QS tiles, notifications, switches, links, buttons) instead of a Monet tonal
        // spread, while the on-accent (on_*) roles flip to contrasting black/white to stay legible.
        // The applied "android:dynamic_<user>" overlay generated here is what every app resolves
        // system_* colors from.
        //
        // On Monet this is all skipped and the stock scheme is emitted untouched.
        final boolean abyss = isIchthysPaletteEnabled(userId);

        // "Pitch black" is now only a modifier ON TOP of Abyss: it drives the bottom rung of the
        // ladder to true #000 for OLED. It no longer selects the palette, and it does nothing at
        // all on Monet.
        final boolean pureBlack = abyss && isPitchBlackEnabled(userId);

        // IchthysOS: derive the tinted surface ladder from the seed once, so every role below sees
        // the same ramp. Null when the seed is unusable -> the fixed grey fallback ladder.
        final SurfaceLadder ladder = abyss ? SurfaceLadder.from(darkColorScheme, pureBlack) : null;

        // Neutral palette
        assignColorsToOverlay(overlay, DynamicColors.getAllNeutralPalette(),
                lightColorScheme, darkColorScheme, abyss, ladder);

        // Accent palette
        assignColorsToOverlay(overlay, DynamicColors.getAllAccentPalette(),
                lightColorScheme, darkColorScheme, abyss, ladder);

        //Themed Colors
        assignColorsToOverlay(overlay, DynamicColors.getAllDynamicColorsMapped(),
                lightColorScheme, darkColorScheme, abyss, ladder);

        //Fixed Colors
        assignColorsToOverlay(overlay, DynamicColors.getFixedColorsMapped(),
                lightColorScheme, darkColorScheme, abyss, ladder);

        //Custom Colors
        assignColorsToOverlay(overlay, DynamicColors.getCustomColorsMapped(),
                lightColorScheme, darkColorScheme, abyss, ladder);

        return overlay;
    }

    private void assignColorsToOverlay(FabricatedOverlay overlay,
            List<Pair<String, DynamicColor>> colors, ColorScheme lightColorScheme,
            ColorScheme darkColorScheme, boolean pitchBlack, @Nullable SurfaceLadder ladder) {
        for (Pair<String, DynamicColor> p : colors) {
            String prefix = "android:color/system_" + p.first;
            overlay.setResourceValue(prefix + "_light", TYPE_INT_COLOR_ARGB8,
                    p.second.getArgb(lightColorScheme.getMaterialScheme()), null);
            int darkArgb = p.second.getArgb(darkColorScheme.getMaterialScheme());
            if (pitchBlack) {
                final Integer shade = pitchBlackShade(p.first, ladder);
                if (shade != null) {
                    darkArgb = shade;
                } else {
                    final Integer accent = pitchBlackAccent(p.first, darkColorScheme);
                    if (accent != null) {
                        darkArgb = accent;
                    } else if (isUngradedNeutralTone(p.first)) {
                        darkArgb = retintNeutral(darkArgb, ladder);
                    }
                }
            }
            overlay.setResourceValue(prefix + "_dark", TYPE_INT_COLOR_ARGB8, darkArgb, null);
        }
    }

    /**
     * Reads the "pitch black" custom option (Settings.Secure custom_pitch_black) for the given
     * user. Uses the system context because overlay generation runs in system_server without a
     * per-user context here. Any failure is treated as "disabled" so normal Monet is unaffected.
     */
    private boolean isPitchBlackEnabled(int userId) {
        return readSecureFlag(userId, "custom_pitch_black", 0);
    }

    /**
     * IchthysOS: whether the Abyss palette is selected in the Theme Engine
     * (Settings.Secure {@code theme_engine_style_mode}: 0 = Monet, 1 = Abyss).
     * <p>
     * This, not {@code custom_pitch_black}, is what decides whether this ROM's own palette is
     * generated. The two used to be the same switch, which meant the palette could only be had
     * with a pure-black background and nobody on Monet could opt out of the ROM's extras. Now the
     * mode picks the palette and {@code custom_pitch_black} only drives the bottom of the surface
     * ladder to true black - see {@link #LADDER}.
     */
    private boolean isIchthysPaletteEnabled(int userId) {
        return readSecureFlag(userId, "theme_engine_style_mode", 0);
    }

    /** Reads a per-user Secure int flag; any failure is treated as "off" so Monet is unaffected. */
    private boolean readSecureFlag(int userId, String key, int def) {
        try {
            final Context ctx = ActivityThread.currentActivityThread().getSystemContext();
            if (ctx == null) {
                return false;
            }
            return Settings.Secure.getIntForUser(ctx.getContentResolver(), key, def, userId) == 1;
        } catch (Exception e) {
            Slog.w(TAG, "Failed to read " + key, e);
            return false;
        }
    }

    /**
     * Maps a Material surface/background role (or the dark end of a neutral tonal palette) to the
     * graded near-black shade it should take in pitch-black mode, or {@code null} if the role is
     * not a pitch-black surface and should keep its Monet value. These roles are what app window
     * backgrounds / cards / dividers resolve to via {@code ?android:attr/colorBackground} and the
     * {@code system_surface*} / {@code system_outline*} colors. Instead of forcing every one of
     * them to pure black (which reads flat), the shades step up by Material elevation so cards,
     * sheets and popups separate from the background and gain a subtle border.
     */
    private static Integer pitchBlackShade(String name, @Nullable SurfaceLadder ladder) {
        final Integer rung = pitchBlackRung(name);
        if (rung == null) {
            return null;
        }
        return ladder != null ? ladder.rung(rung) : FALLBACK_LADDER[rung];
    }

    /** The fixed grey ladder used when the seed is unusable, indexed by {@code RUNG_*}. */
    private static final int[] FALLBACK_LADDER = {
            PB_BLACK, PB_SURFACE, PB_CONTAINER, PB_ELEVATED, PB_ELEVATED_HIGH,
            PB_OUTLINE, PB_OUTLINE_VARIANT,
    };

    /**
     * Maps a role name to its rung on the surface ladder ({@code RUNG_*}), or {@code null} when the
     * role is not a pitch-black surface. Split out of {@link #pitchBlackShade} so the rung is
     * decided by name alone and the actual colour comes from whichever ladder is in force.
     */
    private static Integer pitchBlackRung(String name) {
        switch (name) {
            // Darkest tier: window background and the lowest/dimmest surfaces stay true black.
            case "background":
            case "surface_dim":
            case "surface_container_lowest":
                return RUNG_BLACK;
            // Base surface tier — everything that should be OLED-black in PB mode.
            // surface_bright: Settings preference cards, SettingsLib banners/tabs.
            // surface_container: Settings window background (colorBackground in
            //   Theme.SettingsBase_v36 and the night-mode Theme.Settings.Home),
            //   preference_background drawable, any materialColorSurfaceContainer user.
            // surface_variant: chip/card variant backgrounds.
            case "surface":
            case "surface_container_low":
            case "surface_bright":
            case "surface_container":
            case "surface_variant":
                return RUNG_SURFACE;
            // IchthysOS: surface_container_high used by notification card backgrounds,
            // bottom sheets, etc. — black in PB mode so notification cards don't
            // show as grey (#242424) rectangles on the OLED-black shade.
            case "surface_container_high":
                return RUNG_SURFACE;
            // Highest elevation tier — the only remaining visible-grey surface.
            // Dialogs, popups, and the Settings search bar pill use this to
            // stand out against the pure-black background.
            case "surface_container_highest":
                return RUNG_ELEVATED;
            // Borders / dividers: tinted to match the surfaces so edges are visible against black
            // without glowing.
            case "outline":
                return RUNG_OUTLINE;
            case "outline_variant":
                return RUNG_OUTLINE_VARIANT;
            // IchthysOS: surfaceEffect colors are semi-transparent tinted overlays used as
            // backgrounds for QS tiles, notification rows, toolbar buttons, shade panel,
            // volume dialog, etc. Without this, their Monet-tinted alpha values bleed through
            // on top of the black surfaces and make the background appear tinted / non-black.
            // Map them to appropriate opaque ladder rungs so they become solid dark greys.
            case "surface_effect_0":            // shade panel, volume dialog, pin bouncer bg
            case "surface_effect_0_fallback":   // fallback for the above
                return RUNG_BLACK;
            case "surface_effect_1":            // QS tile bg, notification bg, toolbar, footer
                return RUNG_SURFACE;
            case "surface_effect_2":            // edit tile icon bg, text feedback
                return RUNG_SURFACE;
            case "surface_effect_3":            // subtle accent layer
                return RUNG_SURFACE;
            // Dark end of the neutral tonal palettes (used as backgrounds by many apps). The Monet
            // engine tints these toward the seed; grade them to match the surface steps above.
            case "neutral1_1000":
            case "neutral2_1000":
                return RUNG_BLACK;
            case "neutral1_900":
            case "neutral2_900":
                return RUNG_SURFACE;
            // IchthysOS: neutral1_800 MUST map to the same rung as system_surface_dark
            // because SettingsLib (v31) maps its settingslib_colorSurface to neutral1_800,
            // which is what Settings uses for its main content area background. Without this
            // the Settings background appears grey (#1b1b1b) while the search bar (which uses
            // system_surface_dark = black) is darker than the area behind it — backwards.
            case "neutral1_800":
            case "neutral2_800":
                return RUNG_SURFACE;
            case "neutral1_700":
            case "neutral2_700":
                return RUNG_CONTAINER;
            default:
                return null;
        }
    }

    /**
     * Whether {@code name} is a neutral tonal token that {@link #pitchBlackShade} does NOT already
     * pin to a fixed grey, i.e. {@code neutralN_0..600}.
     * <p>
     * These matter because they are not only painted directly: the androidx Material library derives
     * its whole dark surface set from {@code neutral1_600} by keeping that token's hue/chroma and
     * overriding only the lightness (see {@code m3_ref_palette_dynamic_neutral12} and friends, which
     * are {@code <item android:color="@android:color/system_neutral1_600" android:lStar="12"/>}).
     * Monet tints the neutral palette toward the seed, so with only the 700..1000 end graded, every
     * Material-library app kept rendering seed-tinted "near black" surfaces while the system's own
     * surfaces were pure grey -- pitch black visibly stopped at the edge of Settings/SystemUI.
     * Stripping the chroma from the rest of the ramp is what carries the mode into those apps.
     */
    private static boolean isUngradedNeutralTone(String name) {
        if (!name.startsWith("neutral1_") && !name.startsWith("neutral2_")) {
            return false;
        }
        // pitchBlackRung already fixes 700..1000; everything below it gets retinted onto the ladder.
        return pitchBlackRung(name) == null;
    }

    /**
     * IchthysOS: the pitch-black surface ladder, derived once per overlay generation from the
     * user's accent seed. See {@link #LADDER} for the shape of the ramp and why the hue is blended
     * toward a fixed anchor instead of taken straight from the seed.
     */
    private static final class SurfaceLadder {
        private final double mSeedHue;
        private final int[] mRungs;
        private final boolean mPureBlack;

        private SurfaceLadder(double seedHue, boolean pureBlackBackground) {
            mSeedHue = seedHue;
            mPureBlack = pureBlackBackground;
            mRungs = new int[LADDER.length];
            for (int i = 0; i < LADDER.length; i++) {
                final double tone = pureBlackBackground ? LADDER[i][0] : LADDER[i][1];
                // Pitch black ON: zero chroma so steps are pure neutral grey, no tint.
                // Pitch black OFF: full chroma so the cold-shifted hue is visible.
                final double chroma;
                if (pureBlackBackground) {
                    // Outlines keep a hint of tint even in PB mode for subtle visual coherence.
                    chroma = (i == RUNG_OUTLINE) ? OUTLINE_CHROMA : 0.0;
                } else {
                    chroma = (i == RUNG_OUTLINE || i == RUNG_OUTLINE_VARIANT)
                            ? OUTLINE_CHROMA : SURFACE_CHROMA;
                }
                // Hct clamps chroma to what the tone can actually hold, so the darkest rungs come
                // back near-neutral on their own rather than being forced to an impossible colour.
                mRungs[i] = Hct.from(blendedHue(LADDER[i][2]), chroma, tone).toInt();
            }
        }

        /**
         * Hue for a rung: the seed's own hue, nudged toward the cold side of the wheel by
         * {@code coldFactor} of {@link #SURFACE_COLD_SHIFT_MAX} degrees.
         */
        double blendedHue(double coldFactor) {
            return coldShiftedHue(mSeedHue, coldFactor);
        }

        /** The opaque colour for a {@code RUNG_*} index. */
        int rung(int index) {
            return 0xFF000000 | (mRungs[index] & 0x00FFFFFF);
        }

        /** Whether this ladder was built for pitch-black (OLED) mode. */
        boolean isPureBlack() {
            return mPureBlack;
        }

        /**
         * Builds the ladder for {@code darkColorScheme}, or {@code null} when the seed is unusable
         * so callers fall back to the fixed grey {@link #FALLBACK_LADDER}.
         */
        @Nullable
        static SurfaceLadder from(ColorScheme darkColorScheme, boolean pureBlackBackground) {
            final Integer seed = flatAccentSeed(darkColorScheme);
            return seed == null ? null
                    : new SurfaceLadder(Hct.fromInt(seed).getHue(), pureBlackBackground);
        }
    }

    /**
     * Rotates {@code seedHue} toward the cold anchor along the shorter arc, capped at
     * {@link #SURFACE_COLD_SHIFT_MAX} degrees and then scaled by {@code coldFactor}.
     * <p>
     * The cap is the point: blending a fraction of the way to the anchor makes every seed converge
     * on the anchor's hue, so all accents end up with the same blue surfaces. Capping the rotation
     * in degrees keeps each surface in its seed's family - a fuchsia seed leans violet, a green one
     * leans teal - which is what makes the ramp read as monochromatic-with-a-cold-lean.
     * <p>
     * Taking the shorter arc means a warm seed rotates through red/magenta rather than sweeping the
     * long way round through green; for hues roughly opposite the anchor that is "away from warm"
     * rather than literally bluer, which still reads as a cohesive deepening of the accent.
     */
    private static double coldShiftedHue(double seedHue, double coldFactor) {
        final double delta = ((SURFACE_HUE_ANCHOR - seedHue + 540.0) % 360.0) - 180.0;
        final double capped =
                Math.max(-SURFACE_COLD_SHIFT_MAX, Math.min(SURFACE_COLD_SHIFT_MAX, delta));
        final double result = (seedHue + capped * coldFactor) % 360.0;
        return result < 0 ? result + 360.0 : result;
    }

    /**
     * IchthysOS: moves an ungraded neutral tone onto the surface ladder's hue instead of stripping
     * its colour. Keeps the token's own tone, because its consumers re-lighten it: androidx Material
     * derives its entire dark surface set from {@code neutral1_600} by keeping hue/chroma and
     * overriding only lightness.
     * <p>
     * When pitch black is ON, the neutral palette must be fully desaturated (chroma 0) so Material
     * apps (Settings, etc.) derive pure neutral grey surfaces from it. Without this, neutral1_600
     * keeps its Monet chroma and Material derives tinted surfaces that make Settings backgrounds
     * appear coloured instead of black. When PB is OFF, retinting carries the Abyss tinted blacks
     * into Material-library apps.
     */
    private static int retintNeutral(int argb, @Nullable SurfaceLadder ladder) {
        if (ladder == null || ladder.isPureBlack()) {
            // No ladder or PB ON: strip all chroma so Material apps get pure neutral greys.
            return desaturate(argb);
        }
        final double tone = Hct.fromInt(argb).getTone();
        final int tinted = Hct.from(ladder.blendedHue(0.75), SURFACE_CHROMA, tone).toInt();
        return (argb & 0xFF000000) | (tinted & 0x00FFFFFF);
    }

    /**
     * Returns {@code argb} with its colour removed, keeping the alpha and approximately the original
     * lightness. Uses the same cheap BT.601 luma as {@link #onAccentColor} rather than a full CAM16
     * round-trip: these tones are consumed either as flat greys or as a hue source that the consumer
     * re-lightens anyway, so preserving the exact perceptual tone buys nothing here.
     */
    private static int desaturate(int argb) {
        final int r = (argb >> 16) & 0xFF;
        final int g = (argb >> 8) & 0xFF;
        final int b = argb & 0xFF;
        final int grey = Math.min(255, Math.max(0,
                (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b)));
        return (argb & 0xFF000000) | (grey << 16) | (grey << 8) | grey;
    }

    /**
     * Custom Options "pitch black" (Substratum / 8.1 flat-accent look): maps an accent role (either
     * a legacy {@code accentN_*} tonal token or a Material dynamic {@code primary/secondary/tertiary}
     * token) to the pitch-black override for its dark value, or {@code null} to keep its Monet value.
     * <p>
     * The whole point of the Substratum theme is ONE flat accent everywhere. Monet instead spreads
     * the seed hue across a tonal ramp, so different UI elements resolve different lightnesses of it
     * and look inconsistent (QS tiles paint the raw seed, notifications tint with
     * {@code ?android:attr/colorAccent} = {@code system_accent1_100}, DeviceDefault
     * {@code colorAccentPrimary} = {@code system_accent1_700}, Material3 {@code colorPrimary} =
     * {@code system_primary}, etc.). So when pitch black is on we collapse EVERY accent FILL role to
     * the single picked seed and force every ON-accent role to a contrasting black/white:
     * <ul>
     *   <li>FILL roles ({@link #isAccentFillRole}) -> the seed, so the accent is exactly the same
     *       color everywhere with no tonal variation.</li>
     *   <li>ON-accent roles ({@link #isOnAccentRole}, the {@code on_*} tokens) -> pure black or
     *       white chosen by the seed's luminance, so text/icons drawn on the seed stay legible.</li>
     * </ul>
     * The legacy accent extreme tones ({@code accentN_0/50} near white, {@code accentN_900/1000}
     * near black) are intentionally left to Monet: they read as near-neutral on-accent poles and
     * already contrast, so touching them adds no consistency but risks contrast. {@code accentN_800}
     * is the exception and is handled as an ON-accent role, see {@link #isOnAccentRole}. Neutral
     * tonal roles are handled by {@link #pitchBlackShade} as surfaces. Returns {@code null} (keep
     * Monet) when the seed is unusable.
     */
    private static Integer pitchBlackAccent(String name, ColorScheme darkColorScheme) {
        final Integer seed = flatAccentSeed(darkColorScheme);
        if (seed == null) {
            return null;
        }
        if (isAccentFillRole(name)) {
            return seed;
        }
        if (isOnAccentRole(name)) {
            return onAccentColor(seed);
        }
        return null;
    }

    /**
     * Whether {@code name} is an accent FILL role that should collapse to the seed in pitch black.
     * Covers the legacy {@code accentN} fill band (tones 100..700, i.e. the mid/light and mid/dark
     * tones actually used AS the accent: colorAccent=accent1_100, colorAccentPrimaryVariant=200,
     * colorAccentPrimary=700, and the in-between tones) for accent1/2/3, plus the Material dynamic
     * accent fills ({@code primary/secondary/tertiary} and their {@code _dim}/{@code _container}/
     * {@code _fixed} variants and {@code inverse_primary}). Excludes {@code on_*} (on-accent),
     * the renamed {@code palette_key_color_*} tokens, and the near-neutral extreme tones.
     */
    private static boolean isAccentFillRole(String name) {
        switch (name) {
            case "accent1_100": case "accent1_200": case "accent1_300":
            case "accent1_400": case "accent1_500": case "accent1_600": case "accent1_700":
            case "accent2_100": case "accent2_200": case "accent2_300":
            case "accent2_400": case "accent2_500": case "accent2_600": case "accent2_700":
            case "accent3_100": case "accent3_200": case "accent3_300":
            case "accent3_400": case "accent3_500": case "accent3_600": case "accent3_700":
                return true;
            default:
                break;
        }
        if (name.startsWith("on_")) {
            return false;
        }
        if (name.equals("inverse_primary")) {
            return true;
        }
        // Material dynamic primary/secondary/tertiary fills and their dim/container/fixed variants.
        // The renamed palette-key tokens start with "palette_key_color_" so they are not caught.
        return name.startsWith("primary") || name.startsWith("secondary")
                || name.startsWith("tertiary");
    }

    /**
     * Whether {@code name} is an ON-accent role (text/icon drawn on top of an accent fill): the
     * Material dynamic {@code on_primary/on_secondary/on_tertiary} tokens and their container/fixed
     * variants. These must stay contrasting against the flattened seed, so pitch black paints them
     * pure black or white instead of the seed.
     * <p>
     * {@code accentN_800} counts too. It carries no {@code on_} prefix but IS the dark-theme
     * on-accent tone of the legacy tonal band: SettingsLib maps
     * {@code settingslib_materialColorOnPrimary}/{@code settingslib_thumb_on_color} to it in
     * {@code values-night}, and androidx Material maps its dark {@code colorOnPrimary}/
     * {@code colorOnSecondary}/{@code colorOnTertiary} to it. Left at its Monet value it is a dark
     * tone-20 of the (possibly hue-rotated) primary palette painted straight onto the flat seed,
     * which is both off-hue and low contrast, and it disagrees with the Material dynamic
     * {@code on_primary} token that this method already forces to black/white.
     * <p>
     * So do {@code accent2_100} and {@code accent3_100}, which in DARK are the ON-CONTAINER tones
     * of the legacy band and nothing else: SettingsLib maps
     * {@code settingslib_materialColorOnSecondaryContainer}/{@code ...OnTertiaryContainer} to them
     * in {@code values-night}, and androidx Material maps its dark {@code colorOnSecondaryContainer}
     * / {@code colorOnTertiaryContainer} to them. Their matching containers ({@code accentN_700})
     * are accent FILLS, so flattening both sides painted the content in exactly the colour of the
     * thing it sits on and it disappeared entirely -- a selected bottom-navigation icon on the flat
     * seed pill, for instance. {@code accent1_100} is deliberately NOT in this list: unlike 2/3 it is
     * genuinely dual-purpose in dark (DeviceDefault {@code colorAccent}/{@code colorAccentPrimary},
     * {@code settingslib_dialog_accent} and the preference-category title colour all resolve to it),
     * so it has to stay the flat seed and its {@code colorOnPrimaryContainer} duty loses out.
     */
    private static boolean isOnAccentRole(String name) {
        switch (name) {
            case "accent1_800": case "accent2_800": case "accent3_800":
            case "accent2_100": case "accent3_100":
                return true;
            default:
                break;
        }
        return name.startsWith("on_primary") || name.startsWith("on_secondary")
                || name.startsWith("on_tertiary");
    }

    /**
     * The single opaque seed accent to flatten the accent fill tones to in pitch-black mode. This is
     * the exact color the user picked in the Theme Engine ({@code system_palette}/{@code accent_color}
     * from the theme_customization_overlay_packages JSON) which was fed into this ColorScheme, i.e.
     * the same seed SystemUI's QS tiles render. Returns {@code null} when the seed is fully
     * transparent (no usable picked accent, e.g. pure wallpaper theming) so we fall back to the
     * Monet tonal value instead of painting a transparent/black accent.
     */
    private static Integer flatAccentSeed(ColorScheme darkColorScheme) {
        final int seed = darkColorScheme.getSeed();
        if ((seed >>> 24) == 0) {
            return null;
        }
        return 0xFF000000 | (seed & 0x00FFFFFF);
    }

    /**
     * Pure black or white to draw on top of the flat seed accent, chosen by the seed's perceptual
     * luminance so on-accent text/icons stay legible regardless of which accent the user picked.
     */
    private static int onAccentColor(int seedArgb) {
        // IchthysOS: pick whichever of black/white actually contrasts more, by WCAG relative
        // luminance and contrast ratio, rather than by a BT.601 luma threshold. BT.601 is a
        // gamma-encoded broadcast weighting, so it misjudges saturated hues -- a mid blue and a
        // mid yellow of equal luma need OPPOSITE on-colours, and its 0.5 cut got that wrong near
        // the boundary. Every on-X contrasting with its X is the one hard contract of this theme
        // (it is what keeps icons visible), so it should not depend on hand-tuning per hue.
        final double l = relativeLuminance(seedArgb);
        final double contrastWithBlack = (l + 0.05) / 0.05;
        final double contrastWithWhite = 1.05 / (l + 0.05);
        return contrastWithBlack >= contrastWithWhite ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** WCAG 2.x relative luminance of an opaque colour, in [0, 1]. */
    private static double relativeLuminance(int argb) {
        return 0.2126 * linearize((argb >> 16) & 0xFF)
                + 0.7152 * linearize((argb >> 8) & 0xFF)
                + 0.0722 * linearize(argb & 0xFF);
    }

    /** Undoes sRGB gamma encoding for one 8-bit channel. */
    private static double linearize(int channel) {
        final double s = channel / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /**
     * Returns whether the dark surfaces currently applied to {@code res} match the given
     * pitch-black state. When pitch black is on, a representative set of surface roles must equal
     * the graded near-black palette; when it is off, they must equal the stock Monet dark scheme.
     * Used by {@link #isColorSchemeApplied} so toggling custom_pitch_black forces a regeneration
     * and the transition round-trips in both directions.
     */
    private boolean colorsMatchPitchBlackState(Resources res, ColorScheme darkScheme,
            boolean pitchBlack, boolean pureBlack) {
        final int background = res.getColor(android.R.color.system_background_dark);
        final int surface = res.getColor(android.R.color.system_surface_dark);
        final int container = res.getColor(android.R.color.system_surface_container_dark);

        // Representative dark ACCENT fills that pitch black collapses to the seed, spanning both
        // representations: legacy tonal (accent1/2/3_500) and Material dynamic (primary_container).
        // accent1_100 is ?attr/colorAccent, the classic notification/legacy accent.
        final int accent1Fill = res.getColor(R.color.system_accent1_100_dark);
        final int accent1Mid = res.getColor(R.color.system_accent1_500_dark);
        final int accent2Mid = res.getColor(com.android.internal.R.color.system_accent2_500_dark);
        final int accent3Mid = res.getColor(com.android.internal.R.color.system_accent3_500_dark);
        final int primaryContainer =
                res.getColor(android.R.color.system_primary_container_dark);
        // Representative ON-accent roles that must stay contrasting against the seed, again spanning
        // both representations: Material dynamic (on_primary) and legacy tonal (accent1_800, which
        // SettingsLib and androidx Material both resolve their dark colorOnPrimary from).
        final int onPrimary = res.getColor(android.R.color.system_on_primary_dark);
        final int accent1On = res.getColor(R.color.system_accent1_800_dark);
        // Representative ungraded NEUTRAL tones, which pitch black strips the seed tint from. These
        // moved here out of isColorSchemeApplied: asserting their Monet value unconditionally would
        // never match once pitch black desaturates them, and the scheme would regenerate forever.
        final int neutral1Mid = res.getColor(com.android.internal.R.color.system_neutral1_500_dark);
        final int neutral2Mid = res.getColor(com.android.internal.R.color.system_neutral2_500_dark);

        if (pitchBlack) {
            final Integer seed = flatAccentSeed(darkScheme);
            // A null seed means we could not flatten (transparent seed), so accents keep Monet and
            // are not asserted here; the surface roles still gate regeneration.
            final boolean accentsMatch = seed == null || (
                    accent1Fill == seed
                    && accent1Mid == seed
                    && accent2Mid == seed
                    && accent3Mid == seed
                    && primaryContainer == seed
                    && onPrimary == onAccentColor(seed)
                    && accent1On == onAccentColor(seed));
            // IchthysOS: assert against the seed-derived ladder, not the fixed greys - otherwise
            // every generation would compare unequal and the scheme would regenerate forever.
            final SurfaceLadder ladder = SurfaceLadder.from(darkScheme, pureBlack);
            return background == pitchBlackShade("background", ladder)
                    && surface == pitchBlackShade("surface", ladder)
                    && container == pitchBlackShade("surface_container", ladder)
                    && neutral1Mid == retintNeutral(darkScheme.getNeutral1().getS500(), ladder)
                    && neutral2Mid == retintNeutral(darkScheme.getNeutral2().getS500(), ladder)
                    && accentsMatch;
        }
        return background == darkScheme.getMaterialScheme().getBackground()
                && surface == darkScheme.getMaterialScheme().getSurface()
                && container == darkScheme.getMaterialScheme().getSurfaceContainer()
                && neutral1Mid == darkScheme.getNeutral1().getS500()
                && neutral2Mid == darkScheme.getNeutral2().getS500()
                && accent1Fill == darkScheme.getAccent1().getS100()
                && accent1Mid == darkScheme.getAccent1().getS500()
                && accent2Mid == darkScheme.getAccent2().getS500()
                && accent3Mid == darkScheme.getAccent3().getS500()
                && primaryContainer == darkScheme.getMaterialScheme().getPrimaryContainer()
                && onPrimary == darkScheme.getMaterialScheme().getOnPrimary()
                && accent1On == darkScheme.getAccent1().getS800();
    }

    private void checkCancellation() throws CancellationException {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Operation cancelled");
        }
    }
}
