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

package com.android.systemui.statusbar

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.android.systemui.statusbar.ichthys.indicator.ui.IchthysIndicatorTokens

/**
 * IchthysOS: locks the status bar weather in place right after the camera cutout
 * (statusBar.weather.anchorToCutout).
 *
 * The weather stays what it was, the first child of the end-side content row, so it keeps the
 * row's visibility, alpha and system-event animations, its vertical centring and its dark icon
 * tinting. That row is wrapped around its children and hugs the end of the bar, so its start (and
 * the weather with it) moves whenever an icon appears or disappears. Two things undo that:
 * - the weather's start margin is the distance from the end-side container's start edge to the
 *   anchor: the live cutout's edge plus [IchthysIndicatorTokens.WEATHER_CUTOUT_GAP_DP], which also
 *   clears the widest privacy ring. That space belongs to the weather in the row's measurement, so
 *   when the bar runs out of room it is the icons after it that give way (they collapse into the
 *   lattice / overflow as before);
 * - the weather is translated back by however far the row currently starts from the container's
 *   start edge, which puts it on the anchor however many icons there are.
 *
 * It only applies while the status bar itself lays out around a centred cutout (its cutout space
 * view is showing and the display reports a top cutout): not in landscape, not with a corner
 * cutout, not without one. Then, and with the option off, the weather is simply the first item of
 * the end side, as before. RTL mirrors everything.
 *
 * @param weather the weather view, already added to [endContent]
 * @param endContent the row wrapped around the end-side views (status_bar_end_side_content)
 * @param cutoutSpace the status bar's cutout spacer (cutout_space_view), if the layout has one
 */
class StatusBarWeatherAnchor(
    private val weather: StatusBarWeather,
    private val endContent: ViewGroup,
    private val cutoutSpace: View?,
) {
    private val location = IntArray(2)
    private val layoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }

    fun attach() {
        weather.addOnLayoutChangeListener(layoutListener)
        endContent.addOnLayoutChangeListener(layoutListener)
        (endContent.parent as? View)?.addOnLayoutChangeListener(layoutListener)
        cutoutSpace?.addOnLayoutChangeListener(layoutListener)
        weather.setOnAnchorSettingChangedListener { update() }
        update()
    }

    private fun update() {
        val container = endContent.parent as? View
        val offset = if (container != null) anchorOffset(container) else null

        // The margin is part of the row's measurement: change it outside the layout pass that
        // called us.
        val margin = offset?.coerceAtLeast(0) ?: 0
        val params = weather.layoutParams as? LinearLayout.LayoutParams
        if (params != null && params.marginStart != margin) {
            weather.post {
                val current = weather.layoutParams as? LinearLayout.LayoutParams ?: return@post
                if (current.marginStart != margin) {
                    current.marginStart = margin
                    weather.layoutParams = current
                }
            }
        }

        weather.translationX =
            if (container == null || offset == null || weather.visibility != View.VISIBLE) {
                0f
            } else if (weather.isLayoutRtl) {
                (container.width - offset - endContent.left - weather.right).toFloat()
            } else {
                (offset - endContent.left - weather.left).toFloat()
            }
    }

    /**
     * Distance from [container]'s start edge to where the weather's start edge belongs, or null
     * when the weather is not to be anchored.
     */
    private fun anchorOffset(container: View): Int? {
        if (!weather.isAnchoredToCutout) return null
        // The status bar's own notion of "there is a cutout in the middle of the bar".
        if (cutoutSpace == null || cutoutSpace.visibility != View.VISIBLE) return null
        // Live, in window coordinates, like the container's location below.
        val cutout = weather.rootWindowInsets?.displayCutout?.boundingRectTop ?: return null
        if (cutout.isEmpty) return null

        val gap =
            IchthysIndicatorTokens.dpToPxInt(
                weather.context,
                IchthysIndicatorTokens.WEATHER_CUTOUT_GAP_DP,
            )
        container.getLocationInWindow(location)
        val offset =
            if (weather.isLayoutRtl) {
                location[0] + container.width - (cutout.left - gap)
            } else {
                cutout.right + gap - location[0]
            }
        // The end side starts about where the cutout ends. Anything else (the cutout is not
        // between the two halves after all, or there is no room left beside it) is not this
        // layout: leave the weather with the icons.
        if (offset < -cutout.width() || offset + weather.width > container.width) return null
        return offset
    }
}
