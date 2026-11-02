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

package com.android.systemui.volume.dialog.sliders.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.settingslib.ichthys.ShoalContrast
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsFonts
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderStateModel
import kotlin.math.roundToInt

/**
 * IchthysOS: the horizontal volume panel (Settings.Secure custom_volume_horizontal) and its premade
 * looks. Every binder of the horizontal dialog reads its [Config] once at bind time, like the other
 * volume settings; a change applies the next time the dialog is shown.
 *
 * The looks share one row: [stream icon][track][level], the icon in the accent (muted colour when
 * the stream is muted; tapping it toggles mute), the level in the Shoal mono face. What differs is
 * the track:
 * - [SHOAL]: the pill of the quick settings brightness slider (card surface + hairline, the accent
 *   fill tint), icon and level inside the pill;
 * - [LINE]: a thin line with a round accent knob;
 * - [SEGMENTED]: one rounded bar per step, lit in the accent up to the level;
 * - [CARD]: the whole row is a Shoal card whose fill tint grows with the level; it carries the
 *   stream name too and draws no dialog panel behind it.
 *
 * Colours are the framework Shoal tokens (com.android.internal.R.color.ichthys_*), nothing literal;
 * sizes are code constants (SystemUI must not gain dimens, see the agent brief).
 */
object HorizontalVolumeStyle {
    const val KEY_STYLE = "volume_horizontal_style"
    const val KEY_LEVEL = "volume_horizontal_level"
    const val KEY_THICKNESS = "volume_horizontal_thickness"

    const val SHOAL = 0
    const val LINE = 1
    const val SEGMENTED = 2
    const val CARD = 3

    const val LEVEL_HIDDEN = 0
    const val LEVEL_STEP = 1
    const val LEVEL_PERCENT = 2

    const val SLIM = 0
    const val REGULAR = 1
    const val BOLD = 2

    /** Width (dp) of the slider row, every look. 280 + the dialog's padding fits a 360 dp screen. */
    const val ROW_WIDTH_DP = 280f

    @Immutable
    data class Config(val style: Int, val level: Int, val thickness: Int)

    fun read(context: Context): Config {
        val cr = context.contentResolver
        fun sys(key: String, def: Int, max: Int) =
            Settings.System.getIntForUser(cr, key, def, UserHandle.USER_CURRENT).coerceIn(0, max)
        return Config(
            style = sys(KEY_STYLE, SHOAL, CARD),
            level = sys(KEY_LEVEL, LEVEL_STEP, LEVEL_PERCENT),
            thickness = sys(KEY_THICKNESS, REGULAR, BOLD),
        )
    }

    /** Height (dp) of the slider row. */
    fun rowHeightDp(c: Config): Float =
        when (c.style) {
            SHOAL -> floatArrayOf(32f, 40f, 48f)[c.thickness]
            CARD -> floatArrayOf(44f, 52f, 60f)[c.thickness]
            else -> 40f
        }

    /** Whether the dialog draws its own panel behind the row ([CARD] rows are their own card). */
    fun hasPanel(c: Config): Boolean = c.style != CARD

    /**
     * The dialog panel behind the row(s): the Shoal ground with a hairline, rounded like every
     * Shoal panel. Null for a look whose row is its own card.
     */
    fun panelBackground(context: Context, c: Config): Drawable? {
        if (!hasPanel(c)) return null
        val res = context.resources
        return GradientDrawable().apply {
            cornerRadius = res.getDimension(com.android.internal.R.dimen.ichthys_radius)
            setColor(context.getColor(com.android.internal.R.color.ichthys_ink))
            setStroke(
                res.getDimensionPixelSize(com.android.internal.R.dimen.ichthys_hairline_width),
                context.getColor(com.android.internal.R.color.ichthys_hairline),
            )
        }
    }
}

