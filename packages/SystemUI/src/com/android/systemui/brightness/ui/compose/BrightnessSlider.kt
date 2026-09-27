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

package com.android.systemui.brightness.ui.compose

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.settingslib.display.BrightnessUtils
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.lifecycle.DisposableEffectWithLifecycle
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.sliderPercentage
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.drawInOverlay
import com.android.systemui.biometrics.Utils.toBitmap
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.brightness.ui.compose.AnimationSpecs.IconAppearSpec
import com.android.systemui.brightness.ui.compose.AnimationSpecs.IconDisappearSpec
import com.android.systemui.brightness.ui.compose.InternalDimensions.IconPadding
import com.android.systemui.brightness.QsSliderShape
import com.android.systemui.brightness.ui.compose.InternalDimensions.SliderTrackRoundedCorner
import com.android.systemui.brightness.ui.compose.InternalDimensions.ThumbTrackGapSize
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.shared.colors.SystemUISliderColors
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.util.policy.PolicyRestriction
// IchthysOS: crDroid's "Auto brightness icon" switch is a LineageSecureSettingSwitchPreference, so
// its value lives in the lineagesettings provider, not android.provider.Settings.
import lineageos.providers.LineageSettings
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
@VisibleForTesting
fun BrightnessSlider(
    gammaValue: Int,
    valueRange: IntRange,
    iconResProvider: (Float) -> Int,
    imageLoader: suspend (Int, Context) -> Icon.Loaded?,
    restriction: PolicyRestriction,
    onRestrictedClick: (PolicyRestriction.Restricted) -> Unit,
    onDrag: (Int) -> Unit,
    onStop: (Int) -> Unit,
    overriddenByAppState: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showToast: () -> Unit = {},
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    dimensions: BrightnessSliderDimensions = BrightnessSliderDimensions.Default,
    // IchthysOS: auto-brightness minimum floor (second handle).
    showFloorHandle: Boolean = false,
    floorFraction: Float = 0f,
    onFloorFractionFinished: (Float) -> Unit = {},
) {
    var value by remember(gammaValue) { mutableIntStateOf(gammaValue) }
    val animatedValue by
        animateFloatAsState(targetValue = value.toFloat(), label = "BrightnessSliderAnimatedValue")
    val floatValueRange = valueRange.first.toFloat()..valueRange.last.toFloat()
    val isRestricted = restriction is PolicyRestriction.Restricted
    val contentDescription = stringResource(R.string.accessibility_brightness)
    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel: SliderHapticsViewModel =
        rememberViewModel(traceName = "SliderHapticsViewModel") {
            hapticsViewModelFactory.create(
                interactionSource,
                floatValueRange,
                Orientation.Horizontal,
                SliderHapticFeedbackConfig(
                    maxVelocityToScale = 1f /* slider progress(from 0 to 1) per sec */
                ),
                SeekableSliderTrackerConfig(),
            )
        }
    val colors = SystemUISliderColors.Defaults
    // IchthysOS: crDroid's brightness-slider shape + haptic switches.
    val crDroidSlider = rememberCrDroidSliderState(LocalContext.current)
    val trackCornerSize =
        QsSliderShape.cornerDp(crDroidSlider.shape, dimensions.trackHeight.value).dp

    // The value state is recreated every time gammaValue changes, so we recreate this derivedState
    // We have to use value as that's the value that changes when the user is dragging (gammaValue
    // is always the starting value: actual (not temporary) brightness).
    val iconRes by
        remember(gammaValue, valueRange) {
            derivedStateOf {
                val percentage =
                    (value - valueRange.first) * 100f / (valueRange.last - valueRange.first)
                iconResProvider(percentage)
            }
        }
    val context = LocalContext.current
    val painter: Painter by
        produceState<Painter>(
            initialValue = ColorPainter(Color.Transparent),
            key1 = iconRes,
            key2 = context,
        ) {
            val icon: Icon.Loaded? = imageLoader(iconRes, context)
            if (icon != null) {
                val bitmap = icon.drawable.toBitmap()?.asImageBitmap()
                if (bitmap != null) {
                    this@produceState.value = BitmapPainter(bitmap)
                }
            }
        }
    val activeIconColor = colors.activeTickColor
    val iconSize = dimensions.iconSize
    val inactiveIconColor = colors.inactiveTickColor
    // Offset from the right
    val trackIcon: DrawScope.(Offset, Color, Float) -> Unit = remember {
        { offset, color, alpha ->
            val rtl = layoutDirection == LayoutDirection.Rtl
            scale(if (rtl) -1f else 1f, 1f) {
                translate(offset.x - IconPadding.toPx() - iconSize.toSize().width, offset.y) {
                    with(painter) {
                        draw(
                            iconSize.toSize(),
                            colorFilter = ColorFilter.tint(color),
                            alpha = alpha,
                        )
                    }
                }
            }
        }
    }

    // IchthysOS: track the slider width + height so the floor handle can be positioned as a fraction
    // of the track and its knob can hang in reserved space below the slider.
    var sliderWidthPx by remember { mutableIntStateOf(0) }
    var sliderHeightPx by remember { mutableIntStateOf(0) }
    // IchthysOS: live position of the floor handle while it is being dragged.
    //
    // This state object MUST NOT be keyed on [floorFraction] (it used to be
    // `remember(floorFraction) { … }`). The running `detectHorizontalDragGestures` block below
    // captures the onDrag/onDragEnd lambdas — and therefore this state *object* — from the
    // composition in which its `Modifier.pointerInput(key)` node was created, and is only restarted
    // when that key changes. Re-keying the remember on the settings value therefore orphaned the
    // state the gesture was still writing into the moment the setting changed (i.e. right after the
    // first successful drag): the handle stopped following the finger and only "jumped" to the new
    // position on release, when onDragEnd finally wrote the setting and a fresh state was created
    // from it. Keep one stable state and adopt external changes through the effect below.
    val floorFracState = remember { mutableFloatStateOf(floorFraction) }
    // Only re-runs when the settings-backed value actually changes, so it never fights a drag in
    // progress (nothing writes the floor setting mid-drag) and never snaps the handle back on
    // release before the content observer has delivered the new value.
    LaunchedEffect(floorFraction) { floorFracState.floatValue = floorFraction }

    Box(
        modifier = modifier.onSizeChanged { sliderWidthPx = it.width },
        contentAlignment = Alignment.TopStart,
    ) {
    Column(Modifier.fillMaxWidth()) {
    Box(
        modifier = Modifier.fillMaxWidth().onSizeChanged { sliderHeightPx = it.height },
        contentAlignment = Alignment.CenterStart,
    ) {
    Slider(
        value = animatedValue,
        valueRange = floatValueRange,
        enabled = enabled,
        colors = colors,
        onValueChange = {
            if (enabled) {
                if (!overriddenByAppState) {
                    if (crDroidSlider.haptics) hapticsViewModel.onValueChange(it)
                    var v = it.toInt()
                    // IchthysOS: the floor is a HARD minimum. Don't let the main thumb drag below the
                    // orange floor bar — otherwise it strands there, out of sync with the actual
                    // brightness (which the auto-brightness floor clamps up), i.e. "stuck below the bar".
                    if (showFloorHandle) {
                        val floorGamma =
                            (floorFracState.floatValue * BrightnessUtils.GAMMA_SPACE_MAX)
                                .roundToInt()
                        if (v < floorGamma) v = floorGamma
                    }
                    value = v
                    onDrag(value)
                }
            }
        },
        onValueChangeFinished = {
            if (enabled) {
                if (!overriddenByAppState) {
                    if (crDroidSlider.haptics) hapticsViewModel.onValueChangeEnded()
                    onStop(value)
                }
            }
        },
        modifier =
            Modifier.fillMaxWidth()
                .sysuiResTag("slider")
                .semantics(mergeDescendants = true) {
                    this.text = AnnotatedString(contentDescription)
                }
                .sliderPercentage {
                    (value - valueRange.first).toFloat() / (valueRange.last - valueRange.first)
                }
                .thenIf(isRestricted) {
                    Modifier.clickable {
                        if (restriction is PolicyRestriction.Restricted) {
                            onRestrictedClick(restriction)
                        }
                    }
                },
        interactionSource = interactionSource,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                enabled = enabled,
                thumbSize = DpSize(dimensions.thumbWidth, dimensions.thumbHeight),
                colors = colors,
            )
        },
        track = { sliderState ->
            var showIconActive by remember { mutableStateOf(true) }
            val iconActiveAlphaAnimatable = remember {
                Animatable(
                    initialValue = 1f,
                    typeConverter = Float.VectorConverter,
                    label = "iconActiveAlpha",
                )
            }

            val iconInactiveAlphaAnimatable = remember {
                Animatable(
                    initialValue = 0f,
                    typeConverter = Float.VectorConverter,
                    label = "iconInactiveAlpha",
                )
            }

            LaunchedEffect(iconActiveAlphaAnimatable, iconInactiveAlphaAnimatable, showIconActive) {
                if (showIconActive) {
                    launch { iconActiveAlphaAnimatable.appear() }
                    launch { iconInactiveAlphaAnimatable.disappear() }
                } else {
                    launch { iconActiveAlphaAnimatable.disappear() }
                    launch { iconInactiveAlphaAnimatable.appear() }
                }
            }

            SliderDefaults.Track(
                sliderState = sliderState,
                modifier =
                    Modifier.motionTestValues {
                            iconActiveAlphaAnimatable.value exportAs
                                BrightnessSliderMotionTestKeys.ActiveIconAlpha
                            iconInactiveAlphaAnimatable.value exportAs
                                BrightnessSliderMotionTestKeys.InactiveIconAlpha
                        }
                        .height(dimensions.trackHeight)
                        .drawWithContent {
                            drawContent()

                            val yOffset = size.height / 2 - iconSize.toSize().height / 2
                            val activeTrackStart = 0f
                            val activeTrackEnd =
                                size.width * sliderState.coercedValueAsFraction -
                                    ThumbTrackGapSize.toPx()
                            val inactiveTrackStart = activeTrackEnd + ThumbTrackGapSize.toPx() * 2
                            val inactiveTrackEnd = size.width

                            val activeTrackWidth = activeTrackEnd - activeTrackStart
                            val inactiveTrackWidth = inactiveTrackEnd - inactiveTrackStart

                            if (
                                iconSize.toSize().width <
                                    inactiveTrackWidth - IconPadding.toPx() * 2
                            ) {
                                showIconActive = false
                                trackIcon(
                                    Offset(inactiveTrackEnd, yOffset),
                                    inactiveIconColor,
                                    iconInactiveAlphaAnimatable.value,
                                )
                            } else if (
                                iconSize.toSize().width < activeTrackWidth - IconPadding.toPx() * 2
                            ) {
                                showIconActive = true
                                trackIcon(
                                    Offset(activeTrackEnd, yOffset),
                                    activeIconColor,
                                    iconActiveAlphaAnimatable.value,
                                )
                            }
                        },
                trackCornerSize = trackCornerSize,
                trackInsideCornerSize = 2.dp,
                drawStopIndicator = null,
                thumbTrackGapSize = ThumbTrackGapSize,
                colors = colors,
            )
        },
    )
    } // end slider Box
        // Reserve space below the slider so the floor knob has somewhere to hang, and add a little
        // gap between the brightness bar and the QS tiles.
        if (showFloorHandle) Spacer(Modifier.height(FloorKnobReserve))
    } // end Column

    // IchthysOS: second (floor) handle. The vertical bar marks the floor on the track and is
    // NON-interactive (touches pass through to the main slider); only the KNOB below the slider is
    // draggable, so the floor handle never fights the main brightness thumb.
    if (showFloorHandle && sliderWidthPx > 0 && sliderHeightPx > 0) {
        BrightnessFloorHandle(
            // Passed as a lambda, not a value: it is read inside the handle's `Modifier.offset {}`
            // placement block, so a drag re-places the handle every frame through the layout phase
            // alone — it can never be lost to a skipped recomposition.
            fraction = { floorFracState.floatValue },
            widthPx = sliderWidthPx,
            sliderHeightPx = sliderHeightPx,
            enabled = enabled,
            onDrag = { deltaPx ->
                floorFracState.floatValue =
                    (floorFracState.floatValue + deltaPx / sliderWidthPx.toFloat()).coerceIn(0f, 1f)
            },
            // The setting is written once, here, and never per drag frame: doing it live would spam
            // the settings provider and every observer of the floor value.
            onDragEnd = { onFloorFractionFinished(floorFracState.floatValue) },
        )
    }
    }

    val currentShowToast by rememberUpdatedState(showToast)
    // Showing the warning toast if the current running app window has controlled the
    // brightness value.
    LaunchedEffect(interactionSource, overriddenByAppState) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && overriddenByAppState) {
                currentShowToast()
            }
        }
    }
}

