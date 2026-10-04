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

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.ui.compose.load
import com.android.systemui.common.ui.icons.Undo
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.DragAndDropState
import com.android.systemui.qs.panels.ui.compose.DragType
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileArea
import com.android.systemui.qs.panels.ui.compose.dragAndDropTileSource
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTopBarActionViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.shared.model.groupAndSort
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * IchthysOS "Shoal" tile editor (Settings.Secure ichthys_qs_style = "shoal").
 *
 * It has two parts. First the collapsed panel (quick quick settings): its tiles as circles in rows,
 * with steppers for the tiles per row and the rows. A tile is there on top of its place in the
 * expanded panel, never instead of it. Then the expanded panel the way [ShoalTileGrid] lays it out,
 * editable in place: the big tiles two per row with a junction slot on every meeting point, then
 * the honeycomb of circles. The tiles that are not in the panel sit in a tray pinned to the bottom,
 * outside the scrolling part. Tapping a tile selects it and turns the tray into its options;
 * holding one drags it.
 *
 * Every edit is one [ShoalEditLayout] handed to [onLayout]; the caller snapshots (undo) and writes
 * it. Nothing here writes settings.
 */
internal object ShoalEdit {
    val TOP_BAR_HEIGHT = 56.dp
    /** Height of the top bar's buttons and of the round buttons elsewhere. */
    val BUTTON = 36.dp
    val SECTION_LABEL_HEIGHT = 26.dp
    /** Height of the heading of each of the two parts, the collapsed and the expanded panel. */
    val PART_LABEL_HEIGHT = 30.dp
    /** Height of the options bar's action buttons. */
    val ACTION_HEIGHT = 44.dp
    val CHIP_HEIGHT = 30.dp
    val TRAY_RADIUS = 22.dp
    /** Width of one tile of the tray: a [ShoalQs.CELL] circle with its label underneath. */
    val TRAY_ITEM_WIDTH = 62.dp
    /** Diameter of a tile of the collapsed section, where its share of the row has the room. */
    val COLLAPSED_CELL = 40.dp
    /** The least space between two tiles of the collapsed section: a selection ring fits in it. */
    val COLLAPSED_MIN_GAP = 6.dp
    val COLLAPSED_ROW_GAP = 8.dp
    /** Width kept for the number of a stepper, so that the buttons stay put when it changes. */
    val STEPPER_VALUE_WIDTH = 20.dp
    /** The ring around a selected tile and the gap between the two. */
    val SELECTION_STROKE = 2.dp
    val SELECTION_GAP = 2.dp
    val DASH_STROKE = 1.5.dp
    val DASH_ON = 4.dp
    val DASH_OFF = 3.dp
    /** How close to a junction's centre a drag counts as being on it: the scoop and a bit. */
    val JUNCTION_REACH = 26.dp
    /** Strip at the top and bottom edge of the scrolling part that scrolls it during a drag. */
    val AUTO_SCROLL_ZONE = 48.dp
    const val AUTO_SCROLL_MILLIS_PER_PIXEL = 2
    /** How long an edit is shown before the stores have to agree with it. */
    const val PENDING_TIMEOUT_MILLIS = 1500L
    const val DISABLED_ALPHA = 0.38f
    /** Accent wash inside a junction that is offered as a target. */
    const val MARK_FILL_ALPHA = 0.14f
    const val FREE_SLOT_ALPHA = 0.6f
}

/** What the drag is over. */
private sealed interface ShoalDropTarget {
    data class Big(val index: Int) : ShoalDropTarget

    data class Circle(val index: Int) : ShoalDropTarget

    data class Junction(val slot: Int) : ShoalDropTarget

    /** A place of the collapsed section; only a drag that started there has it as a target. */
    data class Collapsed(val index: Int) : ShoalDropTarget

    data object Tray : ShoalDropTarget
}

/**
 * The editor's transient state: the selection, the drag, and the two layouts that can stand in for
 * the stored one ([pending], [preview]).
 */
private class ShoalEditState : DragAndDropState {
    override var draggedCell by mutableStateOf<SizedTile<EditTileViewModel>?>(null)
        private set

    override var draggedPosition by mutableStateOf(Offset.Unspecified)
        private set

    override var dragType by mutableStateOf<DragType?>(null)
        private set

    /**
     * Whether the drag started on a tile of the collapsed section. A tile is drawn there and in the
     * expanded panel, so the spec alone does not say which of the two is on the move. Such a drag
     * only rearranges the collapsed section.
     */
    var draggedFromCollapsed by mutableStateOf(false)
        private set

    // A tile can be taken out by a drag if the drag brought it in, or if it is removable anyway.
    // Out of the collapsed section any tile can go: it stays in quick settings.
    override val isDraggedCellRemovable: Boolean
        get() =
            draggedFromCollapsed ||
                dragType == DragType.Add ||
                draggedCell?.tile?.isRemovable ?: false

    override val dragInProgress: Boolean
        get() = draggedCell != null

    /**
     * The last edit, shown until the stores report it back. The tile list, the large set and the
     * junction slots live in three places and arrive one after the other; without this the tiles
     * would jump through the states in between.
     */
    var pending by mutableStateOf<ShoalEditLayout?>(null)

    /** What dropping the dragged tile right now would do, shown while the drag lasts. */
    var preview by mutableStateOf<ShoalEditLayout?>(null)
        private set

    var selected by mutableStateOf<TileSpec?>(null)

    /**
     * Whether [selected] was tapped in the collapsed section rather than in the expanded panel. The
     * same tile has different options in the two.
     */
    var selectedInCollapsed by mutableStateOf(false)

    /** The free junction waiting for a tile from the tray, if one was tapped. */
    var pickSlot by mutableStateOf<Int?>(null)

    /** The scrolling part, in root coordinates; a drag near its edges scrolls it. */
    var viewport by mutableStateOf<Rect?>(null)

    // Where things are, for working out what a drag is over. Plain fields: they are only read
    // from drag events, never from composition.
    var wideCoordinates: LayoutCoordinates? = null
    var circleCoordinates: LayoutCoordinates? = null
    var trayCoordinates: LayoutCoordinates? = null
    var collapsedCoordinates: LayoutCoordinates? = null
    var honeycomb: HoneycombGeometry? = null
    var collapsedGrid: ShoalCollapsedGrid? = null

    private var target: ShoalDropTarget? = null

    override fun isMoving(tileSpec: TileSpec): Boolean = draggedCell?.tile?.tileSpec == tileSpec

    override fun onStarted(cell: SizedTile<EditTileViewModel>, dragType: DragType) {
        draggedCell = cell
        this.dragType = dragType
        draggedFromCollapsed = false
        target = null
        preview = null
        pickSlot = null
    }

    /** To be called by a tile of the collapsed section when its drag starts, after [onStarted]. */
    fun onStartedInCollapsed() {
        draggedFromCollapsed = true
    }

    // The editor targets by position (see [hover]), not by lazy grid item.
    override fun onTargeting(target: Int, insertAfter: Boolean) {}

    override fun onMoved(offset: Offset) {
        draggedPosition = offset
    }

    override fun movedOutOfBounds() {}

    override fun onDrop() {
        draggedCell = null
        draggedPosition = Offset.Unspecified
        dragType = null
        draggedFromCollapsed = false
        target = null
        preview = null
    }

    /**
     * Updates [preview] for a drag at [position] (root coordinates) over the [committed] layout.
     *
     * @param collapsed the collapsed panel's tiles of [committed], the automatic ones if none are
     *   picked (see [ShoalEditLayout.collapsedOr])
     */
    fun hover(
        position: Offset,
        committed: ShoalEditLayout,
        collapsed: List<TileSpec>,
        metrics: ShoalEditMetrics,
    ) {
        val spec = draggedCell?.tile?.tileSpec ?: return
        val newTarget =
            if (draggedFromCollapsed) {
                collapsedTargetAt(position, metrics)
            } else {
                targetAt(position, spec, committed, metrics)
            }
        if (newTarget == target) return
        target = newTarget
        val next =
            when (newTarget) {
                null -> null
                is ShoalDropTarget.Big -> committed.toBig(spec, newTarget.index)
                is ShoalDropTarget.Circle -> committed.toCircle(spec, newTarget.index)
                // Null over the tile's own junction: it stays where it is.
                is ShoalDropTarget.Junction -> committed.toJunction(spec, newTarget.slot)
                // Back on its own place nothing changes; in particular the automatic tiles
                // are not turned into picked ones by a drag that moved nothing.
                is ShoalDropTarget.Collapsed ->
                    committed.collapsedAt(collapsed, spec, newTarget.index).takeIf {
                        it.collapsed != collapsed
                    }
                // Dropping a panel tile on the tray takes it out: out of the collapsed panel
                // if that is where it was picked up, out of quick settings otherwise. A tile
                // that came from the tray simply goes back.
                ShoalDropTarget.Tray ->
                    if (draggedFromCollapsed) {
                        committed.collapsedWithout(collapsed, spec)
                    } else if (dragType == DragType.Move && isDraggedCellRemovable) {
                        committed.remove(spec)
                    } else {
                        null
                    }
            }
        preview = next?.takeIf { it != committed }
    }

