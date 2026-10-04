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

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QQS
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.qs.panels.domain.interactor.QuickQuickSettingsRowInteractor
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class QuickQuickSettingsViewModel
@AssistedInject
constructor(
    tilesInteractor: CurrentTilesInteractor,
    qsColumnsViewModelFactory: QSColumnsViewModel.Factory,
    quickQuickSettingsRowInteractor: QuickQuickSettingsRowInteractor,
    mediaInRowInLandscapeViewModelFactory: MediaInRowInLandscapeViewModel.Factory,
    val squishinessViewModel: TileSquishinessViewModel,
    iconTilesViewModel: IconTilesViewModel,
    val tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
) : HydratedActivatable() {

    private val qsColumnsViewModel = qsColumnsViewModelFactory.create(LOCATION_QQS, mediaUiBehavior)
    private val mediaInRowViewModel =
        mediaInRowInLandscapeViewModelFactory.create(LOCATION_QQS, mediaUiBehavior)

    /**
     * IchthysOS: tiles per row as laid out. This is the collapsed panel's own count
     * (quickSettings.collapsed.columns), not the columns of the expanded grid. Next to the media
     * player the panel has half the width, so it takes half the tiles per row (rounded up, so that
     * an odd count still fits on twice the rows).
     */
    val columns: Int
        get() =
            if (mediaInRowViewModel.shouldMediaShowInRow) {
                (columnsWithoutMedia + 1) / 2
            } else {
                columnsWithoutMedia
            }

    private val columnsWithoutMedia by
        quickQuickSettingsRowInteractor.columns.hydratedStateOf(
            initialValue = quickQuickSettingsRowInteractor.defaultColumns
        )

    private val largeTiles by iconTilesViewModel.largeTiles.hydratedStateOf()

    private val collapsedTiles by iconTilesViewModel.collapsedTiles.hydratedStateOf()

    private val rowsWithoutMedia by
        quickQuickSettingsRowInteractor.rows.hydratedStateOf(
            initialValue = quickQuickSettingsRowInteractor.defaultRows
        )

    private val largeTilesSpan: Int
        get() = qsColumnsViewModel.largeSpan

    private val currentTiles by tilesInteractor.currentTiles.hydratedStateOf()

    val tileViewModels: List<SizedTile<TileViewModel>> by derivedStateOf {
        // IchthysOS: the tiles picked for the collapsed panel, as far as they are in quick
        // settings. With none picked (or none of them left) it is the first tiles of the tile
        // list, as it was before the panel had a selection of its own.
        val bySpec = currentTiles.associateBy { it.spec }
        val picked = collapsedTiles.mapNotNull { bySpec[it] }
        picked
            .ifEmpty { currentTiles }
            // As many as the user's tiles per row and rows hold. Next to the media player these
            // are the same tiles: [columns] is halved there, so they wrap onto twice the rows.
            .take(columnsWithoutMedia * rowsWithoutMedia)
            // Android 8.1-style QQS: uniform single-cell (icon-only) tiles.
            .map { SizedTileImpl(TileViewModel(it.tile, it.spec, it.expandable), 1) }
    }

    override suspend fun onActivated() {
        coroutineScope {
            launch { qsColumnsViewModel.activate() }
            launch { mediaInRowViewModel.activate() }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): QuickQuickSettingsViewModel
    }

    private fun TileSpec.width(): Int = if (largeTiles.contains(this)) largeTilesSpan else 1

    companion object {
        /** Behavior of the media carousel in quick quick settings */
        @VisibleForTesting
        val mediaUiBehavior: MediaUiBehavior
            get() =
                MediaUiBehavior(
                    isCarouselDismissible = true,
                    carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
                )
    }
}
