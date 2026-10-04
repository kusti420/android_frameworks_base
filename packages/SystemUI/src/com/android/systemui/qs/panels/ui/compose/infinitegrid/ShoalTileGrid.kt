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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
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
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope

/**
 * IchthysOS "Shoal" quick settings (Settings.Secure ichthys_qs_style = "shoal", the ichthys.nix
 * option quickSettings.style): big tiles (the stock large-tiles set, toggled in edit mode) as wide
 * two-column tiles with their subtitle,
 * every other tile as an icon-only circle in an offset honeycomb (rows alternating N / N-1, each
 * lower row rising [ShoalQs.OVERLAP] into the one above) - the same lattice motif as the Shoal status bar.
 * Where four big tiles meet, a junction slot can hold one more tile as a small circle (see
 * [ShoalJunctions]).
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

    // Junction tiles: a circle where four wide tiles meet, in a scoop cut out of their corners.
    /** Diameter of a junction tile's circle. */
    val JUNCTION = 40.dp
    /** Ground left between the circle and the scooped corners. */
    val JUNCTION_MOAT = 3.dp
    /** Diameter of the scoop; also the junction tile's touch target. */
    val JUNCTION_SCOOP = JUNCTION + JUNCTION_MOAT * 2
    /** Glyph size in a junction tile. */
    val JUNCTION_ICON = 18.dp
}

