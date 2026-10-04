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

package com.android.systemui.brightness.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.brightness.QsSliderShape
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsFonts
import com.android.systemui.qs.tiles.dialog.colordisplay.IchthysNightLightController
import com.android.systemui.res.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * IchthysOS brightness block. The ONE brightness slider: every quick settings tile layout
 * (Settings.Secure ichthys_qs_style = "grid" and "shoal") and the brightness dialog draw this same
 * composable through [BrightnessSliderContainer], so they cannot drift apart again.
 * - a label row: "Brightness", the value in the mono face, then three glyph pills: Night Light,
 *   the floor (with its "NN%" while on) and auto brightness;
 * - a 56 dp pill track (card surface + hairline) whose filled part is the accent fill tint, the
 *   part below the auto-brightness floor the dimmer accent tint, the floor itself a 4 dp accent bar
 *   drawn INSIDE the track, and an accent sun glyph at the start.
 *
 * Colours are the framework Shoal tokens (com.android.internal.R.color.ichthys_*, see
 * [rememberShoalBrightnessColors]), so the block follows the theme in light and dark and falls back
 * to the Material roles when the Shoal palette is not applied. Nothing here is a literal colour.
 *
 * Gestures: a horizontal drag that starts on (or near) the floor bar moves the floor; any other
 * drag or a tap sets the brightness. Taps are handled separately from drags so a vertical swipe
 * that starts on the slider still scrolls / collapses the shade.
 *
 * All values are in the slider's gamma space ([minGamma]..[maxGamma]).
 *
 * @param shape the crDroid `qs_brightness_slider_shape` value ([QsSliderShape]). The default is the
 *   pill; "rounded square" and "square" only change the track's corners.
 * @param interactionSource receives a [DragInteraction] for every brightness (not floor) drag, which
 *   is what the slider haptics listen to.
 * @param ownPanel draw a Shoal panel (ground + hairline) behind the whole block. For hosts with
 *   nothing of their own behind the slider - the brightness dialog's window is transparent. Quick
 *   settings sits on the shade panel and leaves this off.
 */
