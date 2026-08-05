/*
 * Copyright (C) 2026 The IchthysOS Project
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

import android.content.res.Configuration
import android.content.res.Resources
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.kotlin.mapDirect
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.settings.SystemSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * IchthysOS: number of tile rows per page of the expanded Quick Settings grid.
 *
 * Backs crDroid's `qs_tiles_rows` / `qs_tiles_rows_landscape` (Settings.System,
 * `qs_layout_settings.xml`, ranges 2..5 and 1..3). Nothing in SystemUI read either key before, so
 * both sliders were inert.
 *
 * The value feeds `PaginatedGridLayout`, which is the grid the shade actually uses:
 * `GridLayoutTypeRepository.defaultLayoutType` is `PaginatedGridLayoutType` for the single and split
 * shade, and only the (unused here) dual shade gets the un-paginated infinite grid.
 */
@SysUISingleton
class QSRowsRepository
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    @ShadeDisplayAware configurationRepository: ConfigurationRepository,
    private val systemSettings: SystemSettings,
) {
    val rows: Flow<Int> =
        merge(
                configurationRepository.onConfigurationChange,
                systemSettings.observerFlow(
                    UserHandle.USER_CURRENT,
                    Settings.System.QS_TILES_ROWS,
                    Settings.System.QS_TILES_ROWS_LANDSCAPE,
                ),
            )
            .emitOnStart()
            .mapDirect { currentRows }

    val defaultRows: Int
        get() = currentRows

    private val currentRows: Int
        get() {
            val resourceRows =
                resources.getInteger(R.integer.quick_settings_paginated_grid_num_rows)
            val landscape =
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val key =
                if (landscape) Settings.System.QS_TILES_ROWS_LANDSCAPE
                else Settings.System.QS_TILES_ROWS
            // USER_CURRENT, never the resolver's own user: built from a per-display context.
            val userRows = systemSettings.getIntForUser(key, UNSET, UserHandle.USER_CURRENT)
            if (userRows == UNSET) {
                // Clamp the user's value only — never the resource fallback, which a device overlay
                // is allowed to set outside the slider's range.
                return resourceRows
            }
            val min = if (landscape) MIN_ROWS_LANDSCAPE else MIN_ROWS_PORTRAIT
            val max = if (landscape) MAX_ROWS_LANDSCAPE else MAX_ROWS_PORTRAIT
            return userRows.coerceIn(min, max)
        }

    private companion object {
        const val UNSET = -1
        // Mirrors settings:min / android:max on the crDroid seek bars.
        const val MIN_ROWS_PORTRAIT = 2
        const val MAX_ROWS_PORTRAIT = 8
        const val MIN_ROWS_LANDSCAPE = 1
        const val MAX_ROWS_LANDSCAPE = 5
    }
}
