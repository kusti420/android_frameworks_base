/*
 * Copyright (C) 2026 The IchthysOS Project
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

package com.android.systemui.gradient

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import androidx.annotation.ColorInt
import com.android.internal.graphics.ColorUtils
import com.android.systemui.statusbar.phone.IchthysZigzag

/**
 * IchthysOS: shared two-stop gradient palette backed by crDroid's "Gradient customization" screen
 * (`crDroidSettings/res/xml/gradient_settings.xml`).
 *
 * That screen owns three [Settings.System] keys:
 * * `custom_gradient_color_mode` — [GradientColors.MODE_AUTO] (0, default) or
 *   [GradientColors.MODE_CUSTOM] (1)
 * * `custom_gradient_start_color` — ARGB int, only used in `MODE_CUSTOM` (unset: the accent)
 * * `custom_gradient_end_color` — ARGB int, only used in `MODE_CUSTOM` (unset: the accent)
 *
 * Every per-element toggle on that screen (`qs_tile_gradient`, `qs_brightness_slider_gradient`,
 * `volume_slider_gradient`, ...) shares this one palette; the toggles themselves are read by their
 * own consumers — this file only answers "which two colors should a gradient use right now".
 *
 * Deliberately framework-only (no Compose types) so View-based consumers such as the brightness and
 * volume sliders can feed [toIntArray] straight into a `GradientDrawable`/`LinearGradient`, while
 * the Compose QS tiles use the helpers in `GradientCompose.kt`.
 */
data class GradientPalette(@ColorInt val startColor: Int, @ColorInt val endColor: Int) {

    /** `{start, end}`, ready for `GradientDrawable.setColors` / `LinearGradient`. */
    fun toIntArray(): IntArray = intArrayOf(startColor, endColor)

    /** This palette with both stops set to [alpha] (0..1), preserving hue. */
    fun withAlpha(alpha: Float): GradientPalette {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return GradientPalette(
            ColorUtils.setAlphaComponent(startColor, a),
            ColorUtils.setAlphaComponent(endColor, a),
        )
    }
}

/**
 * An immutable snapshot of the three `custom_gradient_*` keys.
 *
 * Consumers that paint a color which changes often (an animated tile background, a slider that
 * follows the accent) should read the snapshot *once* and call [paletteFor] per frame: the snapshot
 * is what needs observing, the base color is not.
 */
data class GradientConfig(
    val colorMode: Int,
    @ColorInt val startColor: Int,
    @ColorInt val endColor: Int,
) {
    /**
     * The palette for an element that would otherwise be painted [baseColor].
     *
     * In [GradientColors.MODE_AUTO] both stops are derived from [baseColor], so the gradient still
     * follows the wallpaper/Monet accent. In [GradientColors.MODE_CUSTOM] the user's two colors win,
     * but [baseColor]'s alpha is kept so a translucent surface stays translucent.
     */
    fun paletteFor(@ColorInt baseColor: Int): GradientPalette {
        val alpha = Color.alpha(baseColor)
        return if (colorMode == GradientColors.MODE_CUSTOM) {
            GradientPalette(
                ColorUtils.setAlphaComponent(startColor, alpha),
                ColorUtils.setAlphaComponent(endColor, alpha),
            )
        } else {
            GradientColors.autoPalette(baseColor)
        }
    }
}

/** Reads the three `custom_gradient_*` [Settings.System] keys. */
object GradientColors {

    /** Derive the two stops from the color the element would otherwise have been painted. */
    const val MODE_AUTO = 0

    /** Use `custom_gradient_start_color` / `custom_gradient_end_color` verbatim. */
    const val MODE_CUSTOM = 1

    const val KEY_COLOR_MODE: String = Settings.System.CUSTOM_GRADIENT_COLOR_MODE
    const val KEY_START_COLOR: String = Settings.System.CUSTOM_GRADIENT_START_COLOR
    const val KEY_END_COLOR: String = Settings.System.CUSTOM_GRADIENT_END_COLOR

    /** Every key this object reads — register observers on exactly these. */
    @JvmField val KEYS: List<String> = listOf(KEY_COLOR_MODE, KEY_START_COLOR, KEY_END_COLOR)

