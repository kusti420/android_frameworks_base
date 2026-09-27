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

package com.android.systemui.keyguard.ui.viewmodel

import android.os.UserHandle
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.viewmodel.UserActionsViewModel
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.ui.viewmodel.dualShadeActions
import com.android.systemui.shade.ui.viewmodel.singleShadeActions
import com.android.systemui.shade.ui.viewmodel.splitShadeActions
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.settings.SystemSettings
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Models UI state and handles user input for the lockscreen scene. */
@OptIn(ExperimentalCoroutinesApi::class)
class LockscreenUserActionsViewModel
@AssistedInject
constructor(
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val communalInteractor: CommunalInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val systemSettings: SystemSettings,
) : UserActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        shadeInteractor.isShadeTouchable
            .flatMapLatest { isShadeTouchable ->
                if (!isShadeTouchable) {
                    return@flatMapLatest flowOf(emptyMap())
                }

                combine(
                    deviceEntryInteractor.isUnlocked,
                    communalInteractor.isCommunalAvailable,
                    shadeModeInteractor.shadeMode,
                    lockscreenQuickSettingsEnabled(),
                ) { isDeviceUnlocked, isCommunalAvailable, shadeMode, lockscreenQsEnabled ->
                    buildList {
                            if (isCommunalAvailable) {
                                add(Swipe.Start to Scenes.Communal)
                            }

                            if (isDeviceUnlocked) {
                                add(Swipe.Up to Scenes.Gone)
                            } else {
                                add(Swipe.Up to Overlays.Bouncer)
                            }

                            addAll(
                                when (shadeMode) {
                                    ShadeMode.Single -> singleShadeActions()
                                    ShadeMode.Split -> splitShadeActions()
                                    ShadeMode.Dual -> dualShadeActions()
                                }.let { actions ->
                                    // IchthysOS: crDroid's enable_lockscreen_quick_settings. When
                                    // off, the lockscreen keeps its shade gestures but loses the
                                    // ones that land on Quick Settings, so QS cannot be reached
                                    // without unlocking.
                                    if (lockscreenQsEnabled) {
                                        actions.toList()
                                    } else {
                                        actions.filterNot { (_, result) ->
                                            result.targetsQuickSettings()
                                        }
                                    }
                                }
                            )
                        }
                        .associate { it }
                }
            }
            .collect { setActions(it) }
    }

    /**
     * crDroid `enable_lockscreen_quick_settings`, defaulting to on to match the preference XML.
     *
     * Read through the `*ForUser` variant with [UserHandle.USER_CURRENT]: the shade runs in a
     * per-display window context whose `getUserId()` is not the foreground user.
     */
    private fun lockscreenQuickSettingsEnabled(): Flow<Boolean> =
        systemSettings
            .observerFlow(KEY_LOCKSCREEN_QUICK_SETTINGS)
            .onStart { emit(Unit) }
            .map {
                systemSettings.getIntForUser(
                    KEY_LOCKSCREEN_QUICK_SETTINGS,
                    1,
                    UserHandle.USER_CURRENT,
                ) != 0
            }

    @AssistedFactory
    interface Factory {
        fun create(): LockscreenUserActionsViewModel
    }
}

private const val KEY_LOCKSCREEN_QUICK_SETTINGS = "enable_lockscreen_quick_settings"

/** True when this action would take the user to Quick Settings, in any shade mode. */
private fun UserActionResult.targetsQuickSettings(): Boolean =
    when (this) {
        is UserActionResult.ChangeScene -> toScene == Scenes.QuickSettings
        is UserActionResult.ShowOverlay -> overlay == Overlays.QuickSettingsShade
        else -> false
    }
