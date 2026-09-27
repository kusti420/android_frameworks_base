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

package com.android.systemui.volume.dialog.sliders.ui

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.internal.R as internalR
import com.android.settingslib.R as SettingsR
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderStateModel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Custom Options: "One UI" style volume slider (volume_panel_style = 1).
 *
 * An original re-implementation of the One UI 6 (A52s firmware A528BXXU5FWK4 / A528BXXSAGYA2)
 * volume pill, built from the measured values in ichthys-refs SPEC_VOLUME_ONEUI.md (no Samsung
 * code or assets):
 * - a 36 x 210 dp pill. The host View supplies the size, the cross-window blur, the black α30
 *   foreground and the 12 dp elevation (VolumeDialogSlidersViewBinder.applyOneUiPill); Compose
 *   draws the 1 dp #0dffffff track stroke, the fill and the glyphs,
 * - the fill is a flat-topped, bottom-anchored cut of the pill (One UI's ClipDrawable), fixed
 *   colours #f2ffffff (light) / #40fafafa (dark), never Monet,
 * - stream icon inside the bottom of the pill and the "more" (expand) glyph inside the top, both
 *   #252525 (light) / #fafafa (dark), no level number,
 * - relative drag (a tap never changes the level), tap the icon to mute/unmute, horizontal swipe
 *   of 30 dp dismisses (toward the edge) or expands (toward the centre),
 * - "squish": the pill scales to 1.07 while touched and pulses to 0.95 on key-driven changes;
 *   programmatic level changes spring (stiffness 450, damping 1).
 */
@Composable
fun OneUiVolumeSlider(
    state: VolumeDialogSliderStateModel,
    iconSize: Dp,
    hapticFeedbackEnabled: Boolean,
    onValueChanged: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    onDragStarted: () -> Unit,
    onDragFinished: () -> Unit,
    onExpandClicked: () -> Unit,
    onSwipeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    panelOnLeft: Boolean = false,
) {
    val min = state.valueRange.start
    val max = state.valueRange.endInclusive
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val fraction = ((state.value - min) / span).coerceIn(0f, 1f)

    // The gesture coroutines below outlive recompositions, so they must read the latest state and
    // callbacks rather than the ones captured when they started.
    val latestState by rememberUpdatedState(state)
    val latestOnValueChanged by rememberUpdatedState(onValueChanged)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val latestOnDragStarted by rememberUpdatedState(onDragStarted)
    val latestOnDragFinished by rememberUpdatedState(onDragFinished)
    val latestOnExpandClicked by rememberUpdatedState(onExpandClicked)
    val latestOnSwipeDismiss by rememberUpdatedState(onSwipeDismiss)

    val view = LocalView.current
    val dark =
        (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val colors = if (dark) OneUiVolumeSpecs.Dark else OneUiVolumeSpecs.Light

    var dragging by remember { mutableStateOf(false) }
    // One UI sets the progress directly while dragging and springs it (stiffness 450, damping 1)
    // for every other change (keys, other apps, mute).
    val animatedFraction by
        animateFloatAsState(
            targetValue = fraction,
            animationSpec =
                if (dragging) snap() else spring(dampingRatio = 1f, stiffness = 450f),
            label = "oneUiVolumeFill",
        )

    // "Squish" feedback on the host view (so the blur, shadow and pill scale together, like One UI
    // scaling the window decor): 1.07 while touched, 0.95 pulse on key-driven level changes.
    val host = view.parent as? View ?: view
    val squish = remember { Animatable(1f) }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(touched) {
        squish.animateTo(
            if (touched) 1.07f else 1f,
            spring(dampingRatio = 1f, stiffness = if (touched) 300f else 200f),
        ) {
            host.scaleX = value
            host.scaleY = value
        }
    }
    var lastSeenValue by remember { mutableFloatStateOf(state.value) }
    LaunchedEffect(state.value) {
        val changedExternally = !dragging && !touched && state.value != lastSeenValue
        lastSeenValue = state.value
        if (changedExternally && !expanded) {
            squish.animateTo(0.95f, spring(dampingRatio = 1f, stiffness = 500f)) {
                host.scaleX = value
                host.scaleY = value
            }
            delay(100)
            squish.animateTo(1f, spring(dampingRatio = 1f, stiffness = 450f)) {
                host.scaleX = value
                host.scaleY = value
            }
        }
    }

    // Tap the stream icon to mute / restore, like One UI.
    var restoreValue by remember { mutableFloatStateOf(max) }
    fun toggleMute() {
        val current = latestState.value
        if (current > min) {
            restoreValue = current
            latestOnValueChanged(min)
            latestOnValueChangeFinished(min)
        } else {
            val target = restoreValue.coerceIn(min, max).takeIf { it > min } ?: (min + 1f)
            latestOnValueChanged(target)
            latestOnValueChangeFinished(target)
        }
    }

    val pillShape = RoundedCornerShape(percent = 50)

    val gestureModifier =
        if (state.isDisabled) {
            Modifier
        } else {
            Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        touched = true
                        try {
                            // Stay squished for the whole gesture, even while the drag consumes it.
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            touched = false
                        }
                    }
                }
                .pointerInput(min, max) {
                    val topAreaPx = OneUiVolumeSpecs.affordanceBox.toPx()
                    detectTapGestures { offset ->
                        when {
                            // The "more" button only fires for a tap inside it.
                            offset.y <= topAreaPx -> latestOnExpandClicked()
                            offset.y >= size.height - topAreaPx -> toggleMute()
                            // A tap on the rest of the pill never changes the level.
                        }
                    }
                }
                .pointerInput(min, max, span, hapticFeedbackEnabled, expanded, panelOnLeft) {
                    val swipePx = OneUiVolumeSpecs.swipeDistance.toPx()
                    var startValue = latestState.value
                    var latestValue = latestState.value
                    var totalX = 0f
                    var totalY = 0f
                    var mode = 0 // 0 undecided, 1 level drag, 2 horizontal swipe
                    var swipeFired = false
                    detectDragGestures(
                        onDragStart = {
                            startValue = latestState.value
                            latestValue = latestState.value
                            totalX = 0f
                            totalY = 0f
                            mode = 0
                            swipeFired = false
                        },
                        onDragEnd = {
                            if (mode == 1) {
                                latestOnValueChangeFinished(latestValue)
                                dragging = false
                                latestOnDragFinished()
                            }
                        },
                        onDragCancel = {
                            if (mode == 1) {
                                dragging = false
                                latestOnDragFinished()
                            }
                        },
                    ) { change, dragAmount ->
                        totalX += dragAmount.x
                        totalY += dragAmount.y
                        if (mode == 0) {
                            mode = if (abs(totalX) > abs(totalY)) 2 else 1
                            if (mode == 1) {
                                dragging = true
                                latestOnDragStarted()
                            }
                        }
                        if (mode == 1) {
                            // Relative drag: the finger moves the level by the distance travelled,
                            // measured along the pill, from wherever it started.
                            val value =
                                (startValue - (totalY / size.height) * span)
                                    .roundToInt()
                                    .toFloat()
                                    .coerceIn(min, max)
                            if (value != latestValue) {
                                latestValue = value
                                if (hapticFeedbackEnabled) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                latestOnValueChanged(value)
                            }
                        } else if (!swipeFired && abs(totalX) > swipePx) {
                            swipeFired = true
                            val towardEdge = if (panelOnLeft) totalX < 0 else totalX > 0
                            if (towardEdge) latestOnSwipeDismiss() else latestOnExpandClicked()
                        }
                        change.consume()
                    }
                }
        }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier.fillMaxHeight()
                    .fillMaxWidth()
                    .alpha(if (state.isDisabled) OneUiVolumeSpecs.disabledAlpha else 1f)
                    .clip(pillShape)
                    .background(if (expanded) colors.expandedTrack else Color.Transparent)
                    .border(
                        width =
                            if (expanded) OneUiVolumeSpecs.expandedStroke
                            else OneUiVolumeSpecs.trackStroke,
                        color = if (expanded) colors.expandedStrokeColor else colors.trackStrokeColor,
                        shape = pillShape,
                    )
                    .then(gestureModifier)
                    .semantics {
                        contentDescription = state.label
                        if (state.isDisabled) {
                            disabled()
                        } else {
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(state.value, state.valueRange)
                            setProgress { target ->
                                onValueChanged(target.coerceIn(min, max))
                                true
                            }
                        }
                    }
        ) {
            // The fill: a flat-topped, bottom-anchored cut of the pill (the parent clip keeps the
            // rounded bottom), exactly how One UI's ClipDrawable reads.
            Box(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(animatedFraction.coerceIn(0f, 1f))
                        .background(colors.fill)
            )
            if (!expanded) {
                OneUiMoreGlyph(color = colors.glyph)
            }
            // IchthysOS: the current volume step, under the "more" glyph (owner request; stock
            // One UI 6 shows none).
            OneUiStepNumber(
                step = state.value.roundToInt(),
                color = colors.glyph,
                topInset =
                    if (expanded) OneUiVolumeSpecs.glyphInset
                    else OneUiVolumeSpecs.glyphInset + OneUiVolumeSpecs.glyphSize,
            )
            OneUiBottomIcon(state = state, iconSize = iconSize, contentColor = colors.glyph)
        }
    }
}

