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

package com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor.model.UiModeNightTileModel
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Handles ui mode night tile clicks.
 *
 * IchthysOS: a tap cycles Off -> On -> Scheduled -> Off, like the Night Light tile. Scheduled is
 * whichever schedule Settings has set up (sunset to sunrise, custom hours or bedtime): the tile
 * remembers the one it left and goes back to it, sunset to sunrise if it never saw one.
 */
class UiModeNightTileUserActionInteractor
@Inject
constructor(
    @Background private val backgroundContext: CoroutineContext,
    private val uiModeManager: UiModeManager,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    @Application private val context: Context,
) : QSTileUserActionInteractor<UiModeNightTileModel> {

    override suspend fun handleInput(input: QSTileInput<UiModeNightTileModel>) =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    if (!input.data.isPowerSave) {
                        withContext(backgroundContext) {
                            // The mode as it is now, not as the tile last drew it.
                            when (val mode = uiModeManager.nightMode) {
                                UiModeManager.MODE_NIGHT_NO ->
                                    uiModeManager.setNightMode(UiModeManager.MODE_NIGHT_YES)
                                UiModeManager.MODE_NIGHT_YES -> enterSchedule()
                                else -> {
                                    // Scheduled (sunset to sunrise, or custom): remember which.
                                    rememberSchedule(mode, uiModeManager.nightModeCustomType)
                                    uiModeManager.setNightMode(UiModeManager.MODE_NIGHT_NO)
                                }
                            }
                        }
                    }
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.expandable,
                        Intent(Settings.ACTION_DARK_THEME_SETTINGS),
                    )
                }
                is QSTileUserAction.ToggleClick -> {}
            }
        }

    /**
     * The custom type of the schedule the tile last left, per user; unset or
     * [UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN] means sunset to sunrise.
     */
    private fun rememberSchedule(uiMode: Int, customType: Int) {
        Settings.Secure.putIntForUser(
            context.contentResolver,
            KEY_LAST_SCHEDULE,
            if (uiMode == UiModeManager.MODE_NIGHT_CUSTOM) customType
            else UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN,
            UserHandle.USER_CURRENT,
        )
    }

    private fun enterSchedule() {
        val customType =
            Settings.Secure.getIntForUser(
                context.contentResolver,
                KEY_LAST_SCHEDULE,
                UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN,
                UserHandle.USER_CURRENT,
            )
        if (customType != UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN) {
            // Also switches to MODE_NIGHT_CUSTOM; the hours themselves are kept by the system.
            uiModeManager.setNightModeCustomType(customType)
        } else {
            uiModeManager.setNightMode(UiModeManager.MODE_NIGHT_AUTO)
        }
    }

    private companion object {
        const val KEY_LAST_SCHEDULE = "ichthys_dark_theme_last_schedule"
    }
}
