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
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.Shader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * IchthysOS "Projected" clock (the concept canvas's clock on a wall): the Depth face's digits
 * put onto a surface of the wallpaper (the lit face of a building, a wall, a curved roof band)
 * instead of standing upright over it.
 *
 * SURFACES ([Surface], one per wallpaper in [KEY_QUADS]; the lock screen editor's handles set it):
 * - [KIND_PLANE]: a four-corner quad (the only kind there was; a projective map onto it).
 * - [KIND_FOLD]: two quads sharing the corner edge of a building seen corner-first; the clock is
 *   one row, split at the fold ([Surface.split] digits on the first face), each face its own
 *   homography and its own light.
 * - [KIND_CURVE]: a quad whose top and bottom edges are quadratic Béziers ([Surface.bend] 0 and 1
 *   bow them outward, in shares of the quad's height) plus a cylinder bend about a vertical axis
 *   ([Surface.bend] 2): curved roof bands.
 * - [KIND_MESH]: 3 x 3 control points the surface passes through (quadratic Lagrange), for
 *   anything else.
 * Every kind goes through ONE warp ([Mapper]): the flat digit outlines are sampled finely and each
 * point mapped from the block's (u, v) onto the surface; a plane keeps the exact projective
 * transform as its fast path. (A bitmap mesh was considered; warping the outlines through the same
 * surface function gives the same geometry with crisp edges and lets every style stay a paint
 * pass over paths.)
 *
 * STYLES ([KEY_STYLE]) are paint passes over the warped digits, so every style works on every
 * surface: [STYLE_PAINTED] (paint multiplied into the wall's shading, a contact shadow in the
 * plane, the wall's tint), [STYLE_LIGHT] (glowing digits, the glow spilling onto the wall, strong
 * at night and a faint tube by day), [STYLE_ENGRAVED] (cut into the wall: darker inner face, a light
 * rim on the sun's side, shadow opposite, colours from the wall), [STYLE_SIGNAGE] (a matte plate in
 * the plane under the digits with a small standoff shadow), [STYLE_BEAM] (soft bright digits and a
 * faint cone towards a source corner; by day it falls back to Painted) and [STYLE_SHADOW] (the
 * digits are a shadow whose length, lean and softness follow the sun; moon-blue at night). The
 * light comes from Living Sky ([Light]); without it, from the hour.
 *
 * Drawn by SystemUI's depth layers (IchthysDepthLayers: under the subject's cut-out, so the subject
 * still stands in front and the X-ray lens still cuts through it) and the lock screen editor's
 * preview, both through [Renderer], so they match. Everything is drawn inside the given canvas: no
 * layer grows (points are clamped to a little past the screen, [QUAD_MIN]..[QUAD_MAX]).
 */
object ProjectedClock {
    /**
     * Settings.Secure: a JSON object, the saved surfaces: wallpaper key (IchthysDepth's
     * "L<lockId>S<sysId>", as its masks are keyed) to a plane's "x0,y0,...,x3,y3" (fractions of the
     * screen; top left, top right, bottom right, bottom left: the original format) or a surface's
     * JSON object ([Surface.toJson]). A wallpaper without one uses the default surface of the
     * [KEY_SURFACE_KIND] kind. At most [MAX_SAVED] are kept (the oldest go).
     */
    const val KEY_QUADS = "ichthys_clock_quads"
    private const val MAX_SAVED = 16

    /** Settings.Secure: the paint style, [STYLE_PAINTED] .. [STYLE_SHADOW]. */
    const val KEY_STYLE = "ichthys_clock_projected_style"
    const val STYLE_PAINTED = 0
    const val STYLE_LIGHT = 1
    const val STYLE_ENGRAVED = 2
    const val STYLE_SIGNAGE = 3
    const val STYLE_BEAM = 4
    const val STYLE_SHADOW = 5
    const val STYLE_COUNT = 6

    /** Settings.Secure: the surface kind a wallpaper without a saved surface starts with. */
    const val KEY_SURFACE_KIND = "ichthys_clock_surface"
    const val KIND_PLANE = 0
    const val KIND_FOLD = 1
    const val KIND_CURVE = 2
    const val KIND_MESH = 3
    const val KIND_COUNT = 4

    /**
     * The default plane, for the typical 1080 x 2400 "architecture corner" wallpaper (a big flat
     * building face lit from the left, lower middle to right, against a flat sky): a wall seen at
     * about 28° of yaw, its near edge at mid screen, receding to the right. The far edge is 0.73
     * of the near one, the top and bottom edges meet just off the right edge of the screen at 47 %
     * of its height. The clock sits in the band 28..58 % of the height: under the date line's
     * place and above the shortcuts.
     */
    val DEFAULT_QUAD = floatArrayOf(0.47f, 0.28f, 0.92f, 0.325f, 0.92f, 0.545f, 0.47f, 0.58f)

    /** How far past the screen a point may go (fractions). */
    const val QUAD_MIN = -0.25f
    const val QUAD_MAX = 1.25f
    /** The least a surface's edge may measure (fraction of the screen's width). */
    const val MIN_EDGE = 0.08f
    /** The most an edge may bow (share of the quad's height) and the cylinder's range. */
    const val MAX_BOW = 1.2f

    /** Fractions of the height measured against the width, for edge lengths (a 1 : 2.2 screen). */
    private const val ASPECT_Y = 2.2f

    // ---- the surface model --------------------------------------------------------------------

    /**
     * One surface. [points] are fractions of the screen, x,y pairs:
     * - plane and curve: 4 (top left, top right, bottom right, bottom left);
     * - fold: 6: the 4 outer corners, then the fold edge's top and bottom;
     * - mesh: 9, row-major (top row left to right, middle row, bottom row).
     * [bend] (curve): top bow, bottom bow (shares of the mean height, outward positive), cylinder
     * (-1..1). [split] (fold): how many of the four digits stand on the first face (1..3).
     */
    class Surface(
        val kind: Int,
        val points: FloatArray,
        val bend: FloatArray = FloatArray(3),
        val split: Int = 2,
    ) {
        val pointCount: Int
            get() = points.size / 2

        fun point(i: Int): FloatArray = floatArrayOf(points[i * 2], points[i * 2 + 1])

        fun withPoint(i: Int, x: Float, y: Float): Surface {
            val p = points.copyOf()
            p[i * 2] = x.coerceIn(QUAD_MIN, QUAD_MAX)
            p[i * 2 + 1] = y.coerceIn(QUAD_MIN, QUAD_MAX)
            return Surface(kind, p, bend.copyOf(), split)
        }

        fun withBend(i: Int, v: Float): Surface {
            val b = bend.copyOf()
            b[i] = v.coerceIn(-MAX_BOW, MAX_BOW)
            return Surface(kind, points.copyOf(), b, split)
        }

        fun withSplit(s: Int): Surface = Surface(kind, points.copyOf(), bend.copyOf(), s.coerceIn(1, 3))

        /** Every point moved by ([dx], [dy]) fractions. */
        fun translated(dx: Float, dy: Float): Surface {
            val p = FloatArray(points.size) { i ->
                (points[i] + if (i % 2 == 0) dx else dy).coerceIn(QUAD_MIN, QUAD_MAX)
            }
            return Surface(kind, p, bend.copyOf(), split)
        }

        /** Every point scaled by [k] about the surface's centre ([ASPECT_Y] keeps it even). */
        fun scaled(k: Float): Surface {
            val c = centre()
            val p = FloatArray(points.size) { i ->
                val cv = if (i % 2 == 0) c[0] else c[1]
                (cv + (points[i] - cv) * k).coerceIn(QUAD_MIN, QUAD_MAX)
            }
            return Surface(kind, p, bend.copyOf(), split)
        }

        fun centre(): FloatArray {
            var x = 0f
            var y = 0f
            for (i in 0 until pointCount) {
                x += points[i * 2]
                y += points[i * 2 + 1]
            }
            return floatArrayOf(x / pointCount, y / pointCount)
        }

        /** The four outer corners (top left, top right, bottom right, bottom left), fractions. */
        fun corners(): FloatArray =
            when (kind) {
                KIND_MESH -> floatArrayOf(points[0], points[1], points[4], points[5], points[16], points[17],
                    points[12], points[13])
                else -> points.copyOf(8)
            }

        /** The box the surface lies in (fractions), bows included. */
        fun bounds(): RectF {
            val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (i in 0 until pointCount) r.union(points[i * 2], points[i * 2 + 1])
            if (kind == KIND_CURVE) {
                // The bows reach half their control's offset (a quadratic Bézier's extreme).
                val meanH = ((points[7] - points[1]) + (points[5] - points[3])) / 2f
                r.top -= max(0f, bend[0]) * meanH * 0.5f
                r.bottom += max(0f, bend[1]) * meanH * 0.5f
            }
            return r
        }

        /** The mean height of the surface on a [w] x [h] screen, px. */
        fun meanHeightPx(w: Int, h: Int): Float {
            val c = corners()
            return (((c[7] - c[1]) + (c[5] - c[3])) / 2f * h).coerceAtLeast(1f)
        }

        /** Inside the allowed range, every edge long enough, every face convex (a wall has no fold). */
        fun isValid(): Boolean {
            for (v in points) if (v.isNaN() || v < QUAD_MIN || v > QUAD_MAX) return false
            return when (kind) {
                KIND_PLANE -> points.size == 8 && isValidQuad(points)
                KIND_CURVE ->
                    points.size == 8 && isValidQuad(points) &&
                        bend.size >= 3 && bend.all { !it.isNaN() && abs(it) <= MAX_BOW }
                KIND_FOLD -> {
                    if (points.size != 12) return false
                    isValidQuad(faceQuad(0)) && isValidQuad(faceQuad(1)) && split in 1..3
                }
                KIND_MESH -> {
                    if (points.size != 18) return false
                    // Each of the four cells a proper quad.
                    for (r in 0..1) for (c in 0..1) if (!isValidQuad(cellQuad(r, c))) return false
                    true
                }
                else -> false
            }
        }

        /** Fold: face 0 (left) = p0, f0, f1, p3; face 1 (right) = f0, p1, p2, f1 (fractions). */
        fun faceQuad(face: Int): FloatArray =
            if (face == 0)
                floatArrayOf(points[0], points[1], points[8], points[9], points[10], points[11], points[6], points[7])
            else
                floatArrayOf(points[8], points[9], points[2], points[3], points[4], points[5], points[10], points[11])

        private fun cellQuad(r: Int, c: Int): FloatArray {
            fun p(row: Int, col: Int, k: Int) = points[(row * 3 + col) * 2 + k]
            return floatArrayOf(p(r, c, 0), p(r, c, 1), p(r, c + 1, 0), p(r, c + 1, 1),
                p(r + 1, c + 1, 0), p(r + 1, c + 1, 1), p(r + 1, c, 0), p(r + 1, c, 1))
        }

        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("k", kind)
            val p = JSONArray()
            for (v in points) p.put(String.format(Locale.ROOT, "%.4f", v).toDouble())
            o.put("p", p)
            if (kind == KIND_CURVE) {
                val b = JSONArray()
                for (v in bend) b.put(String.format(Locale.ROOT, "%.3f", v).toDouble())
                o.put("b", b)
            }
            if (kind == KIND_FOLD) o.put("s", split)
            return o
        }

        /** The same surface as another kind, keeping its place: the editor's kind chips. */
        fun asKind(newKind: Int): Surface {
            if (newKind == kind) return this
            val c = corners()
            return when (newKind) {
                KIND_PLANE -> Surface(KIND_PLANE, c)
                KIND_CURVE -> Surface(KIND_CURVE, c, floatArrayOf(0.35f, 0.35f, 0.25f))
                KIND_FOLD -> {
                    val fx = (c[0] + c[2]) / 2f
                    val topY = (c[1] + c[3]) / 2f
                    val botY = (c[5] + c[7]) / 2f
                    val hgt = botY - topY
                    // The fold edge is nearer: a little taller than the outer edges.
                    Surface(KIND_FOLD, floatArrayOf(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7],
                        fx, topY - hgt * 0.12f, fx, botY + hgt * 0.12f), FloatArray(3), 2)
                }
                else -> Surface(KIND_MESH, meshFromQuad(c))
            }
        }

        override fun equals(other: Any?): Boolean =
            other is Surface && other.kind == kind && other.split == split &&
                other.points.contentEquals(points) && other.bend.contentEquals(bend)

        override fun hashCode(): Int =
            ((kind * 31 + split) * 31 + points.contentHashCode()) * 31 + bend.contentHashCode()

        companion object {
            fun fromJson(o: JSONObject): Surface? {
                val kind = o.optInt("k", KIND_PLANE)
                val pa = o.optJSONArray("p") ?: return null
                val p = FloatArray(pa.length()) { pa.optDouble(it, Double.NaN).toFloat() }
                val b = FloatArray(3)
                o.optJSONArray("b")?.let { ba -> for (i in 0 until min(3, ba.length())) b[i] = ba.optDouble(i, 0.0).toFloat() }
                val s = Surface(kind, p, b, o.optInt("s", 2))
                return if (s.isValid()) s else null
            }

            /** The default surface of [kind], where [DEFAULT_QUAD] is. */
            fun default(kind: Int): Surface =
                when (kind) {
                    KIND_FOLD ->
                        Surface(KIND_FOLD, floatArrayOf(0.10f, 0.31f, 0.90f, 0.31f, 0.90f, 0.55f, 0.10f, 0.55f,
                            0.50f, 0.27f, 0.50f, 0.59f), FloatArray(3), 2)
                    KIND_CURVE ->
                        Surface(KIND_CURVE, floatArrayOf(0.10f, 0.36f, 0.90f, 0.36f, 0.90f, 0.52f, 0.10f, 0.52f),
                            floatArrayOf(0.35f, 0.35f, 0.25f))
                    KIND_MESH -> Surface(KIND_MESH, meshFromQuad(DEFAULT_QUAD))
                    else -> Surface(KIND_PLANE, DEFAULT_QUAD.copyOf())
                }

            /** The 3 x 3 grid that lies on quad [q] (bilinear). */
            fun meshFromQuad(q: FloatArray): FloatArray {
                val out = FloatArray(18)
                for (r in 0..2) for (c in 0..2) {
                    val u = c / 2f
                    val v = r / 2f
                    val x = (1 - v) * ((1 - u) * q[0] + u * q[2]) + v * ((1 - u) * q[6] + u * q[4])
                    val y = (1 - v) * ((1 - u) * q[1] + u * q[3]) + v * ((1 - u) * q[7] + u * q[5])
                    out[(r * 3 + c) * 2] = x
                    out[(r * 3 + c) * 2 + 1] = y
                }
                return out
            }
        }
    }

    // ---- the saved surfaces -------------------------------------------------------------------

    /** The surface saved for [key] in [json] ([KEY_QUADS]), else the default of [defaultKind]. */
    fun surfaceFor(json: String?, key: String?, defaultKind: Int = KIND_PLANE): Surface {
        if (json.isNullOrEmpty() || key.isNullOrEmpty()) return Surface.default(defaultKind)
        return try {
            parseSurface(JSONObject(json).opt(key)) ?: Surface.default(defaultKind)
        } catch (e: org.json.JSONException) {
            Surface.default(defaultKind)
        }
    }

    /** A saved value: a plane's "x,y,..." string (the original format) or a surface's object. */
    fun parseSurface(v: Any?): Surface? =
        when (v) {
            is JSONObject -> Surface.fromJson(v)
            is String ->
                if (v.startsWith("{")) {
                    try { Surface.fromJson(JSONObject(v)) } catch (e: org.json.JSONException) { null }
                } else parseQuad(v)?.let { Surface(KIND_PLANE, it) }
            else -> null
        }

    /** [json] with [surface] saved for [key] (null: the key's removed), the oldest pruned. */
    fun withSurface(json: String?, key: String, surface: Surface?): String {
        val o =
            try {
                if (json.isNullOrEmpty()) JSONObject() else JSONObject(json)
            } catch (e: org.json.JSONException) {
                JSONObject()
            }
        o.remove(key)
        if (surface != null && surface.isValid()) {
            // A plane keeps the original string form (older builds read it).
            if (surface.kind == KIND_PLANE) o.put(key, formatQuad(surface.points)) else o.put(key, surface.toJson())
        }
        // JSONObject keeps insertion order here (a LinkedHashMap): the first keys are the oldest.
        val keys = o.keys().asSequence().toMutableList()
        while (keys.size > MAX_SAVED) o.remove(keys.removeAt(0))
        return o.toString()
    }

    /** The plane saved for [key], else [DEFAULT_QUAD]: the saved surface's corners. */
    fun quadFor(json: String?, key: String?): FloatArray = surfaceFor(json, key).corners()

    /** [json] with the plane [quad] saved for [key] (null: removed). */
    fun withQuad(json: String?, key: String, quad: FloatArray?): String =
        withSurface(json, key, quad?.let { Surface(KIND_PLANE, it) })

    fun parseQuad(s: String?): FloatArray? {
        val p = s?.split(",") ?: return null
        if (p.size != 8) return null
        val q = FloatArray(8)
        for (i in 0 until 8) q[i] = p[i].trim().toFloatOrNull() ?: return null
        return if (isValid(q)) q else null
    }

    fun formatQuad(q: FloatArray): String =
        q.joinToString(",") { String.format(Locale.ROOT, "%.4f", it) }

    /** Inside the allowed range, every edge long enough, and convex (a wall has no fold). */
    fun isValid(q: FloatArray): Boolean = q.size == 8 && isValidQuad(q)

    private fun isValidQuad(q: FloatArray): Boolean {
        if (q.size != 8) return false
        for (v in q) if (v.isNaN() || v < QUAD_MIN || v > QUAD_MAX) return false
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val dx = q[j * 2] - q[i * 2]
            val dy = (q[j * 2 + 1] - q[i * 2 + 1]) * ASPECT_Y
            if (sqrt(dx * dx + dy * dy) < MIN_EDGE) return false
        }
        var sign = 0f
        for (i in 0 until 4) {
            val a = i * 2
            val b = ((i + 1) % 4) * 2
            val c = ((i + 2) % 4) * 2
            val cross = (q[b] - q[a]) * (q[c + 1] - q[b + 1]) - (q[b + 1] - q[a + 1]) * (q[c] - q[b])
            if (abs(cross) < 1e-6f) return false
            if (sign == 0f) sign = cross else if (sign * cross < 0f) return false
        }
        return true
    }

    /**
     * A flat rectangle where [q] is: the clock's natural proportions ([blockAspect] = width /
     * height of the digit block) around the quad's centre, as tall as the quad's mean height.
     */
    fun flatQuad(q: FloatArray, blockAspect: Float, screenW: Int, screenH: Int): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val hPx = ((q[7] - q[1]) + (q[5] - q[3])) / 2f * screenH
        val wPx = hPx * blockAspect
        val hw = wPx / screenW / 2f
        val hh = hPx / screenH / 2f
        return floatArrayOf(cx - hw, cy - hh, cx + hw, cy - hh, cx + hw, cy + hh, cx - hw, cy + hh)
    }

    /**
     * [q] snapped to an upright rectangle when it nearly is one (every corner within [tol]
     * fractions of its neighbours' line), else null. The editor snaps while a corner is dragged.
     */
    fun snapRectangle(q: FloatArray, tol: Float): FloatArray? {
        val left = (q[0] + q[6]) / 2f
        val right = (q[2] + q[4]) / 2f
        val top = (q[1] + q[3]) / 2f
        val bottom = (q[5] + q[7]) / 2f
        val near =
            abs(q[0] - left) < tol && abs(q[6] - left) < tol &&
                abs(q[2] - right) < tol && abs(q[4] - right) < tol &&
                abs(q[1] - top) < tol * ASPECT_Y && abs(q[3] - top) < tol * ASPECT_Y &&
                abs(q[5] - bottom) < tol * ASPECT_Y && abs(q[7] - bottom) < tol * ASPECT_Y
        return if (near) floatArrayOf(left, top, right, top, right, bottom, left, bottom) else null
    }

    /** Whether ([fx], [fy]) (fractions) is inside the convex quad [q]. */
    fun contains(q: FloatArray, fx: Float, fy: Float): Boolean {
        var sign = 0f
        for (i in 0 until 4) {
            val a = i * 2
            val b = ((i + 1) % 4) * 2
            val cross = (q[b] - q[a]) * (fy - q[a + 1]) - (q[b + 1] - q[a + 1]) * (fx - q[a])
            if (sign == 0f) sign = cross else if (sign * cross < 0f) return false
        }
        return true
    }

    /** [q] in pixels of a [w] x [h] screen, into [out] (8 floats). */
    fun toPixels(q: FloatArray, w: Int, h: Int, out: FloatArray = FloatArray(8)): FloatArray {
        for (i in 0 until 4) {
            out[i * 2] = q[i * 2] * w
            out[i * 2 + 1] = q[i * 2 + 1] * h
        }
        return out
    }

    // ---- the one warp -------------------------------------------------------------------------

    /**
     * Maps the flat digit block's (u, v) (0..1 across and down the block; a little past it for
     * shifted passes) onto a [surface] on a [w] x [h] screen, px. One per surface and screen; the
     * renderer and the editor's handles use the same one.
     */
    class Mapper(val surface: Surface, val w: Int, val h: Int) {
        private val px = FloatArray(surface.points.size) { i -> surface.points[i] * (if (i % 2 == 0) w else h) }
        private val faces = arrayOf(Matrix(), Matrix())
        private val tmp = FloatArray(2)

        /** Fold: the share of the block's width on the first face (set by the digit layout). */
        var splitU = 0.5f

        init {
            when (surface.kind) {
                KIND_PLANE -> faces[0].setPolyToPoly(UNIT, 0, px, 0, 4)
                KIND_FOLD -> {
                    faces[0].setPolyToPoly(UNIT, 0, toPixels(surface.faceQuad(0), w, h), 0, 4)
                    faces[1].setPolyToPoly(UNIT, 0, toPixels(surface.faceQuad(1), w, h), 0, 4)
                }
            }
        }

        /** The plane's exact map of the unit square, the fast path; null for other kinds. */
        val planeMatrix: Matrix?
            get() = if (surface.kind == KIND_PLANE) faces[0] else null

        /** Which face (0 or 1) [u] lies on (fold), else 0. */
        fun faceOf(u: Float): Int = if (surface.kind == KIND_FOLD && u >= splitU) 1 else 0

        fun map(u: Float, v: Float, out: FloatArray, o: Int = 0) {
            when (surface.kind) {
                KIND_PLANE -> {
                    tmp[0] = u; tmp[1] = v
                    faces[0].mapPoints(tmp)
                    out[o] = tmp[0]; out[o + 1] = tmp[1]
                }
                KIND_FOLD -> {
                    val f = faceOf(u)
                    tmp[0] = if (f == 0) u / max(splitU, 1e-3f) else (u - splitU) / max(1f - splitU, 1e-3f)
                    tmp[1] = v
                    faces[f].mapPoints(tmp)
                    out[o] = tmp[0]; out[o + 1] = tmp[1]
                }
                KIND_CURVE -> mapCurve(u, v, out, o)
                KIND_MESH -> mapMesh(u, v, out, o)
            }
        }

        private fun mapCurve(u0: Float, v: Float, out: FloatArray, o: Int) {
            val u = cylinder(u0, surface.bend[2])
            // Top edge p0 -> p1, bottom edge p3 -> p2, each a quadratic Bézier bowed outward.
            val meanH = (hypot(px[6] - px[0], px[7] - px[1]) + hypot(px[4] - px[2], px[5] - px[3])) / 2f
            val tx = bez(px[0], ctrl(0, 2, 6, 4, surface.bend[0], meanH, 0), px[2], u)
            val ty = bez(px[1], ctrl(0, 2, 6, 4, surface.bend[0], meanH, 1), px[3], u)
            val bx = bez(px[6], ctrl(6, 4, 0, 2, surface.bend[1], meanH, 0), px[4], u)
            val by = bez(px[7], ctrl(6, 4, 0, 2, surface.bend[1], meanH, 1), px[5], u)
            out[o] = tx + (bx - tx) * v
            out[o + 1] = ty + (by - ty) * v
        }

        /**
         * The control point of the edge a -> b (px indices), bowed by [bow] x [meanH] away from the
         * opposite edge c -> d; component [k].
         */
        private fun ctrl(a: Int, b: Int, c: Int, d: Int, bow: Float, meanH: Float, k: Int): Float {
            val mx = (px[a] + px[b]) / 2f
            val my = (px[a + 1] + px[b + 1]) / 2f
            val ox = (px[c] + px[d]) / 2f
            val oy = (px[c + 1] + px[d + 1]) / 2f
            // Perpendicular to the edge, pointing away from the opposite edge.
            val ex = px[b] - px[a]
            val ey = px[b + 1] - px[a + 1]
            val len = max(1f, hypot(ex, ey))
            var nx = ey / len
            var ny = -ex / len
            if (nx * (mx - ox) + ny * (my - oy) < 0f) { nx = -nx; ny = -ny }
            // The curve reaches half the control's offset: a control at 2 x bow bows by bow.
            return (if (k == 0) mx + nx * bow * meanH * 2f else my + ny * bow * meanH * 2f)
        }

        private fun bez(p0: Float, c: Float, p1: Float, t: Float): Float {
            val s = 1f - t
            return s * s * p0 + 2f * s * t * c + t * t * p1
        }

        /** A cylinder about the vertical axis: the sides foreshorten ([cyl] > 0) or stretch (< 0). */
        private fun cylinder(u: Float, cyl: Float): Float {
            if (abs(cyl) < 0.01f) return u
            val theta = abs(cyl).coerceAtMost(1f) * 2.9f
            val x = (u - 0.5f) * 2f
            return if (cyl > 0f) 0.5f + sin(x * theta / 2f) / (2f * sin(theta / 2f))
            else 0.5f + asin((x * sin(theta / 2f)).coerceIn(-1f, 1f)) / theta
        }

        private fun mapMesh(u: Float, v: Float, out: FloatArray, o: Int) {
            // Quadratic Lagrange through the 3 x 3 points: the handles lie on the surface.
            val lu0 = (2f * u - 1f) * (u - 1f)
            val lu1 = 4f * u * (1f - u)
            val lu2 = u * (2f * u - 1f)
            val lv0 = (2f * v - 1f) * (v - 1f)
            val lv1 = 4f * v * (1f - v)
            val lv2 = v * (2f * v - 1f)
            var x = 0f
            var y = 0f
            for (r in 0..2) {
                val lv = when (r) { 0 -> lv0; 1 -> lv1; else -> lv2 }
                for (c in 0..2) {
                    val lu = when (c) { 0 -> lu0; 1 -> lu1; else -> lu2 }
                    val i = (r * 3 + c) * 2
                    x += lu * lv * px[i]
                    y += lu * lv * px[i + 1]
                }
            }
            out[o] = x
            out[o + 1] = y
        }

        /** The surface's boundary, px (for the editor's outline and the AOD). */
        fun outline(): Path {
            val p = Path()
            val pt = FloatArray(2)
            val n = if (surface.kind == KIND_PLANE || surface.kind == KIND_FOLD) 1 else 16
            fun edge(from: Pair<Float, Float>, to: Pair<Float, Float>, first: Boolean) {
                for (i in (if (first) 0 else 1)..n) {
                    val t = i / n.toFloat()
                    map(from.first + (to.first - from.first) * t, from.second + (to.second - from.second) * t, pt)
                    if (first && i == 0) p.moveTo(pt[0], pt[1]) else p.lineTo(pt[0], pt[1])
                }
            }
            edge(0f to 0f, 1f to 0f, true)
            edge(1f to 0f, 1f to 1f, false)
            edge(1f to 1f, 0f to 1f, false)
            edge(0f to 1f, 0f to 0f, false)
            p.close()
            return p
        }

        /** Fold: the fold edge, px, as a path (the editor draws it; the AOD too). */
        fun foldEdge(): Path? {
            if (surface.kind != KIND_FOLD) return null
            return Path().apply {
                moveTo(px[8], px[9])
                lineTo(px[10], px[11])
            }
        }

        /** The face [face] of a fold (px polygon), else the whole outline. */
        fun facePath(face: Int): Path {
            if (surface.kind != KIND_FOLD) return outline()
            val q = toPixels(surface.faceQuad(face), w, h)
            return Path().apply {
                moveTo(q[0], q[1]); lineTo(q[2], q[3]); lineTo(q[4], q[5]); lineTo(q[6], q[7]); close()
            }
        }

        companion object {
            private val UNIT = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        }
    }

    // ---- the light ----------------------------------------------------------------------------

    /**
     * What the styles know of the light: Living Sky's exposure of the land (1 at noon), how far
     * into the night it is (0 day .. 1 night), where the sun is ([sunX], [sunY] fractions of the
     * screen, as Living Sky places its glow) and how low it stands ([lowSun] 0 high .. 1 at the
     * horizon). Without Living Sky, [forClock] guesses from the hour.
     */
    class Light(
        val exposure: Float = 1f,
        val night: Float = 0f,
        val sunX: Float = 0.3f,
        val sunY: Float = 0.15f,
        val lowSun: Float = 0.3f,
        /**
         * IchthysOS: Living Sky's light on the land (linear RGB multiplier, LivingSky.landLight):
         * the wall the digits lie on is sampled from the raw picture, and this is what the grade
         * does to it, so the styles' wall colours (the engraved face, the plate's tint, the
         * paint's dimming) match the graded picture around them. Null: no grade (the hour's
         * [exposure] stands in for it).
         */
        val land: FloatArray? = null,
    ) {
        /** What [paintOnWall]'s light scale is: the grade is already in the wall, else the hour. */
        val wallScale: Float
            get() = if (land != null) 1f else exposure

        /** Unit direction from the digits towards the light, on the screen (y down). */
        fun dir(): FloatArray {
            val dx = sunX - 0.5f
            val dy = -(0.9f - sunY) - 0.15f
            val len = max(1e-3f, hypot(dx, dy))
            return floatArrayOf(dx / len, dy / len)
        }

        companion object {
            val DAY = Light()

            /** A guess from the clock: dark 20:00..05:59, low sun around 07:00 and 18:30. */
            fun forClock(hour24: Int, minute: Int): Light {
                val t = hour24 + minute / 60f
                val night = when {
                    t >= 21f || t < 5.5f -> 1f
                    t < 7f -> 1f - (t - 5.5f) / 1.5f
                    t >= 19.5f -> (t - 19.5f) / 1.5f
                    else -> 0f
                }
                val day = ((t - 6.5f) / 12.5f).coerceIn(0f, 1f) // 0 at sunrise, 1 at sunset
                val lowSun = (1f - sin(day * Math.PI.toFloat())).coerceIn(0f, 1f)
                return Light(1f - 0.7f * night, night, 0.15f + 0.7f * day, 0.1f + 0.5f * lowSun, lowSun)
            }
        }
    }

    // ---- drawing ------------------------------------------------------------------------------

    /**
     * Draws the projected clock on a [w] x [h] screen canvas. One per surface; main thread. The
     * digit block is laid out once per minute and font; the warped outlines once per minute,
     * surface and shift; the wall's light is sampled once per surface and picture.
     */
    class Renderer {
        private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wallPaint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        private val screenPaint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            }
        private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val multiplyPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val block = Path()
        private val blockBounds = RectF()
        private var blockFor = Int.MIN_VALUE
        private var blockFont = Int.MIN_VALUE
        private var blockRow = false
        private var blockSplit = -1
        /** Fold: the block's u where the digits split between the faces. */
        private var blockSplitU = 0.5f
        private val matrix = Matrix()
        private val shiftMatrix = Matrix()
        private val src = FloatArray(8)
        private val wallRect = Rect()
        private val wallDst = RectF()
        private val clipBounds = RectF()
        private val colorMatrix = ColorMatrix()
        private val measure = PathMeasure()
        private val pos = FloatArray(2)
        private val uv = FloatArray(2)
        private var mapper: Mapper? = null
        private var mapperFor: Surface? = null
        private var mapperW = 0
        private var mapperH = 0
        /** Warped outlines by shift, for this block and mapper. */
        private val warps = HashMap<Long, Path>()
        private var warpsFor = 0

        /** The wall's light under each face: mean sRGB colour, 0..1 per channel, as lit now. */
        private val light = arrayOf(floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
        /** IchthysOS: the same as sampled from the raw picture, before the grade ([gradeWall]). */
        private val sampled = arrayOf(floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
        private var wallFor: Bitmap? = null
        private var wallSurface = 0
        private val region = Region()
        private val regionClip = Region()
        private val regionPath = Path()
        private val regionMatrix = Matrix()

        /** The digit block's width over its height, for [flatQuad]. */
        var blockAspect = 0.77f
            private set

        /** The last drawn outlines' bounds on the screen (px), for whoever draws over them. */
        val drawnBounds = RectF()

        /**
         * The time [hour]:[minute] (hour as shown, 12 or 24 h) in [font] (DepthClockView.FONT_*)
         * on [surface] of a [w] x [h] canvas, in [style], in [colour] (the clock colour) at [alpha].
         * [wall] is the picture the surface lies on (any size, stretched over the screen; the
         * digits take its shading), [sample] a small software copy of it for the light (null: a
         * neutral grey wall); [light] the moment's light. [doze] 0 awake .. 1 dozing: the fill fades
         * to the outline.
         */
        fun draw(
            canvas: Canvas,
            w: Int,
            h: Int,
            surface: Surface,
            style: Int,
            hour: Int,
            minute: Int,
            font: Int,
            colour: Int,
            alpha: Float,
            wall: Bitmap?,
            sample: Bitmap?,
            light: Light,
            doze: Float,
            density: Float,
        ) {
            if (w <= 0 || h <= 0 || !surface.isValid()) return
            val fold = surface.kind == KIND_FOLD
            layoutBlock(hour, minute, font, fold, surface.split)
            if (block.isEmpty) return
            val m = mapperFor(surface, w, h)
            m.splitU = blockSplitU
            val warped = warp(0f, 0f) ?: return
            warped.computeBounds(clipBounds, true)
            // Nothing is drawn past the canvas: the surface may hang a little off the screen.
            if (!clipBounds.intersect(-h.toFloat(), -h.toFloat(), (w + h).toFloat(), (h + h).toFloat())) return
            drawnBounds.set(clipBounds)

            val awake = (1f - doze.coerceIn(0f, 1f)) * alpha.coerceIn(0f, 1f)
            val dozing = doze.coerceIn(0f, 1f) * alpha.coerceIn(0f, 1f)
            // The digits' height on the screen sizes blurs and shadow offsets.
            val nearH = clipBounds.height().coerceAtLeast(1f)
            val shownStyle = if (style == STYLE_BEAM && light.night < 0.25f) STYLE_PAINTED else style

            if (awake > 0.003f) {
                sampleWall(sample, m)
                gradeWall(light)
                when (shownStyle) {
                    STYLE_LIGHT -> drawLight(canvas, warped, colour, awake, nearH, light, wall, w, h, m)
                    STYLE_ENGRAVED -> drawEngraved(canvas, warped, awake, nearH, light, wall, w, h, m)
                    STYLE_SIGNAGE -> drawSignage(canvas, warped, colour, awake, nearH, light, wall, w, h, m)
                    STYLE_BEAM -> drawBeam(canvas, warped, colour, awake, nearH, light, w, h, m)
                    STYLE_SHADOW -> drawShadow(canvas, awake, nearH, light, m)
                    else -> drawPainted(canvas, warped, colour, awake, nearH, light, wall, w, h, m)
                }
            }
            if (dozing > 0.003f) {
                // AOD: the digits in outline on their surface (every style), the fold's edge too.
                strokePaint.strokeWidth = 1.5f * density
                strokePaint.color = withAlpha(colour, 0.6f * dozing)
                canvas.drawPath(warped, strokePaint)
                if (shownStyle == STYLE_SIGNAGE) {
                    strokePaint.strokeWidth = 1f * density
                    strokePaint.color = withAlpha(colour, 0.25f * dozing)
                    canvas.drawPath(platePath(m), strokePaint)
                }
                m.foldEdge()?.let {
                    strokePaint.strokeWidth = 1f * density
                    strokePaint.color = withAlpha(colour, 0.22f * dozing)
                    canvas.drawPath(it, strokePaint)
                }
            }
        }

        // ---- styles ----

        /** P1: paint on the wall: contact shadow in the plane, spill, paint, the wall's grain. */
        private fun drawPainted(canvas: Canvas, warped: Path, colour: Int, awake: Float, nearH: Float,
                light: Light, wall: Bitmap?, w: Int, h: Int, m: Mapper) {
            val paint = paintOnWall(colour, light.wallScale, 0)
            val wallLuma = luma(0)
            // The contact shadow: the digits moved down and a little right IN THE SURFACE'S PLANE
            // (block space, then warped), blurred by a share of the height; darker on a dark wall.
            val shadow = warp(0.012f, 0.028f)
            if (shadow != null) {
                blur(softPaint, nearH * 0.028f, BlurMaskFilter.Blur.NORMAL)
                softPaint.color = Color.argb(((0.30f + 0.2f * (1f - wallLuma)) * 255 * awake).toInt(), 8, 10, 14)
                canvas.drawPath(shadow, softPaint)
            }
            // The light's spill off the surface: the paint's colour, wide and faint.
            blur(softPaint, nearH * 0.06f, BlurMaskFilter.Blur.OUTER)
            softPaint.color = withAlpha(paint, 0.14f * awake)
            canvas.drawPath(warped, softPaint)
            // The paint itself ...
            fillPaint.color = withAlpha(paint, 0.94f * awake)
            canvas.drawPath(warped, fillPaint)
            // ... taking the wall's shading and grain.
            wallTexture(canvas, warped, wall, w, h, awake, m, screenOnDark = false)
        }

        /** P2: a light strip: glowing digits, the glow on the wall; a faint tube by day. */
        private fun drawLight(canvas: Canvas, warped: Path, colour: Int, awake: Float, nearH: Float,
                light: Light, wall: Bitmap?, w: Int, h: Int, m: Mapper) {
            val n = light.night.coerceIn(0f, 1f)
            val glow = saturate(colour, 1.25f)
            val core = blend(colour, Color.WHITE, 0.55f)
            // Wide glow on the wall, then a tight one at the tube.
            blur(softPaint, nearH * 0.14f, BlurMaskFilter.Blur.NORMAL)
            softPaint.color = withAlpha(glow, (0.06f + 0.34f * n) * awake)
            canvas.drawPath(warped, softPaint)
            blur(softPaint, nearH * 0.045f, BlurMaskFilter.Blur.NORMAL)
            softPaint.color = withAlpha(glow, (0.12f + 0.42f * n) * awake)
            canvas.drawPath(warped, softPaint)
            // The tube: by day a pale unlit tube, at night the lit core.
            val tube = blend(core, Color.rgb(205, 205, 210), 0.45f * (1f - n))
            fillPaint.color = withAlpha(tube, (0.52f + 0.46f * n) * awake)
            canvas.drawPath(warped, fillPaint)
            // By day it still sits in the wall's light (lightly).
            if (n < 0.6f) wallTexture(canvas, warped, wall, w, h, awake * (0.6f - n), m, screenOnDark = false)
        }

        /** P3: engraved: darker inner face, light rim on the sun's side, shadow opposite. */
        private fun drawEngraved(canvas: Canvas, warped: Path, awake: Float, nearH: Float, light: Light,
                wall: Bitmap?, w: Int, h: Int, m: Mapper) {
            val wallCol = wallColour(0)
            val inner = scaleLight(wallCol, 0.58f)
            val rim = scaleLight(wallCol, 1.45f)
            val dark = scaleLight(wallCol, 0.35f)
            val d = light.dir()
            // The cut's depth, in the block: a sliver.
            val du = d[0] * 0.011f
            val dv = d[1] * 0.011f
            // A soft shadow inside, cast from the lip on the sun's side (towards the light).
            val shade = warp(-du * 2.2f, -dv * 2.2f)
            if (shade != null) {
                blur(softPaint, nearH * 0.02f, BlurMaskFilter.Blur.NORMAL)
                softPaint.color = withAlpha(dark, 0.55f * awake)
                val save = canvas.save()
                canvas.clipPath(warped)
                canvas.drawPath(shade, softPaint)
                canvas.restoreToCount(save)
            }
            // Light rim towards the sun, shadow rim away from it (each peeks past the face).
            warp(du, dv)?.let { p ->
                fillPaint.color = withAlpha(rim, 0.9f * awake)
                canvas.drawPath(p, fillPaint)
            }
            warp(-du, -dv)?.let { p ->
                fillPaint.color = withAlpha(dark, 0.7f * awake)
                canvas.drawPath(p, fillPaint)
            }
            // The inner face.
            fillPaint.color = withAlpha(inner, 0.96f * awake)
            canvas.drawPath(warped, fillPaint)
            wallTexture(canvas, warped, wall, w, h, awake, m, screenOnDark = false)
        }

        /** P4: a matte plate in the plane under the digits, a small standoff shadow. */
        private fun drawSignage(canvas: Canvas, warped: Path, colour: Int, awake: Float, nearH: Float,
                light: Light, wall: Bitmap?, w: Int, h: Int, m: Mapper) {
            val plate = platePath(m)
            val wallCol = wallColour(0)
            val inkLuma = lumaOf(colour)
            // The plate reads against the digits: dark under light digits, cream under dark ones,
            // tinted a little by the wall.
            val plateCol =
                if (inkLuma > 0.45f) blend(Color.rgb(22, 24, 29), wallCol, 0.18f)
                else blend(Color.rgb(236, 230, 220), wallCol, 0.12f)
            // Standoff shadow: the plate moved down in the plane, soft.
            warpRect(platePad(), 0.004f, 0.022f)?.let { s ->
                blur(softPaint, nearH * 0.035f, BlurMaskFilter.Blur.NORMAL)
                softPaint.color = Color.argb((0.38f * 255 * awake).toInt(), 6, 8, 12)
                canvas.drawPath(s, softPaint)
            }
            fillPaint.color = withAlpha(plateCol, 0.96f * awake)
            canvas.drawPath(plate, fillPaint)
            // The wall's grain in the matte, lightly.
            wallTexture(canvas, plate, wall, w, h, awake * 0.35f, m, screenOnDark = false)
            // The digits, crisp and matte.
            fillPaint.color = withAlpha(paintOnWall(colour, light.wallScale, 0), 0.97f * awake)
            canvas.drawPath(warped, fillPaint)
        }

        /** P5: a projector's beam: soft bright digits, a faint cone from a corner. */
        private fun drawBeam(canvas: Canvas, warped: Path, colour: Int, awake: Float, nearH: Float,
                light: Light, w: Int, h: Int, m: Mapper) {
            val n = light.night.coerceIn(0f, 1f)
            val bright = blend(colour, Color.WHITE, 0.4f)
            // The source: the top corner on the surface's far side (a projector across the room).
            val centreX = m.surface.centre()[0]
            val sx = if (centreX >= 0.5f) w * 0.03f else w * 0.97f
            val sy = h * 0.05f
            val b = clipBounds
            // The cone: from the source to the two bound corners widest apart as seen from it.
            val corners = arrayOf(
                floatArrayOf(b.left, b.top), floatArrayOf(b.right, b.top),
                floatArrayOf(b.right, b.bottom), floatArrayOf(b.left, b.bottom))
            var aMin = Float.MAX_VALUE
            var aMax = -Float.MAX_VALUE
            var cMin = corners[0]
            var cMax = corners[0]
            for (c in corners) {
                val a = Math.atan2((c[1] - sy).toDouble(), (c[0] - sx).toDouble()).toFloat()
                if (a < aMin) { aMin = a; cMin = c }
                if (a > aMax) { aMax = a; cMax = c }
            }
            val cone = Path().apply {
                moveTo(sx, sy)
                lineTo(cMin[0], cMin[1])
                lineTo(cMax[0], cMax[1])
                close()
            }
            val conePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            conePaint.shader = LinearGradient(sx, sy, b.centerX(), b.centerY(),
                withAlpha(bright, 0.0f), withAlpha(bright, 0.11f * n * awake), Shader.TileMode.CLAMP)
            canvas.drawPath(cone, conePaint)
            // The halo on the wall around the digits.
            val halo = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = max(b.width(), b.height()) * 0.8f
            halo.shader = RadialGradient(b.centerX(), b.centerY(), r,
                withAlpha(bright, 0.12f * n * awake), withAlpha(bright, 0f), Shader.TileMode.CLAMP)
            canvas.drawRect(b.centerX() - r, b.centerY() - r, b.centerX() + r, b.centerY() + r, halo)
            // The digits: soft-edged, bright.
            blur(softPaint, nearH * 0.05f, BlurMaskFilter.Blur.NORMAL)
            softPaint.color = withAlpha(bright, 0.5f * awake)
            canvas.drawPath(warped, softPaint)
            blur(softPaint, nearH * 0.012f, BlurMaskFilter.Blur.NORMAL)
            softPaint.color = withAlpha(bright, 0.92f * awake)
            canvas.drawPath(warped, softPaint)
        }

        /** P6: the digits are a shadow: long and soft at dusk, short and hard at noon, moon-blue. */
        private fun drawShadow(canvas: Canvas, awake: Float, nearH: Float, light: Light, m: Mapper) {
            val low = light.lowSun.coerceIn(0f, 1f)
            val n = light.night.coerceIn(0f, 1f)
            // Cast from upright letters: taller the lower the sun, leaning away from it.
            val stretch = 1f + 0.7f * low
            val lean = (0.5f - light.sunX) * 0.9f * low
            val shadow = warpSheared(stretch, lean) ?: return
            val col = blend(Color.rgb(10, 12, 18), Color.rgb(58, 78, 132), n)
            blur(softPaint, nearH * (0.012f + 0.07f * low), BlurMaskFilter.Blur.NORMAL)
            softPaint.color = withAlpha(col, (0.62f - 0.22f * low) * awake)
            if (n < 0.5f) {
                // By day a shadow darkens the wall: multiplied in.
                multiplyPaint.maskFilter = softPaint.maskFilter
                multiplyPaint.color = softPaint.color
                canvas.drawPath(shadow, multiplyPaint)
            } else {
                canvas.drawPath(shadow, softPaint)
            }
            // A faint penumbra for the soft, low sun.
            if (low > 0.3f) {
                blur(softPaint, nearH * 0.16f * low, BlurMaskFilter.Blur.NORMAL)
                softPaint.color = withAlpha(col, 0.14f * low * awake)
                canvas.drawPath(shadow, softPaint)
            }
        }

        // ---- shared passes ----

        /**
         * The wall's shading and grain multiplied into [area]: the picture's luma under it,
         * normalised about its mean (grey = 0.5 + 0.5 * luma / mean: the wall's texture as +-50 %
         * about 1, never brighter than the paint). On a fold each face takes its own mean; a shaded
         * face (luma under 0.3) is screened instead when [screenOnDark], so paint in shadow still
         * reads. A hardware bitmap draws through a colour filter and a blend on a hardware canvas.
         */
        private fun wallTexture(canvas: Canvas, area: Path, wall: Bitmap?, w: Int, h: Int, alpha: Float,
                m: Mapper, screenOnDark: Boolean) {
            if (wall == null || wall.isRecycled || alpha <= 0.003f) return
            val faces = if (m.surface.kind == KIND_FOLD) 2 else 1
            wallRect.set(0, 0, wall.width, wall.height)
            wallDst.set(0f, 0f, w.toFloat(), h.toFloat())
            for (f in 0 until faces) {
                val mean = max(luma(f), 0.04f)
                val k = 0.5f / mean
                colorMatrix.set(floatArrayOf(
                    0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, 127.5f,
                    0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, 127.5f,
                    0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, 127.5f,
                    0f, 0f, 0f, 1f, 0f))
                val p = if (faces == 2 && mean < 0.3f) screenPaint else wallPaint
                p.colorFilter = ColorMatrixColorFilter(colorMatrix)
                p.alpha = (255 * alpha * (if (p === screenPaint) 0.6f else 1f)).toInt()
                val save = canvas.save()
                canvas.clipPath(area)
                if (faces == 2) canvas.clipPath(m.facePath(f))
                canvas.drawBitmap(wall, wallRect, wallDst, p)
                canvas.restoreToCount(save)
            }
        }

        private fun blur(p: Paint, radius: Float, kind: BlurMaskFilter.Blur) {
            p.maskFilter = BlurMaskFilter(max(1f, radius), kind)
        }

        /** The plate's padding around the block, in block (u, v) units: left, top, right, bottom. */
        private fun platePad(): RectF = RectF(-0.10f, -0.09f, 1.10f, 1.09f)

        private fun platePath(m: Mapper): Path = warpRect(platePad(), 0f, 0f) ?: Path()

        /**
         * The clock colour as paint on this wall: tinted toward the wall's own hue (normalised: a
         * warm wall warms the paint, a dark one keeps its hue) and dimmed with the wall's light
         * ([lightScale] for the hour), so white paint on a shaded wall is a shaded white.
         */
        private fun paintOnWall(colour: Int, lightScale: Float, face: Int): Int {
            val l = light[face]
            val mx = max(l[0], max(l[1], l[2])).coerceAtLeast(0.02f)
            val lum = luma(face) * lightScale.coerceIn(0.05f, 1.5f)
            // Lit wall (luma 0.5+): the paint keeps 100 % of its brightness; a dark one: 55 %.
            val bright = 0.55f + 0.45f * (lum / 0.5f).coerceIn(0f, 1f)
            fun ch(c: Int, wallC: Float): Int {
                val tint = 0.72f + 0.28f * (wallC / mx)
                return (c * tint * bright).toInt().coerceIn(0, 255)
            }
            return Color.argb(Color.alpha(colour), ch(Color.red(colour), l[0]),
                ch(Color.green(colour), l[1]), ch(Color.blue(colour), l[2]))
        }

        private fun luma(face: Int): Float {
            val l = light[face]
            return 0.2126f * l[0] + 0.7152f * l[1] + 0.0722f * l[2]
        }

        /**
         * IchthysOS: the wall as the grade lights it: the raw sample ([sampled]) through Living
         * Sky's land path (linear: times [Light.land], desaturated toward moonlight by the night),
         * back to sRGB. Without a grade the sample as it is. Six multiplies a frame.
         */
        private fun gradeWall(moment: Light) {
            val land = moment.land
            for (f in 0 until 2) {
                val raw = sampled[f]
                val out = light[f]
                if (land == null) {
                    out[0] = raw[0]; out[1] = raw[1]; out[2] = raw[2]
                    continue
                }
                val r = raw[0] * raw[0] * land[0]
                val g = raw[1] * raw[1] * land[1]
                val b = raw[2] * raw[2] * land[2]
                val n = moment.night.coerceIn(0f, 1f) * 0.55f
                val ll = 0.2126f * r + 0.7152f * g + 0.0722f * b
                out[0] = sqrt((r + (ll * 0.72f - r) * n).coerceIn(0f, 1f))
                out[1] = sqrt((g + (ll * 0.84f - g) * n).coerceIn(0f, 1f))
                out[2] = sqrt((b + (ll * 1.3f - b) * n).coerceIn(0f, 1f))
            }
        }

        private fun wallColour(face: Int): Int {
            val l = light[face]
            return Color.rgb((l[0] * 255).toInt(), (l[1] * 255).toInt(), (l[2] * 255).toInt())
        }

        /** The mean colour of [sample] under each face (every few pixels), once per surface and picture. */
        private fun sampleWall(sample: Bitmap?, m: Mapper) {
            val hash = m.surface.hashCode() * 31 + m.w
            if (sample === wallFor && hash == wallSurface) return
            wallFor = sample
            wallSurface = hash
            val faces = if (m.surface.kind == KIND_FOLD) 2 else 1
            for (f in 0 until faces) {
                val l = sampled[f]
                if (sample == null || sample.isRecycled || sample.config == Bitmap.Config.HARDWARE) {
                    l[0] = 0.5f; l[1] = 0.5f; l[2] = 0.5f
                    continue
                }
                val sw = sample.width
                val sh = sample.height
                // The face's area at the sample's size, as a region to test pixels against.
                regionPath.set(if (faces == 2) m.facePath(f) else m.outline())
                regionMatrix.setScale(sw.toFloat() / m.w, sh.toFloat() / m.h)
                regionPath.transform(regionMatrix)
                regionClip.set(0, 0, sw, sh)
                region.setPath(regionPath, regionClip)
                val step = max(1, min(sw, sh) / 48)
                var r = 0f
                var g = 0f
                var b = 0f
                var n = 0
                var y = step / 2
                while (y < sh) {
                    var x = step / 2
                    while (x < sw) {
                        if (region.contains(x, y)) {
                            val c = sample.getPixel(x, y)
                            r += Color.red(c) / 255f
                            g += Color.green(c) / 255f
                            b += Color.blue(c) / 255f
                            n++
                        }
                        x += step
                    }
                    y += step
                }
                if (n == 0) {
                    l[0] = 0.5f; l[1] = 0.5f; l[2] = 0.5f
                } else {
                    l[0] = r / n; l[1] = g / n; l[2] = b / n
                }
            }
            if (faces == 1) sampled[1].let { it[0] = sampled[0][0]; it[1] = sampled[0][1]; it[2] = sampled[0][2] }
        }

        // ---- the warp ----

        private fun mapperFor(surface: Surface, w: Int, h: Int): Mapper {
            val cur = mapper
            if (cur != null && mapperFor == surface && mapperW == w && mapperH == h) return cur
            val m = Mapper(surface, w, h)
            mapper = m
            mapperFor = surface
            mapperW = w
            mapperH = h
            warps.clear()
            return m
        }

        /** The block's outlines on the surface, moved by ([du], [dv]) of the block (cached). */
        private fun warp(du: Float, dv: Float): Path? {
            val m = mapper ?: return null
            val key = (blockFor.toLong() shl 40) xor (blockFont.toLong() shl 32) xor
                ((du * 10000).toLong() shl 16) xor ((dv * 10000).toLong() and 0xffff)
            val stamp = blockFor * 31 + blockFont
            if (warpsFor != stamp) {
                warps.clear()
                warpsFor = stamp
            }
            warps[key]?.let { return it }
            val out = warpPath(block, du, dv, m) ?: return null
            if (warps.size > 12) warps.clear()
            warps[key] = out
            return out
        }

        /** [path] (block units) onto the surface, shifted by ([du], [dv]) of the block. */
        private fun warpPath(path: Path, du: Float, dv: Float, m: Mapper): Path? {
            val bw = blockBounds.width()
            val bh = blockBounds.height()
            if (bw <= 0f || bh <= 0f) return null
            val pm = m.planeMatrix
            if (pm != null) {
                // The plane's fast path: the exact projective map of the whole path.
                src[0] = blockBounds.left; src[1] = blockBounds.top
                src[2] = blockBounds.right; src[3] = blockBounds.top
                src[4] = blockBounds.right; src[5] = blockBounds.bottom
                src[6] = blockBounds.left; src[7] = blockBounds.bottom
                val dst = FloatArray(8)
                for (i in 0 until 4) m.map(UNIT_U[i], UNIT_V[i], dst, i * 2)
                if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null
                shiftMatrix.set(matrix)
                shiftMatrix.preTranslate(du * bw, dv * bh)
                return Path(path).also { it.transform(shiftMatrix) }
            }
            // Every other kind: each contour sampled finely and mapped point by point. The step
            // follows the digits' size on the screen (about a pixel apart).
            val heightPx = m.surface.meanHeightPx(m.w, m.h)
            val step = (bh / max(60f, heightPx * 1.1f)).coerceAtLeast(1.5f)
            val out = Path()
            out.fillType = path.fillType
            measure.setPath(path, false)
            var contours = 0
            do {
                val len = measure.length
                if (len <= 0f) continue
                var d = 0f
                var first = true
                val n = max(8, (len / step).toInt()).coerceAtMost(1400)
                val s = len / n
                for (i in 0..n) {
                    measure.getPosTan(min(d, len), pos, null)
                    uv[0] = (pos[0] - blockBounds.left) / bw + du
                    uv[1] = (pos[1] - blockBounds.top) / bh + dv
                    m.map(uv[0], uv[1], pos)
                    if (first) {
                        out.moveTo(pos[0], pos[1])
                        first = false
                    } else out.lineTo(pos[0], pos[1])
                    d += s
                }
                out.close()
                contours++
            } while (measure.nextContour() && contours < 64)
            return out
        }

        /** A rectangle in block units ([r], 0..1 is the block) onto the surface, shifted. */
        private fun warpRect(r: RectF, du: Float, dv: Float): Path? {
            val m = mapper ?: return null
            val p = Path()
            val pt = FloatArray(2)
            val n = if (m.surface.kind == KIND_PLANE) 1 else 12
            fun edge(x0: Float, y0: Float, x1: Float, y1: Float, first: Boolean) {
                for (i in (if (first) 0 else 1)..n) {
                    val t = i / n.toFloat()
                    m.map(x0 + (x1 - x0) * t + du, y0 + (y1 - y0) * t + dv, pt)
                    if (first && i == 0) p.moveTo(pt[0], pt[1]) else p.lineTo(pt[0], pt[1])
                }
            }
            edge(r.left, r.top, r.right, r.top, true)
            edge(r.right, r.top, r.right, r.bottom, false)
            edge(r.right, r.bottom, r.left, r.bottom, false)
            edge(r.left, r.bottom, r.left, r.top, false)
            p.close()
            return p
        }

        /** The block stretched down by [stretch] from its top and leaned by [lean] (a cast shadow). */
        private fun warpSheared(stretch: Float, lean: Float): Path? {
            val m = mapper ?: return null
            val sheared = Path(block)
            val sm = Matrix()
            sm.preTranslate(blockBounds.left, blockBounds.top)
            sm.preSkew(lean, 0f)
            sm.preScale(1f, stretch)
            sm.preTranslate(-blockBounds.left, -blockBounds.top)
            sheared.transform(sm)
            return warpPath(sheared, 0f, 0f, m)
        }

        /**
         * The flat digit block in [font]: hours over minutes (two rows, tabular cells, as
         * DepthClockView sets them), or on a fold one row of four digits with a gap at the
         * [split]. In its own units (text size [BLOCK_SIZE]); [blockBounds] is its box.
         */
        private fun layoutBlock(hour: Int, minute: Int, font: Int, row: Boolean, split: Int) {
            val key = hour * 100 + minute
            if (key == blockFor && font == blockFont && row == blockRow && split == blockSplit) return
            blockFor = key
            blockFont = font
            blockRow = row
            blockSplit = split
            block.reset()
            val hh = String.format(Locale.US, "%02d", hour)
            val mm = String.format(Locale.US, "%02d", minute)
            val size = BLOCK_SIZE
            val digits = hh + mm
            if (font == DepthClockView.FONT_SQUARE) {
                val cap = SquareDigits.capHeight(size)
                val adv = SquareDigits.advance(size)
                if (row) {
                    val gap = adv * ROW_GAP
                    val base = cap
                    for (i in 0 until 4) {
                        val left = i * adv + if (i >= split) gap else 0f
                        SquareDigits.textPath(digits, i, i + 1, left, base, size, block)
                    }
                    blockBounds.set(0f, 0f, adv * 4f + gap, base)
                    blockSplitU = (split * adv + gap / 2f) / blockBounds.width()
                } else {
                    val base1 = cap
                    val base2 = base1 + cap + size * SquareDigits.GAP
                    SquareDigits.textPath(hh, 0, 2, 0f, base1, size, block)
                    SquareDigits.textPath(mm, 0, 2, 0f, base2, size, block)
                    blockBounds.set(0f, 0f, adv * 2f, base2)
                    blockSplitU = 0.5f
                }
                block.fillType = Path.FillType.EVEN_ODD
            } else {
                digitPaint.typeface = DepthClockView.digitTypeface(font)
                digitPaint.textSize = size
                var cell = 0f
                for (d in 0..9) cell = max(cell, digitPaint.measureText(d.toString()))
                val bounds = Rect()
                digitPaint.getTextBounds("00", 0, 2, bounds)
                val cap = bounds.height().toFloat()
                val parts = ArrayList<Path>(4)
                if (row) {
                    val gap = cell * ROW_GAP
                    val base = cap
                    for (i in 0 until 4) {
                        val p = Path()
                        val cellLeft = i * cell + if (i >= split) gap else 0f
                        val left = cellLeft + (cell - digitPaint.measureText(digits, i, i + 1)) / 2f
                        digitPaint.getTextPath(digits, i, i + 1, left, base, p)
                        parts.add(p)
                    }
                    blockBounds.set(0f, 0f, cell * 4f + gap, base + size * 0.02f)
                    blockSplitU = (split * cell + gap / 2f) / blockBounds.width()
                } else {
                    val base1 = cap
                    val base2 = base1 + cap * 1.04f
                    for ((text, base) in arrayOf(hh to base1, mm to base2)) {
                        for (i in 0..1) {
                            val p = Path()
                            val left = i * cell + (cell - digitPaint.measureText(text, i, i + 1)) / 2f
                            digitPaint.getTextPath(text, i, i + 1, left, base, p)
                            parts.add(p)
                        }
                    }
                    blockBounds.set(0f, 0f, cell * 2f, base2 + size * 0.02f)
                    blockSplitU = 0.5f
                }
                // Overlapping contours merged (a "1" is a stem with a flag over it), so the
                // outline (AOD) has no inner lines; even-odd then, as DepthClockView does.
                block.fillType = mergeGlyphs(parts)
                for (p in parts) block.addPath(p)
            }
            blockAspect = blockBounds.width() / max(1f, blockBounds.height())
            warps.clear()
        }

        private fun mergeGlyphs(glyphs: List<Path>): Path.FillType {
            val merged = glyphs.map { mergeOverlaps(it) }
            if (merged.any { it == null }) return Path.FillType.WINDING
            glyphs.forEachIndexed { i, g -> g.set(merged[i]!!) }
            return Path.FillType.EVEN_ODD
        }

        /** As DepthClockView.mergeOverlaps: the union kept only when it covers the same pixels. */
        private fun mergeOverlaps(glyph: Path): Path? {
            if (glyph.isEmpty) return Path(glyph)
            val merged = Path()
            if (!merged.op(glyph, Path(), Path.Op.UNION)) return null
            val b = RectF()
            glyph.computeBounds(b, true)
            val clip = Region(b.left.toInt() - 2, b.top.toInt() - 2, b.right.toInt() + 2, b.bottom.toInt() + 2)
            val before = Region().apply { setPath(glyph, clip) }
            val after = Region().apply { setPath(merged, clip) }
            val area = regionArea(before)
            if (area == 0L) return null
            val diff = Region(before).apply { op(after, Region.Op.XOR) }
            return if (regionArea(diff) * 100 <= area) merged else null
        }

        private fun regionArea(region: Region): Long {
            var sum = 0L
            val it = RegionIterator(region)
            val r = Rect()
            while (it.next(r)) sum += r.width().toLong() * r.height()
            return sum
        }

        companion object {
            private val UNIT_U = floatArrayOf(0f, 1f, 1f, 0f)
            private val UNIT_V = floatArrayOf(0f, 0f, 1f, 1f)
        }
    }

    // ---- colour helpers -----------------------------------------------------------------------

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt(), Color.red(color),
            Color.green(color), Color.blue(color))

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * u).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(a), ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)),
            ch(Color.blue(a), Color.blue(b)))
    }

    private fun scaleLight(c: Int, k: Float): Int =
        Color.argb(Color.alpha(c), (Color.red(c) * k).toInt().coerceIn(0, 255),
            (Color.green(c) * k).toInt().coerceIn(0, 255), (Color.blue(c) * k).toInt().coerceIn(0, 255))

    private fun saturate(c: Int, k: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * k).coerceIn(0f, 1f)
        hsv[2] = max(hsv[2], 0.85f)
        return (Color.HSVToColor(hsv) and 0x00ffffff) or (Color.alpha(c) shl 24)
    }

    private fun lumaOf(c: Int): Float =
        (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    /** The block is laid out at this text size (its own units; the warp maps them). */
    private const val BLOCK_SIZE = 1000f
    /** On a fold the row's gap at the split, in digit cells. */
    private const val ROW_GAP = 0.45f
}
