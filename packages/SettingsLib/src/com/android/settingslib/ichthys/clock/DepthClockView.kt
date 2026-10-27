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
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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
 * - [FILL_HEAT]: dark ink with a hairline edge that glows wherever a finger is on the lock screen
 *   (live, never counted). On AOD the digits' outlines hold today's touches as faint heat
 *   ([TouchHeat], counted by SystemUI while unlocked): each digit is a window onto where on the
 *   screen the phone was touched, brighter where it was touched more.
 *
 * Dozing, only the digits' outlines are drawn (with the heat faint inside them) and nothing ticks.
 *
 * IchthysOS lock screen editor v2:
 * - Settings.Secure [KEY_DEPTH] [DEPTH_SHAPE] ("Shape", One UI style): the digits stretch or
 *   shrink in height (width and letterforms unchanged) to fill the room above the wallpaper's
 *   subject ([SubjectOutline], published by SystemUI once per mask; sized by [AdaptiveShape]).
 *   [KEY_SHAPE_STYLE]: [SHAPE_STRETCH] ends just above the subject, [SHAPE_TUCK] a little behind
 *   it, [SHAPE_FOLLOW] gives each digit column its own height from the outline under it. Changes
 *   ease in; nothing is computed per frame otherwise.
 * - [KEY_FONT] picks the digits' typeface, [KEY_DATE_POS] / [KEY_DATE_ALIGN] put the date (or
 *   status) line above or below the digits, left, centred or right.
 */
class DepthClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {

    override val preferredWidthDp: Float = 360f
    override val preferredHeightDp: Float = 520f

    private var fill = FILL_SOLID

    /** IchthysOS editor v2: depth mode, shape flow (0..100), font, date line place. */
    private var depthMode = 0
    private var shapeStyle = SHAPE_STRETCH
    private var font = FONT_TALL
    private var datePos = DATE_ABOVE
    private var dateAlign = ALIGN_CENTRE

    /**
     * Shape mode ([shapedPath]): the four digits as separate rigid glyphs (hours left/right, then
     * minutes), their columns' x spans, and the vertical factor per column: target, shown (eased).
     */
    private val shaped = Path()
    private val glyphs = Array(4) { Path() }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val columns = FloatArray(4)
    /**
     * IchthysOS polish: what the glyphs / the shape were last worked out for, compared as numbers
     * (no strings built per frame; the shape eases at 60 fps). [shapeDirty] forces it again.
     */
    private val glyphInputs = IntArray(5) { Int.MIN_VALUE }
    private val shapeInputs = IntArray(11) { Int.MIN_VALUE }
    private var shapeProfile: SubjectOutline.Profile? = null
    private var shapeDirty = true
    private val targetK = floatArrayOf(1f, 1f)
    private val fromK = floatArrayOf(1f, 1f)
    private val shownK = floatArrayOf(1f, 1f)
    private var easeStart = 0L
    private var shapeShown = false
    /** Drawn at least once: from then on shape changes ease in (the first draw is placed at once). */
    private var hasDrawn = false
    /** Extra height the stretched digits need below the natural face, measured in. */
    private var stretchExtra = 0f
    private val shapeMatrix = android.graphics.Matrix()
    private val outlineListener: () -> Unit = { post { shapeDirty = true; invalidate() } }

    /**
     * IchthysOS polish: where the weather line under the clock should start ([underTop], this
     * face's px, unscaled; NaN: nothing worked out yet) and the part of the face that shows
     * something ([contentRect]: the date line and the digits as drawn, for the editor's selection
     * frame). Worked out after each draw; [onFaceLayout] (previews) and [DepthFaceAnchor]
     * (SystemUI) hear of changes.
     */
    private var underTop = Float.NaN
    private val contentRect = RectF()
    private val dateRect = RectF()
    private var reportedUnder = Float.NaN
    private val reportedContent = RectF()
    /** The under-the-clock weather line's height on the screen (px; 0: not shown there). */
    private var underLineH = 0f
    private var underLineFor: LockWeather.Options? = null
    private var underLineNow: LockWeather.Now? = null
    private val placeBuf = FloatArray(3)
    private var capH = 0f

    /**
     * For previews: told the face's content rectangle and [underTop] (this view's px) whenever
     * they change, after the draw that changed them.
     */
    var onFaceLayout: ((RectF, Float) -> Unit)? = null

    /**
     * IchthysOS polish: 0 awake .. 1 dozing, as SystemUI's doze animation goes ([setDozeAmount]),
     * so the face cross-fades between its filled and its outlined self instead of switching.
     * Negative: not driven, [dozingNow] decides at once (previews).
     */
    private var dozeT = -1f

