/*
 * Copyright (C) 2024-2026 crDroid Android Project
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
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.weather.WeatherInfoView
import javax.inject.Inject

/**
 * Adds the custom OmniJaws lock screen weather view below the keyguard slice view. Only active when
 * [LockscreenSmartspaceController.isOmniWeatherEnabled] (i.e. lockscreen_weather_enabled is on and
 * the AOSP smartspace is disabled). The slice section owns the smart_space_barrier_bottom barrier
 * and folds this view into it, so the notification stack starts below the weather.
 */
class KeyguardWeatherViewSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val layoutInflater: LayoutInflater,
    private val smartspaceController: LockscreenSmartspaceController,
) : KeyguardSection() {
    private var weatherView: WeatherInfoView? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!smartspaceController.isOmniWeatherEnabled) return

        weatherView =
            layoutInflater.inflate(R.layout.keyguard_weather_area, null, false) as WeatherInfoView
        constraintLayout.addView(weatherView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!smartspaceController.isOmniWeatherEnabled) return

        weatherView?.init()
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!smartspaceController.isOmniWeatherEnabled) return

        val startMargin = KeyguardSmartspaceViewModel.getDateWeatherStartMargin(context)
        constraintSet.apply {
            connect(
                R.id.keyguard_weather_area,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                startMargin,
            )
            connect(
                R.id.keyguard_weather_area,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
            )
            constrainHeight(R.id.keyguard_weather_area, ConstraintSet.WRAP_CONTENT)
            connect(
                R.id.keyguard_weather_area,
                ConstraintSet.TOP,
                R.id.keyguard_slice_view,
                ConstraintSet.BOTTOM,
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        weatherView?.let { area ->
            area.cleanup()
            constraintLayout.removeView(area)
        }
        weatherView = null
    }
}
