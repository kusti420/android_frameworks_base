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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * IchthysOS lock screen clock faces that are drawn rather than laid out from TextClocks.
 *
 * They live in SettingsLib because both hosts link it: the crDroid Settings clock picker inflates
 * the same `keyguard_clock_ichthys_*` layouts for its preview, and SystemUI's
 * KeyguardCustomClockView inflates them (cross-package resources, SystemUI class loader) on the
 * lock screen. The views take no custom XML attributes for that reason; the lock screen host
 * configures them through [IchthysClockFace].
 *
 * Every face redraws once per minute (ACTION_TIME_TICK) and never animates, so AOD costs nothing.
 */
interface IchthysClockFace {
    /** [primary] is the main ink, [accent] the signal colour (hours, lit segments, arcs). */
    fun setClockColors(primary: Int, accent: Int)

    /** While dozing, faces drop unlit dots, fills and gradients to cut lit pixels. */
    fun setDozing(dozing: Boolean)

    /**
     * [showDate]: draw the date line. [dotShape]: [DOT_ROUND], [DOT_SQUARE] or [DOT_DIAMOND] for
     * the dot faces. [showUnlit]: draw unlit dots/letters faintly.
     */
    fun setClockOptions(showDate: Boolean, dotShape: Int, showUnlit: Boolean)

    companion object {
        const val DOT_ROUND = 0
        const val DOT_SQUARE = 1
        const val DOT_DIAMOND = 2
    }
}

/** Shared plumbing: colours, minute ticks, 12/24 h, sizing and small drawing helpers. */
abstract class IchthysClockFaceView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), IchthysClockFace {

    protected var primary: Int = Color.WHITE
    protected var accent: Int = context.getColor(android.R.color.system_accent1_100)
    protected var dozing = false
    protected var showDate = true
    protected var dotShape = IchthysClockFace.DOT_ROUND
    protected var showUnlit = true

    protected val calendar: Calendar = Calendar.getInstance()

    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

    private var receiverRegistered = false
    private val timeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                    calendar.timeZone = TimeZone.getDefault()
                }
                refreshTime()
            }
        }

    /** Preferred size in dp when the layout says wrap_content. */
    protected abstract val preferredWidthDp: Float
    protected abstract val preferredHeightDp: Float

    override fun setClockColors(primary: Int, accent: Int) {
        this.primary = primary
        this.accent = accent
        invalidate()
    }

    override fun setDozing(dozing: Boolean) {
        if (this.dozing == dozing) return
        this.dozing = dozing
        invalidate()
    }

    override fun setClockOptions(showDate: Boolean, dotShape: Int, showUnlit: Boolean) {
        this.showDate = showDate
        this.dotShape = dotShape
        this.showUnlit = showUnlit
        requestLayout()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!receiverRegistered) {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
            context.registerReceiver(timeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        calendar.timeZone = TimeZone.getDefault()
        refreshTime()
    }

    override fun onDetachedFromWindow() {
        if (receiverRegistered) {
            context.unregisterReceiver(timeReceiver)
            receiverRegistered = false
        }
        super.onDetachedFromWindow()
    }

    private fun refreshTime() {
        calendar.timeInMillis = System.currentTimeMillis()
        contentDescription = DateFormat.getTimeFormat(context).format(calendar.time)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(preferredWidthDp).toInt(), widthMeasureSpec)
        val h = resolveSize(dp(preferredHeightDp).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    protected fun is24h(): Boolean = DateFormat.is24HourFormat(context)

    protected fun hour24(): Int = calendar.get(Calendar.HOUR_OF_DAY)

    protected fun minute(): Int = calendar.get(Calendar.MINUTE)

    /** Hour as shown: 0-23 in 24 h mode, 1-12 otherwise. */
    protected fun displayHour(): Int {
        if (is24h()) return hour24()
        val h = hour24() % 12
        return if (h == 0) 12 else h
    }

    protected fun hhmm(): String =
        String.format(java.util.Locale.US, "%02d:%02d", displayHour(), minute())

    protected fun dateText(): String =
        DateFormat.format(DateFormat.getBestDateTimePattern(
            java.util.Locale.getDefault(), "EEEdMMM"), calendar).toString()

    protected fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    protected fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    protected fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color),
            Color.blue(color))

    /** Draws one dot of radius [r] centred at ([cx], [cy]) in the configured [dotShape]. */
    protected fun drawDot(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        when (dotShape) {
            IchthysClockFace.DOT_SQUARE -> canvas.drawRect(cx - r, cy - r, cx + r, cy + r, paint)
            IchthysClockFace.DOT_DIAMOND -> {
                canvas.save()
                canvas.rotate(45f, cx, cy)
                val s = r * 0.85f
                canvas.drawRect(cx - s, cy - s, cx + s, cy + s, paint)
                canvas.restore()
            }
            else -> canvas.drawCircle(cx, cy, r, paint)
        }
    }

    /** Draws a centred single line of text with its baseline at [baseline]. */
    protected fun drawCentered(
        canvas: Canvas,
        text: String,
        cx: Float,
        baseline: Float,
        sizePx: Float,
        color: Int,
        weight: String = "sans-serif-medium",
    ) {
        textPaint.textSize = sizePx
        textPaint.color = color
        textPaint.typeface = Typeface.create(weight, Typeface.NORMAL)
        canvas.drawText(text, cx, baseline, textPaint)
    }
}

