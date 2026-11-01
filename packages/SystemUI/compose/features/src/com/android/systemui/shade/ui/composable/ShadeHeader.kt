/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.shade.ui.composable

import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.ColorInt
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.LowestZIndexContentPicker
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayoutState
import com.android.compose.animation.scene.ValueKey
import com.android.compose.animation.scene.animateElementFloatAsState
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.modifiers.thenIf
import com.android.systemui.Flags.groupedPrivacyChip
// IchthysOS: the adaptive-brightness / brightness-floor toggles, moved out of the QS brightness row
// (where they cost it ~23% of its width) into the empty gap of the expanded header.
import com.android.systemui.common.ui.compose.byLayoutId
import com.android.systemui.common.ui.compose.windowinsets.CutoutLocation
import com.android.systemui.common.ui.compose.windowinsets.LocalDisplayCutout
import com.android.systemui.common.ui.compose.windowinsets.LocalScreenCornerRadius
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.privacy.AbstractOngoingPrivacyChip
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.ui.view.ComposeOngoingPrivacyChip
import com.android.systemui.qs.ui.composable.rememberIchthysSystemInt
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.DualShadeEducationElement
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ui.composable.ShadeHeader.Values.ClockScale
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithEstimate
import com.android.systemui.statusbar.pipeline.mobile.StatusBarMobileIconKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernShadeCarrierGroupMobileView
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModelKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModelKairosComposeWrapper
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModelKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.composeWrapper
import com.android.systemui.shade.ui.ShoalShade
import com.android.systemui.statusbar.IchthysBtBatteryView
import com.android.systemui.statusbar.IchthysShoalBatteryView
import com.android.systemui.statusbar.policy.IchthysHeaderClock
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsInCompose
import com.android.systemui.statusbar.systemstatusicons.ui.compose.SystemStatusIcons
import com.android.systemui.statusbar.systemstatusicons.ui.compose.SystemStatusIconsLegacy
import com.android.systemui.util.composable.kairos.ActivatedKairosSpec
import com.android.systemui.util.kotlin.toDp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

object ShadeHeader {
    object Elements {
        val ExpandedContent = ElementKey("ShadeHeaderExpandedContent")
        val CollapsedContentStart = ElementKey("ShadeHeaderCollapsedContentStart")
        val CollapsedContentEnd = ElementKey("ShadeHeaderCollapsedContentEnd")
        val PrivacyChip = ElementKey("PrivacyChip", contentPicker = LowestZIndexContentPicker)
        val Clock = ElementKey("ShadeHeaderClock", contentPicker = LowestZIndexContentPicker)
        val ShadeCarrierGroup = ElementKey("ShadeCarrierGroup")
    }

    enum class LayoutId {
        StartContent,
        EndContent,
    }

    object Values {
        val ClockScale = ValueKey("ShadeHeaderClockScale")
    }

    object Dimensions {
        @Deprecated(
            "Approximation of the collapsed shade header height, used in legacy shade transitions.",
            replaceWith = ReplaceWith("ShadeHeaderViewModel.statusBarHeightPx"),
        )
        val CollapsedHeightForTransitions = 48.dp
        // IchthysOS: 98 dp, not 120: the header's content sits at its bottom, so this lifts the
        // expanded Quick Settings into the status bar strip (empty there but for the privacy
        // chip), and a full panel fits without scrolling.
        val ExpandedHeight = 98.dp
        val ChipPaddingHorizontal = 6.dp
        val ChipPaddingVertical = 4.dp
    }

    object Colors {
        val textColor: Color
            @Composable
            @ReadOnlyComposable
            get() = if (isSystemInDarkTheme()) Color.White else Color.Black

        val inverseTextColor: Color
            @Composable
            @ReadOnlyComposable
            get() = if (isSystemInDarkTheme()) Color.Black else Color.White
    }

    object TestTags {
        const val Root = "shade_header_root"
        const val BatteryTestTag = "battery_meter_composable_view"
    }
}

/** The status bar that appears above the Shade scene */
@Composable
fun ContentScope.CollapsedShadeHeader(
    viewModel: ShadeHeaderViewModel,
    isSplitShade: Boolean,
    modifier: Modifier = Modifier,
) {
    val cutoutLocation = LocalDisplayCutout.current().location
    val horizontalPadding =
        max(LocalScreenCornerRadius.current / 2f, Shade.Dimensions.HorizontalPadding)

    val useExpandedTextFormat by
        remember(cutoutLocation) {
            derivedStateOf {
                cutoutLocation != CutoutLocation.CENTER || shouldUseExpandedFormat(layoutState)
            }
        }

    val textColor = ShadeHeader.Colors.textColor

    // IchthysOS "Shoal": the one header shared with the Quick Settings scene (see ShoalShadeHeader).
    if (!isSplitShade && ShoalShade.isActive(LocalContext.current)) {
        // IchthysOS: the ringer pill belongs to expanded Quick Settings only.
        ShoalShadeHeader(
            viewModel = viewModel,
            horizontalPadding = ShoalHeaderHorizontalPadding,
            showRinger = false,
            modifier = modifier,
        )
        return
    }

    // This layout assumes it is globally positioned at (0, 0) and is the same size as the screen.
    CutoutAwareShadeHeader(
        statusBarHeightPx = viewModel.statusBarHeightPx,
        modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root),
        startContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier =
                    Modifier.padding(horizontal = horizontalPadding)
                        .layoutId(ShadeHeader.LayoutId.StartContent),
            ) {
                Clock(
                    onClick = viewModel::onClockClicked,
                    textColor = textColor,
                    modifier =
                        Modifier.sysuiResTag("expanded_header_clock")
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .wrapContentSize(Alignment.CenterStart),
                )
                VariableDayDate(
                    longerDateText = viewModel.longerDateText,
                    shorterDateText = viewModel.shorterDateText,
                    textColor = textColor,
                    modifier = Modifier.element(ShadeHeader.Elements.CollapsedContentStart),
                )
            }
        },
        endContent = {
            if (viewModel.isPrivacyChipVisible) {
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(horizontal = horizontalPadding)
                            .layoutId(ShadeHeader.LayoutId.EndContent)
                ) {
                    PrivacyChip(
                        privacyList = viewModel.privacyItems,
                        onClick = viewModel::onPrivacyChipClicked,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.element(ShadeHeader.Elements.CollapsedContentEnd)
                            .padding(horizontal = horizontalPadding)
                            .layoutId(ShadeHeader.LayoutId.EndContent),
                ) {
                    if (isSplitShade) {
                        ShadeCarrierGroup(viewModel = viewModel)
                    }
                    ShadeHighlightChip(
                        isClickable = isSplitShade,
                        onClick = viewModel::onSystemIconChipClicked,
                    ) {
                        val paddingEnd = with(LocalDensity.current) { 3.sp.toDp() }
                        StatusIcons(
                            viewModel = viewModel,
                            useExpandedFormat = useExpandedTextFormat,
                            foregroundColor = textColor.toArgb(),
                            backgroundColor = ShadeHeader.Colors.inverseTextColor.toArgb(),
                            modifier = Modifier.padding(end = paddingEnd).weight(1f, fill = false),
                        )
                        BatteryInfo(
                            viewModel = viewModel,
                            showIcon = true,
                            useExpandedFormat = useExpandedTextFormat,
                            modifier =
                                if (LocalConfiguration.current.equals(ORIENTATION_PORTRAIT))
                                    Modifier.padding(vertical = 8.dp)
                                else Modifier,
                            textColor = textColor,
                        )
                    }
                }
            }
        },
    )
}

