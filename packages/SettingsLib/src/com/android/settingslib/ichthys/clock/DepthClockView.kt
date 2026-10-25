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

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.text.format.DateFormat
import android.util.AttributeSet
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * IchthysOS "Depth" face (kusti420/ichthysos-tracker#58): hours over minutes in very tall condensed
 * digits (Roboto Flex at its narrowest and heaviest), made to stand behind the wallpaper's subject
 * on the depth lock screen. Above them a status line: date, time with seconds, battery temperature
 * and one bar per CPU core.
 *
 * Settings.Secure [KEY_FILL] picks what fills the digits:
 * - [FILL_SOLID]: the clock colour.
 * - [FILL_GRADIENT]: the accent fading into the clock colour, top to bottom.
 * - [FILL_HEAT]: dark ink with a hairline edge, and through it today's touches as glowing heat
 *   ([TouchHeat], counted by SystemUI): each digit is a window onto where on the screen the phone
 *   was touched, brighter where it was touched more. Touches on the lock screen are never counted.
 *
 * Dozing, only the digits' outlines are drawn (with the heat faint inside them) and nothing ticks.
 */
class DepthClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {

    override val preferredWidthDp: Float = 360f
    override val preferredHeightDp: Float = 520f

    private var fill = FILL_HEAT

    private val digitPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = digitTypeface()
            textAlign = Paint.Align.CENTER
        }
    /** For sizing only, so measuring never changes [digitPaint]'s size. */
    private val measurePaint = Paint(digitPaint)
    private val monoPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = monoTypeface() }
    private val digits = Path()
    private val bounds = Rect()

    /** Layout, from [onSizeChanged]. */
    private var statusH = 0f
    private var digitSize = 0f
    private var line1Base = 0f
    private var line2Base = 0f
    private var shownTime = ""

    private val stats = LiveStats(context)

    /** [TouchHeat] as colours, one pixel per cell; rebuilt when the heat changes. */
    private var heatBitmap: Bitmap? = null
    private var heatVersion = -1
    private val heatPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val screenPos = IntArray(2)
    private val heatListener: () -> Unit = { post { invalidate() } }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker =
        object : Runnable {
            override fun run() {
                calendar.timeInMillis = System.currentTimeMillis()
                stats.sampleCores()
                invalidate()
                // On the second, so the seconds in the status line step evenly.
                handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
            }
        }

    private val fillObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                readFill()
                invalidate()
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(KEY_FILL), false, fillObserver, UserHandle.USER_ALL)
        readFill()
        TouchHeat.addListener(heatListener)
        updateTicking()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        TouchHeat.removeListener(heatListener)
        context.contentResolver.unregisterContentObserver(fillObserver)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateTicking()
    }

    override fun setDozing(dozing: Boolean) {
        super.setDozing(dozing)
        updateTicking()
    }

    override fun setClockOptions(showDate: Boolean, dotShape: Int, showUnlit: Boolean) {
        super.setClockOptions(showDate, dotShape, showUnlit)
        layoutFace(width, height)
    }

    /** A tick a second while shown and awake; dozing, the minute tick of the base class only. */
    private fun updateTicking() {
        handler.removeCallbacks(ticker)
        if (isAttachedToWindow && isShown && !dozingNow) handler.post(ticker)
    }

    private fun readFill() {
        fill =
            Settings.Secure.getIntForUser(
                context.contentResolver, KEY_FILL, FILL_HEAT, UserHandle.USER_CURRENT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As wide as the host allows: the digits are sized to the width.
        val w =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
                dp(preferredWidthDp).toInt()
            else MeasureSpec.getSize(widthMeasureSpec)
        val size = digitSizeFor(w)
        // Measured from the real glyphs, as layoutFace() places them, plus a little for the
        // outline and overshoot.
        measurePaint.textSize = size
        measurePaint.getTextBounds("00", 0, 2, bounds)
        val cap = bounds.height().toFloat()
        val h = (statusHeight() + size * 0.04f + cap * 2.04f + size * 0.03f).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutFace(w, h)
    }

    private fun statusHeight(): Float = if (showDate) dp(STATUS_DP) else 0f

    /** The text size at which "00" fills [WIDTH_SHARE] of [w], kept to a share of the screen. */
    private fun digitSizeFor(w: Int): Float {
        measurePaint.textSize = 100f
        val textW = max(measurePaint.measureText("00"), 1f)
        val byWidth = w * WIDTH_SHARE / textW * 100f
        val byScreen = resources.displayMetrics.heightPixels * MAX_SCREEN_SHARE / LINE_STEP
        return min(byWidth, byScreen)
    }

    private fun layoutFace(w: Int, h: Int) {
        if (w <= 0) return
        statusH = statusHeight()
        digitSize = digitSizeFor(w)
        digitPaint.textSize = digitSize
        digitPaint.getTextBounds("00", 0, 2, bounds)
        // Baselines a cap height apart, minus a little, so the lines sit tight like one block.
        val cap = bounds.height().toFloat()
        line1Base = statusH + cap + digitSize * 0.04f
        line2Base = line1Base + cap * 1.04f
        shownTime = ""
        invalidate()
    }

    /** The digits as one path; rebuilt when the minute changes. */
    private fun digitsPath(): Path {
        val hh = String.format(Locale.US, "%02d", displayHour())
        val mm = String.format(Locale.US, "%02d", minute())
        val key = hh + mm
        if (key != shownTime) {
            shownTime = key
            digits.reset()
            val p = Path()
            digitPaint.getTextPath(hh, 0, 2, width / 2f, line1Base, p)
            digits.addPath(p)
            p.reset()
            digitPaint.getTextPath(mm, 0, 2, width / 2f, line2Base, p)
            digits.addPath(p)
        }
        return digits
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || digitSize == 0f) return
        if (showDate) drawStatus(canvas)
        val path = digitsPath()
        paint.shader = null
        if (dozingNow) {
            if (fill == FILL_HEAT) drawHeat(canvas, path, 0.35f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = withAlpha(primary, 0.6f)
            canvas.drawPath(path, paint)
            return
        }
        paint.style = Paint.Style.FILL
        when (fill) {
            FILL_SOLID -> {
                paint.color = primary
                canvas.drawPath(path, paint)
            }
            FILL_GRADIENT -> {
                paint.shader =
                    LinearGradient(0f, statusH, 0f, line2Base, accent, primary,
                        Shader.TileMode.CLAMP)
                canvas.drawPath(path, paint)
                paint.shader = null
            }
            else -> {
                paint.color = INK
                canvas.drawPath(path, paint)
                drawHeat(canvas, path, 1f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(0.8f)
                paint.color = withAlpha(primary, 0.22f)
                canvas.drawPath(path, paint)
            }
        }
    }

    /**
     * Today's touches inside the digits, where they happened on the screen: the heat map is laid
     * over the whole screen and the digits are cut out of it.
     */
    private fun drawHeat(canvas: Canvas, path: Path, alpha: Float) {
        val heat = heatBitmap()
        getLocationOnScreen(screenPos)
        val screen = resources.displayMetrics
        val sw = max(screen.widthPixels, 1)
        val sh = max(screen.heightPixels, 1)
        canvas.save()
        canvas.clipPath(path)
        heatPaint.alpha = (255 * alpha).toInt()
        heatDst.set(-screenPos[0].toFloat(), -screenPos[1].toFloat(),
            (sw - screenPos[0]).toFloat(), (sh - screenPos[1]).toFloat())
        canvas.drawBitmap(heat, null, heatDst, heatPaint)
        canvas.restore()
    }

    private val heatDst = android.graphics.RectF()

    /**
     * [TouchHeat] as colours: nothing where nothing was touched, then the accent, then a warm
     * white where it was touched most. The square root spreads the rarely touched places out.
     * Outside SystemUI (the picker's preview) there is no heat: a made-up day of thumb use.
     */
    private fun heatBitmap(): Bitmap {
        val inSystemUi = context.packageName == "com.android.systemui"
        val version = if (inSystemUi) TouchHeat.version else -2
        heatBitmap?.let { if (version == heatVersion) return it }
        val cells = if (inSystemUi) TouchHeat.snapshot() else TouchHeat.sample()
        val peak = max(cells.max(), 1f)
        val px = IntArray(cells.size)
        val warm = Color.rgb(255, 236, 214)
        for (i in cells.indices) {
            val v = kotlin.math.sqrt(cells[i] / peak)
            if (v < 0.04f) continue
            val c = if (v < 0.7f) accent else blend(accent, warm, (v - 0.7f) / 0.3f)
            px[i] = withAlpha(c or 0xFF000000.toInt(), min(1f, v * 1.15f))
        }
        val b = Bitmap.createBitmap(px, TouchHeat.COLS, TouchHeat.ROWS, Bitmap.Config.ARGB_8888)
        heatBitmap?.recycle()
        heatBitmap = b
        heatVersion = version
        return b
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
        )
    }

    private fun drawStatus(canvas: Canvas) {
        val pad = dp(4f)
        monoPaint.textSize = sp(11f)
        monoPaint.color = withAlpha(primary, 0.88f)
        val base = statusH - dp(10f)
        val pattern = if (is24h()) "HH:mm:ss" else "hh:mm:ss"
        val text =
            dateText().uppercase(Locale.getDefault()) + "  " +
                DateFormat.format(pattern, calendar) + "  " +
                // With the heat fill, today's touches take the temperature's place.
                if (fill == FILL_HEAT && context.packageName == "com.android.systemui")
                    String.format(Locale.US, "%,d TOUCHES", TouchHeat.touches)
                else stats.batteryTemp()?.let { String.format(Locale.US, "%.1f°C", it) } ?: ""
        canvas.drawText(text, pad, base, monoPaint)

        // One bar per core, its clock against its maximum; the busiest in the accent.
        val loads = stats.coreLoads()
        if (loads.isNotEmpty()) {
            val barW = dp(2.5f)
            val gap = dp(1.5f)
            val maxH = dp(12f)
            var x = width - pad - loads.size * (barW + gap)
            val busiest = loads.indices.maxBy { loads[it] }
            paint.style = Paint.Style.FILL
            for ((i, load) in loads.withIndex()) {
                val h = max(dp(2f), maxH * load)
                paint.color = if (i == busiest) accent else withAlpha(primary, 0.85f)
                canvas.drawRect(x, base - h, x + barW, base, paint)
                x += barW + gap
            }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.6f)
        paint.color = withAlpha(primary, 0.18f)
        canvas.drawLine(pad, statusH - dp(4f), width - pad, statusH - dp(4f), paint)
    }

    /**
     * What the phone is doing, as short "log lines" of numbers it measures itself. Cheap: a few
     * small sysfs reads and system service calls a second, only while the face ticks.
     */
    private class LiveStats(private val context: Context) {
        private val cores = Runtime.getRuntime().availableProcessors()
        private val maxFreq = LongArray(cores) { readLong(cpuFile(it, "cpuinfo_max_freq")) }
        private val loads = FloatArray(cores)

        fun sampleCores() {
            for (i in 0 until cores) {
                val cur = readLong(cpuFile(i, "scaling_cur_freq"))
                loads[i] = if (maxFreq[i] > 0 && cur > 0) cur.toFloat() / maxFreq[i] else 0f
            }
        }

        /** Per core 0..1, empty when the clocks can't be read. */
        fun coreLoads(): FloatArray = if (loads.any { it > 0f }) loads else FloatArray(0)

        fun batteryTemp(): Float? =
            battery()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.let { it / 10f }

        private fun battery(): Intent? =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED)

        private fun cpuFile(core: Int, name: String) =
            File("/sys/devices/system/cpu/cpu$core/cpufreq/$name")

        private fun readLong(f: File): Long =
            try {
                f.readText().trim().toLong()
            } catch (e: Exception) {
                // Offline core, or not readable here.
                0L
            }

    }

    companion object {
        /** Settings.Secure: what fills the digits ([FILL_SOLID], [FILL_GRADIENT], [FILL_HEAT]). */
        const val KEY_FILL = "ichthys_clock_fill"
        const val FILL_SOLID = 0
        const val FILL_GRADIENT = 1
        const val FILL_HEAT = 2

        private const val STATUS_DP = 30f
        /** Share of the face's width "00" takes. */
        private const val WIDTH_SHARE = 0.92f
        /** At most this share of the screen's height per line of digits. */
        private const val MAX_SCREEN_SHARE = 0.27f
        /** One line of digits takes about this many text sizes, for measuring. */
        private const val LINE_STEP = 0.76f
        private val INK = Color.argb(158, 4, 9, 11)

        private fun digitTypeface(): Typeface =
            variable("/system/fonts/RobotoFlex-Regular.ttf", "'wdth' 25, 'wght' 1000, 'opsz' 144")
                ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)

        private fun monoTypeface(): Typeface =
            variable("/system/fonts/JetBrainsMono-VF.ttf", "'wght' 450") ?: Typeface.MONOSPACE

        private fun variable(path: String, axes: String): Typeface? =
            try {
                if (File(path).isFile)
                    Typeface.Builder(File(path)).setFontVariationSettings(axes).build()
                else null
            } catch (e: Exception) {
                null
            }
    }
}