/** 5x7 dot glyphs for 0-9 (rows top to bottom, '#' = lit). */
internal object DotFont {
    private val GLYPHS =
        arrayOf(
            arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."), // 0
            arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."), // 1
            arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"), // 2
            arrayOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."), // 3
            arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."), // 4
            arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."), // 5
            arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."), // 6
            arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."), // 7
            arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."), // 8
            arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."), // 9
        )

    const val COLS = 5
    const val ROWS = 7

    fun lit(digit: Int, row: Int, col: Int): Boolean = GLYPHS[digit][row][col] == '#'
}

/**
 * "Shoal": HH:MM in a dot font whose odd rows are shifted half a pitch, so the dots nest like the
 * status bar's zig-zag lattice. Hours in the accent, minutes in the primary ink.
 */
class ShoalLatticeClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {
    override val preferredWidthDp: Float = 320f
    override val preferredHeightDp: Float get() = if (showDate) 124f else 96f

    override fun onDraw(canvas: Canvas) {
        val digits = hhmm().filter { it.isDigit() }.map { it - '0' }
        // 4 glyphs of 5 columns, 1 column gaps, a 2 column colon: 27 columns incl. the half shift.
        val cols = 4 * DotFont.COLS + 3 + 2 + 0.5f
        val pitch = width / cols
        val rowPitch = pitch * 0.87f
        val r = pitch * 0.36f
        val gridH = rowPitch * (DotFont.ROWS - 1) + 2 * r
        val top = r
        var x0 = pitch / 2f
        digits.forEachIndexed { i, d ->
            if (i == 2) {
                // colon
                val cx = x0 + pitch * 0.5f
                drawDot(canvas, cx, top + rowPitch * 2, r, primary)
                drawDot(canvas, cx + pitch * 0.5f, top + rowPitch * 4, r, primary)
                x0 += pitch * 2
            }
            val ink = if (i < 2) accent else primary
            for (row in 0 until DotFont.ROWS) {
                val shift = if (row % 2 == 1) pitch * 0.5f else 0f
                for (col in 0 until DotFont.COLS) {
                    val cx = x0 + col * pitch + shift
                    val cy = top + row * rowPitch
                    if (DotFont.lit(d, row, col)) {
                        drawDot(canvas, cx, cy, r, ink)
                    } else if (showUnlit && !dozing) {
                        drawDot(canvas, cx, cy, r * 0.8f, withAlpha(primary, 0.10f))
                    }
                }
            }
            x0 += pitch * (DotFont.COLS + 1)
        }
        if (showDate) {
            drawCentered(canvas, dateText(), width / 2f, gridH + sp(24f), sp(15f),
                withAlpha(primary, 0.8f))
        }
    }
}

/** Nothing-style dot-matrix: hours stacked over minutes in large round (or square) dots. */
class DotMatrixClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {
    override val preferredWidthDp: Float = 220f
    override val preferredHeightDp: Float get() = if (showDate) 290f else 262f

