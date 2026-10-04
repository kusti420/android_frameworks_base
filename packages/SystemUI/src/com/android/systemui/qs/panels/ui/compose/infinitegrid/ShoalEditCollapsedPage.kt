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

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.ui.compose.DragAndDropState
import com.android.systemui.qs.panels.ui.compose.DragType
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileArea
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * The second page of the IchthysOS "Shoal" tile editor (see ShoalEditTileGrid.kt): the collapsed
 * panel, the row of tiles in the notification shade before quick settings is pulled down.
 *
 * It is an editor of its own, with the grammar of the expanded page. On top the panel as it will
 * look, circles in rows with the free places dashed, under steppers for the tiles per row and the
 * rows. Pinned below, the tray with every tile that is not in the collapsed panel: a tile does not
 * have to be in the expanded panel to be put here. Tapping a tray tile or dragging it up adds it,
 * dragging in the rows reorders, and a tile leaves by its options or by a drag to the tray.
 *
 * The page works on a plain list of tiles. Until the first edit that list is the automatic one
 * (the first tiles of the expanded panel); any edit hands the whole list back as the panel's own.
 */

/** What a drag on the collapsed page is over. */
private sealed interface ShoalCollapsedTarget {
    data class Place(val index: Int) : ShoalCollapsedTarget

    data object Tray : ShoalCollapsedTarget
}

/**
 * What [ShoalCollapsedLayout] measured, for working out which place a drag is over: places are
 * [pitch] apart, [spacing] of which is the ground between two of them.
 */
internal class ShoalCollapsedGrid(
    val columns: Int,
    val pitch: Float,
    val spacing: Int,
    val rowPitch: Int,
)

/**
 * The transient state of the collapsed page: the selection, the drag, and the tiles a drop would
 * give ([preview]).
 */
internal class ShoalCollapsedEditState : DragAndDropState {
    override var draggedCell by mutableStateOf<SizedTile<EditTileViewModel>?>(null)
        private set

    override var draggedPosition by mutableStateOf(Offset.Unspecified)
        private set

    override var dragType by mutableStateOf<DragType?>(null)
        private set

    // Leaving the collapsed panel is not leaving quick settings; any tile can. (The last one
    // stays, which only [hover] can tell.)
    override val isDraggedCellRemovable: Boolean
        get() = true

    override val dragInProgress: Boolean
        get() = draggedCell != null

    /** The panel's tiles if the dragged tile were dropped right now, shown while the drag lasts. */
    var preview by mutableStateOf<List<TileSpec>?>(null)
        private set

    var selected by mutableStateOf<TileSpec?>(null)

    // Where things are, for working out what a drag is over. Plain fields: they are only read
    // from drag events, never from composition.
    /** The scrolling part, in root coordinates. */
    var viewport: Rect? = null
    var rowsCoordinates: LayoutCoordinates? = null
    var trayCoordinates: LayoutCoordinates? = null
    var grid: ShoalCollapsedGrid? = null

    private var target: ShoalCollapsedTarget? = null

    override fun isMoving(tileSpec: TileSpec): Boolean = draggedCell?.tile?.tileSpec == tileSpec

    override fun onStarted(cell: SizedTile<EditTileViewModel>, dragType: DragType) {
        draggedCell = cell
        this.dragType = dragType
        target = null
        preview = null
    }

    // The page targets by position (see [hover]), not by lazy grid item.
    override fun onTargeting(target: Int, insertAfter: Boolean) {}

    override fun onMoved(offset: Offset) {
        draggedPosition = offset
    }

    override fun movedOutOfBounds() {}

    override fun onDrop() {
        draggedCell = null
        draggedPosition = Offset.Unspecified
        dragType = null
        target = null
        preview = null
    }

    /**
     * Updates [preview] for a drag at [position] (root coordinates) over the panel's [tiles], of
     * which it holds [capacity].
     */
    fun hover(position: Offset, tiles: List<TileSpec>, capacity: Int, rtl: Boolean) {
        val spec = draggedCell?.tile?.tileSpec ?: return
        val newTarget = targetAt(position, rtl)
        if (newTarget == target) return
        target = newTarget
        val others = tiles - spec
        val next =
            when (newTarget) {
                null -> null
                // No room for a tile that comes from the tray: it goes back there.
                is ShoalCollapsedTarget.Place ->
                    if (others.size >= capacity) {
                        null
                    } else {
                        val at = newTarget.index.coerceIn(0, others.size)
                        others.subList(0, at) + spec + others.subList(at, others.size)
                    }
                // Dropping a tile of the panel on the tray takes it out, unless it is the last
                // one: the panel is never left empty. A tile that came from the tray simply
                // goes back.
                ShoalCollapsedTarget.Tray ->
                    if (dragType == DragType.Move && others.isNotEmpty()) others else null
            }
        preview = next?.takeIf { it != tiles }
    }

