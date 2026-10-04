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
import android.hardware.display.ColorDisplayManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import com.android.systemui.res.R
import com.android.systemui.util.policy.PolicyRestriction
// IchthysOS: crDroid's "Auto brightness icon" switch is a LineageSecureSettingSwitchPreference, so
// its value lives in the lineagesettings provider, not android.provider.Settings.
import lineageos.providers.LineageSettings
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

/**
 * The stock Material brightness slider. IchthysOS no longer shows it anywhere:
 * [BrightnessSliderContainer] draws [ShoalBrightnessSlider] for every quick settings layout. It is
 * kept, without any IchthysOS additions, only because the upstream BrightnessSliderTest and
 * BrightnessSliderMotionTest drive it directly.
 */
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

    Slider(
        value = animatedValue,
        valueRange = floatValueRange,
        enabled = enabled,
        colors = colors,
        onValueChange = {
            if (enabled) {
                if (!overriddenByAppState) {
                    hapticsViewModel.onValueChange(it)
                    value = it.toInt()
                    onDrag(value)
                }
            }
        },
        onValueChangeFinished = {
            if (enabled) {
                if (!overriddenByAppState) {
                    hapticsViewModel.onValueChangeEnded()
                    onStop(value)
                }
            }
        },
        modifier =
            modifier
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
                trackCornerSize = SliderTrackRoundedCorner,
                trackInsideCornerSize = 2.dp,
                drawStopIndicator = null,
                thumbTrackGapSize = ThumbTrackGapSize,
                colors = colors,
            )
        },
    )

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
 * IchthysOS: whether Night Light is on (Settings.Secure night_display_activated of the current
 * user), for the "Night" pill next to the brightness toggles. Null when the display has no night
 * mode (config_nightDisplayAvailable), which hides the pill.
 */
@Composable
private fun rememberNightLightState(context: Context): Boolean? {
    val available = remember(context) { ColorDisplayManager.isNightDisplayAvailable(context) }
    if (!available) return null
    val cr = context.contentResolver
    fun read(): Boolean =
        Settings.Secure.getIntForUser(
            cr,
            Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
            0,
            UserHandle.USER_CURRENT,
        ) != 0
    val state by
        produceState(initialValue = read(), cr) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = read()
                    }
                }
            cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NIGHT_DISPLAY_ACTIVATED),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            value = read()
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
 * The brightness block of quick settings and of the brightness dialog.
 *
 * IchthysOS: one slider for every quick settings tile layout. Settings.Secure `ichthys_qs_style`
 * ("grid" or "shoal") only picks the tile grid; the brightness block is always
 * [ShoalBrightnessSlider] - label row with the value and the "min" / "Auto" pills, 56 dp pill
 * track with the floor bar inside it - coloured from the Shoal theme tokens. The grid layout used to
 * get the stock Material slider with a floor knob hanging below it; that variant is gone.
 *
 * @param containerColors upstream paints these behind the stock slider. Here they only say whether
 *   the host has a surface of its own: a visible [ContainerColors.idleColor] (the brightness dialog,
 *   whose window is transparent) makes the block draw its own Shoal panel, in token colours, not in
 *   the colour passed. Quick settings passes a transparent idle colour and gets no panel. The
 *   mirror colour is not used: the pill track is opaque, so the brightness mirror (the track alone,
 *   drawn in the overlay while the rest of quick settings fades out) needs no frame behind it.
 * @param dimensions only [BrightnessSliderDimensions.verticalPadding] applies; the track geometry
 *   is the slider's own, so that it is the same wherever it is shown.
 */
