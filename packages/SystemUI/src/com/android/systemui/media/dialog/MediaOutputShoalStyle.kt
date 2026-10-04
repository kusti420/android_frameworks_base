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

package com.android.systemui.media.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import com.android.systemui.shade.ui.ShoalShade

/**
 * IchthysOS "Shoal" look of the output switcher dialog: a card panel with a hairline outline and
 * the theme faces, coloured by [MediaOutputColorSchemeShoal] instead of the artwork. Everything is
 * read from the framework tokens (com.android.internal.R.*.ichthys_*, values/ichthys_tokens.xml);
 * the stock look is untouched while Shoal is off.
 */
internal object MediaOutputShoalStyle {
    const val WEIGHT_NORMAL = 400
    const val WEIGHT_MEDIUM = 500
    const val WEIGHT_SEMI_BOLD = 600

    /** Section captions ("Speakers & displays"), as the section headers of the shade. */
    const val CAPTION_TEXT_SIZE_SP = 12f
    const val CAPTION_LETTER_SPACING = 0.09f

    /** Whether the dialog takes the Shoal look: the switch of the Shoal shade, see [ShoalShade]. */
    fun isEnabled(context: Context): Boolean = ShoalShade.isActive(context)

    /** The text face (theme.font.text) at [weight]. */
    fun text(context: Context, weight: Int): Typeface =
        ShoalShade.face(context.getString(com.android.internal.R.string.ichthys_font_text), weight)

    /** The mono face (theme.font.mono) at [weight], for captions and numerals. */
    fun mono(context: Context, weight: Int): Typeface =
        ShoalShade.face(context.getString(com.android.internal.R.string.ichthys_font_mono), weight)

    /** Corner radius of the panel (theme.shape.radius), in pixels. */
    fun radius(context: Context): Float =
        context.resources.getDimension(com.android.internal.R.dimen.ichthys_radius)

    /** Corner radius of the small elements inside the panel, in pixels. */
    fun radiusSmall(context: Context): Int =
        context.resources.getDimensionPixelSize(com.android.internal.R.dimen.ichthys_radius_small)

    /** The hairline around the panel: a stroke of [color] with nothing inside. */
    fun outline(context: Context, color: Int, radius: Float): Drawable =
        GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(
                context.resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.ichthys_hairline_width
                ),
                color,
            )
        }
}