/** The status bar that appears above the Quick Settings scene on small screens. */
@Composable
fun ContentScope.ExpandedShadeHeader(
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier = Modifier,
) {
    // IchthysOS "Shoal": the same header as the Shade scene's, at the same place, so nothing moves
    // during Shade <-> Quick Settings.
    if (ShoalShade.isActive(LocalContext.current)) {
        // IchthysOS: QuickSettingsScene no longer pads this header, so the full padding applies.
        ShoalShadeHeader(
            viewModel = viewModel,
            horizontalPadding = ShoalHeaderHorizontalPadding,
            showRinger = true,
            modifier = modifier,
        )
        return
    }

    val useExpandedFormat by remember { derivedStateOf { shouldUseExpandedFormat(layoutState) } }

    val textColor = ShadeHeader.Colors.textColor
    val context = LocalContext.current

    Box(modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root)) {
        if (viewModel.isPrivacyChipVisible) {
            Box(
                modifier =
                    Modifier.height(viewModel.statusBarHeightPx.toDp(LocalContext.current).dp)
                        .fillMaxWidth()
            ) {
                PrivacyChip(
                    privacyList = viewModel.privacyItems,
                    onClick = viewModel::onPrivacyChipClicked,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(space = 16.dp, alignment = Alignment.Bottom),
            modifier =
                Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = ShadeHeader.Dimensions.ExpandedHeight),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Clock(
                    onClick = viewModel::onClockClicked,
                    scale = 2.57f,
                    textColor = textColor,
                    modifier =
                        Modifier.sysuiResTag("expanded_header_clock")
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .wrapContentSize(Alignment.CenterStart),
                )
                if (!viewModel.isPrivacyChipVisible) {
                    Box(
                        modifier =
                            Modifier.element(ShadeHeader.Elements.ShadeCarrierGroup).fillMaxWidth()
                    ) {
                        // IchthysOS: the weather (glyph, temperature, today's high / low) in the
                        // carrier's place, as in the Shoal header: a surface style without the
                        // Shoal shade ("tonal") lost it. The carrier shows when there is none.
                        ShoalHeaderWeather(
                            textColor = textColor,
                            mutedColor = textColor.copy(alpha = 0.7f),
                            onClick = { viewModel.onWeatherClicked(context) },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) {
                            ShadeCarrierGroup(
                                viewModel = viewModel,
                                modifier =
                                    Modifier.align(Alignment.CenterEnd).widthIn(max = 180.dp),
                            )
                        }
                    }
                }
            }
            // IchthysOS: Mute | Vibrate | Ring centred on the screen, over the free space between
            // the date and the icons; the row underneath keeps its stock layout.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.element(ShadeHeader.Elements.ExpandedContent).fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    VariableDayDate(
                        longerDateText = viewModel.longerDateText,
                        shorterDateText = viewModel.shorterDateText,
                        textColor = textColor,
                        modifier = Modifier.sysuiResTag("expanded_shade_header_day_date"),
                    )
                    ShadeHighlightChip {
                        val paddingEnd = with(LocalDensity.current) { 3.sp.toDp() }
                        StatusIcons(
                            viewModel = viewModel,
                            useExpandedFormat = useExpandedFormat,
                            foregroundColor = textColor.toArgb(),
                            backgroundColor = ShadeHeader.Colors.inverseTextColor.toArgb(),
                            modifier = Modifier.padding(end = paddingEnd).weight(1f, fill = false),
                        )
                        BatteryInfo(
                            viewModel = viewModel,
                            showIcon = true,
                            useExpandedFormat = true,
                            textColor = textColor,
                        )
                    }
                }
                ShoalRingerPill(
                    accent = MaterialTheme.colorScheme.primary,
                    onAccent = MaterialTheme.colorScheme.onPrimary,
                    mutedColor = textColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** IchthysOS "Shoal": the header's side margin from the screen edge (design: 20 dp). */
private val ShoalHeaderHorizontalPadding = 20.dp

/** The horizontal padding QuickSettingsScene puts around [ExpandedShadeHeader]. */
private val QsSceneHeaderPadding = 16.dp

/**
 * IchthysOS "Shoal": the one shade header, used by both the Shade scene ([CollapsedShadeHeader])
 * and the Quick Settings scene ([ExpandedShadeHeader]) at the same size and position, so the
 * Shade -> Quick Settings transition leaves it in place: the clock ([ShadeHeader.Elements.Clock]),
 * the date ([ShadeHeader.Elements.CollapsedContentStart]) and the icons
 * ([ShadeHeader.Elements.CollapsedContentEnd]) are shared elements of the two scenes.
 *
 * "20:14" in the mono face at 30 sp with ":37" small in the accent, the date below in the text
 * face at 13 sp, muted; the weather ([ShoalHeaderWeather]), the status icon lattice and the Shoal
 * battery on the right.
 */
@Composable
private fun ContentScope.ShoalShadeHeader(
    viewModel: ShadeHeaderViewModel,
    horizontalPadding: Dp,
    showRinger: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The theme's text colours, read under the configuration so they follow dark / light.
    val configuration = LocalConfiguration.current
    val textColor = remember(configuration) { Color(ShoalShade.textColor(context)) }
    val mutedColor = remember(configuration) { Color(ShoalShade.mutedColor(context)) }
    val accentColor = remember(configuration) { Color(ShoalShade.accent(context)) }
    val inkColor = remember(configuration) { Color(ShoalShade.inkColor(context)) }
    val textFamily =
        remember(context) {
            FontFamily(Font(DeviceFontFamilyName(ShoalShade.textFamily(context))))
        }
    // "Mon, 28 September". Re-formatted whenever the view model's date text changes (a new day,
    // a locale or time zone change).
    val longDate =
        remember(viewModel.longerDateText) {
            SimpleDateFormat(ShoalDatePattern, Locale.getDefault()).format(Date())
        }

    Box(
        modifier =
            modifier
                .sysuiResTag(ShadeHeader.TestTags.Root)
                .fillMaxWidth()
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = ShoalHeaderPaddingTop,
                    bottom = ShoalHeaderPaddingBottom,
                )
    ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Clock(
                onClick = viewModel::onClockClicked,
                textColor = textColor,
                shoalHeader = true,
                modifier = Modifier.sysuiResTag("expanded_header_clock"),
            )
            // The date, on the clock's line below. Mute | Vibrate | Ring is drawn over the whole
            // header (after this Row), centred on the screen, whatever the date's width.
            VariableDayDate(
                longerDateText = longDate,
                shorterDateText = viewModel.shorterDateText,
                textColor = mutedColor,
                textStyle = TextStyle(fontFamily = textFamily, fontSize = 13.sp),
                modifier =
                    Modifier.element(ShadeHeader.Elements.CollapsedContentStart)
                        .sysuiResTag("expanded_shade_header_day_date"),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier.element(ShadeHeader.Elements.CollapsedContentEnd)
                    // IchthysOS: the content always takes its natural width, pinned to the end.
                    // During a Shade <-> Quick Settings swipe STL measures this shared element at
                    // a size interpolated between its idle sizes in the two scenes; squeezing the
                    // row to that size took the difference out of the status icon lattice, which
                    // then dropped the icon(s) that no longer fit. The element's end edge is the
                    // header's end in both scenes, so the content never moves.
                    .wrapContentWidth(Alignment.End, unbounded = true),
        ) {
            // Inside the shared element, so it stays put between the Shade and Quick Settings
            // scenes like the icons beside it.
            ShoalHeaderWeather(
                textColor = textColor,
                mutedColor = mutedColor,
                onClick = { viewModel.onWeatherClicked(context) },
                modifier = Modifier.padding(end = 6.dp),
            )
            if (viewModel.isPrivacyChipVisible) {
                // The chip view asks for MATCH_PARENT height (AbstractOngoingPrivacyChip), which
                // the stock headers satisfy with a fixed-height parent. This row has none: its
                // max height is the whole screen, so the chip would stretch the header to full
                // screen height (clock and icons centred mid-screen, QQS and notifications pushed
                // off the bottom). Let it wrap its content here.
                PrivacyChip(
                    privacyList = viewModel.privacyItems,
                    onClick = viewModel::onPrivacyChipClicked,
                    fillParentHeight = false,
                )
            }
            ShadeHighlightChip(isClickable = false) {
                val paddingEnd = with(LocalDensity.current) { 3.sp.toDp() }
                // One format in both scenes, so the icons keep their size during the transition.
                // One container too, composed in one scene at a time (see ShoalSharedStatusIcons),
                // so the icon set and the lattice cannot differ between the scenes.
                // IchthysOS: not weighted: the icons are measured at their natural width (the row
                // above is unbounded), so which icons show never depends on the width left over.
                StatusIcons(
                    viewModel = viewModel,
                    useExpandedFormat = false,
                    foregroundColor = textColor.toArgb(),
                    backgroundColor = ShadeHeader.Colors.inverseTextColor.toArgb(),
                    modifier = Modifier.padding(end = paddingEnd),
                    shareBetweenShadeAndQs = true,
                )
                BatteryInfo(
                    viewModel = viewModel,
                    showIcon = true,
                    useExpandedFormat = false,
                    textColor = textColor,
                )
            }
        }
    }
    // Mute | Vibrate | Ring centred on the date's line and on the screen (this Box spans the
    // header, so its centre is the screen's). The pill is taller than the date's line, so it is
    // dropped below the content's bottom edge (the date's) by half the difference, into the
    // header's bottom padding, instead of reaching up over the clock. Expanded Quick Settings
    // only: it fades in with the scene.
    if (showRinger) {
        ShoalRingerPill(
            accent = accentColor,
            onAccent = inkColor,
            mutedColor = mutedColor,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .offset(y = ShoalHeaderPillDrop)
                    .element(ShoalRingerElement),
        )
    }
    }
}

