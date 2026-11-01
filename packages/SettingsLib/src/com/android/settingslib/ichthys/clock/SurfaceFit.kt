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
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * IchthysOS projected clock, auto-fit: proposes a [ProjectedClock.Surface] from the wallpaper's
 * dominant straight edges (a Hough transform over its luminance edges, the sky and the subject
 * left out), and lists the lines found so the editor can snap handles to them.
 *
 * - Two strong near-vertical lines with a top and a bottom edge between them: a plane.
 * - Three verticals whose outer edges lean the opposite way on each side of the middle one: a fold
 *   at the middle (a building seen corner-first).
 * - A top or bottom edge whose points lie on a parabola rather than a line: a curve.
 * Nothing found: null (the editor keeps the default).
 *
 * All off the main thread (a 96 x 213 analysis of a half-size picture, a few ms).
 */
object SurfaceFit {
    /** A line found in the picture, fractions of the screen, with its strength (votes). */
    class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val strength: Float) {
        val vertical: Boolean
            get() = abs(x1 - x0) * ASPECT < abs(y1 - y0) * 0.35f

        /** x at [y] (a near-vertical line), fractions. */
        fun xAt(y: Float): Float = if (abs(y1 - y0) < 1e-5f) x0 else x0 + (x1 - x0) * (y - y0) / (y1 - y0)

        /** y at [x] (a near-horizontal line), fractions. */
        fun yAt(x: Float): Float = if (abs(x1 - x0) < 1e-5f) y0 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }

    class Proposal(val surface: ProjectedClock.Surface, val lines: List<Line>)

    /** The screen's height over its width (edge geometry is measured in width units). */
    private const val ASPECT = 2.2f
    private const val GRID_W = 96
    private const val GRID_H = 213
    private const val THETA_STEPS = 90 // 2° steps
    private const val MIN_VOTES = 14f

    /**
     * [picture] (any size) with its [sky] and [subject] masks (any size, alpha = coverage; null:
     * none) for a [screenW] x [screenH] screen.
     */
    fun fit(picture: Bitmap, sky: Bitmap?, subject: Bitmap?, screenW: Int, screenH: Int): Proposal? {
        val lum = luminance(picture)
        val skip = BooleanArray(GRID_W * GRID_H)
        sky?.let { mark(it, skip) }
        subject?.let { mark(it, skip) }
        val lines = hough(lum, skip)
        if (lines.isEmpty()) return null
        val verticals = lines.filter { it.vertical }.sortedByDescending { it.strength }
        val others = lines.filter { !it.vertical }.sortedByDescending { it.strength }
        // Verticals a wall apart, strongest first, at most three.
        val picked = ArrayList<Line>()
        for (v in verticals) {
            val x = v.xAt(0.45f)
            if (x < 0.04f || x > 0.96f) continue
            if (picked.none { abs(it.xAt(0.45f) - x) < 0.16f }) picked.add(v)
            if (picked.size == 3) break
        }
        if (picked.size < 2) return planeFromEdges(others, lines)
        val byX = picked.sortedBy { it.xAt(0.45f) }
        if (byX.size == 3) {
            val fold = foldFrom(byX, others, lines)
            if (fold != null) return fold
        }
        // The two strongest verticals that are far enough apart.
        val a = byX[0]
        val b = byX[byX.size - 1]
        return planeBetween(a, b, others, lines)
    }

    /** Handles that come within [tol] (fractions of the width) of a line snap onto it. */
    fun snap(x: Float, y: Float, lines: List<Line>, tol: Float): FloatArray? {
        var best: FloatArray? = null
        var bestD = tol
        for (l in lines) {
            // Distance to the infinite line, in width units.
            val ax = l.x0
            val ay = l.y0 * ASPECT
            val bx = l.x1
            val by = l.y1 * ASPECT
            val px = x
            val py = y * ASPECT
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy
            if (len2 < 1e-8f) continue
            val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(-0.2f, 1.2f)
            val qx = ax + vx * t
            val qy = ay + vy * t
            val d = hypot(px - qx, py - qy)
            if (d < bestD) {
                bestD = d
                best = floatArrayOf(qx, qy / ASPECT)
            }
        }
        return best
    }

    // ---- detection ----------------------------------------------------------------------------

    private fun luminance(picture: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(picture, GRID_W, GRID_H, true)
        val px = IntArray(GRID_W * GRID_H)
        small.getPixels(px, 0, GRID_W, 0, 0, GRID_W, GRID_H)
        return FloatArray(px.size) { i ->
            val c = px[i]
            (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
        }
    }

    private fun mark(mask: Bitmap, skip: BooleanArray) {
        val small = Bitmap.createScaledBitmap(mask, GRID_W, GRID_H, true)
        val px = IntArray(GRID_W * GRID_H)
        small.getPixels(px, 0, GRID_W, 0, 0, GRID_W, GRID_H)
        for (i in px.indices) if ((px[i] ushr 24) > 100) skip[i] = true
    }

    /**
     * Sobel edges over the luminance, then a Hough accumulator (theta 0..180° in 2° steps, rho in
     * width units), peaks taken as lines with their support's extent.
     */
    private fun hough(lum: FloatArray, skip: BooleanArray): List<Line> {
        val w = GRID_W
        val h = GRID_H
        // Edge magnitude and direction.
        val mag = FloatArray(w * h)
        val dir = FloatArray(w * h)
        var sum = 0f
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (skip[i]) continue
            val gx = (lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]) - (lum[i - w - 1] + 2 * lum[i - 1] + lum[i + w - 1])
            val gy = (lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]) - (lum[i - w - 1] + 2 * lum[i - w] + lum[i - w + 1])
            val m = hypot(gx, gy)
            mag[i] = m
            dir[i] = Math.atan2(gy.toDouble(), gx.toDouble()).toFloat()
            sum += m
            n++
        }
        if (n == 0) return emptyList()
        val threshold = max(0.12f, sum / n * 2.2f)
        // Accumulate: rho measured in width units (x in 0..1, y in 0..ASPECT), range -ASPECT..+sqrt(1+ASPECT^2).
        val rhoMax = sqrt(1f + ASPECT * ASPECT)
        val rhoSteps = 220
        val acc = FloatArray(THETA_STEPS * rhoSteps)
        val cosT = FloatArray(THETA_STEPS) { cos(Math.PI.toFloat() * it / THETA_STEPS) }
        val sinT = FloatArray(THETA_STEPS) { sin(Math.PI.toFloat() * it / THETA_STEPS) }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mag[i]
            if (m < threshold) continue
            val fx = (x + 0.5f) / w
            val fy = (y + 0.5f) / h * ASPECT
            // Only thetas near the gradient's direction (the edge's normal): sharper peaks.
            val normal = ((dir[i] / Math.PI.toFloat()) * THETA_STEPS).let { ((it % THETA_STEPS) + THETA_STEPS) % THETA_STEPS }
            for (dt in -3..3) {
                val t = (((normal.roundToInt() + dt) % THETA_STEPS) + THETA_STEPS) % THETA_STEPS
                val rho = fx * cosT[t] + fy * sinT[t]
                val r = (((rho + rhoMax) / (2 * rhoMax)) * (rhoSteps - 1)).roundToInt()
                if (r in 0 until rhoSteps) acc[t * rhoSteps + r] += m
            }
        }
        // Peaks: local maxima above the floor, at most 12, distinct.
        val peaks = ArrayList<IntArray>()
        for (t in 0 until THETA_STEPS) for (r in 1 until rhoSteps - 1) {
            val v = acc[t * rhoSteps + r]
            if (v < MIN_VOTES) continue
            var isMax = true
            loop@ for (dt in -2..2) for (dr in -2..2) {
                if (dt == 0 && dr == 0) continue
                val tt = ((t + dt) % THETA_STEPS + THETA_STEPS) % THETA_STEPS
                val rr = r + dr
                if (rr !in 0 until rhoSteps) continue
                if (acc[tt * rhoSteps + rr] > v) { isMax = false; break@loop }
            }
            if (isMax) peaks.add(intArrayOf(t, r))
        }
        peaks.sortByDescending { acc[it[0] * rhoSteps + it[1]] }
        val lines = ArrayList<Line>()
        for (p in peaks) {
            if (lines.size >= 12) break
            val t = p[0]
            val rho = (p[1].toFloat() / (rhoSteps - 1)) * 2 * rhoMax - rhoMax
            val strength = acc[t * rhoSteps + p[1]]
            // The support's extent along the line: the edge pixels within a band of it.
            val c = cosT[t]
            val s = sinT[t]
            var tMin = Float.MAX_VALUE
            var tMax = -Float.MAX_VALUE
            var count = 0
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (mag[i] < threshold) continue
                val fx = (x + 0.5f) / w
                val fy = (y + 0.5f) / h * ASPECT
                if (abs(fx * c + fy * s - rho) > 0.012f) continue
                val along = -fx * s + fy * c
                tMin = min(tMin, along)
                tMax = max(tMax, along)
                count++
            }
            if (count < 10 || tMax - tMin < 0.12f) continue
            // Endpoints: the foot of the normal plus the extent along the line.
            val x0 = rho * c - tMin * s
            val y0 = (rho * s + tMin * c) / ASPECT
            val x1 = rho * c - tMax * s
            val y1 = (rho * s + tMax * c) / ASPECT
            val line = Line(x0, y0, x1, y1, strength)
            // Distinct from the lines kept so far.
            if (lines.any { near(it, line) }) continue
            lines.add(line)
        }
        return lines
    }

    private fun near(a: Line, b: Line): Boolean {
        val ma = Math.atan2(((a.y1 - a.y0) * ASPECT).toDouble(), (a.x1 - a.x0).toDouble())
        val mb = Math.atan2(((b.y1 - b.y0) * ASPECT).toDouble(), (b.x1 - b.x0).toDouble())
        var da = abs(ma - mb)
        if (da > Math.PI) da = 2 * Math.PI - da
        if (da > Math.PI / 2) da = Math.PI - da
        if (da > Math.toRadians(6.0)) return false
        val mid = floatArrayOf((b.x0 + b.x1) / 2f, (b.y0 + b.y1) / 2f)
        return (snap(mid[0], mid[1], listOf(a), 0.05f) != null)
    }

    // ---- proposals ----------------------------------------------------------------------------

    /** Between two verticals: the strongest edge above and below the middle, else a level band. */
    private fun planeBetween(a: Line, b: Line, others: List<Line>, all: List<Line>): Proposal? {
        val xl = a.xAt(0.45f)
        val xr = b.xAt(0.45f)
        if (xr - xl < 0.22f) return null
        val top = edgeBetween(others, xl, xr, above = true)
        val bottom = edgeBetween(others, xl, xr, above = false)
        val q = FloatArray(8)
        val yTop = { x: Float -> top?.yAt(x) ?: 0.30f }
        val yBot = { x: Float -> bottom?.yAt(x) ?: 0.56f }
        q[0] = a.xAt(yTop(xl)); q[1] = yTop(q[0])
        q[2] = b.xAt(yTop(xr)); q[3] = yTop(q[2])
        q[4] = b.xAt(yBot(xr)); q[5] = yBot(q[4])
        q[6] = a.xAt(yBot(xl)); q[7] = yBot(q[6])
        // A band that is too thin or too tall is not a wall the clock can go on.
        val meanH = ((q[7] - q[1]) + (q[5] - q[3])) / 2f
        if (meanH < 0.08f || meanH > 0.5f) return null
        val plane = ProjectedClock.Surface(ProjectedClock.KIND_PLANE, q)
        if (!plane.isValid()) return null
        val curve = curveIfBent(plane, top, bottom)
        return Proposal(curve ?: plane, all)
    }

    /** No pair of verticals: a level band at the strongest horizontal edges, else nothing. */
    private fun planeFromEdges(others: List<Line>, all: List<Line>): Proposal? {
        val top = edgeBetween(others, 0.1f, 0.9f, above = true) ?: return null
        val bottom = edgeBetween(others, 0.1f, 0.9f, above = false) ?: return null
        val q = floatArrayOf(0.1f, top.yAt(0.1f), 0.9f, top.yAt(0.9f), 0.9f, bottom.yAt(0.9f), 0.1f, bottom.yAt(0.1f))
        val meanH = ((q[7] - q[1]) + (q[5] - q[3])) / 2f
        if (meanH < 0.08f || meanH > 0.5f) return null
        val plane = ProjectedClock.Surface(ProjectedClock.KIND_PLANE, q)
        return if (plane.isValid()) Proposal(curveIfBent(plane, top, bottom) ?: plane, all) else null
    }

    /**
     * Three verticals: a fold at the middle when the top edges on each side lean the opposite way
     * (the roof lines of a corner-first building recede to each side).
     */
    private fun foldFrom(v: List<Line>, others: List<Line>, all: List<Line>): Proposal? {
        val xl = v[0].xAt(0.45f)
        val xm = v[1].xAt(0.45f)
        val xr = v[2].xAt(0.45f)
        val topL = edgeBetween(others, xl, xm, above = true) ?: return null
        val topR = edgeBetween(others, xm, xr, above = true) ?: return null
        val slopeL = (topL.y1 - topL.y0) / ((topL.x1 - topL.x0).takeIf { abs(it) > 1e-4f } ?: return null)
        val slopeR = (topR.y1 - topR.y0) / ((topR.x1 - topR.x0).takeIf { abs(it) > 1e-4f } ?: return null)
        // The near edge is in the middle: the left roof climbs towards it, the right one falls.
        if (!(slopeL < -0.03f && slopeR > 0.03f)) return null
        val botL = edgeBetween(others, xl, xm, above = false)
        val botR = edgeBetween(others, xm, xr, above = false)
        val yTopL = topL.yAt(xl)
        val yTopM = (topL.yAt(xm) + topR.yAt(xm)) / 2f
        val yTopR = topR.yAt(xr)
        val h = 0.22f
        val yBotL = botL?.yAt(xl) ?: (yTopL + h)
        val yBotM = ((botL?.yAt(xm) ?: (yTopM + h * 1.15f)) + (botR?.yAt(xm) ?: (yTopM + h * 1.15f))) / 2f
        val yBotR = botR?.yAt(xr) ?: (yTopR + h)
        val s = ProjectedClock.Surface(ProjectedClock.KIND_FOLD,
            floatArrayOf(xl, yTopL, xr, yTopR, xr, yBotR, xl, yBotL, xm, yTopM, xm, yBotM), FloatArray(3), 2)
        return if (s.isValid()) Proposal(s, all) else null
    }

    /** The strongest non-vertical line crossing [xl]..[xr] above (or below) the band's middle. */
    private fun edgeBetween(others: List<Line>, xl: Float, xr: Float, above: Boolean): Line? {
        val xm = (xl + xr) / 2f
        return others.firstOrNull { l ->
            val lo = min(l.x0, l.x1)
            val hi = max(l.x0, l.x1)
            if (hi < xl + (xr - xl) * 0.3f || lo > xr - (xr - xl) * 0.3f) return@firstOrNull false
            val y = l.yAt(xm)
            if (y < 0.08f || y > 0.75f) return@firstOrNull false
            if (above) y < 0.45f else y >= 0.45f
        }
    }

    /**
     * A curve when the edges' support bends: the Hough gives straight lines only, so the bend is
     * read from where each edge's endpoints and middle disagree with its chord. Mild, so a
     * straight roof stays a plane.
     */
    private fun curveIfBent(plane: ProjectedClock.Surface, top: Line?, bottom: Line?): ProjectedClock.Surface? {
        // Without curvature information from the accumulator the bend is inferred from edge
        // slopes that converge the same way on both edges (an arch): both bow up.
        if (top == null || bottom == null) return null
        val st = (top.y1 - top.y0) / ((top.x1 - top.x0).takeIf { abs(it) > 1e-4f } ?: return null)
        val sb = (bottom.y1 - bottom.y0) / ((bottom.x1 - bottom.x0).takeIf { abs(it) > 1e-4f } ?: return null)
        // Both edges short and leaning alike towards the middle: likely one arc each side; bow.
        val spanT = abs(top.x1 - top.x0)
        val spanB = abs(bottom.x1 - bottom.x0)
        if (spanT > 0.55f || spanB > 0.55f) return null
        if (abs(st) < 0.08f || abs(sb) < 0.08f || st * sb < 0f) return null
        val bow = (abs(st) * 0.8f).coerceIn(0.15f, 0.6f)
        return ProjectedClock.Surface(ProjectedClock.KIND_CURVE, plane.points.copyOf(), floatArrayOf(bow, -bow, 0.2f))
    }
}
