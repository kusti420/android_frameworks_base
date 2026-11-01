/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.composable.elements

import android.content.Context
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import com.android.compose.animation.scene.BaseMutableSceneTransitionLayoutState
import com.android.systemui.keyguard.ICHTHYS_DEPTH_UNLOCK_MS
import kotlinx.coroutines.delay
import com.android.compose.animation.scene.BaseContentScope
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementContentScope
import com.android.compose.modifiers.thenIf
import com.android.systemui.dagger.SysUISingleton
import com.android.settingslib.ichthys.clock.LockNotifications
import com.android.systemui.keyguard.data.repository.IchthysDepthRepository
import com.android.systemui.keyguard.data.repository.IchthysLockNotifRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.ui.composable.LockscreenTouchHandling
import com.android.systemui.keyguard.ui.composable.modifier.burnInAware
import com.android.systemui.keyguard.ui.composable.trackBurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenRootViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.MediaViewController
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.plugins.keyguard.ui.composable.elements.BaseLockscreenElement.ElementSource
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.AmbientIndicationArea
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Clock
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.LockIcon
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.MediaCarousel
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Region
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.SettingsMenu
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Shortcuts
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Smartspace
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.StatusBar
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope.Companion.LockscreenElement
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * IchthysOS: how long past its own length a programmatic Lockscreen to Gone transition may run
 * before the lock screen root settles it at Gone (see RootElement).
 */
private const val UNLOCK_SETTLE_GRACE_MS = 2500L