/** The "more" (expand) glyph inside the top of the pill: a 28 dp glyph 4 dp below the pill top. */
@Composable
private fun BoxScope.OneUiMoreGlyph(color: Color) {
    Box(
        modifier =
            Modifier.align(Alignment.TopCenter)
                .padding(top = OneUiVolumeSpecs.glyphInset)
                .size(OneUiVolumeSpecs.glyphSize),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(OneUiVolumeSpecs.dotSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) {
                Box(
                    modifier =
                        Modifier.size(OneUiVolumeSpecs.dotSize).clip(CircleShape).background(color)
                )
            }
        }
    }
}

/** IchthysOS: the numeric volume step, centred near the top of the pill. */
@Composable
private fun BoxScope.OneUiStepNumber(step: Int, color: Color, topInset: Dp) {
    Text(
        text = step.toString(),
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
    )
}

/** The stream icon inside the bottom of the pill: a 28 dp glyph 4 dp above the pill bottom. */
@Composable
private fun BoxScope.OneUiBottomIcon(
    state: VolumeDialogSliderStateModel,
    iconSize: Dp,
    contentColor: Color,
) {
    Box(
        modifier =
            Modifier.align(Alignment.BottomCenter).padding(bottom = OneUiVolumeSpecs.glyphInset)
    ) {
        // Custom Options: swap EVERY stock AOSP stream glyph for its One UI-family counterpart, so
        // the panel reads as one icon set. Anything we don't have an original for (e.g. a Zen
        // mode's own icon, which arrives as a pre-loaded Drawable with no resId) falls through to
        // the stock icon unchanged.
        val oneUiIconRes = oneUiStreamIconRes(state.icon.resId)
        if (oneUiIconRes != null) {
            M3Icon(
                painter = painterResource(oneUiIconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        } else {
            Icon(icon = state.icon, modifier = Modifier.size(iconSize), tint = contentColor)
        }
    }
}

/**
 * Custom Options: maps every drawable [VolumeDialogSliderIconProvider] can emit onto the original
 * One UI volume-icon family (`oneui_volume_ic_*`). Returns `null` when there is no One UI
 * counterpart, in which case the caller keeps the stock icon.
 *
 * Kept in sync with `VolumeDialogSliderIconProvider.getIconRes` / `getIconForStream`.
 */
@DrawableRes
private fun oneUiStreamIconRes(resId: Int?): Int? =
    when (resId) {
        null -> null
        // Media
        R.drawable.ic_volume_media,
        R.drawable.ic_volume_media_low -> R.drawable.oneui_volume_ic_media_note_waves
        R.drawable.ic_volume_media_mute -> R.drawable.oneui_volume_ic_media_mute
        // Media routed to Bluetooth
        R.drawable.ic_volume_media_bt -> R.drawable.oneui_volume_ic_bluetooth
        R.drawable.ic_volume_media_bt_mute -> R.drawable.oneui_volume_ic_bluetooth_mute
        // Call audio routed to a Bluetooth headset (SCO)
        R.drawable.ic_volume_bt_sco -> R.drawable.oneui_volume_ic_bluetooth_call
        // Remote / cast output (these two live in SettingsLib, not SystemUI)
        SettingsR.drawable.ic_volume_remote -> R.drawable.oneui_volume_ic_cast
        SettingsR.drawable.ic_volume_remote_mute -> R.drawable.oneui_volume_ic_cast_mute
        // Ringtone
        R.drawable.ic_ring_volume -> R.drawable.oneui_volume_ic_ring
        R.drawable.ic_ring_volume_off -> R.drawable.oneui_volume_ic_ring_mute
        R.drawable.ic_volume_ringer_vibrate -> R.drawable.oneui_volume_ic_vibrate
        // Notification
        R.drawable.ic_volume_ringer -> R.drawable.oneui_volume_ic_notification
        R.drawable.ic_volume_ringer_mute -> R.drawable.oneui_volume_ic_notification_mute
        // Alarm
        R.drawable.ic_alarm -> R.drawable.oneui_volume_ic_alarm
        R.drawable.ic_volume_alarm_mute -> R.drawable.oneui_volume_ic_alarm_mute
        // System sounds
        R.drawable.ic_volume_system -> R.drawable.oneui_volume_ic_system
        R.drawable.ic_volume_system_mute -> R.drawable.oneui_volume_ic_system_mute
        // Assistant / voice chat
        R.drawable.ic_volume_voice_chat -> R.drawable.oneui_volume_ic_voice_chat
        R.drawable.ic_volume_voice_chat_mute -> R.drawable.oneui_volume_ic_voice_chat_mute
        // Accessibility
        R.drawable.ic_volume_accessibility -> R.drawable.oneui_volume_ic_accessibility
        // In-call / voice call (this one lives in framework-res)
        internalR.drawable.ic_phone -> R.drawable.oneui_volume_ic_call
        else -> null
    }

/**
 * One UI 6 volume pill values (SPEC_VOLUME_ONEUI.md §2/§3, A52s firmware). Colours are fixed, never
 * Monet. The track background itself (blur + black α30 foreground, or the no-blur tint) is painted
 * by the host View; see VolumeDialogSlidersViewBinder.applyOneUiPill.
 */
object OneUiVolumeSpecs {
    class Palette(
        val fill: Color,
        val glyph: Color,
        val trackStrokeColor: Color,
        val expandedTrack: Color,
        val expandedStrokeColor: Color,
    )

    val Light =
        Palette(
            fill = Color(0xF2FFFFFF),
            glyph = Color(0xFF252525),
            trackStrokeColor = Color(0x0DFFFFFF),
            expandedTrack = Color(0x14000000),
            expandedStrokeColor = Color(0x4DCCCCCC),
        )
    val Dark =
        Palette(
            fill = Color(0x40FAFAFA),
            glyph = Color(0xFFFAFAFA),
            trackStrokeColor = Color(0x0DFFFFFF),
            expandedTrack = Color(0x40000000),
            expandedStrokeColor = Color(0x2ECCCCCC),
        )

    /** Pill track: 36 x 210 dp, fully rounded. */
    val trackWidth = 36.dp
    val trackHeight = 210.dp
    val elevation = 12.dp
    val trackStroke = 1.dp
    val expandedStroke = 0.75.dp
    /** Glyph boxes are 36 dp with 4 dp padding: a 28 dp glyph, 4 dp in from the pill ends. */
    val glyphSize = 28.dp
    val glyphInset = 4.dp
    val affordanceBox = 36.dp
    val dotSize = 3.5.dp
    val dotSpacing = 3.dp
    val swipeDistance = 30.dp
    const val disabledAlpha = 0.7f
}