@Composable
fun BrightnessSliderContainer(
    viewModel: BrightnessSliderViewModel,
    modifier: Modifier = Modifier,
    containerColors: ContainerColors,
    dimensions: BrightnessSliderDimensions = BrightnessSliderDimensions.Default,
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
    var enabled by remember { mutableStateOf(false) }

    DisposableEffectWithLifecycle(Unit) {
        enabled = true
        onDispose {
            viewModel.setIsDragging(false)
            enabled = false
        }
    }

    val isRestricted = restriction is PolicyRestriction.Restricted

    // IchthysOS: auto-brightness minimum floor (the bar inside the track). Only shown when the
    // feature is enabled AND auto-brightness is active. The stored value is an absolute linear
    // brightness float; convert to/from the slider's gamma fraction using the SAME display linear
    // bounds the brightness value uses, so the floor bar lines up with the filled part of the track
    // and its position corresponds to a real screen-brightness level.
    // Both of the settings the "min" / "Auto" pills write are observed here too, so flipping either
    // one - here, from Settings, or by the platform itself - repaints the pills without any extra
    // plumbing.
    val floorState = rememberBrightnessFloorState(context)
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
    val floorGamma = (floorFraction * BrightnessUtils.GAMMA_SPACE_MAX).roundToInt()

    val minGamma = viewModel.minBrightness.value
    val maxGamma = viewModel.maxBrightness.value

    // IchthysOS: crDroid's brightness-slider shape + haptic switches.
    val crDroidSlider = rememberCrDroidSliderState(context)
    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel: SliderHapticsViewModel =
        rememberViewModel(traceName = "SliderHapticsViewModel") {
            viewModel.hapticsViewModelFactory.create(
                interactionSource,
                minGamma.toFloat()..maxGamma.toFloat(),
                Orientation.Horizontal,
                SliderHapticFeedbackConfig(
                    maxVelocityToScale = 1f /* slider progress(from 0 to 1) per sec */
                ),
                SeekableSliderTrackerConfig(),
            )
        }

    val nightLight = rememberNightLightState(context)

    ShoalBrightnessSlider(
        gamma = gamma,
        minGamma = minGamma,
        maxGamma = maxGamma,
        enabled = enabled && !isRestricted,
        isRestricted = isRestricted,
        onRestrictedClick = {
            (restriction as? PolicyRestriction.Restricted)?.let {
                viewModel.showPolicyRestrictionDialog(it)
            }
        },
        overriddenByAppState = overriddenByAppState,
        showToast = {
            viewModel.showToast(
                context,
                com.android.internal.R.string.brightness_unable_adjust_msg,
            )
        },
        // crDroid's qs_show_auto_brightness switch gates the two pills.
        showToggles = rememberQsShowAutoBrightness(context),
        autoMode = floorState.autoMode,
        floorEnabled = floorState.enabled,
        floorGamma = floorGamma,
        onToggleAuto = {
            Settings.System.putIntForUser(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (floorState.autoMode) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                UserHandle.USER_CURRENT,
            )
        },
        onToggleFloor = {
            Settings.System.putIntForUser(
                context.contentResolver,
                Settings.System.AUTO_BRIGHTNESS_MIN_FLOOR_ENABLED,
                if (floorState.enabled) 0 else 1,
                UserHandle.USER_CURRENT,
            )
        },
        nightLight = nightLight,
        onToggleNightLight = {
            // Through ColorDisplayManager, like the Night Light tile, so the schedule logic
            // ("until sunrise", custom times) sees a manual change the same way.
            context
                .getSystemService(ColorDisplayManager::class.java)
                ?.setNightDisplayActivated(nightLight != true)
        },
        // The setting is written once, when the floor drag ends, and never per drag frame: doing it
        // live would spam the settings provider and every observer of the floor value.
        onFloorFinished = { g ->
            val linear =
                BrightnessUtils.convertGammaToLinearFloat(
                    g.coerceIn(0, BrightnessUtils.GAMMA_SPACE_MAX),
                    minLinear,
                    maxLinear,
                )
            Settings.System.putFloatForUser(
                context.contentResolver,
                Settings.System.AUTO_BRIGHTNESS_MIN_FLOOR,
                linear,
                UserHandle.USER_CURRENT,
            )
        },
        onDrag = {
            if (crDroidSlider.haptics) hapticsViewModel.onValueChange(it.toFloat())
            viewModel.setIsDragging(true)
            coroutineScope.launch { viewModel.onDrag(Drag.Dragging(GammaBrightness(it))) }
        },
        onStop = {
            if (crDroidSlider.haptics) hapticsViewModel.onValueChangeEnded()
            viewModel.setIsDragging(false)
            coroutineScope.launch { viewModel.onDrag(Drag.Stopped(GammaBrightness(it))) }
        },
        modifier =
            modifier
                .padding(vertical = { dimensions.verticalPadding.roundToPx() })
                .fillMaxWidth()
                .sysuiResTag("brightness_slider"),
        sliderModifier =
            Modifier.then(if (viewModel.showMirror) Modifier.drawInOverlay() else Modifier)
                .pointerInteropFilter {
                    if (
                        it.actionMasked == MotionEvent.ACTION_UP ||
                            it.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        viewModel.emitBrightnessTouchForFalsing()
                    }
                    false
                },
        shape = crDroidSlider.shape,
        interactionSource = interactionSource,
        ownPanel = containerColors.idleColor.alpha > 0f,
    )
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
