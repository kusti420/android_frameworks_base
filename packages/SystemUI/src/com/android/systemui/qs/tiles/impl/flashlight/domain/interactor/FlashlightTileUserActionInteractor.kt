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

package com.android.systemui.qs.tiles.impl.flashlight.domain.interactor

import android.app.ActivityManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flashlight.domain.interactor.FlashlightInteractor
import com.android.systemui.flashlight.flags.FlashlightStrength
import com.android.systemui.flashlight.shared.model.FlashlightModel
import com.android.systemui.flashlight.ui.dialog.FlashlightDialogDelegate
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.statusbar.policy.FlashlightController
import android.util.Log
import dagger.Lazy
import javax.inject.Inject

/** Handles flashlight tile clicks. */
@SysUISingleton
class FlashlightTileUserActionInteractor
@Inject
constructor(
    private val flashlightController: FlashlightController,
    private val flashlightInteractor: Lazy<FlashlightInteractor>,
    private val flashlightDialogDelegate: Lazy<FlashlightDialogDelegate>,
) : QSTileUserActionInteractor<FlashlightModel> {

    override suspend fun handleInput(input: QSTileInput<FlashlightModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    if (FlashlightStrength.isEnabled) {
                        // IchthysOS: single tap toggles the flashlight on/off
                        // (defaults to max brightness via FlashlightRepositoryImpl)
                        if (!ActivityManager.isUserAMonkey() &&
                                input.data is FlashlightModel.Available) {
                            flashlightInteractor.get().setEnabled(!input.data.enabled)
                        }
                    } else { // preserve old behavior at the cost of some redundancy
                        if (
                            !ActivityManager.isUserAMonkey() &&
                                input.data is FlashlightModel.Available
                        ) {
                            flashlightController.setFlashlight(!input.data.enabled)
                        }
                    }
                }
                is QSTileUserAction.ToggleClick -> {
                    if (
                        FlashlightStrength.isEnabled &&
                            !ActivityManager.isUserAMonkey() &&
                            data is FlashlightModel.Available // both Level and Binary
                    ) {
                        flashlightInteractor.get().setEnabled(!data.enabled)
                    }
                }
                is QSTileUserAction.LongClick -> {
                    // IchthysOS: long-press opens brightness slider for adjustable flashlights
                    if (FlashlightStrength.isEnabled && !ActivityManager.isUserAMonkey()) {
                        when (input.data) {
                            is FlashlightModel.Available.Level -> {
                                Log.d(TAG, "LongClick: Level state, showing brightness dialog")
                                // Ensure flashlight is on before showing the slider
                                if (!input.data.enabled) {
                                    flashlightInteractor.get().setEnabled(true)
                                }
                                flashlightDialogDelegate.get().showDialog(input.action.expandable)
                            }
                            else -> {
                                // Binary or unavailable — no brightness slider available
                                Log.d(TAG, "LongClick: no adjustable levels, ignoring")
                            }
                        }
                    }
                }
            }
        }

    companion object {
        private const val TAG = "FlashTileAction"
    }
}