@Composable
internal fun ContentScope.ShoalTileGrid(
    tiles: List<TileViewModel>,
    largeTiles: Set<TileSpec>,
    junctionTiles: List<TileSpec?>,
    squishiness: () -> Float,
    coroutineScope: CoroutineScope,
    tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    detailsViewModel: DetailsViewModel,
    listening: () -> Boolean,
    requestToggleTextFeedback: (TileSpec) -> Unit,
    enableRevealEffect: Boolean,
    modifier: Modifier = Modifier,
) {
    // Big tiles are the stock "large tiles" set (resizable in edit mode, persisted per user); they
    // keep their relative order and always sit above the honeycomb. Junction tiles are the ones
    // named in the junction slots; a slot only shows while its junction exists.
    val slotOffset = LocalShoalJunctionOffset.current
    val layout =
        remember(tiles, largeTiles, junctionTiles, slotOffset) {
            shoalLayout(tiles, largeTiles, junctionTiles, slotOffset) { it.spec }
        }
    val wide = layout.wide
    val small = layout.circles
    val accent = rememberShoalAccent()
    val fonts = rememberShoalQsFonts()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ShoalQs.SECTION_GAP)) {
        if (wide.isNotEmpty()) {
            // Only junctions with a tile in them are drawn, and only those scoop the corners
            // around them: a free junction leaves the four tiles as they are.
            val occupied =
                remember(layout) {
                    layout.junctions.indices.filter { layout.junctions[it] != null }
                }
            ShoalWideLayout(
                wideCount = wide.size,
                junctions = occupied,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Keyed by tile: a tile that moves (into a junction, say) takes what it remembers
                // with it instead of leaving it to whichever tile gets its old place.
                wide.forEachIndexed { index, tile ->
                    val row = index / 2
                    key(tile.spec) {
                        Element(tile.spec.toElementKey(), Modifier) {
                            ShoalWideTile(
                                tile = tile,
                                accent = accent,
                                fonts = fonts,
                                squishiness = squishiness,
                                tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                                detailsViewModel = detailsViewModel,
                                scoopAbove = ShoalJunctions.below(row - 1) in occupied,
                                scoopBelow = ShoalJunctions.below(row) in occupied,
                                // The first tile of a row meets the junction with its end-side
                                // corners, the second one with its start-side corners.
                                innerOnEnd = index % 2 == 0,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                occupied.forEach { junction ->
                    val tile = layout.junctions[junction] ?: return@forEach
                    key(tile.spec) {
                        Element(tile.spec.toElementKey(), Modifier) {
                            ShoalCircleTile(
                                tile = tile,
                                accent = accent,
                                squishiness = squishiness,
                                tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                                detailsViewModel = detailsViewModel,
                                requestToggleTextFeedback = requestToggleTextFeedback,
                                iconSize = ShoalQs.JUNCTION_ICON,
                                inset = ShoalQs.JUNCTION_MOAT,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        if (small.isNotEmpty()) {
            val rows = remember(small.size) { honeycombRows(small.size) }
            HoneycombLayout(rows = rows, modifier = Modifier.fillMaxWidth()) {
                small.forEach { tile ->
                    key(tile.spec) {
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
}

/**
 * Places the wide tiles two per row (a lone last tile keeps half the width) and the junction
 * tiles on the meeting points between rows.
 *
 * The first [wideCount] children are the wide tiles in order; the rest are the junction tiles of
 * the junctions listed in [junctions], each given a [ShoalQs.JUNCTION_SCOOP] square centred on its
 * junction. They come last so that they draw, and take touches, above the tile corners.
 */
@Composable
internal fun ShoalWideLayout(
    wideCount: Int,
    junctions: List<Int>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = ShoalQs.WIDE_GAP.roundToPx()
        val rowHeight = ShoalQs.WIDE_HEIGHT.roundToPx()
        val scoop = ShoalQs.JUNCTION_SCOOP.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val tileWidth = ((width - gap) / 2).coerceAtLeast(0)
        val widePlaceables =
            measurables.take(wideCount).map { it.measure(Constraints.fixed(tileWidth, rowHeight)) }
        val junctionPlaceables =
            measurables.drop(wideCount).map { it.measure(Constraints.fixed(scoop, scoop)) }
        val rows = (wideCount + 1) / 2
        val height = if (rows == 0) 0 else rows * rowHeight + (rows - 1) * gap
        layout(width, height) {
            widePlaceables.forEachIndexed { index, placeable ->
                placeable.placeRelative(
                    x = (index % 2) * (tileWidth + gap),
                    y = (index / 2) * (rowHeight + gap),
                )
            }
            junctionPlaceables.forEachIndexed { index, placeable ->
                val junction = junctions.getOrNull(index) ?: return@forEachIndexed
                // The same point the scooped corners are cut around: half a gutter beyond the
                // first column's end edge and the upper row's bottom edge.
                val row = ShoalJunctions.rowAbove(junction)
                val centerX = tileWidth + gap / 2f
                val centerY = (row + 1) * rowHeight + row * gap + gap / 2f
                placeable.placeRelative(
                    x = (centerX - scoop / 2f).roundToInt(),
                    y = (centerY - scoop / 2f).roundToInt(),
                )
            }
        }
    }
}

/**
 * The outline of a wide tile next to a shown junction: the [ShoalQs.WIDE_RADIUS] pill with a
 * concave scoop of [ShoalQs.JUNCTION_SCOOP] diameter around each junction it touches. A real
 * outline rather than a ground-coloured disc painted over the tiles, so it also holds over a
 * panel background that is not flat.
 *
 * @param scoopAbove a junction is shown on the boundary above this tile's row
 * @param scoopBelow a junction is shown on the boundary below this tile's row
 * @param innerOnEnd the junctions are on the tile's end side (the first tile of a row)
 */
internal data class ShoalScoopedShape(
    val scoopAbove: Boolean,
    val scoopBelow: Boolean,
    val innerOnEnd: Boolean,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radius = minOf(with(density) { ShoalQs.WIDE_RADIUS.toPx() }, size.minDimension / 2f)
        val pill = RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))
        val scoops = scoops(size, layoutDirection, density) ?: return Outline.Rounded(pill)
        val tile = Path().apply { addRoundRect(pill) }
        return Outline.Generic(Path.combine(PathOperation.Difference, tile, scoops))
    }

    /** The discs cut out of a tile of [size], in the tile's coordinates; null without a scoop. */
    fun scoops(size: Size, layoutDirection: LayoutDirection, density: Density): Path? {
        if (!scoopAbove && !scoopBelow) return null
        with(density) {
            val halfGap = ShoalQs.WIDE_GAP.roundToPx() / 2f
            val scoopRadius = ShoalQs.JUNCTION_SCOOP.toPx() / 2f
            val innerOnRight = innerOnEnd == (layoutDirection == LayoutDirection.Ltr)
            val centerX = if (innerOnRight) size.width + halfGap else -halfGap
            // Measured from the full tile height, not from size.height: while the panel is
            // squishing open the tile is shorter but the junction below has not moved.
            val fullHeight = ShoalQs.WIDE_HEIGHT.roundToPx().toFloat()
            val scoops = Path()
            if (scoopAbove) {
                scoops.addOval(Rect(Offset(centerX, -halfGap), scoopRadius))
            }
            if (scoopBelow) {
                scoops.addOval(Rect(Offset(centerX, fullHeight + halfGap), scoopRadius))
            }
            return scoops
        }
    }
}

/**
 * A circle inset by [inset] on every side: the visible part of a junction tile, whose bounds (and
 * touch target) are the whole scoop. Still a rounded outline, which is all [Expandable] can
 * animate from.
 */
private data class ShoalInsetCircle(val inset: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val insetPx = with(density) { inset.toPx() }
        val diameter = (size.minDimension - 2 * insetPx).coerceAtLeast(0f)
        val left = (size.width - diameter) / 2f
        val top = (size.height - diameter) / 2f
        return Outline.Rounded(
            RoundRect(left, top, left + diameter, top + diameter, CornerRadius(diameter / 2f))
        )
    }
}

// region tiles

/** Label / subtitle lines hug their glyphs so both fit a [ShoalQs.WIDE_HEIGHT] tile. */
private val ShoalTightLineHeight =
    LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)

private data class ShoalTileColors(val background: Color, val content: Color)

private fun shoalTileColors(uiState: TileUiState, accent: Color): ShoalTileColors =
    when (uiState.visualState) {
        STATE_ACTIVE -> ShoalTileColors(accent, ShoalQsColors.OnAccent)
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
    // What the tile is clipped to, when that is not [shape]. The launch animation can only start
    // from a (rounded) rectangle, so the Expandable keeps [shape] and a scooped tile or the wider
    // touch target of a junction tile is done with the clip.
    clipShape: Shape = shape,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        expandable = if (dynamicTargetResolutionEnabled()) tile.expandable else null,
        controller = rememberExpandableController(color = { color }, shape = shape),
        modifier = modifier.clip(clipShape).verticalSquish(squishiness),
        useModifierBasedImplementation = true,
    ) {
        content(hapticsViewModel.createStateAwareExpandable(it))
    }
}

/**
 * Wide two-column tile ([ShoalQs.WIDE_HEIGHT] tall, [ShoalQs.WIDE_RADIUS] radius): icon + label +
 * subtitle, always a filled surface. [scoopAbove] / [scoopBelow] / [innerOnEnd] carve the corners
 * next to a shown junction, see [ShoalScoopedShape].
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
    scoopAbove: Boolean = false,
    scoopBelow: Boolean = false,
    innerOnEnd: Boolean = true,
) {
    val state = rememberShoalTileState(tile, tileHapticsViewModelFactory)
    val uiState = state.uiState
    val colors = shoalTileColors(uiState, accent)
    val background by animateColorAsState(colors.background, label = "ShoalWideTileBackground")
    val content by animateColorAsState(colors.content, label = "ShoalWideTileContent")
    val shape = RoundedCornerShape(ShoalQs.WIDE_RADIUS)
    val clipShape =
        if (scoopAbove || scoopBelow) {
            remember(scoopAbove, scoopBelow, innerOnEnd) {
                ShoalScoopedShape(scoopAbove, scoopBelow, innerOnEnd)
            }
        } else {
            shape
        }
    val isDualTarget = uiState.handlesToggleClick

    ShoalTileSurface(
        tile = tile,
        color = background,
        shape = shape,
        clipShape = clipShape,
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

/**
 * Icon-only honeycomb circle: accent fill + ink glyph when on, #172125 + light glyph when off.
 *
 * A junction tile is the same tile with a smaller glyph ([iconSize]) and an [inset]: its bounds
 * are the scoop it sits in and take the touches, the circle is drawn [inset] inside them.
 */
@Composable
private fun ShoalCircleTile(
    tile: TileViewModel,
    accent: Color,
    squishiness: () -> Float,
    tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    detailsViewModel: DetailsViewModel?,
    requestToggleTextFeedback: (TileSpec) -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = ShoalQs.CIRCLE_ICON,
    inset: Dp = 0.dp,
) {
    val state = rememberShoalTileState(tile, tileHapticsViewModelFactory)
    val uiState = state.uiState
    val colors = shoalTileColors(uiState, accent)
    val background by animateColorAsState(colors.background, label = "ShoalCircleBackground")
    val content by animateColorAsState(colors.content, label = "ShoalCircleContent")
    val isDualTarget = uiState.handlesToggleClick
    val isClickable = uiState.handlesMainClick
    val shape = if (inset > 0.dp) remember(inset) { ShoalInsetCircle(inset) } else CircleShape

    ShoalTileSurface(
        tile = tile,
        color = background,
        shape = shape,
        clipShape = CircleShape,
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
                    // Keep the press ripple on the circle: with an inset the clickable is larger
                    // than what is drawn, and the ring between the two is the moat.
                    .thenIf(inset > 0.dp) {
                        Modifier.drawWithContent {
                            val circle =
                                Path().apply {
                                    addOval(Rect(center, size.minDimension / 2f - inset.toPx()))
                                }
                            clipPath(circle) { this@drawWithContent.drawContent() }
                        }
                    }
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
                size = { iconSize },
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

/** Where [HoneycombLayout] puts its circles: the cell size and each circle's top-left corner. */
internal class HoneycombGeometry(val cell: Int, val positions: List<IntOffset>, val height: Int)

/**
 * The positions of [count] circles on [rows] in a layout [maxWidth] wide: centred lattice, each
 * row raised by [overlap] into the one above. The lattice is centred and is not mirrored for
 * right-to-left.
 */
internal fun Density.honeycombGeometry(
    rows: List<HoneycombRow>,
    count: Int,
    maxWidth: Int,
    cell: Dp = ShoalQs.CELL,
    gap: Dp = ShoalQs.GAP,
    overlap: Dp = ShoalQs.OVERLAP,
): HoneycombGeometry {
    val max = ShoalQs.MAX_PER_ROW
    val gapPx = gap.roundToPx()
    val cellPx = minOf(cell.roundToPx(), (maxWidth - (max - 1) * gapPx) / max).coerceAtLeast(1)
    // Keep the nesting proportional if the circles had to shrink.
    val overlapPx = (overlap.toPx() * cellPx / cell.toPx()).toInt()
    val pitch = cellPx + gapPx
    val fullRowWidth = max * cellPx + (max - 1) * gapPx
    val startX = (maxWidth - fullRowWidth) / 2
    val rowStep = cellPx - overlapPx
    val positions = ArrayList<IntOffset>(count)
    rows.forEachIndexed { r, row ->
        val rowX = startX + row.halfOffset * pitch / 2
        for (i in 0 until row.size) {
            if (positions.size >= count) break
            positions += IntOffset(rowX + i * pitch, r * rowStep)
        }
    }
    // Anything the row plan didn't cover (shouldn't happen) goes on extra full rows.
    var extraRow = rows.size
    var inRow = 0
    while (positions.size < count) {
        val offset = if ((extraRow - rows.size) % 2 == 0) 0 else pitch / 2
        positions += IntOffset(startX + offset + inRow * pitch, extraRow * rowStep)
        inRow++
        if (inRow == max - (extraRow - rows.size) % 2) {
            inRow = 0
            extraRow++
        }
    }
    val usedRows = if (positions.isEmpty()) 0 else positions.maxOf { it.y } / rowStep + 1
    val height = if (usedRows == 0) 0 else usedRows * rowStep + overlapPx
    return HoneycombGeometry(cellPx, positions, height)
}

/**
 * Places circles on [rows] (see [honeycombGeometry]). [onGeometry] is told where they went, for
 * the editor's drop targets.
 */
@Composable
internal fun HoneycombLayout(
    rows: List<HoneycombRow>,
    modifier: Modifier = Modifier,
    cell: Dp = ShoalQs.CELL,
    gap: Dp = ShoalQs.GAP,
    overlap: Dp = ShoalQs.OVERLAP,
    onGeometry: ((HoneycombGeometry) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val maxWidth =
            if (constraints.hasBoundedWidth) constraints.maxWidth
            else (cell.roundToPx() * (ShoalQs.MAX_PER_ROW + 1))
        val geometry = honeycombGeometry(rows, measurables.size, maxWidth, cell, gap, overlap)
        onGeometry?.invoke(geometry)
        val placeables =
            measurables.map { it.measure(Constraints.fixed(geometry.cell, geometry.cell)) }
        layout(maxWidth, geometry.height) {
            placeables.forEachIndexed { i, placeable ->
                placeable.place(geometry.positions[i].x, geometry.positions[i].y)
            }
        }
    }
}

// endregion
