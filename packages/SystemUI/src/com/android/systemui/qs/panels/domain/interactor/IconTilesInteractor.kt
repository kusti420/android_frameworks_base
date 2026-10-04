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

import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.qs.QSEditEvent
import com.android.systemui.qs.panels.data.repository.DefaultLargeTilesRepository
import com.android.systemui.qs.panels.shared.model.PanelsLog
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.metricSpec
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Interactor for retrieving the list of [TileSpec] to be displayed as icons and resizing icons. */
@SysUISingleton
class IconTilesInteractor
@Inject
constructor(
    private val repo: DefaultLargeTilesRepository,
    private val currentTilesInteractor: CurrentTilesInteractor,
    private val preferencesInteractor: QSPreferencesInteractor,
    private val uiEventLogger: UiEventLogger,
    @PanelsLog private val logBuffer: LogBuffer,
    @Background private val scope: CoroutineScope,
) {

    val largeTilesSpecs =
        preferencesInteractor.largeTilesSpecs
            .onEach { logChange(it) }
            .stateIn(scope, SharingStarted.Eagerly, repo.defaultLargeTiles)

    /**
     * IchthysOS "Shoal": the tiles in the junction slots between the big tiles, top to bottom. A
     * null entry is a free slot. See ShoalJunctions for which slots the layout shows.
     */
    val junctionTilesSpecs =
        preferencesInteractor.junctionTilesSpecs.stateIn(
            scope,
            SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * IchthysOS: the tiles picked for the collapsed panel (quick quick settings), in order. Empty
     * while none are picked; the collapsed panel then shows the first tiles of the expanded one.
     */
    val collapsedTilesSpecs =
        preferencesInteractor.collapsedTilesSpecs.stateIn(
            scope,
            SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * IchthysOS: the tiles that are in the tile list for the collapsed panel only. The tile list
     * ([CurrentTilesInteractor]) holds the tiles of both panels, because only a tile in it exists;
     * the expanded panel is that list without these (see [expandedTilesSpecs]).
     *
     * A tile only counts while the collapsed panel's selection names it. A selection changed from
     * outside the editor (ichthys.nix) can then not leave a tile in the list that neither panel
     * shows: it turns up in the expanded panel instead.
     */
    val collapsedOnlyTilesSpecs: StateFlow<Set<TileSpec>> =
        combine(
                preferencesInteractor.collapsedOnlyTilesSpecs,
                preferencesInteractor.collapsedTilesSpecs,
            ) { only, collapsed ->
                only.intersect(collapsed.toSet())
            }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** IchthysOS: the specs of the expanded panel's tiles, in order. */
    val expandedTilesSpecs: List<TileSpec>
        get() {
            val collapsedOnly = collapsedOnlyTilesSpecs.value
            return currentTilesInteractor.currentTilesSpecs.filter { it !in collapsedOnly }
        }

    fun isIconTile(spec: TileSpec): Boolean = !largeTilesSpecs.value.contains(spec)

    /** Set the large tiles to be [specs] */
    fun addLargeTile(spec: TileSpec) {
        preferencesInteractor.setLargeTilesSpecs(largeTilesSpecs.value + spec)
    }

    /** Set the large tiles to be [specs] */
    fun setLargeTiles(specs: Set<TileSpec>) {
        preferencesInteractor.setLargeTilesSpecs(specs)
    }

    /** Remove [specs] from the current set of large tiles */
    fun removeLargeTiles(specs: Set<TileSpec>) {
        preferencesInteractor.removeLargeTilesSpecs(specs)
    }

    /** IchthysOS "Shoal": set the junction slots to be [slots] */
    fun setJunctionTiles(slots: List<TileSpec?>) {
        preferencesInteractor.setJunctionTilesSpecs(slots)
    }

    /** IchthysOS "Shoal": free the junction slots that hold any of [specs] */
    fun removeJunctionTiles(specs: Set<TileSpec>) {
        preferencesInteractor.removeJunctionTilesSpecs(specs)
    }

    /**
     * IchthysOS: sets the tiles of both panels in one go: [expanded] in order, and [collapsed] in
     * order, or empty for the collapsed panel to follow the expanded one. The two are independent.
     * A tile of [collapsed] that is not in [expanded] goes into the tile list for the collapsed
     * panel only, and a tile in neither leaves the list.
     *
     * [collapsed] is taken at its word, so it must only name tiles that can exist.
     */
    fun setPanelTiles(expanded: List<TileSpec>, collapsed: List<TileSpec>) {
        val expandedSet = expanded.toSet()
        val collapsedOnly = collapsed.filterTo(LinkedHashSet()) { it !in expandedSet }
        // The keys first: both are in place by the time the new tile list (written
        // asynchronously) arrives, so a tile of the collapsed panel only never shows up in the
        // expanded one on its way in.
        preferencesInteractor.setCollapsedTilesSpecs(collapsed)
        preferencesInteractor.setCollapsedOnlyTilesSpecs(collapsedOnly)
        freeExpandedPlaces(collapsedOnly)
        currentTilesInteractor.setTiles(expanded + collapsedOnly)
    }

    /**
     * IchthysOS: sets the expanded panel's tiles to be [expanded] and leaves the collapsed panel as
     * it is. A tile the collapsed panel shows stays in the tile list for it when [expanded] no
     * longer has it.
     */
    fun setExpandedTiles(expanded: List<TileSpec>) {
        setPanelTiles(expanded, collapsedTilesKeptWith(expanded))
    }

    /** IchthysOS: takes [spec] out of the expanded panel; see [setExpandedTiles]. */
    fun removeExpandedTile(spec: TileSpec) {
        if (spec in collapsedTilesKeptWith(expandedTilesSpecs)) {
            preferencesInteractor.setCollapsedOnlyTilesSpecs(collapsedOnlyTilesSpecs.value + spec)
            freeExpandedPlaces(setOf(spec))
        } else {
            currentTilesInteractor.removeTiles(listOf(spec))
        }
    }

    /**
     * IchthysOS: [specs] are tiles of the expanded panel from now on, if they were in the tile list
     * for the collapsed panel only: something outside the editor (auto-add) put them there.
     */
    fun showInExpanded(specs: Set<TileSpec>) {
        preferencesInteractor.removeCollapsedOnlyTilesSpecs(specs)
    }

    /** IchthysOS: [specs] left the tile list, and with it the collapsed panel. */
    fun removeCollapsedTiles(specs: Set<TileSpec>) {
        preferencesInteractor.removeCollapsedTilesSpecs(specs)
        preferencesInteractor.removeCollapsedOnlyTilesSpecs(specs)
    }

    /**
     * IchthysOS: the collapsed panel follows the expanded one again. Its tiles stay in the tile
     * list; resetting that is the caller's business.
     */
    fun resetCollapsedTiles() {
        preferencesInteractor.setCollapsedTilesSpecs(emptyList())
        preferencesInteractor.setCollapsedOnlyTilesSpecs(emptySet())
    }

    /**
     * The collapsed panel's selection as it stands once the expanded panel is [expanded]: the tiles
     * it names that are in a panel now or will be. The rest are names it cannot show (not in the
     * tile list), and they are dropped rather than brought to life.
     */
    private fun collapsedTilesKeptWith(expanded: List<TileSpec>): List<TileSpec> {
        val known = currentTilesInteractor.currentTilesSpecs.toSet() + expanded
        return collapsedTilesSpecs.value.filter { it in known }
    }

    // A tile that leaves the expanded panel gives up its size and its junction, the way a tile
    // that leaves the tile list does (DynamicIconTilesInteractor): it starts as an ordinary
    // circle when it is added again.
    private fun freeExpandedPlaces(specs: Set<TileSpec>) {
        if (specs.isEmpty()) return
        if (specs.any { it in largeTilesSpecs.value }) {
            preferencesInteractor.removeLargeTilesSpecs(specs)
        }
        if (specs.any { it in junctionTilesSpecs.value }) {
            preferencesInteractor.removeJunctionTilesSpecs(specs)
        }
    }

    fun resetToDefault() {
        preferencesInteractor.setLargeTilesSpecs(repo.defaultLargeTiles)
    }

    fun resize(spec: TileSpec, toIcon: Boolean) {
        if (!isCurrent(spec)) {
            return
        }

        val isIcon = !largeTilesSpecs.value.contains(spec)
        if (toIcon && !isIcon) {
            preferencesInteractor.setLargeTilesSpecs(largeTilesSpecs.value - spec)
            uiEventLogger.log(
                /* event= */ QSEditEvent.QS_EDIT_RESIZE_SMALL,
                /* uid= */ 0,
                /* packageName= */ spec.metricSpec,
            )
        } else if (!toIcon && isIcon) {
            preferencesInteractor.setLargeTilesSpecs(largeTilesSpecs.value + spec)
            uiEventLogger.log(
                /* event= */ QSEditEvent.QS_EDIT_RESIZE_LARGE,
                /* uid= */ 0,
                /* packageName= */ spec.metricSpec,
            )
        }
    }

    private fun isCurrent(spec: TileSpec): Boolean {
        return currentTilesInteractor.currentTilesSpecs.contains(spec)
    }

    private fun logChange(specs: Set<TileSpec>) {
        logBuffer.log(
            LOG_BUFFER_LARGE_TILES_SPECS_CHANGE_TAG,
            LogLevel.DEBUG,
            { str1 = specs.toString() },
            { "Large tiles change: $str1" },
        )
    }

    private companion object {
        const val LOG_BUFFER_LARGE_TILES_SPECS_CHANGE_TAG = "LargeTilesSpecsChange"
    }
}
