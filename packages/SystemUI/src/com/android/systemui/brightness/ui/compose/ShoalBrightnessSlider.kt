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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ShoalQsColors
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalAccent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsFonts
import com.android.systemui.res.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * IchthysOS "Shoal" brightness block (ichthys_qs_style = "shoal"):
 * - a label row: "Brightness", the value in the mono face, then the "min NN%" (floor) and "Auto"
 *   pills;
 * - a 56 dp pill track (#0c1013 + 1 dp hairline) whose filled part is a dark accent tint, the part
 *   below the auto-brightness floor a darker tint, the floor itself a 4 dp accent bar drawn INSIDE
 *   the track, and an accent sun glyph at the start.
 *
 * Gestures: a horizontal drag that starts on (or near) the floor bar moves the floor; any other
 * drag or a tap sets the brightness. Taps are handled separately from drags so a vertical swipe
 * that starts on the slider still scrolls / collapses the shade.
 *
 * All values are in the slider's gamma space ([minGamma]..[maxGamma]).
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
    onDrag: (Int) -> Unit,
    onStop: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sliderModifier: Modifier = Modifier,
) {
    val accent = rememberShoalAccent()
    val fonts = rememberShoalQsFonts()
    val range = (maxGamma - minGamma).coerceAtLeast(1)
    val showFloor = floorEnabled && autoMode

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
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = stringResource(R.string.quick_settings_brightness_dialog_title),
                style =
                    TextStyle(
                        color = ShoalQsColors.Label,
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
                        color = ShoalQsColors.Muted,
                        fontSize = 12.5.sp,
                        fontFamily = fonts.mono,
                    ),
            )
            Spacer(Modifier.weight(1f))
            if (showToggles) {
                ShoalPill(
                    text = if (floorEnabled) "min $floorPercent%" else "min",
                    filled = false,
                    active = floorEnabled,
                    enabled = enabled && autoMode,
                    accent = accent,
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
                    text = "Auto",
                    filled = true,
                    active = autoMode,
                    enabled = enabled,
                    accent = accent,
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

        val trackShape = RoundedCornerShape(TrackHeight / 2)
        Box(
            modifier =
                sliderModifier
                    .fillMaxWidth()
                    .height(TrackHeight)
                    .clip(trackShape)
                    .semantics {
                        contentDescription = "$percent%"
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
                                    val g = maxOf(
                                        gammaAt(offset.x),
                                        if (currentShowFloor) floorState.intValue else minGamma,
                                    )
                                    valueState.intValue = g
                                    currentOnDrag(g)
                                }
                            },
                            onDragEnd = {
                                when (dragMode) {
                                    DragMode.Main -> currentOnStop(valueState.intValue)
                                    DragMode.Floor -> currentOnFloorFinished(floorState.intValue)
                                    DragMode.None -> {}
                                }
                                dragMode = DragMode.None
                            },
                            onDragCancel = {
                                when (dragMode) {
                                    DragMode.Main -> currentOnStop(valueState.intValue)
                                    DragMode.Floor -> currentOnFloorFinished(floorState.intValue)
                                    DragMode.None -> {}
                                }
                                dragMode = DragMode.None
                            },
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
                            accent = accent,
                            rtl = rtl,
                            alpha = if (enabled) 1f else 0.5f,
                        )
                    }
        )
    }
}

private enum class DragMode {
    None,
    Main,
    Floor,
}

private val TrackHeight = 56.dp
private val MarkerWidth = 4.dp
private val MarkerInset = 7.dp
private val SunStart = 18.dp
private val SunSize = 18.dp
private val FloorGrab = 20.dp

private fun DrawScope.drawShoalTrack(
    valueFrac: Float,
    floorFrac: Float?,
    accent: Color,
    rtl: Boolean,
    alpha: Float,
) {
    val w = size.width
    val h = size.height
    val radius = CornerRadius(h / 2f)
    fun xOf(frac: Float): Float {
        val x = frac.coerceIn(0f, 1f) * w
        return if (rtl) w - x else x
    }
    // Track.
    drawRoundRect(color = ShoalQsColors.Track, cornerRadius = radius, alpha = alpha)
    // Filled part (dark accent tint). The box is clipped to the pill, so a plain rect is enough.
    val valueX = xOf(valueFrac)
    val fillLeft = if (rtl) valueX else 0f
    val fillRight = if (rtl) w else valueX
    if (fillRight > fillLeft) {
        drawRect(
            color = ShoalQsColors.fillTint(accent),
            topLeft = Offset(fillLeft, 0f),
            size = Size(fillRight - fillLeft, h),
            alpha = alpha,
        )
    }
    // Region below the floor (darker tint, drawn over the fill from the start edge, x = 0 in
    // LTR) and the floor marker itself, inside the track. The marker is kept clear of the rounded
    // ends, and the darker region always ends exactly at the marker, so everything between the
    // start edge and the bar is the below-min tint.
    if (floorFrac != null) {
        val floorX = xOf(floorFrac)
        val mw = MarkerWidth.toPx()
        val inset = MarkerInset.toPx()
        val cx = floorX.coerceIn(h / 2f, (w - h / 2f).coerceAtLeast(h / 2f))
        val belowLeft = if (rtl) cx else 0f
        val belowRight = if (rtl) w else cx
        if (belowRight > belowLeft) {
            drawRect(
                color = ShoalQsColors.belowMinTint(accent),
                topLeft = Offset(belowLeft, 0f),
                size = Size(belowRight - belowLeft, h),
                alpha = alpha,
            )
        }
        drawRoundRect(
            color = accent,
            topLeft = Offset(cx - mw / 2f, inset),
            size = Size(mw, h - inset * 2f),
            cornerRadius = CornerRadius(mw / 2f),
            alpha = alpha,
        )
    }
    // Sun glyph at the start.
    val sun = SunSize.toPx()
    val sunCx = if (rtl) w - SunStart.toPx() - sun / 2f else SunStart.toPx() + sun / 2f
    drawSun(Offset(sunCx, h / 2f), sun, accent, alpha)
    // 1 dp hairline.
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = ShoalQsColors.Hairline,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(w - stroke, h - stroke),
        cornerRadius = CornerRadius(h / 2f - stroke / 2f),
        style = Stroke(width = stroke),
    )
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

/** 28 dp pill: outlined (accent when [active]) or accent-filled with ink text when [filled]. */
@Composable
private fun ShoalPill(
    text: String,
    filled: Boolean,
    active: Boolean,
    enabled: Boolean,
    accent: Color,
    textStyle: TextStyle,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val fill = filled && active
    val textColor =
        when {
            fill -> ShoalQsColors.Ink
            active -> accent
            else -> ShoalQsColors.Label
        }
    val outline =
        when {
            fill -> Color.Transparent
            active -> accent
            else -> ShoalQsColors.Hairline
        }
    val outlineWidth = if (active) 1.5.dp else 1.dp
    Box(
        modifier =
            Modifier.height(28.dp)
                .clip(shape)
                .background(if (fill) accent else Color.Transparent, shape)
                .border(outlineWidth, outline, shape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { this.contentDescription = contentDescription }
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = textStyle.copy(color = textColor.copy(alpha = if (enabled) 1f else 0.4f)),
        )
    }
}
