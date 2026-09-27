/*
 * Copyright (C) 2026 IchthysOS
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

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.bounceableInfo
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope

/**
 * IchthysOS "Shoal" quick settings (Settings.Secure ichthys_qs_style = "shoal", the ichthys.nix
 * option quickSettings.style): connectivity tiles as wide two-column tiles with their subtitle,
 * every other tile as an icon-only circle in an offset honeycomb (rows of 5 over 4, each lower row
 * rising 8 dp into the one above) - the same lattice motif as the Shoal status bar.
 */
internal object ShoalQs {
    const val STYLE_SETTING = "ichthys_qs_style"
    /** Tiles that get a wide slot (in their current order), at most [MAX_WIDE]. */
    val WIDE_SPECS = setOf("internet", "wifi", "bt", "vpn", "cell", "cellular")
    const val MAX_WIDE = 4
    val CELL = 58.dp
    val GAP = 10.dp
    val OVERLAP = 8.dp
}

/** Live value of the QS style setting for the current user. */
@Composable
internal fun rememberShoalQsEnabled(): Boolean {
    val context = LocalContext.current
    val enabled by
        produceState(initialValue = readShoal(context), context) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = readShoal(context)
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(ShoalQs.STYLE_SETTING),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            awaitDispose { context.contentResolver.unregisterContentObserver(observer) }
        }
    return enabled
}

private fun readShoal(context: android.content.Context): Boolean =
    "shoal" ==
        Settings.Secure.getStringForUser(
            context.contentResolver,
            ShoalQs.STYLE_SETTING,
            UserHandle.USER_CURRENT,
        )

@Composable
internal fun ContentScope.ShoalTileGrid(
    tiles: List<TileViewModel>,
    squishiness: () -> Float,
    coroutineScope: CoroutineScope,
    tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    detailsViewModel: DetailsViewModel,
    listening: () -> Boolean,
    requestToggleTextFeedback: (TileSpec) -> Unit,
    enableRevealEffect: Boolean,
    modifier: Modifier = Modifier,
) {
    val split =
        remember(tiles) {
            val wide = tiles.filter { it.spec.spec in ShoalQs.WIDE_SPECS }.take(ShoalQs.MAX_WIDE)
            wide to tiles.filterNot { it in wide }
        }
    val (wide, small) = split
    val wideBounceables = remember(wide) { List(wide.size) { BounceableTileViewModel() } }
    val smallBounceables = remember(small) { List(small.size) { BounceableTileViewModel() } }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (wide.isNotEmpty()) {
            val sized = remember(wide) { wide.map { SizedTileImpl(it, 1) } }
            VerticalSpannedGrid(
                columns = 2,
                columnSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal),
                rowSpacing = dimensionResource(R.dimen.qs_tile_margin_vertical),
                spans = sized.map { it.width },
                keys = { sized[it].tile.spec },
            ) { index, column, isFirstInColumn, isLastInColumn ->
                val sizedTile = sized[index]
                Element(sizedTile.tile.spec.toElementKey(), Modifier) {
                    Tile(
                        tile = sizedTile.tile,
                        iconOnly = false,
                        verticalLabel = false,
                        squishiness = squishiness,
                        tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                        coroutineScope = coroutineScope,
                        bounceableInfo =
                            wideBounceables.bounceableInfo(
                                sizedTile,
                                index = index,
                                column = column,
                                columns = 2,
                                isFirstInRow = isFirstInColumn,
                                isLastInRow = isLastInColumn,
                            ),
                        detailsViewModel = detailsViewModel,
                        isVisible = listening,
                        requestToggleTextFeedback = requestToggleTextFeedback,
                        enableRevealEffect = enableRevealEffect,
                    )
                }
            }
        }
        if (small.isNotEmpty()) {
            HoneycombLayout(modifier = Modifier.fillMaxWidth()) {
                small.forEachIndexed { index, tile ->
                    val (column, rowSize) = honeycombPosition(index)
                    Box(modifier = Modifier.clip(CircleShape)) {
                        Element(tile.spec.toElementKey(), Modifier) {
                            Tile(
                                tile = tile,
                                iconOnly = true,
                                squishiness = squishiness,
                                tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                                coroutineScope = coroutineScope,
                                bounceableInfo =
                                    smallBounceables.bounceableInfo(
                                        SizedTileImpl(tile, 1),
                                        index = index,
                                        column = column,
                                        columns = rowSize,
                                        isFirstInRow = column == 0,
                                        isLastInRow = column == rowSize - 1,
                                    ),
                                detailsViewModel = detailsViewModel,
                                isVisible = listening,
                                requestToggleTextFeedback = requestToggleTextFeedback,
                                enableRevealEffect = enableRevealEffect,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** (column within its row, row size) for honeycomb item [index]: rows alternate 5 and 4. */
private fun honeycombPosition(index: Int): Pair<Int, Int> {
    var remaining = index
    var row = 0
    while (true) {
        val size = if (row % 2 == 0) 5 else 4
        if (remaining < size) return remaining to size
        remaining -= size
        row++
    }
}

/** Rows of 5 then 4 circles, odd rows offset by half a pitch and pulled up by [overlap]. */
@Composable
private fun HoneycombLayout(
    modifier: Modifier = Modifier,
    cell: Dp = ShoalQs.CELL,
    gap: Dp = ShoalQs.GAP,
    overlap: Dp = ShoalQs.OVERLAP,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val overlapPx = overlap.roundToPx()
        val maxWidth =
            if (constraints.hasBoundedWidth) constraints.maxWidth else (cell.roundToPx() * 6)
        val cellPx = minOf(cell.roundToPx(), (maxWidth - 4 * gapPx) / 5).coerceAtLeast(1)
        val placeables = measurables.map { it.measure(Constraints.fixed(cellPx, cellPx)) }
        val pitch = cellPx + gapPx
        val fullRowWidth = 5 * cellPx + 4 * gapPx
        val startX = (maxWidth - fullRowWidth) / 2
        val positions = ArrayList<Pair<Int, Int>>(placeables.size)
        var row = 0
        var inRow = 0
        for (i in placeables.indices) {
            val rowSize = if (row % 2 == 0) 5 else 4
            val rowX = startX + if (row % 2 == 0) 0 else pitch / 2
            positions += (rowX + inRow * pitch) to (row * (cellPx - overlapPx))
            inRow++
            if (inRow == rowSize) {
                inRow = 0
                row++
            }
        }
        val rows = if (inRow == 0) row else row + 1
        val height = if (rows == 0) 0 else rows * (cellPx - overlapPx) + overlapPx
        layout(maxWidth, height) {
            placeables.forEachIndexed { i, placeable ->
                placeable.place(positions[i].first, positions[i].second)
            }
        }
    }
}
