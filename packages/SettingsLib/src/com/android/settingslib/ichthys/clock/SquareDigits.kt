/*
 * Copyright (C) 2026 IchthysOS Project
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

package com.android.settingslib.ichthys.clock

import android.graphics.Path
import kotlin.math.min

/**
 * IchthysOS: the Depth face's "Square" digits ([DepthClockView.FONT_SQUARE]), drawn from
 * geometry instead of a typeface: thick straight strokes with square corners on a seven-segment
 * grid, hollow inside ("0" a tall rectangle ring, "9" the same with its lower left side left
 * out). Each digit's strokes are merged into one clean outline ([Path.op]), so it fills, clips
 * the heat and strokes as an AOD outline exactly like a font's glyph would, with no seams.
 *
 * All sizes are shares of the text size, like a font's: digits are tabular ([ADVANCE] each,
 * "1" included, so the clock never shifts as the minutes turn), [CAP] tall, and the gap between
 * two digits ([GAP]) is the one the face keeps between the hours and the minutes, so the four
 * digits sit as an even 2 x 2 block.
 */
object SquareDigits {
    /** Ink height (the digits' cap height) per text size. */
    const val CAP = 0.72f
    /** Ink width of a digit. */
    const val WIDTH = 0.44f
    /** Stroke weight. Bold: the counters stay about as wide as two strokes. */
    const val STROKE = 0.11f
    /** Side bearing on each side of a digit. */
    const val SIDE = 0.04f
    /** One digit's advance. */
    const val ADVANCE = WIDTH + 2 * SIDE
    /** Space between two digits' ink, and between the face's two rows of them. */
    const val GAP = 2 * SIDE

    // Seven-segment parts: top, upper right, lower right, bottom, lower left, upper left, middle.
    private const val A = 1
    private const val B = 2
    private const val C = 4
    private const val D = 8
    private const val E = 16
    private const val F = 32
    private const val G = 64

    /** The parts each digit is made of ("1" is drawn on its own, see [glyph]). */
    private val PARTS =
        intArrayOf(
            A or B or C or D or E or F, // 0
            B or C, // 1
            A or B or G or E or D, // 2
            A or B or G or C or D, // 3
            F or G or B or C, // 4
            A or F or G or C or D, // 5
            A or F or G or E or C or D, // 6
            A or B or C, // 7
            A or B or C or D or E or F or G, // 8
            A or B or C or D or F or G, // 9
        )

    fun advance(size: Float): Float = size * ADVANCE

    fun capHeight(size: Float): Float = size * CAP

    /**
     * The digits of [text] ([start] until [end]) added to [out], their advance boxes laid from
     * [left], standing on [baseline]. Anything that is not a digit keeps its room and draws
     * nothing.
     */
    fun textPath(text: CharSequence, start: Int, end: Int, left: Float, baseline: Float,
            size: Float, out: Path) {
        val glyph = Path()
        var x = left
        for (i in start until end) {
            val d = text[i] - '0'
            if (d in 0..9) {
                glyph(d, x, baseline - capHeight(size), baseline, size, glyph)
                out.addPath(glyph)
            }
            x += advance(size)
        }
    }

    /**
     * [out] set to [digit]'s outline: in its advance box from [left], the ink from [top] to
     * [bottom]. The height may differ from [capHeight] (the Shape mode stretches or squeezes the
     * digits): the verticals grow or shrink and the strokes keep their weight, so a stretched
     * digit stays the same letterform instead of getting fat horizontal bars. Only when squeezed
     * hard do the strokes thin, so the counters never close.
     */
    fun glyph(digit: Int, left: Float, top: Float, bottom: Float, size: Float, out: Path) {
        out.reset()
        val h = bottom - top
        if (h <= 0f || size <= 0f || digit !in 0..9) return
        val t = min(size * STROKE, h * 0.2f)
        val l = left + size * SIDE
        val r = l + size * WIDTH
        val mid = top + h / 2f
        val piece = Path()
        fun bar(x0: Float, y0: Float, x1: Float, y1: Float) {
            piece.reset()
            piece.addRect(x0, y0, x1, y1, Path.Direction.CW)
            if (out.isEmpty) out.set(piece)
            // Should never fail for rectangles; if it did, the winding fill still draws it right.
            else if (!out.op(piece, Path.Op.UNION)) out.addPath(piece)
        }
        if (digit == 1) {
            // A stem a little right of centre with a square flag to its left, so the "1" holds
            // its tabular box as evenly as the other digits.
            val stem = l + (r - l - t) / 2f + t * 0.35f
            bar(stem, top, stem + t, bottom)
            bar(stem - size * WIDTH * 0.28f, top, stem + t, top + t)
            return
        }
        val parts = PARTS[digit]
        if (parts and A != 0) bar(l, top, r, top + t)
        if (parts and F != 0) bar(l, top, l + t, mid + t / 2f)
        if (parts and B != 0) bar(r - t, top, r, mid + t / 2f)
        // The "3"'s middle stroke stops short of the left side, so it doesn't read as a
        // mirrored "E".
        if (parts and G != 0) {
            bar(if (digit == 3) l + (r - l) * 0.3f else l, mid - t / 2f, r, mid + t / 2f)
        }
        if (parts and E != 0) bar(l, mid - t / 2f, l + t, bottom)
        if (parts and C != 0) bar(r - t, mid - t / 2f, r, bottom)
        if (parts and D != 0) bar(l, bottom - t, r, bottom)
    }
}
