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
 */

package com.android.systemui.qs.ui.composable

import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.window.core.layout.WindowSizeClass
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.animateContentFloatAsState
import com.android.compose.animation.scene.rememberMutableSceneTransitionLayoutState
import com.android.compose.animation.scene.transitions
import com.android.compose.gesture.gesturesDisabled
import com.android.compose.lifecycle.DisposableEffectWithLifecycle
import com.android.compose.lifecycle.LaunchedEffectWithLifecycle
import com.android.compose.modifiers.thenIf
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.common.ui.compose.windowinsets.CutoutLocation
import com.android.systemui.common.ui.compose.windowinsets.LocalDisplayCutout
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.notifications.intelligence.rules.shared.NmContextualDisplayLaunch
import com.android.systemui.notifications.intelligence.rules.ui.viewmodel.NotificationRulesParentViewModel
import com.android.systemui.notifications.ui.composable.HeadsUpNotificationPlaceholder
import com.android.systemui.notifications.ui.composable.ScrollingNotificationPanel
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.footer.ui.compose.FooterActionsWithAnimatedVisibility
import com.android.systemui.ichthys.notes.IchthysNotesViewModel
import com.android.systemui.ichthys.notes.ui.IchthysNotesPagerProgress
import com.android.systemui.ichthys.notes.ui.IchthysQsNotesPagerHost
import com.android.systemui.ichthys.notes.ui.LocalIchthysNotesPagerProgress
import com.android.systemui.qs.panels.ui.compose.EditMode
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsEnabled
import com.android.systemui.qs.shared.ui.QuickSettings
import com.android.systemui.qs.ui.composable.QuickSettingsScene.Companion.InternalScenes.Edit
import com.android.systemui.qs.ui.composable.QuickSettingsScene.Companion.InternalScenes.QS
import com.android.systemui.qs.ui.viewmodel.QuickSettingsSceneContentViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsUserActionsViewModel
import com.android.systemui.res.R
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.Scene
import com.android.systemui.shade.ui.composable.CollapsedShadeHeader
import com.android.systemui.shade.ui.composable.ExpandedShadeHeader
import com.android.systemui.shade.ui.composable.ShadePanelScrim
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import com.android.systemui.util.kotlin.toDp
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow

