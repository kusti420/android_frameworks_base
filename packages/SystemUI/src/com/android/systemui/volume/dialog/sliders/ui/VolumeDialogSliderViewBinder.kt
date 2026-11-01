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

import android.os.UserHandle
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.res.R
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.domain.interactor.ExpandedAudioTileDetailsFeatureInteractor
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.domain.OneUiExpandState
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogOverscrollViewModel
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderViewModel
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider
import com.android.systemui.volume.ui.compose.slider.SliderIcon
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import lineageos.providers.LineageSettings

@VolumeDialogSliderScope
class VolumeDialogSliderViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogSliderViewModel,
    private val overscrollViewModel: VolumeDialogOverscrollViewModel,
    private val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val expandedAudioTileDetailsFeatureInteractor: ExpandedAudioTileDetailsFeatureInteractor,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
) {
    fun bind(view: View) {
        // Use horizontal volume dialog if the audio tile details view is enabled
        val isVolumeDialogVertical = !expandedAudioTileDetailsFeatureInteractor.isEnabled()
        val sliderComposeViewId =
            if (isVolumeDialogVertical) {
                R.id.volume_dialog_slider
            } else {
                R.id.volume_dialog_slider_horizontal
            }
        val sliderComposeView: ComposeView = view.requireViewById(sliderComposeViewId)
        sliderComposeView.setContent {
            PlatformTheme {
                VolumeDialogSlider(
                    viewModel = viewModel,
                    overscrollViewModel = overscrollViewModel,
                    hapticsViewModelFactory = hapticsViewModelFactory,
                    isVolumeDialogVertical = isVolumeDialogVertical,
                    onOneUiSwipeDismiss = {
                        visibilityInteractor.dismissDialog(Events.DISMISS_REASON_TOUCH_OUTSIDE)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VolumeDialogSlider(
    viewModel: VolumeDialogSliderViewModel,
    overscrollViewModel: VolumeDialogOverscrollViewModel,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    isVolumeDialogVertical: Boolean,
    onOneUiSwipeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dimensions: VolumeSliderDimensions =
        if (isVolumeDialogVertical) {
            VolumeSliderDimensions.Vertical
        } else {
            VolumeSliderDimensions.Horizontal
        },
) {
    val colors =
        if (isVolumeDialogVertical) {
            SliderDefaults.colors(
                activeTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        } else {
            SliderDefaults.colors(
                activeTickColor = MaterialTheme.colorScheme.onPrimary,
                inactiveTickColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    val collectedSliderStateModel by viewModel.state.collectAsStateWithLifecycle(null)
    val sliderStateModel = collectedSliderStateModel ?: return
    val interactionSource = remember { MutableInteractionSource() }

    val context = LocalContext.current
    // Custom Options: "volume_panel_style" (Settings.System, default 1). 0 = default AOSP look,
    // 1 = "One UI" style slider. Read once at composition; the setting is applied on a restart.
    // Only the vertical volume dialog carries the One UI look (One UI ships a vertical panel).
    val oneUiStyle =
        remember(context, isVolumeDialogVertical) {
            isVolumeDialogVertical &&
                Settings.System.getIntForUser(
                    context.contentResolver,
                    "volume_panel_style",
                    1,
                    UserHandle.USER_CURRENT,
                ) == 1
        }
    // Custom Options: "volume_dialog_haptic_feedback" (Settings.Secure, default on) toggles the
    // slider drag haptics.
    val hapticFeedbackEnabled =
        remember(context) {
            Settings.Secure.getIntForUser(
                context.contentResolver,
                Settings.Secure.VOLUME_DIALOG_HAPTIC_FEEDBACK,
                1,
                UserHandle.USER_CURRENT,
            ) != 0
        }
    // One UI visual parameters: a wide fully-rounded capsule with a continuous rounded fill and no
    // protruding thumb. Default (0) keeps the current values so its rendering is unchanged.
    val oneUiDimensions = VolumeSliderDimensions.OneUi
    val trackSize = if (oneUiStyle) oneUiDimensions.trackSize else dimensions.trackSize
    val iconSize = if (oneUiStyle) oneUiDimensions.iconSize else dimensions.iconSize
    val trackCornerSize = if (oneUiStyle) trackSize / 2 else 0.dp
    val trackInsideCornerSize = if (oneUiStyle) OneUiTrackInsideCornerSize else 0.dp
    val thumbTrackGapSize = if (oneUiStyle) 0.dp else DefaultThumbTrackGapSize

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> viewModel.onSliderDragStarted()
                is DragInteraction.Cancel -> viewModel.onSliderDragFinished()
                is DragInteraction.Stop -> viewModel.onSliderDragFinished()
            }
        }
    }

    if (!isVolumeDialogVertical) {
        // IchthysOS: the horizontal dialog (custom_volume_horizontal) draws one of the premade
        // looks of HorizontalVolumeStyle (volume_horizontal_style / _level / _thickness) instead
        // of the stock Material slider. The vertical dialog below is untouched.
        val horizontalConfig = remember(context) { HorizontalVolumeStyle.read(context) }
        HorizontalVolumeSlider(
            state = sliderStateModel,
            config = horizontalConfig,
            hapticFeedbackEnabled = hapticFeedbackEnabled,
            onValueChanged = { value ->
                overscrollViewModel.setSlider(
                    value = value,
                    min = sliderStateModel.valueRange.start,
                    max = sliderStateModel.valueRange.endInclusive,
                )
                viewModel.setStreamVolume(value, true)
            },
            onValueChangeFinished = { viewModel.onSliderChangeFinished(it) },
            onDragStarted = { viewModel.onSliderDragStarted() },
            onDragFinished = { viewModel.onSliderDragFinished() },
            modifier =
                modifier.pointerInput(Unit) {
                    coroutineScope {
                        val currentContext = currentCoroutineContext()
                        awaitPointerEventScope {
                            while (currentContext.isActive) {
                                viewModel.onTouchEvent(awaitPointerEvent())
                            }
                        }
                    }
                },
        )
        return
    }

    if (oneUiStyle) {
        // Custom Options: One UI (1) renders an original, fully custom capsule slider instead of
        // the AOSP Material slider. Default (0) falls through to the unchanged Slider() below.
        val expanded by OneUiExpandState.expanded.collectAsStateWithLifecycle()
        val panelOnLeft =
            remember(context) {
                LineageSettings.Secure.getIntForUser(
                    context.contentResolver,
                    LineageSettings.Secure.VOLUME_PANEL_ON_LEFT,
                    0,
                    UserHandle.USER_CURRENT,
                ) != 0
            }
        OneUiVolumeSlider(
            state = sliderStateModel,
            iconSize = iconSize,
            hapticFeedbackEnabled = hapticFeedbackEnabled,
            onValueChanged = { value ->
                overscrollViewModel.setSlider(
                    value = value,
                    min = sliderStateModel.valueRange.start,
                    max = sliderStateModel.valueRange.endInclusive,
                )
                viewModel.setStreamVolume(value, true)
            },
            onValueChangeFinished = { viewModel.onSliderChangeFinished(it) },
            onDragStarted = { viewModel.onSliderDragStarted() },
            onDragFinished = { viewModel.onSliderDragFinished() },
            onExpandClicked = {
                // IchthysOS: the "more" button (or a swipe toward the screen centre) toggles the
                // One UI expansion — shows ALL audio streams as side-by-side capsules.
                OneUiExpandState.toggle()
            },
            onSwipeDismiss = onOneUiSwipeDismiss,
            expanded = expanded,
            panelOnLeft = panelOnLeft,
            modifier =
                modifier.pointerInput(Unit) {
                    coroutineScope {
                        val currentContext = currentCoroutineContext()
                        awaitPointerEventScope {
                            while (currentContext.isActive) {
                                viewModel.onTouchEvent(awaitPointerEvent())
                            }
                        }
                    }
                },
        )
        return
    }

    Slider(
        value = sliderStateModel.value,
        valueRange = sliderStateModel.valueRange,
        onValueChanged = { value ->
            overscrollViewModel.setSlider(
                value = value,
                min = sliderStateModel.valueRange.start,
                max = sliderStateModel.valueRange.endInclusive,
            )
            viewModel.setStreamVolume(value, true)
        },
        onValueChangeFinished = { viewModel.onSliderChangeFinished(it) },
        isEnabled = !sliderStateModel.isDisabled,
        isReverseDirection = true,
        isVertical = isVolumeDialogVertical,
        colors = colors,
        interactionSource = interactionSource,
        haptics =
            if (hapticFeedbackEnabled) {
                Haptics.Enabled(
                    hapticsViewModelFactory = hapticsViewModelFactory,
                    hapticConfigs =
                        VolumeHapticsConfigsProvider.continuousConfigs(SliderHapticFeedbackFilter()),
                    orientation =
                        if (isVolumeDialogVertical) {
                            Orientation.Vertical
                        } else {
                            Orientation.Horizontal
                        },
                )
            } else {
                Haptics.Disabled
            },
        stepDistance = 1f,
        track = { sliderState ->
            SliderTrack(
                sliderState,
                colors = colors,
                isEnabled = !sliderStateModel.isDisabled,
                isVertical = isVolumeDialogVertical,
                activeTrackEndIcon = { iconsState ->
                    SliderIcon(
                        icon = {
                            Icon(
                                icon = sliderStateModel.icon,
                                tint = null,
                                modifier = Modifier.size(iconSize),
                            )
                        },
                        isVisible = !iconsState.isInactiveTrackEndIconVisible,
                    )
                },
                inactiveTrackEndIcon = { iconsState ->
                    SliderIcon(
                        icon = {
                            Icon(
                                icon = sliderStateModel.icon,
                                tint = null,
                                modifier = Modifier.size(iconSize),
                            )
                        },
                        isVisible = iconsState.isInactiveTrackEndIconVisible,
                    )
                },
                trackSize = trackSize,
                // Custom Options: default (0) is a rectangular track; One UI (1) is a fully-rounded
                // capsule with a continuous rounded fill (no thumb / track gap).
                thumbTrackGapSize = thumbTrackGapSize,
                trackCornerSize = trackCornerSize,
                trackInsideCornerSize = trackInsideCornerSize,
            )
        },
        thumb = { sliderState, interactions ->
            if (oneUiStyle) {
                // One UI has no protruding thumb; the capsule fill itself is the handle.
            } else {
                SliderDefaults.Thumb(
                    sliderState = sliderState,
                    interactionSource = interactions,
                    enabled = !sliderStateModel.isDisabled,
                    colors = colors,
                    thumbSize = DpSize(dimensions.thumbWidth, dimensions.thumbHeight),
                )
            }
        },
        accessibilityParams = AccessibilityParams(contentDescription = sliderStateModel.label),
        modifier =
            modifier.pointerInput(Unit) {
                coroutineScope {
                    val currentContext = currentCoroutineContext()
                    awaitPointerEventScope {
                        while (currentContext.isActive) {
                            viewModel.onTouchEvent(awaitPointerEvent())
                        }
                    }
                }
            },
    )
}

data class VolumeSliderDimensions(
    val iconSize: Dp,
    val thumbHeight: Dp,
    val thumbWidth: Dp,
    val trackSize: Dp,
) {
    companion object {
        val Vertical =
            VolumeSliderDimensions(
                iconSize = 20.dp,
                thumbWidth = 52.dp,
                thumbHeight = 4.dp,
                trackSize = 40.dp,
            )

        val Horizontal =
            VolumeSliderDimensions(
                iconSize = 24.dp,
                thumbHeight = 24.dp,
                thumbWidth = 3.dp,
                trackSize = 20.dp,
            )

        // Custom Options: "One UI" slider. A narrow, tall, fully-rounded capsule (trackSize / 2
        // corner) with no separate thumb, matching the One UI volume panel proportions (a slim
        // vertical capsule, not a wide bar). 40dp track centered in the 52dp
        // volume_dialog_slider_width column; the capsule height comes from volume_dialog_slider_height
        // (254dp), giving the tall ~6:1 One UI capsule. Value recreated from the reference screenshot
        // (the shipped resource dump exposes only names, not dp values).
        val OneUi =
            VolumeSliderDimensions(
                iconSize = 28.dp,
                thumbHeight = 0.dp,
                thumbWidth = 0.dp,
                trackSize = 36.dp,
            )
    }
}

// Custom Options: rounding of the One UI capsule fill's leading edge (the moving fill line).
private val OneUiTrackInsideCornerSize = 12.dp
// Default (SliderTrack) gap kept around the thumb for the AOSP look. Kept explicit so the
// default branch renders identically to before the One UI option was added.
private val DefaultThumbTrackGapSize = 6.dp