    override fun onDraw(canvas: Canvas) {
        val s = hhmm()
        val lines = listOf(s.substring(0, 2), s.substring(3, 5))
        val cols = 2 * DotFont.COLS + 1
        val pitch = width / cols.toFloat()
        val r = pitch * 0.40f
        val blockH = pitch * DotFont.ROWS
        lines.forEachIndexed { li, pair ->
            val y0 = li * (blockH + pitch) + pitch / 2f
            pair.forEachIndexed { di, ch ->
                val d = ch - '0'
                val x0 = di * (DotFont.COLS + 1) * pitch + pitch / 2f
                for (row in 0 until DotFont.ROWS) for (col in 0 until DotFont.COLS) {
                    val cx = x0 + col * pitch
                    val cy = y0 + row * pitch
                    if (DotFont.lit(d, row, col)) {
                        drawDot(canvas, cx, cy, r, if (li == 0) primary else accent)
                    } else if (showUnlit && !dozing) {
                        drawDot(canvas, cx, cy, r * 0.55f, withAlpha(primary, 0.12f))
                    }
                }
            }
        }
        if (showDate) {
            drawCentered(canvas, dateText().uppercase(), width / 2f, height - sp(4f), sp(14f),
                withAlpha(primary, 0.8f), "sans-serif-condensed")
        }
    }
}

/** Binary-coded decimal: four columns (H, H, M, M), bits 8/4/2/1 from the top. */
class BinaryClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {
    override val preferredWidthDp: Float = 220f
    override val preferredHeightDp: Float get() = if (showDate) 250f else 222f

    override fun onDraw(canvas: Canvas) {
        val s = hhmm()
        val digits = intArrayOf(s[0] - '0', s[1] - '0', s[3] - '0', s[4] - '0')
        val maxBits = intArrayOf(2, 4, 3, 4) // tallest possible value per column
        val colW = width / 4.6f
        val r = colW * 0.30f
        val rowH = colW
        for (c in 0 until 4) {
            val cx = colW * (c + 0.5f) + if (c >= 2) colW * 0.6f else 0f
            for (bit in 0 until 4) {
                if (bit >= maxBits[c]) continue
                val cy = rowH * (3 - bit + 0.5f)
                val on = (digits[c] shr bit) and 1 == 1
                val ink = if (c < 2) accent else primary
                if (on) {
                    drawDot(canvas, cx, cy, r, ink)
                } else if (!dozing) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1.5f)
                    paint.color = withAlpha(primary, if (showUnlit) 0.35f else 0f)
                    canvas.drawCircle(cx, cy, r, paint)
                }
            }
            drawCentered(canvas, digits[c].toString(), cx, rowH * 4 + sp(18f), sp(16f),
                withAlpha(primary, 0.7f))
        }
        if (showDate) {
            drawCentered(canvas, dateText(), width / 2f, height - sp(4f), sp(14f),
                withAlpha(primary, 0.8f))
        }
    }
}

/** Minimal analog: a thin ring, the minutes as an accent arc from 12, the hour as a dot. */
class AnalogRingClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {
    override val preferredWidthDp: Float = 240f
    override val preferredHeightDp: Float = 240f
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val stroke = dp(10f)
        val rad = min(width, height) / 2f - stroke
        oval.set(cx - rad, cy - rad, cx + rad, cy + rad)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = stroke
        paint.color = withAlpha(primary, if (dozing) 0.12f else 0.18f)
        canvas.drawOval(oval, paint)
        paint.color = accent
        canvas.drawArc(oval, -90f, 360f * minute() / 60f, false, paint)
        // Hour marker on an inner orbit.
        val hourAngle = Math.toRadians((360.0 * ((hour24() % 12) + minute() / 60.0) / 12.0) - 90.0)
        val orbit = rad - stroke * 2.2f
        drawDot(canvas, cx + (orbit * cos(hourAngle)).toFloat(),
            cy + (orbit * sin(hourAngle)).toFloat(), stroke * 0.7f, primary)
        drawCentered(canvas, hhmm(), cx, cy + sp(16f), sp(44f), primary, "sans-serif-light")
        if (showDate) {
            drawCentered(canvas, dateText(), cx, cy + sp(44f), sp(13f), withAlpha(primary, 0.75f))
        }
    }
}