/** The Quick Settings (AKA "QS") scene shows the quick setting tiles. */
@SysUISingleton
class QuickSettingsScene
@Inject
constructor(
    private val shadeSession: SaveableSession,
    private val notificationStackScrollView: Lazy<NotificationScrollView>,
    private val notificationsPlaceholderViewModelFactory: NotificationsPlaceholderViewModel.Factory,
    private val actionsViewModelFactory: QuickSettingsUserActionsViewModel.Factory,
    private val contentViewModelFactory: QuickSettingsSceneContentViewModel.Factory,
    private val notificationRulesParentViewModelFactory: NotificationRulesParentViewModel.Factory,
    private val jankMonitor: InteractionJankMonitor,
    // IchthysOS: the notes page left of the tiles (IchthysQsNotesPage).
    private val notesViewModel: IchthysNotesViewModel,
) : ExclusiveActivatable(), Scene {
    override val key = Scenes.QuickSettings

    private val actionsViewModel: QuickSettingsUserActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override val userActions: Flow<Map<UserAction, UserActionResult>> = actionsViewModel.actions

    override val alwaysCompose: Boolean = true

    override suspend fun onActivated() {
        actionsViewModel.activate()
    }

    @Composable
    override fun ContentScope.Content(modifier: Modifier) {
        val viewModel =
            rememberViewModel("QuickSettingsScene-viewModel") { contentViewModelFactory.create() }
        val notificationsPlaceholderViewModel =
            rememberViewModel("QuickSettingsScene-notifPlaceholderViewModel") {
                notificationsPlaceholderViewModelFactory.create(Scenes.QuickSettings)
            }
        val notificationRulesParentViewModel =
            if (NmContextualDisplayLaunch.isEnabled) {
                rememberViewModel("QuickSettingsScene-notifRulesParentViewModel") {
                    notificationRulesParentViewModelFactory.create()
                }
            } else {
                null
            }

        val brightnessMirrorShowing =
            viewModel.qsContainerViewModel.brightnessSliderViewModel.showMirror
        val contentAlpha by
            animateFloatAsState(
                targetValue = if (brightnessMirrorShowing) 0f else 1f,
                label = "alphaAnimationBrightnessMirrorContentHiding",
            )

        LaunchedEffectWithLifecycle(key1 = Unit) {
            try {
                snapshotFlow { contentAlpha }
                    .collect { notificationsPlaceholderViewModel.setAlphaForBrightnessMirror(it) }
            } finally {
                notificationsPlaceholderViewModel.setAlphaForBrightnessMirror(1f)
            }
        }

        LaunchedEffectWithLifecycle(key1 = Unit) { viewModel.detectShadeModeChanges() }

        QuickSettingsScene(
            notificationStackScrollView = notificationStackScrollView.get(),
            viewModel = viewModel,
            headerViewModel = viewModel.qsContainerViewModel.shadeHeaderViewModel,
            notificationsPlaceholderViewModel = notificationsPlaceholderViewModel,
            notificationRulesParentViewModel = notificationRulesParentViewModel,
            modifier =
                modifier
                    .graphicsLayer { alpha = contentAlpha }
                    .thenIf(brightnessMirrorShowing) { Modifier.gesturesDisabled() },
            shadeSession = shadeSession,
            jankMonitor = jankMonitor,
            notesViewModel = notesViewModel,
        )
    }

    companion object {
        object InternalScenes {
            val QS = SceneKey("QuickSettingsMainPanel")
            val Edit = SceneKey("QuickSettingsEditPanel")

            private const val EDIT_MODE_TIME_MILLIS = 500

            val transitions = transitions {
                from(QS, Edit) {
                    spec = tween(durationMillis = EDIT_MODE_TIME_MILLIS)
                    fractionRange(start = 0.5f) { fade(Edit.rootElementKey) }
                    fractionRange(end = 0.5f) { fade(QS.rootElementKey) }
                }
            }
        }
    }
}

