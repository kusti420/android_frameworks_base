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

package com.android.systemui.statusbar.ichthys.indicator.ui

import android.content.Context
import androidx.annotation.ColorInt
import com.android.settingslib.ichthys.ShoalContrast
import com.android.systemui.privacy.PrivacyConfig
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.res.R
import com.android.systemui.statusbar.ichthys.indicator.data.IchthysIndicatorSettingsRepository
import com.android.systemui.statusbar.phone.IchthysZigzag
import kotlin.math.roundToInt

/**
 * The one place the notification dot, the privacy ring and the cutout-anchored status bar weather
 * take their sizes and colours from.
 *
 * Sizes are dp constants scaled by the display density, not `<dimen>` resources, on purpose:
 * adding a dimen to SystemUI shifts the dimen resource-ID table and crash-loops
 * WindowManager-Shell (found by the cutout-ring spike this package replaces). Where a stock
 * resource already carries the value it is reused instead: the notification dot is exactly the
 * privacy dot's [R.dimen.ongoing_appops_dot_diameter]. Colours are never literals: they come from
 * the theme accent the Shoal status bar uses and from the stock privacy colours.
 */
object IchthysIndicatorTokens {
    /** Space between the notification dot and the privacy dot when both are corner dots. */
    const val NOTIFICATION_DOT_GAP_DP = 3f

    /**
     * Largest burn-in drift of the notification dot on the always-on display. It is the amplitude
     * given to the burn-in helper the AOD clock uses; a pixel or two is plenty for a 6dp dot.
     */
    const val BURN_IN_OFFSET_DP = 2f

    /** Opacity of the notification dot and the privacy ring on the always-on display. */
    const val AOD_ALPHA = 0.6f

    /** Fade duration, the privacy dot's own (PrivacyDotViewController.DURATION). */
    const val FADE_DURATION_MS = 160L

    /** Privacy ring stroke width while statusBar.privacyIndicator.ringWidth is unset. */
    const val PRIVACY_RING_WIDTH_DP = 2

    /** Widest privacy ring the option allows. */
    const val PRIVACY_RING_MAX_WIDTH_DP = IchthysIndicatorSettingsRepository.RING_WIDTH_MAX_DP

    /** Clear space between the camera cutout outline and the ring's stroke. */
    const val PRIVACY_RING_GAP_DP = 1f

    /**
     * Distance from the cutout's edge to the anchored status bar weather: the widest ring the
     * option allows, its gap, and a little air, so the weather never moves with the ring width.
     */
    const val WEATHER_CUTOUT_GAP_DP = PRIVACY_RING_GAP_DP + PRIVACY_RING_MAX_WIDTH_DP + 3f

    fun dpToPx(context: Context, dp: Float): Float = dp * context.resources.displayMetrics.density

    fun dpToPxInt(context: Context, dp: Float): Int = dpToPx(context, dp).roundToInt()

    /** Diameter of the notification dot: the privacy dot's. */
    fun dotDiameter(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.ongoing_appops_dot_diameter)

    /** The theme accent, the one the Shoal status bar draws with. */
    @ColorInt fun accent(context: Context): Int = IchthysZigzag.accent(context)

    /** The single ring colour: what the privacy dot itself would be drawn in. */
    @ColorInt
    fun privacyColor(context: Context, types: Set<PrivacyType>): Int {
        val locationOnly = types.isNotEmpty() && types.all { it == PrivacyType.TYPE_LOCATION }
        return context.getColor(PrivacyConfig.getPrivacyColor(locationOnly))
    }

    /**
     * The ring colour of one privacy type (statusBar.privacyIndicator.colorByType): the stock
     * privacy colour for the camera, the theme accent for the microphone, the stock location
     * colour for location. Anything else (screen casting) keeps the stock privacy colour.
     */
    @ColorInt
    fun privacyTypeColor(context: Context, type: PrivacyType): Int =
        when (type) {
            // IchthysOS: a thin ring around the (black) camera cutout: contrast-guarded against
            // black, so a dark accent's ring is drawn in a lighter tone of itself.
            PrivacyType.TYPE_MICROPHONE ->
                ShoalContrast.forGraphic(accent(context), android.graphics.Color.BLACK)
            PrivacyType.TYPE_LOCATION ->
                context.getColor(R.color.privacy_chip_location_only_background)
            else -> context.getColor(R.color.privacy_chip_background)
        }
}