private fun Modifier.sliderBackground(
    backgroundFrameSize: DpSize,
    backgroundRoundedCorner: Dp,
    color: Color,
) = drawWithCache {
    val offsetAround = backgroundFrameSize.toSize()
    val newSize = Size(size.width + 2 * offsetAround.width, size.height + 2 * offsetAround.height)
    val offset = Offset(-offsetAround.width, -offsetAround.height)
    val cornerRadius = CornerRadius(backgroundRoundedCorner.toPx())
    onDrawBehind {
        drawRoundRect(color = color, topLeft = offset, size = newSize, cornerRadius = cornerRadius)
    }
}

/**
 * IchthysOS: the second, floor handle drawn on top of the brightness track. Dragging it sets the
 * minimum brightness that adaptive/auto brightness may reach in the current environment.
 *
 * Shape (per the user's sketch): an orange vertical bar with rounded caps spanning the FULL height
 * of the slider, from which a curved stem hangs — it leaves the bar's bottom straight down, bows to
 * the right, and drops into a round knob that sits below the slider and one knob-diameter to the
 * RIGHT of the bar. Reads like a lollipop / plumb-bob swinging slightly right.
 */
@Composable
private fun BoxScope.BrightnessFloorHandle(
    fraction: () -> Float,
    widthPx: Int,
    sliderHeightPx: Int,
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val barWidth = 4.dp
    val knobRadius = 10.dp
    val knobOffsetX = 20.dp // one knob diameter: the knob swings to the RIGHT of the bar
    val stemGap = 12.dp     // vertical gap between the slider's bottom edge and the top of the knob
    val touch = 30.dp       // grab size of the knob (the only draggable part)
    val touchHalfPx = with(density) { touch.toPx() / 2f }
    val sliderHeightDp = with(density) { sliderHeightPx.toDp() }
    // The bar stays on the fraction position; everything else is laid out relative to it. Half a
    // touch target of slack on the left (and on the right of the knob) keeps the whole marker —
    // curve and offset knob included — inside the Box, so nothing gets clipped or loses its grip.
    val barCenterX = touch / 2
    val knobCenterX = barCenterX + knobOffsetX
    val handleWidth = knobCenterX + touch / 2
    // Full marker height: from the top of the slider down to the bottom of the knob below it.
    val handleHeight = sliderHeightDp + stemGap + knobRadius * 2
    // Kept across recompositions/frames so the curved stem doesn't allocate a Path every draw.
    val stemPath = remember { Path() }
    // The gesture block below is started once per [widthPx] and keeps whatever lambdas it captured
    // then, so read the *current* callbacks through rememberUpdatedState — otherwise a recomposition
    // during (or before) a drag leaves the running detector calling a stale onDrag.
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    Box(
        modifier =
            Modifier.align(Alignment.TopStart)
                .offset { IntOffset((fraction() * widthPx - touchHalfPx).roundToInt(), 0) }
                .width(handleWidth)
                .height(handleHeight)
    ) {
        // The marker itself: a full-height bar on the slider, a curved stem, and the round knob.
        // Draw-only (no pointerInput) so touches over the track pass straight through to the main
        // brightness slider — the whole point of the sketch.
        Box(
            modifier =
                Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    val barCx = barCenterX.toPx()
                    val knobCx = knobCenterX.toPx()
                    val barWpx = barWidth.toPx()
                    val knobRpx = knobRadius.toPx()
                    val sliderHpx = sliderHeightPx.toFloat()
                    val knobCy = sliderHpx + stemGap.toPx() + knobRpx
                    // Vertical bar with rounded caps spanning the FULL slider height, so its ends
                    // touch the slider's top and bottom edges (the track alone is too short).
                    drawRoundRect(
                        color = FloorHandleColor,
                        topLeft = Offset(barCx - barWpx / 2f, 0f),
                        size = Size(barWpx, sliderHpx),
                        cornerRadius = CornerRadius(barWpx / 2f),
                    )
                    // Curved stem: a cubic whose control points are vertically above/below their
                    // anchors, so the stem leaves the bar straight down, bows right, and arrives at
                    // the knob heading straight down again (the "plant stem" of the sketch).
                    val bend = (knobCy - sliderHpx) * StemBendFactor
                    stemPath.reset()
                    stemPath.moveTo(barCx, sliderHpx)
                    stemPath.cubicTo(
                        barCx,
                        sliderHpx + bend,
                        knobCx,
                        knobCy - bend,
                        knobCx,
                        knobCy,
                    )
                    drawPath(
                        path = stemPath,
                        color = FloorHandleColor,
                        style = Stroke(width = barWpx, cap = StrokeCap.Round),
                    )
                    // The knob (grip), hanging below the slider and offset to the right of the bar.
                    drawCircle(
                        color = FloorHandleColor,
                        radius = knobRpx,
                        center = Offset(knobCx, knobCy),
                    )
                }
        )
        // The draggable grip: ONLY the region below the slider (the knob), so the floor handle can
        // never intercept touches meant for the main brightness thumb. It is offset by the same
        // knobCenterX the knob is DRAWN at — if this stayed centred under the bar the grip would no
        // longer be where the user sees the knob.
        Box(
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .offset(x = knobCenterX - touch / 2)
                    .width(touch)
                    .height(stemGap + knobRadius * 2)
                    .thenIf(enabled) {
                        Modifier.pointerInput(widthPx) {
                            detectHorizontalDragGestures(
                                onDragEnd = { currentOnDragEnd() }
                            ) { change, delta ->
                                change.consume()
                                currentOnDrag(delta)
                            }
                        }
                    }
        )
    }
}

