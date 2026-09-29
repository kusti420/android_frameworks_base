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

package com.android.systemui.qs.tiles.dialog.colordisplay

import android.graphics.Typeface
import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import com.android.systemui.util.Assert
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/*
 * IchthysOS shared "slider dialog" for the Night Light and Extra Dim QS tiles (long-press).
 *
 * Styling: Material colour roles for standard text / switches, and the framework Shoal tokens
 * (com.android.internal.R.color.ichthys_*, which alias Material roles when the Shoal palette is not
 * applied) for the slider track, hairlines and accent. No hard-coded colours or font names.
 */

// region Dialog delegate

/**
 * A [SystemUIDialog] hosting compose [Content], shown with the tile's expand animation. Structure
 * follows FlashlightDialogDelegate.
 */
abstract class IchthysSliderDialogDelegate(
    private val mainCoroutineContext: CoroutineContext,
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val shadeDialogContextInteractor: ShadeDialogContextInteractor,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val jankTag: String,
) : SystemUIDialog.Delegate {
    private var currentDialog: ComponentSystemUIDialog? = null

    @Composable protected abstract fun Content(dialog: SystemUIDialog)

    /** Called on the main thread when the dialog stops (dismissed or hidden). */
    protected open fun onDialogStopped() {}

    override fun createDialog(): SystemUIDialog {
        Assert.isMainThread()
        currentDialog?.dismiss()
        val dialog =
            sysuiDialogFactory.create(context = shadeDialogContextInteractor.context) {
                Content(it)
            }
        dialog.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    Assert.isMainThread()
                    if (currentDialog === dialog) currentDialog = null
                    onDialogStopped()
                }
            }
        )
        currentDialog = dialog
        return dialog
    }

    /** Creates and shows the dialog; may be called from any context. */
    suspend fun showDialog(expandable: Expandable? = null) {
        withContext(mainCoroutineContext) {
            val dialog = createDialog()
            expandable
                ?.dialogTransitionController(
                    DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, jankTag)
                )
                ?.let { controller ->
                    if (TransitionAnimator.dynamicTargetResolutionEnabled()) {
                        dialogTransitionAnimator.show(
                            dialog,
                            expandable::dialogTransitionController,
                            controller.cuj,
                        )
                    } else {
                        dialogTransitionAnimator.show(dialog, controller)
                    }
                } ?: dialog.show()
        }
    }
}

// endregion

// region Tokens

@Immutable
class IchthysDialogTokens(
    val text: Color,
    val muted: Color,
    val track: Color,
    val hairline: Color,
    val accent: Color,
    val onAccent: Color,
    val accentFill: Color,
    val radiusSmall: Dp,
    val hairlineWidth: Dp,
    val textFont: FontFamily,
    val monoFont: FontFamily,
)

@Composable
fun rememberIchthysDialogTokens(): IchthysDialogTokens {
    val context = LocalContext.current
    val text = colorResource(com.android.internal.R.color.ichthys_text)
    val muted = colorResource(com.android.internal.R.color.ichthys_muted)
    val track = colorResource(com.android.internal.R.color.ichthys_track)
    val hairline = colorResource(com.android.internal.R.color.ichthys_hairline)
    val accent = colorResource(com.android.internal.R.color.ichthys_accent)
    val onAccent = colorResource(com.android.internal.R.color.ichthys_on_accent)
    val accentFill = colorResource(com.android.internal.R.color.ichthys_accent_fill)
    val radiusSmall = dimensionResource(com.android.internal.R.dimen.ichthys_radius_small)
    val hairlineWidth = dimensionResource(com.android.internal.R.dimen.ichthys_hairline_width)
    val fonts =
        remember(context) {
            val res = context.resources
            fun family(id: Int): FontFamily {
                val name = res.getString(id)
                return if (name.isEmpty()) FontFamily.Default
                else FontFamily(Typeface.create(name, Typeface.NORMAL))
            }
            family(com.android.internal.R.string.ichthys_font_text) to
                family(com.android.internal.R.string.ichthys_font_mono)
        }
    return IchthysDialogTokens(
        text = text,
        muted = muted,
        track = track,
        hairline = hairline,
        accent = accent,
        onAccent = onAccent,
        accentFill = accentFill,
        radiusSmall = radiusSmall,
        hairlineWidth = hairlineWidth,
        textFont = fonts.first,
        monoFont = fonts.second,
    )
}

