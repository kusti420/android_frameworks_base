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

package com.android.systemui.ichthys.notes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import com.android.systemui.ichthys.notes.IchthysNotesRepository
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ShoalQsColors
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalAccent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsEnabled
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsFonts
import com.android.systemui.qs.panels.ui.compose.infinitegrid.shoalGraphic
import com.android.systemui.qs.panels.ui.compose.infinitegrid.shoalOnFill

/**
 * IchthysOS: the tokens the notes page draws with: Shoal's when that style is on, Material's for
 * the grid style. No colour or font is written here; everything comes from the theme.
 */
class IchthysNotesTokens(
    val ink: Color,
    val card: Color,
    val hairline: Color,
    val text: Color,
    val body: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    /** The accent as a glyph or thin mark on the panel: contrast-guarded against the ground. */
    val accentMark: Color,
    val dotInactive: Color,
    val display: FontFamily,
    val mono: FontFamily,
    val bodyFont: FontFamily,
    /** Tints 1..[IchthysNotesRepository.TINT_COUNT], derived from the accent (index 0 unused). */
    val tints: List<Color>,
) {
    /** The colour a note's marks use: its tint, or the accent. */
    fun markColor(tint: Int): Color =
        tints.getOrNull(tint)?.takeIf { tint > 0 } ?: accentMark

    fun fillColor(tint: Int): Color = tints.getOrNull(tint)?.takeIf { tint > 0 } ?: accent
}

/**
 * IchthysOS: five tints spun from the accent's hue (72° apart, the accent itself being tint 1),
 * each pushed to read on the ground. Users who change the accent get a matching set.
 */
private fun deriveTints(accent: Color, ground: Color): List<Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    val saturation = hsv[1].coerceAtLeast(0.55f)
    val value = hsv[2].coerceAtLeast(0.75f)
    return buildList {
        add(Color.Unspecified)
        for (i in 0 until IchthysNotesRepository.TINT_COUNT) {
            val hue = (hsv[0] + 72f * i) % 360f
            val raw = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
            add(raw.shoalGraphic(ground))
        }
    }
}

@Composable
fun rememberIchthysNotesTokens(): IchthysNotesTokens {
    val shoal = rememberShoalQsEnabled()
    val accent = rememberShoalAccent()
    val fonts = rememberShoalQsFonts()
    val scheme = MaterialTheme.colorScheme
    // The Shoal colours are snapshot state (they follow theme changes): read them every
    // composition; only the tint derivation is worth remembering.
    val ground = if (shoal) ShoalQsColors.Ink else scheme.surface
    val seed = if (shoal) accent else scheme.primary
    val tints = remember(seed, ground) { deriveTints(seed, ground) }
    return run {
        if (shoal) {
            IchthysNotesTokens(
                ink = ShoalQsColors.Ink,
                card = ShoalQsColors.Track,
                hairline = ShoalQsColors.Hairline,
                text = ShoalQsColors.OnTileOff,
                body = ShoalQsColors.Label,
                muted = ShoalQsColors.Muted,
                accent = accent,
                onAccent = shoalOnFill(accent),
                accentMark = accent.shoalGraphic(),
                dotInactive = ShoalQsColors.DotInactive,
                display = fonts.display,
                mono = fonts.mono,
                bodyFont = fonts.text,
                tints = tints,
            )
        } else {
            IchthysNotesTokens(
                ink = scheme.surface,
                card = scheme.surfaceContainer,
                hairline = scheme.outlineVariant,
                text = scheme.onSurface,
                body = scheme.onSurface,
                muted = scheme.onSurfaceVariant,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                accentMark = scheme.primary,
                dotInactive = scheme.onSurfaceVariant.copy(alpha = .5f),
                display = FontFamily.Default,
                mono = FontFamily.Monospace,
                bodyFont = FontFamily.Default,
                tints = tints,
            )
        }
    }
}
