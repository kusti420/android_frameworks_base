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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.padding
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.development.ui.compose.BuildNumber
import com.android.systemui.development.ui.viewmodel.BuildNumberViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.dagger.PaginatedBaseLayoutType
import com.android.systemui.qs.panels.ui.compose.Dimensions.FooterHeight
import com.android.systemui.qs.panels.ui.compose.Dimensions.InterPageSpacing
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ShoalQsColors
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalAccent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberShoalQsEnabled
import com.android.systemui.qs.panels.ui.compose.toolbar.EditModeButton
import com.android.systemui.qs.panels.ui.viewmodel.PaginatedGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.EditModeButtonViewModel
import javax.inject.Inject

class PaginatedGridLayout
@Inject
constructor(
    private val viewModelFactory: PaginatedGridViewModel.Factory,
    @PaginatedBaseLayoutType private val delegateGridLayout: PaginatableGridLayout,
) : GridLayout by delegateGridLayout {
    @Composable
    override fun ContentScope.TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        listening: () -> Boolean,
        enableRevealEffect: Boolean,
    ) {
        val viewModel =
            rememberViewModel(traceName = "PaginatedGridLayout-TileGrid") {
                viewModelFactory.create()
            }
        val delegateGridViewModel =
            rememberViewModel(traceName = "PaginatedGridLayout-TileGrid") {
                delegateGridLayout.viewModelFactory.create()
            }

        // IchthysOS: crDroid `qs_tiles_rows` / `qs_tiles_rows_landscape`, falling back to
        // R.integer.quick_settings_paginated_grid_num_rows when unset. See QSRowsRepository.
        val rows = viewModel.rows
        // IchthysOS "Shoal": pages are filled by the Shoal layout's own capacity (wide tiles two
        // per row, a denser honeycomb), not by the stock grid's row count, which split a half-empty
        // panel onto a second page.
        val shoalPaging = rememberShoalQsEnabled()
        val shoalLarge =
            (delegateGridViewModel as? com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridViewModel)
                ?.iconTilesViewModel
                ?.largeTilesState
                ?.value
        val pages =
            remember(tiles, rows, shoalPaging, shoalLarge, *delegateGridViewModel.pageKeys) {
                if (shoalPaging && shoalLarge != null) {
                    shoalPages(tiles) { it.spec in shoalLarge }
                } else {
                    delegateGridViewModel.splitIntoPages(tiles, rows)
                }
            }

        val pagerState = rememberPagerState(0) { pages.size }

        LaunchedEffect(listening, pagerState) {
            snapshotFlow { listening() }
                .collect {
                    // Whenever we go from not listening to listening, we should be in the first
                    // page. If we did this when going from listening to not listening, opening
                    // edit mode in second page will cause it to go to first page during the
                    // transition.
                    if (listening()) {
                        pagerState.scrollToPage(0)
                    }
                }
        }

        // Used to track if this is currently in the first page or not, for animations
        LaunchedEffect(key1 = pagerState) {
            snapshotFlow { pagerState.currentPage == 0 }.collect { viewModel.inFirstPage = it }
        }

        Column(modifier) {
            val contentPaddingValue =
                if (pages.size > 1) {
                    InterPageSpacing
                } else {
                    0.dp
                }
            val contentPadding = PaddingValues(horizontal = contentPaddingValue)
            val nestedScrollConnection =
                remember(viewModel) {
                    object : NestedScrollConnection {
                        override suspend fun onPreFling(available: Velocity): Velocity {
                            viewModel.registerSideSwipeGesture()
                            return Velocity.Zero
                        }
                    }
                }

            /* Use negative padding equal with value equal to content padding. That way, each page
             * layout extends to the sides, but the content is as if there was no padding. That
             * way, the clipping bounds of the HorizontalPager extend beyond the tiles in each page.
             */
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier.sysuiResTag("qs_pager")
                        .padding(horizontal = { -contentPaddingValue.roundToPx() })
                        .nestedScroll(nestedScrollConnection),
                contentPadding = contentPadding,
                pageSpacing = if (pages.size > 1) InterPageSpacing else 0.dp,
                beyondViewportPageCount = 1,
                verticalAlignment = Alignment.Top,
            ) {
                val page = pages[it]

                with(delegateGridLayout) {
                    TileGrid(
                        tiles = page,
                        modifier = Modifier,
                        listening = listening,
                        enableRevealEffect = false,
                    )
                }
            }
            FooterBar(
                buildNumberViewModelFactory = viewModel.buildNumberViewModelFactory,
                pagerState = pagerState,
                showArrowsInPager = viewModel.showArrowsInPagerDots,
                editButtonViewModelFactory = viewModel.editModeButtonViewModelFactory,
                isVisible = { listening() && layoutState.isIdle() },
            )
        }
    }
}

