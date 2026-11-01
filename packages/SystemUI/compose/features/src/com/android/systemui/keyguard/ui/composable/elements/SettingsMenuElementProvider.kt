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
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.LayoutInflater
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import com.android.compose.animation.scene.ElementContentScope
import com.android.settingslib.ichthys.clock.LockWeather
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.IchthysLockNotifRepository
import com.android.systemui.keyguard.ui.binder.KeyguardSettingsViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSettingsMenuViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardTouchHandlingViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.keyguard.ui.composable.elements.BaseLockscreenElement.ElementSource
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementProvider
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.VibratorHelper
import javax.inject.Inject
import kotlin.collections.List
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.filter

@SysUISingleton
class SettingsMenuElementProvider
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val viewModel: KeyguardSettingsMenuViewModel,
    private val touchHandlingViewModelFactory: KeyguardTouchHandlingViewModel.Factory,
    private val vibratorHelper: VibratorHelper,
    private val activityStarter: ActivityStarter,
    private val notifRepository: IchthysLockNotifRepository,
) : LockscreenElementProvider {
    override val elements: List<LockscreenElement> by lazy { listOf(SettingsMenuElement()) }

    private inner class SettingsMenuElement : LockscreenElement {
        override val key = LockscreenElementKeys.SettingsMenu
        override val context = this@SettingsMenuElementProvider.context
        override val source = ElementSource.STANDARD

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            SettingsMenu()
        }
    }

    @Composable
    @SuppressWarnings("InflateParams") // null is passed into the inflate call, on purpose.
    fun SettingsMenu(modifier: Modifier = Modifier) {
        val (disposableHandle, setDisposableHandle) =
            remember { mutableStateOf<DisposableHandle?>(null) }
        // IchthysOS: the menu hangs from the status bar (LockscreenSceneLayout), where the Quiet
        // dock's sheet has its header: the sheet goes away as the menu comes up.
        LaunchedEffect(Unit) {
            viewModel.isVisible.filter { it }.collect { notifRepository.setSheetOpen(false) }
        }
        val weatherClearance = rememberTopWeatherClearance()
        AndroidView(
            factory = { context ->
                LayoutInflater.from(context)
                    .inflate(R.layout.keyguard_settings_popup_menu, null)
                    .apply {
                        // IchthysOS: invisible, not gone: a gone view measures nothing, so the
                        // menu's reported bounds were only its padding, every tap on the button
                        // counted as "outside" and closed it before it could be pressed.
                        visibility = android.view.View.INVISIBLE
                        alpha = 0f

                        setDisposableHandle(
                            KeyguardSettingsViewBinder.bind(
                                view = this,
                                viewModel = viewModel,
                                touchHandlingViewModelFactory = touchHandlingViewModelFactory,
                                rootViewModel = null,
                                vibratorHelper = vibratorHelper,
                                activityStarter = activityStarter,
                            )
                        )
                    }
            },
            onRelease = { disposableHandle?.dispose() },
            // IchthysOS: a small gap under the status bar (and under a top-corner weather line);
            // no padding under it: the padding counts as "inside" for the outside-tap dismissal.
            modifier =
                modifier
                    .padding(top = MENU_TOP_GAP_DP.dp + weatherClearance)
                    .padding(
                        horizontal =
                            dimensionResource(R.dimen.keyguard_affordance_horizontal_offset)
                    ),
        )
    }

    /**
     * IchthysOS: the room a top-corner weather line takes under the status bar (LockWeatherView
     * SLOT_TOP: [LockscreenUpperRegionElementProvider.WEATHER_TOP_DP] down, a 15 sp line at the
     * weather's size in a pill with 5 dp above and under it), so the menu sits under it rather
     * than over it; 0 while the weather is off or elsewhere.
     */
    @Composable
    private fun rememberTopWeatherClearance(): Dp {
        val size by
            produceState(initialValue = topWeatherSize(), context) {
                val resolver = context.contentResolver
                val observer =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            value = topWeatherSize()
                        }
                    }
                for (key in LockWeather.SECURE_KEYS) {
                    resolver.registerContentObserver(
                        Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_ALL)
                }
                for (key in LockWeather.SYSTEM_KEYS) {
                    resolver.registerContentObserver(
                        Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL)
                }
                value = topWeatherSize()
                awaitDispose { resolver.unregisterContentObserver(observer) }
            }
        if (size <= 0) return 0.dp
        val line =
            with(LocalDensity.current) {
                (WEATHER_TOP_SP * WEATHER_LINE_HEIGHT * size / 100f).sp.toDp()
            }
        return LockscreenUpperRegionElementProvider.WEATHER_TOP_DP.dp + WEATHER_PILL_DP.dp + line
    }

    /** The weather's size in percent when it shows in a top corner, else 0. */
    private fun topWeatherSize(): Int {
        val o = LockWeather.readOptions(context)
        val top = o.pos == LockWeather.POS_TOP_START || o.pos == LockWeather.POS_TOP_END
        return if (o.enabled && top) o.size else 0
    }

    private companion object {
        /** IchthysOS: the menu's distance under the status bar (or the top-corner weather). */
        const val MENU_TOP_GAP_DP = 8
        /** IchthysOS: LockWeatherView's top-corner text size at 100 %, and a line's height in it. */
        const val WEATHER_TOP_SP = 15f
        const val WEATHER_LINE_HEIGHT = 1.4f
        /** IchthysOS: the top-corner pill's padding above and under the line (5 dp each, +2). */
        const val WEATHER_PILL_DP = 12
    }
}
