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

package com.android.settingslib.ichthys.clock

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * IchthysOS: what the lock screen's depth may stand on, decided the same way for SystemUI's lock
 * screen (IchthysDepthRepository) and the lock screen editor's preview, so they always agree.
 *
 * Depth is only ever drawn when it is right:
 * - [isRealSubject]: a person, a car, a tower. The subject finder's coverage is solid (little grey
 *   mush) and the picture changes clearly across its outline. Then it stands in front of the
 *   clock. A speck, or a mushy patch (haze, a landscape's "most salient" area) is no subject: the
 *   clock used to sit half inside a mountain.
 * - [skyline]: no subject, but a crisp line between the sky and the land (a ridge, a city, a hill).
 *   Then the clock belongs in the sky: its outline is published with [SubjectOutline.Profile.skyline]
 *   and the face keeps its digits above it (DepthClockView), nothing drawn over the clock.
 * - Neither: no depth; the clock stands in front of the picture, cleanly.
 */
object DepthSource {
    /** Below this share of the screen covered, a mask has no real subject. */
    const val MIN_SHARE = 0.01f

    /**
     * Solid share (fully covered among covered) and edge (mean colour step across the outline) a
     * real subject has. Measured on test pictures: a portrait 0.98 / 0.35, a tower 0.87 / 0.15, a
     * mountain range 0.69 / 0.35; hazy ridges 0.24 / 0.09, sunsets and fields well below.
     */
    const val MIN_CRISP = 0.55f
    const val MIN_EDGE = 0.1f

    /** The land must stand off the sky this clearly (RGB step) for a skyline. */
    const val MIN_SKYLINE_EDGE = 0.1f

    /** Narrower than this share of the width, what rises above the skyline isn't part of it. */
    private const val THIN = 0.08f

    private const val GRID_W = 135
    private const val GRID_H = 300

    /**
     * Whether [subject] (alpha = the subject's coverage, cut to the screen, any size) is a real
     * subject in [picture] (the same framing, any size), with the measures for the log.
     */
    fun isRealSubject(subject: Bitmap, picture: Bitmap): Pair<Boolean, String> {
        val w = GRID_W
        val h = GRID_H
        val cov = alpha(subject, w, h)
        val px = pixels(picture, w, h)
        var covered = 0
        var solid = 0
        for (a in cov) {
            if (a > 40) covered++
            if (a > 204) solid++
        }
        val share = covered.toFloat() / cov.size
        val crisp = if (covered > 0) solid.toFloat() / covered else 0f
        var edgeSum = 0f
        var edgeN = 0
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            val i = y * w + x
            if (cov[i] < 153) continue
            // The colour step across the outline (a green statue against grey cloud differs in
            // colour, hardly in brightness).
            var r = 0f
            var g = 0f
            var b = 0f
            var outN = 0
            for (dy in -3..3) for (dx in -3..3) {
                val xx = x + dx
                val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val j = yy * w + xx
                if (cov[j] < 51) {
                    r += ((px[j] shr 16) and 0xff) / 255f
                    g += ((px[j] shr 8) and 0xff) / 255f
                    b += (px[j] and 0xff) / 255f
                    outN++
                }
            }
            if (outN > 0) {
                val dr = ((px[i] shr 16) and 0xff) / 255f - r / outN
                val dg = ((px[i] shr 8) and 0xff) / 255f - g / outN
                val db = (px[i] and 0xff) / 255f - b / outN
                edgeSum += sqrt((dr * dr + dg * dg + db * db) / 3f)
                edgeN++
            }
        }
        val edge = if (edgeN > 0) edgeSum / edgeN else 0f
        val ok = share >= MIN_SHARE && crisp >= MIN_CRISP && edge >= MIN_EDGE
        return ok to "share ${"%.3f".format(share)} crisp ${"%.2f".format(crisp)} " +
            "edge ${"%.3f".format(edge)}"
    }

    /**
     * The skyline of [sky] (alpha = sky, cut to the screen, any size) over [picture] (the same
     * framing) as an outline of a [screenW] x [screenH] screen, flagged as a skyline; null when
     * there is none or it is soft (the land hardly differs from the sky along it).
     */
    fun skyline(sky: Bitmap, picture: Bitmap, screenW: Int, screenH: Int): SubjectOutline.Profile? {
        val w = GRID_W
        val h = GRID_H
        val s = alpha(sky, w, h)
        val px = pixels(picture, w, h)
        val tops = IntArray(w)
        for (x in 0 until w) {
            var y = 0
            while (y < h && s[y * w + x] > 128) y++
            tops[x] = y
        }
        val sorted = tops.sortedArray()
        val median = sorted[w / 2]
        if (median <= 2 || median >= h - 2) return null
        var edgeSum = 0f
        var edgeN = 0
        for (x in 0 until w) {
            val y = tops[x]
            if (y < 3 || y > h - 4) continue
            val a = px[(y - 3) * w + x]
            val b = px[(y + 3) * w + x]
            val dr = (((a shr 16) and 0xff) - ((b shr 16) and 0xff)) / 255f
            val dg = (((a shr 8) and 0xff) - ((b shr 8) and 0xff)) / 255f
            val db = ((a and 0xff) - (b and 0xff)) / 255f
            edgeSum += sqrt((dr * dr + dg * dg + db * db) / 3f)
            edgeN++
        }
        val edge = if (edgeN > 0) edgeSum / edgeN else 0f
        if (edge < MIN_SKYLINE_EDGE) return null
        // The skyline is the broad line of the land: anything narrower than [THIN] of the width
        // that rises above it (a bare tree, a pole, a lone tower) doesn't count, or the clock
        // would squeeze above a branch tip. A morphological opening of the tops (rising spikes
        // are minima): the max over the window removes them, the min restores broad shapes.
        val r = max(1, (w * THIN / 2).toInt())
        val eroded = IntArray(w) { x ->
            var m = 0
            for (i in max(0, x - r)..min(w - 1, x + r)) m = max(m, tops[i])
            m
        }
        val opened = IntArray(w) { x ->
            var m = h
            for (i in max(0, x - r)..min(w - 1, x + r)) m = min(m, eroded[i])
            m
        }
        val land = ByteArray(w * h)
        for (x in 0 until w) for (y in opened[x] until h) land[y * w + x] = 255.toByte()
        return SubjectOutline.fromCoverage(land, w, h, screenW, screenH, skyline = true)
    }

    private fun alpha(b: Bitmap, w: Int, h: Int): IntArray {
        val px = pixels(b, w, h)
        return IntArray(px.size) { px[it] ushr 24 }
    }

    private fun pixels(b: Bitmap, w: Int, h: Int): IntArray {
        val soft = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val small = Bitmap.createScaledBitmap(soft, w, h, true)
        // On ALPHA_8 getPixels gives the alpha over black: all the alpha reads need.
        val out = IntArray(w * h)
        small.getPixels(out, 0, w, 0, 0, w, h)
        if (small !== soft) small.recycle()
        if (soft !== b) soft.recycle()
        return out
    }

}
