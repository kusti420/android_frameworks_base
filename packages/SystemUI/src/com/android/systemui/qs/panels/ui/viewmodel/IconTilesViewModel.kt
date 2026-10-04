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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.domain.interactor.IconTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

interface IconTilesViewModel {
    val largeTiles: StateFlow<Set<TileSpec>>

    /**
     * IchthysOS "Shoal": the tiles in the junction slots between the big tiles, top to bottom. A
     * null entry is a free slot.
     */
    val junctionTiles: StateFlow<List<TileSpec?>>

    fun isIconTile(spec: TileSpec): Boolean

    fun resize(spec: TileSpec, toIcon: Boolean)

    /**
     * IchthysOS "Shoal": sets which tiles are big and which sit in the junction slots in one go,
     * as arranged in the Shoal tile editor. Unlike [resize] this also takes tiles that are only
     * about to become current (a tile dragged in from the tray).
     */
    fun setShoalLayout(largeTiles: Set<TileSpec>, junctionTiles: List<TileSpec?>)
}

@SysUISingleton
class IconTilesViewModelImpl @Inject constructor(private val interactor: IconTilesInteractor) :
    IconTilesViewModel {
    override val largeTiles = interactor.largeTilesSpecs

    override val junctionTiles = interactor.junctionTilesSpecs

    override fun isIconTile(spec: TileSpec): Boolean = interactor.isIconTile(spec)

    override fun resize(spec: TileSpec, toIcon: Boolean) = interactor.resize(spec, toIcon)

    override fun setShoalLayout(largeTiles: Set<TileSpec>, junctionTiles: List<TileSpec?>) {
        // Written even when they look unchanged: the flows above trail the stores, and two quick
        // edits could otherwise leave the first one in place.
        interactor.setJunctionTiles(junctionTiles)
        interactor.setLargeTiles(largeTiles)
    }
}
