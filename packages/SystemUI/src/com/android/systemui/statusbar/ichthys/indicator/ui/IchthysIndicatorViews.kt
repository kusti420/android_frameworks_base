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

package com.android.systemui.statusbar.ichthys.indicator.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The notification dot: a filled circle the size of the privacy dot, centred in a view that is
 * [slack] pixels larger on every side so the dot can drift for burn-in protection without the
 * window moving.
 */
@SuppressLint("ViewConstructor")
class NotificationDotView(context: Context, private var diameter: Int, private var slack: Int) :
    View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var offsetX = 0
    private var offsetY = 0

    /** Opacity on top of the view's own alpha (which animates showing and hiding). */
    var dim: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    @get:ColorInt
    var color: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    fun setSize(diameter: Int, slack: Int) {
        if (this.diameter != diameter || this.slack != slack) {
            this.diameter = diameter
            this.slack = slack
            requestLayout()
            invalidate()
        }
    }

    /** Burn-in drift in pixels; must stay within the slack. */
    fun setOffset(x: Int, y: Int) {
        val clampedX = x.coerceIn(-slack, slack)
        val clampedY = y.coerceIn(-slack, slack)
        if (offsetX != clampedX || offsetY != clampedY) {
            offsetX = clampedX
            offsetY = clampedY
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = diameter + 2 * slack
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        paint.alpha = (Color.alpha(color) * dim).roundToInt()
        canvas.drawCircle(width / 2f + offsetX, height / 2f + offsetY, diameter / 2f, paint)
    }
}

/** One arc of the privacy ring: a filled path in screen coordinates and its colour. */
data class PrivacyRingSegment(val path: Path, @ColorInt val color: Int)

/** The privacy ring: filled paths given in screen coordinates, drawn relative to the window. */
class PrivacyRingView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var segments: List<PrivacyRingSegment> = emptyList()
    private var originX = 0
    private var originY = 0

    /** Opacity on top of the view's own alpha (which animates showing and hiding). */
    var dim: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * @param segments what to draw, in screen coordinates
     * @param originX the window's left edge on screen
     * @param originY the window's top edge on screen
     */
    fun setRing(segments: List<PrivacyRingSegment>, originX: Int, originY: Int) {
        this.segments = segments
        this.originX = originX
        this.originY = originY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.translate(-originX.toFloat(), -originY.toFloat())
        for (segment in segments) {
            paint.color = segment.color
            paint.alpha = (Color.alpha(segment.color) * dim).roundToInt()
            canvas.drawPath(segment.path, paint)
        }
    }
}

/** The privacy ring's shape for one outline: the band to fill and where it is on screen. */
class PrivacyRingGeometry(val ring: Path, val bounds: Rect) {

    /**
     * Splits the ring into one arc per colour, clockwise from the top, so several things in use
     * at once each get their share of the ring. One colour gives the whole ring.
     */
    fun segments(colors: List<Int>): List<PrivacyRingSegment> {
        if (colors.size <= 1) {
            return listOf(PrivacyRingSegment(ring, colors.firstOrNull() ?: 0))
        }
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val reach = hypot(bounds.width().toFloat(), bounds.height().toFloat())
        val oval = RectF(cx - reach, cy - reach, cx + reach, cy + reach)
        val sweep = 360f / colors.size
        return colors.mapIndexed { index, color ->
            val sector =
                Path().apply {
                    moveTo(cx, cy)
                    arcTo(oval, -90f + index * sweep, sweep)
                    close()
                }
            val arc = Path()
            if (!arc.op(ring, sector, Path.Op.INTERSECT)) arc.set(ring)
            PrivacyRingSegment(arc, color)
        }
    }

    companion object {
        /**
         * A band [width] pixels wide that follows [outline] at [gap] pixels outside it, whatever
         * the outline's shape (a punch hole gives a plain annulus).
         *
         * @param outline the camera cutout's outline in screen coordinates; not modified
         * @param limits the display bounds; the ring's bounds are clipped to it
         * @return null if the outline is empty or lies outside the display
         */
        fun create(outline: Path, gap: Float, width: Float, limits: Rect): PrivacyRingGeometry? {
            if (outline.isEmpty || width <= 0f) return null
            val outer = outset(outline, gap + width)
            val inner = outset(outline, gap)
            val ring = Path()
            if (!ring.op(outer, inner, Path.Op.DIFFERENCE) || ring.isEmpty) {
                // Path ops can fail on degenerate outlines: stroke the outline itself instead.
                ring.reset()
                strokePaint(width).getFillPath(outline, ring)
            }
            if (ring.isEmpty) return null
            val boundsF = RectF()
            ring.computeBounds(boundsF, /* exact= */ true)
            val bounds = Rect()
            boundsF.roundOut(bounds)
            bounds.inset(-1, -1)
            if (!bounds.intersect(limits)) return null
            return PrivacyRingGeometry(ring, bounds)
        }

        /** The area of [path] grown by [distance] pixels all round. */
        private fun outset(path: Path, distance: Float): Path {
            if (distance <= 0f) return Path(path)
            val band = Path()
            strokePaint(2 * distance).getFillPath(path, band)
            val grown = Path()
            return if (grown.op(path, band, Path.Op.UNION)) grown else Path(path)
        }

        private fun strokePaint(width: Float) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeJoin = Paint.Join.ROUND
            }
    }
}
