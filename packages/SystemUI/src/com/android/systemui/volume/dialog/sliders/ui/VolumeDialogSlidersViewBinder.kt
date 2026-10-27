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

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.UserHandle
import android.provider.Settings
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.LayoutRes
import androidx.compose.ui.util.fastForEachIndexed
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.res.R
import com.android.systemui.util.children
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderComponent
import com.android.systemui.volume.dialog.sliders.domain.OneUiExpandState
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSlidersViewModel
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onEach
import lineageos.providers.LineageSettings

@VolumeDialogScope
class VolumeDialogSlidersViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogSlidersViewModel,
    private val dialogViewModel: VolumeDialogViewModel,
    private val windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
) : ViewBinder {

    override fun CoroutineScope.bind(view: View) {

        val floatingSlidersContainer: ViewGroup =
            view.requireViewById(R.id.volume_dialog_floating_sliders_container)
        val mainSliderContainer: View =
            view.requireViewById(R.id.volume_dialog_main_slider_container)
        val background: View = view.requireViewById(R.id.volume_dialog_background)
        val bottomSection: View = view.requireViewById(R.id.volume_dialog_bottom_section_container)
        val topSection: View = view.requireViewById(R.id.volume_dialog_top_section_container)

        // Custom Options: "volume_panel_style" == 1 (One UI) — the collapsed One UI volume popup is
        // the ringer-mode button floating above the slider capsule, and nothing else. The
        // settings/expand pill below the capsule stays hidden (One UI only shows it once the panel
        // is expanded), but the ringer section is KEPT: it is the One UI ringer menu (a single
        // button showing the current mode that expands into normal / vibrate / mute — see
        // VolumeDialogRingerViewBinder). Scoped to One UI only (the slider is anchored to parent
        // with verticalBias 0.5, so GONE-ing the bottom section does not collapse it), so the
        // global dimen and every other volume style are untouched.
        // IchthysOS: only the vertical dialog has the One UI pill, like the other volume binders;
        // the horizontal one (custom_volume_horizontal) keeps its own layout.
        val oneUiStyle =
            viewModel.isVolumeDialogVertical &&
                Settings.System.getIntForUser(
                    view.context.contentResolver,
                    "volume_panel_style",
                    1,
                    UserHandle.USER_CURRENT,
                ) == 1
        if (oneUiStyle) {
            // IchthysOS: start collapsed every time the dialog is (re-)bound.
            OneUiExpandState.collapse()

            val density = view.resources.displayMetrics.density
            bottomSection.visibility = View.GONE
            // IchthysOS: hide the ringer/vibrate/mute toggle above the slider entirely — the
            // One UI capsule is the only control needed. The FrameLayout wrapping the ringer
            // drawer has spread constraints so it steals vertical space even when its child
            // MotionLayout is GONE; hiding the container itself fixes that.
            topSection.visibility = View.GONE
            // IchthysOS: the AOSP dialog background (a dark rounded rectangle behind the capsule)
            // must be INVISIBLE in One UI mode — One UI floats the capsule with no container. Using
            // INVISIBLE rather than GONE preserves layout anchors for the floating-sliders
            // container and bottom/top sections that reference this view in their constraints.
            background.visibility = View.INVISIBLE
            (mainSliderContainer.layoutParams as? ConstraintLayout.LayoutParams)?.let {
                // One UI 6 pill: 36 x 210 dp, 18 dp (its shadow padding) from the screen edge, top
                // at 34% of the free height (VERTICAL_PADDING_TOP_RATIO). The dialog view binder
                // keeps these margins at 0 in One UI mode.
                it.matchConstraintMaxHeight = (OneUiVolumeSpecs.trackHeight.value * density).toInt()
                it.width = (OneUiVolumeSpecs.trackWidth.value * density).toInt()
                it.verticalBias = ONEUI_VERTICAL_BIAS
                it.topMargin = 0
                it.bottomMargin = 0
                // IchthysOS: the margin goes on the screen-edge side, which is the start with
                // "Volume panel on left" (VolumeDialog uses Gravity.START then).
                val edgeMargin = (ONEUI_EDGE_MARGIN_DP * density).toInt()
                val panelOnLeft =
                    LineageSettings.Secure.getIntForUser(
                        view.context.contentResolver,
                        LineageSettings.Secure.VOLUME_PANEL_ON_LEFT,
                        0,
                        UserHandle.USER_CURRENT,
                    ) != 0
                it.marginEnd = if (panelOnLeft) 0 else edgeMargin
                it.marginStart = if (panelOnLeft) edgeMargin else 0
                mainSliderContainer.layoutParams = it
            }
            mainSliderContainer.applyOneUiPill(
                windowRootViewBlurInteractor.isBlurCurrentlySupported.value
            )
            launchTraced("VDSVB#oneUiBlur") {
                windowRootViewBlurInteractor.isBlurCurrentlySupported.collect { supported ->
                    mainSliderContainer.applyOneUiPill(supported)
                }
            }
            launchTraced("VDSVB#oneUiExpand") {
                OneUiExpandState.expanded.collect { expanded ->
                    if (expanded) floatingSlidersContainer.animateOneUiExpand()
                }
            }
        }

        launchTraced("VDSVB#addTouchableBounds") {
            dialogViewModel.addTouchableBounds(mainSliderContainer, floatingSlidersContainer)
        }

        viewModel.sliders
            .onEach { uiModel ->
                bindSlider(
                    uiModel.sliderComponent,
                    mainSliderContainer,
                    arrayOf(mainSliderContainer, background, bottomSection, topSection),
                )

                val floatingSliderViewBinders = uiModel.floatingSliderComponent
                val floatingSliderViewLayoutId =
                    if (viewModel.isVolumeDialogVertical) {
                        R.layout.volume_dialog_slider_floating
                    } else {
                        R.layout.volume_dialog_slider_floating_horizontal
                    }
                floatingSlidersContainer.ensureChildCount(
                    viewLayoutId = floatingSliderViewLayoutId,
                    count = floatingSliderViewBinders.size,
                )
                floatingSliderViewBinders.fastForEachIndexed { index, sliderComponent ->
                    val sliderContainer = floatingSlidersContainer.getChildAt(index)
                    if (oneUiStyle) {
                        // IchthysOS: expanded One UI rows draw their own track (no blur, no
                        // elevation); the wrapping FrameLayout is transparent and the rows are
                        // 36 dp pills spaced like One UI's evenly weighted row container.
                        sliderContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        val pad = (ONEUI_EXPANDED_ROW_GAP_DP / 2f * sliderContainer.resources
                            .displayMetrics.density).toInt()
                        // Keep the vertical padding: the floating container's negative margins
                        // rely on it to line the rows up with the 210 dp main pill.
                        sliderContainer.setPadding(
                            pad,
                            sliderContainer.paddingTop,
                            pad,
                            sliderContainer.paddingBottom,
                        )
                        (sliderContainer as? ViewGroup)?.getChildAt(0)?.let { pill ->
                            pill.layoutParams = pill.layoutParams.apply {
                                width = (OneUiVolumeSpecs.trackWidth.value *
                                    pill.resources.displayMetrics.density).toInt()
                            }
                        }
                    } else if (viewModel.showBlur) {
                        sliderContainer.updateBackground()
                    }
                    bindSlider(sliderComponent, sliderContainer, arrayOf(sliderContainer))
                }
            }
            .launchInTraced("VDSVB#sliders", this)

        if (viewModel.showBlur) {
            launchTraced("VDSVB#isBlurCurrentlySupported") {
                windowRootViewBlurInteractor.isBlurCurrentlySupported.collect { supported ->
                    for (child in floatingSlidersContainer.children) {
                        child.setIsBlurSupported(supported)
                    }
                }
            }
        }
    }

    /**
     * IchthysOS: the collapsed One UI pill host: cross-window blur clipped to the pill, the black
     * α30 foreground on top (or the opaque no-blur tint when blur is unavailable), 12 dp elevation.
     */
    private fun View.applyOneUiPill(blurSupported: Boolean) {
        val density = resources.displayMetrics.density
        val radius = OneUiVolumeSpecs.trackWidth.value * density / 2f
        val night =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val tint =
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(
                    when {
                        blurSupported -> ONEUI_BLUR_FOREGROUND
                        night -> ONEUI_TRACK_NO_BLUR_DARK
                        else -> ONEUI_TRACK_NO_BLUR_LIGHT
                    }
                )
            }
        val blur =
            viewRootImpl?.createBackgroundBlurDrawable()?.apply {
                setCornerRadius(radius)
                setBlurRadius(if (blurSupported) (ONEUI_BLUR_RADIUS_DP * density).toInt() else 0)
            }
        background = if (blur != null) LayerDrawable(arrayOf<Drawable>(blur, tint)) else tint
        elevation = OneUiVolumeSpecs.elevation.value * density
        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        clipToOutline = true
    }

    /**
     * IchthysOS: One UI's expanded-panel entrance: after 250 ms, alpha 0 -> 1 over 200 ms (linear)
     * and scale 0.95 -> 1 over 400 ms with PathInterpolator(0.22, 0.25, 0, 1).
     */
    private fun View.animateOneUiExpand() {
        (getTag(R.id.volume_dialog_floating_sliders_container) as? Animator)?.cancel()
        alpha = 0f
        scaleX = 0.95f
        scaleY = 0.95f
        val fade =
            ObjectAnimator.ofFloat(this, View.ALPHA, 0f, 1f).apply {
                duration = 200
                interpolator = LinearInterpolator()
            }
        val scale =
            ObjectAnimator.ofPropertyValuesHolder(
                    this,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.95f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.95f, 1f),
                )
                .apply {
                    duration = 400
                    interpolator = PathInterpolator(0.22f, 0.25f, 0f, 1f)
                }
        val set =
            AnimatorSet().apply {
                startDelay = 250
                playTogether(fade, scale)
            }
        setTag(R.id.volume_dialog_floating_sliders_container, set)
        set.start()
    }

    private fun View.updateBackground() {
        if (background is LayerDrawable) {
            return
        }
        val surfaceEffect = background as GradientDrawable
        val blurDrawable = viewRootImpl.createBackgroundBlurDrawable()
        val dialogCornerRadius: Int =
            context.resources.getDimensionPixelSize(
                R.dimen.volume_dialog_floating_slider_background_corner_radius
            )
        blurDrawable.setCornerRadius(dialogCornerRadius.toFloat())
        blurDrawable.setBlurRadius(0)
        background = LayerDrawable(arrayOf<Drawable>(blurDrawable, surfaceEffect))
        setIsBlurSupported(windowRootViewBlurInteractor.isBlurCurrentlySupported.value)
    }

    private fun View.setIsBlurSupported(supported: Boolean) {
        val layers = (background as LayerDrawable)
        (layers.getDrawable(0) as BackgroundBlurDrawable).setBlurRadius(
            if (supported) {
                context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_background_surface_blur_radius
                )
            } else {
                0
            }
        )
        (layers.getDrawable(1) as GradientDrawable).setColor(
            context.getColor(
                if (supported) {
                    R.color.volume_dialog_view_background_blur
                } else {
                    R.color.volume_dialog_view_background_blur_fallback
                }
            )
        )
    }

    private fun CoroutineScope.bindSlider(
        component: VolumeDialogSliderComponent,
        sliderContainer: View,
        viewsToAnimate: Array<View>,
    ) {
        with(component.sliderViewBinder()) { bind(sliderContainer) }
        with(component.overscrollViewBinder()) { bind(sliderContainer, viewsToAnimate) }
    }
}