@Composable
fun HorizontalVolumeSlider(
    state: VolumeDialogSliderStateModel,
    config: HorizontalVolumeStyle.Config,
    hapticFeedbackEnabled: Boolean,
    onValueChanged: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    onDragStarted: () -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val min = state.valueRange.start
    val max = state.valueRange.endInclusive
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val fraction = ((state.value - min) / span).coerceIn(0f, 1f)
    val muted = state.value <= min

    // The gesture coroutines outlive recompositions: always act on the latest state/callbacks.
    val latestState by rememberUpdatedState(state)
    val latestOnValueChanged by rememberUpdatedState(onValueChanged)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val latestOnDragStarted by rememberUpdatedState(onDragStarted)
    val latestOnDragFinished by rememberUpdatedState(onDragFinished)

    val view = LocalView.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val colors = rememberHorizontalVolumeColors()
    val fonts = rememberShoalQsFonts()
    val hairline = dimensionResource(com.android.internal.R.dimen.ichthys_hairline_width)
    val cardRadius = dimensionResource(com.android.internal.R.dimen.ichthys_radius_small)

    var dragging by remember { mutableStateOf(false) }
    val animatedFraction by
        animateFloatAsState(
            targetValue = fraction,
            animationSpec = if (dragging) snap() else spring(dampingRatio = 1f, stiffness = 450f),
            label = "horizontalVolumeFill",
        )

    // Tap the stream icon to mute / restore.
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

    val levelText =
        when (config.level) {
            HorizontalVolumeStyle.LEVEL_STEP -> state.value.roundToInt().toString()
            HorizontalVolumeStyle.LEVEL_PERCENT -> "${(fraction * 100f).roundToInt()}%"
            else -> null
        }
    val iconColor = if (muted) colors.muted else colors.accent

    /** Absolute track gestures: a tap or a drag sets the level where the finger is. */
    fun Modifier.trackGestures(): Modifier =
        if (state.isDisabled) this
        else
            this.pointerInput(min, max, span, rtl, hapticFeedbackEnabled) {
                    fun valueAt(x: Float): Float {
                        var f = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        if (rtl) f = 1f - f
                        return (min + f * span).roundToInt().toFloat().coerceIn(min, max)
                    }
                    var latestValue = latestState.value
                    fun setValue(v: Float) {
                        if (v != latestValue) {
                            latestValue = v
                            if (hapticFeedbackEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            latestOnValueChanged(v)
                        }
                    }
                    detectTapGestures { offset ->
                        latestValue = latestState.value
                        setValue(valueAt(offset.x))
                        latestOnValueChangeFinished(latestValue)
                    }
                }
                .pointerInput(min, max, span, rtl, hapticFeedbackEnabled) {
                    fun valueAt(x: Float): Float {
                        var f = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        if (rtl) f = 1f - f
                        return (min + f * span).roundToInt().toFloat().coerceIn(min, max)
                    }
                    var latestValue = latestState.value
                    fun setValue(v: Float) {
                        if (v != latestValue) {
                            latestValue = v
                            if (hapticFeedbackEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            latestOnValueChanged(v)
                        }
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            latestValue = latestState.value
                            dragging = true
                            latestOnDragStarted()
                            setValue(valueAt(offset.x))
                        },
                        onDragEnd = {
                            latestOnValueChangeFinished(latestValue)
                            dragging = false
                            latestOnDragFinished()
                        },
                        onDragCancel = {
                            dragging = false
                            latestOnDragFinished()
                        },
                    ) { change, _ ->
                        change.consume()
                        setValue(valueAt(change.position.x))
                    }
                }

    val semanticsModifier =
        Modifier.semantics {
            contentDescription = state.label
            stateDescription = "${(fraction * 100f).roundToInt()}%"
            if (state.isDisabled) {
                disabled()
            } else {
                progressBarRangeInfo = ProgressBarRangeInfo(state.value, state.valueRange)
                setProgress { target ->
                    val v = target.roundToInt().toFloat().coerceIn(min, max)
                    onValueChanged(v)
                    onValueChangeFinished(v)
                    true
                }
            }
        }

    val iconModifier =
        if (state.isDisabled) Modifier
        else Modifier.pointerInput(Unit) { detectTapGestures { toggleMute() } }

    val levelStyle =
        TextStyle(
            fontFamily = fonts.mono,
            fontSize = LevelTextSize,
            fontWeight = FontWeight.Medium,
            color = colors.body,
            textAlign = TextAlign.End,
        )

    @Composable
    fun Level(modifier: Modifier = Modifier) {
        if (levelText != null) {
            BasicText(
                text = levelText,
                style = levelStyle,
                modifier = modifier.widthIn(min = LevelMinWidth),
            )
        }
    }

    @Composable
    fun StreamIcon(modifier: Modifier = Modifier) {
        Box(modifier = modifier.size(IconBox).then(iconModifier), contentAlignment = Alignment.Center) {
            Icon(icon = state.icon, tint = iconColor, modifier = Modifier.size(IconSize))
        }
    }

    val rootModifier =
        modifier.fillMaxSize().alpha(if (state.isDisabled) DisabledAlpha else 1f).then(semanticsModifier)

    when (config.style) {
        HorizontalVolumeStyle.SHOAL -> {
            // The brightness pill: everything sits inside the track.
            Box(
                modifier =
                    rootModifier
                        .clip(RoundedCornerShape(percent = 50))
                        .trackGestures()
                        .drawBehind {
                            drawPillTrack(
                                frac = animatedFraction,
                                track = colors.card,
                                fill = colors.fill,
                                hairlineColor = colors.hairline,
                                hairline = hairline.toPx(),
                                corner = size.height / 2f,
                                rtl = rtl,
                            )
                        }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = PillInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StreamIcon()
                    Spacer(Modifier.weight(1f))
                    Level()
                }
            }
        }
        HorizontalVolumeStyle.CARD -> {
            // The row is the card: ground + hairline, the fill tint grows with the level.
            Box(
                modifier =
                    rootModifier
                        .clip(RoundedCornerShape(cardRadius))
                        .trackGestures()
                        .drawBehind {
                            drawPillTrack(
                                frac = animatedFraction,
                                track = colors.card,
                                fill = colors.fill,
                                hairlineColor = colors.hairline,
                                hairline = hairline.toPx(),
                                corner = cardRadius.toPx(),
                                rtl = rtl,
                            )
                        }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = CardInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StreamIcon()
                    BasicText(
                        text = state.label,
                        style =
                            TextStyle(
                                fontFamily = fonts.text,
                                fontSize = LabelTextSize,
                                fontWeight = FontWeight.Medium,
                                color = colors.body,
                            ),
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(start = CardLabelGap),
                    )
                    Level()
                }
            }
        }
        else -> {
            // LINE and SEGMENTED: icon and level flank a bare track.
            Row(modifier = rootModifier, verticalAlignment = Alignment.CenterVertically) {
                StreamIcon()
                Spacer(Modifier.width(TrackGap))
                Box(
                    modifier =
                        Modifier.weight(1f).fillMaxHeight().trackGestures().drawBehind {
                            if (config.style == HorizontalVolumeStyle.LINE) {
                                drawLineTrack(
                                    frac = animatedFraction,
                                    thickness = LineThickness[config.thickness].toPx(),
                                    knob = LineKnob[config.thickness].toPx(),
                                    track = colors.track,
                                    accent = colors.accent,
                                    rtl = rtl,
                                )
                            } else {
                                drawSegmentedTrack(
                                    frac = animatedFraction,
                                    steps = span.roundToInt().coerceIn(1, MaxSegments),
                                    barHeight = SegmentHeight[config.thickness].toPx(),
                                    gap = SegmentGap.toPx(),
                                    track = colors.track,
                                    accent = colors.accent,
                                    rtl = rtl,
                                )
                            }
                        }
                )
                if (levelText != null) {
                    Spacer(Modifier.width(TrackGap))
                    Level()
                }
            }
        }
    }
}