private val ShoalHeaderPaddingTop = 10.dp

/**
 * IchthysOS "Shoal": the [StatusIconContext] cache key of the ONE status icon container that the
 * Shade and Quick Settings scenes share (see [ShoalSharedStatusIcons]). Not a real scene, so it
 * never collides with the per-scene containers of the stock headers (e.g. the split shade's).
 */
private val ShoalStatusIconsCacheKey = SceneKey("ShoalShadeHeaderStatusIcons")

/** The scenes that show the Shoal header, and so the shared status icon container. */
private val ShoalHeaderScenes = setOf<ContentKey>(Scenes.Shade, Scenes.QuickSettings)

/**
 * The size, measured in the lookahead (idle layout) pass, of the shared container where it is
 * composed, read by the placeholder that stands in for it in the other scene. Per container, so
 * per scene window.
 */
private class ShoalStatusIconsSize {
    var size by mutableStateOf(IntSize.Zero)
}

private val shoalStatusIconsSizes = WeakHashMap<StatusIconContainer, ShoalStatusIconsSize>()

/**
 * The scene that composes (and so draws) the shared status icons now, or null if none does.
 *
 * During a Shade <-> Quick Settings swipe this is the Shade: SceneContainerConfig puts it above
 * Quick Settings, so it is the scene whose copy of [ShadeHeader.Elements.CollapsedContentEnd]
 * (HighestZIndexContentPicker) STL draws. Otherwise the scene of the two that takes part in the
 * transition, or the current scene when idle. Both scenes evaluate this on the same state in the
 * same composition, so at most one of them composes the container (one View, one icon group).
 */