private val FloorHandleColor = Color(0xFFFF9800)
// How far the curved stem's control points sit from their anchors, as a fraction of the stem's
// vertical run. ~0.55 gives the gentle right-bowing S of the sketch without kinking.
private const val StemBendFactor = 0.55f
// Vertical space reserved below the brightness slider for the floor knob to hang into (also adds a
// little breathing room between the brightness bar and the QS tiles). Must cover the curved stem
// plus the whole knob: stemGap (12dp) + knob diameter (20dp) = 32dp, so 42dp leaves 10dp of gap.
private val FloorKnobReserve = 42.dp

/**
 * IchthysOS: snapshot of the crDroid brightness-slider settings
 * (`qs_brightness_slider_shape`, `qs_brightness_slider_haptic`). crDroid ships both preferences but
 * never shipped a SystemUI consumer for either, so both did nothing.
 */
private data class CrDroidSliderState(val shape: Int, val haptics: Boolean)

private const val KEY_SLIDER_SHAPE = "qs_brightness_slider_shape"
private const val KEY_SLIDER_HAPTIC = "qs_brightness_slider_haptic"

private fun readCrDroidSliderState(cr: ContentResolver): CrDroidSliderState =
    CrDroidSliderState(
        shape =
            QsSliderShape.sanitize(
                Settings.System.getIntForUser(
                    cr,
                    KEY_SLIDER_SHAPE,
                    QsSliderShape.DEFAULT,
                    UserHandle.USER_CURRENT,
                )
            ),
        // crDroid's XML defaults this switch to true, so an unwritten setting means "on".
        haptics =
            Settings.System.getIntForUser(cr, KEY_SLIDER_HAPTIC, 1, UserHandle.USER_CURRENT) != 0,
    )

