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

package com.android.systemui.qs.tiles.impl.night.domain.interactor

import com.android.systemui.accessibility.qs.QSAccessibilityModule
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.logging.QSTileLogger
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.dialog.colordisplay.IchthysNightLightController
import com.android.systemui.qs.tiles.dialog.colordisplay.NightLightDialogDelegate
import com.android.systemui.qs.tiles.impl.night.domain.model.NightDisplayTileModel
import dagger.Lazy
import javax.inject.Inject

/**
 * Handles night display tile clicks.
 *
 * IchthysOS: a tap cycles Off -> On -> Scheduled -> Off (see [IchthysNightLightController]); a long
 * press opens the Night Light slider dialog instead of the Settings page (which the dialog links
 * to).
 */
class NightDisplayTileUserActionInteractor
@Inject
constructor(
    private val nightLightController: IchthysNightLightController,
    private val nightLightDialogDelegate: Lazy<NightLightDialogDelegate>,
    private val qsLogger: QSTileLogger,
) : QSTileUserActionInteractor<NightDisplayTileModel> {
    override suspend fun handleInput(input: QSTileInput<NightDisplayTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    val current = data.ichthysMode()
                    qsLogger.logInfo(spec, "Night Light: cycling from $current")
                    nightLightController.cycle(current)
                }
                is QSTileUserAction.LongClick -> {
                    nightLightDialogDelegate.get().showDialog(action.expandable)
                }
                is QSTileUserAction.ToggleClick -> {}
            }
        }

    companion object {
        val spec = TileSpec.create(QSAccessibilityModule.NIGHT_DISPLAY_TILE_SPEC)

        /** The IchthysOS three-state mode of a tile model. */
        fun NightDisplayTileModel.ichthysMode(): IchthysNightLightController.Mode =
            when (this) {
                is NightDisplayTileModel.AutoModeOff ->
                    if (isActivated) IchthysNightLightController.Mode.ON
                    else IchthysNightLightController.Mode.OFF
                is NightDisplayTileModel.AutoModeCustom,
                is NightDisplayTileModel.AutoModeTwilight ->
                    IchthysNightLightController.Mode.SCHEDULED
            }
    }
}