    private val digitPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = digitTypeface(FONT_TALL)
            textAlign = Paint.Align.CENTER
            // Set tight, the digits nearly touching, like one block.
            letterSpacing = -0.03f
        }
    /** For sizing only, so measuring never changes [digitPaint]'s size. */
    private val measurePaint = Paint(digitPaint)
    private val monoPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = monoTypeface() }
    private val digits = Path()
    private val bounds = Rect()

    /** Layout, from [onSizeChanged]. */
    private var statusH = 0f
    /** Where the digits start, and the date / status line's baseline (above or below them). */
    private var digitTop = 0f
    private var statusBase = 0f
    private var digitSize = 0f
    private var line1Base = 0f
    private var line2Base = 0f
    private var shownTime = -1

    private val stats = LiveStats(context)

    /**
     * IchthysOS lock screen weather ([LockWeather]): drawn in the date line when it is placed
     * there ([LockWeather.POS_DATE]); [weatherRect] is where it was drawn (empty: not drawn).
     */
    private var weatherOptions = LockWeather.Options()
    private val weatherDrawer by lazy { LockWeatherDrawer(context) }
    private val weatherRect = RectF()
    private val weatherListener: () -> Unit = { post { invalidate() } }

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
                // Sampled here, not in onDraw: the draw only reads the last values.
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
                updateTicking()
                invalidate()
            }
        }

    /** IchthysOS: the fill and status line are per user; the observers only see changes. */
    private val userReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                fillObserver.onChange(false)
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(KEY_FILL), false, fillObserver, UserHandle.USER_ALL)
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(KEY_DETAILS), false, fillObserver, UserHandle.USER_ALL)
        for (key in arrayOf(KEY_DEPTH, KEY_SHAPE_STYLE, KEY_FONT, KEY_DATE_POS, KEY_DATE_ALIGN)) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(key), false, fillObserver, UserHandle.USER_ALL)
        }
        // IchthysOS: the weather's options (crDroid's keys are System ones).
        for (key in LockWeather.SYSTEM_KEYS) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, fillObserver, UserHandle.USER_ALL)
        }
        for (key in LockWeather.SECURE_KEYS) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(key), false, fillObserver, UserHandle.USER_ALL)
        }
        LockWeather.addListener(context, weatherListener)
        SubjectOutline.addListener(outlineListener)
        context.registerReceiver(userReceiver, IntentFilter(Intent.ACTION_USER_SWITCHED),
            Context.RECEIVER_NOT_EXPORTED)
        readFill()
        TouchHeat.addListener(heatListener)
        updateTicking()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        DepthFaceAnchor.clear(this)
        stats.stop()
        TouchHeat.removeListener(heatListener)
        SubjectOutline.removeListener(outlineListener)
        LockWeather.removeListener(weatherListener)
        context.contentResolver.unregisterContentObserver(fillObserver)
        context.unregisterReceiver(userReceiver)
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

    /**
     * IchthysOS polish: SystemUI's doze amount (0 awake .. 1 AOD), eased with the doze animation.
     * The digits cross-fade from filled to outlined and the date line dims along with it.
     */
    fun setDozeAmount(amount: Float) {
        val v = amount.coerceIn(0f, 1f)
        if (v == dozeT) return
        dozeT = v
        invalidate()
    }

    private fun dozeFraction(): Float = if (dozeT >= 0f) dozeT else if (dozingNow) 1f else 0f

    override fun setClockOptions(showDate: Boolean, dotShape: Int, showUnlit: Boolean) {
        super.setClockOptions(showDate, dotShape, showUnlit)
        layoutFace(width, height)
        updateTicking()
    }

    override fun setClockColors(primary: Int, accent: Int) {
        super.setClockColors(primary, accent)
        fillShader = null
    }

    /**
     * A tick a second while the status line (seconds, core bars) is shown and the face is awake;
     * otherwise the minute tick of the base class only.
     */
    private fun updateTicking() {
        handler.removeCallbacks(ticker)
        if (isAttachedToWindow && isShown && !dozingNow && showDate && details) {
            stats.start()
            handler.post(ticker)
        } else {
            stats.stop()
            // The dozing status line still shows the temperature: one read now, none per draw.
            if (isAttachedToWindow && showDate && details) stats.readBatteryOnce()
        }
    }

    /** For previews (the lock screen editor): show this fill / status line instead of the settings'. */
    // IchthysOS polish: the preview setters only act on a change. The editor binds every face on
    // every frame of a drag; re-reading the settings and the shape each time was its jank.
    var previewFill: Int? = null
        set(v) {
            if (field == v) return
            field = v
            readFill()
            invalidate()
        }
    var previewDetails: Boolean? = null
        set(v) {
            if (field == v) return
            field = v
            readFill()
            updateTicking()
            invalidate()
        }

    /** IchthysOS editor v2, for previews: these instead of the settings (null: the setting). */
    var previewDepth: Int? = null
        set(v) {
            if (field == v) return
            field = v
            readFill()
        }
    var previewShapeStyle: Int? = null
        set(v) {
            if (field == v) return
            field = v
            readFill()
        }
    var previewFont: Int? = null
        set(v) {
            if (field == v) return
            field = v
            readFill()
        }
    var previewDatePos: Int? = null
        set(v) {
            if (field == v) return
            field = v
            readFill()
        }
    var previewDateAlign: Int? = null
        set(v) {
            if (field == v) return
            field = v
            readFill()
        }

    /** IchthysOS, for previews: these weather options instead of the settings'. */
    var previewWeather: LockWeather.Options? = null
        set(v) {
            if (field == v) return
            field = v
            readFill()
        }

    /**
     * For previews: told where the weather in the date line is (this view's pixels; null: none)
     * after each draw that moved it.
     */
    var onWeatherPlaced: ((RectF?) -> Unit)? = null
    private var reportedWeather: RectF? = null

    /** For previews: the outline to fit to, instead of SystemUI's [SubjectOutline.current]. */
    var previewOutline: SubjectOutline.Profile? = null
        set(v) {
            if (field === v) return
            field = v
            shapeDirty = true
            invalidate()
        }

    /**
     * For previews drawn scaled: where this face's top left corner is on the previewed lock screen
     * (in [previewOutline]'s pixels) and its scale there: left, top, scale. Without it the face's
     * real place on the screen is used.
     */
    var previewPlacement: FloatArray? = null
        set(v) {
            val old = field
            if (old != null && v != null && old.contentEquals(v)) return
            field = v
            invalidate()
        }

    private fun secure(key: String, def: Int): Int =
        Settings.Secure.getIntForUser(context.contentResolver, key, def, UserHandle.USER_CURRENT)

    private fun readFill() {
        details = previewDetails ?: (secure(KEY_DETAILS, 0) != 0)
        fill = previewFill ?: secure(KEY_FILL, FILL_SOLID)
        depthMode = previewDepth ?: secure(KEY_DEPTH, 0)
        shapeStyle = previewShapeStyle ?: secure(KEY_SHAPE_STYLE, SHAPE_STRETCH)
        // IchthysOS polish: out of Shape the digits ease back to their height (shapedPath), and
        // the room they took goes once they have.
        dateAlign = previewDateAlign ?: secure(KEY_DATE_ALIGN, ALIGN_CENTRE)
        weatherOptions = previewWeather ?: LockWeather.readOptions(context)
        val newFont = previewFont ?: secure(KEY_FONT, FONT_TALL)
        val newPos = previewDatePos ?: secure(KEY_DATE_POS, DATE_ABOVE)
        if (newFont != font || newPos != datePos) {
            font = newFont
            datePos = newPos
            digitPaint.typeface = digitTypeface(font)
            digitPaint.letterSpacing = if (font in CONDENSED) -0.03f else 0f
            measurePaint.set(digitPaint)
            requestLayout()
            layoutFace(width, height)
        }
        shapeDirty = true
        invalidate()
    }

    /**
     * IchthysOS editor v2: the digits' natural block in this view (left, top, right, bottom), for
     * the editor's suggestions. Empty before the first layout.
     */
    fun digitBlock(out: RectF) {
        if (digitSize == 0f) {
            out.setEmpty()
            return
        }
        digitPaint.getTextBounds("00", 0, 2, bounds)
        val half = digitPaint.measureText("00") / 2f
        out.set(width / 2f - half, digitTop, width / 2f + half, line2Base + digitSize * 0.02f)
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
        // Plus a line under the digits for the night's touch count.
        val h = (statusHeight() + size * 0.04f + cap * 2.04f + size * 0.03f + dp(18f) +
            // IchthysOS: room for digits stretched taller in the Shape mode.
            // (Also while easing back out of Shape: it goes once the digits are back.)
            stretchExtra).toInt()
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
        capH = cap
        // IchthysOS editor v2: the date / status line above the digits, or below them.
        digitTop = if (datePos == DATE_BELOW) dp(4f) else statusH
        line1Base = digitTop + cap + digitSize * 0.04f
        line2Base = line1Base + cap * 1.04f
        statusBase =
            if (datePos == DATE_BELOW) line2Base + digitSize * 0.03f + statusH - dp(10f)
            else statusH - dp(10f)
        shownTime = -1
        // Glyphs and shape again for the new size.
        glyphInputs[0] = Int.MIN_VALUE
        shapeDirty = true
        fillShader = null
        invalidate()
    }

    /** The solid or gradient fill's shader, built once per layout, colour and fill. */
    private var fillShader: Shader? = null
    private var fillShaderFill = -1

    private fun fillShader(): Shader {
        fillShader?.let { if (fillShaderFill == fill) return it }
        val shader =
            if (fill == FILL_GRADIENT)
                LinearGradient(0f, digitTop, 0f, line2Base, POND_CYAN, primary,
                    Shader.TileMode.CLAMP)
            else
                LinearGradient(0f, digitTop, 0f, line2Base,
                    withAlpha(primary, 0.97f), withAlpha(primary, 0.86f),
                    Shader.TileMode.CLAMP)
        fillShader = shader
        fillShaderFill = fill
        return shader
    }

    /** The digits as one path; rebuilt when the minute changes. */
    private fun digitsPath(): Path {
        // IchthysOS polish: compared as a number, so no strings are made on the frames between.
        val t = displayHour() * 100 + minute()
        if (t != shownTime) {
            shownTime = t
            val hh = String.format(Locale.US, "%02d", displayHour())
            val mm = String.format(Locale.US, "%02d", minute())
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

    /**
     * IchthysOS "Shape" ([DEPTH_SHAPE]): the digits stretched or squeezed in height only, as rigid
     * glyphs, so the clock fills the room above the subject's outline (16 dp clear of it, and of
     * the weather line under the clock when that is shown: [underLineH]). The factors are worked
     * out again only when the time, the outline, the style or this face's place on the screen
     * changes, then eased in (450 ms; at once while dozing or on the first draw). Out of Shape
     * (another depth mode, a turned clock, no subject) the digits ease back to their height.
     */
    private fun shapedPath(natural: Path): Path {
        val profile = previewOutline ?: SubjectOutline.current
        val place = if (depthMode == DEPTH_SHAPE && profile != null) placement() else null
        if (place == null || place[2] <= 0f || profile == null) {
            if (!shapeShown) return natural
            retarget(1f, 1f)
            if (easeStart == 0L) {
                // Back at the natural height: the room the stretch took goes now.
                shapeShown = false
                if (stretchExtra != 0f) {
                    stretchExtra = 0f
                    requestLayout()
                }
                return natural
            }
            return easedGlyphs()
        }
        val left = place[0]
        val top = place[1]
        val scale = place[2]
        buildGlyphs()
        val blockH = line2Base + digitSize * 0.02f - digitTop
        // Coarse position: the AOD's burn-in nudges don't redo it.
        val moved =
            inputsChanged(shapeInputs, shownTime, (left / 8).toInt(), (top / 8).toInt(),
                (scale * 100).toInt(), shapeStyle, width, digitSize.toInt(), datePos,
                underLineH.toInt(), font, 0)
        val changed = moved || shapeDirty || profile !== shapeProfile
        if (changed) {
            shapeDirty = false
            shapeProfile = profile
            val pw = profile.width.toFloat()
            val ph = profile.height.toFloat()
            val gap = dp(SHAPE_GAP_DP)
            // IchthysOS polish: the weather line under the clock goes between the digits and the
            // subject, so the digits keep room for it.
            val reserve = underLineH / scale
            // The lowest y (in this face) a column may reach: the subject's top under it, and
            // never into the notifications / shortcuts part of the screen.
            fun limit(x0: Float, x1: Float): Float {
                val y = min(profile.minTop((left + x0 * scale) / pw, (left + x1 * scale) / pw),
                    MAX_SCREEN_BOTTOM) * ph
                return (y - gap - top) / scale - reserve
            }
            val l = limit(columns[0], columns[1])
            val r = limit(columns[2], columns[3])
            if (shapeStyle == SHAPE_FOLLOW) {
                val k = AdaptiveShape.follow(digitTop, blockH, floatArrayOf(l, r))
                retarget(k[0], k[1])
            } else {
                val k = AdaptiveShape.stretch(digitTop, blockH, min(l, r), shapeStyle == SHAPE_TUCK)
                retarget(k, k)
            }
            val extra = max(0f, max(targetK[0], targetK[1]) - 1f) * blockH
            // Grows at once (the taller digits need the room now), shrinks once they have eased.
            if (extra > stretchExtra + 1f) {
                stretchExtra = extra
                requestLayout()
            }
        }
        if (easeStart == 0L && stretchExtra > 0f) {
            val extra = max(0f, max(targetK[0], targetK[1]) - 1f) * blockH
            if (kotlin.math.abs(extra - stretchExtra) > 1f) {
                stretchExtra = extra
                requestLayout()
            }
        }
        return easedGlyphs()
    }

    /** New target factors: eased from where the digits are now, or at once before the first draw. */
    private fun retarget(k0: Float, k1: Float) {
        if (shapeShown && k0 == targetK[0] && k1 == targetK[1]) return
        targetK[0] = k0
        targetK[1] = k1
        if (!hasDrawn || dozingNow) {
            shownK[0] = k0
            shownK[1] = k1
            easeStart = 0L
        } else {
            fromK[0] = shownK[0]
            fromK[1] = shownK[1]
            easeStart = android.os.SystemClock.uptimeMillis()
        }
        shapeShown = true
    }

    /** The glyphs at the eased factors (one more frame asked for while easing). */
    private fun easedGlyphs(): Path {
        buildGlyphs()
        if (easeStart != 0L) {
            val t = ((android.os.SystemClock.uptimeMillis() - easeStart) / EASE_MS).coerceIn(0f, 1f)
            val e = EASE.getInterpolation(t)
            shownK[0] = fromK[0] + (targetK[0] - fromK[0]) * e
            shownK[1] = fromK[1] + (targetK[1] - fromK[1]) * e
            if (t >= 1f) easeStart = 0L else postInvalidateOnAnimation()
        }
        shaped.reset()
        for (i in 0 until 4) {
            shapeMatrix.setScale(1f, shownK[i % 2], 0f, digitTop)
            shaped.addPath(glyphs[i], shapeMatrix)
        }
        return shaped
    }

    /** True (and [last] updated) when any of [v] differs from [last]. */
    private fun inputsChanged(last: IntArray, vararg v: Int): Boolean {
        var changed = false
        for (i in v.indices) {
            if (last[i] != v[i]) {
                last[i] = v[i]
                changed = true
            }
        }
        return changed
    }

    /** The four digits as separate glyphs, laid out like the natural pair; per minute. */
    private fun buildGlyphs() {
        if (!inputsChanged(glyphInputs, shownTime, width, digitSize.toInt(), font, datePos)) return
        glyphPaint.set(digitPaint)
        glyphPaint.textAlign = Paint.Align.LEFT
        val hh = String.format(Locale.US, "%02d", displayHour())
        val mm = String.format(Locale.US, "%02d", minute())
        var x0Left = Float.MAX_VALUE
        var x1Left = 0f
        var x0Right = Float.MAX_VALUE
        var x1Right = 0f
        for ((row, pair) in listOf(hh to line1Base, mm to line2Base).withIndex()) {
            val text = pair.first
            val w = glyphPaint.measureText(text)
            val a = glyphPaint.measureText(text, 0, 1)
            val x = width / 2f - w / 2f
            glyphs[row * 2].reset()
            glyphs[row * 2 + 1].reset()
            glyphPaint.getTextPath(text, 0, 1, x, pair.second, glyphs[row * 2])
            glyphPaint.getTextPath(text, 1, 2, x + a, pair.second, glyphs[row * 2 + 1])
            x0Left = min(x0Left, x)
            x1Left = max(x1Left, x + a)
            x0Right = min(x0Right, x + a)
            x1Right = max(x1Right, x + w)
        }
        columns[0] = x0Left
        columns[1] = x1Left
        columns[2] = x0Right
        columns[3] = x1Right
    }

    /**
     * Left, top and scale of this face on the lock screen: the preview's, or measured (the hosts'
     * scale multiplied up). IchthysOS: measured as if nothing were turned, the way the editor's
     * preview gives it: the shape and the weather line are worked out on the upright face and the
     * turn is applied around it. (This returned null under a turn, so a turned clock on the lock
     * screen lost its Shape fit and put the weather line on the subject while the preview didn't.)
     * The array is reused: read it at once.
     */
    private fun placement(): FloatArray? {
        previewPlacement?.let { return it }
        var x = 0f
        var y = 0f
        var scale = 1f
        var v: android.view.View = this
        while (true) {
            // v's own transform, its rotation left out.
            x = v.pivotX + (x - v.pivotX) * v.scaleX + v.translationX + v.left
            y = v.pivotY + (y - v.pivotY) * v.scaleY + v.translationY + v.top
            scale *= v.scaleX
            val p = v.parent as? android.view.View ?: break
            x -= p.scrollX
            y -= p.scrollY
            v = p
        }
        // v is the root: its place in the window was added above; add the window's on screen.
        v.getLocationOnScreen(screenPos)
        placeBuf[0] = x + screenPos[0] - v.left
        placeBuf[1] = y + screenPos[1] - v.top
        placeBuf[2] = scale
        return placeBuf
    }

    /**
     * IchthysOS polish: the height of the weather line under the clock when it is shown there
     * (LockWeatherView's own measure), else 0. Only redone when the options or the weather change.
     */
    private fun updateUnderLine() {
        val o = weatherOptions
        val now = LockWeather.current ?: if (previewWeather != null) LockWeather.sample else null
        if (o === underLineFor && now === underLineNow) return
        underLineFor = o
        underLineNow = now
        val h =
            if (!o.enabled || now == null ||
                LockWeather.placeFor(o, true, showDate) != LockWeather.POS_UNDER) 0f
            else LockWeatherView.underHeight(context, weatherDrawer, o, now)
        if (h != underLineH) {
            underLineH = h
            shapeDirty = true
        }
    }

    /**
     * IchthysOS polish (after a draw): the content rectangle and where the weather line under the
     * clock goes ([underTop]):
     * - Shape: right under the shaped digits (which kept room for it above the subject).
     * - Overlap / X-ray with a subject: under the digits if it fits above the subject there,
     *   else just above the subject's top, but never higher than under the hours; when even
     *   under the hours would put it on the subject, above the clock (and its date line).
     * - Otherwise: under the digits (or the date line below them).
     */
    private fun updateAnchors(path: Path) {
        val k = if (path === shaped) max(shownK[0], shownK[1]) else 1f
        val digitsBottom = digitTop + (line2Base + digitSize * 0.02f - digitTop) * k
        val bottom =
            if (showDate && datePos == DATE_BELOW && !dateRect.isEmpty) max(digitsBottom, dateRect.bottom)
            else digitsBottom
        var under = bottom
        if (underLineH > 0f && depthMode != DEPTH_OFF && depthMode != DEPTH_SHAPE) {
            val profile = previewOutline ?: SubjectOutline.current
            val place = if (profile != null) placement() else null
            if (profile != null && place != null && place[2] > 0f) {
                buildGlyphs()
                val scale = place[2]
                val pw = profile.width.toFloat()
                val subject = (profile.minTop((place[0] + columns[0] * scale) / pw,
                    (place[0] + columns[3] * scale) / pw) * profile.height - place[1]) / scale
                val need = (underLineH + dp(UNDER_CLEAR_DP)) / scale
                if (bottom + need > subject) {
                    val underHours = line1Base + digitSize * 0.02f
                    under =
                        if (underHours + need <= subject) max(underHours, subject - need)
                        else {
                            // No clear band anywhere under the hours (the subject reaches up
                            // into the digits): the line goes above the clock instead, over
                            // the date line when that is on top, never onto the subject.
                            val topOfFace =
                                if (showDate && datePos != DATE_BELOW && !dateRect.isEmpty)
                                    min(dateRect.top, line1Base - capH)
                                else line1Base - capH
                            topOfFace - need
                        }
                }
            }
        }
        underTop = under
        // What shows: the digits' block and the date line.
        val half = digitPaint.measureText("00") / 2f
        contentRect.set(width / 2f - half, line1Base - capH - digitSize * 0.01f,
            width / 2f + half, digitsBottom)
        if (showDate && !dateRect.isEmpty) contentRect.union(dateRect)
        if (context.packageName == "com.android.systemui") DepthFaceAnchor.publish(this, underTop)
        val cb = onFaceLayout ?: return
        val sameUnder = (underTop.isNaN() && reportedUnder.isNaN()) || underTop == reportedUnder
        if (sameUnder && contentRect == reportedContent) return
        reportedUnder = underTop
        reportedContent.set(contentRect)
        cb(RectF(contentRect), underTop)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || digitSize == 0f) return
        // IchthysOS: counts only roll over on the next counted touch, and none are counted on
        // the lock screen; from midnight on, the AOD must not show yesterday as "today".
        if (fill == FILL_HEAT && context.packageName == "com.android.systemui") {
            TouchHeat.rollOver(
                calendar.get(java.util.Calendar.YEAR) * 10000 +
                    (calendar.get(java.util.Calendar.MONTH) + 1) * 100 +
                    calendar.get(java.util.Calendar.DAY_OF_MONTH))
        }
        // IchthysOS polish: the room for the weather under the clock, before the shape is fitted.
        updateUnderLine()
        val path = shapedPath(digitsPath())
        val doze = dozeFraction()
        dateRect.setEmpty()
        if (showDate) {
            // Below shaped digits, the line follows their (lowest) bottom.
            if (datePos == DATE_BELOW && path === shaped) {
                val natural = line2Base + digitSize * 0.02f - digitTop
                statusBase = digitTop + natural * max(shownK[0], shownK[1]) + digitSize * 0.03f +
                    statusH - dp(10f)
            } else if (datePos == DATE_BELOW) {
                statusBase = line2Base + digitSize * 0.03f + statusH - dp(10f)
            }
            weatherRect.setEmpty()
            if (details) drawStatus(canvas) else drawDate(canvas, doze)
        } else {
            weatherRect.setEmpty()
        }
        reportWeather()
        paint.shader = null
        // IchthysOS polish: awake and dozing looks cross-fade with the doze amount (SystemUI's
        // doze animation); without one, it is one or the other.
        if (doze < 1f) drawAwake(canvas, path, 1f - doze)
        if (doze > 0f) drawDozing(canvas, path, doze)
        updateAnchors(path)
        hasDrawn = true
    }

    /** Dozing: only the digits' outlines (the day's heat faint inside), at [alpha]. */
    private fun drawDozing(canvas: Canvas, path: Path, alpha: Float) {
        if (fill == FILL_HEAT) drawHeat(canvas, path, 0.45f * alpha)
        paint.shader = null
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = withAlpha(primary, 0.6f * alpha)
        canvas.drawPath(path, paint)
        // The concept's night line under the digits.
        if (details && fill == FILL_HEAT && context.packageName == "com.android.systemui") {
            monoPaint.textSize = sp(11f)
            monoPaint.letterSpacing = 0.1f
            monoPaint.color = withAlpha(primary, 0.6f * alpha)
            monoPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                String.format(Locale.US, "TODAY · %,d TOUCHES", TouchHeat.touches),
                width / 2f, height - dp(4f), monoPaint)
            monoPaint.textAlign = Paint.Align.LEFT
        }
    }

    /** Awake: the digits filled as [fill] says, at [alpha]. */
    private fun drawAwake(canvas: Canvas, path: Path, alpha: Float) {
        paint.style = Paint.Style.FILL
        when (fill) {
            FILL_SOLID, FILL_GRADIENT -> {
                // Solid: cream letters lying in the water (the clock colour the user picked), a
                // soft shadow under them, a faint fall-off towards the bottom. Gradient: pond
                // light fading into the clock colour (the depth look's palette, not the theme
                // accent, so it never turns red).
                paint.color = primary
                paint.setShadowLayer(dp(14f), 0f, dp(6f),
                    Color.argb((90 * alpha).toInt(), 0, 20, 24))
                paint.shader = fillShader()
                // With a shader only the paint's alpha counts: it fades the fill.
                paint.alpha = (Color.alpha(primary) * alpha).toInt()
                canvas.drawPath(path, paint)
                paint.shader = null
                paint.clearShadowLayer()
            }
            else -> {
                paint.color = withAlpha(INK, alpha)
                canvas.drawPath(path, paint)
                drawHeat(canvas, path, alpha)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(0.8f)
                paint.color = withAlpha(primary, 0.22f * alpha)
                canvas.drawPath(path, paint)
            }
        }
    }

    /**
     * Today's touches inside the digits, where they happened on the screen: the heat map is laid
     * over the whole screen and the digits are cut out of it. IchthysOS polish: in previews (no
     * real heat there) the made-up day is laid over the face itself, so every digit shows some.
     */
    private fun drawHeat(canvas: Canvas, path: Path, alpha: Float) {
        val heat = heatBitmap()
        canvas.save()
        canvas.clipPath(path)
        heatPaint.alpha = (255 * alpha).toInt()
        if (context.packageName == "com.android.systemui") {
            getLocationOnScreen(screenPos)
            val screen = resources.displayMetrics
            val sw = max(screen.widthPixels, 1)
            val sh = max(screen.heightPixels, 1)
            heatDst.set(-screenPos[0].toFloat(), -screenPos[1].toFloat(),
                (sw - screenPos[0]).toFloat(), (sh - screenPos[1]).toFloat())
        } else {
            heatDst.set(0f, digitTop, width.toFloat(), line2Base + digitSize * 0.02f)
        }
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
        val liveCells = if (inSystemUi) TouchHeat.liveSnapshot() else null
        val version = if (inSystemUi) TouchHeat.version else -2
        // The doze state is part of what is drawn (see below).
        val key = if (dozingNow) version + 1_000_000_000 else version
        if (liveCells == null) heatBitmap?.let { if (key == heatVersion) return it }
        // A finger on the lock screen: keep drawing frames until its glow has faded.
        if (liveCells != null) postInvalidateOnAnimation()
        val cells = if (inSystemUi) TouchHeat.snapshot() else TouchHeat.sample()
        // Counted heat is scaled against at least a few dozen touches' worth, so a handful doesn't
        // light up like a full day, and stays dimmer than a finger on the screen right now. The
        // previews' made-up day is shown at its full range.
        val peak = if (inSystemUi) max(cells.max(), MIN_PEAK) else max(cells.max(), 0.001f)
        // IchthysOS polish: one pixel array and one bitmap, refilled (a finger on the lock screen
        // redraws this every frame).
        val px = heatPixels ?: IntArray(cells.size).also { heatPixels = it }
        px.fill(0)
        // The depth look's own palette, whatever the theme's accent: pond light, cool and calm.
        val cool = POND_CYAN
        val warm = POND_CREAM
        // Awake, only a finger on the screen glows; dozing, the day's touches show. Previews have
        // no finger: they show their made-up day awake too (the X-ray heat idea was black).
        val showCounted = dozingNow || !inSystemUi
        for (i in cells.indices) {
            // Previews show their made-up day at full strength (it reaches the warm white).
            val countedMax = if (inSystemUi) COUNTED_MAX else 1f
            val counted = if (showCounted) kotlin.math.sqrt(cells[i] / peak) * countedMax else 0f
            val v = if (liveCells != null) max(counted, liveCells[i]) else counted
            if (v < 0.04f) continue
            val c = if (v < 0.6f) cool else blend(cool, warm, (v - 0.6f) / 0.4f)
            px[i] = withAlpha(c or 0xFF000000.toInt(), min(1f, v * 1.15f))
        }
        val b = heatBitmap
            ?: Bitmap.createBitmap(TouchHeat.COLS, TouchHeat.ROWS, Bitmap.Config.ARGB_8888)
                .also { heatBitmap = it }
        b.setPixels(px, 0, TouchHeat.COLS, 0, 0, TouchHeat.COLS, TouchHeat.ROWS)
        heatVersion = if (liveCells != null) -3 else key
        return b
    }

    private var heatPixels: IntArray? = null

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
        )
    }

    /** The calm date line: "Sunday 25 October", centred, in a clean face. */
    private fun drawDate(canvas: Canvas, doze: Float) {
        datePaint.textSize = sp(15f)
        // IchthysOS polish: dims with the doze amount; a tighter, darker shadow so it reads on
        // bright and busy pictures.
        val strength = 0.92f + (0.7f - 0.92f) * doze
        datePaint.color = withAlpha(POND_CREAM, strength)
        datePaint.setShadowLayer(dp(5f), 0f, dp(1f), Color.argb((110 * (1f - doze)).toInt(), 0, 14, 18))
        val text = dateLine()
        datePaint.getFontMetrics(dateMetrics)
        val fm = dateMetrics
        // IchthysOS lock screen weather: after the date, in the same line, the two aligned as
        // one (parts that don't fit the face's width are left out).
        val weather = weatherInDate()
        if (weather != null) {
            weatherDrawer.dozing = dozingNow
            weatherDrawer.strength = strength
            val textW = datePaint.measureText(text)
            val gap = dp(14f)
            val room = width - dp(8f) - textW - gap
            if (weatherDrawer.layout(weatherOptions, weather,
                    datePaint.textSize * weatherOptions.size / 100f, room)) {
                val total = textW + gap + weatherDrawer.width
                val left =
                    when (dateAlign) {
                        ALIGN_START -> dp(4f)
                        ALIGN_END -> width - dp(4f) - total
                        else -> width / 2f - total / 2f
                    }
                datePaint.textAlign = Paint.Align.LEFT
                canvas.drawText(text, left, statusBase, datePaint)
                // A small dot between them, on the middle of the lower-case letters.
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = datePaint.color
                canvas.drawCircle(left + textW + gap / 2f,
                    statusBase + (fm.ascent + fm.descent) / 2f * 0.75f, dp(1.4f), paint)
                weatherDrawer.draw(canvas, left + textW + gap, statusBase, weatherRect)
                dateRect.set(left, statusBase + fm.ascent, left + total, statusBase + fm.descent)
                dateRect.union(weatherRect)
                return
            }
        }
        // IchthysOS editor v2: left, centred or right.
        val textW = datePaint.measureText(text)
        val x =
            when (dateAlign) {
                ALIGN_START -> {
                    datePaint.textAlign = Paint.Align.LEFT
                    dp(4f)
                }
                ALIGN_END -> {
                    datePaint.textAlign = Paint.Align.RIGHT
                    width - dp(4f)
                }
                else -> {
                    datePaint.textAlign = Paint.Align.CENTER
                    width / 2f
                }
            }
        canvas.drawText(text, x, statusBase, datePaint)
        val l =
            when (dateAlign) {
                ALIGN_START -> x
                ALIGN_END -> x - textW
                else -> x - textW / 2f
            }
        dateRect.set(l, statusBase + fm.ascent, l + textW, statusBase + fm.descent)
    }

    /** IchthysOS polish: the date line's text, made once a day (not on every frame). */
    private fun dateLine(): String {
        val day = calendar.get(java.util.Calendar.YEAR) * 1000 +
            calendar.get(java.util.Calendar.DAY_OF_YEAR)
        val locale = Locale.getDefault()
        if (day != dateDay || locale != dateLocale) {
            dateDay = day
            dateLocale = locale
            dateString =
                DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), calendar)
                    .toString()
        }
        return dateString
    }

    private var dateDay = -1
    private var dateLocale: Locale? = null
    private var dateString = ""
    private val dateMetrics = Paint.FontMetrics()

    /** The weather when it goes in this face's date line now, else null. */
    private fun weatherInDate(): LockWeather.Now? {
        val o = weatherOptions
        if (!o.enabled || LockWeather.placeFor(o, true, showDate) != LockWeather.POS_DATE) return null
        return LockWeather.current ?: if (previewWeather != null) LockWeather.sample else null
    }

    private fun reportWeather() {
        val cb = onWeatherPlaced ?: return
        val now = if (weatherRect.isEmpty) null else weatherRect
        val old = reportedWeather
        if (now == null && old == null) return
        if (now != null && old != null && now == old) return
        reportedWeather = now?.let { RectF(it) }
        cb(reportedWeather)
    }

    private val datePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = variable("/system/fonts/Syne-VF.ttf", "'wght' 600")
                ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.04f
        }

    /** Settings.Secure [KEY_DETAILS]: the raw status line instead of the date. */
    private var details = false

    private fun drawStatus(canvas: Canvas) {
        val pad = dp(4f)
        monoPaint.textSize = sp(10.5f)
        monoPaint.letterSpacing = 0.06f
        monoPaint.color = withAlpha(blend(primary, POND_CYAN, 0.25f), 0.9f)
        val base = statusBase
        // Seconds only while they tick: dozing, the face updates once a minute.
        val pattern =
            when {
                dozingNow -> if (is24h()) "HH:mm" else "hh:mm"
                is24h() -> "HH:mm:ss"
                else -> "hh:mm:ss"
            }
        val text =
            dateText().uppercase(Locale.getDefault()) + "  " +
                DateFormat.format(pattern, calendar) + "  " +
                // With the heat fill, today's touches take the temperature's place.
                if (fill == FILL_HEAT && context.packageName == "com.android.systemui")
                    String.format(Locale.US, "%,d TOUCHES", TouchHeat.touches)
                else stats.batteryTemp()?.let { String.format(Locale.US, "%.1f°C", it) } ?: ""
        canvas.drawText(text, pad, base, monoPaint)

        // IchthysOS lock screen weather: outside, after the phone's own numbers, when it fits
        // before the core bars.
        weatherInDate()?.let { w ->
            val extra = weatherDrawer.plain(weatherOptions, w).uppercase(Locale.getDefault())
            if (extra.isNotEmpty()) {
                val x = pad + monoPaint.measureText(text) + dp(10f)
                val ew = monoPaint.measureText("· $extra")
                val bars = Runtime.getRuntime().availableProcessors() * dp(4f) + dp(8f)
                if (x + ew < width - pad - bars) {
                    canvas.drawText("· $extra", x, base, monoPaint)
                    weatherRect.set(x, base - monoPaint.textSize, x + ew, base + dp(2f))
                }
            }
        }

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
                paint.color = if (i == busiest) POND_CYAN else withAlpha(primary, 0.85f)
                canvas.drawRect(x, base - h, x + barW, base, paint)
                x += barW + gap
            }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.6f)
        paint.color = withAlpha(primary, 0.18f)
        val rule = statusBase + dp(6f)
        canvas.drawLine(pad, rule, width - pad, rule, paint)
        // IchthysOS polish: the status line runs the face's width.
        dateRect.set(pad, base - monoPaint.textSize, width - pad, rule + dp(1f))
    }

    /**
     * What the phone is doing, as short "log lines" of numbers it measures itself. Cheap: a few
     * small sysfs reads a second, only while the face ticks; the battery temperature comes from
     * the battery broadcast, kept while ticking ([start] / [stop]).
     */
    private class LiveStats(private val context: Context) {
        private val cores = Runtime.getRuntime().availableProcessors()
        private val maxFreq = LongArray(cores) { readLong(cpuFile(it, "cpuinfo_max_freq")) }
        private val loads = FloatArray(cores)
        private var temp: Float? = null
        private var listening = false

        private val batteryReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    temp =
                        intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                            .takeIf { it != Int.MIN_VALUE }
                            ?.let { it / 10f }
                }
            }

        fun start() {
            if (listening) return
            listening = true
            // Sticky: the current value arrives at once.
            context.registerReceiver(batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        }

        fun stop() {
            if (!listening) return
            listening = false
            context.unregisterReceiver(batteryReceiver)
        }

        fun readBatteryOnce() {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED)?.let { batteryReceiver.onReceive(context, it) }
        }

        fun sampleCores() {
            for (i in 0 until cores) {
                val cur = readLong(cpuFile(i, "scaling_cur_freq"))
                // A core that was offline when the face was made had no maximum to read then.
                if (cur > 0 && maxFreq[i] <= 0) {
                    maxFreq[i] = readLong(cpuFile(i, "cpuinfo_max_freq"))
                }
                loads[i] = if (maxFreq[i] > 0 && cur > 0) cur.toFloat() / maxFreq[i] else 0f
            }
        }

        /** Per core 0..1, empty when the clocks can't be read. */
        fun coreLoads(): FloatArray = if (loads.any { it > 0f }) loads else FloatArray(0)

        /** The last value the battery broadcast gave, null before the first. */
        fun batteryTemp(): Float? = temp

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

        /** Settings.Secure: 1 shows the raw status line (seconds, touches, CPU) over the date. */
        const val KEY_DETAILS = "ichthys_clock_details"

        /**
         * IchthysOS editor v2. Settings.Secure (SystemUI's IchthysDepthRepository reads it too):
         * 0 off, 1 X-ray (subject in front + touch lens; the old "on"), 2 overlap (subject in front),
         * 3 shape (the digits fit around the subject; the subject still drawn in front).
         */
        const val KEY_DEPTH = "ichthys_lockscreen_depth"
        const val DEPTH_OFF = 0
        const val DEPTH_XRAY = 1
        const val DEPTH_OVERLAP = 2
        const val DEPTH_SHAPE = 3

        /**
         * Settings.Secure, how [DEPTH_SHAPE] sizes the digits (height only, letterforms intact):
         * [SHAPE_STRETCH] the whole clock ends just above the subject, [SHAPE_TUCK] a little
         * behind it, [SHAPE_FOLLOW] each digit column from the outline under it.
         */
        const val KEY_SHAPE_STYLE = "ichthys_clock_shape_style"
        const val SHAPE_STRETCH = 0
        const val SHAPE_TUCK = 1
        const val SHAPE_FOLLOW = 2
        private const val EASE_MS = 450f
        private val EASE = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
        /** The shape never reaches below this share of the screen (notifications, shortcuts). */
        private const val MAX_SCREEN_BOTTOM = 0.62f

        /** Settings.Secure: the digits' typeface. */
        const val KEY_FONT = "ichthys_clock_font"
        const val FONT_TALL = 0
        const val FONT_FLEX = 1
        const val FONT_WIDE = 2
        const val FONT_THIN = 3
        const val FONT_SERIF = 4
        const val FONT_MONO = 5
        private val CONDENSED = setOf(FONT_TALL, FONT_FLEX, FONT_THIN)

        /** Settings.Secure: the date / status line above ([DATE_ABOVE]) or below the digits. */
        const val KEY_DATE_POS = "ichthys_clock_date_pos"
        const val DATE_ABOVE = 0
        const val DATE_BELOW = 1

        /** Settings.Secure: the date line's alignment. */
        const val KEY_DATE_ALIGN = "ichthys_clock_date_align"
        const val ALIGN_CENTRE = 0
        const val ALIGN_START = 1
        const val ALIGN_END = 2

        /** Room kept between the shaped digits and the subject. */
        private const val SHAPE_GAP_DP = 16f
        /** IchthysOS polish: room between the weather line under the clock and the subject. */
        private const val UNDER_CLEAR_DP = 8f

        private const val STATUS_DP = 30f
        /** See [heatBitmap]. */
        private const val MIN_PEAK = 12f
        private const val COUNTED_MAX = 0.8f
        /** Share of the face's width "00" takes. */
        private const val WIDTH_SHARE = 0.92f
        /** At most this share of the screen's height per line of digits. */
        private const val MAX_SCREEN_SHARE = 0.27f
        /** One line of digits takes about this many text sizes, for measuring. */
        private const val LINE_STEP = 0.76f
        private val INK = Color.argb(150, 3, 14, 17)
        /** The depth look's palette (the koi concept's pond light). */
        val POND_CYAN = Color.rgb(159, 231, 221)
        val POND_CREAM = Color.rgb(246, 241, 234)

        private val typefaces = HashMap<Int, Typeface>()

        /** [KEY_FONT]'s faces, each with a fallback that is always there. Made once each. */
        fun digitTypeface(font: Int): Typeface =
            synchronized(typefaces) {
                typefaces.getOrPut(font) {
                    when (font) {
                        FONT_FLEX ->
                            variable(ROBOTO_FLEX, "'wdth' 25, 'wght' 1000, 'opsz' 144")
                        FONT_WIDE ->
                            variable(ROBOTO_FLEX, "'wdth' 151, 'wght' 800, 'opsz' 144")
                        FONT_THIN ->
                            variable(BIG_SHOULDERS, "'wght' 200")
                                ?: variable(ROBOTO_FLEX, "'wdth' 25, 'wght' 200, 'opsz' 144")
                        FONT_SERIF -> Typeface.create("serif", Typeface.BOLD)
                        FONT_MONO -> Typeface.create("monospace", Typeface.BOLD)
                        else ->
                            // Big Shoulders Display Black (IchthysDepth ships it), else Roboto
                            // Flex at its narrowest.
                            variable(BIG_SHOULDERS, "'wght' 900")
                                ?: variable(ROBOTO_FLEX, "'wdth' 25, 'wght' 1000, 'opsz' 144")
                    } ?: Typeface.create("sans-serif-condensed", Typeface.BOLD)
                }
            }

        private const val BIG_SHOULDERS = "/system/fonts/BigShouldersDisplay-VF.ttf"
        private const val ROBOTO_FLEX = "/system/fonts/RobotoFlex-Regular.ttf"

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
 * IchthysOS polish: where SystemUI's Depth face wants the weather line under the clock to start
 * ([underTop]: px from the face's own top, before the host's scale; NaN: under the whole face as
 * before). The face publishes it after each draw that moved it; SystemUI's LockWeatherView
 * follows it, so the line sits under the digits you can see (Shape, a subject in front) instead
 * of under the face's full box. Only one face publishes at a time; a detached one clears its own.
 */
