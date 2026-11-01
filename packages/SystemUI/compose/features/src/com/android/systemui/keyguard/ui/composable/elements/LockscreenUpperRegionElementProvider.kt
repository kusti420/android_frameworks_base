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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.android.settingslib.ichthys.clock.LockNotifications
import com.android.systemui.keyguard.data.repository.IchthysLockNotifRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.viewinterop.AndroidView
import androidx.window.core.layout.WindowSizeClass
import com.android.compose.animation.Easings
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementContentScope
import com.android.compose.animation.scene.PropertyTransformationBuilder
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.IchthysDepthRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.ui.view.KeyguardCustomClockView
import com.android.systemui.keyguard.ui.viewmodel.LockscreenUpperRegionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenUpperRegionViewModel.Decision
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.KeyguardBlueprintLog
import com.android.systemui.plugins.keyguard.ui.composable.elements.BaseLockscreenElement.ElementSource
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Clock
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.MediaCarousel
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Notifications
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Region
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys.Smartspace
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenMovableParentKeys.UpperRegion.NarrowLayout as NarrowScenes
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenMovableParentKeys.UpperRegion.WideLayout as WideScenes
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope.Companion.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope.Companion.NestedScenes
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.settingslib.ichthys.clock.LockWeather
import com.android.settingslib.ichthys.clock.LockWeatherView
import com.android.settingslib.ichthys.clock.SubjectOutline
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject
import kotlin.math.roundToInt