private fun shoalStatusIconsScene(layoutState: SceneTransitionLayoutState): ContentKey? {
    val state = layoutState.transitionState
    if (state is TransitionState.Transition) {
        val inFrom = state.fromContent in ShoalHeaderScenes
        val inTo = state.toContent in ShoalHeaderScenes
        when {
            inFrom && inTo -> return Scenes.Shade
            inTo -> return state.toContent
            inFrom -> return state.fromContent
        }
    }
    return state.currentScene.takeIf { it in ShoalHeaderScenes }
}

/**
 * IchthysOS "Shoal": the header's status icons as ONE [StatusIconContainer] shared by the Shade and
 * Quick Settings scenes. It is composed only in the scene that draws it
 * ([shoalStatusIconsScene]); the other lays out an empty placeholder of the same size.
 *
 * With one container per scene, the Shade's container drew the Shade -> Quick Settings swipe while
 * STL measured the shared header end ([ShadeHeader.Elements.CollapsedContentEnd]) at a size
 * interpolated between its idle sizes in the two scenes. The Quick Settings scene's idle size came
 * from its own container (that scene is always composed, but laid out only while shown) and did
 * not match; the difference came out of the status icons, the only weighted child of the row, and
 * the lattice dropped the icons that no longer fit (mute) until the swipe ended and Quick
 * Settings' container took over, with mute stacked above Wi-Fi. Clock, date and battery stayed.
 *
 * Not an STL MovableElement on purpose: its placeholder keeps the size the element last had in
 * that scene (Quick Settings is always composed, so that is the size from the last time Quick
 * Settings was shown), which is the same stale-width interpolation again. This placeholder follows
 * the container's live idle size, so the two scenes' idle sizes are always the same.
 */
@Composable
private fun ContentScope.ShoalSharedStatusIcons(
    iconContainer: StatusIconContainer,
    modifier: Modifier,
    icons: @Composable (Modifier) -> Unit,
) {
    val measured = remember(iconContainer) {
        shoalStatusIconsSizes.getOrPut(iconContainer) { ShoalStatusIconsSize() }
    }
    val composeHere by
        remember(layoutState, contentKey) {
            derivedStateOf { shoalStatusIconsScene(layoutState) == contentKey }
        }
    // IchthysOS: the header shows every status icon, as the stock QS header did
    // (setShouldRestrictIcons(false)): the zig-zag lattice then never hides an icon for lack of
    // width, so the icon set is the StatusBarIconController's alone, in every scene and transition.
    SideEffect {
        if (iconContainer.isRestrictingIcons) {
            iconContainer.setShouldRestrictIcons(false)
            iconContainer.requestLayout()
        }
    }
    if (composeHere) {
        icons(
            modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                // Only the idle size: during a transition the approach pass measures at the size
                // STL interpolates, which must not feed back into the placeholder.
                if (isLookingAhead || layoutState.currentTransition == null) {
                    measured.size = IntSize(placeable.width, placeable.height)
                }
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
        )
    } else {
        Layout(modifier = modifier) { _, constraints ->
            val size = measured.size
            layout(
                constraints.constrainWidth(size.width),
                constraints.constrainHeight(size.height),
            ) {}
        }
    }
}

/**
 * How far below the date's bottom edge the ringer pill's bottom edge sits: (pill height 24 dp -
 * the 13 sp date's line) / 2 = 6 dp would centre it on the date's line.
 *
 * IchthysOS: 16 dp further down (owner: "too high up"): centred on the date's line it sat tight
 * under the date and the clock, and its end ran into the weather's "high / low" line. Lower, it
 * has room below the header text and still ends well above the Brightness row (about 15 dp of
 * the ~30 dp gap remain). Only expanded Quick Settings draws it (showRinger), so the Shade's
 * header is unchanged; it is drawn into the space below the header, not laid out, so nothing
 * else moves.
 */
private val ShoalHeaderPillDrop = 22.dp
/** IchthysOS: the ringer pill, shared between the Shade and Quick Settings scenes. */
/** IchthysOS: the ringer pill, expanded Quick Settings' own (faded in by its transition). */
val ShoalRingerElement = ElementKey("ShoalRinger")
private val ShoalHeaderPaddingBottom = 8.dp
private const val ShoalDatePattern = "EEE, d MMMM"

/**
 * The status bar that appears above both the Notifications and Quick Settings shade overlays when
 * overlay shade is enabled.
 */
