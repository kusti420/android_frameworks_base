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

package com.android.systemui.screenshot.ichthys

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One edit applied to the image. Every op is expressed relative to the image *as it looks right
 * before the op is applied* (normalized 0..1 coordinates, stroke width as a fraction of the
 * shorter side), so the same list replays identically on the sampled preview bitmap and on the
 * full-resolution original.
 */
sealed interface EditOp

/** Freehand stroke. [points] holds normalized x,y pairs. [color] is ARGB. */
class StrokeOp(
    val points: FloatArray,
    val color: Int,
    val widthFrac: Float,
    val highlighter: Boolean,
) : EditOp

/** Crop to the normalized rect. */
class CropOp(val left: Float, val top: Float, val right: Float, val bottom: Float) : EditOp

/** Rotate by 90 degrees. */
class RotateOp(val clockwise: Boolean) : EditOp

/** Mirror horizontally. */
object FlipOp : EditOp

/** Applies [EditOp]s to bitmaps with plain android.graphics (works for preview and full-res). */
object ShotRenderer {

    /** Highlighter strokes are this much wider than pen strokes. */
    const val HIGHLIGHTER_SCALE = 3f
    private const val HIGHLIGHTER_ALPHA = 0x66

    /** Returns a new mutable bitmap = [src] with all [ops] applied. [src] is left untouched. */
    fun render(src: Bitmap, ops: List<EditOp>): Bitmap {
        var cur = src.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ true)
        for (op in ops) cur = applyOne(cur, op)
        return cur
    }

    /**
     * Applies one op to the mutable [bmp]. Strokes draw in place and return [bmp]; geometry ops
     * return a new bitmap and recycle [bmp].
     */
    fun applyOne(bmp: Bitmap, op: EditOp): Bitmap {
        val w = bmp.width
        val h = bmp.height
        return when (op) {
            is StrokeOp -> {
                drawStroke(Canvas(bmp), op, w, h)
                bmp
            }
            is CropOp -> {
                val l = (op.left.coerceIn(0f, 1f) * w).roundToInt()
                val t = (op.top.coerceIn(0f, 1f) * h).roundToInt()
                val r = (op.right.coerceIn(0f, 1f) * w).roundToInt()
                val b = (op.bottom.coerceIn(0f, 1f) * h).roundToInt()
                val cw = max(1, r - l)
                val ch = max(1, b - t)
                val m = Matrix().apply { postTranslate(-l.toFloat(), -t.toFloat()) }
                transform(bmp, cw, ch, m)
            }
            is RotateOp -> {
                val m = Matrix()
                if (op.clockwise) {
                    m.postRotate(90f)
                    m.postTranslate(h.toFloat(), 0f)
                } else {
                    m.postRotate(-90f)
                    m.postTranslate(0f, w.toFloat())
                }
                transform(bmp, h, w, m)
            }
            FlipOp -> {
                val m = Matrix().apply {
                    postScale(-1f, 1f)
                    postTranslate(w.toFloat(), 0f)
                }
                transform(bmp, w, h, m)
            }
        }
    }

    /** Output (w, h) of the image after [ops], given the starting size. */
    fun sizeAfter(w0: Int, h0: Int, ops: List<EditOp>): Pair<Int, Int> {
        var w = w0
        var h = h0
        for (op in ops) {
            when (op) {
                is CropOp -> {
                    val l = (op.left.coerceIn(0f, 1f) * w).roundToInt()
                    val t = (op.top.coerceIn(0f, 1f) * h).roundToInt()
                    val r = (op.right.coerceIn(0f, 1f) * w).roundToInt()
                    val b = (op.bottom.coerceIn(0f, 1f) * h).roundToInt()
                    w = max(1, r - l)
                    h = max(1, b - t)
                }
                is RotateOp -> {
                    val tmp = w
                    w = h
                    h = tmp
                }
                else -> Unit
            }
        }
        return w to h
    }

    /** Draws [op] onto [canvas] whose drawing space is a [w]x[h] image. */
    fun drawStroke(canvas: Canvas, op: StrokeOp, w: Int, h: Int) {
        val pts = op.points
        if (pts.size < 2) return
        val paint = paintFor(op, w, h)
        if (pts.size == 2) {
            canvas.drawPoint(pts[0] * w, pts[1] * h, paint)
            return
        }
        val path = Path()
        var px = pts[0] * w
        var py = pts[1] * h
        path.moveTo(px, py)
        var i = 2
        while (i + 1 < pts.size) {
            val x = pts[i] * w
            val y = pts[i + 1] * h
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
            px = x
            py = y
            i += 2
        }
        path.lineTo(px, py)
        canvas.drawPath(path, paint)
    }

    private fun paintFor(op: StrokeOp, w: Int, h: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = if (op.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
            color = op.color
            val base = op.widthFrac * min(w, h)
            strokeWidth = max(1f, if (op.highlighter) base * HIGHLIGHTER_SCALE else base)
            if (op.highlighter) alpha = HIGHLIGHTER_ALPHA
        }

    private fun transform(src: Bitmap, outW: Int, outH: Int, m: Matrix): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }
}
