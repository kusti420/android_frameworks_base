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

package com.android.systemui.volume.dialog.ui.binder

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import androidx.compose.ui.util.lerp
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.Flags
import com.android.systemui.common.ui.view.onApplyWindowInsets
import com.android.systemui.common.ui.view.updateMargin
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.awaitCancellationThenDispose
import com.android.systemui.util.view.listenToComputeInternalInsets
import com.android.systemui.volume.dialog.captions.ui.viewmodel.VolumeDialogCaptionsButtonViewModel
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.android.systemui.volume.dialog.ui.utils.JankListenerFactory
import com.android.systemui.volume.dialog.ui.utils.suspendAnimate
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import com.android.systemui.volume.dialog.utils.VolumeTracer
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan

private const val SPRING_STIFFNESS = 700f
private const val SPRING_DAMPING_RATIO = 0.9f

private const val FRACTION_HIDE = 0f
private const val FRACTION_SHOW = 1f
private const val ANIMATION_MINIMUM_VISIBLE_CHANGE = 0.01f

// Custom Options: One UI 6 volume pill motion (ichthys-refs SPEC_VOLUME_ONEUI.md §2.6).
private const val ONEUI_SHOW_STIFFNESS = 150f
private const val ONEUI_SHOW_DAMPING_RATIO = 0.7f
private const val ONEUI_HIDE_DURATION_MS = 350L