@Composable
private fun rememberCrDroidSliderState(context: Context): CrDroidSliderState {
    val cr = context.contentResolver
    val state by
        produceState(initialValue = readCrDroidSliderState(cr), cr) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = readCrDroidSliderState(cr)
                    }
                }
            cr.registerContentObserver(
                Settings.System.getUriFor(KEY_SLIDER_SHAPE),
                false,
                observer,
            )
            cr.registerContentObserver(
                Settings.System.getUriFor(KEY_SLIDER_HAPTIC),
                false,
                observer,
            )
            value = readCrDroidSliderState(cr)
            awaitDispose { cr.unregisterContentObserver(observer) }
        }
    return state
}

/** IchthysOS: snapshot of the auto-brightness floor settings backing the second handle. */
private data class BrightnessFloorState(
    val enabled: Boolean,
    val autoMode: Boolean,
    val floorLinear: Float,
)

// Every read/write below goes through the *ForUser variants with USER_CURRENT. The shade runs in a
// per-display window context whose getUserId() is NOT the foreground user, so the plain
// (calling-user) overloads can silently resolve to the wrong user and hand back the default with no
// log at all. USER_CURRENT is resolved by the settings provider itself and is always right here.
private fun readBrightnessFloorState(cr: ContentResolver): BrightnessFloorState {
    val enabled =
        Settings.System.getIntForUser(
            cr,
            Settings.System.AUTO_BRIGHTNESS_MIN_FLOOR_ENABLED,
            0,
            UserHandle.USER_CURRENT,
        ) != 0
    val autoMode =
        Settings.System.getIntForUser(
            cr,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            0,
            UserHandle.USER_CURRENT,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    val floor =
        Settings.System.getFloatForUser(
            cr,
            Settings.System.AUTO_BRIGHTNESS_MIN_FLOOR,
            0f,
            UserHandle.USER_CURRENT,
        )
    return BrightnessFloorState(enabled, autoMode, floor)
}

@Composable
private fun rememberBrightnessFloorState(context: Context): BrightnessFloorState {
    val cr = context.contentResolver
    val state by
        produceState(initialValue = readBrightnessFloorState(cr), cr) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = readBrightnessFloorState(cr)
                    }
                }
            cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.AUTO_BRIGHTNESS_MIN_FLOOR_ENABLED),
                false,
                observer,
            )
            cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.AUTO_BRIGHTNESS_MIN_FLOOR),
                false,
                observer,
            )
            cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                false,
                observer,
            )
            value = readBrightnessFloorState(cr)
            awaitDispose { cr.unregisterContentObserver(observer) }
        }
    return state
}

