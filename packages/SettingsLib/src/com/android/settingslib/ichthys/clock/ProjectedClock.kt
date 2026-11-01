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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * IchthysOS "Projected" clock (the concept canvas's clock on a wall): the Depth face's digits
 * painted onto a planar surface of the wallpaper (the lit face of a building, a wall, a road
 * sign) instead of standing upright over it.
 *
 * The surface is a four-corner quad in fractions of the screen ([KEY_QUADS], one per wallpaper,
 * the lock screen editor's corner handles set it; [DEFAULT_QUAD] until then). The digits are laid
 * out flat (two rows, tabular cells, the Depth face's own fonts) and sent through a projective
 * matrix ([Matrix.setPolyToPoly]) onto the quad, then blended so they read as being ON the wall:
 * - they take the wall's light: the paint colour is tinted and dimmed by the picture's colour
 *   under the quad ([Renderer.sample]), and the wall's own shading and grain is multiplied into
 *   them (the picture's luma under the digits, normalised about its mean);
 * - a soft contact shadow sits a little below and beside them along the surface (offset in the
 *   wall's own plane, so it foreshortens with it), and a faint wide spill of the paint's colour
 *   around them is the light falling off the surface;
 * - dozing, the same quad in outline only (the Depth face's AOD look).
 *
 * Drawn by SystemUI's depth layers (IchthysDepthLayers: under the subject's cut-out, so the
 * subject still stands in front and the X-ray lens still cuts through it) and the lock screen
 * editor's preview, both through [Renderer], so they match. Everything is drawn inside the given
 * canvas: no layer grows (a quad is clamped to a little past the screen, [QUAD_MIN]..[QUAD_MAX]).
 */
object ProjectedClock {
    /**
     * Settings.Secure: a JSON object, the saved quads: wallpaper key (IchthysDepth's
     * "L<lockId>S<sysId>", as its masks are keyed) to "x0,y0,x1,y1,x2,y2,x3,y3" (fractions of the
     * screen; top left, top right, bottom right, bottom left). A wallpaper without one uses
     * [DEFAULT_QUAD]. At most [MAX_SAVED] are kept (the oldest go).
     */
    const val KEY_QUADS = "ichthys_clock_quads"
    private const val MAX_SAVED = 16

    /**
     * The default surface, for the typical 1080 x 2400 "architecture corner" wallpaper (a big flat
     * building face lit from the left, lower middle to right, against a flat sky): a wall seen at
     * about 28° of yaw, its near edge at mid screen, receding to the right. The far edge is 0.73
     * of the near one (d / (d + w sin 28°) for a wall about as wide as the eye is far), the top
     * and bottom edges meet just off the right edge of the screen at 47 % of its height (the
     * horizon of a camera looking slightly down at the corner). Width 0.45 of the screen for a
     * near edge of 0.30: the Depth face's 2 x 2 block (about 1 : 1.3) foreshortened by cos 28°.
     * The clock sits in the band 28..58 % of the height: under the date line's place and above
     * the shortcuts.
     */
    val DEFAULT_QUAD = floatArrayOf(0.47f, 0.28f, 0.92f, 0.325f, 0.92f, 0.545f, 0.47f, 0.58f)

    /** How far past the screen a corner may go (fractions). */
    const val QUAD_MIN = -0.25f
    const val QUAD_MAX = 1.25f
    /** The least a quad's edge may measure (fraction of the screen's width). */
    const val MIN_EDGE = 0.08f

    // ---- the saved quads ----------------------------------------------------------------------

    /** The quad saved for [key] in [json] ([KEY_QUADS]), else [DEFAULT_QUAD]. */
    fun quadFor(json: String?, key: String?): FloatArray {
        if (json.isNullOrEmpty() || key.isNullOrEmpty()) return DEFAULT_QUAD.copyOf()
        return try {
            parseQuad(JSONObject(json).optString(key, "")) ?: DEFAULT_QUAD.copyOf()
        } catch (e: org.json.JSONException) {
            DEFAULT_QUAD.copyOf()
        }
    }

    /** [json] with [quad] saved for [key] (null: the key's quad removed), the oldest pruned. */
    fun withQuad(json: String?, key: String, quad: FloatArray?): String {
        val o =
            try {
                if (json.isNullOrEmpty()) JSONObject() else JSONObject(json)
            } catch (e: org.json.JSONException) {
                JSONObject()
            }
        o.remove(key)
        if (quad != null && isValid(quad)) o.put(key, formatQuad(quad))
        // JSONObject keeps insertion order here (a LinkedHashMap): the first keys are the oldest.
        val keys = o.keys().asSequence().toMutableList()
        while (keys.size > MAX_SAVED) o.remove(keys.removeAt(0))
        return o.toString()
    }

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
    fun isValid(q: FloatArray): Boolean {
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

    /** Fractions of the height measured against the width, for edge lengths (a 1 : 2.2 screen). */
    private const val ASPECT_Y = 2.2f

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

    // ---- drawing ------------------------------------------------------------------------------

    /**
     * Draws the projected clock on a [w] x [h] screen canvas. One per surface; main thread. The
     * digit block is laid out once per minute and font; the wall's light is sampled once per quad
     * and picture.
     */
    class Renderer {
        private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wallPaint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val spillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val block = Path()
        private val blockBounds = RectF()
        private var blockFor = Int.MIN_VALUE
        private var blockFont = Int.MIN_VALUE
        private val projected = Path()
        private val shadow = Path()
        private val spill = Path()
        private val matrix = Matrix()
        private val shadowMatrix = Matrix()
        private val src = FloatArray(8)
        private val dst = FloatArray(8)
        private val wallRect = Rect()
        private val wallDst = RectF()
        private val clipBounds = RectF()
        private val colorMatrix = ColorMatrix()
        private var blurFor = -1f
        private var spillFor = -1f

        /** The wall's light under the last quad: mean sRGB colour, 0..1 per channel. */
        private val light = floatArrayOf(0.5f, 0.5f, 0.5f)
        private var wallFor: Bitmap? = null
        private var wallQuad = 0

        /** The digit block's width over its height, for [flatQuad]. */
        var blockAspect = 0.77f
            private set

        /**
         * The time [hour]:[minute] (hour as shown, 12 or 24 h) in [font] (DepthClockView.FONT_*)
         * on [quad] (fractions) of a [w] x [h] canvas, in [colour] (the clock colour) at [alpha].
         * [wall] is the picture the quad lies on (any size, stretched over the screen; the
         * digits take its shading), [sample] a small software copy of it for the light (null: a
         * neutral grey wall); [lightScale] dims the wall's light (Living Sky's exposure at this
         * hour). [doze] 0 awake .. 1 dozing: the fill fades to the outline.
         */
        fun draw(
            canvas: Canvas,
            w: Int,
            h: Int,
            quad: FloatArray,
            hour: Int,
            minute: Int,
            font: Int,
            colour: Int,
            alpha: Float,
            wall: Bitmap?,
            sample: Bitmap?,
            lightScale: Float,
            doze: Float,
            density: Float,
        ) {
            if (w <= 0 || h <= 0 || !isValid(quad)) return
            layoutBlock(hour, minute, font)
            if (block.isEmpty) return
            // The block's corners onto the quad's: a projective map; the paths go through it
            // whole (Skia subdivides curves under perspective).
            src[0] = blockBounds.left; src[1] = blockBounds.top
            src[2] = blockBounds.right; src[3] = blockBounds.top
            src[4] = blockBounds.right; src[5] = blockBounds.bottom
            src[6] = blockBounds.left; src[7] = blockBounds.bottom
            toPixels(quad, w, h, dst)
            if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return
            projected.set(block)
            projected.transform(matrix)
            projected.computeBounds(clipBounds, true)
            // Nothing is drawn past the canvas: the quad may hang a little off the screen.
            if (!clipBounds.intersect(-h.toFloat(), -h.toFloat(), (w + h).toFloat(), (h + h).toFloat())) return

            val awake = (1f - doze.coerceIn(0f, 1f)) * alpha.coerceIn(0f, 1f)
            val dozing = doze.coerceIn(0f, 1f) * alpha.coerceIn(0f, 1f)
            // The near edge's height on the screen sizes the blur and the shadow's offset.
            val nearH = max(abs(dst[7] - dst[1]), abs(dst[5] - dst[3])).coerceAtLeast(1f)

            if (awake > 0.003f) {
                sampleWall(sample, quad, w, h)
                val paint = paintOnWall(colour, lightScale)
                // The contact shadow: the digits moved down and a little right IN THE WALL'S PLANE
                // (block space, then projected), so it foreshortens with the surface, blurred by a
                // share of the near edge's height; darker where the wall is darker.
                shadowMatrix.set(matrix)
                shadowMatrix.preTranslate(blockBounds.width() * 0.012f, blockBounds.height() * 0.028f)
                shadow.set(block)
                shadow.transform(shadowMatrix)
                val blur = nearH * 0.028f
                if (blur != blurFor) {
                    shadowPaint.maskFilter = BlurMaskFilter(max(1f, blur), BlurMaskFilter.Blur.NORMAL)
                    blurFor = blur
                }
                val wallLuma = 0.2126f * light[0] + 0.7152f * light[1] + 0.0722f * light[2]
                shadowPaint.color = Color.argb(((0.30f + 0.2f * (1f - wallLuma)) * 255 * awake).toInt(), 8, 10, 14)
                canvas.drawPath(shadow, shadowPaint)
                // The light's spill off the surface: the paint's colour, wide and faint.
                val wide = nearH * 0.06f
                if (wide != spillFor) {
                    spillPaint.maskFilter = BlurMaskFilter(max(1f, wide), BlurMaskFilter.Blur.OUTER)
                    spillFor = wide
                }
                spillPaint.color = withAlpha(paint, 0.14f * awake)
                canvas.drawPath(projected, spillPaint)
                // The paint itself ...
                fillPaint.color = withAlpha(paint, 0.94f * awake)
                canvas.drawPath(projected, fillPaint)
                // ... taking the wall's shading and grain: the picture's luma under the digits,
                // normalised about its mean (grey = 0.5 + 0.5 * luma / mean: the wall's texture
                // as +-50 % about 1, never brighter than the paint), multiplied in. A hardware
                // bitmap draws through a colour filter and a blend on a hardware canvas.
                if (wall != null && !wall.isRecycled) {
                    val mean = max(wallLuma, 0.04f)
                    val k = 0.5f / mean
                    colorMatrix.set(floatArrayOf(
                        0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, 127.5f,
                        0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, 127.5f,
                        0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, 127.5f,
                        0f, 0f, 0f, 1f, 0f))
                    wallPaint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                    wallPaint.alpha = (255 * awake).toInt()
                    wallRect.set(0, 0, wall.width, wall.height)
                    wallDst.set(0f, 0f, w.toFloat(), h.toFloat())
                    val save = canvas.save()
                    canvas.clipPath(projected)
                    canvas.drawBitmap(wall, wallRect, wallDst, wallPaint)
                    canvas.restoreToCount(save)
                }
            }
            if (dozing > 0.003f) {
                // AOD: the same quad in outline (the Depth face's dozing look).
                strokePaint.strokeWidth = 1.5f * density
                strokePaint.color = withAlpha(colour, 0.6f * dozing)
                canvas.drawPath(projected, strokePaint)
            }
        }

        /**
         * The clock colour as paint on this wall: tinted toward the wall's own hue (normalised: a
         * warm wall warms the paint, a dark one keeps its hue) and dimmed with the wall's light
         * ([lightScale] for the hour), so white paint on a shaded wall is a shaded white.
         */
        private fun paintOnWall(colour: Int, lightScale: Float): Int {
            val mx = max(light[0], max(light[1], light[2])).coerceAtLeast(0.02f)
            val luma = (0.2126f * light[0] + 0.7152f * light[1] + 0.0722f * light[2]) * lightScale.coerceIn(0.05f, 1.5f)
            // Lit wall (luma 0.5+): the paint keeps 100 % of its brightness; a dark one: 55 %.
            val bright = 0.55f + 0.45f * (luma / 0.5f).coerceIn(0f, 1f)
            fun ch(c: Int, wallC: Float): Int {
                val tint = 0.72f + 0.28f * (wallC / mx)
                return (c * tint * bright).toInt().coerceIn(0, 255)
            }
            return Color.argb(Color.alpha(colour), ch(Color.red(colour), light[0]),
                ch(Color.green(colour), light[1]), ch(Color.blue(colour), light[2]))
        }

        /** The mean colour of [sample] inside [quad] (every few pixels), once per quad and picture. */
        private fun sampleWall(sample: Bitmap?, quad: FloatArray, w: Int, h: Int) {
            val hash = quad.contentHashCode()
            if (sample === wallFor && hash == wallQuad) return
            wallFor = sample
            wallQuad = hash
            if (sample == null || sample.isRecycled || sample.config == Bitmap.Config.HARDWARE) {
                light[0] = 0.5f; light[1] = 0.5f; light[2] = 0.5f
                return
            }
            val sw = sample.width
            val sh = sample.height
            val step = max(1, min(sw, sh) / 48)
            var r = 0f
            var g = 0f
            var b = 0f
            var n = 0
            var y = step / 2
            while (y < sh) {
                var x = step / 2
                while (x < sw) {
                    if (contains(quad, (x + 0.5f) / sw, (y + 0.5f) / sh)) {
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
                light[0] = 0.5f; light[1] = 0.5f; light[2] = 0.5f
            } else {
                light[0] = r / n; light[1] = g / n; light[2] = b / n
            }
        }

        /**
         * The flat digit block: hours over minutes in [font], tabular cells (every digit centred
         * in the widest digit's advance, as DepthClockView sets them), the rows a cap height and
         * a hair apart. In its own units (text size [BLOCK_SIZE]); [blockBounds] is its box.
         */
        private fun layoutBlock(hour: Int, minute: Int, font: Int) {
            val key = hour * 100 + minute
            if (key == blockFor && font == blockFont) return
            blockFor = key
            blockFont = font
            block.reset()
            val hh = String.format(Locale.US, "%02d", hour)
            val mm = String.format(Locale.US, "%02d", minute)
            val size = BLOCK_SIZE
            if (font == DepthClockView.FONT_SQUARE) {
                val cap = SquareDigits.capHeight(size)
                val adv = SquareDigits.advance(size)
                val base1 = cap
                val base2 = base1 + cap + size * SquareDigits.GAP
                SquareDigits.textPath(hh, 0, 2, 0f, base1, size, block)
                SquareDigits.textPath(mm, 0, 2, 0f, base2, size, block)
                block.fillType = Path.FillType.EVEN_ODD
                blockBounds.set(0f, 0f, adv * 2f, base2)
            } else {
                digitPaint.typeface = DepthClockView.digitTypeface(font)
                digitPaint.textSize = size
                var cell = 0f
                for (d in 0..9) cell = max(cell, digitPaint.measureText(d.toString()))
                val bounds = Rect()
                digitPaint.getTextBounds("00", 0, 2, bounds)
                val cap = bounds.height().toFloat()
                val base1 = cap
                val base2 = base1 + cap * 1.04f
                val parts = ArrayList<Path>(4)
                for ((text, base) in arrayOf(hh to base1, mm to base2)) {
                    for (i in 0..1) {
                        val p = Path()
                        val left = i * cell + (cell - digitPaint.measureText(text, i, i + 1)) / 2f
                        digitPaint.getTextPath(text, i, i + 1, left, base, p)
                        parts.add(p)
                    }
                }
                // Overlapping contours merged (a "1" is a stem with a flag over it), so the
                // outline (AOD) has no inner lines; even-odd then, as DepthClockView does.
                block.fillType = mergeGlyphs(parts)
                for (p in parts) block.addPath(p)
                blockBounds.set(0f, 0f, cell * 2f, base2 + size * 0.02f)
            }
            blockAspect = blockBounds.width() / max(1f, blockBounds.height())
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

        private fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt(), Color.red(color),
                Color.green(color), Color.blue(color))
    }

    /** The block is laid out at this text size (its own units; the matrix maps them). */
    private const val BLOCK_SIZE = 1000f
}
