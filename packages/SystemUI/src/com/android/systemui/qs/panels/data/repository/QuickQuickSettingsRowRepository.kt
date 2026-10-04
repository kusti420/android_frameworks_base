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

package com.android.systemui.qs.panels.data.repository

import android.content.res.Resources
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.settings.SystemSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

@SysUISingleton
class QuickQuickSettingsRowRepository
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    @ShadeDisplayAware configurationRepository: ConfigurationRepository,
    private val systemSettings: SystemSettings,
) {
    val rows =
        merge(
                configurationRepository.onConfigurationChange,
                systemSettings.observerFlow(UserHandle.USER_CURRENT, Settings.System.QQS_TILES_ROWS),
            )
            .emitOnStart()
            .map { currentRows }

    /**
     * IchthysOS: writes the user's row count, the same key the crDroid slider and the ichthys.nix
     * option quickSettings.layout.qqsTilesRows write. The tile editor sets it.
     */
    fun setRows(rows: Int) {
        systemSettings.putIntForUser(
            Settings.System.QQS_TILES_ROWS,
            rows.coerceIn(MIN_ROWS, MAX_ROWS),
            UserHandle.USER_CURRENT,
        )
    }

    /**
     * IchthysOS: crDroid's `qqs_tiles_rows` (Settings.System, `qs_layout_settings.xml`, range 1..5)
     * — the number of tile rows shown in the collapsed Quick Quick Settings header.
     *
     * Unset is one row. The header only ever drew one row whatever this said (and whatever the
     * `quick_qs_paginated_grid_num_rows` resource said), so one row is what an untouched panel
     * has to keep now that the value is honoured.
     */
    private val currentRows: Int
        get() {
            // USER_CURRENT, never the resolver's own user: built from a per-display context.
            val userRows =
                systemSettings.getIntForUser(
                    Settings.System.QQS_TILES_ROWS,
                    UNSET,
                    UserHandle.USER_CURRENT,
                )
            return if (userRows == UNSET) DEFAULT_ROWS else userRows.coerceIn(MIN_ROWS, MAX_ROWS)
        }

    companion object {
        private const val UNSET = -1
        private const val DEFAULT_ROWS = 1
        // Mirrors settings:min / android:max on the crDroid seek bar.
        const val MIN_ROWS = 1
        const val MAX_ROWS = 5
    }
}
