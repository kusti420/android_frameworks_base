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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.data.repository.QSPreferencesRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.TilesUpgradePath
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class QSPreferencesInteractor @Inject constructor(private val repo: QSPreferencesRepository) {
    val largeTilesSpecs: Flow<Set<TileSpec>> = repo.largeTilesSpecs
    val editTooltipShown: Flow<Boolean> = repo.editTooltipShown
    /** IchthysOS "Shoal": tiles in the junction slots, top to bottom; null is a free slot. */
    val junctionTilesSpecs: Flow<List<TileSpec?>> = repo.junctionTilesSpecs
    /** IchthysOS: tiles picked for the collapsed panel, in order; empty while none are picked. */
    val collapsedTilesSpecs: Flow<List<TileSpec>> = repo.collapsedTilesSpecs
    /** IchthysOS: the tiles that are in the tile list for the collapsed panel only. */
    val collapsedOnlyTilesSpecs: Flow<Set<TileSpec>> = repo.collapsedOnlyTilesSpecs
    /** IchthysOS: tiles per row of the collapsed panel. */
    val collapsedColumns: Flow<Int> = repo.collapsedColumns

    fun setLargeTilesSpecs(specs: Set<TileSpec>) {
        repo.writeLargeTileSpecs(specs)
    }

    fun removeLargeTilesSpecs(specs: Set<TileSpec>) {
        repo.removeLargeTileSpecs(specs)
    }

    fun setJunctionTilesSpecs(slots: List<TileSpec?>) {
        repo.writeJunctionTileSpecs(slots)
    }

    fun removeJunctionTilesSpecs(specs: Set<TileSpec>) {
        repo.removeJunctionTileSpecs(specs)
    }

    fun setCollapsedTilesSpecs(specs: List<TileSpec>) {
        repo.writeCollapsedTileSpecs(specs)
    }

    fun removeCollapsedTilesSpecs(specs: Set<TileSpec>) {
        repo.removeCollapsedTileSpecs(specs)
    }

    fun setCollapsedOnlyTilesSpecs(specs: Set<TileSpec>) {
        repo.writeCollapsedOnlyTileSpecs(specs)
    }

    fun removeCollapsedOnlyTilesSpecs(specs: Set<TileSpec>) {
        repo.removeCollapsedOnlyTileSpecs(specs)
    }

    /** Null goes back to the default. */
    fun setCollapsedColumns(columns: Int?) {
        repo.writeCollapsedColumns(columns)
    }

    fun setEditTooltipShown(value: Boolean) {
        repo.writeEditTooltipShown(value)
    }

    /**
     * This method should be called to indicate that a "new" set of tiles has been determined for a
     * particular user coming from different upgrade sources.
     *
     * @see TilesUpgradePath for more information
     */
    fun setInitialOrUpgradeLargeTilesSpecs(specs: TilesUpgradePath, user: Int) {
        repo.setInitialOrUpgradeLargeTiles(specs, user)
    }
}