@Composable
private fun ContentScope.QuickSettingsScene(
    notificationStackScrollView: NotificationScrollView,
    viewModel: QuickSettingsSceneContentViewModel,
    headerViewModel: ShadeHeaderViewModel,
    notificationsPlaceholderViewModel: NotificationsPlaceholderViewModel,
    notificationRulesParentViewModel: NotificationRulesParentViewModel?,
    modifier: Modifier = Modifier,
    shadeSession: SaveableSession,
    jankMonitor: InteractionJankMonitor,
    notesViewModel: IchthysNotesViewModel,
) {
    val targetBlur by
        remember(layoutState) {
            derivedStateOf { viewModel.calculateBlur(layoutState.transitionState) }
        }
    val animatedBlurRadiusPx: Float by
        animateFloatAsState(targetValue = targetBlur, label = "QS-blurRadius")
    val onlyPunchHolesInThisScene =
        layoutState.isTransitioningBetween(Scenes.Gone, Scenes.QuickSettings) ||
            layoutState.isTransitioningBetween(Scenes.Lockscreen, Scenes.QuickSettings)
    Box(
        modifier
            .fillMaxSize()
            .blur(with(LocalDensity.current) { animatedBlurRadiusPx.toDp() })
            .thenIf(onlyPunchHolesInThisScene) {
                // Render the scene to an offscreen buffer so that BlendMode.DstOut only clears
                // this scene (and not the one under it) during a scene transition.
                Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            }
    ) {
        // This is the background for the whole scene, as the elements don't necessarily provide
        // a background that extends to the edges.
        ShadePanelScrim(viewModel.isTransparencyEnabled)

        val sceneState =
            rememberMutableSceneTransitionLayoutState(
                initialScene =
                    remember { if (viewModel.qsContainerViewModel.isEditing) Edit else QS },
                transitions = QuickSettingsScene.Companion.InternalScenes.transitions,
            )

        val coroutineScope = rememberCoroutineScope()

        DisposableEffectWithLifecycle(key1 = viewModel, key2 = sceneState) {
            onDispose {
                viewModel.qsContainerViewModel.editModeViewModel.stopEditing()
                sceneState.snapTo(QS)
            }
        }

        LaunchedEffectWithLifecycle(
            key1 = sceneState,
            key2 = viewModel.qsContainerViewModel.isEditing,
            key3 = coroutineScope,
        ) {
            if (viewModel.qsContainerViewModel.isEditing) {
                sceneState.setTargetScene(Edit, coroutineScope)
            } else {
                sceneState.setTargetScene(QS, coroutineScope)
            }
        }

        NestedSceneTransitionLayout(
            state = sceneState,
            debugName = "QuickSettingsScene",
            modifier = Modifier.fillMaxSize(),
        ) {
            scene(QS) {
                Element(QS.rootElementKey, Modifier) {
                    QuickSettingsContent(
                        Modifier,
                        viewModel,
                        headerViewModel,
                        this@QuickSettingsScene.verticalOverscrollEffect,
                        notesViewModel,
                    )
                }
            }

            scene(Edit) {
                Element(Edit.rootElementKey, Modifier) {
                    GridAnchor()
                    EditMode(
                        viewModel.qsContainerViewModel.editModeViewModel,
                        Modifier.testTag("edit_mode_scene")
                            .padding(horizontal = QuickSettingsShade.Dimensions.HorizontalPadding)
                            .padding(
                                top =
                                    headerViewModel.statusBarHeightPx.toDp(LocalContext.current).dp
                            ),
                    )
                }
            }
        }

        val shadeHorizontalPadding =
            dimensionResource(id = R.dimen.notification_panel_margin_horizontal)
        HeadsUpNotificationPlaceholder(
            tag = "QSScene",
            stackScrollView = notificationStackScrollView,
            viewModel = notificationsPlaceholderViewModel,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = shadeHorizontalPadding),
        )

        if (isAlwaysComposedContentVisible()) {
            // IchthysOS: the display's full height. Configuration.screenHeightDp and this
            // composition's container both leave out the navigation bar, but the notification
            // stack view spans the whole display: parked at either, the shelf's icon row showed
            // under the navigation bar (with a media player in Quick Settings). Notifications must
            // never be visible in the Quick Settings scene at rest.
            val context = LocalContext.current
            val screenHeight =
                maxOf(
                        LocalWindowInfo.current.containerSize.height,
                        context
                            .getSystemService(WindowManager::class.java)
                            .maximumWindowMetrics
                            .bounds
                            .height(),
                    )
                    .toFloat()
            /**
             * The minimum possible value for the top of the notification stack. In other words: how
             * high is the notification stack allowed to get when the scene is at rest. It may still
             * be translated farther upwards by a transition animation but, at rest, the top edge of
             * its bounds must be limited to be at or below this value.
             *
             * A 1 pixel is added to compensate for any kind of rounding errors to make sure 100%
             * that the notification stack is entirely "below" the entire screen.
             */
            // IchthysOS: plus a shelf and its gap. With its top below the cutoff the stack has no
            // height, and StackScrollAlgorithm clamps the rows and the shelf to a shelf height
            // above the top (shelfStart = stack height - shelf - gap): parked one pixel below the
            // screen, the shelf's icon row still showed at the screen's bottom edge.
            val shelfAllowance =
                dimensionResource(R.dimen.notification_shelf_height) +
                    dimensionResource(R.dimen.notification_divider_height)
            val minNotificationStackTop =
                screenHeight.roundToInt() + 1 + with(LocalDensity.current) {
                    shelfAllowance.roundToPx()
                }
            val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues()
            // TODO(b/436646848): remove ScrollingNotificationPanel from QuickSettings
            ScrollingNotificationPanel(
                tag = "QSScene",
                shadeSession = shadeSession,
                stackScrollView = notificationStackScrollView,
                viewModel = notificationsPlaceholderViewModel,
                notificationRulesParentViewModel = notificationRulesParentViewModel,
                jankMonitor = jankMonitor,
                shouldPunchHoleBehindScrim = true,
                shouldFillMaxHeight = true,
                isTransparencyEnabled = viewModel.isTransparencyEnabled,
                stackTopPadding = dimensionResource(id = R.dimen.notification_side_paddings_single),
                stackBottomPadding = { navigationBarPadding.calculateBottomPadding() },
                shouldIncludeHeadsUpSpace = false,
                isActivated = false,
                modifier =
                    Modifier.fillMaxWidth()
                        // Match the screen height with the scrim, so it covers the whole screen,
                        // when the stack "passes by" during the QS -> Gone transition.
                        .height(LocalWindowInfo.current.containerSize.height.dp)
                        .offset { IntOffset(x = 0, y = minNotificationStackTop) }
                        .padding(horizontal = shadeHorizontalPadding),
            )
        }
    }
}

