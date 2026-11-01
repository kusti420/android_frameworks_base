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

package com.android.systemui.statusbar.ichthys.indicator.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.util.Log
import android.util.RotationUtils
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.android.app.animation.Interpolators
import com.android.app.displaylib.PerDisplayRepository
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.util.ContrastColorUtil
import com.android.systemui.CoreStartable
import com.android.systemui.ScreenDecorations
import com.android.systemui.SysUICutoutProvider
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent
import com.android.systemui.display.naturalBounds
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.PrivacyType
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.events.PrivacyDotViewController
import com.android.systemui.statusbar.ichthys.indicator.data.IchthysIndicatorSettingsRepository
import com.android.systemui.statusbar.ichthys.indicator.data.IchthysPrivacyRingState
import com.android.systemui.statusbar.ichthys.indicator.data.NotificationDotLogic
import com.android.systemui.statusbar.ichthys.indicator.data.NotificationLightModel
import com.android.systemui.statusbar.ichthys.indicator.data.NotificationLightRepository
import com.android.systemui.statusbar.ichthys.indicator.data.NotificationDotColor
import com.android.systemui.statusbar.ichthys.indicator.data.PrivacyIndicatorStyle
import com.android.systemui.statusbar.ichthys.indicator.domain.NotificationDotInteractor
import com.android.systemui.statusbar.ichthys.indicator.domain.NotificationDotModel
import com.android.systemui.statusbar.layout.StatusBarContentInsetsChangedListener
import com.android.systemui.statusbar.layout.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.phone.domain.interactor.DarkIconInteractor
import com.android.systemui.statusbar.phone.domain.model.DarkState
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/**
 * IchthysOS: draws the notification dot and the privacy ring.
 *
 * Both live in small windows of their own in the screen decoration layer: the window type and
 * flags of the privacy dot / rounded corner overlays ([ScreenDecorations
 * .getWindowLayoutBaseParams]), so they sit above the status bar, the shade, the keyguard and the
 * always-on display, are not clipped by the status bar window (which is what the cutout-ring spike
 * this replaces found for a view inside the bar), take no touches and stay out of screenshots. A
 * window is only attached while its indicator shows.
 *
 * **Notification dot.** A filled circle the size of the privacy dot at the privacy dot's place:
 * right after the status bar content area, vertically centred in it, from
 * [StatusBarContentInsetsProvider] exactly like PrivacyDotViewController places the dot (so it
 * follows rotation, RTL and cutouts). With statusBar.notificationDot.logic = light (the default)
 * it is the notification light of a phone with an LED, on the always-on display: lit exactly while
 * the notification service has the light flashing ([NotificationLightRepository]). With unseen it
 * shows while [NotificationDotInteractor] has an unseen notification to announce and the shade is
 * closed, awake or dozing. On the always-on display it is dimmed and drifts by the burn-in
 * helper's offset on every doze tick.
 *
 * **Privacy indicator.** With statusBar.privacyIndicator.style = cutoutRing the stock corner dot
 * stays hidden and a ring is drawn around the front camera: the configured camera protection path
 * if the device has one, otherwise the live display cutout path (or its bounding rects), in the
 * current rotation. It shows for as long as [PrivacyItemController] lists something in use (the
 * camera, the microphone, and location if those indicators are on), wherever the user is: the
 * shade, the lock screen, a full-screen app. Without a cutout, or with style = dot, the stock dot
 * is used; the notification dot then takes the slot just inside it, which
 * [StatusBarContentInsetsProvider.setReservedEndPadding] keeps free of status bar content.
 *
 * Everything here runs on the main thread.
 */
