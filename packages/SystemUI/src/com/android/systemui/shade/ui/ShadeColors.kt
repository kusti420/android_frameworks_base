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

package com.android.systemui.shade.ui

import android.content.Context
import android.provider.Settings
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R

object ShadeColors {
    /**
     * IchthysOS: true when the Abyss palette is selected in the Theme Engine
     * (Settings.Secure theme_engine_style_mode: 0 = Monet, 1 = Abyss). Was custom_pitch_black,
     * which tied the shade's whole appearance to the pure-black switch; that switch now only
     * drives the bottom rung of the surface ladder, which these colours read through.
     */
    private fun isPitchBlack(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, "theme_engine_style_mode", 0) == 1 &&
            (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * IchthysOS: pitch black no longer means flat #000 for every shade surface. ThemeOverlayHelper
     * derives a tinted near-black ladder from the user's accent seed (black -> dark blue ->
     * purple-ish blue) and publishes it on the system_* dark roles, so the shade reads those
     * instead of hardcoding black and stays consistent with Settings, QS and every app that
     * follows the system dark theme. These are deliberately the *_dark roles: pitch black is a
     * dark-only mode, and the shade must not pick up light-theme surfaces when the user has the
     * system in light mode.
     */
    private fun ladder(context: Context, colorRes: Int): Int =
        context.resources.getColor(colorRes, context.theme)

    /** Deepest rung - true black, for the scrim that sits behind everything. */
    private fun pitchBlackBackground(context: Context): Int =
        ladder(context, android.R.color.system_background_dark)

    /** Base panel surface - the shade/QS panel itself. */
    private fun pitchBlackPanel(context: Context): Int =
        ladder(context, android.R.color.system_surface_dark)

    /** Container rung - the rounded rect notifications sit on, one step above the panel. */
    private fun pitchBlackContainer(context: Context): Int =
        ladder(context, android.R.color.system_surface_container_dark)

    /**
     * Calculate notification shade panel color.
     *
     * @param context Context to resolve colors.
     * @param blurSupported Whether blur is enabled (can be off due to battery saver)
     * @param withScrim Whether to composite a scrim when blur is enabled (used by legacy shade).
     * @return color for the shade panel.
     */
    @JvmStatic
    fun shadePanel(context: Context, blurSupported: Boolean, withScrim: Boolean): Int {
        if (ShoalShade.isInk(context)) return ShoalShade.inkColor(context)
        if (isPitchBlack(context)) return pitchBlackPanel(context)
        return if (blurSupported) {
            if (withScrim) {
                ColorUtils.compositeColors(
                    shadePanelStandard(context),
                    shadePanelScrimBehind(context),
                )
            } else {
                shadePanelStandard(context)
            }
        } else {
            shadePanelFallback(context)
        }
    }

    @JvmStatic
    fun notificationScrim(context: Context, blurSupported: Boolean): Int {
        if (ShoalShade.isInk(context)) return ShoalShade.inkColor(context)
        if (isPitchBlack(context)) return pitchBlackPanel(context)
        return if (blurSupported) {
            notificationScrimStandard(context)
        } else {
            notificationScrimFallback(context)
        }
    }

    @JvmStatic
    fun shadePanelScrimBehind(context: Context): Int {
        if (ShoalShade.isInk(context)) return ShoalShade.inkColor(context)
        if (isPitchBlack(context)) return pitchBlackBackground(context)
        return context.resources.getColor(
            com.android.internal.R.color.shade_panel_scrim,
            context.theme,
        )
    }

    /**
     * Background color of the rounded cornered rect behind Notifications in Single- or SplitShade.
     */
    @JvmStatic
    fun classicShadeNotificationScrimBg(
        context: Context,
        blurSupported: Boolean,
        composited: Boolean,
    ): Int {
        // IchthysOS "Shoal": one flat ink ground; the cards carry the hairlines.
        if (ShoalShade.isInk(context)) return ShoalShade.inkColor(context)
        if (isPitchBlack(context)) return pitchBlackContainer(context)
        val surfaceEffect0Color =
            context.resources.getColor(
                com.android.internal.R.color.customColorSurfaceEffect0,
                context.theme,
            )
        if (!composited) {
            // SplitShade (avoid adding the shade panel with scrim)
            return surfaceEffect0Color
        }
        // SingleShade
        val shadePanelWithScrim = shadePanel(context, blurSupported, withScrim = true)
        return ColorUtils.compositeColors(surfaceEffect0Color, shadePanelWithScrim)
    }

    @JvmStatic
    private fun shadePanelStandard(context: Context): Int {
        val layerAbove =
            context.resources.getColor(com.android.internal.R.color.shade_panel_fg, context.theme)
        val layerBelow =
            context.resources.getColor(com.android.internal.R.color.shade_panel_bg, context.theme)
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    @JvmStatic
    private fun shadePanelFallback(context: Context): Int {
        return context.getColor(R.color.shade_panel_fallback)
    }

    @JvmStatic
    private fun notificationScrimStandard(context: Context): Int {
        return ColorUtils.setAlphaComponent(
            context.getColor(R.color.notification_scrim_base),
            (0.5f * 255).toInt(),
        )
    }

    @JvmStatic
    private fun notificationScrimFallback(context: Context): Int {
        return context.getColor(R.color.notification_scrim_fallback)
    }
}