// endregion

// region Slider

private val TrackHeight = 56.dp
private val ThumbWidth = 4.dp
private val ThumbInset = 7.dp
/** Horizontal inset of the value range inside the pill, so the thumb clears the rounded ends. */
private val TrackPad = 16.dp

/**
 * Horizontal Shoal-style slider: a label row (label, value in the mono token font) above a 56 dp
 * pill track with a filled part and a 4 dp thumb bar drawn inside the track.
 *
 * @param gradient optional colours drawn across the track (start to end); the unfilled part shows
 *   them faintly, the filled part fully.
 * @param step value quantum; a haptic tick plays on quantum changes (thinned for fine ranges).
 * @param startLabel optional caption under the start end of the track (e.g. "Dimmer").
 * @param endLabel optional caption under the end of the track (e.g. "Brighter").
 */
@Composable
fun IchthysPillSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    valueText: (Int) -> String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    enabled: Boolean = true,
    gradient: List<Color>? = null,
    thumbColor: Color? = null,
    onDragStarted: () -> Unit = {},
    onValueChangeFinished: (Int) -> Unit = {},
    startLabel: String? = null,
    endLabel: String? = null,
) {
    val tokens = rememberIchthysDialogTokens()
    val view = LocalView.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val min = valueRange.first
    val max = valueRange.last.coerceAtLeast(min + 1)
    val range = max - min
    val q = step.coerceAtLeast(1)
    val totalSteps = (range / q).coerceAtLeast(1)
    val tickEvery = (totalSteps / MAX_TICKS).coerceAtLeast(1)

    val valueState = remember { mutableIntStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) valueState.intValue = value }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val currentOnDragStarted by rememberUpdatedState(onDragStarted)

    fun quantize(v: Int): Int {
        val steps = ((v - min).toFloat() / q).roundToInt()
        return (min + steps * q).coerceIn(min, max)
    }

    fun update(v: Int) {
        val nv = quantize(v)
        val old = valueState.intValue
        if (nv == old) return
        valueState.intValue = nv
        if ((nv - min) / q / tickEvery != (old - min) / q / tickEvery || nv == min || nv == max) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
        currentOnValueChange(nv)
    }

    val shownText = valueText(valueState.intValue)
    val alpha = if (enabled) 1f else 0.5f

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style =
                    TextStyle(
                        color = tokens.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = tokens.textFont,
                    ),
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = shownText,
                style = TextStyle(color = tokens.muted, fontSize = 13.sp, fontFamily = tokens.monoFont),
            )
        }

        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(TrackHeight)
                    .clip(RoundedCornerShape(TrackHeight / 2))
                    .semantics {
                        contentDescription = label
                        stateDescription = shownText
                        progressBarRangeInfo =
                            ProgressBarRangeInfo(
                                valueState.intValue.toFloat(),
                                min.toFloat()..max.toFloat(),
                                (totalSteps - 1).coerceIn(0, 200),
                            )
                        if (enabled) {
                            setProgress { target ->
                                update(target.roundToInt())
                                currentOnFinished(valueState.intValue)
                                true
                            }
                        }
                    }
                    .pointerInput(enabled, min, max, q, rtl) {
                        if (!enabled) return@pointerInput
                        val pad = TrackPad.toPx()
                        fun valueAt(x: Float): Int {
                            val usable = (size.width - 2 * pad).coerceAtLeast(1f)
                            var f = ((x - pad) / usable).coerceIn(0f, 1f)
                            if (rtl) f = 1f - f
                            return min + (f * range).roundToInt()
                        }
                        detectTapGestures(
                            onTap = { offset ->
                                currentOnDragStarted()
                                update(valueAt(offset.x))
                                currentOnFinished(valueState.intValue)
                            }
                        )
                    }
                    .pointerInput(enabled, min, max, q, rtl) {
                        if (!enabled) return@pointerInput
                        val pad = TrackPad.toPx()
                        fun valueAt(x: Float): Int {
                            val usable = (size.width - 2 * pad).coerceAtLeast(1f)
                            var f = ((x - pad) / usable).coerceIn(0f, 1f)
                            if (rtl) f = 1f - f
                            return min + (f * range).roundToInt()
                        }
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                currentOnDragStarted()
                                update(valueAt(offset.x))
                            },
                            onDragEnd = {
                                dragging = false
                                currentOnFinished(valueState.intValue)
                            },
                            onDragCancel = {
                                dragging = false
                                currentOnFinished(valueState.intValue)
                            },
                        ) { change, _ ->
                            change.consume()
                            update(valueAt(change.position.x))
                        }
                    }
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        val pad = TrackPad.toPx()
                        val frac = (valueState.intValue - min).toFloat() / range
                        val f = if (rtl) 1f - frac.coerceIn(0f, 1f) else frac.coerceIn(0f, 1f)
                        val x = pad + f * (w - 2 * pad)
                        val corner = CornerRadius(h / 2f)
                        drawRoundRect(color = tokens.track, cornerRadius = corner, alpha = alpha)
                        val fillLeft = if (rtl) x else 0f
                        val fillRight = if (rtl) w else x
                        if (gradient != null && gradient.size >= 2) {
                            val brush =
                                Brush.horizontalGradient(
                                    if (rtl) gradient.reversed() else gradient,
                                    startX = 0f,
                                    endX = w,
                                )
                            drawRect(brush = brush, alpha = 0.22f * alpha)
                            if (fillRight > fillLeft) {
                                drawRect(
                                    brush = brush,
                                    topLeft = Offset(fillLeft, 0f),
                                    size = Size(fillRight - fillLeft, h),
                                    alpha = 0.9f * alpha,
                                )
                            }
                        } else if (fillRight > fillLeft) {
                            drawRect(
                                color = tokens.accentFill,
                                topLeft = Offset(fillLeft, 0f),
                                size = Size(fillRight - fillLeft, h),
                                alpha = alpha,
                            )
                        }
                        val tw = ThumbWidth.toPx()
                        val inset = ThumbInset.toPx()
                        drawRoundRect(
                            color = thumbColor ?: tokens.accent,
                            topLeft = Offset(x - tw / 2f, inset),
                            size = Size(tw, h - inset * 2f),
                            cornerRadius = CornerRadius(tw / 2f),
                            alpha = alpha,
                        )
                        val stroke = tokens.hairlineWidth.toPx()
                        if (stroke > 0f) {
                            drawRoundRect(
                                color = tokens.hairline,
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(w - stroke, h - stroke),
                                cornerRadius = CornerRadius(h / 2f - stroke / 2f),
                                style = Stroke(width = stroke),
                            )
                        }
                    }
        )
        if (startLabel != null || endLabel != null) {
            val captionStyle =
                TextStyle(color = tokens.muted, fontSize = 12.sp, fontFamily = tokens.textFont)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = startLabel.orEmpty(),
                    style = captionStyle,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = endLabel.orEmpty(), style = captionStyle, maxLines = 1)
            }
        }
    }
}