/** The Shoal tokens the slider draws with; they follow the theme, nothing here is literal. */
@Immutable
private class HorizontalVolumeColors(
    val card: Color,
    val track: Color,
    val hairline: Color,
    val body: Color,
    val muted: Color,
    val accent: Color,
    val fill: Color,
)

@Composable
private fun rememberHorizontalVolumeColors(): HorizontalVolumeColors {
    val card = colorResource(com.android.internal.R.color.ichthys_card)
    val track = colorResource(com.android.internal.R.color.ichthys_track)
    val hairline = colorResource(com.android.internal.R.color.ichthys_hairline)
    val body = colorResource(com.android.internal.R.color.ichthys_body)
    val muted = colorResource(com.android.internal.R.color.ichthys_muted)
    val accent = colorResource(com.android.internal.R.color.ichthys_accent)
    val fill = colorResource(com.android.internal.R.color.ichthys_accent_fill)
    return remember(card, track, hairline, body, muted, accent, fill) {
        // IchthysOS: here the accent only ever draws marks (stream icon, line, knob, lit
        // segments) on the card, the track or the fill tint, never a filled surface, so it is
        // contrast-guarded against all three: a dark accent is drawn in a lighter tone of itself.
        val mark =
            ShoalContrast.forGraphic(accent.toArgb(), card.toArgb(), track.toArgb(), fill.toArgb())
        HorizontalVolumeColors(card, track, hairline, body, muted, Color(mark), fill)
    }
}