    /** How far apart the two auto-mode stops are pushed in HSL lightness. */
    private const val AUTO_LIGHTNESS_SPREAD = 0.14f

    /** How far apart the two auto-mode stops are pushed in hue, in degrees. */
    private const val AUTO_HUE_SPREAD = 12f

    /**
     * Reads all three keys. A custom stop the user never picked falls back to the Shoal accent (the
     * one the status bar and the notification dot draw with), so an untouched device in
     * [MODE_CUSTOM] follows the theme instead of a fixed hue.
     *
     * `getIntForUser` with [UserHandle.USER_CURRENT], never the resolver's own user: consumers of
     * this palette run in per-display window contexts whose user is not the foreground user, and
     * getting that wrong returns the default forever with no log output.
     */
    @JvmStatic
    fun config(context: Context): GradientConfig =
        config(context.contentResolver, IchthysZigzag.accent(context))

    /** [config] with the colour to use for an unset custom stop given explicitly. */
    @JvmStatic
    fun config(resolver: ContentResolver, @ColorInt unsetColor: Int): GradientConfig =
        GradientConfig(
            colorMode =
                Settings.System.getIntForUser(
                    resolver,
                    KEY_COLOR_MODE,
                    MODE_AUTO,
                    UserHandle.USER_CURRENT,
                ),
            startColor =
                Settings.System.getIntForUser(
                    resolver,
                    KEY_START_COLOR,
                    unsetColor,
                    UserHandle.USER_CURRENT,
                ),
            endColor =
                Settings.System.getIntForUser(
                    resolver,
                    KEY_END_COLOR,
                    unsetColor,
                    UserHandle.USER_CURRENT,
                ),
        )

    /** One-shot convenience for consumers that do not need to cache a [GradientConfig]. */
    @JvmStatic
    fun palette(context: Context, @ColorInt baseColor: Int): GradientPalette =
        config(context).paletteFor(baseColor)

    /**
     * [MODE_AUTO]: split [baseColor] into a slightly lighter, hue-rotated stop and a slightly
     * darker, counter-rotated one, so the average still reads as [baseColor] and the element keeps
     * its themed identity.
     */
    @JvmStatic
    fun autoPalette(@ColorInt baseColor: Int): GradientPalette {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(baseColor, hsl)
        val alpha = Color.alpha(baseColor)

        val startHsl =
            floatArrayOf(
                (hsl[0] + AUTO_HUE_SPREAD).mod(360f),
                hsl[1],
                (hsl[2] + AUTO_LIGHTNESS_SPREAD).coerceIn(0f, 1f),
            )
        val endHsl =
            floatArrayOf(
                (hsl[0] - AUTO_HUE_SPREAD).mod(360f),
                hsl[1],
                (hsl[2] - AUTO_LIGHTNESS_SPREAD).coerceIn(0f, 1f),
            )
        return GradientPalette(
            ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(startHsl), alpha),
            ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(endHsl), alpha),
        )
    }

    /**
     * Registers [observer] on all of [KEYS]. Callers own [observer] and must pass it to
     * [unregisterObserver] when done.
     */
    @JvmStatic
    fun registerObserver(context: Context, observer: ContentObserver): Unit =
        registerObserver(context.contentResolver, observer)

    @JvmStatic
    fun registerObserver(resolver: ContentResolver, observer: ContentObserver) {
        KEYS.forEach { key ->
            resolver.registerContentObserver(
                Settings.System.getUriFor(key),
                /* notifyForDescendants= */ false,
                observer,
            )
        }
    }

    @JvmStatic
    fun unregisterObserver(context: Context, observer: ContentObserver): Unit =
        unregisterObserver(context.contentResolver, observer)

    @JvmStatic
    fun unregisterObserver(resolver: ContentResolver, observer: ContentObserver) {
        resolver.unregisterContentObserver(observer)
    }

    /** Convenience: a [ContentObserver] on [handler] that runs [onChange] for any gradient key. */
    @JvmStatic
    fun observer(handler: Handler?, onChange: () -> Unit): ContentObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
        }
}