@SysUISingleton
/** Provides an element representing all lockscreen ui */
class LockscreenRootElementProvider
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val viewModelFactory: LockscreenRootViewModel.Factory,
    private val aodBurnInViewModel: AodBurnInViewModel,
    private val keyguardClockViewModel: KeyguardClockViewModel,
    private val depthRepository: IchthysDepthRepository,
    private val keyguardInteractor: KeyguardInteractor,
    private val mediaViewController: MediaViewController,
    private val notifRepository: IchthysLockNotifRepository,
    private val dockProvider: IchthysNotificationDockProvider,
) : LockscreenElementProvider {
    override val elements: List<LockscreenElement> by lazy { listOf(RootElement()) }

    private inner class RootElement : LockscreenElement {
        override val key = LockscreenElementKeys.Root
        override val context = this@LockscreenRootElementProvider.context
        override val source = ElementSource.STANDARD

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            val viewModel =
                rememberViewModel("LockscreenRoot-viewModel") { viewModelFactory.create() }
            val burnInTracker =
                trackBurnInParameters(
                    aodBurnInViewModel,
                    { viewModel.burnIn },
                    keyguardClockViewModel,
                )
            LockscreenTouchHandling(viewModel.touchHandlingFactory) { onSettingsMenuPlaced ->
                val innerContext =
                    context.copy(
                        burnInAware = Modifier.burnInAware(viewModel.burnIn, isClock = false),
                        burnInAwareClock = Modifier.burnInAware(viewModel.burnIn, isClock = true),
                        onElementPositioned = { key, rect ->
                            when (key) {
                                Clock.Small -> {
                                    burnInTracker.onSmallClockTopChanged(rect.top)
                                    viewModel.setSmallClockBottom(rect.bottom)
                                }
                                Smartspace.Cards -> {
                                    burnInTracker.onSmartspaceTopChanged(rect.top)
                                    viewModel.setSmartspaceCardBottom(rect.bottom)
                                }
                                MediaCarousel -> viewModel.setMediaPlayerBottom(rect.bottom)
                                Shortcuts.Start -> viewModel.setShortcutTop(rect.top)
                                Shortcuts.End -> viewModel.setShortcutTop(rect.top)
                                SettingsMenu -> onSettingsMenuPlaced(rect)
                                else -> {}
                            }
                        },
                    )

                val dozeAmount by keyguardInteractor.dozeAmount.collectAsStateWithLifecycle(0f)
                // IchthysOS: with depth off, no ripples, no lens and no painting on the clock.
                val depthOn by depthRepository.enabled.collectAsStateWithLifecycle()
                // IchthysOS depth (tracker#58): the layers come apart while unlocking.
                val layoutState = contentScope.layoutState
                depthRepository.unlockProgress = {
                    layoutState.currentTransition
                        ?.takeIf { it.fromContent == Scenes.Lockscreen && it.toContent == Scenes.Gone }
                        ?.progress
                        ?.coerceIn(0f, 1f) ?: 0f
                }
                // IchthysOS: a swipe takes the layers along its direction; a fingerprint, face or
                // tap unlock (programmatic) dissolves the subject from the lock icon instead.
                depthRepository.unlockBySwipe = {
                    layoutState.currentTransition
                        ?.takeIf { it.fromContent == Scenes.Lockscreen && it.toContent == Scenes.Gone }
                        ?.isInitiatedByUserInput ?: false
                }
                depthRepository.burnInShift = { viewModel.burnIn.translation }
                // IchthysOS: a programmatic unlock (fingerprint, face, tap) always ends. Seen once:
                // after a fingerprint unlock the lock screen stayed over the launcher, its
                // fingerprint icon on it, and nothing unlocked until the next lock. Should such a
                // Lockscreen to Gone transition still run well past its length, it is settled at
                // Gone (where it was going). A swipe is the finger's to hold, so it is left alone.
                val programmaticUnlock =
                    layoutState.currentTransition?.takeIf {
                        it.fromContent == Scenes.Lockscreen &&
                            it.toContent == Scenes.Gone &&
                            !it.isInitiatedByUserInput
                    }
                LaunchedEffect(programmaticUnlock) {
                    val unlock = programmaticUnlock ?: return@LaunchedEffect
                    delay(ICHTHYS_DEPTH_UNLOCK_MS + UNLOCK_SETTLE_GRACE_MS)
                    if (layoutState.currentTransition === unlock && unlock.currentScene == Scenes.Gone) {
                        Log.w(
                            "IchthysLockscreen",
                            "Lockscreen to Gone still running after " +
                                "${ICHTHYS_DEPTH_UNLOCK_MS + UNLOCK_SETTLE_GRACE_MS} ms " +
                                "(progress ${unlock.progress}): settling at Gone",
                        )
                        (layoutState as? BaseMutableSceneTransitionLayoutState)?.snapTo(Scenes.Gone)
                    }
                }
                // IchthysOS Quiet dock (tracker#58): the dock's sheet is a lock screen thing only.
                // It closes as the screen dozes, as any scene change starts (an unlock, the shade)
                // and when the lock screen leaves composition.
                val leaving = layoutState.currentTransition != null
                LaunchedEffect(dozeAmount > 0f, leaving) {
                    if (dozeAmount > 0f || leaving) notifRepository.setSheetOpen(false)
                }
                DisposableEffect(Unit) { onDispose { notifRepository.setSheetOpen(false) } }
                with(scopeFactory.create(contentScope, innerContext)) {
                    LockscreenSceneLayout(
                        viewModel.isUdfpsSupported,
                        depth = {
                            // IchthysOS: the lock screen media art (crDroid's MediaViewController
                            // scrim: the playing track's cover, with its blur, filter and fade)
                            // as the lowest wallpaper layer; the depth pictures fade out over it
                            // while it shows. Hung behind the scene container by
                            // CentralSurfacesImpl it was never seen: the backdrop covered it.
                            AndroidView(
                                factory = {
                                    mediaViewController.getMediaArtScrim().also { art ->
                                        (art.parent as? ViewGroup)?.removeView(art)
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            IchthysDepthBackdrop(
                                depthRepository,
                                dozeAmount,
                                Modifier.ichthysDepthPlane(depthRepository, IchthysDepthPlane.BACK),
                            )
                        },
                        depthCutout = { if (depthOn) IchthysDepthLensOverlay(depthRepository) },
                        depthTouch =
                            if (depthOn) Modifier.ichthysDepthLens(depthRepository) else Modifier,
                        dock = {
                            dockProvider.Dock(
                                dozeAmount,
                                Modifier.burnInAware(viewModel.burnIn, isClock = false),
                            )
                        },
                        onBottomZone = { keepOutTop, dockTop ->
                            notifRepository.keepOutTop = keepOutTop
                            notifRepository.dockTop = dockTop
                        },
                        // IchthysOS: chrome goes at the very start of every unlock.
                        chrome = Modifier.ichthysUnlockChrome(depthRepository),
                    )
                }
            }
        }
    }
}

@Immutable
interface UnfoldTranslations {
    /**
     * Amount of horizontal translation to apply to elements that are aligned to the start side
     * (left in left-to-right layouts). Can also be used as horizontal padding for elements that
     * need horizontal padding on both side. In pixels.
     */
    val start: Float

    /**
     * Amount of horizontal translation to apply to elements that are aligned to the end side (right
     * in left-to-right layouts). In pixels.
     */
    val end: Float
}

/**
 * Encapsulates alignment lines produced by the lock icon element.
 *
 * Because the lock icon is also the same element as the under-display fingerprint sensor (UDFPS),
 * [LockscreenSceneLayout] uses the lock icon provided alignment lines to make sure that other
 * elements on screen do not overlap with the lock icon.
 */
object LockIconAlignmentLines {
    /** The left edge of the lock icon. */
    val Left =
        VerticalAlignmentLine(
            merger = { old, new ->
                // When two left alignment line values are provided, choose the leftmost one:
                min(old, new)
            }
        )

    /** The top edge of the lock icon. */
    val Top =
        HorizontalAlignmentLine(
            merger = { old, new ->
                // When two top alignment line values are provided, choose the topmost one:
                min(old, new)
            }
        )

    /** The right edge of the lock icon. */
    val Right =
        VerticalAlignmentLine(
            merger = { old, new ->
                // When two right alignment line values are provided, choose the rightmost one:
                max(old, new)
            }
        )

    /** The bottom edge of the lock icon. */
    val Bottom =
        HorizontalAlignmentLine(
            merger = { old, new ->
                // When two bottom alignment line values are provided, choose the bottommost one:
                max(old, new)
            }
        )
}

/**
 * Arranges the layout for the lockscreen scene.
 *
 * Takes care of figuring out the correct layout configuration based on the device form factor,
 * orientation, and the current UI state.
 *
 * Notes about some non-obvious behaviors:
 * - [LockIcon] is drawn according to the [LockIconAlignmentLines] that it must supply. The layout
 *   logic uses those alignment lines to make sure other elements don't overlap with the lock icon
 *   as it may be drawn on top of the UDFPS (under display fingerprint sensor).
 * - IchthysOS depth (tracker#58): [depth] fills the screen under everything, [depthCutout] fills it
 *   over the upper region (the clock) and under the lock icon and the lower region.
 * - IchthysOS Quiet dock (tracker#58): everything at the bottom keeps to one keep-out, the lock
 *   icon's bounds (the fingerprint sensor's on UDFPS) plus [LockNotifications.KEEP_OUT_MARGIN_DP].
 *   The [dock] hangs [LockNotifications.DOCK_GAP_DP] above it (never off the screen's bottom, so
 *   it never meets the sensor); the upper region ends above the dock, or above the keep-out when
 *   the dock takes no height (cards mode, no notifications). The dock is placed before the upper
 *   region: the depth subject (drawn with the clock) stays in front of it, as of the clock.
 */
@Composable
private fun LockscreenScope<ContentScope>.LockscreenSceneLayout(
    isUdfpsSupported: Boolean,
    modifier: Modifier = Modifier,
    depth: @Composable () -> Unit = {},
    depthCutout: @Composable () -> Unit = {},
    depthTouch: Modifier = Modifier,
    dock: @Composable () -> Unit = {},
    onBottomZone: (keepOutTop: Int, dockTop: Int) -> Unit = { _, _ -> },
    chrome: Modifier = Modifier,
) {
    val keepOutMarginPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        LockNotifications.KEEP_OUT_MARGIN_DP.dp.roundToPx()
    }
    val dockGapPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        LockNotifications.DOCK_GAP_DP.dp.roundToPx()
    }
    val upperGapPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        LockNotifications.UPPER_GAP_DP.dp.roundToPx()
    }
    Layout(
        content = {
            Box { depth() }
            LockscreenElement(StatusBar)
            // IchthysOS: [chrome] (ichthysUnlockChrome) on everything that is not a depth layer:
            // the dock, the fingerprint icon, the indication line, the shortcuts and "Customize
            // lock screen" leave at the very start of every unlock.
            Box(with(contentScope) { Modifier.element(IchthysDockElementKey) }.then(chrome)) {
                dock()
            }
            LockscreenElement(Region.Upper)
            Box { depthCutout() }
            LockscreenElement(LockIcon, chrome)
            LockscreenElement(AmbientIndicationArea, chrome)
            LockscreenElement(Region.Lower, chrome)
            LockscreenElement(SettingsMenu, chrome)
        },
        // Hide the lock screen elements when an overlay is shown above.
        modifier =
            modifier
                .then(depthTouch)
                .thenIf(contentScope.isIdleWithOverlay()) {
                    Modifier.graphicsLayer { alpha = 0f }
                },
    ) { measurables, constraints ->
        check(measurables.size == 9)
        val depthMeasurable = measurables[0]
        val statusBarMeasurable = measurables[1]
        val dockMeasurable = measurables[2]
        val upperRegionMeasurable = measurables[3]
        val depthCutoutMeasurable = measurables[4]
        val lockIconMeasurable = measurables[5]
        val ambientIndicationMeasurable = measurables[6]
        val lowerRegionMeasurable = measurables[7]
        val settingsMenuMeasurable = measurables[8]
        val fullScreen = Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
        val depthPlaceable = depthMeasurable.measure(fullScreen)
        val depthCutoutPlaceable = depthCutoutMeasurable.measure(fullScreen)

        val statusBarPlaceable =
            statusBarMeasurable.measure(constraints = Constraints.fixedWidth(constraints.maxWidth))

        val lockIconPlaceable =
            lockIconMeasurable.measure(constraints.copy(minWidth = 0, minHeight = 0))

        // Height available between the bottom of the status bar and either the top of the UDFPS
        // icon (if one is showing) or the bottom of the screen, if no UDFPS icon is showing.
        val lockIconBounds =
            IntRect(
                left = lockIconPlaceable[LockIconAlignmentLines.Left],
                top = lockIconPlaceable[LockIconAlignmentLines.Top],
                right = lockIconPlaceable[LockIconAlignmentLines.Right],
                bottom = lockIconPlaceable[LockIconAlignmentLines.Bottom],
            )

        val ambientIndicationPlaceable =
            ambientIndicationMeasurable.measure(
                constraints = Constraints.fixedWidth(constraints.maxWidth)
            )

        // IchthysOS Quiet dock: the keep-out around the sensor, and the dock above it. The dock
        // measures to nothing when it has nothing to show; then the upper region reaches the
        // keep-out itself.
        val keepOutTop = lockIconBounds.top - keepOutMarginPx
        val dockPlaceable =
            dockMeasurable.measure(
                Constraints(
                    minWidth = 0,
                    maxWidth = constraints.maxWidth.coerceAtLeast(0),
                    minHeight = 0,
                    maxHeight = (keepOutTop - dockGapPx - statusBarPlaceable.measuredHeight).coerceAtLeast(0),
                )
            )
        val dockShown = dockPlaceable.measuredHeight > 0
        val dockBottom = keepOutTop - dockGapPx
        val dockTop = dockBottom - dockPlaceable.measuredHeight
        val upperRegionBottom = if (dockShown) dockTop - upperGapPx else keepOutTop
        onBottomZone(keepOutTop, if (dockShown) dockTop else keepOutTop)

        var upperRegionMaxHeight = upperRegionBottom - statusBarPlaceable.measuredHeight
        var lowerRegionMaxHeight = constraints.maxHeight - lockIconBounds.bottom

        if (!isUdfpsSupported) {
            upperRegionMaxHeight -= ambientIndicationPlaceable.measuredHeight
        } else {
            lowerRegionMaxHeight -= ambientIndicationPlaceable.measuredHeight
        }

        val upperRegionPlaceable =
            upperRegionMeasurable.measure(
                Constraints(
                    minWidth = 0,
                    maxWidth = constraints.maxWidth.coerceAtLeast(0),
                    minHeight = 0,
                    maxHeight = upperRegionMaxHeight.coerceAtLeast(0),
                )
            )

        val lowerRegionPlaceable =
            lowerRegionMeasurable.measure(
                Constraints(
                    minWidth = 0,
                    maxWidth = constraints.maxWidth.coerceAtLeast(0),
                    minHeight = 0,
                    maxHeight = lowerRegionMaxHeight.coerceAtLeast(0),
                )
            )

        // IchthysOS: at its own size (the root's constraints may be the whole screen).
        val settingsMenuPlaceable =
            settingsMenuMeasurable.measure(constraints.copy(minWidth = 0, minHeight = 0))

        layout(constraints.maxWidth, constraints.maxHeight) {
            // Placement order is drawing order.
            depthPlaceable.place(0, 0)
            statusBarPlaceable.place(0, 0)
            // IchthysOS Quiet dock: centred, its bottom a gap above the keep-out; under the upper
            // region, so the depth subject (drawn with the clock) stands in front of it.
            if (dockShown) {
                dockPlaceable.placeRelative(
                    (constraints.maxWidth - dockPlaceable.measuredWidth) / 2,
                    dockTop,
                )
            }
            upperRegionPlaceable.placeRelative(0, statusBarPlaceable.measuredHeight)
            depthCutoutPlaceable.place(0, 0)
            lockIconPlaceable.place(lockIconBounds.left, lockIconBounds.top)

            if (isUdfpsSupported) {
                // Place below UDFPS icon.
                ambientIndicationPlaceable.placeRelative(
                    0,
                    lockIconBounds.top + lockIconPlaceable.measuredHeight,
                )
            } else {
                // Place above lock icon.
                ambientIndicationPlaceable.placeRelative(
                    0,
                    lockIconBounds.top - ambientIndicationPlaceable.measuredHeight,
                )
            }

            lowerRegionPlaceable.place(
                0,
                constraints.maxHeight - lowerRegionPlaceable.measuredHeight,
            )

            // IchthysOS: "Customize lock screen" hangs from the status bar, centred. The bottom
            // has no free room on the A52s: the stock spot (between the shortcuts) is on the
            // under-display fingerprint sensor, and just above the keep-out it covered the Quiet
            // dock (the dock's bottom is DOCK_GAP_DP above the keep-out, the menu was 48 dp tall
            // plus 32 dp of padding), with the indication line and the shortcuts under it. Up
            // here it only ever lies over the clock face, never anything tappable; the element
            // keeps clear of a top-corner weather line and closes the dock's sheet itself
            // (SettingsMenuElementProvider). Drawn last, in front of the clock and the subject.
            settingsMenuPlaceable.placeRelative(
                (constraints.maxWidth - settingsMenuPlaceable.measuredWidth) / 2,
                statusBarPlaceable.measuredHeight,
            )
        }
    }
}

private fun BaseContentScope.isIdleWithOverlay(): Boolean {
    return !layoutState.isTransitioning() && layoutState.currentOverlays.isNotEmpty()
}
