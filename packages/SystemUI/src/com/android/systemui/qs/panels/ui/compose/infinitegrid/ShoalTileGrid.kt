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

import android.content.Context
import android.content.res.Resources
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_UNAVAILABLE
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.thenIf
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator.Companion.dynamicTargetResolutionEnabled
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconProvider
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope

/**
 * IchthysOS "Shoal" quick settings (Settings.Secure ichthys_qs_style = "shoal", the ichthys.nix
 * option quickSettings.style): big tiles (the stock large-tiles set, toggled in edit mode) as wide
 * two-column tiles with their subtitle,
 * every other tile as an icon-only circle in an offset honeycomb (rows alternating N / N-1, each
 * lower row rising [ShoalQs.OVERLAP] into the one above) - the same lattice motif as the Shoal status bar.
 *
 * The tiles are drawn by [ShoalWideTile] / [ShoalCircleTile] rather than the stock [Tile]: the stock
 * tile follows crDroid's qs_panel_style, whose default "card" look has a transparent container, so
 * off tiles had no background at all. Shoal tiles always paint a filled surface.
 */
internal object ShoalQs {
    // Honeycomb of icon circles. Rows alternate MAX_PER_ROW / MAX_PER_ROW - 1 (6 / 5): a full row
    // is 6 * 48 + 5 * 8 = 328 dp, leaving room to spare on the 384 dp panel.
    /** Circle diameter; also the touch target (>= 44 dp). */
    val CELL = 48.dp
    /** Horizontal gap between circles in a row (the half-pitch offset is (CELL + GAP) / 2). */
    val GAP = 8.dp
    /** How far each lower row rises into the one above (8 dp at the old 58 dp cell, scaled). */
    val OVERLAP = 6.5.dp
    const val MAX_PER_ROW = 6
    /** Glyph size inside a circle. */
    val CIRCLE_ICON = 20.dp

    // Wide two-column tiles.
    val WIDE_HEIGHT = 52.dp
    val WIDE_RADIUS = 22.dp
    val WIDE_GAP = 8.dp
    val WIDE_PADDING = 14.dp
    /** Glyph size in a wide tile. */
    val WIDE_ICON = 20.dp
    /** Touch target around the wide tile's icon (the toggle of dual-target tiles). */
    val WIDE_ICON_TARGET = 36.dp
    /** Space between the wide tile's icon target and its text. */
    val WIDE_ICON_TEXT_GAP = 6.dp
    val WIDE_LABEL_SIZE = 14.sp
    val WIDE_LABEL_LINE_HEIGHT = 17.sp
    val WIDE_SUBTITLE_SIZE = 12.sp
    val WIDE_SUBTITLE_LINE_HEIGHT = 14.sp

    /** Vertical space between the wide tiles and the honeycomb. */
    val SECTION_GAP = 12.dp
}