/** Radar: range rings and a sweep wedge up to the minute; the hour is a blip with a halo. */
class RadarClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {
    override val preferredWidthDp: Float = 250f
    override val preferredHeightDp: Float = 250f
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rad = min(width, height) / 2f - dp(4f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = withAlpha(primary, 0.28f)
        for (i in 1..3) canvas.drawCircle(cx, cy, rad * i / 3f, paint)
        canvas.drawLine(cx - rad, cy, cx + rad, cy, paint)
        canvas.drawLine(cx, cy - rad, cx, cy + rad, paint)
        val sweep = 360f * minute() / 60f
        if (!dozing && sweep > 0f) {
            // Sweep fading in towards the current minute.
            val grad =
                SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, withAlpha(accent, 0.55f)),
                    floatArrayOf(0f, sweep / 360f))
            canvas.save()
            canvas.rotate(-90f, cx, cy)
            paint.style = Paint.Style.FILL
            paint.shader = grad
            oval.set(cx - rad, cy - rad, cx + rad, cy + rad)
            canvas.drawArc(oval, 0f, sweep, true, paint)
            paint.shader = null
            canvas.restore()
        }
        val minAngle = Math.toRadians(sweep - 90.0)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = accent
        canvas.drawLine(cx, cy, cx + (rad * cos(minAngle)).toFloat(),
            cy + (rad * sin(minAngle)).toFloat(), paint)
        val hourAngle = Math.toRadians((360.0 * ((hour24() % 12) + minute() / 60.0) / 12.0) - 90.0)
        val bx = cx + (rad * 0.6f * cos(hourAngle)).toFloat()
        val by = cy + (rad * 0.6f * sin(hourAngle)).toFloat()
        if (!dozing) drawDot(canvas, bx, by, dp(12f), withAlpha(primary, 0.18f))
        drawDot(canvas, bx, by, dp(6f), primary)
        drawCentered(canvas, hhmm(), cx, cy + rad - sp(6f), sp(18f), primary)
    }
}

/** Sonar: two concentric arcs from 12 o'clock, hours outside, minutes inside, time in the middle. */
class SonarArcsClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {
    override val preferredWidthDp: Float = 240f
    override val preferredHeightDp: Float = 240f
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val stroke = dp(14f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = stroke
        val outer = min(width, height) / 2f - stroke
        val inner = outer - stroke * 1.7f
        val hourFrac = ((hour24() % 12) + minute() / 60f) / 12f
        val minFrac = minute() / 60f
        for ((radius, frac, ink) in
            listOf(Triple(outer, hourFrac, accent), Triple(inner, minFrac, primary))) {
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
            if (!dozing) {
                paint.color = withAlpha(primary, 0.12f)
                canvas.drawOval(oval, paint)
            }
            paint.color = ink
            canvas.drawArc(oval, -90f, 360f * frac.coerceAtLeast(0.004f), false, paint)
        }
        drawCentered(canvas, hhmm(), cx, cy + sp(12f), sp(34f), primary, "sans-serif-medium")
        if (showDate) {
            drawCentered(canvas, dateText(), cx, cy + sp(36f), sp(12f), withAlpha(primary, 0.75f))
        }
    }
}

/** Day progress: a 24 hour dial with tick marks and the day's elapsed share as an arc. */
class DayProgressClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {
    override val preferredWidthDp: Float = 250f
    override val preferredHeightDp: Float = 250f
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rad = min(width, height) / 2f - dp(10f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (h in 0 until 24) {
            val a = Math.toRadians(h * 15.0 - 90.0)
            val long = h % 6 == 0
            paint.strokeWidth = dp(if (long) 3f else 1.5f)
            paint.color = withAlpha(primary, if (long) 0.8f else 0.35f)
            val r0 = rad - dp(if (long) 14f else 8f)
            canvas.drawLine(cx + (r0 * cos(a)).toFloat(), cy + (r0 * sin(a)).toFloat(),
                cx + (rad * cos(a)).toFloat(), cy + (rad * sin(a)).toFloat(), paint)
        }
        val frac = (hour24() * 60 + minute()) / 1440f
        val arcR = rad - dp(24f)
        oval.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        paint.strokeWidth = dp(8f)
        paint.color = accent
        canvas.drawArc(oval, -90f, 360f * frac, false, paint)
        drawCentered(canvas, hhmm(), cx, cy + sp(12f), sp(38f), primary, "sans-serif-light")
        drawCentered(canvas, "${(frac * 100).toInt()}% of the day", cx, cy + sp(36f), sp(12f),
            withAlpha(primary, 0.75f))
    }
}

