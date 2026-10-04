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

import androidx.compose.runtime.compositionLocalOf
import com.android.systemui.qs.pipeline.shared.TileSpec

/**
 * IchthysOS "Shoal" junctions: the point where four big tiles meet (the centre of the column
 * gutter, on the boundary between two full rows of big tiles) is a slot that can hold one ordinary
 * tile, drawn as a small circle with the four tile corners around it scooped out.
 *
 * Junctions are positions, numbered from the top, and are stored as a list of slots
 * (Settings.Secure ichthys_qs_junctions, the ichthys.nix option quickSettings.junctions). They do
 * not belong to the big tiles next to them: reordering big tiles leaves the junction tiles where
 * they are, and a free slot stays free. A slot whose junction does not exist (too few rows) keeps
 * its tile, which is shown at the front of the circles until the junction is back.
 *
 * Everything here is plain data so that the panel, the pager and the editor agree on the layout.
 */
internal object ShoalJunctions {
    /** Rows of big tiles on one page of the panel (see [shoalPages]). */
    const val ROWS_PER_PAGE = 5
    const val WIDE_PER_PAGE = ROWS_PER_PAGE * 2
    /** A page of [ROWS_PER_PAGE] rows has one junction less: none on the seam to the next page. */
    const val PER_PAGE = ROWS_PER_PAGE - 1
    /** Circles on one page: a 6 / 5 / 6 honeycomb. */
    const val SMALL_PER_PAGE = 17

    const val NONE = -1

    /** The row of big tiles right above junction [junction] (rows and junctions count from 0). */
    fun rowAbove(junction: Int): Int = (junction / PER_PAGE) * ROWS_PER_PAGE + junction % PER_PAGE

    /**
     * The junction on the boundary below [row], or [NONE] for the last row of a page: the next row
     * is on the next page, so there is no meeting point.
     */
    fun below(row: Int): Int {
        if (row < 0 || row % ROWS_PER_PAGE == ROWS_PER_PAGE - 1) return NONE
        return (row / ROWS_PER_PAGE) * PER_PAGE + row % ROWS_PER_PAGE
    }

    /**
     * How many junctions [wideCount] big tiles have. A junction needs a full row on both sides, so
     * a lone last tile adds none, and neither does a single row.
     */
    fun count(wideCount: Int): Int {
        val fullRows = wideCount / 2
        if (fullRows < 2) return 0
        val boundaries = fullRows - 1
        val seams = boundaries / ROWS_PER_PAGE
        return boundaries - seams
    }
}

/**
 * The first junction slot of the page being composed. [PaginatedGridLayout] sets it for each page;
 * a grid that is not paged starts at slot 0.
 */
internal val LocalShoalJunctionOffset = compositionLocalOf { 0 }

/**
 * What goes where on one page: the big tiles in order, one entry per junction of those big tiles
 * (null for a free one), and the circles.
 */
internal class ShoalLayout<T>(val wide: List<T>, val junctions: List<T?>, val circles: List<T>)

/**
 * Resolves [tiles] (one page, or all of them) into the Shoal layout.
 *
 * A tile named in [slots] is a junction tile even if it is also in [large]: the slot is the more
 * specific instruction, and the editor writes the slot before it takes the tile out of the large
 * set. Slots naming tiles that are not in [tiles] count as free.
 *
 * @param slotOffset the slot of this page's first junction
 */
internal fun <T> shoalLayout(
    tiles: List<T>,
    large: Set<TileSpec>,
    slots: List<TileSpec?>,
    slotOffset: Int = 0,
    spec: (T) -> TileSpec,
): ShoalLayout<T> {
    val bySpec = tiles.associateBy(spec)
    val slotted = HashSet<TileSpec>()
    slots.forEach { if (it != null && it in bySpec) slotted.add(it) }

    val wide = tiles.filter { spec(it) in large && spec(it) !in slotted }
    val placed = HashSet<TileSpec>()
    val junctions =
        List(ShoalJunctions.count(wide.size)) { index ->
            val slot = slots.getOrNull(slotOffset + index)
            if (slot != null && placed.add(slot)) bySpec[slot] else null
        }
    // Junction tiles without a junction go first, in slot order, so they are easy to find.
    val displaced =
        slots.distinct().mapNotNull { if (it != null && it !in placed) bySpec[it] else null }
    val circles = displaced + tiles.filter { spec(it) !in large && spec(it) !in slotted }
    return ShoalLayout(wide, junctions, circles)
}