@Composable
internal fun ContentScope.ShoalTileGrid(
    tiles: List<TileViewModel>,
    largeTiles: Set<TileSpec>,
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
        remember(tiles, largeTiles) {
            // Big tiles are the stock "large tiles" set (resizable in edit mode, persisted per
            // user); they keep their relative order and always sit above the honeycomb.
            tiles.partition { it.spec in largeTiles }
        }
    val (wide, small) = split
    val accent = rememberShoalAccent()
    val fonts = rememberShoalQsFonts()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ShoalQs.SECTION_GAP)) {
        if (wide.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(ShoalQs.WIDE_GAP)) {
                wide.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ShoalQs.WIDE_GAP)) {
                        pair.forEach { tile ->
                            Element(
                                tile.spec.toElementKey(),
                                Modifier.weight(1f).height(ShoalQs.WIDE_HEIGHT),
                            ) {
                                ShoalWideTile(
                                    tile = tile,
                                    accent = accent,
                                    fonts = fonts,
                                    squishiness = squishiness,
                                    tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                                    detailsViewModel = detailsViewModel,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        // Keep a lone last wide tile at half width.
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (small.isNotEmpty()) {
            val rows = remember(small.size) { honeycombRows(small.size) }
            HoneycombLayout(rows = rows, modifier = Modifier.fillMaxWidth()) {
                small.forEach { tile ->
                    Element(tile.spec.toElementKey(), Modifier) {
                        ShoalCircleTile(
                            tile = tile,
                            accent = accent,
                            squishiness = squishiness,
                            tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                            detailsViewModel = detailsViewModel,
                            requestToggleTextFeedback = requestToggleTextFeedback,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

// region tiles

/** Label / subtitle lines hug their glyphs so both fit a [ShoalQs.WIDE_HEIGHT] tile. */
private val ShoalTightLineHeight =
    LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)

private data class ShoalTileColors(val background: Color, val content: Color)

private fun shoalTileColors(uiState: TileUiState, accent: Color): ShoalTileColors =
    when (uiState.visualState) {
        STATE_ACTIVE -> ShoalTileColors(accent, ShoalQsColors.Ink)
        STATE_UNAVAILABLE ->
            ShoalTileColors(
                ShoalQsColors.TileOff.copy(alpha = 0.55f),
                ShoalQsColors.OnTileOff.copy(alpha = 0.38f),
            )
        else -> ShoalTileColors(ShoalQsColors.TileOff, ShoalQsColors.OnTileOff)
    }

/** State shared by both Shoal tile shapes: ui state, icon, haptics, and the click handlers. */
private class ShoalTileState(
    val uiState: TileUiState,
    val iconProvider: Context.() -> Icon,
    val hapticsViewModel: TileHapticsViewModel,
    val hapticEnabled: Boolean,
)

@Composable
private fun rememberShoalTileState(
    tile: TileViewModel,
    tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
): ShoalTileState {
    val resources = shoalResources()
    val uiState by
        produceState(tile.currentState.toUiState(resources), tile, resources) {
            tile.state.collect { value = it.toUiState(resources) }
        }
    val icon by
        produceState(tile.currentState.toIconProvider(), tile) {
            tile.state.collect { value = it.toIconProvider() }
        }
    val hapticsViewModel: TileHapticsViewModel =
        rememberViewModel(traceName = "ShoalTileHapticsViewModel") {
            tileHapticsViewModelFactory.create(tile)
        }
    val hapticEnabled = rememberQsTileHapticEnabled()
    val currentIcon = icon
    return ShoalTileState(
        uiState = uiState,
        iconProvider = { shoalTileIcon(currentIcon) },
        hapticsViewModel = hapticsViewModel,
        hapticEnabled = hapticEnabled,
    )
}

@Composable
private fun ShoalTileSurface(
    tile: TileViewModel,
    color: Color,
    shape: Shape,
    squishiness: () -> Float,
    hapticsViewModel: TileHapticsViewModel,
    modifier: Modifier = Modifier,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        expandable = if (dynamicTargetResolutionEnabled()) tile.expandable else null,
        controller = rememberExpandableController(color = { color }, shape = shape),
        modifier = modifier.clip(shape).verticalSquish(squishiness),
        useModifierBasedImplementation = true,
    ) {
        content(hapticsViewModel.createStateAwareExpandable(it))
    }
}

/**
 * Wide two-column tile ([ShoalQs.WIDE_HEIGHT] tall, [ShoalQs.WIDE_RADIUS] radius): icon + label +
 * subtitle, always a filled surface.
 */
@Composable
private fun ShoalWideTile(
    tile: TileViewModel,
    accent: Color,
    fonts: ShoalQsFonts,
    squishiness: () -> Float,
    tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    detailsViewModel: DetailsViewModel?,
    modifier: Modifier = Modifier,
) {
    val state = rememberShoalTileState(tile, tileHapticsViewModelFactory)
    val uiState = state.uiState
    val colors = shoalTileColors(uiState, accent)
    val background by animateColorAsState(colors.background, label = "ShoalWideTileBackground")
    val content by animateColorAsState(colors.content, label = "ShoalWideTileContent")
    val shape = RoundedCornerShape(ShoalQs.WIDE_RADIUS)
    val isDualTarget = uiState.handlesToggleClick

    ShoalTileSurface(
        tile = tile,
        color = background,
        shape = shape,
        squishiness = squishiness,
        hapticsViewModel = state.hapticsViewModel,
        modifier = modifier.sysuiResTag("tile_expandable"),
    ) { expandable ->
        val longClick: (() -> Unit)? =
            {
                    if (state.hapticEnabled) {
                        state.hapticsViewModel.setTileInteractionState(
                            TileHapticsViewModel.TileInteractionState.LONG_CLICKED
                        )
                    }
                    tile.settingsClick(expandable)
                }
                .takeIf { uiState.handlesSettingsClick }
        Row(
            modifier =
                Modifier.fillMaxSize()
                    .tileCombinedClickable(
                        onClick = {
                            if (!uiState.handlesMainClick) return@tileCombinedClickable
                            val hasDetails =
                                QsDetailedView.isEnabled &&
                                    detailsViewModel?.onTileClicked(tile.spec) == true
                            if (!hasDetails) tile.mainClick(expandable)
                            if (state.hapticEnabled) {
                                state.hapticsViewModel.setTileInteractionState(
                                    TileHapticsViewModel.TileInteractionState.CLICKED
                                )
                            }
                        },
                        onLongClick = longClick,
                        accessibilityUiState = uiState.accessibilityUiState,
                        iconOnly = false,
                        isDualTarget = isDualTarget,
                        interactionSource = null,
                    )
                    .padding(horizontal = ShoalQs.WIDE_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The icon doubles as the toggle of dual-target tiles (Wi-Fi, Bluetooth, data), as in
            // the stock large tile; the rest of the tile opens its details / dialog.
            Box(
                modifier =
                    Modifier.size(ShoalQs.WIDE_ICON_TARGET)
                        .clip(CircleShape)
                        .then(
                            if (isDualTarget && uiState.handlesMainClick) {
                            Modifier.tileCombinedClickable(
                                onClick = {
                                    if (state.hapticEnabled) {
                                        state.hapticsViewModel.setTileInteractionState(
                                            TileHapticsViewModel.TileInteractionState.CLICKED
                                        )
                                    }
                                    tile.toggleClick()
                                },
                                onLongClick = longClick,
                                accessibilityUiState = uiState.accessibilityUiState,
                                iconOnly = true,
                                isDualTarget = true,
                                interactionSource = null,
                            )
                            } else {
                                Modifier
                            }
                        ),
                contentAlignment = Alignment.CenterStart,
            ) {
                SmallTileContent(
                    iconProvider = state.iconProvider,
                    color = content,
                    size = { ShoalQs.WIDE_ICON },
                )
            }
            Spacer(Modifier.width(ShoalQs.WIDE_ICON_TEXT_GAP))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                BasicText(
                    text = uiState.label,
                    style =
                        TextStyle(
                            color = content,
                            fontSize = ShoalQs.WIDE_LABEL_SIZE,
                            lineHeight = ShoalQs.WIDE_LABEL_LINE_HEIGHT,
                            lineHeightStyle = ShoalTightLineHeight,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = fonts.text,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (uiState.secondaryLabel.isNotEmpty()) {
                    BasicText(
                        text = uiState.secondaryLabel,
                        style =
                            TextStyle(
                                color = content.copy(alpha = content.alpha * 0.8f),
                                fontSize = ShoalQs.WIDE_SUBTITLE_SIZE,
                                lineHeight = ShoalQs.WIDE_SUBTITLE_LINE_HEIGHT,
                                lineHeightStyle = ShoalTightLineHeight,
                                fontFamily = fonts.text,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Icon-only honeycomb circle: accent fill + ink glyph when on, #172125 + light glyph when off. */
@Composable
private fun ShoalCircleTile(
    tile: TileViewModel,
    accent: Color,
    squishiness: () -> Float,
    tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    detailsViewModel: DetailsViewModel?,
    requestToggleTextFeedback: (TileSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberShoalTileState(tile, tileHapticsViewModelFactory)
    val uiState = state.uiState
    val colors = shoalTileColors(uiState, accent)
    val background by animateColorAsState(colors.background, label = "ShoalCircleBackground")
    val content by animateColorAsState(colors.content, label = "ShoalCircleContent")
    val isDualTarget = uiState.handlesToggleClick
    val isClickable = uiState.handlesMainClick

    ShoalTileSurface(
        tile = tile,
        color = background,
        shape = CircleShape,
        squishiness = squishiness,
        hapticsViewModel = state.hapticsViewModel,
        modifier = modifier.sysuiResTag("tile_expandable"),
    ) { expandable ->
        // Same semantics as the stock icon-only tile: tap toggles dual-target tiles, long press
        // opens their details; everything else taps through to its main click.
        val useLongClickToSettings = !(isDualTarget && isClickable)
        val longClick: (() -> Unit)? =
            {
                    if (state.hapticEnabled) {
                        state.hapticsViewModel.setTileInteractionState(
                            TileHapticsViewModel.TileInteractionState.LONG_CLICKED
                        )
                    }
                    if (useLongClickToSettings) {
                        tile.settingsClick(expandable)
                    } else {
                        val hasDetails =
                            QsDetailedView.isEnabled &&
                                detailsViewModel?.onTileClicked(tile.spec) == true
                        if (!hasDetails) tile.mainClick(expandable)
                    }
                }
                .takeIf { !useLongClickToSettings || uiState.handlesSettingsClick }
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .tileCombinedClickable(
                        onClick = {
                            if (!isClickable) return@tileCombinedClickable
                            if (isDualTarget) {
                                tile.toggleClick()
                            } else {
                                val hasDetails =
                                    QsDetailedView.isEnabled &&
                                        detailsViewModel?.onTileClicked(tile.spec) == true
                                if (!hasDetails) tile.mainClick(expandable)
                            }
                            if (state.hapticEnabled) {
                                state.hapticsViewModel.setTileInteractionState(
                                    TileHapticsViewModel.TileInteractionState.CLICKED
                                )
                            }
                            if (uiState.isToggleable) requestToggleTextFeedback(tile.spec)
                        },
                        onLongClick = longClick,
                        accessibilityUiState = uiState.accessibilityUiState,
                        iconOnly = true,
                        isDualTarget = isDualTarget,
                        interactionSource = null,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            SmallTileContent(
                iconProvider = state.iconProvider,
                color = content,
                size = { ShoalQs.CIRCLE_ICON },
            )
        }
    }
}

private fun Context.shoalTileIcon(icon: IconProvider): Icon {
    return icon.icon?.let {
        if (it is QSTileImpl.ResourceIcon) {
            Icon.Resource(it.resId, null)
        } else {
            Icon.Loaded(it.getDrawable(this), null)
        }
    } ?: Icon.Resource(R.drawable.ic_error_outline, null)
}

/** [Resources] that recompose on configuration changes (mirrors Tile.kt's private helper). */
@Composable
@ReadOnlyComposable
private fun shoalResources(): Resources {
    LocalConfiguration.current
    return LocalResources.current
}

// endregion

// region honeycomb

/**
 * One honeycomb row: [size] circles starting at [halfOffset] half-pitches from the start of a
 * [ShoalQs.MAX_PER_ROW]-wide lattice. A centred row of `s` circles has halfOffset `MAX - s`.
 */
internal data class HoneycombRow(val size: Int, val halfOffset: Int)

/**
 * Splits [count] circles into honeycomb rows. Adjacent rows must sit half a pitch apart (their
 * half-offsets differ in parity), otherwise the raised lower row would collide with the one above.
 *
 * Preference, in order: fewest rows; rows as even as possible (alternating N / N-1, e.g. 6/5/6,
 * 5/6/5, or 4/5/4 for thirteen tiles rather than 6/6/1); centred rows (a row is only shifted half
 * a pitch off-centre when two equal rows meet); no lone single circle; larger rows first.
 */
internal fun honeycombRows(count: Int, max: Int = ShoalQs.MAX_PER_ROW): List<HoneycombRow> {
    if (count <= 0) return emptyList()
    val minRows = (count + max - 1) / max
    var best: List<HoneycombRow>? = null
    var bestScore = Int.MAX_VALUE
    for (rows in minRows..minRows + 3) {
        val sizes = IntArray(rows)
        fun offsetsFor(): Pair<IntArray, Int>? {
            // DP over the three candidate offsets per row (centre, centre -1, centre +1).
            val inf = Int.MAX_VALUE / 4
            val cand = Array(rows) { r ->
                val c = max - sizes[r]
                intArrayOf(c, c - 1, c + 1).filter { it in 0..2 * (max - sizes[r]) }.toIntArray()
            }
            val cost = Array(rows) { r -> IntArray(cand[r].size) { inf } }
            val from = Array(rows) { r -> IntArray(cand[r].size) { -1 } }
            for (j in cand[0].indices) cost[0][j] = if (j == 0) 0 else 1
            for (r in 1 until rows) {
                for (j in cand[r].indices) {
                    val own = if (j == 0) 0 else 1
                    for (k in cand[r - 1].indices) {
                        if (cost[r - 1][k] >= inf) continue
                        if ((cand[r][j] - cand[r - 1][k]) % 2 == 0) continue
                        val c = cost[r - 1][k] + own
                        if (c < cost[r][j]) {
                            cost[r][j] = c
                            from[r][j] = k
                        }
                    }
                }
            }
            var bestJ = -1
            for (j in cand[rows - 1].indices) {
                if (cost[rows - 1][j] < inf && (bestJ < 0 || cost[rows - 1][j] < cost[rows - 1][bestJ]))
                    bestJ = j
            }
            if (bestJ < 0) return null
            val out = IntArray(rows)
            var j = bestJ
            for (r in rows - 1 downTo 0) {
                out[r] = cand[r][j]
                j = from[r][j]
            }
            return out to cost[rows - 1][bestJ]
        }
        fun visit(row: Int, remaining: Int) {
            if (row == rows) {
                if (remaining != 0) return
                val (offsets, shifts) = offsetsFor() ?: return
                val spread = sizes.maxOrNull()!! - sizes.minOrNull()!!
                var score = rows * 1000 + spread * 10 + shifts * 15
                if (count > 2 && sizes.any { it == 1 }) score += 40
                for (r in 0 until rows - 1) if (sizes[r] < sizes[r + 1]) score += 1
                if (score < bestScore) {
                    bestScore = score
                    best = List(rows) { HoneycombRow(sizes[it], offsets[it]) }
                }
                return
            }
            val rowsLeft = rows - row - 1
            for (s in max downTo 1) {
                val rest = remaining - s
                if (rest < rowsLeft || rest > rowsLeft * max) continue
                sizes[row] = s
                visit(row + 1, rest)
            }
        }
        visit(0, count)
        if (best != null && bestScore < (rows + 1) * 1000) break
    }
    // Always feasible for rows >= 2 (alternating sizes), but keep a plain fallback.
    return best
        ?: List((count + max - 1) / max) { r ->
            val size = minOf(max, count - r * max)
            HoneycombRow(size, max - size)
        }
}

/** Places circles on [rows]: centred lattice, each row raised by [overlap] into the one above. */
@Composable
private fun HoneycombLayout(
    rows: List<HoneycombRow>,
    modifier: Modifier = Modifier,
    cell: Dp = ShoalQs.CELL,
    gap: Dp = ShoalQs.GAP,
    overlap: Dp = ShoalQs.OVERLAP,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val max = ShoalQs.MAX_PER_ROW
        val gapPx = gap.roundToPx()
        val maxWidth =
            if (constraints.hasBoundedWidth) constraints.maxWidth
            else (cell.roundToPx() * (max + 1))
        val cellPx = minOf(cell.roundToPx(), (maxWidth - (max - 1) * gapPx) / max).coerceAtLeast(1)
        // Keep the nesting proportional if the circles had to shrink.
        val overlapPx = (overlap.toPx() * cellPx / cell.toPx()).toInt()
        val placeables = measurables.map { it.measure(Constraints.fixed(cellPx, cellPx)) }
        val pitch = cellPx + gapPx
        val fullRowWidth = max * cellPx + (max - 1) * gapPx
        val startX = (maxWidth - fullRowWidth) / 2
        val rowStep = cellPx - overlapPx
        val positions = ArrayList<Pair<Int, Int>>(placeables.size)
        var index = 0
        rows.forEachIndexed { r, row ->
            val rowX = startX + row.halfOffset * pitch / 2
            for (i in 0 until row.size) {
                if (index >= placeables.size) break
                positions += (rowX + i * pitch) to (r * rowStep)
                index++
            }
        }
        // Anything the row plan didn't cover (shouldn't happen) goes on extra full rows.
        var extraRow = rows.size
        var inRow = 0
        while (positions.size < placeables.size) {
            val offset = if ((extraRow - rows.size) % 2 == 0) 0 else pitch / 2
            positions += (startX + offset + inRow * pitch) to (extraRow * rowStep)
            inRow++
            if (inRow == max - (extraRow - rows.size) % 2) {
                inRow = 0
                extraRow++
            }
        }
        val usedRows = if (placeables.isEmpty()) 0 else positions.maxOf { it.second } / rowStep + 1
        val height = if (usedRows == 0) 0 else usedRows * rowStep + overlapPx
        layout(maxWidth, height) {
            placeables.forEachIndexed { i, placeable ->
                placeable.place(positions[i].first, positions[i].second)
            }
        }
    }
}

// endregion
