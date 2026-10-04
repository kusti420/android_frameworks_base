/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui

import android.content.Context
import android.graphics.Typeface

/**
 * String tokens for the different GSF type roles.
 *
 * IchthysOS: the GSF "variable-<role>" font families are not installed here (Typeface.create of
 * one falls back to the default sans), so these are role names, not family names: resolve them
 * with [typeface].
 */
object FontStyles {
    // baseline
    const val GSF_DISPLAY_LARGE = "display-large"
    const val GSF_DISPLAY_MEDIUM = "display-medium"
    const val GSF_DISPLAY_SMALL = "display-small"

    const val GSF_HEADLINE_LARGE = "headline-large"
    const val GSF_HEADLINE_MEDIUM = "headline-medium"
    const val GSF_HEADLINE_SMALL = "headline-small"

    const val GSF_TITLE_LARGE = "title-large"
    const val GSF_TITLE_MEDIUM = "title-medium"
    const val GSF_TITLE_SMALL = "title-small"

    const val GSF_LABEL_LARGE = "label-large"
    const val GSF_LABEL_MEDIUM = "label-medium"
    const val GSF_LABEL_SMALL = "label-small"

    const val GSF_BODY_LARGE = "body-large"
    const val GSF_BODY_MEDIUM = "body-medium"
    const val GSF_BODY_SMALL = "body-small"

    // emphasized
    const val GSF_DISPLAY_LARGE_EMPHASIZED = "display-large-emphasized"
    const val GSF_DISPLAY_MEDIUM_EMPHASIZED = "display-medium-emphasized"
    const val GSF_DISPLAY_SMALL_EMPHASIZED = "display-small-emphasized"
    const val GSF_DISPLAY_SMALL_EMPHASIZED_LIGHT = "display-small-emphasized-light"

    const val GSF_HEADLINE_LARGE_EMPHASIZED = "headline-large-emphasized"
    const val GSF_HEADLINE_MEDIUM_EMPHASIZED = "headline-medium-emphasized"
    const val GSF_HEADLINE_SMALL_EMPHASIZED = "headline-small-emphasized"

    const val GSF_TITLE_LARGE_EMPHASIZED = "title-large-emphasized"
    const val GSF_TITLE_MEDIUM_EMPHASIZED = "title-medium-emphasized"
    const val GSF_TITLE_SMALL_EMPHASIZED = "title-small-emphasized"

    const val GSF_LABEL_LARGE_EMPHASIZED = "label-large-emphasized"
    const val GSF_LABEL_MEDIUM_EMPHASIZED = "label-medium-emphasized"
    const val GSF_LABEL_SMALL_EMPHASIZED = "label-small-emphasized"

    const val GSF_BODY_LARGE_EMPHASIZED = "body-large-emphasized"
    const val GSF_BODY_MEDIUM_EMPHASIZED = "body-medium-emphasized"
    const val GSF_BODY_SMALL_EMPHASIZED = "body-small-emphasized"

    private const val WEIGHT_MEDIUM = 500

    /**
     * The typeface of one of the roles above, split as the Compose type scale is
     * (TypeScaleTokens): display, headline and title-large roles take config_headlineFontFamily,
     * the other title, label and body roles config_bodyFontFamily (both follow the theme), and the
     * emphasized roles the medium weight of that family.
     */
    @JvmStatic
    @JvmOverloads
    fun typeface(context: Context, role: String, style: Int = Typeface.NORMAL): Typeface {
        val headline =
            role.startsWith("display") ||
                role.startsWith("headline") ||
                role.startsWith("title-large")
        val family =
            context.getString(
                if (headline) com.android.internal.R.string.config_headlineFontFamily
                else com.android.internal.R.string.config_bodyFontFamily
            )
        val base = Typeface.create(family, style)
        if (!role.endsWith("-emphasized")) return base
        return Typeface.create(base, WEIGHT_MEDIUM, (style and Typeface.ITALIC) != 0)
    }
}
