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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * IchthysOS "Shoal" tile editor (Settings.Secure ichthys_qs_style = "shoal").
 *
 * It has two pages, swiped between or picked from the two tabs under the top bar. The two panels
 * are independent: a tile can be in either, in both or in neither.
 *
 * The first page shows the expanded panel the way [ShoalTileGrid] lays it out, editable in place:
 * the big tiles two per row with a junction slot on every meeting point, then the honeycomb of
 * circles. The tiles that are not in the panel sit in a tray pinned to the bottom, outside the
 * scrolling part. Tapping a tile selects it and turns the tray into its options; holding one drags
 * it. The second page does the same for the collapsed panel (see [ShoalEditCollapsedPage]).
 *
 * Every edit is one [ShoalEditLayout] handed to [onLayout]; the caller snapshots (undo) and writes
 * it. The collapsed panel's tiles per row and rows are the exception: they are plain settings,
 * written at once. Nothing here writes settings itself.
 */
internal object ShoalEdit {
    val TOP_BAR_HEIGHT = 56.dp
    /** Height of the top bar's buttons and of the round buttons elsewhere. */
    val BUTTON = 36.dp
    /** Height of the two tabs under the top bar, and the ground left around the marked one. */
    val TABS_HEIGHT = 34.dp
    val TABS_INSET = 3.dp
    val SECTION_LABEL_HEIGHT = 26.dp
    /** Height of the options bar's action buttons. */
    val ACTION_HEIGHT = 44.dp
    val CHIP_HEIGHT = 30.dp
    val TRAY_RADIUS = 22.dp
    /** Width of one tile of the tray: a [ShoalQs.CELL] circle with its label underneath. */
    val TRAY_ITEM_WIDTH = 62.dp
    /** Space between the cells of the collapsed panel's rows, as the panel has it. */
    val COLLAPSED_CELL_SPACING = 4.dp
    /** The least space between two tiles of the collapsed panel's rows: a selection ring fits. */
    val COLLAPSED_MIN_GAP = 6.dp
    /** Space between the rows of the collapsed panel: its 44 dp cells are 4 dp apart. */
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

    data object Tray : ShoalDropTarget
}

/**
 * The transient state of the expanded page: the selection, the drag, and the layout a drop would
 * give ([preview]).
 */
private class ShoalEditState : DragAndDropState {
    override var draggedCell by mutableStateOf<SizedTile<EditTileViewModel>?>(null)
        private set

    override var draggedPosition by mutableStateOf(Offset.Unspecified)
        private set

    override var dragType by mutableStateOf<DragType?>(null)
        private set

    // A tile can be taken out by a drag if the drag brought it in, or if it is removable anyway.
    override val isDraggedCellRemovable: Boolean
        get() = dragType == DragType.Add || draggedCell?.tile?.isRemovable ?: false

    override val dragInProgress: Boolean
        get() = draggedCell != null

    /** What dropping the dragged tile right now would do, shown while the drag lasts. */
    var preview by mutableStateOf<ShoalEditLayout?>(null)
        private set

    var selected by mutableStateOf<TileSpec?>(null)

    /** The free junction waiting for a tile from the tray, if one was tapped. */
    var pickSlot by mutableStateOf<Int?>(null)

    /** The scrolling part, in root coordinates; a drag near its edges scrolls it. */
    var viewport by mutableStateOf<Rect?>(null)

    // Where things are, for working out what a drag is over. Plain fields: they are only read
    // from drag events, never from composition.
    var wideCoordinates: LayoutCoordinates? = null
    var circleCoordinates: LayoutCoordinates? = null
    var trayCoordinates: LayoutCoordinates? = null
    var honeycomb: HoneycombGeometry? = null

    private var target: ShoalDropTarget? = null

    override fun isMoving(tileSpec: TileSpec): Boolean = draggedCell?.tile?.tileSpec == tileSpec

    override fun onStarted(cell: SizedTile<EditTileViewModel>, dragType: DragType) {
        draggedCell = cell
        this.dragType = dragType
        target = null
        preview = null
        pickSlot = null
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
        target = null
        preview = null
    }