/**
 * Today's touches on the screen, as a coarse heat map: [COLS] x [ROWS] cells over the screen in its
 * natural orientation, the size of the A52s touch panel's node grid. SystemUI counts into it
 * (IchthysTouchHeatCollector: touch-downs while unlocked only, never on the lock screen or the
 * bouncer, so it can't give a PIN or pattern away), saves it, and starts it over every day; the
 * Depth face draws it ([DepthClockView.FILL_HEAT]). Nothing of it leaves SystemUI.
 */
object TouchHeat {
    const val COLS = 16
    const val ROWS = 37

    /** Spread of one touch, in cells. */
    private const val SIGMA = 1.1f

    private val cells = FloatArray(COLS * ROWS)
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** Touch-downs counted today. */
    @Volatile var touches = 0
        private set

    /** Bumped on every change, so drawers know when to redraw. */
    @Volatile var version = 0
        private set

    /** The day (yyyymmdd) the counts belong to. */
    @Volatile var day = 0
        private set

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** One touch at ([fx], [fy]), as fractions of the screen's width and height. */
    @Synchronized
    fun add(fx: Float, fy: Float, today: Int) {
        if (today != day) reset(today)
        val cx = fx * COLS - 0.5f
        val cy = fy * ROWS - 0.5f
        val r = (SIGMA * 3).toInt() + 1
        for (y in max(0, cy.toInt() - r)..min(ROWS - 1, cy.toInt() + r)) {
            for (x in max(0, cx.toInt() - r)..min(COLS - 1, cx.toInt() + r)) {
                val dx = x - cx
                val dy = y - cy
                cells[y * COLS + x] += kotlin.math.exp(-(dx * dx + dy * dy) / (2 * SIGMA * SIGMA))
            }
        }
        touches++
        changed()
    }