    private fun targetAt(position: Offset, rtl: Boolean): ShoalCollapsedTarget? {
        val tray = trayCoordinates?.takeIf { it.isAttached }
        if (tray != null && tray.boundsInRoot().contains(position)) return ShoalCollapsedTarget.Tray

        // Anywhere over the page above the tray counts for the place nearest to it, so that a
        // tile dragged up from the tray does not have to be aimed.
        if (viewport?.contains(position) != true) return null
        val rows = rowsCoordinates?.takeIf { it.isAttached } ?: return null
        val grid = grid ?: return null
        val local = position - rows.positionInRoot()
        val column =
            floor((local.x + grid.spacing / 2f) / grid.pitch).toInt().coerceIn(0, grid.columns - 1)
        val row = floor(local.y / grid.rowPitch).toInt().coerceAtLeast(0)
        val fromStart = if (rtl) grid.columns - 1 - column else column
        // Past the last tile is the end of the panel: the index is brought into range on insert.
        return ShoalCollapsedTarget.Place(row * grid.columns + fromStart)
    }
}

/**
 * @param tiles every tile there is, for the tray
 * @param collapsed the panel's tiles as stored or as the last edit left them, no more than fit; the
 *   automatic ones while it is [following] the expanded panel
 * @param onCollapsed takes the panel's tiles after an edit; they are its own from then on
 * @param onFollow whether the panel follows the expanded one, from the switch for it
 */