/**
 * IchthysOS: crDroid ships the `qs_show_auto_brightness` preference but never shipped a SystemUI
 * consumer for it, so the switch did nothing. This is that consumer.
 *
 * The key is a *LineageSettings.Secure* one (the preference is a LineageSecureSettingSwitchPreference),
 * not android.provider.Settings, and the preference declares `android:defaultValue="true"`. The
 * default here must therefore be 1: if SystemUI defaulted to 0 the switch would read "on" in Settings
 * while nothing appeared in the shade until the user toggled it twice.
 */
private fun readQsShowAutoBrightness(cr: ContentResolver): Boolean =
    LineageSettings.Secure.getIntForUser(
        cr,
        LineageSettings.Secure.QS_SHOW_AUTO_BRIGHTNESS,
        1,
        UserHandle.USER_CURRENT,
    ) != 0

@Composable
private fun rememberQsShowAutoBrightness(context: Context): Boolean {
    val cr = context.contentResolver
    val state by
        produceState(initialValue = readQsShowAutoBrightness(cr), cr) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = readQsShowAutoBrightness(cr)
                    }
                }
            cr.registerContentObserver(
                LineageSettings.Secure.getUriFor(LineageSettings.Secure.QS_SHOW_AUTO_BRIGHTNESS),
                false,
                observer,
            )
            value = readQsShowAutoBrightness(cr)
            awaitDispose { cr.unregisterContentObserver(observer) }
        }
    return state
}