@Composable
fun ContentScope.OverlayShadeHeader(
    viewModel: ShadeHeaderViewModel,
    notificationsHighlight: ChipHighlightModel,
    quickSettingsHighlight: ChipHighlightModel,
    showClock: Boolean,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding =
        maxOf(
            LocalScreenCornerRadius.current / 2f,
            Shade.Dimensions.HorizontalPadding,
            OverlayShade.Dimensions.PanelPaddingHorizontal,
        )

    // This layout assumes it is globally positioned at (0, 0) and is the same width as the screen.
    CutoutAwareShadeHeader(
        statusBarHeightPx = viewModel.statusBarHeightPx,
        modifier = modifier,
        startContent = {
            Box(modifier = Modifier.layoutId(ShadeHeader.LayoutId.StartContent)) {
                ShadeHighlightChip(
                    backgroundColor = notificationsHighlight.backgroundColor,
                    hoverBackgroundColor = notificationsHighlight.hoverBackgroundColor,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    onClick = viewModel::onNotificationIconChipClicked,
                    clickTargetModifier =
                        Modifier.fillMaxHeight().padding(horizontal = horizontalPadding),
                    modifier =
                        Modifier.bouncy(
                            isEnabled = viewModel.animateNotificationsChipBounce,
                            onBoundsChange = { bounds ->
                                viewModel.onDualShadeEducationElementBoundsChange(
                                    element = DualShadeEducationElement.Notifications,
                                    bounds = bounds,
                                )
                            },
                        ),
                ) {
                    if (showClock) {
                        Clock(textColor = notificationsHighlight.foregroundColor)
                    }
                    VariableDayDate(
                        longerDateText = viewModel.longerDateText,
                        shorterDateText = viewModel.shorterDateText,
                        textColor = notificationsHighlight.foregroundColor,
                    )
                }
            }
        },
        endContent = {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.layoutId(ShadeHeader.LayoutId.EndContent),
            ) {
                ShadeHighlightChip(
                    backgroundColor = quickSettingsHighlight.backgroundColor,
                    hoverBackgroundColor = quickSettingsHighlight.hoverBackgroundColor,
                    onClick = viewModel::onSystemIconChipClicked,
                    clickTargetModifier =
                        Modifier.fillMaxHeight().padding(horizontal = horizontalPadding),
                    modifier =
                        Modifier.bouncy(
                            isEnabled = viewModel.animateSystemIconChipBounce,
                            onBoundsChange = { bounds ->
                                viewModel.onDualShadeEducationElementBoundsChange(
                                    element = DualShadeEducationElement.QuickSettings,
                                    bounds = bounds,
                                )
                            },
                        ),
                ) {
                    val paddingEnd = with(LocalDensity.current) { 3.sp.toDp() }
                    StatusIcons(
                        viewModel = viewModel,
                        useExpandedFormat = false,
                        modifier = Modifier.padding(end = paddingEnd).weight(1f, fill = false),
                        foregroundColor = quickSettingsHighlight.foregroundColor.toArgb(),
                        backgroundColor = quickSettingsHighlight.backgroundColor.toArgb(),
                    )
                    BatteryInfo(
                        viewModel = viewModel,
                        showIcon = true,
                        useExpandedFormat = false,
                        chipHighlightModel = quickSettingsHighlight,
                    )
                }
                if (!groupedPrivacyChip() && viewModel.isPrivacyChipVisible) {
                    Box(
                        modifier = Modifier.fillMaxHeight().padding(horizontal = horizontalPadding)
                    ) {
                        PrivacyChip(
                            privacyList = viewModel.privacyItems,
                            onClick = viewModel::onPrivacyChipClicked,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
        },
    )
}

/** The header that appears at the top of the Quick Settings shade overlay. */
@Composable
fun QuickSettingsOverlayHeader(viewModel: ShadeHeaderViewModel, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        ShadeCarrierGroup(viewModel = viewModel)
        BatteryInfo(viewModel = viewModel, showIcon = false, useExpandedFormat = true)
    }
}

@Composable
fun ContentScope.QuickSettingsOverlayPrivacyChip(
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier = Modifier,
) {
    if (groupedPrivacyChip() && viewModel.isPrivacyChipVisible) {
        Box(modifier = modifier.height(48.dp).fillMaxWidth()) {
            PrivacyChip(
                privacyList = viewModel.privacyItems,
                onClick = viewModel::onPrivacyChipClicked,
                showPrivacyText = true,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/*
 * Places startContent and endContent according to the location of the display cutout.
 * Assumes it is globally positioned at (0, 0) and the same size as the screen.
 */
@Composable
private fun CutoutAwareShadeHeader(
    statusBarHeightPx: Int,
    modifier: Modifier = Modifier,
    startContent: @Composable () -> Unit,
    endContent: @Composable () -> Unit,
) {
    val cutoutProvider = LocalDisplayCutout.current
    Layout(
        modifier = modifier.sysuiResTag(ShadeHeader.TestTags.Root),
        contents = listOf(startContent, endContent),
    ) { measurables, constraints ->
        val measurableStartContent = measurables[0].byLayoutId<ShadeHeader.LayoutId>()
        val measurableEndContent = measurables[1].byLayoutId<ShadeHeader.LayoutId>()
        val cutout = cutoutProvider()

        val cutoutWidth = cutout.width
        val cutoutHeight = cutout.height
        val cutoutTop = cutout.top
        val cutoutLocation = cutout.location

        check(constraints.hasBoundedWidth)

        val screenWidth = constraints.maxWidth
        val width = max((screenWidth - cutoutWidth) / 2, 0)
        val height = max(cutoutHeight + (cutoutTop * 2), statusBarHeightPx)
        val childConstraints = Constraints.fixed(width, height)

        fun measureStart(constraints: Constraints) =
            measurableStartContent[ShadeHeader.LayoutId.StartContent]!!.measure(constraints)
        fun measureEnd(constraints: Constraints) =
            measurableEndContent[ShadeHeader.LayoutId.EndContent]!!.measure(constraints)

        layout(screenWidth, height) {
            if (cutoutLocation == CutoutLocation.RIGHT) {
                val isRightToLeftEnabled = layoutDirection == LayoutDirection.Rtl
                if (isRightToLeftEnabled) {
                    val rightCutoutChildConstraints =
                        Constraints.fixed(screenWidth - cutoutWidth, height)
                    val startPlaceable = measureStart(rightCutoutChildConstraints)
                    val endPlaceable = measureEnd(rightCutoutChildConstraints)

                    startPlaceable.place(
                        x = screenWidth - cutoutWidth - startPlaceable.width,
                        y = 0,
                    )
                    endPlaceable.place(x = 0, y = 0)
                } else {
                    val startPlaceable = measureStart(childConstraints)
                    val endPlaceable = measureEnd(childConstraints)
                    startPlaceable.placeRelative(x = 0, y = 0)
                    endPlaceable.placeRelative(x = startPlaceable.width, y = 0)
                }
            } else {
                val startPlaceable = measureStart(childConstraints)
                val endPlaceable = measureEnd(childConstraints)
                if (cutoutLocation == CutoutLocation.NONE) {
                    startPlaceable.placeRelative(x = 0, y = 0)
                    endPlaceable.placeRelative(x = startPlaceable.width, y = 0)
                } else if (cutoutLocation == CutoutLocation.CENTER) {
                    startPlaceable.placeRelative(x = 0, y = 0)
                    endPlaceable.placeRelative(x = startPlaceable.width + cutoutWidth, y = 0)
                } else {
                    // CutoutLocation.LEFT
                    startPlaceable.placeRelative(x = cutoutWidth, y = 0)
                    endPlaceable.placeRelative(x = startPlaceable.width + cutoutWidth, y = 0)
                }
            }
        }
    }
}

@VisibleForTesting
object ShadeHeaderMotionTestKeys {
    val Alpha = MotionTestValueKey<Float>("alpha")
}

@Composable
private fun ContentScope.Clock(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    scale: Float = 1f,
    textColor: Color? = null,
    shoalHeader: Boolean = false,
) {
    val layoutDirection = LocalLayoutDirection.current

    ElementWithValues(
        key = ShadeHeader.Elements.Clock,
        modifier =
            modifier.motionTestValues {
                ShadeHeader.Elements.Clock.currentAlpha()?.let { alpha ->
                    alpha exportAs ShadeHeaderMotionTestKeys.Alpha
                }
            },
    ) {
        val animatedScale by animateElementFloatAsState(scale, ClockScale, canOverflow = false)

        content {
            AndroidView(
                factory = { context ->
                    // IchthysOS "Shoal": display face + accent seconds when a Shoal surface
                    // style is set; the stock clock otherwise.
                    IchthysHeaderClock(
                            ContextThemeWrapper(
                                context,
                                R.style.Theme_SystemUI_QuickSettings_Header,
                            ),
                            null,
                        )
                        .apply {
                            // IchthysOS: Clock re-applies the status bar's 4 dp side padding on
                            // every "interesting" configuration change (reloadDimens), so of the
                            // two header clocks (Shade's and Quick Settings') the older one ended
                            // 4 dp to the right of the other and the shared element moved on every
                            // swipe. The compose headers do their own padding.
                            setShouldApplyPadding(false)
                            setPaddingRelative(0, 0, 0, 0)
                            isSingleLine = true
                            textDirection = View.TEXT_DIRECTION_LOCALE
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL
                            if (onClick != null) {
                                isClickable = true
                                isFocusable = true
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                                setOnClickListener { onClick.invoke() }
                                accessibilityDelegate =
                                    object : View.AccessibilityDelegate() {
                                        override fun onInitializeAccessibilityNodeInfo(
                                            host: View,
                                            info: AccessibilityNodeInfo,
                                        ) {
                                            super.onInitializeAccessibilityNodeInfo(host, info)
                                            info.className = android.widget.Button::class.java.name
                                            info.isClickable = true
                                            info.isFocusable = true
                                        }
                                    }
                            }
                        }
                },
                update = { view ->
                    textColor?.let { view.setTextColor(it.toArgb()) }
                    // IchthysOS "Shoal": the shared Shade / QS header's 30 sp mono clock.
                    view.header = shoalHeader
                },
                modifier =
                    modifier
                        .wrapContentWidth(unbounded = true)
                        // use graphicsLayer instead of Modifier.scale to anchor transform to the
                        // (start, top) corner
                        .graphicsLayer {
                            scaleX = animatedScale
                            scaleY = animatedScale
                            transformOrigin =
                                TransformOrigin(
                                    when (layoutDirection) {
                                        LayoutDirection.Ltr -> 0f
                                        LayoutDirection.Rtl -> 1f
                                    },
                                    0.5f,
                                )
                        }
                        .thenIf(onClick != null) { Modifier.clickable { onClick?.invoke() } },
            )
        }
    }
}

@Composable
private fun BatteryInfo(
    viewModel: ShadeHeaderViewModel,
    showIcon: Boolean,
    useExpandedFormat: Boolean,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    chipHighlightModel: ChipHighlightModel? = null,
) {
    // IchthysOS "Shoal": in the zig-zag layout the header shows the same upright accent cell +
    // mono percentage as the status bar, instead of the stock battery.
    if (rememberIchthysZigzag()) {
        if (showIcon) {
            val tint = (chipHighlightModel?.foregroundColor ?: textColor).toArgb()
            Row(verticalAlignment = Alignment.CenterVertically) {
                // IchthysOS: the connected Bluetooth device's charge as the same pill as on the
                // status bar (the lattice's small disc steps aside for it).
                AndroidView(
                    factory = { context ->
                        IchthysBtBatteryView(context).apply { setQsHeader(true) }
                    },
                    update = { view -> view.onDarkChanged(ArrayList(), 0f, tint) },
                    modifier = Modifier.padding(end = 4.dp),
                )
                AndroidView(
                    // IchthysOS: the QS header battery style / percent overrides apply here.
                    factory = { context ->
                        IchthysShoalBatteryView(context).apply { setQsHeader(true) }
                    },
                    update = { view -> view.onDarkChanged(ArrayList(), 0f, tint) },
                    modifier = modifier.sysuiResTag(ShadeHeader.TestTags.BatteryTestTag),
                )
            }
        }
        return
    }
    val isQuickSettingsDarkTheme = isSystemInDarkTheme()
    // `viewModel.isShadeAreaDark` does not account for when the shade is pulled down and scrim is
    // applied behind the battery. Use `isSystemInDarkTheme` for sufficient contrast against the
    // shade.
    val isDarkProvider: IsAreaDark =
        when (chipHighlightModel) {
            // null means directly on top of the shade scrim.
            null -> IsAreaDark { isQuickSettingsDarkTheme }
            ChipHighlightModel.Transparent -> viewModel.isShadeAreaDark
            ChipHighlightModel.Strong -> IsAreaDark { !isQuickSettingsDarkTheme }
            ChipHighlightModel.Weak -> IsAreaDark { isQuickSettingsDarkTheme }
        }
    // IchthysOS: crDroid "Battery estimate" (qs_show_battery_estimate, default on). The legacy
    // ShadeHeaderController reader never runs with scene_container on.
    val showEstimateSetting = rememberIchthysSystemInt("qs_show_battery_estimate", 1) != 0
    BatteryWithEstimate(
        viewModelFactory = viewModel.batteryViewModelFactory,
        isDarkProvider = { isDarkProvider },
        showIcon = showIcon,
        showEstimate = useExpandedFormat && showEstimateSetting,
        textColor = textColor,
        modifier = modifier.sysuiResTag(ShadeHeader.TestTags.BatteryTestTag),
    )
}

@Composable
private fun CarrierTextWithSubscriptionId(
    viewModel: ShadeHeaderViewModel,
    subId: Int,
    textColor: Color,
    inverseTextColor: Color,
) {
    AndroidView(
        factory = { context ->
            ModernShadeCarrierGroupMobileView.constructAndBind(
                    context = context,
                    logger = viewModel.mobileIconsViewModel.get().logger,
                    slot = "mobile_carrier_shade_group",
                    viewModel =
                        (viewModel.mobileIconsViewModel
                            .get()
                            .viewModelForSub(subId, StatusBarLocation.SHADE_CARRIER_GROUP)
                            as ShadeCarrierGroupMobileIconViewModel),
                )
                .also { it.setOnClickListener { viewModel.onShadeCarrierGroupClicked() } }
        },
        update = { view ->
            view.setStyleAndTint(
                R.style.TextAppearance_QS_Status,
                textColor.toArgb(),
                inverseTextColor.toArgb(),
            )
        },
    )
}

@Composable
private fun CarrierTextNoSubscriptionId(viewModel: ShadeHeaderViewModel) {
    Text(
        text = viewModel.carrierText.toString(),
        modifier = Modifier.basicMarquee(),
        color = ShadeHeader.Colors.textColor,
        style =
            TextStyle(
                fontFamily =
                    FontFamily(Font(DeviceFontFamilyName("variable-body-medium-emphasized"))),
                letterSpacing = 0.01.em,
            ),
        maxLines = 1,
    )
}

@Composable
private fun ShadeCarrierGroup(viewModel: ShadeHeaderViewModel, modifier: Modifier = Modifier) {
    // IchthysOS "Shoal": no carrier name / "Emergency calls only" text in the header.
    if (ShoalShade.isActive(LocalContext.current)) {
        return
    }
    if (StatusBarMobileIconKairos.isEnabled) {
        ShadeCarrierGroupKairos(viewModel, modifier)
        return
    }

    val textColor = ShadeHeader.Colors.textColor
    val inverseTextColor = ShadeHeader.Colors.inverseTextColor
    val mobileSubIds = viewModel.mobileSubIds

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (mobileSubIds.isEmpty()) {
            CarrierTextNoSubscriptionId(viewModel)
        } else {
            for (subId in mobileSubIds) {
                CarrierTextWithSubscriptionId(viewModel, subId, textColor, inverseTextColor)
            }
        }
    }
}

@Composable
private fun ShadeCarrierGroupKairos(
    viewModel: ShadeHeaderViewModel,
    modifier: Modifier = Modifier,
) {
    val textColor = ShadeHeader.Colors.textColor
    val inverseTextColor = ShadeHeader.Colors.inverseTextColor
    Row(modifier = modifier) {
        ActivatedKairosSpec(
            buildSpec = viewModel.mobileIconsViewModelKairos.get().composeWrapper(),
            kairosNetwork = viewModel.kairosNetwork,
            name = nameTag("ShadeCarrierGroupKairos"),
        ) { iconsViewModel: MobileIconsViewModelKairosComposeWrapper ->
            if (iconsViewModel.icons.isEmpty()) {
                CarrierTextNoSubscriptionId(viewModel)
            } else {
                for ((subId, icon) in iconsViewModel.icons) {
                    CarrierTextWithSubscriptionIdKairos(
                        viewModel,
                        subId,
                        icon,
                        iconsViewModel.logger,
                        textColor,
                        inverseTextColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentScope.StatusIcons(
    viewModel: ShadeHeaderViewModel,
    useExpandedFormat: Boolean,
    @ColorInt foregroundColor: Int,
    @ColorInt backgroundColor: Int,
    modifier: Modifier = Modifier,
    /**
     * IchthysOS "Shoal": use the one container shared by the Shade and Quick Settings scenes
     * ([ShoalSharedStatusIcons]) instead of this content's own.
     */
    shareBetweenShadeAndQs: Boolean = false,
) {
    val statusIconContext = LocalStatusIconContext.current
    val shared =
        shareBetweenShadeAndQs &&
            !SystemStatusIconsInCompose.isEnabled &&
            contentKey in ShoalHeaderScenes
    val cacheKey = if (shared) ShoalStatusIconsCacheKey else contentKey
    val iconContainer = statusIconContext.iconContainer(cacheKey)
    val iconManager = statusIconContext.iconManager(cacheKey)
    val movableContent =
        remember(statusIconContext, iconManager) { statusIconContext.movableContent(iconManager) }

    // TODO(408001821): Add support for background color like [TintedIconManager.setTint].
    if (SystemStatusIconsInCompose.isEnabled) {
        SystemStatusIcons(
            viewModelFactory = viewModel.systemStatusIconsViewModelFactory,
            systemStatusIconBlocklistInteractor = viewModel.systemStatusIconsBlockListInteractor,
            tint = Color(foregroundColor),
        )
    } else {
        val isTransitioning = layoutState.isTransitioningBetween(Scenes.Shade, Scenes.QuickSettings)

        val icons: @Composable (Modifier) -> Unit = { iconsModifier ->
            SystemStatusIconsLegacy(
                iconContainer = iconContainer,
                iconManager = iconManager,
                statusBarIconController = viewModel.statusBarIconController,
                useExpandedFormat = useExpandedFormat,
                isTransitioning = isTransitioning,
                foregroundColor = foregroundColor,
                backgroundColor = backgroundColor,
                isSingleCarrier = viewModel.isSingleCarrier,
                isMicCameraIndicationEnabled = viewModel.isMicCameraIndicationEnabled,
                isPrivacyChipEnabled = viewModel.isPrivacyChipVisible,
                isLocationIndicationEnabled = viewModel.isLocationIndicationEnabled,
                modifier = iconsModifier,
                content = movableContent,
            )
        }
        if (shared) {
            ShoalSharedStatusIcons(iconContainer = iconContainer, modifier = modifier, icons = icons)
        } else {
            icons(modifier)
        }
    }
}

@Composable
private fun CarrierTextWithSubscriptionIdKairos(
    viewModel: ShadeHeaderViewModel,
    subId: Int,
    icon: MobileIconViewModelKairos,
    logger: MobileViewLogger,
    textColor: Color,
    inverseTextColor: Color,
) {
    Spacer(modifier = Modifier.width(5.dp))
    val scope = rememberCoroutineScope()
    AndroidView(
        factory = { context ->
            ModernShadeCarrierGroupMobileView.constructAndBindKairos(
                    context = context,
                    logger = logger,
                    slot = "mobile_carrier_shade_group",
                    viewModel =
                        ShadeCarrierGroupMobileIconViewModelKairos(icon, icon.iconInteractor),
                    scope = scope,
                    subscriptionId = subId,
                    location = StatusBarLocation.SHADE_CARRIER_GROUP,
                    kairosNetwork = viewModel.kairosNetwork,
                )
                .first
                .also { it.setOnClickListener { viewModel.onShadeCarrierGroupClicked() } }
        },
        update = { view ->
            view.setStyleAndTint(
                R.style.TextAppearance_QS_Status,
                textColor.toArgb(),
                inverseTextColor.toArgb(),
            )
        },
    )
}

@Composable
private fun ContentScope.PrivacyChip(
    privacyList: List<PrivacyItem>,
    onClick: (AbstractOngoingPrivacyChip) -> Unit,
    modifier: Modifier = Modifier,
    showPrivacyText: Boolean = false,
    // IchthysOS: false when the parent does not give the chip a fixed height. The chip view's
    // layout params are MATCH_PARENT in height, and AndroidView turns that into "exactly the max
    // height constraint", i.e. the whole screen under a parent that only wraps its content.
    fillParentHeight: Boolean = true,
) {
    AndroidView(
        factory = { context ->
            val view =
                if (groupedPrivacyChip()) {
                        ComposeOngoingPrivacyChip(context).apply {
                            this.showPrivacyText = showPrivacyText
                            layoutParams.apply { height = MATCH_PARENT }
                        }
                    } else {
                        OngoingPrivacyChip(context, null)
                    }
                    .also { privacyChip: AbstractOngoingPrivacyChip ->
                        privacyChip.privacyList = privacyList
                        privacyChip.setOnClickListener { onClick(privacyChip) }
                        if (!fillParentHeight) {
                            privacyChip.layoutParams =
                                privacyChip.layoutParams.apply { height = WRAP_CONTENT }
                        }
                    }
            view
        },
        update = {
            it.privacyList = privacyList
            if (groupedPrivacyChip()) {
                (it as ComposeOngoingPrivacyChip).apply { this.showPrivacyText = showPrivacyText }
            }
        },
        modifier = modifier.element(ShadeHeader.Elements.PrivacyChip),
    )
}

/** Modifies the given [Modifier] such that it shows a looping vertical bounce animation. */
@Composable
private fun Modifier.bouncy(
    isEnabled: Boolean,
    onBoundsChange: (bounds: IntRect) -> Unit,
): Modifier {
    val density = LocalDensity.current
    val animatable = remember { Animatable(0f) }
    LaunchedEffect(isEnabled) {
        if (isEnabled) {
            while (true) {
                // Lifts the element up to the first peak.
                animatable.animateTo(
                    targetValue = with(density) { -(10.dp).toPx() },
                    animationSpec =
                        tween(
                            durationMillis = 200,
                            easing = CubicBezierEasing(0.15f, 0f, 0.23f, 1f),
                        ),
                )
                // Drops the element back to the ground from the first peak.
                animatable.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        tween(
                            durationMillis = 167,
                            easing = CubicBezierEasing(0.74f, 0f, 0.22f, 1f),
                        ),
                )
                // Lifts the element up again, this time to the second, smaller peak.
                animatable.animateTo(
                    targetValue = with(density) { -(5.dp).toPx() },
                    animationSpec =
                        tween(
                            durationMillis = 150,
                            easing = CubicBezierEasing(0.62f, 0f, 0.35f, 1f),
                        ),
                )
                // Drops the element back to the ground from the second peak.
                animatable.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        tween(
                            durationMillis = 117,
                            easing = CubicBezierEasing(0.67f, 0f, 0.51f, 1f),
                        ),
                )
                // Wait for a moment before repeating it.
                delay(1000)
            }
        } else {
            animatable.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = 500))
        }
    }

    return this.thenIf(isEnabled) {
        Modifier.onGloballyPositioned { coordinates ->
                val offset = coordinates.positionInWindow()
                onBoundsChange(
                    IntRect(
                        offset = IntOffset(x = offset.x.roundToInt(), y = offset.y.roundToInt()),
                        size = coordinates.size,
                    )
                )
            }
            .offset { IntOffset(x = 0, y = animatable.value.roundToInt()) }
    }
}

private fun shouldUseExpandedFormat(state: SceneTransitionLayoutState): Boolean {
    return state.isIdle(Scenes.QuickSettings) ||
        (state is TransitionState.Transition &&
            ((state.isTransitioning(to = Scenes.QuickSettings) && state.progress >= 0.5) ||
                (state.isTransitioning(from = Scenes.QuickSettings) && state.progress <= 0.5)))
}