// Custom Options: horizontal nudge (dp) that centres the One UI ringer button over the 36dp One UI
// slider capsule. See the comment at its use site for the derivation.
private const val ONEUI_RINGER_CENTERING_NUDGE_DP = 4f

// IchthysOS: One UI 6 pill placement and background (ichthys-refs SPEC_VOLUME_ONEUI.md §2.2-2.4).
private const val ONEUI_VERTICAL_BIAS = 0.34f
private const val ONEUI_EDGE_MARGIN_DP = 18f
private const val ONEUI_EXPANDED_ROW_GAP_DP = 26f
/** Samsung's blur radius "256" on a captured bitmap; ~36 dp of cross-window blur reads the same. */
private const val ONEUI_BLUR_RADIUS_DP = 36f
private const val ONEUI_BLUR_FOREGROUND = 0x1E000000
private const val ONEUI_TRACK_NO_BLUR_LIGHT = 0xCCBFBFBF.toInt()
private const val ONEUI_TRACK_NO_BLUR_DARK = 0xCC3D3D3D.toInt()

private fun ViewGroup.ensureChildCount(@LayoutRes viewLayoutId: Int, count: Int) {
    val childCountDelta = childCount - count
    when {
        childCountDelta > 0 -> {
            removeViews(0, childCountDelta)
        }
        childCountDelta < 0 -> {
            val inflater = LayoutInflater.from(context)
            repeat(-childCountDelta) { inflater.inflate(viewLayoutId, this, true) }
        }
    }
}
