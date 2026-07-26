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
    private static final int PB_BLACK           = 0xFF000000; // background / dim / lowest surface
    private static final int PB_SURFACE         = 0xFF0A0A0A; // base surface / container low
    private static final int PB_CONTAINER       = 0xFF141414; // surface container / surface variant
    private static final int PB_ELEVATED        = 0xFF1C1C1C; // elevated container / surface bright
    private static final int PB_ELEVATED_HIGH   = 0xFF242424; // highest container
    private static final int PB_OUTLINE         = 0xFF333333; // subtle border
    private static final int PB_OUTLINE_VARIANT = 0xFF1F1F1F; // very subtle divider

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
            // Dark ACCENT roles are verified in colorsMatchPitchBlackState because pitch black
            // flattens them to the seed; asserting the stock Monet tone here would spuriously fail
            // (and loop-regenerate) while pitch black is on. Light accents are never flattened, so
            // they stay asserted inline against the light Monet scheme.
            return res.getColor(R.color.system_accent1_500_light)
                    == lightScheme.getAccent1().getS500()

                    && res.getColor(R.color.system_accent2_500_light)
                    == lightScheme.getAccent2().getS500()

                    && res.getColor(R.color.system_accent3_500_light)
                    == lightScheme.getAccent3().getS500()

                    && res.getColor(com.android.internal.R.color.system_neutral1_500_dark)
                    == darkScheme.getNeutral1().getS500()
                    && res.getColor(R.color.system_neutral1_500_light)
                    == lightScheme.getNeutral1().getS500()

                    && res.getColor(com.android.internal.R.color.system_neutral2_500_dark)
                    == darkScheme.getNeutral2().getS500()
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
                    && colorsMatchPitchBlackState(res, darkScheme, isPitchBlackEnabled(userId));
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

        // Custom Options "pitch black": when enabled, the dark end of the surface/background
        // roles is forced to pure black so the whole system (QS, Settings and any app that
        // follows the system dark theme) gets a true-black background. Additionally EVERY accent
        // FILL role -- both the legacy tonal band (accent1/2/3 tones 100..700) and the Material
        // dynamic primary/secondary/tertiary roles (incl. their dim/container/fixed variants) --
        // is flattened to the single user-picked seed so the accent is ONE flat color everywhere
        // (QS tiles, notifications, switches, links, buttons) instead of a Monet tonal spread,
        // while the on-accent (on_*) roles flip to contrasting black/white to stay legible.
        // This mirrors the SystemUI QS/shade blackening; the applied "android:dynamic_<user>"
        // overlay generated here is what every app resolves system_* colors from.
        final boolean pitchBlack = isPitchBlackEnabled(userId);

        // Neutral palette
        assignColorsToOverlay(overlay, DynamicColors.getAllNeutralPalette(),
                lightColorScheme, darkColorScheme, pitchBlack);

        // Accent palette
        assignColorsToOverlay(overlay, DynamicColors.getAllAccentPalette(),
                lightColorScheme, darkColorScheme, pitchBlack);

        //Themed Colors
        assignColorsToOverlay(overlay, DynamicColors.getAllDynamicColorsMapped(),
                lightColorScheme, darkColorScheme, pitchBlack);

        //Fixed Colors
        assignColorsToOverlay(overlay, DynamicColors.getFixedColorsMapped(),
                lightColorScheme, darkColorScheme, pitchBlack);

        //Custom Colors
        assignColorsToOverlay(overlay, DynamicColors.getCustomColorsMapped(),
                lightColorScheme, darkColorScheme, pitchBlack);

        return overlay;
    }

    private void assignColorsToOverlay(FabricatedOverlay overlay,
            List<Pair<String, DynamicColor>> colors, ColorScheme lightColorScheme,
            ColorScheme darkColorScheme, boolean pitchBlack) {
        for (Pair<String, DynamicColor> p : colors) {
            String prefix = "android:color/system_" + p.first;
            overlay.setResourceValue(prefix + "_light", TYPE_INT_COLOR_ARGB8,
                    p.second.getArgb(lightColorScheme.getMaterialScheme()), null);
            int darkArgb = p.second.getArgb(darkColorScheme.getMaterialScheme());
            if (pitchBlack) {
                final Integer shade = pitchBlackShade(p.first);
                if (shade != null) {
                    darkArgb = shade;
                } else {
                    final Integer accent = pitchBlackAccent(p.first, darkColorScheme);
                    if (accent != null) {
                        darkArgb = accent;
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
        try {
            final Context ctx = ActivityThread.currentActivityThread().getSystemContext();
            if (ctx == null) {
                return false;
            }
            return Settings.Secure.getIntForUser(ctx.getContentResolver(),
                    "custom_pitch_black", 0, userId) == 1;
        } catch (Exception e) {
            Slog.w(TAG, "Failed to read custom_pitch_black", e);
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
    private static Integer pitchBlackShade(String name) {
        switch (name) {
            // Darkest tier: window background and the lowest/dimmest surfaces stay true black.
            case "background":
            case "surface_dim":
            case "surface_container_lowest":
                return PB_BLACK;
            // Base surface tier.
            case "surface":
            case "surface_container_low":
                return PB_SURFACE;
            // Standard container tier (cards, list items, surface variant).
            case "surface_variant":
            case "surface_container":
                return PB_CONTAINER;
            // Elevated tier (bottom sheets, menus, bright surface).
            case "surface_bright":
            case "surface_container_high":
                return PB_ELEVATED;
            // Highest elevation tier (dialogs, popups).
            case "surface_container_highest":
                return PB_ELEVATED_HIGH;
            // Borders / dividers: subtle greys so edges are visible against black without glowing.
            case "outline":
                return PB_OUTLINE;
            case "outline_variant":
                return PB_OUTLINE_VARIANT;
            // Dark end of the neutral tonal palettes (used as backgrounds by many apps). The Monet
            // engine tints these toward the seed; grade them to match the surface steps above.
            case "neutral1_1000":
            case "neutral2_1000":
                return PB_BLACK;
            case "neutral1_900":
            case "neutral2_900":
                return PB_SURFACE;
            case "neutral1_800":
            case "neutral2_800":
                return PB_CONTAINER;
            case "neutral1_700":
            case "neutral2_700":
                return PB_ELEVATED;
            default:
                return null;
        }
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
     * The legacy accent extreme tones ({@code accentN_0/50} near white, {@code accentN_800/900/1000}
     * near black) are intentionally left to Monet: they read as near-neutral on-accent poles and
     * already contrast, so touching them adds no consistency but risks contrast. Neutral tonal roles
     * are handled by {@link #pitchBlackShade} as surfaces. Returns {@code null} (keep Monet) when the
     * seed is unusable.
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
     */
    private static boolean isOnAccentRole(String name) {
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
        final int r = (seedArgb >> 16) & 0xFF;
        final int g = (seedArgb >> 8) & 0xFF;
        final int b = seedArgb & 0xFF;
        // Cheap, dependency-free BT.601 luma. Light seed -> black on-accent, dark seed -> white.
        final double luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luma > 0.5 ? 0xFF000000 : 0xFFFFFFFF;
    }

    /**
     * Returns whether the dark surfaces currently applied to {@code res} match the given
     * pitch-black state. When pitch black is on, a representative set of surface roles must equal
     * the graded near-black palette; when it is off, they must equal the stock Monet dark scheme.
     * Used by {@link #isColorSchemeApplied} so toggling custom_pitch_black forces a regeneration
     * and the transition round-trips in both directions.
     */
    private boolean colorsMatchPitchBlackState(Resources res, ColorScheme darkScheme,
            boolean pitchBlack) {
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
        // Representative ON-accent role that must stay contrasting against the seed.
        final int onPrimary = res.getColor(android.R.color.system_on_primary_dark);

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
                    && onPrimary == onAccentColor(seed));
            return background == PB_BLACK
                    && surface == PB_SURFACE
                    && container == PB_CONTAINER
                    && accentsMatch;
        }
        return background == darkScheme.getMaterialScheme().getBackground()
                && surface == darkScheme.getMaterialScheme().getSurface()
                && container == darkScheme.getMaterialScheme().getSurfaceContainer()
                && accent1Fill == darkScheme.getAccent1().getS100()
                && accent1Mid == darkScheme.getAccent1().getS500()
                && accent2Mid == darkScheme.getAccent2().getS500()
                && accent3Mid == darkScheme.getAccent3().getS500()
                && primaryContainer == darkScheme.getMaterialScheme().getPrimaryContainer()
                && onPrimary == darkScheme.getMaterialScheme().getOnPrimary();
    }

    private void checkCancellation() throws CancellationException {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Operation cancelled");
        }
    }
}