@Composable
internal fun ShoalBrightnessSlider(
    gamma: Int,
    minGamma: Int,
    maxGamma: Int,
    enabled: Boolean,
    isRestricted: Boolean,
    onRestrictedClick: () -> Unit,
    overriddenByAppState: Boolean,
    showToast: () -> Unit,
    showToggles: Boolean,
    autoMode: Boolean,
    floorEnabled: Boolean,
    floorGamma: Int,
    onToggleAuto: () -> Unit,
    onToggleFloor: () -> Unit,
    onFloorFinished: (Int) -> Unit,
    // Night Light: the tile's Off / On / Scheduled mode (null hides the pill: no night display, or
    // not read yet) and whether the screen is tinted right now.
    nightMode: IchthysNightLightController.Mode?,
    nightTinted: Boolean,
    onToggleNightLight: () -> Unit,
    onNightLightLongClick: () -> Unit,
    onDrag: (Int) -> Unit,
    onStop: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sliderModifier: Modifier = Modifier,
    shape: Int = QsSliderShape.DEFAULT,
    interactionSource: MutableInteractionSource? = null,
    ownPanel: Boolean = false,
) {
    val colors = rememberShoalBrightnessColors()
    val fonts = rememberShoalQsFonts()
    val hairlineWidth = dimensionResource(com.android.internal.R.dimen.ichthys_hairline_width)
    val panelModifier =
        if (ownPanel) {
            val panelShape =
                RoundedCornerShape(dimensionResource(com.android.internal.R.dimen.ichthys_radius))
            Modifier.background(colors.ground, panelShape)
                .border(hairlineWidth, colors.hairline, panelShape)
                .padding(PanelPadding)
        } else {
            Modifier
        }
    val range = (maxGamma - minGamma).coerceAtLeast(1)
    val showFloor = floorEnabled && autoMode
    // The pill is this slider's own default; QsSliderShape.DEFAULT's 12 dp is the stock 16 dp
    // track's radius and would square off a 56 dp one.
    val trackCorner =
        QsSliderShape.cornerDp(
                if (QsSliderShape.sanitize(shape) == QsSliderShape.DEFAULT) QsSliderShape.CIRCLE
                else shape,
                TrackHeight.value,
            )
            .dp

    // Stable state objects (never re-keyed) so a running gesture never writes into an orphaned
    // state; external changes are adopted by the effects below while no drag is in progress.
    val valueState = remember { mutableIntStateOf(gamma) }
    val floorState = remember { mutableIntStateOf(floorGamma) }
    var dragMode by remember { mutableStateOf(DragMode.None) }
    LaunchedEffect(gamma) { if (dragMode != DragMode.Main) valueState.intValue = gamma }
    LaunchedEffect(floorGamma) {
        if (dragMode != DragMode.Floor) floorState.intValue = floorGamma
    }

    val value = valueState.intValue
    val percent = ((value - minGamma) * 100f / range).roundToInt().coerceIn(0, 100)
    val floorPercent = ((floorState.intValue - minGamma) * 100f / range).roundToInt().coerceIn(0, 100)

    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnStop by rememberUpdatedState(onStop)
    val currentOnFloorFinished by rememberUpdatedState(onFloorFinished)
    val currentOnRestrictedClick by rememberUpdatedState(onRestrictedClick)
    val currentShowToast by rememberUpdatedState(showToast)
    val currentOverridden by rememberUpdatedState(overriddenByAppState)
    val currentShowFloor by rememberUpdatedState(showFloor)
    val currentInteractionSource by rememberUpdatedState(interactionSource)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sliderDescription = stringResource(R.string.accessibility_brightness)

    Column(
        modifier = modifier.then(panelModifier),
        verticalArrangement = Arrangement.spacedBy(LabelRowGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = stringResource(R.string.quick_settings_brightness_dialog_title),
                style =
                    TextStyle(
                        color = colors.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = fonts.text,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            BasicText(
                text = "$percent%",
                style =
                    TextStyle(
                        color = colors.value,
                        fontSize = 12.5.sp,
                        fontFamily = fonts.mono,
                    ),
            )
            Spacer(Modifier.weight(1f))
            if (showToggles) {
                if (nightMode != null) {
                    val scheduled = nightMode == IchthysNightLightController.Mode.SCHEDULED
                    val nightLabel = stringResource(R.string.quick_settings_night_display_label)
                    val nightState =
                        stringResource(
                            when (nightMode) {
                                IchthysNightLightController.Mode.OFF ->
                                    R.string.ichthys_night_light_mode_off
                                IchthysNightLightController.Mode.ON ->
                                    R.string.ichthys_night_light_mode_on
                                IchthysNightLightController.Mode.SCHEDULED ->
                                    R.string.ichthys_night_light_mode_scheduled
                            }
                        )
                    ShoalPill(
                        icon = ShoalPillIcon.Night,
                        // Like the tile: lit whenever Night Light is not off (a schedule is armed
                        // even while the screen is not tinted); the glyph shows the tint itself.
                        text = if (scheduled) "auto" else null,
                        filled = false,
                        active = nightMode != IchthysNightLightController.Mode.OFF,
                        iconLit = nightTinted,
                        enabled = true,
                        colors = colors,
                        textStyle = TextStyle(fontSize = 12.sp, fontFamily = fonts.mono),
                        contentDescription = "$nightLabel, $nightState",
                        onClick = onToggleNightLight,
                        // The tile's long press: its temperature and schedule dialog.
                        onLongClick = onNightLightLongClick,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                ShoalPill(
                    icon = ShoalPillIcon.Floor,
                    // The floor value stays readable next to its glyph while the floor is on.
                    text = if (floorEnabled) "$floorPercent%" else null,
                    filled = false,
                    active = floorEnabled,
                    enabled = enabled && autoMode,
                    colors = colors,
                    textStyle =
                        TextStyle(fontSize = 12.sp, fontFamily = fonts.mono),
                    contentDescription =
                        stringResource(
                            if (floorEnabled) R.string.ichthys_accessibility_brightness_floor_on
                            else R.string.ichthys_accessibility_brightness_floor_off
                        ),
                    onClick = onToggleFloor,
                )
                Spacer(Modifier.width(6.dp))
                ShoalPill(
                    icon = ShoalPillIcon.Auto,
                    text = null,
                    filled = true,
                    active = autoMode,
                    enabled = enabled,
                    colors = colors,
                    textStyle =
                        TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = fonts.text,
                        ),
                    contentDescription =
                        stringResource(
                            if (autoMode) R.string.ichthys_accessibility_auto_brightness_on
                            else R.string.ichthys_accessibility_auto_brightness_off
                        ),
                    onClick = onToggleAuto,
                )
            }
        }

        Box(
            modifier =
                sliderModifier
                    .fillMaxWidth()
                    .height(TrackHeight)
                    .sysuiResTag("slider")
                    .clip(RoundedCornerShape(trackCorner))
                    .semantics {
                        contentDescription = sliderDescription
                        stateDescription = "$percent%"
                        progressBarRangeInfo =
                            ProgressBarRangeInfo(
                                value.toFloat(),
                                minGamma.toFloat()..maxGamma.toFloat(),
                            )
                        if (enabled && !overriddenByAppState) {
                            setProgress { target ->
                                var g = target.roundToInt().coerceIn(minGamma, maxGamma)
                                if (currentShowFloor) g = maxOf(g, floorState.intValue)
                                valueState.intValue = g
                                currentOnDrag(g)
                                currentOnStop(g)
                                true
                            }
                        }
                    }
                    .pointerInput(enabled, isRestricted, minGamma, maxGamma, rtl) {
                        fun fracAt(x: Float): Float {
                            val f = (x / size.width.toFloat()).coerceIn(0f, 1f)
                            return if (rtl) 1f - f else f
                        }
                        fun gammaAt(x: Float): Int = minGamma + (fracAt(x) * range).roundToInt()
                        fun clampToFloor(g: Int): Int =
                            if (currentShowFloor) maxOf(g, floorState.intValue) else g
                        detectTapGestures(
                            onTap = { offset ->
                                when {
                                    isRestricted -> currentOnRestrictedClick()
                                    !enabled -> {}
                                    currentOverridden -> currentShowToast()
                                    else -> {
                                        val g = clampToFloor(gammaAt(offset.x))
                                        valueState.intValue = g
                                        currentOnDrag(g)
                                        currentOnStop(g)
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(enabled, isRestricted, minGamma, maxGamma, rtl) {
                        fun fracAt(x: Float): Float {
                            val f = (x / size.width.toFloat()).coerceIn(0f, 1f)
                            return if (rtl) 1f - f else f
                        }
                        fun gammaAt(x: Float): Int = minGamma + (fracAt(x) * range).roundToInt()
                        fun floorX(): Float {
                            val f = (floorState.intValue - minGamma).toFloat() / range
                            val x = f.coerceIn(0f, 1f) * size.width
                            return if (rtl) size.width - x else x
                        }
                        val grab = FloorGrab.toPx()
                        // The interaction of the brightness drag in progress, if any.
                        var dragInteraction: DragInteraction.Start? = null
                        fun finishDrag(cancelled: Boolean) {
                            when (dragMode) {
                                DragMode.Main -> currentOnStop(valueState.intValue)
                                DragMode.Floor -> currentOnFloorFinished(floorState.intValue)
                                DragMode.None -> {}
                            }
                            dragInteraction?.let { start ->
                                currentInteractionSource?.tryEmit(
                                    if (cancelled) DragInteraction.Cancel(start)
                                    else DragInteraction.Stop(start)
                                )
                            }
                            dragInteraction = null
                            dragMode = DragMode.None
                        }
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragMode =
                                    when {
                                        isRestricted -> {
                                            currentOnRestrictedClick()
                                            DragMode.None
                                        }
                                        !enabled -> DragMode.None
                                        currentShowFloor && abs(offset.x - floorX()) <= grab ->
                                            DragMode.Floor
                                        currentOverridden -> {
                                            currentShowToast()
                                            DragMode.None
                                        }
                                        else -> DragMode.Main
                                    }
                                if (dragMode == DragMode.Main) {
                                    val start = DragInteraction.Start()
                                    dragInteraction = start
                                    currentInteractionSource?.tryEmit(start)
                                    val g = maxOf(
                                        gammaAt(offset.x),
                                        if (currentShowFloor) floorState.intValue else minGamma,
                                    )
                                    valueState.intValue = g
                                    currentOnDrag(g)
                                }
                            },
                            onDragEnd = { finishDrag(cancelled = false) },
                            onDragCancel = { finishDrag(cancelled = true) },
                        ) { change, _ ->
                            when (dragMode) {
                                DragMode.Main -> {
                                    change.consume()
                                    var g = gammaAt(change.position.x)
                                    if (currentShowFloor) g = maxOf(g, floorState.intValue)
                                    if (g != valueState.intValue) {
                                        valueState.intValue = g
                                        currentOnDrag(g)
                                    }
                                }
                                DragMode.Floor -> {
                                    change.consume()
                                    floorState.intValue = gammaAt(change.position.x)
                                }
                                DragMode.None -> {}
                            }
                        }
                    }
                    .drawBehind {
                        drawShoalTrack(
                            valueFrac = (valueState.intValue - minGamma).toFloat() / range,
                            floorFrac =
                                if (showFloor)
                                    (floorState.intValue - minGamma).toFloat() / range
                                else null,
                            colors = colors,
                            corner = trackCorner.toPx(),
                            hairline = hairlineWidth.toPx(),
                            rtl = rtl,
                            alpha = if (enabled) 1f else 0.5f,
                        )
                    }
        )
    }
}

/**
 * The colours of the brightness block, all of them framework Shoal tokens. The tokens resolve by
 * the night mode of the context they are read through and are rewritten by the theme engine
 * (ShoalPalette) when the accent or the surface style changes, so there is nothing to observe here.
 */
@Immutable
private class ShoalBrightnessColors(
    /** Ground of the block's own panel, where it draws one. */
    val ground: Color,
    /** Track surface, and the surface the accent tints are mixed over. */
    val track: Color,
    /** Outline of the track and of an inactive pill. */
    val hairline: Color,
    /** "Brightness" and inactive pill text. */
    val label: Color,
    /** The percentage. */
    val value: Color,
    /** Sun glyph, floor bar, active pill. */
    val accent: Color,
    /** Text on an accent-filled pill. */
    val onAccent: Color,
    /** Filled part of the track. */
    val fill: Color,
    /** Part of the track below the auto-brightness floor. */
    val belowFloor: Color,
)

@Composable
private fun rememberShoalBrightnessColors(): ShoalBrightnessColors {
    val ground = colorResource(com.android.internal.R.color.ichthys_ink)
    val track = colorResource(com.android.internal.R.color.ichthys_card)
    val hairline = colorResource(com.android.internal.R.color.ichthys_hairline)
    val label = colorResource(com.android.internal.R.color.ichthys_body)
    val value = colorResource(com.android.internal.R.color.ichthys_muted)
    val accent = colorResource(com.android.internal.R.color.ichthys_accent)
    val onAccent = colorResource(com.android.internal.R.color.ichthys_on_accent)
    val fill = colorResource(com.android.internal.R.color.ichthys_accent_fill)
    val belowFloor = colorResource(com.android.internal.R.color.ichthys_accent_dim)
    return remember(ground, track, hairline, label, value, accent, onAccent, fill, belowFloor) {
        ShoalBrightnessColors(
            ground = ground,
            track = track,
            hairline = hairline,
            label = label,
            value = value,
            accent = accent,
            onAccent = onAccent,
            fill = fill,
            belowFloor = belowFloor,
        )
    }
}

private enum class DragMode {
    None,
    Main,
    Floor,
}

// Sizes are dp constants on purpose: a new SystemUI <dimen> shifts the dimen resource-ID table.
private val TrackHeight = 56.dp
private val LabelRowGap = 10.dp
private val PanelPadding = 14.dp
private val MarkerWidth = 4.dp
private val MarkerInset = 7.dp
private val SunStart = 18.dp
private val SunSize = 18.dp
private val FloorGrab = 20.dp

private fun DrawScope.drawShoalTrack(
    valueFrac: Float,
    floorFrac: Float?,
    colors: ShoalBrightnessColors,
    corner: Float,
    hairline: Float,
    rtl: Boolean,
    alpha: Float,
) {
    val w = size.width
    val h = size.height
    fun xOf(frac: Float): Float {
        val x = frac.coerceIn(0f, 1f) * w
        return if (rtl) w - x else x
    }
    // Track.
    drawRoundRect(color = colors.track, cornerRadius = CornerRadius(corner), alpha = alpha)
    // Filled part (accent fill tint). The box is clipped to the track shape, so a plain rect is
    // enough.
    val valueX = xOf(valueFrac)
    val fillLeft = if (rtl) valueX else 0f
    val fillRight = if (rtl) w else valueX
    if (fillRight > fillLeft) {
        drawRect(
            color = colors.fill,
            topLeft = Offset(fillLeft, 0f),
            size = Size(fillRight - fillLeft, h),
            alpha = alpha,
        )
    }
    // Region below the floor (dimmer tint, drawn over the fill from the start edge, x = 0 in
    // LTR) and the floor marker itself, inside the track. The marker is kept clear of the track's
    // ends (of the rounding, or of the outline on a square track), and the dimmer region always
    // ends exactly at the marker, so everything between the start edge and the bar is the
    // below-min tint.
    if (floorFrac != null) {
        val floorX = xOf(floorFrac)
        val mw = MarkerWidth.toPx()
        val inset = MarkerInset.toPx()
        val edge = maxOf(corner, inset + mw / 2f)
        val cx = floorX.coerceIn(edge, (w - edge).coerceAtLeast(edge))
        val belowLeft = if (rtl) cx else 0f
        val belowRight = if (rtl) w else cx
        if (belowRight > belowLeft) {
            drawRect(
                color = colors.belowFloor,
                topLeft = Offset(belowLeft, 0f),
                size = Size(belowRight - belowLeft, h),
                alpha = alpha,
            )
        }
        drawRoundRect(
            color = colors.accent,
            topLeft = Offset(cx - mw / 2f, inset),
            size = Size(mw, h - inset * 2f),
            cornerRadius = CornerRadius(mw / 2f),
            alpha = alpha,
        )
    }
    // Sun glyph at the start.
    val sun = SunSize.toPx()
    val sunCx = if (rtl) w - SunStart.toPx() - sun / 2f else SunStart.toPx() + sun / 2f
    drawSun(Offset(sunCx, h / 2f), sun, colors.accent, alpha)
    // Hairline.
    if (hairline > 0f) {
        drawRoundRect(
            color = colors.hairline,
            topLeft = Offset(hairline / 2f, hairline / 2f),
            size = Size(w - hairline, h - hairline),
            cornerRadius = CornerRadius((corner - hairline / 2f).coerceAtLeast(0f)),
            style = Stroke(width = hairline),
        )
    }
}

private fun DrawScope.drawSun(center: Offset, size: Float, color: Color, alpha: Float) {
    val core = size * 0.2f
    val rayInner = size * 0.34f
    val rayOuter = size * 0.48f
    val rayWidth = size * 0.1f
    drawCircle(color = color, radius = core, center = center, alpha = alpha)
    for (i in 0 until 8) {
        val a = i * PI / 4.0
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        drawLine(
            color = color,
            start = Offset(center.x + dx * rayInner, center.y + dy * rayInner),
            end = Offset(center.x + dx * rayOuter, center.y + dy * rayOuter),
            strokeWidth = rayWidth,
            cap = StrokeCap.Round,
            alpha = alpha,
        )
    }
}

/** 28 dp pill: outlined (accent when [active]) or accent-filled with on-accent text when [filled]. */
@Composable
private fun ShoalPill(
    icon: ShoalPillIcon,
    text: String?,
    filled: Boolean,
    active: Boolean,
    enabled: Boolean,
    iconLit: Boolean = active,
    colors: ShoalBrightnessColors,
    textStyle: TextStyle,
    contentDescription: String,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val fill = filled && active
    val contentColor =
        when {
            fill -> colors.onAccent
            active -> colors.accent
            else -> colors.label
        }.copy(alpha = if (enabled) 1f else 0.4f)
    val outline =
        when {
            fill -> Color.Transparent
            active -> colors.accent
            else -> colors.hairline
        }
    val outlineWidth = if (active) 1.5.dp else 1.dp
    Row(
        modifier =
            Modifier.height(28.dp)
                .clip(shape)
                .background(if (fill) colors.accent else Color.Transparent, shape)
                .border(outlineWidth, outline, shape)
                .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)
                .semantics { this.contentDescription = contentDescription }
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon == ShoalPillIcon.Night) {
            // The Night Light tile's own glyph (NightDisplayTileMapper), so the pill and the tile
            // can never show two different pictures for the same thing.
            Image(
                painter =
                    painterResource(
                        if (iconLit) R.drawable.ic_qs_ichthys_nightlight
                        else R.drawable.ic_qs_ichthys_nightlight_off
                    ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(contentColor),
                modifier = Modifier.size(ShoalPillIconSize),
            )
        } else {
            Canvas(Modifier.size(ShoalPillIconSize)) { drawShoalPillIcon(icon, contentColor) }
        }
        if (text != null) {
            Spacer(Modifier.width(5.dp))
            BasicText(text = text, style = textStyle.copy(color = contentColor))
        }
    }
}

/** The glyphs of the brightness row's pills. */
private enum class ShoalPillIcon {
    /** Night Light: the tile's drawable (ic_qs_ichthys_nightlight / _off), not drawn here. */
    Night,
    /** Brightness floor: a sun resting on a bar it cannot go below. */
    Floor,
    /** Auto brightness: a sun with an "A" in it. */
    Auto,
}

private val ShoalPillIconSize = 16.dp

/** Draws [icon] in the 16 dp box of this scope, in one colour, as 1.5 dp round strokes. */
private fun DrawScope.drawShoalPillIcon(icon: ShoalPillIcon, color: Color) {
    val u = size.minDimension / 16f
    val w = 1.5f * u
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = w) =
        drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), width, StrokeCap.Round)
    // Rays of a sun centred on (cx, cy), from radius r1 to r2, for the given angles (degrees,
    // 0 = right, counter-clockwise on screen being negative y).
    fun rays(cx: Float, cy: Float, r1: Float, r2: Float, vararg degrees: Int) {
        for (d in degrees) {
            val a = d * PI / 180.0
            val dx = cos(a).toFloat()
            val dy = -sin(a).toFloat()
            line(cx + dx * r1, cy + dy * r1, cx + dx * r2, cy + dy * r2)
        }
    }
    when (icon) {
        ShoalPillIcon.Night -> Unit // a drawable, see ShoalPill
        ShoalPillIcon.Floor -> {
            drawCircle(
                color = color,
                radius = 2.6f * u,
                center = Offset(8f * u, 7.4f * u),
                style = Stroke(width = w),
            )
            rays(8f, 7.4f, 4.3f, 5.8f, 0, 45, 90, 135, 180)
            // The floor.
            line(2f, 13.6f, 14f, 13.6f, 2f * u)
        }
        ShoalPillIcon.Auto -> {
            drawCircle(
                color = color,
                radius = 4.6f * u,
                center = Offset(8f * u, 8f * u),
                style = Stroke(width = w),
            )
            rays(8f, 8f, 6.2f, 7.4f, 0, 45, 90, 135, 180, 225, 270, 315)
            // "A"
            val a = 1.2f * u
            line(6.1f, 10.2f, 8f, 5.6f, a)
            line(8f, 5.6f, 9.9f, 10.2f, a)
            line(6.8f, 8.6f, 9.2f, 8.6f, a)
        }
    }
}
