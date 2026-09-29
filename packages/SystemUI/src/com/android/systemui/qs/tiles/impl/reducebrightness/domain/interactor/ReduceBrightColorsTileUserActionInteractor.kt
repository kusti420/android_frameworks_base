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

package com.android.systemui.qs.tiles.impl.reducebrightness.domain.interactor

import android.content.res.Resources
import com.android.systemui.accessibility.extradim.ExtraDimDialogManager
import com.android.systemui.qs.ReduceBrightColorsController
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.dialog.colordisplay.ExtraDimDialogDelegate
import com.android.systemui.qs.tiles.impl.reducebrightness.domain.model.ReduceBrightColorsTileModel
import com.android.systemui.shade.ShadeDisplayAware
import dagger.Lazy
import javax.inject.Inject

/**
 * Handles reduce bright colors tile clicks.
 *
 * IchthysOS: a long press opens the Extra Dim slider dialog (intensity, keep-on-after-restart)
 * instead of the Settings page, which the dialog links to.
 */
class ReduceBrightColorsTileUserActionInteractor
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val reduceBrightColorsController: ReduceBrightColorsController,
    private val extraDimDialogManager: ExtraDimDialogManager,
    private val extraDimDialogDelegate: Lazy<ExtraDimDialogDelegate>,
) : QSTileUserActionInteractor<ReduceBrightColorsTileModel> {

    val isInUpgradeMode: Boolean = reduceBrightColorsController.isInUpgradeMode(resources)

    override suspend fun handleInput(input: QSTileInput<ReduceBrightColorsTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    if (isInUpgradeMode) {
                        extraDimDialogManager.dismissKeyguardIfNeededAndShowDialog(
                            action.expandable
                        )
                        return@with
                    }
                    reduceBrightColorsController.setReduceBrightColorsActivated(
                        !input.data.isEnabled
                    )
                }
                is QSTileUserAction.LongClick -> {
                    if (isInUpgradeMode) {
                        extraDimDialogManager.dismissKeyguardIfNeededAndShowDialog(
                            action.expandable
                        )
                        return@with
                    }
                    extraDimDialogDelegate.get().showDialog(action.expandable)
                }
                is QSTileUserAction.ToggleClick -> {}
            }
        }
}
