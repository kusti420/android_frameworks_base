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
import static android.util.TypedValue.TYPE_INT_DEC;

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

import java.util.List;
import java.util.Map;
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

    // IchthysOS "Shoal": the Settings.Secure keys ichthys.nix writes (see IchthysOptions). The
    // palette itself lives in ShoalPalette; this class only reads the inputs and writes the output.
    //
    // Mode: ichthys_theme_surface = "stock" | "hairline" | "tonal". Unset means the option's
    // default, hairline: ichthys.nix never writes a value equal to the default, so "unset" is the
    // normal state of a koi device. The old crDroid Theme Engine switch (theme_engine_style_mode,
    // Monet/Abyss) is deliberately NOT consulted any more: honouring an explicit 0 there would turn
    // Shoal off for anyone who once visited the Theme Engine, and an applied config could never
    // turn it back on (writing "hairline" is a no-op because it is the default). Plain Monet is
    // theme.surface = "stock".
    static final String KEY_SURFACE = "ichthys_theme_surface";
    /** "tinted" (default) | "neutral". */
    static final String KEY_NEUTRAL = "ichthys_theme_neutral";
    /** Surface levels 2..5 (default 3). */
    static final String KEY_SURFACES = "ichthys_theme_surfaces";
    /** 1 = ink is pure #000 in dark. */
    static final String KEY_PITCH_BLACK = "custom_pitch_black";

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
            // Light legacy tonal tokens are never rewritten by Shoal, so they track the Monet
            // seed/style/contrast in every mode.
            final boolean monetLight = res.getColor(R.color.system_accent1_500_light)
                    == lightScheme.getAccent1().getS500()
                    && res.getColor(R.color.system_accent2_500_light)
                    == lightScheme.getAccent2().getS500()
                    && res.getColor(R.color.system_accent3_500_light)
                    == lightScheme.getAccent3().getS500()
                    && res.getColor(R.color.system_neutral1_500_light)
                    == lightScheme.getNeutral1().getS500()
                    && res.getColor(R.color.system_neutral2_500_light)
                    == lightScheme.getNeutral2().getS500();
            if (!monetLight) {
                return false;
            }

            // The Shoal inputs (settings + seed) are fingerprinted into ichthys_palette_hash, so
            // flipping theme.surface / neutral / surfaces / pitchBlack regenerates exactly once
            // and a matching overlay is never regenerated in a loop.
            final ShoalPalette[] shoal = createShoalPalettes(userId, darkScheme);
            if (res.getInteger(R.integer.ichthys_palette_hash)
                    != ShoalPalette.combinedHash(shoal[0], shoal[1])) {
                return false;
            }

            if (shoal[0].isStock()) {
                return res.getColor(android.R.color.system_outline_variant_dark)
                        == darkScheme.getMaterialScheme().getOutlineVariant()
                        && res.getColor(android.R.color.system_outline_variant_light)
                        == lightScheme.getMaterialScheme().getOutlineVariant()
                        && res.getColor(android.R.color.system_primary_container_light)
                        == lightScheme.getMaterialScheme().getPrimaryContainer()
                        && res.getColor(android.R.color.system_background_dark)
                        == darkScheme.getMaterialScheme().getBackground()
                        && res.getColor(android.R.color.system_surface_container_dark)
                        == darkScheme.getMaterialScheme().getSurfaceContainer()
                        && res.getColor(android.R.color.system_on_primary_dark)
                        == darkScheme.getMaterialScheme().getOnPrimary()
                        && res.getColor(R.color.system_neutral1_500_dark)
                        == darkScheme.getNeutral1().getS500()
                        && res.getColor(R.color.system_accent1_100_dark)
                        == darkScheme.getAccent1().getS100()
                        && res.getColor(R.color.system_accent1_800_dark)
                        == darkScheme.getAccent1().getS800();
            }

            // Shoal: a representative role per family, plus the one legacy value that depends on
            // Monet (the retinted neutral) since the hash cannot see Monet's tones.
            final Map<String, Integer> dark = shoal[0].roles();
            final Map<String, Integer> light = shoal[1].roles();
            final Integer neutral1Mid = shoal[0].legacy("neutral1_500",
                    darkScheme.getNeutral1().getS500());
            return res.getColor(android.R.color.system_background_dark) == dark.get("background")
                    && res.getColor(android.R.color.system_primary_container_dark)
                    == dark.get("primary_container")
                    && res.getColor(android.R.color.system_surface_container_low_light)
                    == light.get("surface_container_low")
                    && res.getColor(R.color.ichthys_card_dark)
                    == shoal[0].tokens().get(ShoalPalette.CARD)
                    && (neutral1Mid == null
                    || res.getColor(R.color.system_neutral1_500_dark) == neutral1Mid);
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
     * <p>
     * IchthysOS: besides the Monet roles this also carries the Shoal palette (see
     * {@link ShoalPalette}): unless theme.surface is "stock" the Material system roles take the
     * Shoal ramp, and the {@code ichthys_*} colour tokens, {@code ichthys_surface_style},
     * {@code ichthys_surface_levels} and {@code ichthys_palette_hash} are written into the same
     * {@code android:dynamic_<user>} overlay, so every app resolves one consistent palette. In
     * stock mode only the three integers are written; the colour tokens keep their resource
     * defaults, which alias the (Monet) Material roles.
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

        final ShoalPalette[] shoal = createShoalPalettes(userId, darkColorScheme);
        final ShoalPalette dark = shoal[0];
        final ShoalPalette light = shoal[1];

        // Neutral palette
        assignColorsToOverlay(overlay, DynamicColors.getAllNeutralPalette(),
                lightColorScheme, darkColorScheme, light, dark);

        // Accent palette
        assignColorsToOverlay(overlay, DynamicColors.getAllAccentPalette(),
                lightColorScheme, darkColorScheme, light, dark);

        //Themed Colors
        assignColorsToOverlay(overlay, DynamicColors.getAllDynamicColorsMapped(),
                lightColorScheme, darkColorScheme, light, dark);

        //Fixed Colors
        assignColorsToOverlay(overlay, DynamicColors.getFixedColorsMapped(),
                lightColorScheme, darkColorScheme, light, dark);

        //Custom Colors
        assignColorsToOverlay(overlay, DynamicColors.getCustomColorsMapped(),
                lightColorScheme, darkColorScheme, light, dark);

        // IchthysOS Shoal tokens.
        for (String token : ShoalPalette.TOKENS) {
            final Integer d = dark.tokens().get(token);
            final Integer l = light.tokens().get(token);
            if (d != null) {
                overlay.setResourceValue("android:color/ichthys_" + token + "_dark",
                        TYPE_INT_COLOR_ARGB8, d, null);
            }
            if (l != null) {
                overlay.setResourceValue("android:color/ichthys_" + token + "_light",
                        TYPE_INT_COLOR_ARGB8, l, null);
            }
        }
        overlay.setResourceValue("android:integer/ichthys_surface_style", TYPE_INT_DEC,
                dark.surface().ordinal(), null);
        overlay.setResourceValue("android:integer/ichthys_surface_levels", TYPE_INT_DEC,
                dark.levels(), null);
        overlay.setResourceValue("android:integer/ichthys_palette_hash", TYPE_INT_DEC,
                ShoalPalette.combinedHash(dark, light), null);

        return overlay;
    }

    private void assignColorsToOverlay(FabricatedOverlay overlay,
            List<Pair<String, DynamicColor>> colors, ColorScheme lightColorScheme,
            ColorScheme darkColorScheme, ShoalPalette light, ShoalPalette dark) {
        for (Pair<String, DynamicColor> p : colors) {
            String prefix = "android:color/system_" + p.first;
            overlay.setResourceValue(prefix + "_light", TYPE_INT_COLOR_ARGB8,
                    shoalValue(light, p.first,
                            p.second.getArgb(lightColorScheme.getMaterialScheme())), null);
            overlay.setResourceValue(prefix + "_dark", TYPE_INT_COLOR_ARGB8,
                    shoalValue(dark, p.first,
                            p.second.getArgb(darkColorScheme.getMaterialScheme())), null);
        }
    }

    /** The Shoal value for a role (Material role, then legacy tonal token), else Monet's. */
    private static int shoalValue(ShoalPalette palette, String name, int monetArgb) {
        final Integer role = palette.roles().get(name);
        if (role != null) {
            return role;
        }
        final Integer legacy = palette.legacy(name, monetArgb);
        return legacy != null ? legacy : monetArgb;
    }

    /** The dark and light Shoal palettes for {@code userId}'s current settings. */
    private ShoalPalette[] createShoalPalettes(int userId, ColorScheme darkColorScheme) {
        final ShoalPalette.Surface surface =
                ShoalPalette.Surface.fromSetting(readSecureString(userId, KEY_SURFACE));
        final boolean tinted = !"neutral".equals(readSecureString(userId, KEY_NEUTRAL));
        final int levels = readSecureInt(userId, KEY_SURFACES, ShoalPalette.DEFAULT_LEVELS);
        final boolean pitchBlack = readSecureInt(userId, KEY_PITCH_BLACK, 0) == 1;
        final int seed = shoalSeed(darkColorScheme);
        return new ShoalPalette[] {
                ShoalPalette.create(seed, surface, tinted, levels, pitchBlack, true),
                ShoalPalette.create(seed, surface, tinted, levels, pitchBlack, false),
        };
    }

    /**
     * The accent seed: the colour the user picked (theme_customization_overlay_packages
     * system_palette, fed into the ColorScheme), i.e. the same seed SystemUI's QS tiles render.
     * When that is transparent (no usable pick), the Monet primary.
     */
    private static int shoalSeed(ColorScheme darkColorScheme) {
        final int seed = darkColorScheme.getSeed();
        if ((seed >>> 24) != 0) {
            return 0xFF000000 | (seed & 0x00FFFFFF);
        }
        return 0xFF000000 | (darkColorScheme.getMaterialScheme().getPrimary() & 0x00FFFFFF);
    }

    @Nullable
    private static String readSecureString(int userId, String key) {
        try {
            final Context ctx = ActivityThread.currentActivityThread().getSystemContext();
            if (ctx == null) {
                return null;
            }
            return Settings.Secure.getStringForUser(ctx.getContentResolver(), key, userId);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to read " + key, e);
            return null;
        }
    }

    private static int readSecureInt(int userId, String key, int def) {
        final String s = readSecureString(userId, key);
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void checkCancellation() throws CancellationException {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Operation cancelled");
        }
    }
}
