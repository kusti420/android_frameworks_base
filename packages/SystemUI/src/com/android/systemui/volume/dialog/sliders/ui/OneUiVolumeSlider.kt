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

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import com.android.internal.R as internalR
import com.android.settingslib.R as SettingsR
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderStateModel
import kotlin.math.roundToInt

/**
 * Custom Options: original "One UI" style volume slider.
 *
 * This is a fully self-contained recreation of the One UI 8 volume panel look built from primitive
 * Compose drawing (no Samsung assets, layouts or code). It renders:
 * - a slim, fully-rounded vertical capsule as the track; its unfilled (upper) portion stays a
 *   uniform neutral grey,
 * - an ACCENT fill that grows FROM THE BOTTOM, edge-to-edge (full capsule width), clipped to the
 *   capsule's rounded shape, with a top-lit gradient for depth (One UI fills white; we use accent),
 * - the stream icon pinned to the bottom of the capsule (a note-with-sound-waves glyph for media),
 * - an "⋯" expand affordance and the numeric level pinned to the top of the capsule.
 *
 * The glyphs are drawn twice - dark over the grey track and white clipped to the fill region - so
 * they flip color as the fill rises over them, the same read as the reference One UI panel.
 *
 * Specs measured from the One UI 8 reference screenshots (pixel sampling), see [OneUiVolumeSpecs].
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
    modifier: Modifier = Modifier,
) {
    val min = state.valueRange.start
    val max = state.valueRange.endInclusive
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val fraction = ((state.value - min) / span).coerceIn(0f, 1f)
    val level = state.value.roundToInt()

    // Smoothly grow/shrink the fill so external volume changes and taps don't snap harshly.
    val animatedFraction by animateFloatAsState(targetValue = fraction, label = "oneUiVolumeFill")

    val view = LocalView.current

    // Custom Options: One UI colors are set EXPLICITLY (never from the Monet colorScheme) so the
    // capsule renders neutral-grey + seed-accent regardless of the system tonal palette:
    // - track: a solid, opaque neutral grey capsule; the UNFILLED (upper) portion stays grey,
    // - fill: the picked seed accent (?android:attr/colorAccent, the same accent SystemUI reads)
    //   that grows FROM THE BOTTOM, edge-to-edge (full capsule width), clipped to the capsule's
    //   rounded shape, with a top-lit vertical gradient for depth (One UI fills white; we use accent),
    // - glyphs: near-black over the grey track, white where the fill has risen over them.
    val accentColor =
        remember(view) {
            val ta = view.context.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
            val c = ta.getColor(0, OneUiVolumeSpecs.fallbackAccent)
            ta.recycle()
            Color(c).copy(alpha = 1f)
        }
    val trackColor = if (state.isDisabled) OneUiVolumeSpecs.trackDisabled else OneUiVolumeSpecs.track
    val fillColor = if (state.isDisabled) trackColor else accentColor
    val inactiveContentColor = OneUiVolumeSpecs.glyphOverTrack
    val activeContentColor = OneUiVolumeSpecs.glyphOverFill

    // Depth: a top-lit vertical gradient across the fill (lighter at its top edge, a touch deeper at
    // the bottom) so the accent fill reads with subtle volume instead of flat. Mapped to the fill's
    // own (bottom-anchored) bounds, so the highlight always sits at the current fill line.
    val fillBrush =
        if (state.isDisabled) {
            SolidColor(fillColor)
        } else {
            Brush.verticalGradient(
                0f to lerp(fillColor, Color.White, OneUiVolumeSpecs.fillHighlight),
                0.5f to fillColor,
                1f to lerp(fillColor, Color.Black, OneUiVolumeSpecs.fillShade),
            )
        }

    val capsuleShape = RoundedCornerShape(percent = 50)

    // Snap an unbounded pointer offset (top = max, bottom = min) to a discrete level value.
    fun valueForOffset(y: Float, height: Float): Float {
        val f = (1f - (y / height)).coerceIn(0f, 1f)
        return (min + (f * span)).roundToInt().toFloat().coerceIn(min, max)
    }

    val gestureModifier =
        if (state.isDisabled) {
            Modifier
        } else {
            Modifier.pointerInput(min, max, span) {
                    // Custom Options: the "⋯" affordance and the level number live INSIDE the top of
                    // the capsule, and the capsule is tap-to-set. Without this guard, tapping the
                    // "⋯" snaps the stream straight to maximum - a genuinely nasty surprise on a
                    // media stream. Taps that land in the top affordance strip are swallowed; drags
                    // (which are unambiguous) still work from anywhere on the capsule.
                    val affordanceHeightPx = OneUiVolumeSpecs.topAffordanceHeight.toPx()
                    detectTapGestures(
                        onTap = { offset ->
                            if (offset.y >= affordanceHeightPx) {
                                val value = valueForOffset(offset.y, size.height.toFloat())
                                onValueChanged(value)
                                onValueChangeFinished(value)
                            } else {
                                // IchthysOS: tapping the "⋯" dots triggers expansion
                                onExpandClicked()
                            }
                        }
                    )
                }
                .pointerInput(min, max, span, hapticFeedbackEnabled) {
                    var latestValue = state.value
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            onDragStarted()
                            latestValue = valueForOffset(offset.y, size.height.toFloat())
                            onValueChanged(latestValue)
                        },
                        onDragEnd = {
                            onValueChangeFinished(latestValue)
                            onDragFinished()
                        },
                        onDragCancel = { onDragFinished() },
                    ) { change, _ ->
                        val value = valueForOffset(change.position.y, size.height.toFloat())
                        if (value != latestValue) {
                            latestValue = value
                            if (hapticFeedbackEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        onValueChanged(value)
                        change.consume()
                    }
                }
        }

    // Center a slim, tall capsule inside the (wider) slider column so it reads as a narrow One UI
    // vertical capsule rather than a wide bar. The capsule is the only touch target.
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Box(
        modifier =
            Modifier.fillMaxHeight()
                .width(OneUiVolumeSpecs.capsuleWidth)
                .clip(capsuleShape)
                .background(trackColor)
                .then(gestureModifier)
                .semantics {
                    contentDescription = state.label
                    if (state.isDisabled) {
                        disabled()
                    } else {
                        progressBarRangeInfo = ProgressBarRangeInfo(state.value, state.valueRange)
                        setProgress { target ->
                            onValueChanged(target.coerceIn(min, max))
                            true
                        }
                    }
                }
    ) {
        val fillFraction = animatedFraction.coerceIn(0f, 1f)

        // 1) Dark ⋯ + number on the grey track (visible wherever the fill hasn't risen).
        OneUiTopGlyphs(level = level, contentColor = inactiveContentColor)

        // 2) The accent FILL: a bottom-anchored, full-width box whose height = level fraction of the
        //    capsule height. Its own shape has a ROUNDED TOP cap (bottom corners square - the parent
        //    capsule clip rounds the bottom). RoundedCornerShape auto-clamps the top radius to the
        //    box height, so the cap reads nearly flat when the fill is low and domes out as the fill
        //    rises - matching One UI ("starts flat, rounds at the top as you scroll up"). The
        //    gradient is mapped to this box, so its top-lit highlight sits at the current fill line.
        Box(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fillFraction)
                    .clip(
                        RoundedCornerShape(
                            topStart = OneUiVolumeSpecs.fillTopCornerRadius,
                            topEnd = OneUiVolumeSpecs.fillTopCornerRadius,
                            bottomEnd = 0.dp,
                            bottomStart = 0.dp,
                        )
                    )
                    .background(fillBrush)
        )

        // 3) The ⋯ + number flip to white where the fill has risen over them (clipped to the fill
        //    region) so they stay readable over the accent - the same read as the reference panel.
        Box(modifier = Modifier.fillMaxSize().clip(BottomFillShape(fillFraction))) {
            OneUiTopGlyphs(level = level, contentColor = activeContentColor)
        }

        // 4) The stream icon (note-with-sound-waves for media) is drawn last, ON TOP of the fill, and
        //    stays a CONSTANT dark glyph - One UI keeps the note dark over both the grey track and the
        //    accent fill (black-on-magenta and black-on-grey both read fine), so it does NOT flip.
        OneUiBottomIcon(
            state = state,
            iconSize = iconSize,
            contentColor = OneUiVolumeSpecs.glyphOverTrack,
        )
      }
    }
}

/** The "⋯" expand affordance + numeric level pinned to the top of the capsule. */
@Composable
private fun BoxScope.OneUiTopGlyphs(level: Int, contentColor: Color) {
    Column(
        modifier =
            Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = OneUiVolumeSpecs.topPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OneUiExpandDots(color = contentColor)
        Spacer(modifier = Modifier.height(OneUiVolumeSpecs.dotsToNumberGap))
        Text(
            text = level.toString(),
            color = contentColor,
            fontSize = OneUiVolumeSpecs.numberTextSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** The stream icon pinned to the bottom of the capsule. Rendered once, in a constant color. */
@Composable
private fun BoxScope.OneUiBottomIcon(
    state: VolumeDialogSliderStateModel,
    iconSize: Dp,
    contentColor: Color,
) {
    Box(
        modifier =
            Modifier.align(Alignment.BottomCenter).padding(bottom = OneUiVolumeSpecs.iconBottomPadding)
    ) {
        // Custom Options: swap EVERY stock AOSP stream glyph for its One UI-family counterpart, so
        // the panel reads as one icon set instead of "One UI note + Material everything else".
        // Anything we don't have an original for (e.g. a Zen mode's own icon, which arrives as a
        // pre-loaded Drawable with no resId) falls through to the stock icon unchanged.
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

/** Original "⋯" expand affordance: three small dots. */
@Composable
private fun OneUiExpandDots(color: Color) {
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

/**
 * A [Shape] that outlines the bottom [fraction] of its bounds as a rectangle. Combined with the
 * parent capsule clip, an overlay clipped to this shape is revealed only where the accent fill has
 * risen (bottom-anchored), so glyphs there flip to their over-fill color.
 */
private data class BottomFillShape(val fraction: Float) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val f = fraction.coerceIn(0f, 1f)
        val top = size.height * (1f - f)
        return Outline.Rectangle(Rect(0f, top, size.width, size.height))
    }
}

/**
 * One UI volume slider specs. Values are recreated from the One UI reference (original values, no
 * Samsung binaries):
 * - capsule = a slim, tall, fully-rounded capsule (RoundedCornerShape(50%)); ends are semicircular.
 * - colors are solid/flat/opaque and set EXPLICITLY (never from the Monet colorScheme): grey track,
 *   seed-accent fill, near-black/white glyphs. No translucency or overlays on the fill.
 * - paddings derived from reference glyph positions (screenshot pixel sampling).
 */
private object OneUiVolumeSpecs {
    // Opaque colors (no Monet). The UNFILLED part of the track stays this neutral grey (~#717175
    // from the ref); the accent fill grows over it from the bottom.
    val track = Color(0xFF717175)
    val trackDisabled = Color(0xFF48484A)
    // Glyphs are drawn twice: dark over the grey track, white where the fill has risen over them.
    val glyphOverTrack = Color(0xFF1A1A1C)
    val glyphOverFill = Color(0xFFFFFFFF)
    // Fallback only if ?android:attr/colorAccent can't be resolved; the live accent wins.
    val fallbackAccent = 0xFFE91E63.toInt()

    // A slim, tall capsule (One UI proportion) centered inside the 52dp slider column. The collapsed
    // One UI popup is JUST this capsule (the lead removed the ringer/settings buttons).
    // Reference: real One UI 8 phone (adb pixel-sampling) measures the capsule ~112x540px ≈ 4.8:1.
    // Per direct on-device comparison the user wants ours a bit taller & thinner, so we run slightly
    // more elongated: 36dp wide, height capped at 205dp in VolumeDialogSlidersViewBinder (≈5.7:1).
    // 50% (semicircular) ends; the accent fill is edge-to-edge.
    val capsuleWidth = 36.dp
    // Rounded TOP cap on the accent fill (~capsule half-width) so the fill line domes like a pill.
    // Constant radius: RoundedCornerShape clamps it to the fill height, so it looks flat at low
    // volume and progressively rounds as the fill rises (the One UI behavior). Not scaled by level.
    val fillTopCornerRadius = capsuleWidth / 2f
    // Fill depth: top-lit vertical gradient across the fill's own bounds (lighter at the fill line,
    // slightly deeper at the very bottom).
    val fillHighlight = 0.28f
    val fillShade = 0.06f

    val topPadding = 12.dp
    val dotsToNumberGap = 5.dp
    val numberTextSize = 13.sp
    val iconBottomPadding = 14.dp
    val dotSize = 3.5.dp
    val dotSpacing = 3.dp
    // Height of the non-value-changing strip at the top of the capsule that holds the "⋯"
    // affordance and the level number: topPadding + dot + gap + number line, rounded up.
    val topAffordanceHeight = 40.dp
}