/**
 * IchthysOS: one of the small icon buttons that sit at the end of the QS brightness row.
 *
 * The clickable area is always [BrightnessRowControlHeight] square even though the glyph is smaller,
 * so both buttons clear the 48dp touch minimum. That costs nothing vertically: Material's [Slider]
 * already applies `minimumInteractiveComponentSize()`, so the brightness row is 48dp tall anyway.
 *
 * State is conveyed twice over — glyph *and* opacity — because a shape-only difference at ~20dp is
 * easy to miss in a dark shade.
 */
@Composable
private fun BrightnessRowToggle(
    iconRes: Int,
    contentDescription: String,
    checked: Boolean,
    enabled: Boolean,
    iconSize: Dp,
    checkedAlpha: Float,
    tint: Color,
    onClick: () -> Unit,
) {
    val alpha =
        when {
            !enabled -> 0.25f
            checked -> checkedAlpha
            else -> 0.45f
        }
    Box(
        modifier =
            Modifier.size(BrightnessRowControlHeight)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint.copy(alpha = alpha),
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * IchthysOS: the pair of brightness toggle buttons — adaptive brightness, and the auto-brightness
 * minimum floor — as a standalone unit.
 *
 * They used to sit at the end of the QS brightness row, where the two 48dp targets ate ~23% of the
 * row's width. They now live in the empty gap of the *expanded* QS header (see
 * `ShadeHeader.ExpandedShadeHeader`), which hands that width back to the slider.
 * [BrightnessSliderContainer] still renders them inline for the layouts that show a collapsed header
 * above QS (split shade, large-width QS) and therefore have no such gap.
 *
 * Everything the buttons need comes from the settings provider, not from a view model, so this can
 * be dropped anywhere in the shade without plumbing:
 * - `LineageSettings.Secure.qs_show_auto_brightness` gates the whole pair (crDroid's preference,
 *   which lives in the *lineagesettings* provider, not android.provider.Settings),
 * - `Settings.System.screen_brightness_mode` drives the adaptive toggle,
 * - `Settings.System.auto_brightness_min_floor_enabled` drives the floor toggle.
 *
 * All three are observed, so the buttons repaint live no matter who changes them.
 */
@Composable
fun BrightnessToggleButtons(modifier: Modifier = Modifier, tint: Color = Color.White) {
    val context = LocalContext.current
    // Registering the floor observers only when the buttons are actually shown keeps the hidden
    // case (switch off in Settings) free.
    if (rememberQsShowAutoBrightness(context)) {
        val floorState = rememberBrightnessFloorState(context)
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            BrightnessToggleButtonsContent(context, floorState, enabled = true, tint = tint)
        }
    }
}

/**
 * The two buttons themselves, shared by the header and the (collapsed-header) inline layouts.
 * IchthysOS: floor (min) first, adaptive on the right, as the owner asked.
 */
@Composable
private fun BrightnessToggleButtonsContent(
    context: Context,
    floorState: BrightnessFloorState,
    enabled: Boolean,
    tint: Color,
) {
    // The floor clamps *auto*-brightness, so it is meaningless in manual mode. Disabled rather than
    // hidden: it sits immediately next to the adaptive toggle, and making it vanish the instant you
    // tap that neighbour would reflow the row under the user's finger and hide the fact that the
    // feature exists at all. Greyed out it still reads as "this belongs to adaptive brightness —
    // turn that on first".
    BrightnessRowToggle(
        iconRes = R.drawable.ic_ichthys_brightness_floor,
        contentDescription =
            stringResource(
                if (floorState.enabled) R.string.ichthys_accessibility_brightness_floor_on
                else R.string.ichthys_accessibility_brightness_floor_off
            ),
        checked = floorState.enabled,
        enabled = enabled && floorState.autoMode,
        iconSize = FloorToggleIconSize,
        checkedAlpha = FloorToggleCheckedAlpha,
        tint = tint,
        onClick = {
            Settings.System.putIntForUser(
                context.contentResolver,
                Settings.System.AUTO_BRIGHTNESS_MIN_FLOOR_ENABLED,
                if (floorState.enabled) 0 else 1,
                UserHandle.USER_CURRENT,
            )
        },
    )
    BrightnessRowToggle(
        iconRes =
            if (floorState.autoMode) R.drawable.ic_ichthys_auto_brightness
            else R.drawable.ic_ichthys_auto_brightness_off,
        contentDescription =
            stringResource(
                if (floorState.autoMode) R.string.ichthys_accessibility_auto_brightness_on
                else R.string.ichthys_accessibility_auto_brightness_off
            ),
        checked = floorState.autoMode,
        enabled = enabled,
        iconSize = AutoBrightnessIconSize,
        checkedAlpha = 1f,
        tint = tint,
        onClick = {
            Settings.System.putIntForUser(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (floorState.autoMode) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                UserHandle.USER_CURRENT,
            )
        },
    )
}