@SysUISingleton
class IchthysIndicatorsStartable
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Main private val mainHandler: Handler,
    private val windowManager: WindowManager,
    private val displayManager: DisplayManager,
    private val settings: IchthysIndicatorSettingsRepository,
    private val ringState: IchthysPrivacyRingState,
    private val notificationDotInteractor: NotificationDotInteractor,
    private val notificationLightRepository: NotificationLightRepository,
    private val keyguardInteractor: KeyguardInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val darkIconInteractor: DarkIconInteractor,
    private val burnInHelper: BurnInHelperWrapper,
    private val configurationController: ConfigurationController,
    private val contentInsetsProvider: StatusBarContentInsetsProvider,
    private val privacyDotViewController: PrivacyDotViewController,
    private val privacyItemController: PrivacyItemController,
    private val displaySubcomponents: PerDisplayRepository<SystemUIDisplaySubcomponent>,
) : CoreStartable {

    /** Bumped whenever geometry or colours may have changed (rotation, density, theme, insets). */
    private val environmentChanges = MutableStateFlow(0)

    /** Whether the privacy indicator is currently drawn as the cutout ring. */
    private val ringActive = MutableStateFlow(false)

    private val dotView by lazy {
        NotificationDotView(context, IchthysIndicatorTokens.dotDiameter(context), burnInRange() + 1)
    }
    private val ringView by lazy { PrivacyRingView(context) }
    private val dotWindow by lazy { IndicatorWindow(dotView, "IchthysNotificationDot") }
    private val ringWindow by lazy { IndicatorWindow(ringView, "IchthysPrivacyRing") }

    /** Where the dot is on screen (without burn-in drift), for the dark-area tint lookup. */
    private val dotBounds = Rect()
    private var ringGeometry: PrivacyRingGeometry? = null
    private var ringColorByType = false
    private var privacyState = PrivacyIndicatorState()

    /**
     * What is in use right now, straight from the list of privacy items. The ring used to follow
     * the corner dot's state, which the status bar only settles once its chip animation has run:
     * a use that began or ended meanwhile (going to the camera and back) was missed or kept.
     */
    private val privacyInUse = MutableStateFlow(PrivacyIndicatorState())

    // A field on purpose: PrivacyItemController holds its callbacks weakly.
    private val privacyItemsCallback =
        object : PrivacyItemController.Callback {
            override fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>) {
                val types = privacyItems.mapTo(mutableSetOf()) { it.privacyType }
                privacyInUse.value =
                    if (types.isEmpty()) {
                        privacyInUse.value.copy(visible = false)
                    } else {
                        PrivacyIndicatorState(visible = true, types = types)
                    }
            }
        }

    private val sysUICutoutProvider: SysUICutoutProvider?
        get() = displaySubcomponents[Display.DEFAULT_DISPLAY]?.sysUICutoutProvider

    override fun start() {
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration) = onEnvironmentChanged()

                override fun onDensityOrFontScaleChanged() = onEnvironmentChanged()

                override fun onThemeChanged() = onEnvironmentChanged()

                override fun onUiModeChanged() = onEnvironmentChanged()

                override fun onLayoutDirectionChanged(isLayoutRtl: Boolean) =
                    onEnvironmentChanged()
            }
        )
        contentInsetsProvider.addCallback(
            object : StatusBarContentInsetsChangedListener {
                override fun onStatusBarContentInsetsChanged() = onEnvironmentChanged()
            }
        )
        // Rotating by 180 degrees changes neither the configuration nor the insets.
        displayManager.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}

                override fun onDisplayRemoved(displayId: Int) {}

                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) onEnvironmentChanged()
                }
            },
            mainHandler,
        )

        privacyItemController.addCallback(privacyItemsCallback)
        scope.launch { layout.collect { applyLayout(it) } }
        scope.launch { dotUiState.collect { renderDot(it) } }
        scope.launch {
            combine(privacyInUse, ringActive, environmentChanges) {
                    state,
                    _,
                    _ ->
                    state
                }
                .collect {
                    privacyState = it
                    renderRing()
                }
        }
        scope.launch {
            keyguardInteractor.dozeAmount.onStart { emit(0f) }.collect { applyDozeAmount(it) }
        }
        scope.launch {
            combine(
                    keyguardInteractor.isDozing,
                    keyguardInteractor.dozeTimeTick.onStart { emit(0L) },
                ) { dozing, _ ->
                    dozing
                }
                .collect { applyBurnIn(it) }
        }
    }

    private fun onEnvironmentChanged() {
        environmentChanges.value++
    }

    // ---------------------------------------------------------------------------------------------
    // Layout: where things go and which privacy style is in effect

    private data class LayoutSettings(
        val style: PrivacyIndicatorStyle,
        val notificationDotEnabled: Boolean,
        val ringWidthDp: Int,
        val ringColorByType: Boolean,
    )

    private val layout: Flow<LayoutSettings> =
        combine(
            settings.privacyIndicatorStyle,
            settings.notificationDotEnabled,
            settings.privacyRingWidthDp,
            settings.privacyIndicatorColorByType,
            environmentChanges,
        ) { style, dotEnabled, ringWidthDp, colorByType, _ ->
            LayoutSettings(
                style,
                dotEnabled,
                ringWidthDp ?: IchthysIndicatorTokens.PRIVACY_RING_WIDTH_DP,
                colorByType,
            )
        }

    private fun applyLayout(config: LayoutSettings) {
        val displayBounds = windowManager.maximumWindowMetrics.bounds
        val outline =
            if (config.style == PrivacyIndicatorStyle.CUTOUT_RING) cameraOutline() else null
        val geometry =
            outline?.let {
                PrivacyRingGeometry.create(
                    outline = it,
                    gap =
                        IchthysIndicatorTokens.dpToPx(
                            context,
                            IchthysIndicatorTokens.PRIVACY_RING_GAP_DP,
                        ),
                    width = IchthysIndicatorTokens.dpToPx(context, config.ringWidthDp.toFloat()),
                    limits = displayBounds,
                )
            }
        // No cutout to draw around: the stock corner dot is the fallback.
        val ring = geometry != null
        ringGeometry = geometry
        ringColorByType = config.ringColorByType

        // Two corner dots need two slots; with the ring the notification dot has the privacy
        // dot's own.
        val diameter = IchthysIndicatorTokens.dotDiameter(context)
        val reserved =
            if (!ring && config.notificationDotEnabled) {
                diameter +
                    IchthysIndicatorTokens.dpToPxInt(
                        context,
                        IchthysIndicatorTokens.NOTIFICATION_DOT_GAP_DP,
                    )
            } else {
                0
            }
        // Notifies the insets listeners (this class among them) only if the value changed.
        contentInsetsProvider.setReservedEndPadding(reserved)
        privacyDotViewController.setIchthysIndicatorLayout(
            replacedByCutoutRing = ring,
            dotInnerOffset = reserved,
        )

        // The privacy dot's place: straight after the content area (before it in RTL), centred in
        // the area below the status bar's top padding. See PrivacyDotViewController.setCornerSizes.
        val area = contentInsetsProvider.getStatusBarContentAreaForCurrentRotation()
        val paddingTop = contentInsetsProvider.getStatusBarPaddingTop()
        val left = if (configurationController.isLayoutRtl) area.left - diameter else area.right
        val top = area.top + paddingTop + (area.height() - paddingTop - diameter) / 2
        dotBounds.set(left, top, left + diameter, top + diameter)
        val slack = burnInRange() + 1
        dotView.setSize(diameter, slack)
        dotWindow.setBounds(
            Rect(dotBounds).apply {
                inset(-slack, -slack)
                intersect(displayBounds)
            }
        )

        ringActive.value = ring
        ringState.isRingActive = ring
        renderRing()
    }

    /** The outline to draw the ring around, in screen coordinates for the current rotation. */
    private fun cameraOutline(): Path? {
        val cutoutInfo =
            sysUICutoutProvider?.cutoutInfoForCurrentDisplayAndRotation() ?: return null

        // 1. The camera protection path, when the device configures one for this display
        //    (config_frontBuiltInDisplayCutoutProtection): it is the camera itself rather than
        //    the whole cutout. It is given for the natural orientation.
        val protection = cutoutInfo.cameraProtection?.cutoutProtectionPath
        if (protection != null && !protection.isEmpty) {
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val natural = display.naturalBounds
                val toLogical = Matrix()
                RotationUtils.transformPhysicalToLogicalCoordinates(
                    display.rotation,
                    natural.width(),
                    natural.height(),
                    toLogical,
                )
                return Path(protection).apply { transform(toLogical) }
            }
        }

        // 2. The live display cutout's path. The returned instance is shared: copy it.
        val cutoutPath = cutoutInfo.cutout.cutoutPath
        if (cutoutPath != null && !cutoutPath.isEmpty) {
            return Path(cutoutPath)
        }

        // 3. Its bounding rects, as the cutout-ring spike did.
        val rects = cutoutInfo.cutout.boundingRects.filter { !it.isEmpty }
        if (rects.isEmpty()) return null
        return Path().apply { rects.forEach { addOval(RectF(it), Path.Direction.CW) } }
    }

    private fun burnInRange(): Int =
        IchthysIndicatorTokens.dpToPxInt(context, IchthysIndicatorTokens.BURN_IN_OFFSET_DP)

    // ---------------------------------------------------------------------------------------------
    // Notification dot

    private data class DotSettings(
        val enabled: Boolean,
        val logic: NotificationDotLogic,
        val onAod: Boolean,
        val color: NotificationDotColor,
    )

    /** What the dot may announce: by the unseen rule awake and dozing, and the light's state. */
    private data class DotSources(
        val statusBarDot: NotificationDotModel?,
        val aodDot: NotificationDotModel?,
        val light: NotificationLightModel?,
    )

    private data class DeviceState(
        val dozing: Boolean,
        val shadeExpanded: Boolean,
        val statusBarHidden: Boolean,
        val keyguardShowing: Boolean,
    )

    private data class DotUiState(val visible: Boolean, val color: Int)

    /** The status bar is hidden by an immersive app (and not swiped back in). */
    private val isStatusBarHidden: Flow<Boolean> =
        displaySubcomponents[Display.DEFAULT_DISPLAY]?.statusBarModeRepo?.let { modeRepo ->
            combine(modeRepo.isInFullscreenMode, modeRepo.isTransientShown) {
                fullscreen,
                transientShown ->
                fullscreen && !transientShown
            }
        } ?: flowOf(false)

    private val dotUiState: Flow<DotUiState> =
        combine(
                combine(
                    settings.notificationDotEnabled,
                    settings.notificationDotLogic,
                    settings.notificationDotOnAod,
                    settings.notificationDotColor,
                    ::DotSettings,
                ),
                combine(
                    keyguardInteractor.isDozing,
                    shadeInteractor.isAnyExpanded,
                    isStatusBarHidden,
                    keyguardInteractor.isKeyguardShowing,
                    ::DeviceState,
                ),
                combine(
                    notificationDotInteractor.statusBarDot,
                    notificationDotInteractor.aodDot,
                    notificationLightRepository.light,
                    ::DotSources,
                ),
                darkIconInteractor.darkState(Display.DEFAULT_DISPLAY),
                environmentChanges,
            ) { dotSettings, device, sources, darkState, _ ->
                if (dotSettings.logic == NotificationDotLogic.LIGHT) {
                    // The dot is the notification light. That only ever flashes with the screen
                    // off, so the always-on display is the one place it can show; the dot holds
                    // still there instead of flashing, which would keep the phone awake.
                    val light = sources.light
                    DotUiState(
                        visible =
                            dotSettings.enabled &&
                                dotSettings.onAod &&
                                light != null &&
                                device.dozing,
                        color = dotColor(dotSettings.color, light?.argb, device, darkState),
                    )
                } else {
                    // Dozing: what the always-on display may show. Awake: what the status bar
                    // may show, and only while there is a status bar.
                    val model =
                        when {
                            !dotSettings.enabled -> null
                            device.dozing -> sources.aodDot.takeIf { dotSettings.onAod }
                            device.statusBarHidden -> null
                            // IchthysOS: the lock screen shows its notifications itself (the
                            // quiet dock, cards or glance counts), so no dot over its status bar.
                            device.keyguardShowing -> null
                            else -> sources.statusBarDot
                        }
                    DotUiState(
                        visible = model != null && !device.shadeExpanded,
                        color = dotColor(dotSettings.color, model?.appColor, device, darkState),
                    )
                }
            }
            .distinctUntilChanged()

    private fun dotColor(
        source: NotificationDotColor,
        appColor: Int?,
        device: DeviceState,
        darkState: DarkState,
    ): Int =
        when (source) {
            NotificationDotColor.ACCENT -> IchthysIndicatorTokens.accent(context)
            NotificationDotColor.WHITE ->
                // The home status bar's icon tint; the lock screen and the always-on display are
                // always light-on-dark.
                if (device.dozing || device.keyguardShowing) {
                    Color.WHITE
                } else if (DarkIconDispatcher.isInAreas(darkState.areas, dotBounds)) {
                    darkState.tint
                } else {
                    DarkIconDispatcher.DEFAULT_ICON_TINT
                }
            NotificationDotColor.APP ->
                appColor?.let { ContrastColorUtil.ensureTextContrastOnBlack(it) }
                    ?: IchthysIndicatorTokens.accent(context)
        }

    private fun renderDot(state: DotUiState) {
        if (state.visible) {
            dotView.color = state.color
            dotWindow.show()
        } else {
            dotWindow.hide()
        }
    }

    /** Dims both indicators towards the always-on display's level as the device dozes. */
    private fun applyDozeAmount(dozeAmount: Float) {
        val amount = dozeAmount.coerceIn(0f, 1f)
        val dim = 1f + (IchthysIndicatorTokens.AOD_ALPHA - 1f) * amount
        dotView.dim = dim
        ringView.dim = dim
    }

    /**
     * Burn-in protection: the same helper and the same axis treatment as the always-on display
     * clock (BurnInInteractor.burnIn), with a much smaller amplitude. Called on every doze tick.
     */
    private fun applyBurnIn(dozing: Boolean) {
        if (!dozing) {
            dotView.setOffset(0, 0)
            return
        }
        val range = burnInRange()
        dotView.setOffset(
            burnInHelper.burnInOffset(range, /* xAxis= */ true),
            burnInHelper.burnInOffset(range, /* xAxis= */ false) * 2 - range,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Privacy ring

    private fun renderRing() {
        val geometry = ringGeometry
        if (geometry == null || !ringActive.value || !privacyState.visible) {
            // Keeps what it was drawing while it fades out.
            ringWindow.hide()
            return
        }
        ringView.setRing(
            geometry.segments(ringColors(privacyState.types)),
            geometry.bounds.left,
            geometry.bounds.top,
        )
        ringWindow.setBounds(geometry.bounds)
        ringWindow.show()
    }

    private fun ringColors(types: Set<PrivacyType>): List<Int> =
        if (ringColorByType && types.isNotEmpty()) {
            types.sorted().map { IchthysIndicatorTokens.privacyTypeColor(context, it) }.distinct()
        } else {
            listOf(IchthysIndicatorTokens.privacyColor(context, types))
        }

    // ---------------------------------------------------------------------------------------------
    // Windows

    /** One indicator's window: attached while the indicator shows, faded in and out. */
    private inner class IndicatorWindow(private val view: View, windowTitle: String) {
        private val params: WindowManager.LayoutParams =
            ScreenDecorations.getWindowLayoutBaseParams(EXCLUDE_FROM_SCREENSHOTS).apply {
                // The base params leave touchability to the views; these never take touches.
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                gravity = Gravity.TOP or Gravity.LEFT
                width = 0
                height = 0
                setTitle(windowTitle)
            }
        private var attached = false
        /** Fading in or fully visible. */
        private var shown = false
        /** Asked to show; differs from [shown] only until the window has a size. */
        private var wanted = false

        /** Screen position and size of the window. */
        fun setBounds(bounds: Rect) {
            if (
                params.x == bounds.left &&
                    params.y == bounds.top &&
                    params.width == bounds.width() &&
                    params.height == bounds.height()
            ) {
                return
            }
            params.x = bounds.left
            params.y = bounds.top
            params.width = bounds.width()
            params.height = bounds.height()
            if (attached) {
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to move ${params.title}", e)
                }
            }
            if (wanted) show()
        }

        fun show() {
            wanted = true
            if (shown) return
            if (params.width <= 0 || params.height <= 0) return
            if (!attached) {
                view.alpha = 0f
                try {
                    windowManager.addView(view, params)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to add ${params.title}", e)
                    return
                }
                attached = true
            }
            shown = true
            view
                .animate()
                .alpha(1f)
                .setDuration(IchthysIndicatorTokens.FADE_DURATION_MS)
                .setInterpolator(Interpolators.ALPHA_IN)
                .withEndAction(null)
                .start()
        }

        fun hide() {
            wanted = false
            if (!shown) return
            shown = false
            view
                .animate()
                .alpha(0f)
                .setDuration(IchthysIndicatorTokens.FADE_DURATION_MS)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction { detachIfHidden() }
                .start()
        }

        private fun detachIfHidden() {
            if (shown || !attached) return
            attached = false
            try {
                windowManager.removeViewImmediate(view)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed to remove ${params.title}", e)
            }
        }
    }

    private companion object {
        const val TAG = "IchthysIndicators"

        /**
         * Keeps the indicators out of screenshots and screen recordings, like the stock privacy
         * dot (PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY: the window is then composed above
         * everything, outside the display's window hierarchy). False gives a plain trusted
         * overlay of the same type, which is what the cutout-ring spike ran with.
         */
        const val EXCLUDE_FROM_SCREENSHOTS = true
    }
}

/** Whether the privacy ring shows, and for what. */
private data class PrivacyIndicatorState(
    val visible: Boolean = false,
    /** What is in use. Not cleared when the ring hides, so it can fade out unchanged. */
    val types: Set<PrivacyType> = emptySet(),
)
