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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.PaginatableGridLayout
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.bounceableInfo
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackContentViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.res.R
import com.android.systemui.shade.shared.flag.DualShadeFlag
import javax.inject.Inject
import kotlinx.coroutines.launch

@SysUISingleton
class InfiniteGridLayout
@Inject
constructor(
    private val detailsViewModel: DetailsViewModel,
    private val iconTilesViewModel: IconTilesViewModel,
    override val viewModelFactory: InfiniteGridViewModel.Factory,
    private val textFeedbackContentViewModelFactory: TextFeedbackContentViewModel.Factory,
    private val tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
) : PaginatableGridLayout {

    @Composable
    override fun ContentScope.TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        listening: () -> Boolean,
        enableRevealEffect: Boolean,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid") {
                viewModelFactory.create()
            }

        val context = LocalContext.current
        val textFeedbackViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid", key = context) {
                textFeedbackContentViewModelFactory.create(context)
            }

        val columns = viewModel.columnsWithMediaViewModel.columns
        val largeTilesSpan = viewModel.columnsWithMediaViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState
        val junctionTiles by viewModel.iconTilesViewModel.junctionTilesState
        // Tiles or largeTiles may be updated while this is composed, so listen to any changes
        // Android 8.1-style QS: every tile occupies a single uniform grid cell (no large tiles),
        // rendered as icon-over-label. largeTiles/largeTilesSpan are intentionally still read so
        // the memo invalidates on config changes.
        val sizedTiles =
            remember(tiles, largeTiles, largeTilesSpan) {
                tiles.map { SizedTileImpl(it, 1) }
            }
        val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        // IchthysOS "Shoal": wide connectivity tiles + honeycomb of icon circles.
        if (rememberShoalQsEnabled()) {
            ShoalTileGrid(
                tiles = tiles,
                largeTiles = largeTiles,
                junctionTiles = junctionTiles,
                squishiness = { squishiness },
                coroutineScope = scope,
                tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                detailsViewModel = detailsViewModel,
                listening = listening,
                requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                enableRevealEffect = enableRevealEffect,
                modifier = modifier,
            )
            TileListener(tiles, listening)
            return
        }

        val bounceables =
            remember(sizedTiles) { List(sizedTiles.size) { BounceableTileViewModel() } }
        val spans by remember(sizedTiles) { derivedStateOf { sizedTiles.fastMap { it.width } } }
        VerticalSpannedGrid(
            columns = columns,
            columnSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal),
            rowSpacing = dimensionResource(R.dimen.qs_tile_margin_vertical),
            spans = spans,
            keys = { sizedTiles[it].tile.spec },
            modifier = modifier,
        ) { spanIndex, column, isFirstInColumn, isLastInColumn ->
            val it = sizedTiles[spanIndex]

            Element(it.tile.spec.toElementKey(), Modifier) {
                Tile(
                    tile = it.tile,
                    iconOnly = false,
                    verticalLabel = true,
                    squishiness = { squishiness },
                    tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                    coroutineScope = scope,
                    bounceableInfo =
                        bounceables.bounceableInfo(
                            it,
                            index = spanIndex,
                            column = column,
                            columns = columns,
                            isFirstInRow = isFirstInColumn,
                            isLastInRow = isLastInColumn,
                        ),
                    detailsViewModel = detailsViewModel,
                    isVisible = listening,
                    requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                    enableRevealEffect = enableRevealEffect,
                )
            }
        }

        TileListener(tiles, listening)
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit,
        onSetTiles: (List<TileSpec>) -> Unit,
        onStopEditing: () -> Unit,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModelFactory.create()
            }
        val columnsViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModel.columnsWithMediaViewModelFactory.createWithoutMediaTracking()
            }
        val snapshotViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.snapshotViewModelFactory.create()
            }
        val topBarActionsViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.editTopBarActionsViewModelFactory.create()
            }
        val scrollState = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()
        val dialogDelegate =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.resetDialogDelegateFactory.create {
                    // Clear the stack of snapshots on reset
                    snapshotViewModel.clearStack()

                    // Automatically scroll to the top on reset
                    coroutineScope.launch { scrollState.animateScrollTo(0) }
                }
            }
        val showDualShadeSetting =
            DualShadeFlag.isEnabled &&
                LocalResources.current.getBoolean(
                    com.android.settingslib.R.bool.config_useDualShadeSetting
                )
        val actions =
            remember(topBarActionsViewModel, showDualShadeSetting) {
                topBarActionsViewModel.actions(showDualShadeSetting).toMutableStateList()
            }
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState

        val currentTiles by rememberUpdatedState(tiles.filter { it.isCurrent })

        // IchthysOS "Shoal" has its own editor, which shows the panel as it is laid out (big
        // tiles, junctions, honeycomb) instead of a grid of cells, and the collapsed panel on a
        // page of its own. It shares the snapshots (undo) and the reset dialog with the stock
        // editor below.
        if (rememberShoalQsEnabled()) {
            val junctionTiles by viewModel.iconTilesViewModel.junctionTilesState
            // The collapsed panel (quick quick settings): which tiles it shows, which of those
            // the expanded panel does not have, and how many per row on how many rows.
            val collapsedTiles by viewModel.iconTilesViewModel.collapsedTilesState
            val collapsedOnlyTiles by viewModel.iconTilesViewModel.collapsedOnlyTilesState
            val collapsedColumns by viewModel.collapsedColumnsState
            val collapsedRows by viewModel.collapsedRowsState
            ShoalEditTileGrid(
                tiles = tiles,
                largeTiles = largeTiles,
                junctionTiles = junctionTiles,
                collapsedTiles = collapsedTiles,
                collapsedOnlyTiles = collapsedOnlyTiles,
                collapsedColumns = collapsedColumns,
                collapsedRows = collapsedRows,
                collapsedColumnsRange = viewModel.collapsedColumnsRange,
                collapsedRowsRange = viewModel.collapsedRowsRange,
                canUndo = snapshotViewModel.canUndo,
                topBarActions = actions,
                scrollState = scrollState,
                onUndo = snapshotViewModel::undo,
                onReset = { dialogDelegate.showDialog() },
                onStopEditing = onStopEditing,
                onCollapsedColumns = viewModel::setCollapsedColumns,
                onCollapsedRows = viewModel::setCollapsedRows,
                onLayout = { layout ->
                    // The current tiles here are the expanded panel's; the collapsed panel's
                    // go into the snapshot as they stand.
                    snapshotViewModel.takeSnapshot(
                        currentTiles.map { it.tileSpec },
                        largeTiles,
                        junctionTiles,
                    )
                    // Slots and sizes first: both are in place by the time the new tile list
                    // (written asynchronously) arrives, so a new tile shows up where it belongs.
                    iconTilesViewModel.setShoalLayout(layout.big.toSet(), layout.slots)
                    // Then the tiles of both panels in one go, not through onSetTiles: the two
                    // panels share the tile list, and which of its tiles are there for the
                    // collapsed panel only can only be told from both.
                    iconTilesViewModel.setPanelTiles(layout.tileSpecs(), layout.collapsed)
                },
                modifier = modifier,
            )
            return
        }

        val columns = columnsViewModel.columns
        val largeTilesSpan = columnsViewModel.largeSpan
        val listState =
            remember(columns, largeTilesSpan) {
                EditTileListState(
                    currentTiles,
                    largeTiles,
                    columns = columns,
                    largeTilesSpan = largeTilesSpan,
                )
            }
        LaunchedEffect(currentTiles, largeTiles) { listState.updateTiles(currentTiles, largeTiles) }

        DefaultEditTileGrid(
            listState = listState,
            allTiles = tiles,
            modifier = modifier,
            scrollState = scrollState,
            snapshotViewModel = snapshotViewModel,
            onStopEditing = onStopEditing,
            topBarActions = actions,
        ) { action ->
            // Opening the dialog doesn't require a snapshot
            if (action != EditAction.ResetGrid) {
                snapshotViewModel.takeSnapshot(currentTiles.map { it.tileSpec }, largeTiles)
            }

            when (action) {
                is EditAction.AddTile -> {
                    onAddTile(action.tileSpec, listState.tileSpecs().size)
                }
                is EditAction.InsertTile -> {
                    onAddTile(action.tileSpec, action.position)
                }
                is EditAction.RemoveTile -> {
                    onRemoveTile(action.tileSpec)
                }
                EditAction.ResetGrid -> {
                    dialogDelegate.showDialog()
                }
                is EditAction.ResizeTile -> {
                    iconTilesViewModel.resize(action.tileSpec, action.toIcon)
                }
                is EditAction.SetTiles -> {
                    onSetTiles(action.tileSpecs)
                }
            }
        }
    }
}
