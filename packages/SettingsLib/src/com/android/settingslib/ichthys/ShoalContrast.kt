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

package com.android.settingslib.ichthys

import com.android.internal.graphics.ColorUtils
import com.android.internal.graphics.cam.Cam
import com.android.internal.graphics.cam.CamUtils

/**
 * IchthysOS: adaptive contrast for the accent (or any colour) used as a FOREGROUND: text, glyphs,
 * outlines, rings and thin indicators drawn in the accent on a Shoal surface.
 *
 * The accent is the seed the user picked, and the Shoal UI paints it as is. A mid or light accent
 * (koi, pink) reads fine on the dark ink, a dark one (indigo #3949ab, navy) sinks into it, and
 * more so at low screen brightness. [readable] keeps the colour when it already reaches the
 * required contrast against the surface(s) it sits on - so an accent that passes is returned
 * bit-for-bit unchanged - and otherwise moves only its tone (CAM16 hue and chroma kept, L* moved:
 * HCT tone), lighter on a dark surface and darker on a light one, until it does. It still reads as
 * the user's accent, just a lighter (or darker) tone of it.
 *
 * The thresholds are WCAG 2.x ratios plus a fixed margin for dim screens (no runtime brightness
 * dependency, so the result is deterministic):
 * - [TEXT] 5:1 (WCAG 4.5:1 for body text),
 * - [GRAPHIC] 3.5:1 (WCAG 3:1 for icons, outlines and other non-text marks).
 *
 * Accent FILLS that carry their own foreground stay the accent; their foreground comes from
 * [onFill], black or white by contrast.
 *
 * Pure colour maths on ints, safe from any thread; results are cached, so calling it from a
 * per-second clock tick or a draw pass is cheap.
 */
object ShoalContrast {

    /** IchthysOS: text in the accent: WCAG 4.5:1 plus a margin for low screen brightness. */
    const val TEXT = 5.0

    /** IchthysOS: icons, outlines, rings, thin bars: WCAG 3:1 plus the same margin. */
    const val GRAPHIC = 3.5

    /**
     * IchthysOS: floor for a deliberately dimmed (disabled-looking) accent mark, see [dimmed]. It
     * must look quieter than the enabled state yet stay legible.
     */
    const val DIMMED = 3.0

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val CACHE_SIZE = 64

    private val cache =
        object : LinkedHashMap<String, Int>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) =
                size > CACHE_SIZE
        }

    /** IchthysOS: [fg] for text on [bgs]: see [readable] with [TEXT]. */
    @JvmStatic fun forText(fg: Int, vararg bgs: Int): Int = readable(fg, TEXT, *bgs)

    /** IchthysOS: [fg] for icons, outlines and strokes on [bgs]: [readable] with [GRAPHIC]. */
    @JvmStatic fun forGraphic(fg: Int, vararg bgs: Int): Int = readable(fg, GRAPHIC, *bgs)

    /**
     * IchthysOS: [fg] unchanged when it reaches [ratio] against every one of [bgs]; otherwise
     * [fg] with its tone moved away from them (lighter on dark backgrounds, darker on light ones),
     * hue and chroma kept as far as the sRGB gamut allows, at the first tone that does. Falls
     * back to white / black. The alpha of [fg] is kept; the contrast is measured on its opaque
     * colour. [bgs] are treated as opaque. No backgrounds: [fg] unchanged.
     */
    @JvmStatic
    fun readable(fg: Int, ratio: Double, vararg bgs: Int): Int {
        if (bgs.isEmpty()) return fg
        val alpha = fg and BLACK
        val opaqueFg = opaque(fg)
        if (minContrast(opaqueFg, bgs) >= ratio) return fg
        val key = buildString {
            append(opaqueFg).append('/').append(ratio)
            for (bg in bgs) append('/').append(opaque(bg))
        }
        val cached = synchronized(cache) { cache[key] }
        val solved = cached ?: solve(opaqueFg, ratio, bgs).also {
            synchronized(cache) { cache[key] = it }
        }
        return alpha or (solved and 0x00FFFFFF)
    }

    /**
     * IchthysOS: a quieter version of [fg] for a disabled or inactive mark on [bg]: [fg] pulled
     * [amount] of the way towards [bg] (what an alpha would do), but never below [DIMMED]:1, so a
     * dark accent does not vanish into the ink the way a plain 40% alpha made it.
     */
    @JvmStatic
    @JvmOverloads
    fun dimmed(fg: Int, bg: Int, amount: Float = 0.45f): Int {
        val blended = ColorUtils.blendARGB(opaque(fg), opaque(bg), amount.coerceIn(0f, 1f))
        return readable(blended, DIMMED, bg)
    }

    /**
     * IchthysOS: the foreground for a surface FILLED with [fill] (an active tile, an accent pill):
     * black or white, whichever contrasts more (always at least ~4.6:1).
     */
    @JvmStatic
    fun onFill(fill: Int): Int {
        val f = opaque(fill)
        return if (contrast(BLACK, f) >= contrast(WHITE, f)) BLACK else WHITE
    }

    /** IchthysOS: WCAG 2.x contrast ratio of the opaque colours of [a] and [b], in [1, 21]. */
    @JvmStatic
    fun contrast(a: Int, b: Int): Double = ColorUtils.calculateContrast(opaque(a), opaque(b))

    private fun solve(fg: Int, ratio: Double, bgs: IntArray): Int {
        // IchthysOS: direction: away from the backgrounds. Lighten when white beats black against
        // their mean luminance (the crossover is at a relative luminance of ~0.18).
        var lum = 0.0
        for (bg in bgs) lum += ColorUtils.calculateLuminance(opaque(bg))
        val lighten = lum / bgs.size < 0.179
        val cam = Cam.fromInt(fg)
        val hue = cam.hue
        val chroma = cam.chroma
        var tone = CamUtils.lstarFromInt(fg)
        while (if (lighten) tone < 100f else tone > 0f) {
            tone = if (lighten) minOf(100f, tone + 1f) else maxOf(0f, tone - 1f)
            val c = opaque(Cam.getInt(hue, chroma, tone))
            if (minContrast(c, bgs) >= ratio) return c
        }
        return if (lighten) WHITE else BLACK
    }

    private fun minContrast(fg: Int, bgs: IntArray): Double {
        var min = Double.MAX_VALUE
        for (bg in bgs) min = minOf(min, contrast(fg, bg))
        return min
    }

    private fun opaque(argb: Int): Int = BLACK or (argb and 0x00FFFFFF)
}