/**
 * Splits [tiles] into the pages of the Shoal panel: [ShoalJunctions.WIDE_PER_PAGE] big tiles and
 * [ShoalJunctions.SMALL_PER_PAGE] circles each, with every junction tile on the page that has its
 * junction. Page `p` starts at junction slot `p * ShoalJunctions.PER_PAGE`.
 */
internal fun <T> shoalPages(
    tiles: List<T>,
    large: Set<TileSpec>,
    slots: List<TileSpec?>,
    spec: (T) -> TileSpec,
): List<List<T>> {
    if (tiles.isEmpty()) return emptyList()
    val all = shoalLayout(tiles, large, slots, slotOffset = 0, spec = spec)
    val wide = all.wide.chunked(ShoalJunctions.WIDE_PER_PAGE)
    val junctions = all.junctions.chunked(ShoalJunctions.PER_PAGE)
    val circles = all.circles.chunked(ShoalJunctions.SMALL_PER_PAGE)
    return List(maxOf(wide.size, circles.size, 1)) { page ->
        wide.getOrElse(page) { emptyList() } +
            junctions.getOrElse(page) { emptyList() }.filterNotNull() +
            circles.getOrElse(page) { emptyList() }
    }
}

// region editor model

/** Where a tile is in a [ShoalEditLayout]. */
internal sealed interface ShoalPlace {
    data class Big(val index: Int) : ShoalPlace

    data class Junction(val slot: Int) : ShoalPlace

    /** [index] counts the circles as shown, the junction tiles without a junction first. */
    data class Circle(val index: Int) : ShoalPlace
}

/**
 * The layout the Shoal editor works on: the big tiles in order, the junction slots and the circles.
 * Every edit is a function from one layout to the next, so that a drag can show what a drop would
 * do before anything is written.
 *
 * [slots] only names current tiles and has no trailing free slots; it may be longer than the number
 * of junctions [big] has (see [displaced]).
 *
 * [big], [slots] and [circles] are the expanded panel. [collapsed] is the collapsed panel's own
 * selection (Settings.Secure ichthys_qqs_tiles), and independent of them: a tile can be in it and
 * in the expanded panel, or in only one of the two. Empty while nothing is picked, and the
 * collapsed panel then follows the expanded one (see [collapsedOr]).
 */