@Composable
private fun ContentScope.QuickSettingsContent(
    modifier: Modifier,
    viewModel: QuickSettingsSceneContentViewModel,
    headerViewModel: ShadeHeaderViewModel,
    verticalOverscrollEffect: OverscrollEffect,
    notesViewModel: IchthysNotesViewModel,
) {
    val cutoutLocation = LocalDisplayCutout.current().location

    val shadeHorizontalPadding =
        dimensionResource(id = R.dimen.notification_panel_margin_horizontal)

    // TODO(b/280887232): implement the real UI.
    Box(
        modifier =
            modifier.fillMaxSize().thenIf(cutoutLocation != CutoutLocation.CENTER) {
                Modifier.displayCutoutPadding()
            }
    ) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val footerActionsViewModel =
            remember(lifecycleOwner, viewModel) {
                viewModel.getFooterActionsViewModel(lifecycleOwner)
            }
        animateContentFloatAsState(value = 1f, key = QuickSettings.SharedValues.TilesSquishiness)

        // ############## SCROLLING ################

        val scrollState = rememberScrollState()
        // When animating into the scene, we don't want it to be able to scroll, as it could mess
        // up with the expansion animation.
        val isScrollable =
            layoutState.isIdle(Scenes.QuickSettings) ||
                layoutState.isTransitioning(from = Scenes.QuickSettings)

        LaunchedEffectWithLifecycle(isScrollable) {
            if (!isScrollable) {
                scrollState.scrollTo(0)
            }
        }

        // ############# NAV BAR paddings ###############

        val navBarInsets = WindowInsets.navigationBars.asPaddingValues()

        // ############# Media ###############
        val mediaInRow = viewModel.qsContainerViewModel.showMediaInRow

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.fillMaxSize().overscroll(verticalOverscrollEffect).padding(navBarInsets),
        ) {
            // IchthysOS "Shoal": the header is a fixed child of this column, laid out exactly as
            // the Shade scene lays out its header (a fixed slot above the scrolling content), so
            // the shared clock, date and icons end a Shade <-> Quick Settings swipe where they
            // started. Inside the scrolling column below (verticalScroll + unbounded height) they
            // settled about 11 px off, and the transition's end moved them there.
            val shoalHeader = com.android.systemui.shade.ui.ShoalShade.isActive(LocalContext.current)
            // IchthysOS: the notes page (crDroid "Notes page" switch, default on). With it the
            // scrolling tile content sits in a two-page pager, and the header must not page with
            // it: the grid style's header is hoisted up here too, where the Shoal header already is.
            val notesPage = rememberIchthysSystemInt(IchthysQsSettings.NOTES_PAGE, 1) != 0
            val usesCollapsedHeader =
                LocalWindowSizeClass.current.isWidthAtLeastBreakpoint(
                    WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
                )
            val fixedHeader = shoalHeader || notesPage
            // IchthysOS: the header's tile-page-only parts (the Shoal ringer pill) fade out while
            // the pager swipes to the notes page; the pager host attaches its state here.
            val notesProgress = remember { IchthysNotesPagerProgress() }
            CompositionLocalProvider(
                LocalIchthysNotesPagerProgress provides notesProgress.takeIf { notesPage }
            ) {
                if (shoalHeader) {
                    ExpandedShadeHeader(viewModel = headerViewModel)
                } else if (notesPage) {
                    if (usesCollapsedHeader) {
                        CollapsedShadeHeader(viewModel = headerViewModel, isSplitShade = false)
                    } else {
                        ExpandedShadeHeader(
                            viewModel = headerViewModel,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            // IchthysOS "Shoal": the media player is a strip docked between the scrolling part and
            // the footer actions, in the non-scrolling part, instead of a carousel at the end of
            // the scrolling content. In the grid style the host is the plain box that was here.
            ShoalMediaDockHost(
                isDocked = rememberShoalQsEnabled(),
                viewModelFactory = viewModel.qsContainerViewModel.mediaViewModelFactory,
                onCarouselDismissed = viewModel.qsContainerViewModel::onMediaSwipeToDismiss,
                modifier = Modifier.fillMaxSize().weight(1f),
                // In line with the tiles (see the paddings of QuickSettingsContent).
                horizontalPadding =
                    shadeHorizontalPadding + dimensionResource(id = R.dimen.qs_horizontal_margin),
            ) {
                // IchthysOS: with the notes page on, the scrolling tile content is the second page
                // of a horizontal pager whose first page is the notes; otherwise it is hosted as
                // it was. The header, media dock and footer are outside the pager either way.
                IchthysQsTilesHost(
                    notesViewModel = notesViewModel,
                    enabled = notesPage,
                    isScrollable = isScrollable,
                    progress = notesProgress,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier =
                            Modifier.disableSwipesWhenScrolling()
                                .verticalScroll(scrollState, enabled = isScrollable)
                                .clipScrollableContainer(Orientation.Horizontal)
                                .fillMaxWidth()
                                .wrapContentHeight(unbounded = true)
                                .align(Alignment.TopCenter)
                                .sysuiResTag("expanded_qs_scroll_view")
                    ) {
                        // IchthysOS "Shoal": the header is above this scrolling column (see the
                        // Column that holds it); only the stock headers are drawn here.
                        if (!fixedHeader) {
                            if (usesCollapsedHeader) {
                                CollapsedShadeHeader(
                                    viewModel = headerViewModel,
                                    isSplitShade = false,
                                )
                            } else {
                                ExpandedShadeHeader(
                                    viewModel = headerViewModel,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        QuickSettingsContent(
                            viewModel.qsContainerViewModel,
                            mediaInRow,
                            Modifier.padding(horizontal = shadeHorizontalPadding),
                        )
                    }
                }
            }

            FooterActionsWithAnimatedVisibility(
                viewModel = footerActionsViewModel,
                isCustomizing = false,
                customizingAnimationDuration = 0,
                modifier =
                    Modifier.align(Alignment.CenterHorizontally)
                        .sysuiResTag("qs_footer_actions")
                        .padding(horizontal = shadeHorizontalPadding),
            )
        }
    }
}

/**
 * IchthysOS: hosts the scrolling tile content either directly (notes page off) or as the tiles
 * page of [IchthysQsNotesPagerHost].
 */
@Composable
private fun ContentScope.IchthysQsTilesHost(
    notesViewModel: IchthysNotesViewModel,
    enabled: Boolean,
    isScrollable: Boolean,
    progress: IchthysNotesPagerProgress,
    modifier: Modifier = Modifier,
    tiles: @Composable BoxScope.() -> Unit,
) {
    if (enabled) {
        IchthysQsNotesPagerHost(
            viewModel = notesViewModel,
            isScrollable = isScrollable,
            modifier = modifier,
            progress = progress,
            tiles = tiles,
        )
    } else {
        Box(modifier = modifier, content = tiles)
    }
}