// Touch target (and therefore the height the trailing controls are aligned against). Matches the
// height Material's Slider is forced to by minimumInteractiveComponentSize().
private val BrightnessRowControlHeight = 48.dp
private val AutoBrightnessIconSize = 22.dp
// Subordinate: the floor toggle is the rarer action, so it is drawn smaller and never at full white.
private val FloorToggleIconSize = 17.dp
private const val FloorToggleCheckedAlpha = 0.8f

@Composable
fun BrightnessSliderContainer(
    viewModel: BrightnessSliderViewModel,
    modifier: Modifier = Modifier,
    containerColors: ContainerColors,
    dimensions: BrightnessSliderDimensions = BrightnessSliderDimensions.Default,
    // IchthysOS: the adaptive/floor toggles are drawn at the end of the row only where there is no
    // expanded QS header to host them (see [BrightnessToggleButtons]). The default keeps every
    // caller that has no header of its own — the brightness dialog, the legacy QS fragment —
    // exactly as it was.
    showToggles: Boolean = true,
) {
    val gamma = viewModel.currentBrightness.value
    if (gamma == BrightnessSliderViewModel.initialValue.value) { // Ignore initial negative value.
        return
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )
    val overriddenByAppState by viewModel.brightnessOverriddenByWindow.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }

    DisposableEffectWithLifecycle(Unit) {
        enabled = true
        onDispose {
            dragging = false
            viewModel.setIsDragging(false)
            enabled = false
        }
    }

    // Use dragging instead of viewModel.showMirror so the color starts changing as soon as the
    // dragging state changes. If not, we may be waiting for the background to finish fading in
    // when stopping dragging
    val containerColor by
        animateColorAsState(
            if (dragging && viewModel.supportsMirroring) {
                containerColors.mirrorColor
            } else {
                containerColors.idleColor
            }
        )

    val isRestricted = restriction is PolicyRestriction.Restricted

    // IchthysOS: auto-brightness minimum floor (second handle). Only shown when the feature is
    // enabled AND auto-brightness is active. The stored value is an absolute linear brightness
    // float; convert to/from the slider's gamma fraction using the SAME display linear bounds the
    // main handle uses, so the floor handle lines up with the main brightness handle and its
    // position corresponds to a real screen-brightness level.
    val floorState = rememberBrightnessFloorState(context)
    val showFloorHandle = floorState.enabled && floorState.autoMode
    // IchthysOS: the brightness-row toggle buttons. Both of the settings they write are already
    // observed by rememberBrightnessFloorState, so flipping either one — here, from Settings, or by
    // the platform itself — repaints the buttons without any extra plumbing.
    // (Called unconditionally — a composable must not hide behind a short-circuiting `&&`.)
    val showBrightnessToggles = rememberQsShowAutoBrightness(context) && showToggles
    val minLinear = viewModel.minLinearBrightness
    val maxLinear = viewModel.maxLinearBrightness
    val floorFraction =
        remember(floorState.floorLinear, minLinear, maxLinear) {
            if (maxLinear > minLinear) {
                val gamma =
                    BrightnessUtils.convertLinearToGammaFloat(
                        floorState.floorLinear,
                        minLinear,
                        maxLinear,
                    )
                (gamma.toFloat() / BrightnessUtils.GAMMA_SPACE_MAX).coerceIn(0f, 1f)
            } else {
                0f
            }
        }

    // Custom Options: show the current brightness as a percentage next to the slider.
    val minGamma = viewModel.minBrightness.value
    val maxGamma = viewModel.maxBrightness.value
    val brightnessPercent =
        if (maxGamma > minGamma) {
            ((gamma - minGamma) * 100f / (maxGamma - minGamma)).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
    // IchthysOS: the slider always gets the full width. The percentage (and, in the layouts that
    // host them inline, the floor + adaptive toggles) sit in a slim label row above it.
    Column(
        modifier =
            modifier
                .padding(vertical = { dimensions.verticalPadding.roundToPx() })
                .fillMaxWidth()
                .sysuiResTag("brightness_slider"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = { 4.dp.roundToPx() }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$brightnessPercent%",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            if (showBrightnessToggles) {
                BrightnessToggleButtonsContent(
                    context = context,
                    floorState = floorState,
                    enabled = enabled && !isRestricted,
                    tint = Color.White,
                )
            }
        }
        BrightnessSlider(
            enabled = enabled && !isRestricted,
            gammaValue = gamma,
            valueRange = viewModel.minBrightness.value..viewModel.maxBrightness.value,
            iconResProvider = BrightnessSliderViewModel::getIconForPercentage,
            imageLoader = viewModel::loadImage,
            restriction = restriction,
            onRestrictedClick = viewModel::showPolicyRestrictionDialog,
            onDrag = {
                viewModel.setIsDragging(true)
                dragging = true
                coroutineScope.launch { viewModel.onDrag(Drag.Dragging(GammaBrightness(it))) }
            },
            onStop = {
                viewModel.setIsDragging(false)
                dragging = false
                coroutineScope.launch { viewModel.onDrag(Drag.Stopped(GammaBrightness(it))) }
            },
            modifier =
                Modifier.borderOnFocus(
                        color = MaterialTheme.colorScheme.secondary,
                        cornerSize = CornerSize(SliderTrackRoundedCorner),
                    )
                    .then(if (viewModel.showMirror) Modifier.drawInOverlay() else Modifier)
                    .sliderBackground(
                        DpSize(dimensions.backgroundFrameWidth, dimensions.backgroundFrameHeight),
                        dimensions.backgroundRoundedCorner,
                        containerColor,
                    )
                    .fillMaxWidth()
                    .pointerInteropFilter {
                        if (
                            it.actionMasked == MotionEvent.ACTION_UP ||
                                it.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            viewModel.emitBrightnessTouchForFalsing()
                        }
                        false
                    },
            hapticsViewModelFactory = viewModel.hapticsViewModelFactory,
            overriddenByAppState = overriddenByAppState,
            showToast = {
                viewModel.showToast(
                    context,
                    com.android.internal.R.string.brightness_unable_adjust_msg,
                )
            },
            dimensions = dimensions,
            showFloorHandle = showFloorHandle,
            floorFraction = floorFraction,
            onFloorFractionFinished = { frac ->
                val gamma = (frac * BrightnessUtils.GAMMA_SPACE_MAX).roundToInt()
                val linear = BrightnessUtils.convertGammaToLinearFloat(gamma, minLinear, maxLinear)
                Settings.System.putFloatForUser(
                    context.contentResolver,
                    Settings.System.AUTO_BRIGHTNESS_MIN_FLOOR,
                    linear,
                    UserHandle.USER_CURRENT,
                )
            },
        )
    }
}

