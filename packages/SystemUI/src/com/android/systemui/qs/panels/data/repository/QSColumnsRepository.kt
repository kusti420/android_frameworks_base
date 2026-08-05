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

@SysUISingleton
class QSColumnsRepository
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    @ShadeDisplayAware configurationRepository: ConfigurationRepository,
    private val systemSettings: SystemSettings,
) {
    val splitShadeColumns: Flow<Int> =
        configurationRepository.onConfigurationChange.emitOnStart().mapDirect {
            resources.getInteger(R.integer.quick_settings_split_shade_num_columns)
        }
    val dualShadeColumns: Flow<Int> =
        configurationRepository.onConfigurationChange.emitOnStart().mapDirect {
            resources.getInteger(R.integer.quick_settings_dual_shade_num_columns)
        }

    /**
     * IchthysOS: re-emits on a configuration change (portrait <-> landscape, density, ...) *and* on
     * a write to either crDroid column key, so moving the "QS columns" slider updates the shade
     * without a SystemUI restart.
     */
    val columns: Flow<Int> =
        merge(
                configurationRepository.onConfigurationChange,
                systemSettings.observerFlow(
                    UserHandle.USER_CURRENT,
                    Settings.System.QS_TILES_COLUMNS,
                    Settings.System.QS_TILES_COLUMNS_LANDSCAPE,
                ),
            )
            .emitOnStart()
            .mapDirect { currentColumns }

    val defaultColumns: Int
        get() = currentColumns

    /**
     * IchthysOS: crDroid's `qs_tiles_columns` / `qs_tiles_columns_landscape` (Settings.System,
     * `qs_layout_settings.xml`, range 2..6). Nothing in SystemUI read these before, so both sliders
     * were inert.
     *
     * In this tree every tile occupies a single grid cell (`InfiniteGridLayout` packs
     * `SizedTileImpl(tile, 1)` for the 8.1-style grid), so the grid's column count *is* the number
     * of tiles per row and the crDroid value can be applied verbatim.
     *
     * When the key is unset we fall back to the per-orientation resource, so an untouched device
     * keeps the stock 6/8-column layout rather than snapping to crDroid's slider default of 4.
     */
    private val currentColumns: Int
        get() {
            val resourceColumns =
                resources.getInteger(R.integer.quick_settings_infinite_grid_num_columns)
            val landscape =
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val key =
                if (landscape) Settings.System.QS_TILES_COLUMNS_LANDSCAPE
                else Settings.System.QS_TILES_COLUMNS
            // USER_CURRENT, never the resolver's own user: this repository is built from a
            // @ShadeDisplayAware (per-display) context whose user is not the foreground user.
            val userColumns = systemSettings.getIntForUser(key, UNSET, UserHandle.USER_CURRENT)
            // The clamp applies to the user's value only. Clamping the resource fallback too would
            // silently rewrite a device/config-overlay column count that is outside the slider's
            // range (portrait ships 6, landscape 8) into something the ROM never asked for.
            return if (userColumns == UNSET) resourceColumns
            else userColumns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        }

    private companion object {
        const val UNSET = -1
        // Mirrors settings:min / android:max on the crDroid seek bars.
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 10
    }
}