@Composable
internal fun ShoalEditCollapsedPage(
    state: ShoalCollapsedEditState,
    tiles: List<EditTileViewModel>,
    bySpec: Map<TileSpec, EditTileViewModel>,
    collapsed: List<TileSpec>,
    following: Boolean,
    columns: Int,
    rows: Int,
    columnsRange: IntRange,
    rowsRange: IntRange,
    accent: Color,
    fonts: ShoalQsFonts,
    onCollapsed: (List<TileSpec>) -> Unit,
    onFollow: (Boolean) -> Unit,
    onColumns: (Int) -> Unit,
    onRows: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val capacity = columns * rows
    val shown = state.preview ?: collapsed
    val currentCollapsed by rememberUpdatedState(collapsed)
    val currentCapacity by rememberUpdatedState(capacity)
    val commit by rememberUpdatedState(onCollapsed)

    LaunchedEffect(collapsed) {
        val selected = state.selected
        if (selected != null && selected !in collapsed) state.selected = null
    }

    val onTileClick: (TileSpec) -> Unit = { spec ->
        state.selected = if (state.selected == spec) null else spec
    }
    val onTrayClick: (TileSpec) -> Unit = { spec ->
        val panel = currentCollapsed
        if (panel.size < currentCapacity && spec !in panel) commit(panel + spec)
    }
    val onDragStart: () -> Unit = { state.selected = null }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .dragAndDropTileArea(
                    dragAndDropState = state,
                    onMoved = { state.hover(it, currentCollapsed, currentCapacity, rtl) },
                    onDrop = { state.preview?.let { dropped -> commit(dropped) } },
                )
    ) {
        val selectedSpec = state.selected
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { state.viewport = it.boundsInRoot() }
                    .nestedScroll(ShoalEditScrollLock)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp, bottom = 24.dp)
        ) {
            val labelStyle =
                TextStyle(color = ShoalQsColors.Muted, fontSize = 11.sp, fontFamily = fonts.mono)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = spacedBy(8.dp),
            ) {
                BasicText(
                    text = stringResource(R.string.ichthys_qs_edit_collapsed_tiles).uppercase(),
                    style = labelStyle.copy(letterSpacing = 0.08.em),
                    maxLines = 1,
                )
                BasicText(
                    text =
                        stringResource(
                            R.string.ichthys_qs_edit_collapsed_count,
                            shown.size,
                            capacity,
                        ),
                    style = labelStyle.copy(color = accent),
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                // On: the panel shows the first tiles of the expanded one. Any edit here turns
                // it off; turning it on again is the way back, and gives up the panel's own
                // tiles.
                ShoalEditChip(
                    text = stringResource(R.string.ichthys_qs_edit_collapsed_follow),
                    selected = following,
                    accent = accent,
                    fonts = fonts,
                    onClick = { onFollow(!following) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShoalEditStepper(
                    label = stringResource(R.string.ichthys_qs_edit_collapsed_per_row),
                    value = columns,
                    range = columnsRange,
                    decreaseDescription =
                        stringResource(R.string.ichthys_qs_edit_collapsed_per_row_fewer),
                    increaseDescription =
                        stringResource(R.string.ichthys_qs_edit_collapsed_per_row_more),
                    fonts = fonts,
                    onValue = onColumns,
                )
                Spacer(Modifier.weight(1f))
                ShoalEditStepper(
                    label = stringResource(R.string.ichthys_qs_edit_collapsed_rows),
                    value = rows,
                    range = rowsRange,
                    decreaseDescription =
                        stringResource(R.string.ichthys_qs_edit_collapsed_rows_fewer),
                    increaseDescription =
                        stringResource(R.string.ichthys_qs_edit_collapsed_rows_more),
                    fonts = fonts,
                    onValue = onRows,
                )
            }

            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().onGloballyPositioned { state.rowsCoordinates = it }) {
                ShoalCollapsedLayout(
                    columns = columns,
                    modifier = Modifier.fillMaxWidth(),
                    onGrid = { state.grid = it },
                ) {
                    shown.forEachIndexed { index, spec ->
                        key(spec) {
                            val tile = bySpec[spec]
                            ShoalEditCircleTile(
                                tile = tile,
                                selected = spec == selectedSpec,
                                moving = state.isMoving(spec),
                                accent = accent,
                                dragState = state,
                                onClick = { onTileClick(spec) },
                                onDragStart = onDragStart,
                                description =
                                    stringResource(
                                        R.string.ichthys_qs_edit_collapsed_tile,
                                        tile?.label?.text ?: "",
                                        index + 1,
                                    ),
                            )
                        }
                    }
                    repeat(capacity - shown.size) { free -> key(free) { ShoalEditCollapsedSlot() } }
                }
            }

            Spacer(Modifier.height(18.dp))
            val selectedName = selectedSpec?.let { bySpec[it]?.label?.text }
            BasicText(
                text =
                    when {
                        selectedName != null ->
                            stringResource(R.string.ichthys_qs_edit_hint_selected, selectedName)
                        following ->
                            stringResource(R.string.ichthys_qs_edit_collapsed_hint_following)
                        else -> stringResource(R.string.ichthys_qs_edit_hint_idle)
                    },
                style =
                    TextStyle(
                        color = ShoalQsColors.Muted,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        fontFamily = fonts.mono,
                    ),
            )
        }

        val bleed = dimensionResource(R.dimen.overlay_qs_layout_horizontal_padding)
        // Every tile that is not in the collapsed panel, whether the expanded panel has it or
        // not.
        val available =
            remember(tiles, collapsed) {
                val inPanel = collapsed.toSet()
                tiles.filter { it.tileSpec !in inPanel }
            }
        val dragging = state.dragInProgress
        val selected = selectedSpec?.let { bySpec[it] }
        // The last tile stays: an empty panel is not a thing, the switch above is the way to
        // give up one's own tiles.
        val canRemove = collapsed.size > 1
        ShoalEditTraySurface(
            bleed = bleed,
            onPlaced = { state.trayCoordinates = it },
            modifier = Modifier.sysuiResTag(SHOAL_EDIT_COLLAPSED_TRAY_TEST_TAG),
        ) {
            if (selected != null && !dragging) {
                ShoalEditCollapsedOptions(
                    tile = selected,
                    row = collapsed.indexOf(selected.tileSpec).coerceAtLeast(0) / columns + 1,
                    canRemove = canRemove,
                    availableCount = available.size,
                    accent = accent,
                    fonts = fonts,
                    onDeselect = { state.selected = null },
                    onRemove = { commit(collapsed - selected.tileSpec) },
                    modifier = Modifier.padding(horizontal = bleed),
                )
            } else {
                val full = collapsed.size >= capacity
                ShoalEditAvailable(
                    available = available,
                    folded = dragging,
                    hint =
                        when {
                            dragging && state.dragType == DragType.Move && canRemove ->
                                stringResource(R.string.ichthys_qs_edit_tray_hint_remove)
                            dragging -> ""
                            full -> stringResource(R.string.ichthys_qs_edit_collapsed_full)
                            else -> stringResource(R.string.ichthys_qs_edit_tray_hint)
                        },
                    hintMarked = full && !dragging,
                    dragState = state,
                    accent = accent,
                    fonts = fonts,
                    bleed = bleed,
                    onTrayClick = onTrayClick,
                )
            }
        }
    }
}

/**
 * The options of the selected tile of the collapsed panel: what it is and where, a way out of the
 * selection, and the one thing to do with it here.
 */
