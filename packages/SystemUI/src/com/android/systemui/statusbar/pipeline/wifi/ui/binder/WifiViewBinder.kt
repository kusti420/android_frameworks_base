/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.wifi.ui.binder

import android.content.res.ColorStateList
import android.net.wifi.ScanResult
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewVisibilityHelper
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Binds a wifi icon in the status bar to its view-model.
 *
 * To use this properly, users should maintain a one-to-one relationship between the [View] and the
 * view-binding, binding each view only once. It is okay and expected for the same instance of the
 * view-model to be reused for multiple view/view-binder bindings.
 */
@OptIn(InternalCoroutinesApi::class)
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
object WifiViewBinder {

    /**
     * Maps a [ScanResult] WIFI_STANDARD_* constant to its badge drawable, or 0 when there is no
     * badge to show (the view-model already filters to 4/5/6/7, this is a defensive fallback).
     */
    private fun wifiStandardBadgeRes(standard: Int): Int =
        when (standard) {
            ScanResult.WIFI_STANDARD_11N -> R.drawable.ic_wifi_standard_4
            ScanResult.WIFI_STANDARD_11AC -> R.drawable.ic_wifi_standard_5
            ScanResult.WIFI_STANDARD_11AX -> R.drawable.ic_wifi_standard_6
            ScanResult.WIFI_STANDARD_11BE -> R.drawable.ic_wifi_standard_7
            else -> 0
        }

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @JvmStatic
    fun bind(view: ViewGroup, viewModel: LocationBasedWifiViewModel): ModernStatusBarViewBinding {
        val groupView = view.requireViewById<ViewGroup>(R.id.wifi_group)
        val iconView = view.requireViewById<ImageView>(R.id.wifi_signal)
        val dotView = view.requireViewById<StatusBarIconView>(R.id.status_bar_dot)
        val activityInView = view.requireViewById<ImageView>(R.id.wifi_in)
        val activityOutView = view.requireViewById<ImageView>(R.id.wifi_out)
        val activityContainerView = view.requireViewById<View>(R.id.inout_container)
        val standardBadgeView = view.requireViewById<ImageView>(R.id.wifi_standard_badge)

        view.isVisible = true
        iconView.isVisible = true

        // TODO(b/238425913): We should log this visibility state.
        @StatusBarIconView.VisibleState
        val visibilityState: MutableStateFlow<Int> = MutableStateFlow(STATE_HIDDEN)

        val iconTint: MutableStateFlow<Int> = MutableStateFlow(viewModel.defaultColor)
        val decorTint: MutableStateFlow<Int> = MutableStateFlow(viewModel.defaultColor)

        var isCollecting: Boolean = false

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                isCollecting = true

                launch {
                    visibilityState.collect { visibilityState ->
                        // for b/296864006, we can not hide all the child views if visibilityState
                        // is STATE_HIDDEN. Because hiding all child views would cause the
                        // getWidth() of this view return 0, and that would cause the translation
                        // calculation fails in StatusIconContainer. Therefore, like class
                        // MobileIconBinder, instead of set the child views visibility to View.GONE,
                        // we set their visibility to View.INVISIBLE to make them invisible but
                        // keep the width.
                        ModernStatusBarViewVisibilityHelper.setVisibilityState(
                            visibilityState,
                            groupView,
                            dotView,
                        )
                    }
                }

                launch {
                    viewModel.wifiIcon.collect { wifiIcon ->
                        view.isVisible = wifiIcon is WifiIcon.Visible
                        if (wifiIcon is WifiIcon.Visible) {
                            IconViewBinder.bind(wifiIcon.icon, iconView)
                        }
                    }
                }

                launch {
                    iconTint.collect { tint ->
                        val tintList = ColorStateList.valueOf(tint)
                        iconView.imageTintList = tintList
                        activityInView.imageTintList = tintList
                        activityOutView.imageTintList = tintList
                        standardBadgeView.imageTintList = tintList
                        dotView.setDecorColor(tint)
                    }
                }

                // crDroid wifi_standard_icon: show a small Wi-Fi generation badge when enabled.
                launch {
                    viewModel.wifiStandard.collect { standard ->
                        val badgeRes = wifiStandardBadgeRes(standard)
                        if (badgeRes == 0) {
                            standardBadgeView.isVisible = false
                            standardBadgeView.setImageDrawable(null)
                        } else {
                            standardBadgeView.setImageResource(badgeRes)
                            standardBadgeView.isVisible = true
                        }
                    }
                }

                launch { decorTint.collect { tint -> dotView.setDecorColor(tint) } }

                launch {
                    viewModel.isActivityInViewVisible.distinctUntilChanged().collect { visible ->
                        activityInView.isVisible = visible
                    }
                }

                launch {
                    viewModel.isActivityOutViewVisible.distinctUntilChanged().collect { visible ->
                        activityOutView.isVisible = visible
                    }
                }

                launch {
                    viewModel.isActivityContainerVisible.distinctUntilChanged().collect { visible ->
                        activityContainerView.isVisible = visible
                    }
                }

                try {
                    awaitCancellation()
                } finally {
                    isCollecting = false
                }
            }
        }

        return object : ModernStatusBarViewBinding {
            override fun getShouldIconBeVisible(): Boolean {
                return viewModel.wifiIcon.value is WifiIcon.Visible
            }

            override fun onVisibilityStateChanged(@StatusBarIconView.VisibleState state: Int) {
                visibilityState.value = state
            }

            override fun onIconTintChanged(newTint: Int, contrastTint: Int /* unused */) {
                iconTint.value = newTint
            }

            override fun onDecorTintChanged(newTint: Int) {
                decorTint.value = newTint
            }

            override fun isCollecting(): Boolean {
                return isCollecting
            }
        }
    }
}