/** Binds the root view of the Volume Dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumeDialogScope
class VolumeDialogViewBinder
@Inject
constructor(
    @Application context: Context,
    private val viewModel: VolumeDialogViewModel,
    private val captionsButtonViewModel: VolumeDialogCaptionsButtonViewModel,
    private val jankListenerFactory: JankListenerFactory,
    private val tracer: VolumeTracer,
    @VolumeDialog private val viewBinders: List<@JvmSuppressWildcards ViewBinder>,
) {

    private val halfOpenedOffsetPx: Float =
        context.resources.getDimensionPixelSize(R.dimen.volume_dialog_half_opened_offset).toFloat()
    private val mainSliderVerticalMargin: Int by lazy {
        context.resources.getDimensionPixelSize(R.dimen.volume_dialog_slider_vertical_margin)
    }
    private val mainSliderHorizontalMargin: Int by lazy {
        context.resources.getDimensionPixelSize(R.dimen.volume_dialog_slider_horizontal_margin)
    }

    private val mainSliderWithCaptionsToggleVerticalMargin: Int by lazy {
        context.resources.getDimensionPixelSize(
            R.dimen.volume_dialog_slider_vertical_margin_with_captions_toggle
        )
    }
    private val mainSliderWithCaptionsToggleHorizontalMargin: Int by lazy {
        context.resources.getDimensionPixelSize(
            R.dimen.volume_dialog_slider_horizontal_margin_with_captions_toggle
        )
    }

    private val mainSliderWidth: Int by lazy {
        context.resources.getDimensionPixelSize(R.dimen.volume_dialog_horizontal_slider_width)
    }

    private val mainSliderWidthWithCaptions: Int by lazy {
        context.resources.getDimensionPixelSize(
            R.dimen.volume_dialog_horizontal_slider_width_with_captions
        )
    }

    fun CoroutineScope.bind(dialog: Dialog, isVolumeDialogVertical: Boolean = true) {
        // Root view of the Volume Dialog.
        val root: ViewGroup = dialog.requireViewById(R.id.volume_dialog)
        val mainSliderContainer: View? = root.findViewById(R.id.volume_dialog_main_slider_container)
        root.accessibilityDelegate = Accessibility(viewModel)
        root.setOnHoverListener { _, event ->
            viewModel.onHover(
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                    event.actionMasked == MotionEvent.ACTION_HOVER_MOVE
            )
            true
        }
        // Custom Options: "volume_panel_style" (Settings.System, default 1 = One UI).
        val oneUiStyle =
            isVolumeDialogVertical &&
                Settings.System.getIntForUser(
                    root.context.contentResolver,
                    "volume_panel_style",
                    1,
                    UserHandle.USER_CURRENT,
                ) == 1
        animateVisibility(root, dialog, viewModel.dialogVisibilityModel, oneUiStyle)

        viewModel.dialogTitle
            .filter { it.isNotEmpty() }
            .onEach { dialog.window?.setTitle(it) }
            .launchInTraced("VDVB#dialogTitle", this)
        viewModel.isHalfOpened
            .scan<Boolean, Boolean?>(null) { acc, isHalfOpened ->
                // don't animate the initial state
                root.applyVerticalOffset(
                    offsetPx = if (isHalfOpened) halfOpenedOffsetPx else 0f,
                    shouldAnimate = acc != null,
                )
                isHalfOpened
            }
            .launchInTraced("VDVB#isHalfOpened", this)

        launchTraced("VDVB#viewTreeObserver") {
            root.viewTreeObserver.listenToComputeInternalInsets {
                viewModel.fillTouchableBounds(this)
            }
        }

        launchTraced("VDVB#insets") {
            root
                .onApplyWindowInsets { view, newInsets ->
                    val insetsValues =
                        newInsets.getInsets(
                            WindowInsets.Type.displayCutout() or
                                WindowInsets.Type.navigationBars() or
                                WindowInsets.Type.statusBars()
                        )
                    if (isVolumeDialogVertical) {
                        view.updatePadding(
                            left = insetsValues.left,
                            top = insetsValues.top,
                            right = insetsValues.right,
                            bottom = insetsValues.bottom,
                        )
                        if (!oneUiStyle) {
                            mainSliderContainer?.updateMargin(
                                top = getSliderVerticalMargin() - view.paddingTop,
                                bottom = getSliderVerticalMargin() - view.paddingBottom,
                            )
                        }
                        // One UI keeps the pill at 0 margins and a 0.34 vertical bias (set by
                        // VolumeDialogSlidersViewBinder), matching VERTICAL_PADDING_TOP_RATIO.
                    } else {
                        // Custom Options: the horizontal dialog is a fixed-width, window-centred
                        // pill. Only clear the status bar / cutout at the top; the old slider
                        // margin + ConstraintLayout max-width logic (which assumed the slider was
                        // a direct ConstraintLayout child) no longer applies and would crash.
                        view.updatePadding(top = insetsValues.top)
                    }
                    WindowInsets.CONSUMED
                }
                .awaitCancellationThenDispose()
        }

        for (viewBinder in viewBinders) {
            with(viewBinder) { bind(root) }
        }
    }

    private fun CoroutineScope.animateVisibility(
        view: View,
        dialog: Dialog,
        visibilityModel: Flow<VolumeDialogVisibilityModel>,
        oneUiStyle: Boolean,
    ) {
        view.applyAnimationProgress(FRACTION_HIDE)
        val animationValueHolder = FloatValueHolder(FRACTION_HIDE)
        val animation: SpringAnimation =
            SpringAnimation(animationValueHolder)
                .setSpring(
                    // One UI slides the pill in with a softer, slightly bouncy spring.
                    SpringForce()
                        .setStiffness(if (oneUiStyle) ONEUI_SHOW_STIFFNESS else SPRING_STIFFNESS)
                        .setDampingRatio(
                            if (oneUiStyle) ONEUI_SHOW_DAMPING_RATIO else SPRING_DAMPING_RATIO
                        )
                )
                .setMinimumVisibleChange(ANIMATION_MINIMUM_VISIBLE_CHANGE)
                .addUpdateListener { _, value, _ -> view.applyAnimationProgress(value) }
        var junkListener: DynamicAnimation.OnAnimationUpdateListener? = null

        visibilityModel
            .conflate()
            .onEach {
                when (it) {
                    is VolumeDialogVisibilityModel.Visible -> {
                        tracer.traceVisibilityEnd(it)
                        junkListener?.let(animation::removeUpdateListener)
                        junkListener =
                            jankListenerFactory.show(view).also(animation::addUpdateListener)
                        animation.suspendAnimate(FRACTION_SHOW)
                    }

                    is VolumeDialogVisibilityModel.Dismissed -> {
                        tracer.traceVisibilityEnd(it)
                        junkListener?.let(animation::removeUpdateListener)
                        junkListener =
                            jankListenerFactory.dismiss(view).also(animation::addUpdateListener)
                        if (oneUiStyle) {
                            // One UI hides with a 350 ms accelerating slide to the edge.
                            animation.cancel()
                            ValueAnimator.ofFloat(animationValueHolder.value, FRACTION_HIDE)
                                .apply {
                                    duration = ONEUI_HIDE_DURATION_MS
                                    interpolator = PathInterpolator(0.7f, 0f, 0.83f, 0.83f)
                                }
                                .suspendAnimate<Float> { value ->
                                    animationValueHolder.value = value
                                    view.applyAnimationProgress(value)
                                }
                        } else {
                            animation.suspendAnimate(FRACTION_HIDE)
                        }
                        dialog.dismiss()
                    }

                    is VolumeDialogVisibilityModel.Invisible -> {
                        // do nothing
                    }
                }
            }
            .launchInTraced("VDVB#visibilityModel", this)
    }

    /**
     * @param fraction in range [0, 1]. 0 corresponds to the dialog being hidden and 1 - visible.
     */
    private fun View.applyAnimationProgress(fraction: Float) {
        alpha = ceil(fraction)
        translationX = lerp(width, 0, fraction).toFloat()
    }

    private suspend fun View.applyVerticalOffset(offsetPx: Float, shouldAnimate: Boolean) {
        if (!shouldAnimate) {
            translationY = offsetPx
            return
        }
        animate().setDuration(150).translationY(offsetPx).suspendAnimate()
    }

    private fun getSliderVerticalMargin(): Int =
        if (Flags.captionsToggleInVolumeDialogV1() && captionsButtonViewModel.isVisible.value) {
            mainSliderWithCaptionsToggleVerticalMargin
        } else {
            mainSliderVerticalMargin
        }

    private fun getSliderHorizontalMargin(): Int =
        if (Flags.captionsToggleInVolumeDialogV1() && captionsButtonViewModel.isVisible.value) {
            mainSliderWithCaptionsToggleHorizontalMargin
        } else {
            mainSliderHorizontalMargin
        }

    private fun getSliderWidth(): Int =
        if (Flags.captionsToggleInVolumeDialogV1() && captionsButtonViewModel.isVisible.value) {
            mainSliderWidthWithCaptions
        } else {
            mainSliderWidth
        }

    private class Accessibility(private val viewModel: VolumeDialogViewModel) :
        View.AccessibilityDelegate() {

        override fun dispatchPopulateAccessibilityEvent(
            host: View,
            event: AccessibilityEvent,
        ): Boolean {
            // Activities populate their title here. Follow that example.
            val title = viewModel.dialogTitle.value
            if (title.isNotEmpty()) {
                event.text.add(title)
            }
            return true
        }

        override fun onRequestSendAccessibilityEvent(
            host: ViewGroup,
            child: View,
            event: AccessibilityEvent,
        ): Boolean {
            viewModel.resetDialogTimeout()
            return super.onRequestSendAccessibilityEvent(host, child, event)
        }
    }
}
