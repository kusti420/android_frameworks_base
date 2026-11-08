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

package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.brightness.ui.compose.BrightnessSlider
import com.android.systemui.brightness.ui.compose.BrightnessSliderDimensions
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.qs.shared.ui.QuickSettings.Elements
import com.android.systemui.ichthys.notes.ui.IchthysQsNotes
import com.android.systemui.qs.ui.composable.QuickSettingsScene
import com.android.systemui.shade.ui.composable.Shade
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.shade.ui.composable.ShoalRingerElement
import kotlin.time.Duration.Companion.milliseconds

fun TransitionBuilder.toQuickSettingsSceneTransition(durationScale: Double = 1.0) {
    spec = tween(durationMillis = (DefaultDuration * durationScale).inWholeMilliseconds.toInt())

    val translationY = ShadeHeader.Dimensions.CollapsedHeightForTransitions
    translate(
        ShadeHeader.Elements.ExpandedContent,
        y = -(ShadeHeader.Dimensions.ExpandedHeight - translationY),
    )
    translate(ShadeHeader.Elements.Clock, y = -translationY)
    translate(ShadeHeader.Elements.ShadeCarrierGroup, y = -translationY)

    fractionRange(start = .58f) {
        fade(ShadeHeader.Elements.ExpandedContent)
        fade(ShadeHeader.Elements.Clock)
        fade(ShadeHeader.Elements.ShadeCarrierGroup)
        // IchthysOS "Shoal": the Shoal header's date and icons (shared with the Shade scene).
        fade(ShadeHeader.Elements.CollapsedContentStart)
        fade(ShadeHeader.Elements.CollapsedContentEnd)
        fade(Elements.BrightnessSlider)
        // IchthysOS: the quick settings notes page, when quick settings collapses from it.
        fade(IchthysQsNotes.Elements.Page)
        // IchthysOS: the tiles too; translated alone they slid under the scrim edge fully opaque.
        fade(Elements.QuickSettingsContent)
        // IchthysOS "Shoal": the ringer pill under the clock; it stayed opaque to the end.
        fade(ShoalRingerElement)
    }

    fade(Notifications.Elements.HeadsUpNotificationPlaceholder)
    fade(Shade.Elements.BackgroundScrim)

    // New all compose element
    // Ideally, we would want to have the BrightnessSlider start right off screen and translate
    // down,
    // And have the other elements of the content slide anchored to that. But anchoredTranslate does
    // not support that yet.
    translate(
        Elements.QuickSettingsContent,
        y = -ShadeHeader.Dimensions.ExpandedHeight - BrightnessSliderDimensions.Default.thumbHeight,
    )
    // IchthysOS: the notes page moves with the tile content it stands in for.
    translate(
        IchthysQsNotes.Elements.Page,
        y = -ShadeHeader.Dimensions.ExpandedHeight - BrightnessSliderDimensions.Default.thumbHeight,
    )
    translate(Notifications.Elements.NotificationScrim, Edge.Top, false)
    translate(QuickSettingsScene.Companion.InternalScenes.Edit.rootElementKey, Edge.Top, true)
}

private val DefaultDuration = 500.milliseconds
