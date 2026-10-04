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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QQS
import com.android.systemui.media.controls.ui.controller.MediaLocation
import com.android.systemui.media.controls.ui.controller.mediaHostStatesManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.remedia.data.repository.setHasMedia
import com.android.systemui.qs.composefragment.dagger.usingMediaInComposeFragment
import com.android.systemui.qs.panels.data.repository.QSPreferencesRepository
import com.android.systemui.qs.panels.domain.interactor.qsPreferencesInteractor
import com.android.systemui.qs.pipeline.domain.interactor.currentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QuickQuickSettingsViewModelTest : SysuiTestCase() {

    private val tiles =
        listOf(
                "$PREFIX_SMALL:1",
                "$PREFIX_SMALL:2",
                "$PREFIX_LARGE:3",
                "$PREFIX_SMALL:4",
                "$PREFIX_LARGE:5",
                "$PREFIX_LARGE:6",
                "$PREFIX_SMALL:7",
                "$PREFIX_SMALL:8",
                "$PREFIX_LARGE:9",
            )
            .map(TileSpec::create)

    private val kosmos =
        testKosmos().apply {
            qsPreferencesInteractor.setLargeTilesSpecs(
                tiles.filter { it.spec.startsWith(PREFIX_LARGE) }.toSet()
            )
            testCase.context.orCreateTestableResources.addOverride(
                R.integer.quick_settings_infinite_grid_num_columns,
                4,
            )
            fakeConfigurationRepository.onConfigurationChange()
            usingMediaInComposeFragment = true
        }

    private val underTest =
        kosmos.quickQuickSettingsViewModelFactory.create().apply { activateIn(kosmos.testScope) }

    @Before
    fun setUp() {
        kosmos.setTiles(tiles)
    }

    // IchthysOS: the collapsed panel has its own tiles per row (7 while ichthys_qqs_columns is
    // unset) instead of the expanded grid's columns, and every tile takes one place. It shows as
    // many tiles as its tiles per row times its rows hold.

    @Test
    fun tiles_asManyAsColumnsTimesRows() =
        with(kosmos) {
            testScope.runTest {
                setRows(1)

                assertThat(underTest.columns).isEqualTo(DEFAULT_COLUMNS)
                assertThat(underTest.tileViewModels.map { it.tile.spec })
                    .isEqualTo(tiles.take(DEFAULT_COLUMNS))
            }
        }

    @Test
    fun changeRows_tilesChange() =
        with(kosmos) {
            testScope.runTest {
                setRows(1)
                assertThat(underTest.tileViewModels.map { it.tile.spec })
                    .isEqualTo(tiles.take(DEFAULT_COLUMNS))

                // Two rows of 7 hold all 9 tiles.
                setRows(2)
                assertThat(underTest.tileViewModels.map { it.tile.spec }).isEqualTo(tiles)
            }
        }

    @Test
    fun changeTiles_tilesChange() =
        with(kosmos) {
            testScope.runTest {
                setRows(1)

                // Remove tile small:4
                currentTilesInteractor.removeTiles(setOf(tiles[3]))
                runCurrent()

                assertThat(underTest.tileViewModels.map { it.tile.spec })
                    .isEqualTo((tiles - tiles[3]).take(DEFAULT_COLUMNS))
            }
        }

    @Test
    fun mediaVisibleInLandscape_halfColumns_sameTiles() =
        with(kosmos) {
            testScope.runTest {
                setRows(1)
                runCurrent()
                assertThat(underTest.columns).isEqualTo(DEFAULT_COLUMNS)
                assertThat(underTest.tileViewModels.map { it.tile.spec })
                    .isEqualTo(tiles.take(DEFAULT_COLUMNS))

                makeMediaVisible(LOCATION_QQS, visible = true)
                setConfigurationForMediaInRow(mediaInRow = true)
                runCurrent()

                // Half the tiles per row, rounded up; the same tiles wrap onto twice the rows.
                assertThat(underTest.columns).isEqualTo((DEFAULT_COLUMNS + 1) / 2)
                assertThat(underTest.tileViewModels.map { it.tile.spec })
                    .isEqualTo(tiles.take(DEFAULT_COLUMNS))
            }
        }

    private fun Kosmos.setTiles(tiles: List<TileSpec>) {
        currentTilesInteractor.setTiles(tiles)
    }

    private fun TestScope.setRows(rows: Int) {
        with(kosmos) {
            testCase.context.orCreateTestableResources.addOverride(
                R.integer.quick_qs_paginated_grid_num_rows,
                rows,
            )
            fakeConfigurationRepository.onConfigurationChange()
        }
        runCurrent()
    }

    private companion object {
        const val PREFIX_SMALL = "small"
        const val PREFIX_LARGE = "large"
        const val DEFAULT_COLUMNS = QSPreferencesRepository.COLLAPSED_COLUMNS_DEFAULT

        private fun Kosmos.makeMediaVisible(@MediaLocation location: Int, visible: Boolean) {
            mediaHostStatesManager.updateHostState(
                location,
                MediaHost.MediaHostStateHolder().apply { this.visible = visible },
            )
            setHasMedia(true)
        }
    }
}