    @Synchronized
    fun reset(today: Int) {
        cells.fill(0f)
        touches = 0
        day = today
        changed()
    }

    @Synchronized fun snapshot(): FloatArray = cells.copyOf()

    /** As saved: day, touches, then the cells. */
    @Synchronized
    fun save(out: java.io.DataOutputStream) {
        out.writeInt(day)
        out.writeInt(touches)
        for (c in cells) out.writeFloat(c)
    }

    @Synchronized
    fun load(input: java.io.DataInputStream) {
        day = input.readInt()
        touches = input.readInt()
        for (i in cells.indices) cells[i] = input.readFloat()
        changed()
    }

    private fun changed() {
        version++
        listeners.forEach { it() }
    }

    /** A made-up day of one-handed use, for previews: the thumb's arc low on the screen. */
    fun sample(): FloatArray {
        val out = FloatArray(COLS * ROWS)
        fun splat(fx: Float, fy: Float, w: Float) {
            for (y in 0 until ROWS) for (x in 0 until COLS) {
                val dx = (x + 0.5f) / COLS - fx
                val dy = ((y + 0.5f) / ROWS - fy) * 2.2f
                out[y * COLS + x] += w * kotlin.math.exp(-(dx * dx + dy * dy) / 0.012f)
            }
        }
        splat(0.62f, 0.78f, 1f)
        splat(0.45f, 0.86f, 0.8f)
        splat(0.75f, 0.55f, 0.5f)
        splat(0.3f, 0.35f, 0.35f)
        splat(0.5f, 0.12f, 0.25f)
        return out
    }
}