object DepthFaceAnchor {
    @Volatile
    var underTop = Float.NaN
        private set

    private var owner: Any? = null
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun publish(from: Any, value: Float) {
        val same = (value.isNaN() && underTop.isNaN()) || value == underTop
        if (same && owner === from) return
        owner = from
        underTop = value
        if (!same) listeners.forEach { it() }
    }

    fun clear(from: Any) {
        if (owner !== from) return
        owner = null
        if (!underTop.isNaN()) {
            underTop = Float.NaN
            listeners.forEach { it() }
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

    /**
     * IchthysOS: starts the day over once [today] has come, without waiting for a touch. Nothing
     * before the counts are loaded (day 0), so it never races SystemUI's load.
     */
    @Synchronized
    fun rollOver(today: Int) {
        if (day != 0 && day != today) reset(today)
    }

    @Synchronized fun snapshot(): FloatArray = cells.copyOf()

    /**
     * Touches on the lock screen itself, shown live and never counted or saved: each one glows
     * where it is and fades within about a second ([LIVE_FADE_MS]).
     */
    private val live = FloatArray(COLS * ROWS)
    private var liveAt = 0L

    /** A finger on the lock screen at ([fx], [fy]) (fractions of the screen). */
    @Synchronized
    fun pulse(fx: Float, fy: Float) {
        fadeLive()
        splat(live, fx, fy, 0.6f)
        for (i in live.indices) live[i] = min(live[i], 1f)
        changed()
    }

    /** The live layer, faded to now; null once it has gone. */
    @Synchronized
    fun liveSnapshot(): FloatArray? {
        fadeLive()
        return if (live.any { it > 0.02f }) live.copyOf() else null
    }

    private fun fadeLive() {
        val now = android.os.SystemClock.uptimeMillis()
        val k = kotlin.math.exp(-(now - liveAt) / LIVE_FADE_MS.toFloat())
        liveAt = now
        for (i in live.indices) live[i] *= k
    }

    private fun splat(into: FloatArray, fx: Float, fy: Float, w: Float) {
        val cx = fx * COLS - 0.5f
        val cy = fy * ROWS - 0.5f
        val r = (SIGMA * 3).toInt() + 1
        for (y in max(0, cy.toInt() - r)..min(ROWS - 1, cy.toInt() + r)) {
            for (x in max(0, cx.toInt() - r)..min(COLS - 1, cx.toInt() + r)) {
                val dx = x - cx
                val dy = y - cy
                into[y * COLS + x] += w * kotlin.math.exp(-(dx * dx + dy * dy) / (2 * SIGMA * SIGMA))
            }
        }
    }

    private const val LIVE_FADE_MS = 450L

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
        // IchthysOS polish: previews lay this over the digits, and in the X-ray look the
        // minutes sit behind the subject, so the hours line carries a warm spot of its own (the
        // X-ray heat idea read as dark, empty digits when all the heat was low down).
        splat(0.32f, 0.24f, 1f)
        splat(0.7f, 0.32f, 0.75f)
        splat(0.58f, 0.72f, 0.9f)
        splat(0.25f, 0.82f, 0.5f)
        splat(0.82f, 0.88f, 0.4f)
        return out
    }
}