    /**
     * Updates [preview] for a drag at [position] (root coordinates) over the [committed] layout.
     */
    fun hover(position: Offset, committed: ShoalEditLayout, metrics: ShoalEditMetrics) {
        val spec = draggedCell?.tile?.tileSpec ?: return
        val newTarget = targetAt(position, spec, committed, metrics)
        if (newTarget == target) return
        target = newTarget
        val next =
            when (newTarget) {
                null -> null
                is ShoalDropTarget.Big -> committed.toBig(spec, newTarget.index)
                is ShoalDropTarget.Circle -> committed.toCircle(spec, newTarget.index)
                // Null over the tile's own junction: it stays where it is.
                is ShoalDropTarget.Junction -> committed.toJunction(spec, newTarget.slot)
                // Dropping a panel tile on the tray takes it out. A tile that came from the
                // tray simply goes back.
                ShoalDropTarget.Tray ->
                    if (dragType == DragType.Move && isDraggedCellRemovable) {
                        committed.remove(spec)
                    } else {
                        null
                    }
            }
        preview = next?.takeIf { it != committed }
    }

    private fun targetAt(
        position: Offset,
        spec: TileSpec,
        committed: ShoalEditLayout,
        metrics: ShoalEditMetrics,
    ): ShoalDropTarget? {
        val tray = trayCoordinates?.takeIf { it.isAttached }
        if (tray != null && tray.boundsInRoot().contains(position)) return ShoalDropTarget.Tray

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

/** Pixel sizes the drop targets are worked out with; the same ones [ShoalWideLayout] uses. */
private class ShoalEditMetrics(
    val gap: Int,
    val rowHeight: Int,
    val junctionReach: Float,
    val rtl: Boolean,
)

/**
 * @param tiles every tile there is; the current ones are those of the expanded panel
 * @param collapsedTiles the tiles picked for the collapsed panel as stored; empty while it follows
 *   the expanded panel
 * @param collapsedOnlyTiles the tiles that are in the tile list for the collapsed panel only
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
    collapsedOnlyTiles: Set<TileSpec>,
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
    val expandedState = remember { ShoalEditState() }
    val collapsedState = remember { ShoalCollapsedEditState() }
    val accent = rememberShoalAccent()
    val fonts = rememberShoalQsFonts()

    val bySpec = remember(tiles) { tiles.associateBy { it.tileSpec } }
    val expandedSpecs = remember(tiles) { tiles.filter { it.isCurrent }.map { it.tileSpec } }
    val stored =
        remember(
            expandedSpecs,
            largeTiles,
            junctionTiles,
            collapsedTiles,
            collapsedOnlyTiles,
            bySpec,
        ) {
            ShoalEditLayout.of(
                expandedSpecs,
                largeTiles,
                junctionTiles,
                // A tile the editor does not list cannot exist, so the panel does not show it.
                collapsedTiles.filter { it in bySpec },
                collapsedOnlyTiles,
            )
        }
    // The last edit, shown until the stores report it back. The tile list, the large set, the
    // junction slots and the collapsed panel's keys live in different places and arrive one after
    // the other; without this the tiles would jump through the states in between.
    var pending by remember { mutableStateOf<ShoalEditLayout?>(null) }
    val committed = pending ?: stored
    val currentCommitted by rememberUpdatedState(committed)
    val currentOnLayout by rememberUpdatedState(onLayout)

    // Drop the stand-in once the stores agree with it, or after a while if they never do (an edit
    // the tile list refused, say).
    LaunchedEffect(stored, pending) {
        val shown = pending ?: return@LaunchedEffect
        if (shown != stored) delay(ShoalEdit.PENDING_TIMEOUT_MILLIS)
        pending = null
    }

    val commit: (ShoalEditLayout?) -> Unit = { next ->
        if (next != null && next != currentCommitted) {
            pending = next
            currentOnLayout(next)
        }
    }

    // The collapsed panel's tiles of the committed layout. While none are picked they are the
    // first of the expanded panel in tile list order, and that list is only rewritten (big
    // tiles, junctions, circles) by an edit: the stored layout still has it in the order it came
    // in. The page shows and edits what fits the panel; a tile beyond that (the rows were taken
    // down) is back among the tiles that can be added.
    val collapsedCapacity = collapsedColumns * collapsedRows
    val collapsed =
        remember(committed, stored, expandedSpecs, collapsedCapacity) {
            committed
                .collapsedOr(
                    if (committed == stored) expandedSpecs else committed.tileSpecs(),
                    collapsedCapacity,
                )
                .take(collapsedCapacity)
        }
    val currentCollapsed by rememberUpdatedState(collapsed)

    val pagerState = rememberPagerState(0) { ShoalEditPages.size }
    val pagerScope = rememberCoroutineScope()
    // The pager is the one part that runs from edge to edge, for the trays of its pages (see
    // ShoalEditTraySurface): it reaches out by the shade's horizontal padding and pads its pages
    // back in. The space between the pages keeps the next page's tray out of that padding.
    val bleed = dimensionResource(R.dimen.overlay_qs_layout_horizontal_padding)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .consumeWindowInsets(WindowInsets.displayCutout)
                .sysuiResTag(SHOAL_EDIT_ROOT_TEST_TAG)
    ) {
        ShoalEditTopBar(
            accent = accent,
            fonts = fonts,
            canUndo = canUndo,
            actions = topBarActions,
            onUndo = {
                // What is undone is the stored layout, so stop showing ours.
                pending = null
                onUndo()
            },
            onReset = onReset,
            onStopEditing = onStopEditing,
            modifier = Modifier.statusBarsPadding(),
        )
        ShoalEditPageTabs(
            page = pagerState.currentPage,
            accent = accent,
            fonts = fonts,
            onPage = { page -> pagerScope.launch { pagerState.animateScrollToPage(page) } },
        )
        Spacer(Modifier.height(6.dp))
        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = { -bleed.roundToPx() }),
            contentPadding = PaddingValues(horizontal = bleed),
            pageSpacing = bleed * 2,
            // Both pages stay composed: each keeps its selection and where it was scrolled to.
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
            // A tile on the move must not take the page with it. Once the drag is a drag and
            // drop session the pager no longer sees the pointer anyway; this covers the rest.
            userScrollEnabled = !expandedState.dragInProgress && !collapsedState.dragInProgress,
        ) { page ->
            if (page == SHOAL_EDIT_PAGE_EXPANDED) {
                ShoalEditExpandedPage(
                    state = expandedState,
                    tiles = tiles,
                    bySpec = bySpec,
                    committed = committed,
                    accent = accent,
                    fonts = fonts,
                    scrollState = scrollState,
                    onLayout = commit,
                )
            } else {
                ShoalEditCollapsedPage(
                    state = collapsedState,
                    tiles = tiles,
                    bySpec = bySpec,
                    collapsed = collapsed,
                    following = committed.collapsed.isEmpty(),
                    columns = collapsedColumns,
                    rows = collapsedRows,
                    columnsRange = collapsedColumnsRange,
                    rowsRange = collapsedRowsRange,
                    accent = accent,
                    fonts = fonts,
                    onCollapsed = { picked -> commit(currentCommitted.copy(collapsed = picked)) },
                    // Not following starts from the tiles the panel shows now, as its own.
                    onFollow = { follow ->
                        commit(
                            currentCommitted.copy(
                                collapsed = if (follow) emptyList() else currentCollapsed
                            )
                        )
                    },
                    onColumns = onCollapsedColumns,
                    onRows = onCollapsedRows,
                )
            }
        }
    }
}

/** The editor's pages in order, by the string that names each on its tab. */
private val ShoalEditPages =
    listOf(R.string.ichthys_qs_edit_page_expanded, R.string.ichthys_qs_edit_page_collapsed)
private const val SHOAL_EDIT_PAGE_EXPANDED = 0

/**
 * The two tabs under the top bar: which page is shown, and a way to the other one for those who do
 * not swipe. One pill with the current page's half filled with the accent.
 */
@Composable
private fun ShoalEditPageTabs(
    page: Int,
    accent: Color,
    fonts: ShoalQsFonts,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(ShoalEdit.TABS_HEIGHT)
                .border(1.dp, ShoalQsColors.Hairline, CircleShape)
                .padding(ShoalEdit.TABS_INSET)
    ) {
        ShoalEditPages.forEachIndexed { index, label ->
            val selected = index == page
            Box(
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .then(if (selected) Modifier.background(accent) else Modifier)
                        .clickable(role = Role.Tab, onClick = { onPage(index) })
                        .semantics { this.selected = selected },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = stringResource(label),
                    style =
                        TextStyle(
                            color = if (selected) ShoalQsColors.OnAccent else ShoalQsColors.Label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            fontFamily = fonts.text,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The page of the expanded panel: the panel, editable in place, over its tray.
 *
 * @param committed the layout as stored, or as the last edit left it
 * @param onLayout takes an edit of [committed]; null (an edit that cannot be done) is ignored
 */
@Composable
private fun ShoalEditExpandedPage(
    state: ShoalEditState,
    tiles: List<EditTileViewModel>,
    bySpec: Map<TileSpec, EditTileViewModel>,
    committed: ShoalEditLayout,
    accent: Color,
    fonts: ShoalQsFonts,
    scrollState: ScrollState,
    onLayout: (ShoalEditLayout?) -> Unit,
    modifier: Modifier = Modifier,
) {
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

    val layout = state.preview ?: committed
    val currentCommitted by rememberUpdatedState(committed)
    val commit by rememberUpdatedState(onLayout)

    LaunchedEffect(committed) {
        val selected = state.selected
        if (selected != null && selected !in committed) state.selected = null
        val slot = state.pickSlot
        if (
            slot != null &&
                (slot >= committed.junctionCount || committed.junctionTile(slot) != null)
        ) {
            state.pickSlot = null
        }
    }

    val onTileClick: (TileSpec) -> Unit = { spec ->
        state.pickSlot = null
        state.selected = if (state.selected == spec) null else spec
    }
    val onJunctionClick: (Int) -> Unit = { slot ->
        val tile = currentCommitted.junctionTile(slot)
        val selected = state.selected
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
    val onDragStart: () -> Unit = { state.selected = null }

    ShoalEditAutoScroll(state, scrollState, committed = { currentCommitted }, metrics = metrics)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .dragAndDropTileArea(
                    dragAndDropState = state,
                    onMoved = { state.hover(it, currentCommitted, metrics) },
                    onDrop = { commit(state.preview) },
                )
    ) {
        val selectedSpec = state.selected
        val markedJunctions =
            remember(committed, selectedSpec) {
                if (selectedSpec == null) emptySet() else committed.junctionsFor(selectedSpec)
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
                                    selected = spec == selectedSpec,
                                    moving = state.isMoving(spec),
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
                                    selected = spec != null && spec == selectedSpec,
                                    marked = slot in markedJunctions || slot == state.pickSlot,
                                    moving = spec != null && state.isMoving(spec),
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
                                    selected = spec == selectedSpec,
                                    moving = state.isMoving(spec),
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
            tiles = tiles,
            selected = selectedSpec?.let { bySpec[it] },
            accent = accent,
            fonts = fonts,
            onTrayClick = onTrayClick,
            onDeselect = { state.selected = null },
            onLayout = { commit(it) },
        )
    }
}

/**
 * Keeps what a part of the editor does not scroll itself from travelling further up. With the tray
 * pinned, the panel often fits without scrolling; a swipe on it would otherwise be handed on to the
 * scene around the editor, whose swipe closes quick settings, and a tile dragged without holding it
 * first would do just that. In the tray it is the rows that scroll sideways: at their end the rest
 * of the swipe would otherwise go to the pager and turn the page.
 */
internal object ShoalEditScrollLock : NestedScrollConnection {
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
                if (position.isSpecified) state.hover(position, committed(), metrics)
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

/**
 * A circle of the honeycomb, or of the collapsed panel's rows.
 *
 * @param moving the tile is the one being dragged, so this is where it would land
 * @param description what the tile is announced as; its name unless the place needs saying too
 */
@Composable
internal fun ShoalEditCircleTile(
    tile: EditTileViewModel?,
    selected: Boolean,
    moving: Boolean,
    accent: Color,
    dragState: DragAndDropState,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    modifier: Modifier = Modifier,
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
    tiles: List<EditTileViewModel>,
    selected: EditTileViewModel?,
    accent: Color,
    fonts: ShoalQsFonts,
    onTrayClick: (TileSpec) -> Unit,
    onDeselect: () -> Unit,
    onLayout: (ShoalEditLayout?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bleed = dimensionResource(R.dimen.overlay_qs_layout_horizontal_padding)
    // Every tile that is not in the expanded panel, the ones only the collapsed panel shows
    // included.
    val available = remember(tiles, layout) { tiles.filter { it.tileSpec !in layout } }
    val dragging = state.dragInProgress

    ShoalEditTraySurface(
        bleed = bleed,
        onPlaced = { state.trayCoordinates = it },
        modifier = modifier.sysuiResTag(SHOAL_EDIT_TRAY_TEST_TAG),
    ) {
        if (selected != null && !dragging) {
            ShoalEditOptions(
                tile = selected,
                layout = layout,
                availableCount = available.size,
                accent = accent,
                fonts = fonts,
                onDeselect = onDeselect,
                onLayout = onLayout,
                modifier = Modifier.padding(horizontal = bleed),
            )
        } else {
            val pickSlot = state.pickSlot
            ShoalEditAvailable(
                available = available,
                folded = dragging,
                hint =
                    when {
                        dragging &&
                            state.dragType == DragType.Move &&
                            state.isDraggedCellRemovable ->
                            stringResource(R.string.ichthys_qs_edit_tray_hint_remove)
                        dragging -> ""
                        pickSlot != null ->
                            stringResource(R.string.ichthys_qs_edit_tray_hint_pick, pickSlot + 1)
                        else -> stringResource(R.string.ichthys_qs_edit_tray_hint)
                    },
                hintMarked = pickSlot != null && !dragging,
                dragState = state,
                accent = accent,
                fonts = fonts,
                bleed = bleed,
                onTrayClick = onTrayClick,
            )
        }
    }
}

/**
 * The surface of a page's tray: a recessed sheet with round top corners, pinned under the panel.
 *
 * The editor is laid out inside the shade's horizontal padding. The tray is the one part that runs
 * from edge to edge, so it reaches out by that padding ([bleed]); what is in it pads itself back
 * in.
 *
 * @param onPlaced the tray's coordinates, for telling a drag that is over it
 */
@Composable
internal fun ShoalEditTraySurface(
    bleed: Dp,
    onPlaced: (LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = { -bleed.roundToPx() })
                .onGloballyPositioned(onPlaced)
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
                // the scene's swipe. It ends here instead. The strip over the navigation bar is
                // left out, so the system gesture area still works. What the rows of the tray
                // leave of a sideways swipe ends here too (see ShoalEditScrollLock).
                .nestedScroll(ShoalEditScrollLock)
                .scrollable(rememberScrollableState { it }, Orientation.Vertical),
        content = content,
    )
}

/**
 * The tiles a page can add: a handle, the title with a count and a [hint], the category chips and
 * one row of circles.
 *
 * @param folded only the title row is shown: a tile is being dragged and the panel wants the room
 * @param hintMarked the hint is the thing to read right now and is set in the accent
 */
@Composable
internal fun ShoalEditAvailable(
    available: List<EditTileViewModel>,
    folded: Boolean,
    hint: String,
    hintMarked: Boolean,
    dragState: DragAndDropState,
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
                text = hint,
                style = if (hintMarked) monoStyle.copy(color = accent) else monoStyle,
                maxLines = 1,
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
                    dragState = dragState,
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
 * The options of the selected tile: what it is and where, a way out of the selection, and the three
 * things that can be done with it. Which three depends on where the tile is.
 */
@Composable
private fun ShoalEditOptions(
    tile: EditTileViewModel,
    layout: ShoalEditLayout,
    availableCount: Int,
    accent: Color,
    fonts: ShoalQsFonts,
    onDeselect: () -> Unit,
    onLayout: (ShoalEditLayout?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = tile.tileSpec
    val place = layout.placeOf(spec)
    val placeText =
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
    val freeJunction = layout.freeJunctionFor(spec)
    val makeBig = stringResource(R.string.ichthys_qs_edit_make_big)
    val makeSmall = stringResource(R.string.ichthys_qs_edit_make_small)

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
        verticalArrangement = spacedBy(14.dp),
    ) {
        ShoalEditOptionsHeader(
            tile = tile,
            placeText = placeText,
            accent = accent,
            fonts = fonts,
            onDeselect = onDeselect,
        )

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

        ShoalEditFoldedTray(
            availableCount = availableCount,
            accent = accent,
            fonts = fonts,
            onClick = onDeselect,
        )
    }
}

/** The head of a tile's options: what it is and where, and a way out of the selection. */
@Composable
internal fun ShoalEditOptionsHeader(
    tile: EditTileViewModel,
    placeText: String,
    accent: Color,
    fonts: ShoalQsFonts,
    onDeselect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacedBy(12.dp)) {
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
}

/**
 * The tray as it shows under a tile's options, folded to one row: tapping it lets go of the tile
 * and brings the available tiles back.
 */
@Composable
internal fun ShoalEditFoldedTray(
    availableCount: Int,
    accent: Color,
    fonts: ShoalQsFonts,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(ShoalEdit.ACTION_HEIGHT)
                .clip(CircleShape)
                .border(1.dp, ShoalQsColors.Hairline, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
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

internal enum class ShoalEditButtonStyle {
    /** Accent fill, ink text: the one thing to do next. */
    Accent,
    /** Tile surface. */
    Tile,
    /** Hairline outline on the ground. */
    Outline,
}

/** A pill button: fully rounded, in one of the three Shoal surfaces. */
@Composable
internal fun ShoalEditButton(
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
internal fun ShoalEditChip(
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
internal fun ShoalEditRoundButton(
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

internal enum class ShoalEditGlyphs {
    Plus,
    Minus,
    Close,
    ChevronUp,
}

/** The editor's own line glyphs, 16 dp, drawn rather than taken from an icon set. */
@Composable
internal fun ShoalEditGlyph(glyph: ShoalEditGlyphs, color: Color, modifier: Modifier = Modifier) {
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

internal fun DrawScope.dashedStroke(): Stroke {
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