    /** What a drag that started in the collapsed section is over: a place of it, or the tray. */
    private fun collapsedTargetAt(position: Offset, metrics: ShoalEditMetrics): ShoalDropTarget? {
        val tray = trayCoordinates?.takeIf { it.isAttached }
        if (tray != null && tray.boundsInRoot().contains(position)) return ShoalDropTarget.Tray

        val section = collapsedCoordinates?.takeIf { it.isAttached } ?: return null
        val grid = collapsedGrid ?: return null
        val local = position - section.positionInRoot()
        // Half a row of slack above and below; further out the drag has left the section and
        // the tiles go back to where they were.
        val slack = grid.rowPitch / 2f
        if (local.y < -slack || local.y > section.size.height + slack) return null
        val column = floor(local.x / grid.cellWidth).toInt().coerceIn(0, grid.columns - 1)
        val row = floor(local.y / grid.rowPitch).toInt().coerceAtLeast(0)
        val fromStart = if (metrics.rtl) grid.columns - 1 - column else column
        // Past the last tile is the end of the row: the index is brought into range on insert.
        return ShoalDropTarget.Collapsed(row * grid.columns + fromStart)
    }

    private fun targetAt(
        position: Offset,
        spec: TileSpec,
        committed: ShoalEditLayout,
        metrics: ShoalEditMetrics,
    ): ShoalDropTarget? {
        val tray = trayCoordinates?.takeIf { it.isAttached }
        if (tray != null && tray.boundsInRoot().contains(position)) return ShoalDropTarget.Tray

        // The collapsed section takes no tile from a drag (a tile gets there from its options),
        // and what is above the big tiles is no longer theirs: that section sits there.
        val collapsed = collapsedCoordinates?.takeIf { it.isAttached }
        if (collapsed != null && position.y < collapsed.boundsInRoot().bottom) return null

        val wide = wideCoordinates?.takeIf { it.isAttached } ?: return null
        val circles = circleCoordinates?.takeIf { it.isAttached } ?: return null
        // The geometry on screen is the one of the layout being shown, preview included.
        val shown = preview ?: committed

        val wideOrigin = wide.positionInRoot()
        val local = position - wideOrigin
        val width = wide.size.width
        val tileWidth = (width - metrics.gap) / 2
        val rowPitch = metrics.rowHeight + metrics.gap

        // Junctions first: they sit on top of the big tiles' corners.
        val gutterX = tileWidth + metrics.gap / 2f
        val junctionX = if (metrics.rtl) width - gutterX else gutterX
        for (slot in 0 until shown.junctionCount) {
            val row = ShoalJunctions.rowAbove(slot)
            val junctionY = (row + 1) * metrics.rowHeight + row * metrics.gap + metrics.gap / 2f
            if ((local - Offset(junctionX, junctionY)).getDistance() > metrics.junctionReach) {
                continue
            }
            val own = committed.placeOf(spec) == ShoalPlace.Junction(slot)
            if (own || committed.toJunction(spec, slot) != null) {
                return ShoalDropTarget.Junction(slot)
            }
        }

        // Above the middle of the gap between the two sections is the big tiles' side.
        val circleOrigin = circles.positionInRoot()
        val boundary = (wideOrigin.y + wide.size.height + circleOrigin.y) / 2f
        if (position.y < boundary) {
            val row = floor((local.y + metrics.gap / 2f) / rowPitch).toInt().coerceAtLeast(0)
            val onStartSide = (local.x < width / 2f) != metrics.rtl
            return ShoalDropTarget.Big(row * 2 + if (onStartSide) 0 else 1)
        }

        val shownCircles = shown.shownCircles
        val geometry = honeycomb
        if (geometry == null || geometry.positions.isEmpty() || shownCircles.isEmpty()) {
            return ShoalDropTarget.Circle(0)
        }
        val circleLocal = position - circleOrigin
        val half = geometry.cell / 2f
        var nearest = 0
        var nearestDistance = Float.MAX_VALUE
        for (index in 0 until minOf(shownCircles.size, geometry.positions.size)) {
            val cell = geometry.positions[index]
            val distance = (circleLocal - Offset(cell.x + half, cell.y + half)).getDistance()
            if (distance < nearestDistance) {
                nearest = index
                nearestDistance = distance
            }
        }
        // A tile that is already a circle takes the cell it is over. One that comes in from
        // elsewhere goes before or after the nearest circle, whichever side it is on.
        val after = spec !in shownCircles && circleLocal.x > geometry.positions[nearest].x + half
        return ShoalDropTarget.Circle(if (after) nearest + 1 else nearest)
    }
}

/** What [ShoalCollapsedLayout] measured, for working out which of its places a drag is over. */
private class ShoalCollapsedGrid(val columns: Int, val cellWidth: Float, val rowPitch: Int)

/**
 * The collapsed panel as the options of a tile need it: its [tiles] (the automatic ones if none are
 * picked, see [ShoalEditLayout.collapsedOr]), the tiles per row and how many tiles it shows.
 */
private data class ShoalEditCollapsed(
    val tiles: List<TileSpec>,
    val columns: Int,
    val capacity: Int,
) {
    val isFull: Boolean
        get() = tiles.size >= capacity
}

/** Pixel sizes the drop targets are worked out with; the same ones [ShoalWideLayout] uses. */
private class ShoalEditMetrics(
    val gap: Int,
    val rowHeight: Int,
    val junctionReach: Float,
    val rtl: Boolean,
)

/**
 * @param collapsedTiles the tiles picked for the collapsed panel as stored; empty while none are
 * @param collapsedColumns tiles per row of the collapsed panel
 * @param collapsedRows rows of the collapsed panel
 * @param onCollapsedColumns sets the tiles per row; written at once, it is not part of a layout
 * @param onCollapsedRows sets the rows, likewise
 */