data class ContainerColors(val idleColor: Color, val mirrorColor: Color) {
    companion object {
        fun singleColor(color: Color) = ContainerColors(color, color)

        val defaultContainerColor: Color
            @Composable @ReadOnlyComposable get() = colorResource(R.color.shade_panel_fallback)
    }
}

data class BrightnessSliderDimensions(
    val iconSize: DpSize,
    val thumbHeight: Dp,
    val thumbWidth: Dp,
    val trackHeight: Dp,
    val verticalPadding: Dp,
    val backgroundRoundedCorner: Dp,
    val backgroundFrameWidth: Dp,
    val backgroundFrameHeight: Dp,
) {
    companion object {
        val Default =
            BrightnessSliderDimensions(
                // Custom Options: thinner slider + much smaller vertical footprint so the QS
                // tiles sit higher (was thumb 52 / track 40 / vPadding 6).
                iconSize = DpSize(20.dp, 20.dp),
                thumbHeight = 22.dp,
                thumbWidth = 4.dp,
                trackHeight = 16.dp,
                verticalPadding = 2.dp,
                backgroundRoundedCorner = 24.dp,
                backgroundFrameWidth = 10.dp,
                backgroundFrameHeight = 6.dp,
            )
    }
}

private object InternalDimensions {
    val SliderTrackRoundedCorner = 12.dp
    val IconPadding = 6.dp
    val ThumbTrackGapSize = 6.dp
}

private object AnimationSpecs {
    val IconAppearSpec = tween<Float>(durationMillis = 100, delayMillis = 33)
    val IconDisappearSpec = tween<Float>(durationMillis = 50)
}

private suspend fun Animatable<Float, AnimationVector1D>.appear() =
    animateTo(targetValue = 1f, animationSpec = IconAppearSpec)

private suspend fun Animatable<Float, AnimationVector1D>.disappear() =
    animateTo(targetValue = 0f, animationSpec = IconDisappearSpec)

@VisibleForTesting
object BrightnessSliderMotionTestKeys {
    val ActiveIconAlpha = MotionTestValueKey<Float>("activeIconAlpha")
    val InactiveIconAlpha = MotionTestValueKey<Float>("inactiveIconAlpha")
}