internal data class ShoalEditLayout(
    val big: List<TileSpec>,
    val slots: List<TileSpec?>,
    val circles: List<TileSpec>,
    val collapsed: List<TileSpec> = emptyList(),
) {
    val junctionCount: Int
        get() = ShoalJunctions.count(big.size)

    /** Junction tiles whose junction does not exist at the moment, in slot order. */
    val displaced: List<TileSpec>
        get() = slots.drop(junctionCount).filterNotNull()

    /** The circles as the panel shows them. */
    val shownCircles: List<TileSpec>
        get() = displaced + circles

    /** The tile in junction [slot], or null if it is free or does not exist. */
    fun junctionTile(slot: Int): TileSpec? =
        if (slot < junctionCount) slots.getOrNull(slot) else null

    /** Every tile of the layout, in the order written to the tile list. */
    fun tileSpecs(): List<TileSpec> = big + slots.filterNotNull() + circles

    operator fun contains(spec: TileSpec): Boolean = placeOf(spec) != null

    fun placeOf(spec: TileSpec): ShoalPlace? {
        val bigIndex = big.indexOf(spec)
        if (bigIndex >= 0) return ShoalPlace.Big(bigIndex)
        val slot = slots.indexOf(spec)
        if (slot in 0 until junctionCount) return ShoalPlace.Junction(slot)
        val circle = shownCircles.indexOf(spec)
        return if (circle >= 0) ShoalPlace.Circle(circle) else null
    }

    /** [spec] as a big tile at [index] (the end by default), wherever it was before. */
    fun toBig(spec: TileSpec, index: Int = Int.MAX_VALUE): ShoalEditLayout {
        val rest = without(spec)
        return rest.copy(big = rest.big.inserted(index, spec)).trimmed()
    }

    /**
     * [spec] as a circle at [shownIndex] of [shownCircles], wherever it was before. It always lands
     * among the ordinary circles, after the junction tiles that are waiting for a junction.
     */
    fun toCircle(spec: TileSpec, shownIndex: Int = Int.MAX_VALUE): ShoalEditLayout {
        val rest = without(spec)
        val index =
            if (shownIndex == Int.MAX_VALUE) shownIndex else shownIndex - rest.displaced.size
        return rest.copy(circles = rest.circles.inserted(index, spec)).trimmed()
    }

    /**
     * [spec] in junction [slot]. A tile already there takes the place [spec] came from (a tile
     * coming from the tray has no place to give, so the other one becomes the first circle). Null
     * when the junction would not exist afterwards: a big tile that leaves the last full row for a
     * junction takes that row's junction with it.
     */
    fun toJunction(spec: TileSpec, slot: Int): ShoalEditLayout? {
        if (slot < 0) return null
        val from = placeOf(spec)
        if (from == ShoalPlace.Junction(slot)) return null
        val rest = without(spec)
        val other = rest.slots.getOrNull(slot)
        var next = rest.withSlot(slot, spec)
        if (other != null) {
            next =
                when (from) {
                    is ShoalPlace.Big -> next.copy(big = next.big.inserted(from.index, other))
                    is ShoalPlace.Junction -> next.withSlot(from.slot, other)
                    is ShoalPlace.Circle ->
                        next.copy(
                            circles = next.circles.inserted(from.index - next.displaced.size, other)
                        )
                    null -> next.copy(circles = listOf(other) + next.circles)
                }
        }
        if (slot >= next.junctionCount) return null
        return next.trimmed()
    }

    /** The top-most free junction [spec] can move to, or null. */
    fun freeJunctionFor(spec: TileSpec): Int? {
        return (0 until junctionCount).firstOrNull {
            junctionTile(it) == null && toJunction(spec, it) != null
        }
    }

    /** The junctions [spec] can move to, free or not (a taken one swaps). */
    fun junctionsFor(spec: TileSpec): Set<Int> {
        return (0 until junctionCount).filterTo(HashSet()) { toJunction(spec, it) != null }
    }

    /** Takes [spec] out of the expanded panel. The collapsed panel keeps it if it has it. */
    fun remove(spec: TileSpec): ShoalEditLayout = without(spec).trimmed()

    /**
     * The collapsed panel's tiles: the ones picked or, while none are, the first [limit] of [order]
     * (the expanded panel's tiles as the tile list has them), which is what the panel shows then.
     * Every edit of the collapsed panel starts from this list, so the first one turns the automatic
     * tiles into picked ones.
     */
    fun collapsedOr(order: List<TileSpec>, limit: Int): List<TileSpec> {
        return collapsed.ifEmpty { order.take(limit) }
    }

    // The collapsed panel's tiles stay as they are: they do not depend on the expanded panel.
    private fun without(spec: TileSpec): ShoalEditLayout {
        return copy(
            big = big - spec,
            slots = slots.map { if (it == spec) null else it },
            circles = circles - spec,
        )
    }

    private fun withSlot(slot: Int, spec: TileSpec): ShoalEditLayout {
        val padded = slots + List(maxOf(0, slot + 1 - slots.size)) { null }
        return copy(slots = padded.mapIndexed { index, old -> if (index == slot) spec else old })
    }

    private fun trimmed(): ShoalEditLayout {
        return if (slots.lastOrNull() == null && slots.isNotEmpty()) {
            copy(slots = slots.dropLastWhile { it == null })
        } else {
            this
        }
    }

    companion object {
        /**
         * The layout of the [current] tiles, resolved the way [shoalLayout] resolves the panel
         * (without pages: the editor shows every row, and [ShoalJunctions] leaves the page seams
         * without a junction).
         *
         * @param current the tiles of the expanded panel, in tile list order
         * @param collapsed the collapsed panel's selection as stored. Only the tiles in the tile
         *   list are kept, each once, as the collapsed panel reads it: those in [current] and those
         *   in [collapsedOnly]
         * @param collapsedOnly the tiles that are in the tile list for the collapsed panel only
         */
        fun of(
            current: List<TileSpec>,
            large: Set<TileSpec>,
            slots: List<TileSpec?>,
            collapsed: List<TileSpec> = emptyList(),
            collapsedOnly: Set<TileSpec> = emptySet(),
        ): ShoalEditLayout {
            val currentSet = current.toSet()
            val seen = HashSet<TileSpec>()
            val cleanSlots =
                slots
                    .map { if (it != null && it in currentSet && seen.add(it)) it else null }
                    .dropLastWhile { it == null }
            return ShoalEditLayout(
                big = current.filter { it in large && it !in seen },
                slots = cleanSlots,
                circles = current.filter { it !in large && it !in seen },
                collapsed = collapsed.filter { it in currentSet || it in collapsedOnly }.distinct(),
            )
        }
    }
}

private fun <T> List<T>.inserted(index: Int, element: T): List<T> {
    val at = index.coerceIn(0, size)
    return subList(0, at) + element + subList(at, size)
}

// endregion