private object Dimensions {
    val FooterHeight = 48.dp
    val InterPageSpacing = 16.dp
}

@Composable
private fun FooterBar(
    buildNumberViewModelFactory: BuildNumberViewModel.Factory,
    pagerState: PagerState,
    showArrowsInPager: Boolean,
    editButtonViewModelFactory: EditModeButtonViewModel.Factory,
    isVisible: () -> Boolean = { true },
) {
    val editButtonViewModel =
        rememberViewModel(traceName = "PaginatedGridLayout-editButtonViewModel") {
            editButtonViewModelFactory.create()
        }

    // Use requiredHeight so it won't be squished if the view doesn't quite fit. As this is
    // expected to be inside a scrollable container, this should not be an issue.
    // Also, we construct the layout this way to do the following:
    // * PagerDots is centered in the row, taking as much space as it needs.
    // * On the start side, we place the BuildNumber, taking as much space as it needs, but
    //   constrained by the available space left over after PagerDots.
    // * On the end side, we place the edit mode button, with the same constraints as for
    //   BuildNumber (but it will usually fit, as it's just a square button).
    Row(
        modifier = Modifier.requiredHeight(FooterHeight).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f)) {
            BuildNumber(viewModelFactory = buildNumberViewModelFactory)
            Spacer(modifier = Modifier.weight(1f))
        }
        // IchthysOS "Shoal": an 18x6 accent pill for the current page, 6 dp #2a3136 dots.
        val shoal = rememberShoalQsEnabled()
        val shoalAccent = rememberShoalAccent()
        PagerDots(
            pagerState = pagerState,
            activeColor =
                if (shoal) shoalAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            nonActiveColor =
                if (shoal) ShoalQsColors.DotInactive
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f),
            modifier = Modifier.wrapContentWidth(),
            showArrows = showArrowsInPager,
            clickToCyclePages = !showArrowsInPager,
            spaceSize = if (shoal) 6.dp else 4.dp,
            activeDotWidth = if (shoal) 18.dp else 12.dp,
        )
        Row(Modifier.weight(1f)) {
            Spacer(modifier = Modifier.weight(1f))
            EditModeButton(viewModel = editButtonViewModel, isVisible = isVisible())
        }
    }
}

/** Shoal page capacity: up to 4 rows of wide tiles and a 6/5/6 honeycomb per page. */
private const val SHOAL_WIDE_PER_PAGE = 8
private const val SHOAL_SMALL_PER_PAGE = 17

private fun shoalPages(
    tiles: List<TileViewModel>,
    isLarge: (TileViewModel) -> Boolean,
): List<List<TileViewModel>> {
    val pages = mutableListOf<MutableList<TileViewModel>>()
    var wide = 0
    var small = 0
    for (tile in tiles) {
        val large = isLarge(tile)
        val full = if (large) wide >= SHOAL_WIDE_PER_PAGE else small >= SHOAL_SMALL_PER_PAGE
        if (pages.isEmpty() || full) {
            pages.add(mutableListOf())
            wide = 0
            small = 0
        }
        pages.last().add(tile)
        if (large) wide++ else small++
    }
    return pages
}
