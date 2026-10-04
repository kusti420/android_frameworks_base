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

@file:OptIn(ExperimentalTextApi::class)

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject

/**
 * IchthysOS "Shoal" quick settings palette and type. Everything here only applies while
 * Settings.Secure ichthys_qs_style = "shoal" (see [rememberShoalQsEnabled]).
 *
 * The colours are the framework ichthys_* tokens, which the theme engine fills from ichthys.nix
 * for dark and for light. They are read once and again whenever the theme or the night mode
 * changes ([update], called by [ShoalQsColorsStartable]), and kept in snapshot state, so
 * compositions and draw scopes that read them follow the theme without holding a context.
 */
object ShoalQsColors {
    private var palette by mutableStateOf(Palette())

    /** The ground the panel sits on. */
    val Ink: Color
        get() = palette.ink

    /** Recessed track surface (footer buttons, edit-mode grid). */
    val Track: Color
        get() = palette.track

    /** 1 dp hairline around recessed surfaces. */
    val Hairline: Color
        get() = palette.hairline

    /** Filled "off" surface for tiles. */
    val TileOff: Color
        get() = palette.tileOff

    /** Foreground on off tiles. */
    val OnTileOff: Color
        get() = palette.onTileOff

    /** Foreground on the accent (active tiles). */
    val OnAccent: Color
        get() = palette.onAccent

    /** Primary label text. */
    val Label: Color
        get() = palette.label

    /** Secondary / numeric text. */
    val Muted: Color
        get() = palette.muted

    /** Inactive pager dot. */
    val DotInactive: Color
        get() = palette.dotInactive

    /** Fallback when no accent can be resolved (Koi). */
    val KoiAccent = Color(0xFFFF6B3D)

    /** Re-reads the tokens; call on the main thread when the theme or night mode changed. */
    fun update(context: Context) {
        palette =
            Palette(
                ink = context.token(com.android.internal.R.color.ichthys_ink),
                track = context.token(com.android.internal.R.color.ichthys_card),
                hairline = context.token(com.android.internal.R.color.ichthys_hairline),
                tileOff = context.token(com.android.internal.R.color.ichthys_tile),
                onTileOff = context.token(com.android.internal.R.color.ichthys_text),
                onAccent = context.token(com.android.internal.R.color.ichthys_on_accent),
                label = context.token(com.android.internal.R.color.ichthys_body),
                muted = context.token(com.android.internal.R.color.ichthys_muted),
                dotInactive = context.token(com.android.internal.R.color.ichthys_outline),
            )
    }

    private fun Context.token(id: Int): Color = Color(getColor(id))

    /**
     * The defaults are the dark koi ramp: what shows until the first [update], which runs when
     * SystemUI starts, before any quick settings composition.
     */
    private data class Palette(
        val ink: Color = Color(0xFF07090B),
        val track: Color = Color(0xFF0C1013),
        val hairline: Color = Color(0xFF20282D),
        val tileOff: Color = Color(0xFF172125),
        val onTileOff: Color = Color(0xFFF2F4F1),
        val onAccent: Color = Color(0xFF07090B),
        val label: Color = Color(0xFFC9CECA),
        val muted: Color = Color(0xFF8B938F),
        val dotInactive: Color = Color(0xFF2A3136),
    )
}

// region style setting

internal object ShoalQsSettings {
    const val STYLE_SETTING = "ichthys_qs_style"
    const val FONT_DISPLAY = "ichthys_theme_font_display"
    const val FONT_MONO = "ichthys_theme_font_mono"
    const val FONT_TEXT = "ichthys_theme_font_text"
    const val SYSTEM_PALETTE = "android.theme.customization.system_palette"
}

/** Live value of the QS style setting for the current user. */
@Composable
fun rememberShoalQsEnabled(): Boolean {
    val context = LocalContext.current
    val enabled by
        produceState(initialValue = readShoal(context), context) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = readShoal(context)
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(ShoalQsSettings.STYLE_SETTING),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            value = readShoal(context)
            awaitDispose { context.contentResolver.unregisterContentObserver(observer) }
        }
    return enabled
}

// Unset means the option's default (quickSettings.style = "shoal"); only "grid" is off.
private fun readShoal(context: Context): Boolean =
    "grid" !=
        Settings.Secure.getStringForUser(
            context.contentResolver,
            ShoalQsSettings.STYLE_SETTING,
            UserHandle.USER_CURRENT,
        )

// endregion

// region accent

/**
 * The theme accent. The ichthys theme engine writes the accent as the Monet seed
 * (`android.theme.customization.system_palette` in theme_customization_overlay_packages); when one
 * is set we use that exact colour (koi #ff6b3d) rather than the Monet tone derived from it. With a
 * wallpaper-derived palette there is no seed, so we fall back to system_accent1_400, the tone the
 * rest of the Shoal surfaces (status bar, notification footer) use.
 */
@Composable
fun rememberShoalAccent(): Color {
    val context = LocalContext.current
    val accent by
        produceState(initialValue = readAccent(context), context) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = readAccent(context)
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            value = readAccent(context)
            awaitDispose { context.contentResolver.unregisterContentObserver(observer) }
        }
    return accent
}

private fun readAccent(context: Context): Color {
    val seed =
        try {
            val json =
                Settings.Secure.getStringForUser(
                    context.contentResolver,
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    UserHandle.USER_CURRENT,
                )
            if (json.isNullOrEmpty()) null
            else {
                val raw = JSONObject(json).optString(ShoalQsSettings.SYSTEM_PALETTE, "")
                if (raw.isEmpty()) null
                else {
                    val hex = raw.removePrefix("#")
                    val argb = java.lang.Long.parseLong(hex, 16)
                    if (hex.length == 6) Color(0xFF000000L or argb)
                    else if (hex.length == 8) Color(argb)
                    else null
                }
            }
        } catch (e: Exception) {
            null
        }
    if (seed != null) return seed
    return try {
        Color(context.getColor(android.R.color.system_accent1_400))
    } catch (e: Exception) {
        ShoalQsColors.KoiAccent
    }
}

// endregion

// region type

/** The Shoal type families: display (Syne), mono (JetBrains Mono) and text (Instrument Sans). */
class ShoalQsFonts(val display: FontFamily, val mono: FontFamily, val text: FontFamily)

@Composable
fun rememberShoalQsFonts(): ShoalQsFonts {
    val context = LocalContext.current
    return remember(context) {
        val cr = context.contentResolver
        // An unset key means the Shoal default family (as ShoalShade does), not the system face:
        // the ichthys.nix defaults are not always written to Settings.
        fun family(key: String, default: String): FontFamily {
            val stored = Settings.Secure.getStringForUser(cr, key, UserHandle.USER_CURRENT)
            val name = if (stored.isNullOrEmpty()) default else stored
            val device = DeviceFontFamilyName(name)
            return FontFamily(
                Font(device, weight = FontWeight.Normal),
                Font(device, weight = FontWeight.Medium),
                Font(device, weight = FontWeight.SemiBold),
                Font(device, weight = FontWeight.Bold),
                Font(device, weight = FontWeight.ExtraBold),
            )
        }
        ShoalQsFonts(
            display = family(ShoalQsSettings.FONT_DISPLAY, "syne"),
            mono = family(ShoalQsSettings.FONT_MONO, "jetbrains-mono"),
            text = family(ShoalQsSettings.FONT_TEXT, "instrument-sans"),
        )
    }
}

// endregion
