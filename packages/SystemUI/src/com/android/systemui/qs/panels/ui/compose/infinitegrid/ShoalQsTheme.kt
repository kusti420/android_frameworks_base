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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
 */
object ShoalQsColors {
    /** The ground the panel sits on. */
    val Ink = Color(0xFF07090B)
    /** Recessed track surface (brightness track, footer buttons). */
    val Track = Color(0xFF0C1013)
    /** 1 dp hairline around recessed surfaces. */
    val Hairline = Color(0xFF20282D)
    /** Filled "off" surface for tiles. */
    val TileOff = Color(0xFF172125)
    /** Foreground on off tiles. */
    val OnTileOff = Color(0xFFF2F4F1)
    /** Primary label text. */
    val Label = Color(0xFFC9CECA)
    /** Secondary / numeric text. */
    val Muted = Color(0xFF8B938F)
    /** Inactive pager dot. */
    val DotInactive = Color(0xFF2A3136)
    /** Fallback when no accent can be resolved (Koi). */
    val KoiAccent = Color(0xFFFF6B3D)

    /** The dark accent tint of the filled part of the brightness track (#3a2418 for koi). */
    fun fillTint(accent: Color): Color = lerp(Track, accent, 0.20f)

    /** The darker tint of the part of the track below the minimum floor (#1f1511 for koi). */
    fun belowMinTint(accent: Color): Color = lerp(Track, accent, 0.08f)
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

private fun readShoal(context: Context): Boolean =
    "shoal" ==
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