/** A rounded track (the box is clipped to it) with the fill tint up to [frac] and a hairline. */
private fun DrawScope.drawPillTrack(
    frac: Float,
    track: Color,
    fill: Color,
    hairlineColor: Color,
    hairline: Float,
    corner: Float,
    rtl: Boolean,
) {
    val w = size.width
    val h = size.height
    drawRoundRect(color = track, cornerRadius = CornerRadius(corner))
    val x = frac.coerceIn(0f, 1f) * w
    val left = if (rtl) w - x else 0f
    val right = if (rtl) w else x
    if (right > left) {
        drawRect(color = fill, topLeft = Offset(left, 0f), size = Size(right - left, h))
    }
    if (hairline > 0f) {
        drawRoundRect(
            color = hairlineColor,
            topLeft = Offset(hairline / 2f, hairline / 2f),
            size = Size(w - hairline, h - hairline),
            cornerRadius = CornerRadius((corner - hairline / 2f).coerceAtLeast(0f)),
            style = Stroke(width = hairline),
        )
    }
}

/** A thin round-capped line, the accent part up to [frac], and a round accent knob there. */
private fun DrawScope.drawLineTrack(
    frac: Float,
    thickness: Float,
    knob: Float,
    track: Color,
    accent: Color,
    rtl: Boolean,
) {
    val w = size.width
    val cy = size.height / 2f
    val r = knob / 2f
    // The knob stays inside the box at both ends.
    val startX = if (rtl) w - r else r
    val endX = if (rtl) r else w - r
    val knobX = startX + (endX - startX) * frac.coerceIn(0f, 1f)
    drawLine(
        color = track,
        start = Offset(startX, cy),
        end = Offset(endX, cy),
        strokeWidth = thickness,
        cap = StrokeCap.Round,
    )
    if (knobX != startX) {
        drawLine(
            color = accent,
            start = Offset(startX, cy),
            end = Offset(knobX, cy),
            strokeWidth = thickness,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(color = accent, radius = r, center = Offset(knobX, cy))
}

/** [steps] rounded bars with [gap] between them, the first round([frac] * steps) in the accent. */
private fun DrawScope.drawSegmentedTrack(
    frac: Float,
    steps: Int,
    barHeight: Float,
    gap: Float,
    track: Color,
    accent: Color,
    rtl: Boolean,
) {
    val w = size.width
    val top = (size.height - barHeight) / 2f
    val barW = ((w - gap * (steps - 1)) / steps).coerceAtLeast(1f)
    val lit = (frac.coerceIn(0f, 1f) * steps).roundToInt()
    val radius = CornerRadius(minOf(barW, barHeight) / 2f)
    for (i in 0 until steps) {
        val index = if (rtl) steps - 1 - i else i
        val x = index * (barW + gap)
        drawRoundRect(
            color = if (i < lit) accent else track,
            topLeft = Offset(x, top),
            size = Size(barW, barHeight),
            cornerRadius = radius,
        )
    }
}

private val IconBox = 32.dp
private val IconSize = 20.dp
private val PillInset = 6.dp
private val CardInset = 10.dp
private val CardLabelGap = 6.dp
private val TrackGap = 8.dp
private val LevelTextSize = 13.sp
private val LabelTextSize = 13.sp
private val LevelMinWidth = 30.dp
private const val DisabledAlpha = 0.5f
private const val MaxSegments = 30
/** Per thickness (slim, regular, bold). */
private val LineThickness: Array<Dp> = arrayOf(2.dp, 4.dp, 6.dp)
private val LineKnob: Array<Dp> = arrayOf(14.dp, 18.dp, 22.dp)
private val SegmentHeight: Array<Dp> = arrayOf(10.dp, 16.dp, 22.dp)
private val SegmentGap = 3.dp