@Composable
internal fun ShoalEditTileGrid(
    tiles: List<EditTileViewModel>,
    largeTiles: Set<TileSpec>,
    junctionTiles: List<TileSpec?>,
    collapsedTiles: List<TileSpec>,
    collapsedColumns: Int,
    collapsedRows: Int,
    collapsedColumnsRange: IntRange,
    collapsedRowsRange: IntRange,
    canUndo: Boolean,
    topBarActions: List<EditTopBarActionViewModel>,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onStopEditing: () -> Unit,
    onLayout: (ShoalEditLayout) -> Unit,
    onCollapsedColumns: (Int) -> Unit,
    onCollapsedRows: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val state = remember { ShoalEditState() }
    val accent = rememberShoalAccent()
    val fonts = rememberShoalQsFonts()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val metrics =
        remember(density, rtl) {
            with(density) {
                ShoalEditMetrics(
                    gap = ShoalQs.WIDE_GAP.roundToPx(),
                    rowHeight = ShoalQs.WIDE_HEIGHT.roundToPx(),
                    junctionReach = ShoalEdit.JUNCTION_REACH.toPx(),
                    rtl = rtl,
                )
            }
        }

    val bySpec = remember(tiles) { tiles.associateBy { it.tileSpec } }
    val currentSpecs = remember(tiles) { tiles.filter { it.isCurrent }.map { it.tileSpec } }
    val stored =
        remember(currentSpecs, largeTiles, junctionTiles, collapsedTiles) {
            ShoalEditLayout.of(currentSpecs, largeTiles, junctionTiles, collapsedTiles)
        }
    val committed = state.pending ?: stored
    val layout = state.preview ?: committed
    val currentCommitted by rememberUpdatedState(committed)
    val currentOnLayout by rememberUpdatedState(onLayout)

    // The collapsed panel's tiles of a layout. While none are picked they are the first of the
    // tile list, and that list is only rewritten (big tiles, junctions, circles) by an edit: the
    // stored layout still has it in the order it came in.
    val collapsedCapacity = collapsedColumns * collapsedRows
    val committedCollapsed =
        remember(committed, stored, currentSpecs, collapsedCapacity) {
            committed.collapsedOr(
                if (committed == stored) currentSpecs else committed.tileSpecs(),
                collapsedCapacity,
            )
        }
    val shownCollapsed =
        remember(layout, stored, currentSpecs, collapsedCapacity) {
            layout
                .collapsedOr(
                    if (layout == stored) currentSpecs else layout.tileSpecs(),
                    collapsedCapacity,
                )
                .take(collapsedCapacity)
        }
    val currentCollapsed by rememberUpdatedState(committedCollapsed)

    // Drop the stand-in once the stores agree with it, or after a while if they never do (an edit
    // the tile list refused, say).
    LaunchedEffect(stored, state.pending) {
        val pending = state.pending ?: return@LaunchedEffect
        if (pending != stored) delay(ShoalEdit.PENDING_TIMEOUT_MILLIS)
        state.pending = null
    }
    LaunchedEffect(committed, committedCollapsed, collapsedCapacity) {
        val selected = state.selected
        if (selected != null && selected !in committed) state.selected = null
        // A tile selected in the collapsed section has to still be shown there.
        if (
            selected != null &&
                state.selectedInCollapsed &&
                selected !in committedCollapsed.take(collapsedCapacity)
        ) {
            state.selected = null
        }
        val slot = state.pickSlot
        if (
            slot != null &&
                (slot >= committed.junctionCount || committed.junctionTile(slot) != null)
        ) {
            state.pickSlot = null
        }
    }

    val commit: (ShoalEditLayout?) -> Unit = { next ->
        if (next != null && next != currentCommitted) {
            state.pending = next
            currentOnLayout(next)
        }
    }
    val select: (TileSpec, Boolean) -> Unit = { spec, inCollapsed ->
        state.pickSlot = null
        val again = state.selected == spec && state.selectedInCollapsed == inCollapsed
        state.selected = if (again) null else spec
        state.selectedInCollapsed = inCollapsed
    }
    val onTileClick: (TileSpec) -> Unit = { spec -> select(spec, false) }
    val onJunctionClick: (Int) -> Unit = { slot ->
        val tile = currentCommitted.junctionTile(slot)
        // A tile selected in the collapsed section is not on its way to a junction.
        val selected = state.selected?.takeIf { !state.selectedInCollapsed }
        when {
            // Tap then tap: the selected tile goes to the junction, swapping with what is there.
            selected != null && selected != tile -> {
                val next = currentCommitted.toJunction(selected, slot)
                if (next != null) commit(next) else if (tile != null) onTileClick(tile)
            }
            tile != null -> onTileClick(tile)
            else -> state.pickSlot = if (state.pickSlot == slot) null else slot
        }
    }
    val onTrayClick: (TileSpec) -> Unit = { spec ->
        val slot = state.pickSlot
        state.pickSlot = null
        commit(
            slot?.let { currentCommitted.toJunction(spec, it) } ?: currentCommitted.toCircle(spec)
        )
    }
    // Tap then tap, as with a junction: the tile selected in the expanded panel is added to the
    // collapsed one. A free place is only there while the section is not full.
    val onCollapsedSlotClick: () -> Unit = {
        val selected = state.selected?.takeIf { !state.selectedInCollapsed }
        if (selected != null && selected in currentCommitted && selected !in currentCollapsed) {
            commit(currentCommitted.collapsedAt(currentCollapsed, selected))
        }
    }
    val onDragStart: () -> Unit = { state.selected = null }
    val onCollapsedDragStart: () -> Unit = {
        state.selected = null
        state.onStartedInCollapsed()
    }

    ShoalEditAutoScroll(
        state,
        scrollState,
        committed = { currentCommitted },
        collapsed = { currentCollapsed },
        metrics = metrics,
    )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .consumeWindowInsets(WindowInsets.displayCutout)
                .dragAndDropTileArea(
                    dragAndDropState = state,
                    onMoved = { state.hover(it, currentCommitted, currentCollapsed, metrics) },
                    onDrop = { commit(state.preview) },
                )
                .sysuiResTag(SHOAL_EDIT_ROOT_TEST_TAG)
    ) {
        ShoalEditTopBar(
            accent = accent,
            fonts = fonts,
            canUndo = canUndo,
            actions = topBarActions,
            onUndo = {
                // What is undone is the stored layout, so stop showing ours.
                state.pending = null
                onUndo()
            },
            onReset = onReset,
            onStopEditing = onStopEditing,
            modifier = Modifier.statusBarsPadding(),
        )

        val selectedSpec = state.selected
        val selectedInCollapsed = state.selectedInCollapsed
        // The selection as the expanded panel shows it: its copy of a tile is not the selected
        // one while the tile was tapped in the collapsed section.
        val expandedSelection = selectedSpec?.takeIf { !selectedInCollapsed }
        val markedJunctions =
            remember(committed, expandedSelection) {
                if (expandedSelection == null) {
                    emptySet()
                } else {
                    committed.junctionsFor(expandedSelection)
                }
            }
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { state.viewport = it.boundsInRoot() }
                    .nestedScroll(ShoalEditScrollLock)
                    .verticalScroll(scrollState)
                    .padding(top = 4.dp, bottom = 24.dp)
        ) {
            ShoalEditPartLabel(
                title = stringResource(R.string.ichthys_qs_edit_collapsed_panel),
                count =
                    stringResource(
                        R.string.ichthys_qs_edit_collapsed_count,
                        shownCollapsed.size,
                        collapsedCapacity,
                    ),
                hint =
                    stringResource(
                        if (layout.collapsed.isEmpty()) {
                            R.string.ichthys_qs_edit_collapsed_automatic
                        } else {
                            R.string.ichthys_qs_edit_drag_to_reorder
                        }
                    ),
                accent = accent,
                fonts = fonts,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShoalEditStepper(
                    label = stringResource(R.string.ichthys_qs_edit_collapsed_per_row),
                    value = collapsedColumns,
                    range = collapsedColumnsRange,
                    decreaseDescription =
                        stringResource(R.string.ichthys_qs_edit_collapsed_per_row_fewer),
                    increaseDescription =
                        stringResource(R.string.ichthys_qs_edit_collapsed_per_row_more),
                    fonts = fonts,
                    onValue = onCollapsedColumns,
                )
                Spacer(Modifier.weight(1f))
                ShoalEditStepper(
                    label = stringResource(R.string.ichthys_qs_edit_collapsed_rows),
                    value = collapsedRows,
                    range = collapsedRowsRange,
                    decreaseDescription =
                        stringResource(R.string.ichthys_qs_edit_collapsed_rows_fewer),
                    increaseDescription =
                        stringResource(R.string.ichthys_qs_edit_collapsed_rows_more),
                    fonts = fonts,
                    onValue = onCollapsedRows,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().onGloballyPositioned { state.collapsedCoordinates = it }) {
                // The free places take the tile selected in the expanded panel, if it is not
                // here already.
                val canAdd = expandedSelection != null && expandedSelection !in shownCollapsed
                ShoalCollapsedLayout(
                    columns = collapsedColumns,
                    modifier = Modifier.fillMaxWidth(),
                    onGrid = { state.collapsedGrid = it },
                ) {
                    shownCollapsed.forEachIndexed { index, spec ->
                        key(spec) {
                            ShoalEditCircleTile(
                                tile = bySpec[spec],
                                selected = selectedInCollapsed && spec == selectedSpec,
                                moving = state.draggedFromCollapsed && state.isMoving(spec),
                                accent = accent,
                                dragState = state,
                                onClick = { select(spec, true) },
                                onDragStart = onCollapsedDragStart,
                                description =
                                    stringResource(
                                        R.string.ichthys_qs_edit_collapsed_tile,
                                        bySpec[spec]?.label?.text ?: "",
                                        index + 1,
                                    ),
                            )
                        }
                    }
                    repeat(collapsedCapacity - shownCollapsed.size) { free ->
                        key(free) {
                            ShoalEditCollapsedSlot(
                                marked = canAdd,
                                accent = accent,
                                onClick = onCollapsedSlotClick,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(ShoalQsColors.Hairline))
            Spacer(Modifier.height(8.dp))
            ShoalEditPartLabel(
                title = stringResource(R.string.ichthys_qs_edit_expanded_panel),
                count = layout.tileSpecs().size.toString(),
                hint = null,
                accent = accent,
                fonts = fonts,
            )
            ShoalEditSectionLabel(
                label = stringResource(R.string.ichthys_qs_edit_big_tiles),
                count = layout.big.size,
                hint = stringResource(R.string.ichthys_qs_edit_drag_to_reorder),
                accent = accent,
                fonts = fonts,
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().onGloballyPositioned { state.wideCoordinates = it }) {
                if (layout.big.isEmpty()) {
                    ShoalEditEmptySection(
                        text = stringResource(R.string.ichthys_qs_edit_no_big_tiles),
                        fonts = fonts,
                        modifier = Modifier.fillMaxWidth().height(ShoalQs.WIDE_HEIGHT),
                    )
                } else {
                    val junctions =
                        remember(layout.junctionCount) { List(layout.junctionCount) { it } }
                    ShoalWideLayout(
                        wideCount = layout.big.size,
                        junctions = junctions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        layout.big.forEachIndexed { index, spec ->
                            key(spec) {
                                val row = index / 2
                                // In the editor every junction is drawn, free or not, so every
                                // corner next to one is scooped.
                                val shape =
                                    remember(index, layout.junctionCount) {
                                        ShoalScoopedShape(
                                            scoopAbove = ShoalJunctions.below(row - 1) in junctions,
                                            scoopBelow = ShoalJunctions.below(row) in junctions,
                                            innerOnEnd = index % 2 == 0,
                                        )
                                    }
                                ShoalEditWideTile(
                                    tile = bySpec[spec],
                                    shape = shape,
                                    selected = spec == expandedSelection,
                                    moving = !state.draggedFromCollapsed && state.isMoving(spec),
                                    accent = accent,
                                    fonts = fonts,
                                    dragState = state,
                                    onClick = { onTileClick(spec) },
                                    onDragStart = onDragStart,
                                )
                            }
                        }
                        junctions.forEach { slot ->
                            key(slot) {
                                val spec = layout.junctionTile(slot)
                                ShoalEditJunction(
                                    slot = slot,
                                    tile = spec?.let { bySpec[it] },
                                    selected = spec != null && spec == expandedSelection,
                                    marked = slot in markedJunctions || slot == state.pickSlot,
                                    moving =
                                        spec != null &&
                                            !state.draggedFromCollapsed &&
                                            state.isMoving(spec),
                                    accent = accent,
                                    dragState = state,
                                    onClick = { onJunctionClick(slot) },
                                    onDragStart = onDragStart,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            val circles = layout.shownCircles
            ShoalEditSectionLabel(
                label = stringResource(R.string.ichthys_qs_edit_circles),
                count = circles.size,
                hint = null,
                accent = accent,
                fonts = fonts,
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().onGloballyPositioned { state.circleCoordinates = it }) {
                if (circles.isEmpty()) {
                    ShoalEditEmptySection(
                        text = stringResource(R.string.ichthys_qs_edit_no_circles),
                        fonts = fonts,
                        modifier = Modifier.fillMaxWidth().height(ShoalQs.CELL),
                    )
                } else {
                    val rows = remember(circles.size) { honeycombRows(circles.size) }
                    HoneycombLayout(
                        rows = rows,
                        modifier = Modifier.fillMaxWidth(),
                        onGeometry = { state.honeycomb = it },
                    ) {
                        circles.forEach { spec ->
                            key(spec) {
                                ShoalEditCircleTile(
                                    tile = bySpec[spec],
                                    selected = spec == expandedSelection,
                                    moving = !state.draggedFromCollapsed && state.isMoving(spec),
                                    accent = accent,
                                    dragState = state,
                                    onClick = { onTileClick(spec) },
                                    onDragStart = onDragStart,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            val pickSlot = state.pickSlot
            val selectedName = selectedSpec?.let { bySpec[it]?.label?.text }
            BasicText(
                text =
                    when {
                        pickSlot != null ->
                            stringResource(R.string.ichthys_qs_edit_hint_pick, pickSlot + 1)
                        selectedName != null && markedJunctions.isNotEmpty() ->
                            stringResource(
                                R.string.ichthys_qs_edit_hint_selected_junctions,
                                selectedName,
                            )
                        selectedName != null ->
                            stringResource(R.string.ichthys_qs_edit_hint_selected, selectedName)
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

        ShoalEditTray(
            state = state,
            layout = committed,
            collapsed =
                ShoalEditCollapsed(
                    tiles = committedCollapsed,
                    columns = collapsedColumns,
                    capacity = collapsedCapacity,
                ),
            tiles = tiles,
            selected = selectedSpec?.let { bySpec[it] },
            selectedInCollapsed = selectedInCollapsed,
            accent = accent,
            fonts = fonts,
            onTrayClick = onTrayClick,
            onDeselect = { state.selected = null },
            onLayout = commit,
        )
    }
}

/**
 * Keeps what the panel does not scroll itself from travelling further up. With the tray pinned, the
 * panel often fits without scrolling; a swipe on it would otherwise be handed on to the scene
 * around the editor, whose swipe closes quick settings, and a tile dragged without holding it first
 * would do just that.
 */
private object ShoalEditScrollLock : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/**
 * Scrolls the panel while a drag is near the top or bottom edge of the part that scrolls, and keeps
 * the drop preview up to date while the tiles move under a finger that is not moving.
 */
@Composable
private fun ShoalEditAutoScroll(
    state: ShoalEditState,
    scrollState: ScrollState,
    committed: () -> ShoalEditLayout,
    collapsed: () -> List<TileSpec>,
    metrics: ShoalEditMetrics,
) {
    val zone = with(LocalDensity.current) { ShoalEdit.AUTO_SCROLL_ZONE.toPx() }
    val scrollTarget by
        remember(state, scrollState, zone) {
            derivedStateOf {
                val position = state.draggedPosition
                val viewport = state.viewport
                if (!position.isSpecified || viewport == null) {
                    null
                } else {
                    // The drag position and the viewport are both in root coordinates, so the
                    // zones are measured from where the scrolling part really begins and ends.
                    // Only inside it: below it is the tray, where a tile is dropped to remove
                    // it, and that must not scroll.
                    when {
                        position.y < viewport.top + zone -> 0
                        position.y > viewport.bottom - zone && position.y <= viewport.bottom ->
                            scrollState.maxValue
                        else -> null
                    }
                }
            }
        }
    LaunchedEffect(scrollTarget) {
        scrollTarget?.let {
            // The duration follows the distance, for the same speed everywhere.
            val distance = abs(it - scrollState.value)
            scrollState.animateScrollTo(
                it,
                animationSpec =
                    tween(
                        durationMillis = distance * ShoalEdit.AUTO_SCROLL_MILLIS_PER_PIXEL,
                        easing = LinearEasing,
                    ),
            )
        }
    }
    LaunchedEffect(state, scrollState) {
        snapshotFlow { scrollState.value }
            .collect {
                val position = state.draggedPosition
                if (position.isSpecified) {
                    state.hover(position, committed(), collapsed(), metrics)
                }
            }
    }
}

// region top bar

@Composable
private fun ShoalEditTopBar(
    accent: Color,
    fonts: ShoalQsFonts,
    canUndo: Boolean,
    actions: List<EditTopBarActionViewModel>,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onStopEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(ShoalEdit.TOP_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
    ) {
        ShoalEditButton(
            text = stringResource(R.string.ichthys_qs_edit_done),
            style = ShoalEditButtonStyle.Accent,
            accent = accent,
            fonts = fonts,
            onClick = onStopEditing,
            modifier = Modifier.height(ShoalEdit.BUTTON),
            horizontalPadding = 16.dp,
        )
        BasicText(
            text = stringResource(R.string.qs_edit_tiles),
            style =
                TextStyle(
                    color = ShoalQsColors.OnTileOff,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = fonts.display,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        ShoalEditRoundButton(
            contentDescription = stringResource(com.android.internal.R.string.undo),
            enabled = canUndo,
            onClick = onUndo,
        ) {
            ShoalEditVectorGlyph(Undo, ShoalQsColors.Label)
        }
        // The stock editor's extra actions (the shade layout settings), kept reachable.
        actions.forEach { action ->
            ShoalEditRoundButton(
                contentDescription = stringResource(action.labelId),
                onClick = action.onClick,
            ) {
                ShoalEditVectorGlyph(action.icon, ShoalQsColors.Label)
            }
        }
        ShoalEditButton(
            text = stringResource(com.android.internal.R.string.reset),
            style = ShoalEditButtonStyle.Outline,
            accent = accent,
            fonts = fonts,
            onClick = onReset,
            modifier = Modifier.height(ShoalEdit.BUTTON),
            horizontalPadding = 14.dp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// endregion

// region panel

/**
 * The heading of one of the editor's two parts, the collapsed panel and the expanded one. Set like
 * the tray's title, a step above the section labels that follow it.
 */
@Composable
private fun ShoalEditPartLabel(
    title: String,
    count: String,
    hint: String?,
    accent: Color,
    fonts: ShoalQsFonts,
) {
    val mono = TextStyle(color = ShoalQsColors.Muted, fontSize = 11.sp, fontFamily = fonts.mono)
    Row(
        modifier = Modifier.fillMaxWidth().height(ShoalEdit.PART_LABEL_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
    ) {
        BasicText(
            text = title,
            style =
                TextStyle(
                    color = ShoalQsColors.OnTileOff,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = fonts.display,
                ),
            maxLines = 1,
        )
        BasicText(text = count, style = mono.copy(color = accent), maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (hint != null) BasicText(text = hint, style = mono, maxLines = 1)
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
 * The tiles of the collapsed section in rows of [columns], the way the collapsed panel lays them
 * out: every place is an equal share of the width, with its circle in the middle. The circle is
 * [ShoalEdit.COLLAPSED_CELL] across, or less where the places are too narrow for that.
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
        val cellWidth = width.toFloat() / perRow
        val circle =
            minOf(
                    ShoalEdit.COLLAPSED_CELL.roundToPx(),
                    (cellWidth - ShoalEdit.COLLAPSED_MIN_GAP.toPx()).toInt(),
                )
                .coerceAtLeast(0)
        val rowGap = ShoalEdit.COLLAPSED_ROW_GAP.roundToPx()
        val rowPitch = circle + rowGap
        val placeables = measurables.map { it.measure(Constraints.fixed(circle, circle)) }
        val rows = (placeables.size + perRow - 1) / perRow
        onGrid(ShoalCollapsedGrid(perRow, cellWidth, rowPitch))
        layout(width, (rows * rowPitch - rowGap).coerceAtLeast(0)) {
            placeables.forEachIndexed { index, placeable ->
                val x = (index % perRow) * cellWidth + (cellWidth - circle) / 2f
                placeable.placeRelative(x.roundToInt(), (index / perRow) * rowPitch)
            }
        }
    }
}

/**
 * A free place of the collapsed section: a dashed circle, as a free junction is.
 *
 * @param marked the tile selected in the expanded panel can be put here, with a tap
 */
@Composable
private fun ShoalEditCollapsedSlot(
    marked: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.ichthys_qs_edit_collapsed_slot)
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .drawBehind {
                    val radius = size.minDimension / 2f
                    if (marked) {
                        drawJunctionMark(accent, radius)
                    } else {
                        drawCircle(
                            ShoalQsColors.Muted,
                            radius - ShoalEdit.DASH_STROKE.toPx() / 2f,
                            alpha = ShoalEdit.FREE_SLOT_ALPHA,
                            style = dashedStroke(),
                        )
                    }
                }
                .clickable(enabled = marked, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (marked) ShoalEditGlyph(glyph = ShoalEditGlyphs.Plus, color = accent)
    }
}

@Composable
private fun ShoalEditSectionLabel(
    label: String,
    count: Int,
    hint: String?,
    accent: Color,
    fonts: ShoalQsFonts,
) {
    val style = TextStyle(color = ShoalQsColors.Muted, fontSize = 11.sp, fontFamily = fonts.mono)
    Row(
        modifier = Modifier.fillMaxWidth().height(ShoalEdit.SECTION_LABEL_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
    ) {
        BasicText(text = label.uppercase(), style = style.copy(letterSpacing = 0.08.em))
        BasicText(text = count.toString(), style = style.copy(color = accent))
        Spacer(Modifier.weight(1f))
        if (hint != null) BasicText(text = hint, style = style)
    }
}

/** Stands in for a section with no tiles, so that there is still something to drop on. */
@Composable
private fun ShoalEditEmptySection(
    text: String,
    fonts: ShoalQsFonts,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.drawBehind {
                val inset = ShoalEdit.DASH_STROKE.toPx() / 2f
                drawRoundRect(
                    color = ShoalQsColors.Muted,
                    alpha = ShoalEdit.FREE_SLOT_ALPHA,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius(ShoalQs.WIDE_RADIUS.toPx() - inset),
                    style = dashedStroke(),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style =
                TextStyle(color = ShoalQsColors.Muted, fontSize = 11.sp, fontFamily = fonts.mono),
        )
    }
}

/**
 * A big tile as the panel draws it (icon, label, scooped corners), without its subtitle: the editor
 * has no live state to show.
 */
@Composable
private fun ShoalEditWideTile(
    tile: EditTileViewModel?,
    shape: ShoalScoopedShape,
    selected: Boolean,
    moving: Boolean,
    accent: Color,
    fonts: ShoalQsFonts,
    dragState: DragAndDropState,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    if (tile == null || moving) {
        // Where the dragged tile would land. (No view model at all should not happen; the cell
        // is kept so that the layout's child count holds.)
        Box(
            modifier.animatePlacement().thenIf(moving) {
                Modifier.drawBehind {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    drawOutline(outline, accent, alpha = ShoalEdit.MARK_FILL_ALPHA)
                    drawOutline(outline, accent, style = dashedStroke())
                }
            }
        )
        return
    }
    Row(
        modifier =
            modifier
                .animatePlacement()
                .dragAndDropTileSource(
                    SizedTileImpl(tile, 2),
                    dragState,
                    DragType.Move,
                    onDragStart,
                )
                .thenIf(selected) {
                    Modifier.drawBehind {
                        // The ring stops at the scoops instead of running through the junctions.
                        val scoops = shape.scoops(size, layoutDirection, this)
                        val radius = ShoalQs.WIDE_RADIUS.toPx()
                        if (scoops != null) {
                            clipPath(scoops, clipOp = ClipOp.Difference) {
                                drawSelectionRing(accent, radius)
                            }
                        } else {
                            drawSelectionRing(accent, radius)
                        }
                    }
                }
                .clip(shape)
                .background(ShoalQsColors.TileOff)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = tile.label.text
                    this.selected = selected
                }
                .padding(horizontal = ShoalQs.WIDE_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Same metrics as the live tile, so the label sits where it does in the panel.
        Box(Modifier.size(ShoalQs.WIDE_ICON_TARGET), contentAlignment = Alignment.CenterStart) {
            SmallTileContent(
                iconProvider = { tile.icon },
                color = ShoalQsColors.OnTileOff,
                animateToEnd = true,
                size = { ShoalQs.WIDE_ICON },
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Spacer(Modifier.width(ShoalQs.WIDE_ICON_TEXT_GAP))
        BasicText(
            text = tile.label.text,
            style =
                TextStyle(
                    color = ShoalQsColors.OnTileOff,
                    fontSize = ShoalQs.WIDE_LABEL_SIZE,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = fonts.text,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clearAndSetSemantics {},
        )
    }
}

@Composable
private fun ShoalEditCircleTile(
    tile: EditTileViewModel?,
    selected: Boolean,
    moving: Boolean,
    accent: Color,
    dragState: DragAndDropState,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    modifier: Modifier = Modifier,
    // What the tile is announced as; its name unless the place needs saying too.
    description: String? = null,
) {
    if (tile == null || moving) {
        Box(
            modifier.animatePlacement().thenIf(moving) {
                Modifier.drawBehind { drawJunctionMark(accent, size.minDimension / 2f) }
            }
        )
        return
    }
    Box(
        modifier =
            modifier
                .animatePlacement()
                .dragAndDropTileSource(
                    SizedTileImpl(tile, 1),
                    dragState,
                    DragType.Move,
                    onDragStart,
                )
                .thenIf(selected) {
                    Modifier.drawBehind { drawSelectionRing(accent, size.minDimension / 2f) }
                }
                .clip(CircleShape)
                .background(ShoalQsColors.TileOff)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = description ?: tile.label.text
                    this.selected = selected
                },
        contentAlignment = Alignment.Center,
    ) {
        SmallTileContent(
            iconProvider = { tile.icon },
            color = ShoalQsColors.OnTileOff,
            animateToEnd = true,
            size = { ShoalQs.CIRCLE_ICON },
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * A junction slot. With a tile: the [ShoalQs.JUNCTION] circle the panel draws. Free: a dashed
 * circle with a plus. Its bounds are the whole scoop, like the live junction tile's.
 *
 * @param marked the junction is offered as a target: the selected tile can move here, or the tray
 *   is picking a tile for it
 * @param moving its tile is the one being dragged (so it shows as the landing place)
 */
@Composable
private fun ShoalEditJunction(
    slot: Int,
    tile: EditTileViewModel?,
    selected: Boolean,
    marked: Boolean,
    moving: Boolean,
    accent: Color,
    dragState: DragAndDropState,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = tile?.takeIf { !moving }
    val description =
        if (shown != null) {
            stringResource(R.string.ichthys_qs_edit_junction_tile, shown.label.text, slot + 1)
        } else {
            stringResource(R.string.ichthys_qs_edit_junction_empty, slot + 1)
        }
    val source =
        if (shown != null) {
            Modifier.dragAndDropTileSource(
                SizedTileImpl(shown, 1),
                dragState,
                DragType.Move,
                onDragStart,
            )
        } else {
            Modifier
        }
    Box(
        modifier =
            modifier
                .then(source)
                .clip(CircleShape)
                .drawBehind {
                    val radius = ShoalQs.JUNCTION.toPx() / 2f
                    // The moat between the circle and the scooped corners carries the rings.
                    val moat = radius + ShoalQs.JUNCTION_MOAT.toPx() / 2f
                    if (shown != null) {
                        drawCircle(ShoalQsColors.TileOff, radius)
                        if (selected) {
                            drawCircle(
                                accent,
                                moat,
                                style = Stroke(ShoalEdit.SELECTION_STROKE.toPx()),
                            )
                        } else if (marked) {
                            drawCircle(accent, moat, style = dashedStroke())
                        }
                    } else if (marked || moving) {
                        drawJunctionMark(accent, radius)
                    } else {
                        drawCircle(
                            ShoalQsColors.Muted,
                            radius - ShoalEdit.DASH_STROKE.toPx() / 2f,
                            alpha = ShoalEdit.FREE_SLOT_ALPHA,
                            style = dashedStroke(),
                        )
                    }
                }
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = description
                    this.selected = selected
                },
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            SmallTileContent(
                iconProvider = { shown.icon },
                color = ShoalQsColors.OnTileOff,
                animateToEnd = true,
                size = { ShoalQs.JUNCTION_ICON },
                modifier = Modifier.clearAndSetSemantics {},
            )
        } else {
            ShoalEditGlyph(
                glyph = ShoalEditGlyphs.Plus,
                color = if (marked || moving) accent else ShoalQsColors.Muted,
            )
        }
    }
}

// endregion

// region tray

/**
 * The tray pinned under the panel. Normally the tiles that can be added: a handle, the title with a
 * count, the category chips and one row of circles. While a tile is selected it shows that tile's
 * options instead, and while one is dragged it folds down to its title row to give the panel room
 * (and to be the place to drop a tile to remove it).
 */
@Composable
private fun ShoalEditTray(
    state: ShoalEditState,
    layout: ShoalEditLayout,
    collapsed: ShoalEditCollapsed,
    tiles: List<EditTileViewModel>,
    selected: EditTileViewModel?,
    selectedInCollapsed: Boolean,
    accent: Color,
    fonts: ShoalQsFonts,
    onTrayClick: (TileSpec) -> Unit,
    onDeselect: () -> Unit,
    onLayout: (ShoalEditLayout?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The editor is laid out inside the shade's horizontal padding. The tray is the one part
    // that runs from edge to edge, so it reaches out by that padding and pads its rows back in.
    val bleed = dimensionResource(R.dimen.overlay_qs_layout_horizontal_padding)
    val available = remember(tiles, layout) { tiles.filter { it.tileSpec !in layout } }
    val dragging = state.dragInProgress

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = { -bleed.roundToPx() })
                .onGloballyPositioned { state.trayCoordinates = it }
                .drawBehind {
                    val radius = ShoalEdit.TRAY_RADIUS.toPx()
                    val corner = CornerRadius(radius)
                    val surface =
                        Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(Offset.Zero, size),
                                    topLeft = corner,
                                    topRight = corner,
                                )
                            )
                        }
                    drawPath(surface, ShoalQsColors.Track)
                    // The hairline along the top edge only, round the two corners.
                    val edge =
                        Path().apply {
                            moveTo(0f, radius)
                            arcTo(Rect(0f, 0f, 2 * radius, 2 * radius), 180f, 90f, false)
                            lineTo(size.width - radius, 0f)
                            arcTo(
                                Rect(size.width - 2 * radius, 0f, size.width, 2 * radius),
                                270f,
                                90f,
                                false,
                            )
                        }
                    drawPath(edge, ShoalQsColors.Hairline, style = Stroke(1.dp.toPx()))
                }
                .animateContentSize()
                .navigationBarsPadding()
                // Nothing in the tray scrolls vertically, so a vertical drag on it would go to
                // the scene's swipe (see ShoalEditScrollLock). It ends here instead. The strip
                // over the navigation bar is left out, so the system gesture area still works.
                .scrollable(rememberScrollableState { it }, Orientation.Vertical)
                .sysuiResTag(SHOAL_EDIT_TRAY_TEST_TAG)
    ) {
        if (selected != null && !dragging) {
            ShoalEditOptions(
                tile = selected,
                inCollapsed = selectedInCollapsed,
                layout = layout,
                collapsed = collapsed,
                availableCount = available.size,
                accent = accent,
                fonts = fonts,
                onDeselect = onDeselect,
                onLayout = onLayout,
                modifier = Modifier.padding(horizontal = bleed),
            )
        } else {
            ShoalEditAvailable(
                state = state,
                available = available,
                folded = dragging,
                accent = accent,
                fonts = fonts,
                bleed = bleed,
                onTrayClick = onTrayClick,
            )
        }
    }
}

@Composable
private fun ShoalEditAvailable(
    state: ShoalEditState,
    available: List<EditTileViewModel>,
    folded: Boolean,
    accent: Color,
    fonts: ShoalQsFonts,
    bleed: Dp,
    onTrayClick: (TileSpec) -> Unit,
) {
    // By category, then by name, as the stock editor lists them.
    val grouped = remember(available) { groupAndSort(available) }
    var category by remember { mutableStateOf<TileCategory?>(null) }
    // A category that has run out of tiles (the last one was added) falls back to All.
    val shownCategory = category?.takeIf { it in grouped }
    val shown =
        remember(grouped, shownCategory) {
            if (shownCategory == null) grouped.values.flatten()
            else grouped[shownCategory] ?: emptyList()
        }
    val pickSlot = state.pickSlot
    val monoStyle =
        TextStyle(color = ShoalQsColors.Muted, fontSize = 11.sp, fontFamily = fonts.mono)

    Column(
        modifier =
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = if (folded) 12.dp else 22.dp),
        verticalArrangement = spacedBy(12.dp),
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .background(ShoalQsColors.DotInactive, CircleShape)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = bleed),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = spacedBy(8.dp),
        ) {
            BasicText(
                text = stringResource(R.string.ichthys_qs_edit_available),
                style =
                    TextStyle(
                        color = ShoalQsColors.OnTileOff,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = fonts.display,
                    ),
            )
            BasicText(text = available.size.toString(), style = monoStyle.copy(color = accent))
            Spacer(Modifier.weight(1f))
            BasicText(
                text =
                    when {
                        folded && state.draggedFromCollapsed ->
                            stringResource(R.string.ichthys_qs_edit_tray_hint_remove_collapsed)
                        folded && state.dragType == DragType.Move && state.isDraggedCellRemovable ->
                            stringResource(R.string.ichthys_qs_edit_tray_hint_remove)
                        folded -> ""
                        pickSlot != null ->
                            stringResource(R.string.ichthys_qs_edit_tray_hint_pick, pickSlot + 1)
                        else -> stringResource(R.string.ichthys_qs_edit_tray_hint)
                    },
                style =
                    if (pickSlot != null && !folded) monoStyle.copy(color = accent) else monoStyle,
            )
        }
        if (folded) return@Column

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = bleed),
            horizontalArrangement = spacedBy(6.dp),
        ) {
            ShoalEditChip(
                text = stringResource(R.string.ichthys_qs_edit_category_all),
                selected = shownCategory == null,
                accent = accent,
                fonts = fonts,
                onClick = { category = null },
            )
            grouped.keys.forEach { entry ->
                ShoalEditChip(
                    text = entry.label.load() ?: "",
                    selected = shownCategory == entry,
                    accent = accent,
                    fonts = fonts,
                    onClick = { category = entry },
                )
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = bleed),
            horizontalArrangement = spacedBy(14.dp),
        ) {
            items(shown, key = { it.tileSpec.spec }) { tile ->
                ShoalEditTrayTile(
                    tile = tile,
                    fonts = fonts,
                    dragState = state,
                    onClick = { onTrayClick(tile.tileSpec) },
                )
            }
        }
    }
}

@Composable
private fun ShoalEditTrayTile(
    tile: EditTileViewModel,
    fonts: ShoalQsFonts,
    dragState: DragAndDropState,
    onClick: () -> Unit,
) {
    val addLabel =
        stringResource(R.string.accessibility_qs_edit_named_tile_add_action, tile.label.text)
    Column(
        modifier = Modifier.width(ShoalEdit.TRAY_ITEM_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier.size(ShoalQs.CELL)
                    .dragAndDropTileSource(SizedTileImpl(tile, 1), dragState, DragType.Add) {}
                    .clip(CircleShape)
                    .background(ShoalQsColors.TileOff)
                    .clickable(role = Role.Button, onClickLabel = addLabel, onClick = onClick)
                    .semantics { contentDescription = tile.label.text },
            contentAlignment = Alignment.Center,
        ) {
            SmallTileContent(
                iconProvider = { tile.icon },
                color = ShoalQsColors.OnTileOff,
                animateToEnd = true,
                size = { ShoalQs.CIRCLE_ICON },
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        BasicText(
            text = tile.label.text,
            style =
                TextStyle(
                    color = ShoalQsColors.Label,
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    textAlign = TextAlign.Center,
                    fontFamily = fonts.mono,
                ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * The options of the selected tile: what it is and where, a way out of the selection, and what can
 * be done with it. For a tile of the expanded panel that is three things, depending on where it is,
 * and under them whether it is in the collapsed panel as well. A tile selected in the collapsed
 * section ([inCollapsed]) can only be taken out of that panel.
 */
@Composable
private fun ShoalEditOptions(
    tile: EditTileViewModel,
    inCollapsed: Boolean,
    layout: ShoalEditLayout,
    collapsed: ShoalEditCollapsed,
    availableCount: Int,
    accent: Color,
    fonts: ShoalQsFonts,
    onDeselect: () -> Unit,
    onLayout: (ShoalEditLayout?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = tile.tileSpec
    // Null for a tile selected in the collapsed section: where it is in the expanded panel is
    // not what the options are about then.
    val place = if (inCollapsed) null else layout.placeOf(spec)
    // -1 when the tile is not in the collapsed panel.
    val collapsedIndex = collapsed.tiles.indexOf(spec)
    val placeText =
        if (inCollapsed) {
            stringResource(
                R.string.ichthys_qs_edit_place_collapsed,
                collapsedIndex.coerceAtLeast(0) / collapsed.columns.coerceAtLeast(1) + 1,
            )
        } else {
            when (place) {
                is ShoalPlace.Big ->
                    stringResource(
                        if (place.index % 2 == 0) R.string.ichthys_qs_edit_place_big_left
                        else R.string.ichthys_qs_edit_place_big_right,
                        place.index / 2 + 1,
                    )
                is ShoalPlace.Junction ->
                    stringResource(R.string.ichthys_qs_edit_place_junction, place.slot + 1)
                is ShoalPlace.Circle ->
                    stringResource(
                        R.string.ichthys_qs_edit_place_circle,
                        honeycombRowOf(place.index, layout.shownCircles.size) + 1,
                    )
                null -> ""
            }
        }
    val freeJunction = layout.freeJunctionFor(spec)
    val makeBig = stringResource(R.string.ichthys_qs_edit_make_big)
    val makeSmall = stringResource(R.string.ichthys_qs_edit_make_small)

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
        verticalArrangement = spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).border(1.5.dp, accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SmallTileContent(
                    iconProvider = { tile.icon },
                    color = accent,
                    animateToEnd = true,
                    size = { ShoalQs.CIRCLE_ICON },
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = spacedBy(2.dp)) {
                BasicText(
                    text = tile.label.text,
                    style =
                        TextStyle(
                            color = ShoalQsColors.OnTileOff,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = fonts.text,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = placeText,
                    style =
                        TextStyle(
                            color = ShoalQsColors.Muted,
                            fontSize = 11.sp,
                            fontFamily = fonts.mono,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ShoalEditRoundButton(
                contentDescription = stringResource(R.string.ichthys_qs_edit_deselect),
                onClick = onDeselect,
            ) {
                ShoalEditGlyph(ShoalEditGlyphs.Close, ShoalQsColors.Label)
            }
        }

        if (!inCollapsed) {
            Row(horizontalArrangement = spacedBy(8.dp)) {
                val actionModifier = Modifier.weight(1f).height(ShoalEdit.ACTION_HEIGHT)
                if (place is ShoalPlace.Junction) {
                    ShoalEditButton(
                        text = makeBig,
                        style = ShoalEditButtonStyle.Tile,
                        accent = accent,
                        fonts = fonts,
                        onClick = { onLayout(layout.toBig(spec)) },
                        modifier = actionModifier,
                    )
                    ShoalEditButton(
                        text = makeSmall,
                        style = ShoalEditButtonStyle.Tile,
                        accent = accent,
                        fonts = fonts,
                        onClick = { onLayout(layout.toCircle(spec, 0)) },
                        modifier = actionModifier,
                    )
                } else {
                    val isBig = place is ShoalPlace.Big
                    ShoalEditButton(
                        text = if (isBig) makeSmall else makeBig,
                        style = ShoalEditButtonStyle.Tile,
                        accent = accent,
                        fonts = fonts,
                        // A tile changing size lands on the edge of its new section nearest to
                        // where it was: the end of the big tiles, the front of the circles.
                        onClick = {
                            onLayout(if (isBig) layout.toCircle(spec, 0) else layout.toBig(spec))
                        },
                        modifier = actionModifier,
                    )
                    ShoalEditButton(
                        text = stringResource(R.string.ichthys_qs_edit_to_junction),
                        style = ShoalEditButtonStyle.Accent,
                        accent = accent,
                        fonts = fonts,
                        enabled = freeJunction != null,
                        onClick = { freeJunction?.let { onLayout(layout.toJunction(spec, it)) } },
                        modifier = actionModifier,
                    )
                }
                ShoalEditButton(
                    text = stringResource(R.string.qs_customize_remove),
                    style = ShoalEditButtonStyle.Outline,
                    accent = accent,
                    fonts = fonts,
                    enabled = tile.isRemovable,
                    onClick = { onLayout(layout.remove(spec)) },
                    modifier = actionModifier,
                )
            }
        }

        // In the collapsed panel as well, or not. For a tile selected in the collapsed section
        // this is all there is to do.
        val collapsedModifier = Modifier.fillMaxWidth().height(ShoalEdit.ACTION_HEIGHT)
        when {
            collapsedIndex >= 0 ->
                ShoalEditButton(
                    text = stringResource(R.string.ichthys_qs_edit_collapsed_remove),
                    style = ShoalEditButtonStyle.Outline,
                    accent = accent,
                    fonts = fonts,
                    onClick = { onLayout(layout.collapsedWithout(collapsed.tiles, spec)) },
                    modifier = collapsedModifier,
                )
            // No room: the button says so instead of offering what it cannot do.
            collapsed.isFull ->
                ShoalEditButton(
                    text = stringResource(R.string.ichthys_qs_edit_collapsed_full),
                    style = ShoalEditButtonStyle.Tile,
                    accent = accent,
                    fonts = fonts,
                    enabled = false,
                    onClick = {},
                    modifier = collapsedModifier,
                )
            else ->
                ShoalEditButton(
                    text = stringResource(R.string.ichthys_qs_edit_collapsed_add),
                    style = ShoalEditButtonStyle.Tile,
                    accent = accent,
                    fonts = fonts,
                    onClick = { onLayout(layout.collapsedAt(collapsed.tiles, spec)) },
                    modifier = collapsedModifier,
                )
        }

        // The folded tray: tapping it lets go of the tile and brings the available tiles back.
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(ShoalEdit.ACTION_HEIGHT)
                    .clip(CircleShape)
                    .border(1.dp, ShoalQsColors.Hairline, CircleShape)
                    .clickable(role = Role.Button, onClick = onDeselect)
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = spacedBy(8.dp),
        ) {
            BasicText(
                text = stringResource(R.string.ichthys_qs_edit_available),
                style =
                    TextStyle(
                        color = ShoalQsColors.Label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = fonts.text,
                    ),
            )
            BasicText(
                text = availableCount.toString(),
                style = TextStyle(color = accent, fontSize = 11.sp, fontFamily = fonts.mono),
            )
            Spacer(Modifier.weight(1f))
            ShoalEditGlyph(ShoalEditGlyphs.ChevronUp, ShoalQsColors.Label)
        }
    }
}

/** The honeycomb row (from 0) of the circle at [index] when there are [count] circles. */
private fun honeycombRowOf(index: Int, count: Int): Int {
    var before = 0
    honeycombRows(count).forEachIndexed { row, plan ->
        before += plan.size
        if (index < before) return row
    }
    return 0
}

// endregion

// region pieces

private enum class ShoalEditButtonStyle {
    /** Accent fill, ink text: the one thing to do next. */
    Accent,
    /** Tile surface. */
    Tile,
    /** Hairline outline on the ground. */
    Outline,
}

/** A pill button: fully rounded, in one of the three Shoal surfaces. */
@Composable
private fun ShoalEditButton(
    text: String,
    style: ShoalEditButtonStyle,
    accent: Color,
    fonts: ShoalQsFonts,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalPadding: Dp = 8.dp,
    fontWeight: FontWeight = FontWeight.SemiBold,
) {
    val content =
        when (style) {
            ShoalEditButtonStyle.Accent -> ShoalQsColors.OnAccent
            ShoalEditButtonStyle.Tile -> ShoalQsColors.OnTileOff
            ShoalEditButtonStyle.Outline -> ShoalQsColors.Label
        }
    Box(
        modifier =
            modifier
                .alpha(if (enabled) 1f else ShoalEdit.DISABLED_ALPHA)
                .clip(CircleShape)
                .then(
                    when (style) {
                        ShoalEditButtonStyle.Accent -> Modifier.background(accent)
                        ShoalEditButtonStyle.Tile -> Modifier.background(ShoalQsColors.TileOff)
                        ShoalEditButtonStyle.Outline ->
                            Modifier.border(1.dp, ShoalQsColors.Hairline, CircleShape)
                    }
                )
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style =
                TextStyle(
                    color = content,
                    fontSize = 13.sp,
                    fontWeight = fontWeight,
                    fontFamily = fonts.text,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A category chip of the tray: accent when it is the one shown, a hairline outline otherwise. */
@Composable
private fun ShoalEditChip(
    text: String,
    selected: Boolean,
    accent: Color,
    fonts: ShoalQsFonts,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.height(ShoalEdit.CHIP_HEIGHT)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(accent)
                    else Modifier.border(1.dp, ShoalQsColors.Hairline, CircleShape)
                )
                .clickable(role = Role.Tab, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style =
                TextStyle(
                    color = if (selected) ShoalQsColors.OnAccent else ShoalQsColors.Label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    fontFamily = fonts.text,
                ),
            maxLines = 1,
        )
    }
}

/** A [ShoalEdit.BUTTON] round button with a hairline outline, for a glyph. */
@Composable
private fun ShoalEditRoundButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(ShoalEdit.BUTTON)
                .alpha(if (enabled) 1f else ShoalEdit.DISABLED_ALPHA)
                .clip(CircleShape)
                .border(1.dp, ShoalQsColors.Hairline, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ShoalEditVectorGlyph(icon: ImageVector, color: Color) {
    Image(
        imageVector = icon,
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = Modifier.size(18.dp),
    )
}

private enum class ShoalEditGlyphs {
    Plus,
    Minus,
    Close,
    ChevronUp,
}

/** The editor's own line glyphs, 16 dp, drawn rather than taken from an icon set. */
@Composable
private fun ShoalEditGlyph(glyph: ShoalEditGlyphs, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(16.dp).drawBehind {
            val stroke = 1.8.dp.toPx()
            val w = size.width
            val h = size.height
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                drawLine(
                    color,
                    Offset(w * x1, h * y1),
                    Offset(w * x2, h * y2),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            when (glyph) {
                ShoalEditGlyphs.Plus -> {
                    line(0.5f, 0.2f, 0.5f, 0.8f)
                    line(0.2f, 0.5f, 0.8f, 0.5f)
                }
                ShoalEditGlyphs.Minus -> line(0.2f, 0.5f, 0.8f, 0.5f)
                ShoalEditGlyphs.Close -> {
                    line(0.25f, 0.25f, 0.75f, 0.75f)
                    line(0.75f, 0.25f, 0.25f, 0.75f)
                }
                ShoalEditGlyphs.ChevronUp -> {
                    line(0.25f, 0.62f, 0.5f, 0.38f)
                    line(0.5f, 0.38f, 0.75f, 0.62f)
                }
            }
        }
    )
}

private fun DrawScope.dashedStroke(): Stroke {
    return Stroke(
        width = ShoalEdit.DASH_STROKE.toPx(),
        pathEffect =
            PathEffect.dashPathEffect(
                floatArrayOf(ShoalEdit.DASH_ON.toPx(), ShoalEdit.DASH_OFF.toPx())
            ),
    )
}

/**
 * The ring around a selected tile: outside the tile, [ShoalEdit.SELECTION_GAP] away from it.
 * [cornerRadius] is the tile's own.
 */
private fun DrawScope.drawSelectionRing(color: Color, cornerRadius: Float) {
    val stroke = ShoalEdit.SELECTION_STROKE.toPx()
    val out = ShoalEdit.SELECTION_GAP.toPx() + stroke / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(-out, -out),
        size = Size(size.width + 2 * out, size.height + 2 * out),
        cornerRadius = CornerRadius(cornerRadius + out),
        style = Stroke(stroke),
    )
}

/** An accent wash and dashed ring of [radius]: a junction (or a place) that takes a tile. */
private fun DrawScope.drawJunctionMark(accent: Color, radius: Float) {
    drawCircle(accent, radius, alpha = ShoalEdit.MARK_FILL_ALPHA)
    drawCircle(accent, radius - ShoalEdit.DASH_STROKE.toPx() / 2f, style = dashedStroke())
}

/**
 * Slides a tile to its new place when the layout puts it somewhere else, instead of jumping. The
 * tile is offset back to where it was and the offset animates away.
 */
@Composable
private fun Modifier.animatePlacement(): Modifier {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(IntOffset.Zero) }
    var animatable by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    return onPlaced { target = it.positionInParent().round() }
        .offset {
            // onPlaced runs before this, so the first placement starts at its target and does
            // not animate in.
            val animation =
                animatable ?: Animatable(target, IntOffset.VectorConverter).also { animatable = it }
            if (animation.targetValue != target) {
                scope.launch {
                    animation.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
            animation.value - target
        }
}

// endregion

private const val SHOAL_EDIT_ROOT_TEST_TAG = "ShoalEditModeRoot"
private const val SHOAL_EDIT_TRAY_TEST_TAG = "ShoalEditModeTray"