@Composable
private fun ShoalEditCollapsedOptions(
    tile: EditTileViewModel,
    row: Int,
    canRemove: Boolean,
    availableCount: Int,
    accent: Color,
    fonts: ShoalQsFonts,
    onDeselect: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
        verticalArrangement = spacedBy(14.dp),
    ) {
        ShoalEditOptionsHeader(
            tile = tile,
            placeText = stringResource(R.string.ichthys_qs_edit_place_collapsed, row),
            accent = accent,
            fonts = fonts,
            onDeselect = onDeselect,
        )
        ShoalEditButton(
            text = stringResource(R.string.qs_customize_remove),
            style = ShoalEditButtonStyle.Outline,
            accent = accent,
            fonts = fonts,
            enabled = canRemove,
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth().height(ShoalEdit.ACTION_HEIGHT),
        )
        ShoalEditFoldedTray(
            availableCount = availableCount,
            accent = accent,
            fonts = fonts,
            onClick = onDeselect,
        )
    }
}

/**
 * A number with a button on either side to take one off or add one, labelled like a section. The
 * buttons stop at the ends of [range].
 */
@Composable
private fun ShoalEditStepper(
    label: String,
    value: Int,
    range: IntRange,
    decreaseDescription: String,
    increaseDescription: String,
    fonts: ShoalQsFonts,
    onValue: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacedBy(6.dp)) {
        BasicText(
            text = label.uppercase(),
            style =
                TextStyle(
                    color = ShoalQsColors.Muted,
                    fontSize = 11.sp,
                    fontFamily = fonts.mono,
                    letterSpacing = 0.08.em,
                ),
            maxLines = 1,
        )
        ShoalEditRoundButton(
            contentDescription = decreaseDescription,
            enabled = value > range.first,
            onClick = { onValue(value - 1) },
        ) {
            ShoalEditGlyph(ShoalEditGlyphs.Minus, ShoalQsColors.Label)
        }
        BasicText(
            text = value.toString(),
            style =
                TextStyle(
                    color = ShoalQsColors.OnTileOff,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    fontFamily = fonts.mono,
                ),
            maxLines = 1,
            modifier = Modifier.width(ShoalEdit.STEPPER_VALUE_WIDTH),
        )
        ShoalEditRoundButton(
            contentDescription = increaseDescription,
            enabled = value < range.last,
            onClick = { onValue(value + 1) },
        ) {
            ShoalEditGlyph(ShoalEditGlyphs.Plus, ShoalQsColors.Label)
        }
    }
}

/**
 * The collapsed panel's tiles in rows of [columns], the way the panel lays them out
 * (QuickQuickSettings.kt): equal cells [ShoalEdit.COLLAPSED_CELL_SPACING] apart, each with its
 * circle in the middle. The circle is [ShoalQs.COLLAPSED_CIRCLE] across, or less where the cells
 * are too narrow to leave [ShoalEdit.COLLAPSED_MIN_GAP] between two of them.
 */
@Composable
private fun ShoalCollapsedLayout(
    columns: Int,
    modifier: Modifier = Modifier,
    onGrid: (ShoalCollapsedGrid) -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val perRow = columns.coerceAtLeast(1)
        val spacing = ShoalEdit.COLLAPSED_CELL_SPACING.roundToPx()
        val cellWidth = (width - spacing * (perRow - 1)).toFloat() / perRow
        val pitch = cellWidth + spacing
        val circle =
            minOf(
                    ShoalQs.COLLAPSED_CIRCLE.roundToPx(),
                    (pitch - ShoalEdit.COLLAPSED_MIN_GAP.toPx()).toInt(),
                )
                .coerceAtLeast(0)
        val rowGap = ShoalEdit.COLLAPSED_ROW_GAP.roundToPx()
        val rowPitch = circle + rowGap
        val placeables = measurables.map { it.measure(Constraints.fixed(circle, circle)) }
        val rows = (placeables.size + perRow - 1) / perRow
        onGrid(ShoalCollapsedGrid(perRow, pitch, spacing, rowPitch))
        layout(width, (rows * rowPitch - rowGap).coerceAtLeast(0)) {
            placeables.forEachIndexed { index, placeable ->
                val x = (index % perRow) * pitch + (cellWidth - circle) / 2f
                placeable.placeRelative(x.roundToInt(), (index / perRow) * rowPitch)
            }
        }
    }
}

/** A free place of the collapsed panel: a dashed circle, as a free junction is. */
@Composable
private fun ShoalEditCollapsedSlot(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.ichthys_qs_edit_collapsed_slot)
    Box(
        modifier
            .drawBehind {
                drawCircle(
                    ShoalQsColors.Muted,
                    size.minDimension / 2f - ShoalEdit.DASH_STROKE.toPx() / 2f,
                    alpha = ShoalEdit.FREE_SLOT_ALPHA,
                    style = dashedStroke(),
                )
            }
            .semantics { contentDescription = description }
    )
}

private const val SHOAL_EDIT_COLLAPSED_TRAY_TEST_TAG = "ShoalEditModeCollapsedTray"