private const val MAX_TICKS = 40

// endregion

// region Rows

/** Single-choice segmented control (e.g. Off / On / Scheduled). */
@Composable
fun <T> IchthysSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = rememberIchthysDialogTokens()
    val shape = RoundedCornerShape(tokens.radiusSmall)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(shape)
                .border(tokens.hairlineWidth, tokens.hairline, shape)
                .selectableGroup()
    ) {
        options.forEach { (key, text) ->
            val isSelected = key == selected
            Box(
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .background(if (isSelected) tokens.accent else Color.Transparent)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { if (!isSelected) onSelect(key) },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style =
                        TextStyle(
                            color = if (isSelected) tokens.onAccent else tokens.text,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            fontFamily = tokens.textFont,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** Label + Material switch; the whole row toggles. */
@Composable
fun IchthysSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    val tokens = rememberIchthysDialogTokens()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(tokens.radiusSmall))
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = tokens.textFont),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = tokens.textFont),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // The row handles the toggle (and its semantics); the switch is visual only.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A row that opens a Settings page. */
@Composable
fun IchthysLinkRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = rememberIchthysDialogTokens()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(tokens.radiusSmall))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = tokens.textFont),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Small muted note line (e.g. "Preview"). */
@Composable
fun IchthysNote(text: String, modifier: Modifier = Modifier) {
    val tokens = rememberIchthysDialogTokens()
    Text(
        text = text,
        style = TextStyle(color = tokens.muted, fontSize = 12.sp, fontFamily = tokens.monoFont),
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

// endregion
