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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.GONE
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.VISIBLE
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardClockViewBinder
import com.android.systemui.keyguard.ui.view.KeyguardCustomClockView
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceLayout
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.R as sharedR
import com.android.systemui.util.ui.value
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

internal fun ConstraintSet.setVisibility(views: Iterable<View>, visibility: Int) =
    views.forEach { view -> this.setVisibility(view.id, visibility) }

private fun ConstraintSet.setAlpha(views: Iterable<View>, alpha: Float) =
    views.forEach { view -> this.setAlpha(view.id, alpha) }

private fun ConstraintSet.setScaleX(views: Iterable<View>, scaleX: Float) =
    views.forEach { view -> this.setScaleX(view.id, scaleX) }

private fun ConstraintSet.setScaleY(views: Iterable<View>, scaleY: Float) =
    views.forEach { view -> this.setScaleY(view.id, scaleY) }

@SysUISingleton
class ClockSection
@Inject
constructor(
    private val clockInteractor: KeyguardClockInteractor,
    protected val keyguardClockViewModel: KeyguardClockViewModel,
    @ShadeDisplayAware private val context: Context,
    val blueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
    private val rootViewModel: KeyguardRootViewModel,
    private val aodBurnInViewModel: AodBurnInViewModel,
    private val largeScreenHeaderHelperLazy: Lazy<LargeScreenHeaderHelper>,
) : KeyguardSection() {
    private var disposableHandle: DisposableHandle? = null
    private var customClockViewId: Int = View.NO_ID

    override fun addViews(constraintLayout: ConstraintLayout) {
        // IchthysOS custom lockscreen clock overlay. Self-manages visibility from
        // Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE; stays GONE for the default style so the
        // stock AOSP clock is used instead.
        val existing = constraintLayout.findViewWithTag<View>(KeyguardCustomClockView.VIEW_TAG)
        if (existing != null) {
            customClockViewId = existing.id
            return
        }
        val customClock =
            KeyguardCustomClockView(context).apply {
                id = View.generateViewId()
                tag = KeyguardCustomClockView.VIEW_TAG
                translationZ = 100f
            }
        customClockViewId = customClock.id
        val lp =
            ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                )
                .apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
        constraintLayout.addView(customClock, lp)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        disposableHandle?.dispose()
        disposableHandle =
            KeyguardClockViewBinder.bind(
                this,
                constraintLayout,
                keyguardClockViewModel,
                clockInteractor,
                blueprintInteractor.get(),
                rootViewModel,
                aodBurnInViewModel,
            )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        keyguardClockViewModel.currentClock.value?.let { clock ->
            constraintSet.applyDeltaFrom(buildConstraints(clock, constraintSet))
        }
        // IchthysOS: keep the custom clock overlay constrained to fill its parent every time the
        // keyguard re-applies a ConstraintSet. A view absent from the applied set loses its
        // constraints and collapses to nothing -- that left only the stock clock visible even with
        // a custom style selected. Visibility tracks the selected style.
        val active = isCustomClockActive()
        Log.i("IchthysClock", "applyConstraints: customClockViewId=$customClockViewId active=$active")
        if (customClockViewId != View.NO_ID) {
            constraintSet.apply {
                constrainWidth(customClockViewId, ConstraintSet.MATCH_CONSTRAINT)
                constrainHeight(customClockViewId, ConstraintSet.MATCH_CONSTRAINT)
                connect(customClockViewId, TOP, PARENT_ID, TOP)
                connect(customClockViewId, BOTTOM, PARENT_ID, BOTTOM)
                connect(customClockViewId, START, PARENT_ID, START)
                connect(customClockViewId, END, PARENT_ID, END)
                setVisibility(customClockViewId, if (active) VISIBLE else GONE)
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        disposableHandle?.dispose()
        constraintLayout.findViewWithTag<View>(KeyguardCustomClockView.VIEW_TAG)?.let {
            constraintLayout.removeView(it)
        }
    }

    private fun buildConstraints(
        clock: ClockController,
        constraintSet: ConstraintSet,
    ): ConstraintSet {
        // Add constraint between rootView and clockContainer
        applyDefaultConstraints(constraintSet)
        getNonTargetClockFace(clock).applyConstraints(constraintSet)
        getTargetClockFace(clock).applyConstraints(constraintSet)

        // Add constraint between elements in clock and clock container
        return constraintSet.apply {
            setVisibility(getTargetClockFace(clock).views, VISIBLE)
            setVisibility(getNonTargetClockFace(clock).views, GONE)
            setAlpha(getTargetClockFace(clock).views, 1F)
            setAlpha(getNonTargetClockFace(clock).views, 0F)

            // IchthysOS: when a custom lockscreen clock style is selected, hide the stock AOSP clock
            // so it does not overlap KeyguardCustomClockView (which draws the selected face).
            if (isCustomClockActive()) {
                setVisibility(getTargetClockFace(clock).views, GONE)
                setVisibility(getNonTargetClockFace(clock).views, GONE)
            }

            if (!keyguardClockViewModel.isLargeClockVisible.value) {
                if (keyguardClockViewModel.shouldDateWeatherBeBelowSmallClock.value) {
                    connect(
                        sharedR.id.bc_smartspace_view,
                        TOP,
                        sharedR.id.date_smartspace_view,
                        BOTTOM,
                    )
                } else {
                    connect(
                        sharedR.id.bc_smartspace_view,
                        TOP,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                        BOTTOM,
                    )
                }
            } else {
                if (aodBurnInViewModel.movement.value.scaleClockOnly) {
                    setScaleX(
                        getTargetClockFace(clock).views,
                        aodBurnInViewModel.movement.value.scale,
                    )
                    setScaleY(
                        getTargetClockFace(clock).views,
                        aodBurnInViewModel.movement.value.scale,
                    )
                }
            }
        }
    }

    private fun isCustomClockActive(): Boolean =
        Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
            0,
            UserHandle.USER_CURRENT,
        ) != 0

    private fun getTargetClockFace(clock: ClockController): ClockFaceLayout =
        if (keyguardClockViewModel.isLargeClockVisible.value) clock.largeClock.layout
        else clock.smallClock.layout

    private fun getNonTargetClockFace(clock: ClockController): ClockFaceLayout =
        if (keyguardClockViewModel.isLargeClockVisible.value) clock.smallClock.layout
        else clock.largeClock.layout

    private fun constrainWeatherClockDateIconsBarrier(constraints: ConstraintSet) {
        constraints.apply {
            createBarrier(
                R.id.weather_clock_bc_smartspace_bottom,
                Barrier.BOTTOM,
                context.resources.getDimensionPixelSize(clocksR.dimen.enhanced_smartspace_height),
                (ClockViewIds.WEATHER_CLOCK_TIME),
            )
            if (
                rootViewModel.isNotifIconContainerVisible.value.value &&
                    keyguardClockViewModel.hasAodIcons.value
            ) {
                createBarrier(
                    ClockViewIds.WEATHER_CLOCK_DATE_BARRIER_BOTTOM,
                    Barrier.BOTTOM,
                    0,
                    *intArrayOf(
                        R.id.aod_notification_icon_container,
                        R.id.weather_clock_bc_smartspace_bottom,
                    ),
                )
            } else {
                createBarrier(
                    ClockViewIds.WEATHER_CLOCK_DATE_BARRIER_BOTTOM,
                    Barrier.BOTTOM,
                    0,
                    *intArrayOf(R.id.weather_clock_bc_smartspace_bottom),
                )
            }
        }
    }

    fun applyDefaultConstraints(constraints: ConstraintSet) {
        val guideline =
            if (keyguardClockViewModel.clockShouldBeCentered.value) PARENT_ID
            else R.id.split_shade_guideline
        constraints.apply {
            connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, START, PARENT_ID, START)
            connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, END, guideline, END)
            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                BOTTOM,
                R.id.device_entry_icon_view,
                TOP,
            )
            val largeClockTopMargin =
                keyguardClockViewModel.getLargeClockTopMargin() +
                    context.resources.getDimensionPixelSize(
                        clocksR.dimen.enhanced_smartspace_height
                    )
            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                TOP,
                PARENT_ID,
                TOP,
                largeClockTopMargin,
            )
            constrainWidth(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, WRAP_CONTENT)

            // The following two lines make LOCKSCREEN_CLOCK_VIEW_LARGE is constrained to available
            // height when it goes beyond constraints; otherwise, it use WRAP_CONTENT
            constrainHeight(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, WRAP_CONTENT)
            constrainMaxHeight(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, 0)
            constrainWidth(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, WRAP_CONTENT)
            constrainHeight(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                context.resources.getDimensionPixelSize(clocksR.dimen.small_clock_height),
            )
            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                START,
                PARENT_ID,
                START,
                context.resources.getDimensionPixelSize(clocksR.dimen.clock_padding_start) +
                    context.resources.getDimensionPixelSize(
                        clocksR.dimen.status_view_margin_horizontal
                    ),
            )
            val smallClockTopMargin = keyguardClockViewModel.getSmallClockTopMargin()
            create(R.id.small_clock_guideline_top, ConstraintSet.HORIZONTAL_GUIDELINE)
            setGuidelineBegin(R.id.small_clock_guideline_top, smallClockTopMargin)
            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                TOP,
                R.id.small_clock_guideline_top,
                BOTTOM,
            )

            // Explicitly clear pivot to force recalculate pivot instead of using legacy value
            setTransformPivot(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, Float.NaN, Float.NaN)

            val smallClockBottom =
                keyguardClockViewModel.getSmallClockTopMargin() +
                    context.resources.getDimensionPixelSize(clocksR.dimen.small_clock_height)
            val marginBetweenSmartspaceAndNotification =
                context.resources.getDimensionPixelSize(
                    R.dimen.keyguard_status_view_bottom_margin
                ) +
                    if (context.resources.getBoolean(R.bool.config_use_large_screen_shade_header)) {
                        largeScreenHeaderHelperLazy.get().getLargeScreenHeaderHeight()
                    } else {
                        0
                    }

            if (keyguardClockViewModel.shouldDateWeatherBeBelowSmallClock.value) {
                val dateWeatherSmartspaceHeight =
                    context.resources
                        .getDimensionPixelSize(clocksR.dimen.date_weather_view_height)
                        .toFloat()
                clockInteractor.setNotificationStackDefaultTop(
                    smallClockBottom +
                        dateWeatherSmartspaceHeight +
                        marginBetweenSmartspaceAndNotification
                )
            } else {
                clockInteractor.setNotificationStackDefaultTop(
                    (smallClockBottom + marginBetweenSmartspaceAndNotification).toFloat()
                )
            }
        }

        constrainWeatherClockDateIconsBarrier(constraints)
    }
}
