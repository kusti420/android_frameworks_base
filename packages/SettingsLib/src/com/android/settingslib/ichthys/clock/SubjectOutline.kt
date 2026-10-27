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

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * IchthysOS lock screen "Shape" depth mode (kusti420/ichthysos-tracker#58, editor v2): the top
 * outline of the lock wallpaper's subject, for the Depth face to fit its digits into the space the
 * subject leaves.
 *
 * A [Profile] is made ONCE per mask: SystemUI's IchthysDepthRepository builds it from the subject
 * coverage grid it already has and publishes it here ([set]); the lock screen editor builds its own
 * from the mask it previews ([fromMask]). Faces only read it. Nothing is computed per frame.
 */
object SubjectOutline {

    /**
     * The subject's top edge in a screen of [width] x [height] px (natural portrait orientation):
     * [tops] has one value per column across the screen, the fraction of the screen's height where
     * the subject starts in that column (1 = nothing there). Eroded and smoothed, so single hair
     * strands make no spikes and the shape never dips into the subject.
     */
    class Profile(
        val tops: FloatArray,
        val width: Int,
        val height: Int,
        /**
         * IchthysOS: the line between the sky and the land, not a subject (DepthSource.skyline):
         * the clock stays above it and nothing is drawn over the clock.
         */
        val skyline: Boolean = false,
    ) {
        /** Top of the subject at [fx] (fraction of the width), as a fraction of the height. */
        fun topAt(fx: Float): Float {
            if (tops.isEmpty()) return 1f
            val p = (fx * tops.size - 0.5f).coerceIn(0f, (tops.size - 1).toFloat())
            val i = p.toInt()
            val j = min(i + 1, tops.size - 1)
            val t = p - i
            return tops[i] * (1 - t) + tops[j] * t
        }

        /** The highest point of the subject between [fx0] and [fx1] (fractions of the width). */
        fun minTop(fx0: Float, fx1: Float): Float {
            if (tops.isEmpty()) return 1f
            val a = (fx0 * tops.size).toInt().coerceIn(0, tops.size - 1)
            val b = (fx1 * tops.size).toInt().coerceIn(0, tops.size - 1)
            var m = 1f
            for (i in min(a, b)..max(a, b)) m = min(m, tops[i])
            return m
        }

        /** Left and right edge of the subject (fractions), or null without one. */
        fun span(): Pair<Float, Float>? {
            val first = tops.indexOfFirst { it < 1f }
            if (first < 0) return null
            val last = tops.indexOfLast { it < 1f }
            return first.toFloat() / tops.size to (last + 1f) / tops.size
        }

        /**
         * The top of the head: the highest point in the middle of the subject (its middle 60 %),
         * so a raised hand at the side doesn't count. 1 without a subject.
         */
        val headTop: Float by lazy {
            val s = span() ?: return@lazy 1f
            val w = s.second - s.first
            minTop(s.first + w * 0.2f, s.second - w * 0.2f)
        }

        /**
         * IchthysOS: the line (fraction of the height) the subject's face has ended above, for the
         * lock screen's notifications to start no higher than (SystemUI's upper region and the
         * editor's preview use this same value). The head is taken as round at its top: its
         * radius comes from how wide the outline is [HEAD_PROBE] under the head's top (less what
         * [clean]'s erosion added), and a head with its hair reaches about [HEAD_HEIGHTS] radii
         * down to the chin. 0 without a subject, or when the head is so low that nothing can go
         * under its face anyway.
         */
        val faceSafe: Float by lazy {
            val top = headTop
            val s = span()
            if (s == null || top >= MAX_HEAD_TOP || tops.isEmpty()) return@lazy 0f
            val n = tops.size
            val w = s.second - s.first
            // The head's highest column, in the same middle 60 % as headTop.
            val a0 = ((s.first + w * 0.2f) * n).toInt().coerceIn(0, n - 1)
            val b0 = ((s.second - w * 0.2f) * n).toInt().coerceIn(a0, n - 1)
            var peak = a0
            for (i in a0..b0) if (tops[i] < tops[peak]) peak = i
            val probe = top + HEAD_PROBE
            var a = peak
            while (a > 0 && tops[a - 1] <= probe) a--
            var b = peak
            while (b < n - 1 && tops[b + 1] <= probe) b++
            val eroded = 2 * max(1, (n * 0.03f).roundToInt())
            val chord = max(1, b - a + 1 - eroded).toFloat() / n * width
            val d = HEAD_PROBE * height
            // A circle's radius from a chord of [chord] at [d] under its top; never wider than
            // the chord itself (a flat-topped outline).
            val r = min((chord * chord / 4f + d * d) / (2f * d), chord)
            (top + r * HEAD_HEIGHTS / height).coerceIn(top + MIN_FACE, MAX_FACE_SAFE)
        }
    }

    /** See [Profile.faceSafe]. Fractions of the screen's height, except [HEAD_HEIGHTS]. */
    private const val HEAD_PROBE = 0.04f
    private const val HEAD_HEIGHTS = 2.8f
    private const val MIN_FACE = 0.12f
    private const val MAX_FACE_SAFE = 0.70f
    private const val MAX_HEAD_TOP = 0.55f

    @Volatile
    var current: Profile? = null
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** SystemUI: the lock wallpaper's outline now (null: no subject, or depth off). */
    fun set(profile: Profile?) {
        if (profile === current) return
        current = profile
        listeners.forEach { it() }
    }

    /**
     * From a coverage grid ([cov], row-major, [gridW] x [gridH] cells, alpha 0..255) laid over a
     * screen of [screenW] x [screenH] px. Null when nothing is covered.
     */
    fun fromCoverage(
        cov: ByteArray,
        gridW: Int,
        gridH: Int,
        screenW: Int,
        screenH: Int,
        threshold: Int = 128,
        skyline: Boolean = false,
    ): Profile? {
        if (gridW <= 0 || gridH <= 1 || cov.size < gridW * gridH) return null
        val raw = FloatArray(gridW) { 1f }
        var any = false
        for (x in 0 until gridW) {
            // Two covered cells in a row: a speck of background noise doesn't start the subject.
            for (y in 0 until gridH - 1) {
                if ((cov[y * gridW + x].toInt() and 0xff) > threshold &&
                    (cov[(y + 1) * gridW + x].toInt() and 0xff) > threshold) {
                    raw[x] = y.toFloat() / gridH
                    any = true
                    break
                }
            }
        }
        return if (any) Profile(clean(raw), screenW, screenH, skyline) else null
    }

    /**
     * From a mask (the subject's coverage in the alpha channel) that is already cut to a screen of
     * [screenW] x [screenH] px, at any resolution.
     */
    fun fromMask(mask: Bitmap, screenW: Int, screenH: Int): Profile? {
        val gw = max(16, min(COLUMNS, mask.width))
        val gh = max(16, (gw.toFloat() * mask.height / mask.width).roundToInt())
        val small = Bitmap.createScaledBitmap(mask, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        if (small !== mask) small.recycle()
        val cov = ByteArray(px.size) { (px[it] ushr 24).toByte() }
        return fromCoverage(cov, gw, gh, screenW, screenH)
    }

    /** Erode (lowest top within ~3 % of the width), then smooth (~8 %), never below the eroded. */
    private fun clean(raw: FloatArray): FloatArray {
        val n = raw.size
        val r1 = max(1, (n * 0.03f).roundToInt())
        val r2 = max(1, (n * 0.08f).roundToInt())
        val eroded = FloatArray(n) { i ->
            var m = 1f
            for (j in max(0, i - r1)..min(n - 1, i + r1)) m = min(m, raw[j])
            m
        }
        return FloatArray(n) { i ->
            var s = 0f
            var c = 0
            for (j in max(0, i - r2)..min(n - 1, i + r2)) {
                s += eroded[j]
                c++
            }
            min(s / c, eroded[i] + 0.01f)
        }
    }

    /** Columns a mask is read at (8 px each on a 1080 px wide screen). */
    const val COLUMNS = 135
}

/**
 * IchthysOS: the "Shape" depth mode's sizing, One UI style. The digits keep their letterforms: they
 * are only stretched or squeezed in HEIGHT (width fixed), as whole rigid glyphs, never warped.
 * - [stretch]: one vertical factor for the whole clock, so its bottom ends just above the subject
 *   (or, with a tuck, a little behind it, where the cut-out covers it).
 * - [follow]: one factor per digit column, each from the outline under that column, with
 *   neighbours at most [MAX_STEP] apart, so the clock steps down calmly over a head.
 * A factor k scales the digits about their top: y' = top + (y - top) * k.
 */
object AdaptiveShape {
    const val MIN_K = 0.5f
    const val MAX_K = 1.8f
    /** At most this ratio between neighbouring digit columns ([follow]). */
    const val MAX_STEP = 1.2f
    /** Share of the clock's height that may go behind the subject with [stretch]'s tuck. */
    const val TUCK = 0.18f

    /**
     * The vertical factor that makes a block of natural [height] starting at [top] end at [limit]
     * ([tuck]: that share of the block may end behind it).
     */
    fun stretch(top: Float, height: Float, limit: Float, tuck: Boolean = false): Float {
        if (height <= 0f) return 1f
        val room = (limit - top) / if (tuck) height * (1f - TUCK) else height
        return room.coerceIn(MIN_K, MAX_K)
    }

    /**
     * Per digit column: [limits] are the lowest y each column may reach. Each gets [stretch]'s
     * factor, then a taller column is brought down to [MAX_STEP] times its neighbour (only ever
     * down, so every column still clears the subject).
     */
    fun follow(top: Float, height: Float, limits: FloatArray): FloatArray {
        val k = FloatArray(limits.size) { stretch(top, height, limits[it]) }
        for (pass in 0 until 2) {
            for (i in 1 until k.size) k[i] = min(k[i], k[i - 1] * MAX_STEP)
            for (i in k.size - 2 downTo 0) k[i] = min(k[i], k[i + 1] * MAX_STEP)
        }
        return k
    }
}