/** English word clock on the classic 11x10 letter grid, rounded down to five minutes. */
class WordGridClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {
    override val preferredWidthDp: Float = 300f
    override val preferredHeightDp: Float = 300f

    private data class Word(val row: Int, val col: Int, val len: Int)

    private val grid =
        arrayOf(
            "ITLISASTIME", "ACQUARTERDC", "TWENTYFIVEX", "HALFBTENFTO", "PASTERUNINE",
            "ONESIXTHREE", "FOURFIVETWO", "EIGHTELEVEN", "SEVENTWELVE", "TENSEOCLOCK",
        )
    private val itW = Word(0, 0, 2)
    private val isW = Word(0, 3, 2)
    private val quarter = Word(1, 2, 7)
    private val twenty = Word(2, 0, 6)
    private val fiveM = Word(2, 6, 4)
    private val half = Word(3, 0, 4)
    private val tenM = Word(3, 5, 3)
    private val to = Word(3, 9, 2)
    private val past = Word(4, 0, 4)
    private val oclock = Word(9, 5, 6)
    private val hours =
        arrayOf(
            Word(8, 5, 6), // 12 twelve
            Word(5, 0, 3), Word(6, 8, 3), Word(5, 6, 5), Word(6, 0, 4), Word(6, 4, 4), // 1-5
            Word(5, 3, 3), Word(8, 0, 5), Word(7, 0, 5), Word(4, 7, 4), Word(9, 0, 3), // 6-10
            Word(7, 5, 6), // 11
        )

    private fun litWords(): List<Word> {
        val m5 = minute() / 5
        var h = hour24() % 12
        val out = mutableListOf(itW, isW)
        when (m5) {
            0 -> out += oclock
            1 -> out += listOf(fiveM, past)
            2 -> out += listOf(tenM, past)
            3 -> out += listOf(quarter, past)
            4 -> out += listOf(twenty, past)
            5 -> out += listOf(twenty, fiveM, past)
            6 -> out += listOf(half, past)
            7 -> out += listOf(twenty, fiveM, to)
            8 -> out += listOf(twenty, to)
            9 -> out += listOf(quarter, to)
            10 -> out += listOf(tenM, to)
            else -> out += listOf(fiveM, to)
        }
        if (m5 >= 7) h = (h + 1) % 12
        hourWord = hours[h]
        out += hourWord
        return out
    }

    private var hourWord: Word = hours[0]

    override fun onDraw(canvas: Canvas) {
        val lit = Array(10) { BooleanArray(11) }
        for (w in litWords()) for (c in w.col until w.col + w.len) lit[w.row][c] = true
        val cellW = width / 11f
        val cellH = (height - dp(16f)) / 10f
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = min(cellW, cellH) * 0.62f
        textPaint.textAlign = Paint.Align.CENTER
        for (r in 0 until 10) for (c in 0 until 11) {
            val on = lit[r][c]
            if (!on && (dozing || !showUnlit)) continue
            val inHour = r == hourWord.row && c >= hourWord.col && c < hourWord.col + hourWord.len
            textPaint.color =
                if (!on) withAlpha(primary, 0.16f) else if (inHour) accent else primary
            canvas.drawText(grid[r][c].toString(), cellW * (c + 0.5f),
                cellH * (r + 0.72f), textPaint)
        }
        // Minute dots (0-4 past the five-minute step).
        val extra = minute() % 5
        for (i in 0 until 4) {
            val on = i < extra
            if (!on && dozing) continue
            drawDot(canvas, width / 2f + (i - 1.5f) * dp(14f), height - dp(6f), dp(3f),
                if (on) accent else withAlpha(primary, 0.2f))
        }
    }
}