@SysUISingleton
/** Provides a combined element for all lockscreen ui above the lock icon. */
class LockscreenUpperRegionElementProvider
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    @KeyguardBlueprintLog private val blueprintLog: LogBuffer,
    private val viewModelFactory: LockscreenUpperRegionViewModel.Factory,
    private val depthRepository: IchthysDepthRepository,
    private val keyguardInteractor: KeyguardInteractor,
    // IchthysOS Quiet dock (tracker#58): how notifications sit on the lock screen, and the sheet.
    private val notifRepository: IchthysLockNotifRepository,
    private val dockProvider: IchthysNotificationDockProvider,
) : LockscreenElementProvider {
    private val logger = Logger(blueprintLog, "LockscreenUpperRegionElementProvider")
    override val elements: List<LockscreenElement> by lazy { listOf(UpperRegionElement()) }

    private inner class UpperRegionElement : LockscreenElement {
        override val key = Region.Upper
        override val context = this@LockscreenUpperRegionElementProvider.context
        override val source = ElementSource.STANDARD

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            val viewModel = rememberViewModel("LockscreenUpperRegion") { viewModelFactory.create() }
            val layoutType = logDecision("LayoutType") { getLayoutDecision(viewModel.shadeMode) }
            val layout =
                remember(viewModel, layoutType) {
                    when (layoutType) {
                        LayoutType.WIDE -> WideLayout(viewModel)
                        LayoutType.NARROW -> NarrowLayout(viewModel)
                    }
                }

            with(layout) { Layout() }
        }
    }

    abstract inner class RegionLayout(val viewModel: LockscreenUpperRegionViewModel) {
        @Composable abstract fun LockscreenScope<ContentScope>.Layout(modifier: Modifier = Modifier)

        @Composable
        protected fun LockscreenScope<ContentScope>.Notifications(
            aodAlignment: Alignment,
            modifier: Modifier = Modifier,
        ) {
            // IchthysOS Quiet dock (tracker#58): at rest the stack of cards shows only in the
            // cards mode; in the dock and glance modes it is the dock's sheet (sheetOpen, over the
            // clock). The dock draws its own AOD icon row, so the stock AOD shelf is the cards
            // mode's. In the cards mode the stack starts at the owner's anchor (never above the
            // clock, a clear gap under it when automatic): "notifications are too high up".
            val notif by notifRepository.settings.collectAsStateWithLifecycle()
            val sheetOpen by notifRepository.sheetOpen.collectAsStateWithLifecycle()
            Box(modifier = Modifier.fillMaxSize().then(modifier)) {
                if (notif.cards) AODNotifications(Modifier.align(aodAlignment))
                // Make the Notification section overlap with the AOD icons, to avoid jumps while
                // animating them in.
                AnimatedVisibility(viewModel.isNotificationStackActive && (notif.cards || sheetOpen)) {
                    LockscreenElement(
                        Notifications.Stack,
                        modifier =
                            Modifier.burnInAware(isClock = false)
                                .then(
                                    if (notif.cards) Modifier.ichthysCardsAnchor(notif.anchorPercent)
                                    else Modifier
                                ),
                    )
                }
            }
        }

        /**
         * IchthysOS Quiet dock: [clock] with the dock's sheet over it. The sheet (a header and the
         * notification stack) slides up over the clock, which blurs and dims under it ([blur] 0..1,
         * read by the clock at draw time); a swipe down on the header, a tap on the dock, dozing or
         * any scene change puts it away (the repository's sheetOpen). Cards mode has no sheet.
         */
        @Composable
        protected fun LockscreenScope<ContentScope>.ClockWithSheet(
            modifier: Modifier = Modifier,
            clock: @Composable (blur: () -> Float) -> Unit,
        ) {
            val notif by notifRepository.settings.collectAsStateWithLifecycle()
            val sheetOpen by notifRepository.sheetOpen.collectAsStateWithLifecycle()
            val shown = sheetOpen && !notif.cards
            val blur by animateFloatAsState(if (shown) 1f else 0f, tween(SHEET_MS), label = "ichthysSheetBlur")
            Box(modifier.fillMaxSize()) {
                clock { blur }
                // IchthysOS: only the header slides and fades. The stack's placeholder stands
                // still where the sheet puts it: the NSSL is a view outside this layer (no fade
                // reaches it) that follows the placeholder's bounds, so inside the slide it rode
                // up the screen with the animation, in front of everything, before the cards were
                // counted. The header keeps its height while it animates (a slide, no size
                // change), so the stack never moves under it.
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(SHEET_TOP_DP.dp))
                    AnimatedVisibility(
                        shown,
                        enter = slideInVertically(tween(SHEET_MS, easing = Easings.Emphasized)) { it } +
                            fadeIn(tween(SHEET_MS)),
                        exit = slideOutVertically(tween(SHEET_MS, easing = Easings.Emphasized)) { it } +
                            fadeOut(tween(SHEET_MS * 2 / 3)),
                    ) {
                        dockProvider.SheetHeader(onClose = { notifRepository.setSheetOpen(false) })
                    }
                    if (shown) Notifications(aodAlignment = Alignment.TopStart)
                }
            }
        }

        /**
         * IchthysOS polish: the clock, the media player and the notifications in one budget (the
         * upper region's height, which the root ends above the dock and the sensor's keep-out).
         * The media takes its own height first, [LockNotifications.MEDIA_GAP_DP] under the clock;
         * the clock gets what is left above it (the Depth face scales down to fit, see
         * [DepthClock]); the notifications the rest. The media player used to run on past the
         * region's bottom under the shortcuts ("cut off from the bottom").
         */
        @Composable
        protected fun UpperStack(
            clock: @Composable () -> Unit,
            media: @Composable () -> Unit,
            notifications: @Composable () -> Unit,
        ) {
            val gapPx = with(LocalDensity.current) { LockNotifications.MEDIA_GAP_DP.dp.roundToPx() }
            Layout(
                content = {
                    Box { clock() }
                    Box { media() }
                    Box { notifications() }
                }
            ) { measurables, constraints ->
                val maxH = constraints.maxHeight.coerceAtLeast(0)
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val mediaPlaceable =
                    measurables[1].measure(loose.copy(maxHeight = (maxH * 0.55f).toInt().coerceAtLeast(0)))
                val mediaH = mediaPlaceable.height
                val mediaGap = if (mediaH > 0) gapPx else 0
                val clockPlaceable =
                    measurables[0].measure(loose.copy(maxHeight = (maxH - mediaH - mediaGap).coerceAtLeast(0)))
                val used = clockPlaceable.height + mediaGap + mediaH
                val notifPlaceable =
                    measurables[2].measure(loose.copy(maxHeight = (maxH - used).coerceAtLeast(0)))
                layout(constraints.maxWidth, maxH) {
                    clockPlaceable.placeRelative(0, 0)
                    mediaPlaceable.placeRelative(0, clockPlaceable.height + mediaGap)
                    notifPlaceable.placeRelative(0, used)
                }
            }
        }

        @Composable
        protected fun LockscreenScope<ContentScope>.MediaCarousel(modifier: Modifier = Modifier) {
            val bottomPadding =
                dimensionResource(R.dimen.notification_section_divider_height_lockscreen)
            val notificationWidth =
                dimensionResource(R.dimen.shade_panel_width) -
                    (dimensionResource(R.dimen.overlay_qs_layout_horizontal_padding) * 2)
            val widthModifier =
                if (viewModel.shadeMode == ShadeMode.Dual) {
                    Modifier.width(notificationWidth)
                } else {
                    Modifier.fillMaxWidth()
                }

            LockscreenElement(
                MediaCarousel,
                modifier =
                    Modifier.then(widthModifier)
                        .padding(bottom = bottomPadding)
                        .then(modifier)
                        // IchthysOS: the player is chrome, not part of the depth reveal: it goes
                        // at the very start of every unlock (it sits in the nested clock scenes,
                        // where the outer transition's fade(MediaCarousel) does not reach).
                        .ichthysUnlockChrome(depthRepository),
            )
        }

        @Composable
        protected fun LockscreenScope<ContentScope>.AODNotifications(
            modifier: Modifier = Modifier
        ) {
            Column(modifier) {
                LockscreenElement(Notifications.AOD.Promoted, Modifier.padding(bottom = 4.dp))
                LockscreenElement(Notifications.AOD.IconShelf)
            }
        }

        protected fun TransitionBuilder.configureClockCenteringTransition() {
            val duration =
                if (viewModel.shouldSkipTransition) 1 else CLOCK_CENTERING_DURATION_MILLIS
            spec = tween(duration, easing = Easings.Emphasized)
        }

        protected fun TransitionBuilder.configureClockSwitchTransition(
            enter: PropertyTransformationBuilder.() -> Unit,
            exit: PropertyTransformationBuilder.() -> Unit,
        ) {
            val duration = if (viewModel.shouldSkipTransition) 1 else 300
            spec = tween(duration, easing = Easings.Emphasized)

            // Since Smartspace cards are guaranteed to be shared between the small and large clock
            // regions, it's convenient to anchor the movement of the small clock elements to it.
            anchoredTranslate(Clock.Small, anchor = Smartspace.Cards)
            anchoredTranslate(Smartspace.DWA.SmallClock.Row, anchor = Smartspace.Cards)
            anchoredTranslate(Smartspace.DWA.SmallClock.Column, anchor = Smartspace.Cards)

            if (!viewModel.shouldSkipTransition) {
                timestampRange(endMillis = 133) { exit() }
                timestampRange(startMillis = 133, endMillis = 300) { enter() }
            }
        }

        protected fun PropertyTransformationBuilder.fadeLargeClock() {
            fade(Clock.Large)
            fade(Smartspace.DWA.LargeClock.Above)
            fade(Smartspace.DWA.LargeClock.Below)
        }

        protected fun PropertyTransformationBuilder.fadeSmallClock() {
            fade(Clock.Small)
            fade(Smartspace.DWA.SmallClock.Row)
            fade(Smartspace.DWA.SmallClock.Column)
        }
    }

    /**
     * The Ichthys custom clock, with the depth lock screen's subject cut-out over it
     * (tracker#58; nothing is drawn over it while depth is off).
     */
    @Composable
    private fun DepthClock(blur: () -> Float = { 0f }) {
        val dozeAmount by keyguardInteractor.dozeAmount.collectAsStateWithLifecycle(0f)
        // IchthysOS polish: the face scales down (about its top centre) when its budget is smaller
        // than its natural height (the media player under it, UpperStack), instead of running on
        // under the shortcuts. Set in layout, read in draw. The subject's cut-out draws in window
        // coordinates and must not scale with it: it takes the inverse about the same pivot.
        val fit = remember { mutableFloatStateOf(1f) }
        // IchthysOS: back on the stock clock, no stale rectangle may keep taking held touches.
        // Only this clock's own: in a large/small clock switch the other one may own it by now.
        val placed = remember { arrayOf(androidx.compose.ui.geometry.Rect.Zero) }
        DisposableEffect(Unit) {
            onDispose {
                if (depthRepository.clockBounds == placed[0]) {
                    depthRepository.clockBounds = androidx.compose.ui.geometry.Rect.Zero
                }
            }
        }
        // IchthysOS lock screen weather (LockWeatherView, SettingsLib): under the clock or in a
        // top corner, in front of the subject's cut-out (the Depth face draws the date-line place
        // itself). Each view takes no height unless the weather is placed in its slot.
        var clockHeight by remember { mutableIntStateOf(0) }
        var underShift by remember { mutableIntStateOf(0) }
        var underHeight by remember { mutableIntStateOf(0) }
        val weatherDozing = dozeAmount > 0.5f
        // IchthysOS polish: this column ends where the notifications start. That is under the
        // clock as you see it (scaled, Shape-fitted, with the weather line), not under its
        // unscaled box; and with a subject in front (depth on, a mask) never higher than under
        // the subject's face (SubjectOutline.faceSafe, the same line the editor's preview uses).
        // Changes ease in (a spring), so notifications never jump.
        val outline = rememberSubjectOutline()
        var columnTop by remember { mutableFloatStateOf(Float.NaN) }
        val visibleBottom = clockHeight + underShift + underHeight
        val faceSafe = outline?.faceSafe ?: 0f
        val floor =
            if (outline == null || faceSafe <= 0f || columnTop.isNaN()) 0
            else (faceSafe * outline.height - columnTop).roundToInt()
        val target = maxOf(visibleBottom, floor).toFloat()
        val reserve = remember { Animatable(-1f) }
        // Placed once before anything eases: the first frame shows the line where it belongs.
        val measured = clockHeight > 0 && !columnTop.isNaN()
        LaunchedEffect(target, measured) {
            if (!measured) return@LaunchedEffect
            if (reserve.value < 0f) {
                reserve.snapTo(target)
            } else {
                reserve.animateTo(
                    target,
                    spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .onGloballyPositioned {
                    // Coarse: transitions and nudges don't move the line.
                    val y = it.positionInWindow().y
                    if (columnTop.isNaN() || kotlin.math.abs(y - columnTop) >= 16f) columnTop = y
                }
                .layout { measurable, constraints ->
                    // Measured at its natural height; a smaller budget scales it (fit).
                    val placeable =
                        measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    val r = reserve.value
                    val natural = if (r < 0f) placeable.height else r.roundToInt()
                    val h = natural.coerceIn(constraints.minHeight, constraints.maxHeight)
                    fit.floatValue = if (natural > 0 && h < natural) h.toFloat() / natural else 1f
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    val f = fit.floatValue
                    scaleX = f
                    scaleY = f
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .onGloballyPositioned {
                        placed[0] = it.boundsInWindow()
                        depthRepository.clockBounds = placed[0]
                    }
                    .onSizeChanged { clockHeight = it.height }
                    .ichthysDepthPlane(depthRepository, IchthysDepthPlane.MIDDLE)
            ) {
                AndroidView(
                    factory = { KeyguardCustomClockView(it) },
                    // IchthysOS polish: the Depth face cross-fades into its AOD look with this.
                    update = { it.setDozeAmount(dozeAmount) },
                    // IchthysOS Quiet dock: blurred and dimmed under the open sheet.
                    modifier = Modifier.fillMaxWidth().ichthysSheetBlur(blur),
                )
                IchthysDepthCutout(
                    depthRepository,
                    dozeAmount,
                    Modifier.matchParentSize()
                        .graphicsLayer {
                            // The inverse of the fit, about the same pivot (this box starts at
                            // the column's top and spans its width), so the window-anchored
                            // picture stays exactly on the backdrop.
                            val f = fit.floatValue
                            if (f > 0f && f != 1f) {
                                scaleX = 1f / f
                                scaleY = 1f / f
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            }
                        }
                        .ichthysDepthPlane(depthRepository, IchthysDepthPlane.FRONT),
                )
                AndroidView(
                    factory = {
                        LockWeatherView(it).apply {
                            slot = LockWeatherView.SLOT_TOP
                            followClock = true
                        }
                    },
                    update = {
                        it.dozing = weatherDozing
                        it.dozeAmount = dozeAmount
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                            .align(Alignment.TopStart)
                            .padding(top = WEATHER_TOP_DP.dp)
                            .ichthysSheetBlur(blur)
                            .graphicsLayer { alpha = 1f - WEATHER_DOZE_DIM * dozeAmount },
                )
            }
            AndroidView(
                factory = {
                    LockWeatherView(it).apply {
                        slot = LockWeather.POS_UNDER
                        followClock = true
                        onShift = { s -> underShift = s }
                    }
                },
                update = {
                    it.clockHeight = clockHeight
                    it.dozing = weatherDozing
                    it.dozeAmount = dozeAmount
                },
                // The face is scaled about its top: the line follows its scaled bottom (or, on the
                // Depth face, the place the face gives it: DepthFaceAnchor).
                modifier =
                    Modifier.fillMaxWidth()
                        .onSizeChanged { underHeight = it.height }
                        .offset { IntOffset(0, underShift) }
                        .ichthysDepthPlane(depthRepository, IchthysDepthPlane.MIDDLE)
                        .ichthysSheetBlur(blur)
                        .graphicsLayer { alpha = 1f - WEATHER_DOZE_DIM * dozeAmount },
            )
        }
    }

    /** IchthysOS polish: [SubjectOutline.current] as state (SystemUI publishes it per mask). */
    @Composable
    private fun rememberSubjectOutline(): SubjectOutline.Profile? {
        var profile by remember { mutableStateOf(SubjectOutline.current) }
        DisposableEffect(Unit) {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val listener: () -> Unit = { main.post { profile = SubjectOutline.current } }
            SubjectOutline.addListener(listener)
            profile = SubjectOutline.current
            onDispose { SubjectOutline.removeListener(listener) }
        }
        return profile
    }

    /** The Narrow Layouts are intended for phones */
    inner class NarrowLayout(viewModel: LockscreenUpperRegionViewModel) : RegionLayout(viewModel) {
        @Composable
        override fun LockscreenScope<ContentScope>.Layout(modifier: Modifier) {
            // IchthysOS Quiet dock: notifications shrink the clock only in the cards mode; the
            // dock keeps the large clock (its sheet opens over it).
            val notif by notifRepository.settings.collectAsStateWithLifecycle()
            val clockSize =
                logDecision("NarrowLayout: ClockSize") {
                    viewModel.evaluateClockSize {
                        when {
                            notif.cards && viewModel.isNotificationStackActive ->
                                Decision(ClockSize.SMALL, "isNotificationStackActive")
                            viewModel.isMediaVisible -> Decision(ClockSize.SMALL, "isMediaVisible")
                            else -> Decision(ClockSize.LARGE, "Default Case")
                        }
                    }
                }

            val narrowPadding =
                dimensionResource(R.dimen.notification_side_paddings_single) +
                    dimensionResource(R.dimen.notification_panel_margin_horizontal)

            NestedScenes(
                sceneKey =
                    when (clockSize) {
                        ClockSize.LARGE -> NarrowScenes.LargeClock
                        ClockSize.SMALL -> NarrowScenes.SmallClock
                    },
                transitions = {
                    from(from = NarrowScenes.SmallClock, to = NarrowScenes.LargeClock) {
                        configureClockSwitchTransition(
                            enter = { fadeLargeClock() },
                            exit = { fadeSmallClock() },
                        )
                    }
                    from(from = NarrowScenes.LargeClock, to = NarrowScenes.SmallClock) {
                        configureClockSwitchTransition(
                            enter = { fadeSmallClock() },
                            exit = { fadeLargeClock() },
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = narrowPadding).then(modifier),
                debugName = "NarrowLayout - Clocks",
            ) {
                scene(NarrowScenes.LargeClock) {
                    // IchthysOS Quiet dock: the dock's sheet opens over the large clock.
                    ClockWithSheet { blur ->
                        if (isCustomLockClockActive(LocalContext.current)) {
                            DepthClock(blur)
                        } else {
                            Box(Modifier.fillMaxWidth().ichthysSheetBlur(blur)) {
                                LockscreenElement(Region.Clock.Large)
                            }
                        }
                    }
                }
                scene(NarrowScenes.SmallClock) {
                    // IchthysOS: render the selected crDroid custom clock in the clock slot so it
                    // reserves its own height -- the notifications then flow below it, adaptive to
                    // ANY clock style. Falls back to the stock clock for default. IchthysOS polish:
                    // clock, media and notifications share one budget (UpperStack), so the media
                    // player and the stack always end above the dock and the sensor's keep-out.
                    val custom = isCustomLockClockActive(LocalContext.current)
                    ClockWithSheet { blur ->
                        UpperStack(
                            clock = {
                                if (custom) DepthClock(blur)
                                else Box(Modifier.fillMaxWidth().ichthysSheetBlur(blur)) {
                                    LockscreenElement(Region.Clock.Small)
                                }
                            },
                            media = { MediaCarousel() },
                            // Cards under the media; in the dock modes the stack is the sheet's
                            // (one Notifications.Stack element at a time).
                            notifications = {
                                if (notif.cards) Notifications(aodAlignment = Alignment.TopStart)
                            },
                        )
                    }
                }
            }
        }
    }

    /** The wide layouts are intended for tablets / foldables */
    inner class WideLayout(viewModel: LockscreenUpperRegionViewModel) : RegionLayout(viewModel) {
        @Composable
        override fun LockscreenScope<ContentScope>.Layout(modifier: Modifier) {
            val clockSize =
                logDecision("WideLayout: ClockSize") {
                    viewModel.evaluateClockSize {
                        when {
                            viewModel.shadeMode == ShadeMode.Dual ->
                                Decision(ClockSize.LARGE, "shadeMode == ShadeMode.Dual")
                            viewModel.isMediaVisible -> Decision(ClockSize.SMALL, "isMediaVisible")
                            else -> Decision(ClockSize.LARGE, "Default Case")
                        }
                    }
                }

            val isTwoColumn =
                logDecision("WideLayout: TwoColumn") {
                    when {
                        clockSize == ClockSize.SMALL -> Decision(true, "clockSize == SMALL")
                        !viewModel.isDozing &&
                            (viewModel.isNotificationStackActive || viewModel.isMediaVisible) -> {
                            Decision(
                                true,
                                "!isDozing && (isNotificationStackActive || isMediaVisible)",
                            )
                        }
                        viewModel.isDozing && viewModel.isHeadsUpNotificationActive ->
                            Decision(true, "isDozing && isHeadsUpNotificationActive")
                        viewModel.isDozing && viewModel.isPromotedNotificationActive ->
                            Decision(true, "isDozing && isPromotedNotificationActive")
                        else -> Decision(false, "Default Case")
                    }
                }

            var widePadding = dimensionResource(R.dimen.upper_region_wide_horizontal_padding)
            if (viewModel.shadeMode == ShadeMode.Dual) {
                widePadding =
                    max(
                        widePadding,
                        WindowInsets.safeContent
                            .asPaddingValues()
                            .calculateStartPadding(LocalLayoutDirection.current),
                    )
            }

            NestedScenes(
                sceneKey =
                    when {
                        !isTwoColumn -> WideScenes.CenteredClock
                        clockSize == ClockSize.LARGE -> WideScenes.TwoColumn.LargeClock
                        else -> WideScenes.TwoColumn.SmallClock
                    },
                transitions = {
                    from(from = WideScenes.CenteredClock, to = WideScenes.TwoColumn.LargeClock) {
                        configureClockCenteringTransition()
                    }
                    from(from = WideScenes.TwoColumn.LargeClock, to = WideScenes.CenteredClock) {
                        configureClockCenteringTransition()
                    }
                    from(from = WideScenes.CenteredClock, to = WideScenes.TwoColumn.SmallClock) {
                        configureClockSwitchTransition(
                            enter = { fadeSmallClock() },
                            exit = { fadeLargeClock() },
                        )
                    }
                    from(from = WideScenes.TwoColumn.SmallClock, to = WideScenes.CenteredClock) {
                        configureClockSwitchTransition(
                            enter = { fadeLargeClock() },
                            exit = { fadeSmallClock() },
                        )
                    }
                    from(
                        from = WideScenes.TwoColumn.LargeClock,
                        to = WideScenes.TwoColumn.SmallClock,
                    ) {
                        configureClockSwitchTransition(
                            enter = { fadeSmallClock() },
                            exit = { fadeLargeClock() },
                        )
                    }
                    from(
                        from = WideScenes.TwoColumn.SmallClock,
                        to = WideScenes.TwoColumn.LargeClock,
                    ) {
                        configureClockSwitchTransition(
                            enter = { fadeLargeClock() },
                            exit = { fadeSmallClock() },
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = widePadding).then(modifier),
                debugName = "WideLayout - Clocks",
            ) {
                scene(WideScenes.CenteredClock) {
                    // Media is unsupported with centered large clock
                    LargeClockCenter_NotifsAlign(
                        notifAlignment =
                            when (viewModel.shadeMode) {
                                ShadeMode.Dual -> {
                                    if (viewModel.useDesktopStatusBar) Alignment.TopEnd
                                    else Alignment.TopStart
                                }
                                ShadeMode.Split -> Alignment.TopEnd
                                else -> {
                                    logger.wtf("WideLayout state is invalid")
                                    Alignment.TopCenter
                                }
                            }
                    )
                }
                scene(WideScenes.TwoColumn.LargeClock) {
                    when (viewModel.shadeMode) {
                        ShadeMode.Dual -> {
                            if (viewModel.useDesktopStatusBar) LargeClockStart_NotifsEnd_MediaEnd()
                            else LargeClockEnd_NotifsStart_MediaStart()
                        }
                        // Media is unsupported with large clock in split shade mode
                        ShadeMode.Split -> LargeClockStart_NotifsEnd()
                        else -> logger.wtf("WideLayout state is invalid")
                    }
                }
                scene(WideScenes.TwoColumn.SmallClock) {
                    when (viewModel.shadeMode) {
                        ShadeMode.Dual -> {
                            if (viewModel.useDesktopStatusBar) SmallClockStart_NotifsEnd_MediaEnd()
                            else SmallClockStart_NotifsStart_MediaStart()
                        }
                        ShadeMode.Split -> SmallClockStart_NotifsEnd_MediaStart()
                        else -> logger.wtf("WideLayout state is invalid")
                    }
                }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.LargeClockCenter_NotifsAlign(
            notifAlignment: Alignment,
            modifier: Modifier = Modifier,
        ) {
            // We overlap the notification stack with large clock region so that large clock is
            // horizontally centered as expected. Since this layout should only be used when all
            // the notifications are on the shelf, these elements won't overlap visually in
            // practice outside of momentarily during certain transitions.
            Box(
                modifier = Modifier.fillMaxSize().then(modifier),
                contentAlignment = Alignment.Center,
            ) {
                LockscreenElement(Region.Clock.Large)
                AODNotifications(Modifier.align(notifAlignment))
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.LargeClockEnd_NotifsStart_MediaStart(
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn {
                    MediaCarousel(Modifier.align(Alignment.Start))
                    Notifications(aodAlignment = Alignment.TopStart)
                }
                EndColumn(useLargeClockPadding = true) { LockscreenElement(Region.Clock.Large) }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.SmallClockStart_NotifsStart_MediaStart(
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn {
                    LockscreenElement(Region.Clock.Small)
                    MediaCarousel(Modifier.align(Alignment.Start))
                    Notifications(aodAlignment = Alignment.TopStart)
                }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.LargeClockStart_NotifsEnd(
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn(useLargeClockPadding = true) { LockscreenElement(Region.Clock.Large) }
                EndColumn { Notifications(aodAlignment = Alignment.TopEnd) }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.LargeClockStart_NotifsEnd_MediaEnd(
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn(useLargeClockPadding = true) { LockscreenElement(Region.Clock.Large) }
                EndColumn {
                    MediaCarousel(Modifier.align(Alignment.End))
                    Notifications(aodAlignment = Alignment.TopEnd)
                }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.SmallClockStart_NotifsEnd_MediaStart(
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn {
                    LockscreenElement(Region.Clock.Small)
                    MediaCarousel(Modifier.align(Alignment.Start))
                }
                EndColumn { Notifications(aodAlignment = Alignment.TopEnd) }
            }
        }

        @Composable
        private fun LockscreenScope<ContentScope>.SmallClockStart_NotifsEnd_MediaEnd(
            modifier: Modifier = Modifier
        ) {
            TwoColumn(modifier) {
                StartColumn { LockscreenElement(Region.Clock.Small) }
                EndColumn {
                    MediaCarousel(Modifier.align(Alignment.End))
                    Notifications(aodAlignment = Alignment.TopEnd)
                }
            }
        }

        @Immutable
        inner class TwoColumnScope(private val rowScope: RowScope, private val columnPadding: Dp) {
            @Composable
            fun StartColumn(
                useLargeClockPadding: Boolean = false,
                modifier: Modifier = Modifier,
                content: @Composable ColumnScope.() -> Unit = {},
            ) {
                var paddingModifier =
                    if (useLargeClockPadding) Modifier.padding(end = columnPadding)
                    else Modifier.padding(horizontal = columnPadding)
                Column(
                    paddingModifier
                        .fillMaxWidth(0.5f)
                        .fillMaxHeight()
                        .graphicsLayer { translationX = viewModel.unfoldTranslations.start }
                        .then(modifier)
                ) {
                    content()
                }
            }

            @Composable
            fun EndColumn(
                useLargeClockPadding: Boolean = false,
                modifier: Modifier = Modifier,
                content: @Composable ColumnScope.() -> Unit = {},
            ) {
                var paddingModifier =
                    if (useLargeClockPadding) Modifier.padding(start = columnPadding)
                    else Modifier.padding(horizontal = columnPadding)
                Column(
                    paddingModifier
                        .fillMaxWidth(1f)
                        .fillMaxHeight()
                        .graphicsLayer { translationX = viewModel.unfoldTranslations.end }
                        .then(modifier)
                ) {
                    content()
                }
            }
        }

        @Composable
        private fun TwoColumn(
            modifier: Modifier = Modifier,
            content: @Composable TwoColumnScope.() -> Unit = {},
        ) {
            Row(modifier) {
                val padding = dimensionResource(R.dimen.upper_region_wide_column_horizontal_padding)
                with(TwoColumnScope(this@Row, padding)) { content() }
            }
        }
    }

    @Composable
    fun <T> logDecision(
        @CompileTimeConstant prefix: String,
        func: @Composable () -> Decision<T>,
    ): T {
        val decision = func()
        logger.i({ "$prefix: decision=$str1; reason=$str2" }) {
            str1 = "${decision.choice}"
            str2 = decision.reason
        }
        return decision.choice
    }

    companion object {
        const val CLOCK_CENTERING_DURATION_MILLIS = 1000

        /** IchthysOS: the top-corner weather's distance under the status bar. */
        const val WEATHER_TOP_DP = 10
        /** IchthysOS: how much dimmer the weather is on AOD (eased with the doze amount). */
        const val WEATHER_DOZE_DIM = 0.35f
        /** IchthysOS Quiet dock: the sheet's slide, and its header's distance under the status bar. */
        const val SHEET_MS = 320
        const val SHEET_TOP_DP = 8
        /** IchthysOS Quiet dock: how far the clock blurs under the open sheet. */
        const val SHEET_BLUR_DP = 18f

        enum class LayoutType {
            WIDE,
            NARROW,
        }

        @Composable
        fun getLayoutDecision(shadeMode: ShadeMode): Decision<LayoutType> {
            return when (shadeMode) {
                ShadeMode.Single -> Decision(LayoutType.NARROW, "Single Shade")
                ShadeMode.Split -> Decision(LayoutType.WIDE, "Split Shade")
                ShadeMode.Dual -> {
                    with(LocalWindowSizeClass.current) {
                        val isWindowLarge =
                            isAtLeastBreakpoint(
                                WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
                                WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
                            )

                        if (isWindowLarge) {
                            Decision(LayoutType.WIDE, "Dual Shade && SizeClass >= Medium")
                        } else {
                            Decision(LayoutType.NARROW, "Dual Shade && SizeClass < Medium")
                        }
                    }
                }
            }
        }

        @Composable
        fun getLayoutType(shadeMode: ShadeMode): LayoutType {
            return getLayoutDecision(shadeMode).choice
        }
    }
}

/**
 * IchthysOS Quiet dock: the clock (or anything of bounded size) blurred and dimmed by [blur] (0..1,
 * read at draw time) under the open sheet. The layer is the node's own bounded box: never larger.
 */
private fun Modifier.ichthysSheetBlur(blur: () -> Float): Modifier = graphicsLayer {
    val b = blur().coerceIn(0f, 1f)
    if (b > 0.01f) {
        val r = LockscreenUpperRegionElementProvider.SHEET_BLUR_DP * density * b
        renderEffect = BlurEffect(r, r, TileMode.Decal)
        alpha = 1f - 0.45f * b
    } else {
        renderEffect = null
        alpha = 1f
    }
}

/**
 * IchthysOS Quiet dock, cards mode: the stack starts at the owner's anchor ([anchorPercent] of the
 * screen's height from the top; [LockNotifications.ANCHOR_AUTO]: [LockNotifications.CARDS_GAP_DP]
 * under whatever is above it), never above where it is laid out (the clock and its date come
 * first) and never past the room it has (the root ends the region above the keep-out). The node's
 * own window position is read coarsely (nudges and transitions don't move the stack).
 */
@Composable
private fun Modifier.ichthysCardsAnchor(anchorPercent: Int): Modifier {
    val screenH = LocalContext.current.resources.displayMetrics.heightPixels
    var top by remember { mutableFloatStateOf(Float.NaN) }
    return onGloballyPositioned {
            val y = it.positionInWindow().y
            if (top.isNaN() || kotlin.math.abs(y - top) >= 8f) top = y
        }
        .layout { measurable, constraints ->
            val gap = LockNotifications.CARDS_GAP_DP.dp.roundToPx()
            val want =
                if (anchorPercent == LockNotifications.ANCHOR_AUTO || top.isNaN()) gap
                else maxOf(gap / 2, (screenH * anchorPercent / 100f - top).roundToInt())
            val pad = want.coerceIn(0, constraints.maxHeight)
            val placeable =
                measurable.measure(
                    constraints.copy(minHeight = 0, maxHeight = (constraints.maxHeight - pad).coerceAtLeast(0))
                )
            layout(placeable.width, placeable.height + pad) { placeable.place(0, pad) }
        }
}

// IchthysOS: true when a crDroid custom lockscreen clock style is selected (non-default). Public
// Settings.Secure.getInt reads for the current user and stays within the compose module's API. The
// crDroid clock picker restarts SystemUI on apply, so a non-reactive read here is sufficient.
private fun isCustomLockClockActive(context: android.content.Context): Boolean =
    android.provider.Settings.Secure.getInt(
        context.contentResolver,
        "lock_screen_custom_clock_style",
        0,
    ) != 0
