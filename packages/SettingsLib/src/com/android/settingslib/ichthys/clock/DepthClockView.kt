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
import android.graphics.BitmapShader
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
 *   status) line above or below the digits, left, centred or right. [FONT_SQUARE] has no
 *   typeface: its digits are drawn ([SquareDigits]) and measured through [pairWidth] /
 *   [capHeight] / [rowStep], so everything built on the glyph paths works the same for it.
 */
class DepthClockView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : IchthysClockFaceView(context, attrs) {

    override val preferredWidthDp: Float = 360f
    override val preferredHeightDp: Float = 520f

    private var fill = FILL_SOLID
    /** IchthysOS: [KEY_GLASS_FROST] and [KEY_GLASS_OPACITY], read with the fill. */
    private var glassFrost = 0
    private var glassOpacity = -1

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
    /** IchthysOS: the depth fit's sideways move of the whole clock (face px), eased like [shownK]. */
    private var lastNoFit: String? = null
    private var targetShift = 0f
    private var fromShift = 0f
    private var shownShift = 0f
    /**
     * IchthysOS: One UI's layout beside the subject ([sidePlan]): each glyph's own height on top
     * of the even factor (hours left/right, then minutes; 1: even), target / from / shown, and
     * the side it took (-1 left, 1 right, 0 none), which the date line aligns to.
     */
    private val targetV = floatArrayOf(1f, 1f, 1f, 1f)
    private val fromV = floatArrayOf(1f, 1f, 1f, 1f)
    private val shownV = floatArrayOf(1f, 1f, 1f, 1f)
    private val shapedForV = FloatArray(4) { Float.NaN }
    private var sideLayout = 0
    private val shapedBounds = RectF()
    private var easeStart = 0L
    private var shapeShown = false
    /** Drawn at least once: from then on shape changes ease in (the first draw is placed at once). */
    private var hasDrawn = false
    /** Extra height the stretched digits need below the natural face, measured in. */
    private var stretchExtra = 0f
    private val shapeMatrix = android.graphics.Matrix()
    /**
     * IchthysOS: what [glyphs] hold (digit and advance box left, per glyph), so the Square digits
     * can be drawn again at a Shape height instead of being scaled ([easedGlyphs]); [glyphVersion]
     * counts rebuilds, and [shaped] was last made from [shapedFor] at [shapedVersion].
     */
    private val glyphDigit = IntArray(4)
    private val glyphLeft = FloatArray(4)
    private var glyphVersion = 0
    private val shapedFor = floatArrayOf(Float.NaN, Float.NaN)
    private var shapedVersion = -1
    private val stretchedGlyph = Path()
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
        for (key in arrayOf(KEY_DEPTH, KEY_SHAPE_STYLE, KEY_FONT, KEY_DATE_POS, KEY_DATE_ALIGN,
                KEY_GLASS_FROST, KEY_GLASS_OPACITY)) {
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
        ClockBackdrop.addListener(heatListener)
        updateTicking()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        DepthFaceAnchor.clear(this)
        stats.stop()
        TouchHeat.removeListener(heatListener)
        ClockBackdrop.removeListener(heatListener)
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

    /**
     * IchthysOS: false while the depth fit has stepped back (the clock couldn't sit above the
     * subject without shrinking too far): the subject must not be drawn over the clock then.
     * SystemUI hears it through [DepthFaceAnchor], a preview through [onDepthApplies].
     */
    var depthApplies = true
        private set
    var onDepthApplies: ((Boolean) -> Unit)? = null

    private fun setDepthApplies(applies: Boolean) {
        if (depthApplies == applies) return
        depthApplies = applies
        if (context.packageName == "com.android.systemui") {
            DepthFaceAnchor.publishDepthApplies(this, applies)
        }
        onDepthApplies?.let { cb -> post { cb(applies) } }
    }

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
        glassFrost = secure(KEY_GLASS_FROST, 0)
        glassOpacity = secure(KEY_GLASS_OPACITY, -1)
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
        val half = pairWidth(digitPaint) / 2f
        out.set(width / 2f - half, digitTop, width / 2f + half, line2Base + digitSize * 0.02f)
    }

    /** IchthysOS: true for [FONT_SQUARE], whose digits are [SquareDigits] rather than text. */
    private val squareFont: Boolean
        get() = font == FONT_SQUARE

    /** A pair's width at [paint]'s text size, as the digits are laid out (two [digitCell]s). */
    private fun pairWidth(paint: Paint): Float =
        if (squareFont) SquareDigits.advance(paint.textSize) * 2f else digitCell(paint) * 2f

    private val digitStrings = Array(10) { it.toString() }

    /**
     * IchthysOS: tabular figures, as the flagships set their clocks. Every digit gets the widest
     * digit's advance and sits centred in it, so the two rows line up as one grid and nothing
     * shifts sideways as the time changes (a proportional 1 left "19" over "00" misaligned).
     */
    private fun digitCell(paint: Paint): Float {
        var w = 0f
        for (d in digitStrings) w = max(w, paint.measureText(d))
        return w
    }

    /** Where digit [i] of [text] starts with LEFT alignment: centred in its cell from [left]. */
    private fun cellLeft(paint: Paint, text: String, i: Int, left: Float, cell: Float): Float =
        left + i * cell + (cell - paint.measureText(text, i, i + 1)) / 2f

    /** The digits' ink height ("00"'s bounds) at [paint]'s text size. */
    private fun capHeight(paint: Paint): Float {
        if (squareFont) return SquareDigits.capHeight(paint.textSize)
        paint.getTextBounds("00", 0, 2, bounds)
        return bounds.height().toFloat()
    }

    /**
     * From the hours' baseline to the minutes': a cap height and a hair, so the lines sit tight
     * like one block. IchthysOS: Square keeps the same gap between its rows as between its
     * digits, an even 2 x 2 block.
     */
    private fun rowStep(cap: Float, size: Float): Float =
        if (squareFont) cap + size * SquareDigits.GAP else cap * 1.04f

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
        val cap = capHeight(measurePaint)
        // Plus a line under the digits for the night's touch count.
        val h = (statusHeight() + size * 0.04f + cap + rowStep(cap, size) + size * 0.03f + dp(18f) +
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
        val textW = max(pairWidth(measurePaint), 1f)
        val byWidth = w * WIDTH_SHARE / textW * 100f
        val byScreen = resources.displayMetrics.heightPixels * MAX_SCREEN_SHARE / LINE_STEP
        return min(byWidth, byScreen)
    }

    private fun layoutFace(w: Int, h: Int) {
        if (w <= 0) return
        statusH = statusHeight()
        digitSize = digitSizeFor(w)
        digitPaint.textSize = digitSize
        // Baselines a cap height apart, plus a little, so the lines sit tight like one block.
        val cap = capHeight(digitPaint)
        capH = cap
        // IchthysOS editor v2: the date / status line above the digits, or below them.
        digitTop = if (datePos == DATE_BELOW) dp(4f) else statusH
        line1Base = digitTop + cap + digitSize * 0.04f
        line2Base = line1Base + rowStep(cap, digitSize)
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

    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frostMatrix = android.graphics.Matrix()
    private var frostShader: BitmapShader? = null
    private var frostFor: android.graphics.Bitmap? = null
    /** The depth fit's sideways move this frame (the canvas is translated by it). */
    private var drawShift = 0f

    /**
     * IchthysOS: the frost ([ClockBackdrop.frost]) through [path], mapped from the screen to
     * this face (its place, scale and the fit's move); false when there is none to draw.
     */
    private fun drawFrost(canvas: Canvas, path: Path, alpha: Float): Boolean {
        val frost = ClockBackdrop.frost ?: return false
        val place = placement() ?: return false
        if (place[2] <= 0f) return false
        if (frost !== frostFor) {
            frostShader = BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
            frostFor = frost
        }
        val shader = frostShader ?: return false
        frostMatrix.setScale(ClockBackdrop.frostW.toFloat() / frost.width,
            ClockBackdrop.frostH.toFloat() / frost.height)
        frostMatrix.postTranslate(-place[0], -place[1])
        frostMatrix.postScale(1f / place[2], 1f / place[2])
        frostMatrix.postTranslate(-drawShift, 0f)
        shader.setLocalMatrix(frostMatrix)
        // Lifted like light through frosted glass, and dimmed with Living Sky's grade.
        val m = (ClockBackdrop.frostDim() * 1.12f).coerceIn(0.06f, 1.7f)
        val c = (255 * m.coerceAtMost(1f)).toInt()
        val add = (255 * ((m - 1f).coerceAtLeast(0f) * 0.35f + 0.06f)).toInt().coerceIn(0, 120)
        frostPaint.colorFilter = android.graphics.LightingColorFilter(Color.rgb(c, c, c),
            Color.rgb(add, add, add))
        frostPaint.shader = shader
        frostPaint.alpha = (255 * alpha).toInt()
        canvas.drawPath(path, frostPaint)
        frostPaint.shader = null
        return true
    }

    // IchthysOS: built defensively: the shader compiles on the device at runtime, and a failure
    // must fall back to the frosted veil, never take SystemUI down.
    private val clearGlass: android.graphics.RuntimeShader? by lazy {
        try {
            android.graphics.RuntimeShader(CLEAR_GLASS)
        } catch (e: RuntimeException) {
            android.util.Log.w("DepthClockView", "clear glass shader failed", e)
            null
        }
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glassMask: Bitmap? = null
    private var glassMaskShader: BitmapShader? = null
    private val glassMaskKey = RectF()
    private var glassMaskFor = -1L
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pathBounds = RectF()
    private val glassMap = android.graphics.Matrix()
    private val glassGlobal = android.graphics.Matrix()
    private val glassValues = FloatArray(9)
    private val glassColumns = FloatArray(9)

    /**
     * IchthysOS: clear glass (Liquid Glass): each digit a flat pane of real glass over the picture
     * behind ([ClockBackdrop.frost], a quarter of the screen). The face shows the scene true; only
     * a narrow rounded edge refracts it, reflects the room (Fresnel) and catches the light, with a
     * fine light line round the whole outline. Apple's slider position comes from the picture: a
     * touch of milk over a bright one, smoke over a dark one, a faint glow over near black. False
     * when there is no picture or the shader could not be built.
     */
    private fun drawClearGlass(canvas: Canvas, path: Path, alpha: Float): Boolean {
        val frost = ClockBackdrop.frost ?: return false
        val place = placement() ?: return false
        if (place[2] <= 0f || width <= 0 || height <= 0) return false
        if (frost !== frostFor) {
            frostShader = BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
            frostFor = frost
        }
        val picture = frostShader ?: return false
        val mask = glassMask(path) ?: return false
        // Face px (the fit's move included) -> the screen, the face's turn included (clear glass
        // shows the scene: it must sit exactly over it) -> the frost's px.
        val toFrost = frost.width.toFloat() / max(1, ClockBackdrop.frostW)
        val map = glassMap
        map.setTranslate(drawShift, 0f)
        val pp = previewPlacement
        if (pp != null) {
            map.postScale(pp[2], pp[2])
            map.postTranslate(pp[0], pp[1])
            if (pp.size >= 6 && pp[3] != 0f) map.postRotate(pp[3], pp[4], pp[5])
        } else {
            val global = glassGlobal
            global.reset()
            transformMatrixToGlobal(global)
            map.postConcat(global)
        }
        map.postScale(toFrost, toFrost)
        map.getValues(glassValues)
        // Column-major for the shader.
        for (r in 0 until 3) for (c in 0 until 3) glassColumns[c * 3 + r] = glassValues[r * 3 + c]
        val m = (ClockBackdrop.frostDim() * 1.06f).coerceIn(0.06f, 1.7f)
        val sh = clearGlass ?: return false
        sh.setInputShader("picture", picture)
        sh.setInputShader("mask", mask)
        sh.setFloatUniform("toFrost", glassColumns)
        sh.setFloatUniform("maskMul", MASK_SCALE)
        sh.setFloatUniform("bend", dp(9f))
        sh.setFloatUniform("dim", m.coerceAtMost(1f))
        sh.setFloatUniform("add", (m - 1f).coerceAtLeast(0f) * 0.35f)
        // Apple's slider position from the picture's lightness: milk over a bright one, smoke
        // over a dark one, and a faint glow when it is nearly black (smoke would erase the glass).
        val luma = ClockBackdrop.luma.coerceIn(0f, 1f)
        val lstar = if (luma > 0.008856f) 116f * Math.cbrt(luma.toDouble()).toFloat() - 16f else 903.3f * luma
        val frosted = glassFrost != 0
        val milk = when {
            glassOpacity in 0..100 -> -0.4f + glassOpacity / 100f * 0.88f      // smoky .. milky
            lstar < 22f -> if (frosted) 0.16f else 0.12f
            frosted -> ((lstar - 12f) / 95f).coerceIn(0.05f, 0.38f)
            else -> ((lstar - 32f) / 60f).coerceIn(-0.4f, 0.18f)
        }
        sh.setFloatUniform("milk", milk)
        // Frosted: what is seen through the pane is blurred (in the frost's own px).
        sh.setFloatUniform("frost", if (frosted) dp(6f) * glassValues[0].coerceAtLeast(0.05f) else 0f)
        sh.setFloatUniform("lightDir", -0.6f, -0.8f)
        sh.setFloatUniform("alpha", alpha)
        // Over a dark picture the glass takes the picture's accent, pale at the top of the
        // digits to full at their bottom.
        val acc = ClockBackdrop.accent
        val dark = if (acc == 0) 0f else ((0.12f - ClockBackdrop.luma) / 0.08f).coerceIn(0f, 1f)
        sh.setFloatUniform("tint", Color.red(acc) / 255f, Color.green(acc) / 255f,
            Color.blue(acc) / 255f)
        sh.setFloatUniform("tintAmt", dark * 0.85f)
        path.computeBounds(pathBounds, true)
        sh.setFloatUniform("tintSpan", pathBounds.top, max(pathBounds.bottom, pathBounds.top + 1f))
        // A soft shadow under the glass, lifting it off the picture.
        shadowPaint.color = Color.argb((60 * alpha).toInt(), 0, 8, 16)
        shadowPaint.maskFilter = shadowBlur
        canvas.save()
        canvas.translate(0f, dp(4f))
        canvas.drawPath(path, shadowPaint)
        canvas.restore()
        clearPaint.shader = sh
        canvas.drawPath(path, clearPaint)
        clearPaint.shader = null
        return true
    }

    private val shadowBlur by lazy {
        android.graphics.BlurMaskFilter(dp(9f), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    /**
     * The digits, blurred a little ([MASK_SCALE] of the face): the glass shader reads the edges'
     * direction and nearness from it. Made again only when the digits changed.
     */
    private fun glassMask(path: Path): BitmapShader? {
        path.computeBounds(pathBounds, true)
        val key = shownTime.toLong() * 31 + glyphVersion
        if (pathBounds == glassMaskKey && key == glassMaskFor) glassMaskShader?.let { return it }
        val w = max(1, (width * MASK_SCALE).toInt() + 1)
        val h = max(1, (height * MASK_SCALE).toInt() + 1)
        var bmp = glassMask
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            glassMask = bmp
        }
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        c.scale(MASK_SCALE, MASK_SCALE)
        maskPaint.maskFilter = android.graphics.BlurMaskFilter(dp(4f),
            android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawPath(path, maskPaint)
        glassMaskKey.set(pathBounds)
        glassMaskFor = key
        glassMaskShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        return glassMaskShader
    }

    private var glassVeil: Shader? = null
    private var glassVeilKey = -1L
    private var glassEdge: Shader? = null
    private var glassEdgeKey = -1L

    /**
     * IchthysOS: the glass's veil, top to bottom of the digits: a pale white (a breath of the
     * clock colour in it) that thins out downwards; thin over the frost, dense without it.
     */
    private fun glassShader(bright: Float): Shader {
        // Over a dark picture frosted glass glows: the veil thickens (linear luma behind it).
        val dark = ((0.2f - ClockBackdrop.luma) / 0.18f).coerceIn(0f, 1f)
        val key = (digitTop.toLong() shl 40) or (line2Base.toLong() shl 16) or
            ((bright * 20).toLong() shl 8) or ((dark * 10).toLong() shl 4) or
            (primary.toLong() and 0xf)
        glassVeil?.let { if (key == glassVeilKey) return it }
        val tint = blendColours(Color.WHITE, primary, 0.16f)
        // Over the frost (bright < 0): a thin white veil, the picture does the rest. Without it:
        // a denser frosted white that holds even over a bright picture.
        val f = bright < 0f
        val top = withAlpha(tint, (if (f) 0.46f else 0.9f) + dark * 0.28f)
        val mid = withAlpha(tint, (if (f) 0.36f else 0.74f) + dark * 0.34f)
        val bottom = withAlpha(tint, (if (f) 0.28f else 0.6f) + dark * 0.4f)
        // Per row: each row of digits is its own pane, so the gradient repeats with the row
        // pitch instead of running over both (the minutes came out a darker grey).
        val pitch = max(1f, line2Base - line1Base)
        val shader = LinearGradient(0f, digitTop, 0f, digitTop + pitch,
            intArrayOf(top, mid, bottom), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.REPEAT)
        glassVeil = shader
        glassVeilKey = key
        return shader
    }

    /** The light edge: bright along the top of each row, gone by its bottom. */
    private fun glassEdgeShader(): Shader {
        val key = (digitTop.toLong() shl 32) or line2Base.toLong()
        glassEdge?.let { if (key == glassEdgeKey) return it }
        // Repeats with the row pitch: bright at the top of each row only.
        val pitch = max(1f, line2Base - line1Base)
        val shader = LinearGradient(0f, digitTop, 0f, digitTop + pitch,
            intArrayOf(Color.argb(200, 255, 255, 255), Color.argb(60, 255, 255, 255),
                Color.argb(18, 255, 255, 255)),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.REPEAT)
        glassEdge = shader
        glassEdgeKey = key
        return shader
    }

    /** [a] to [b] by [t], channel by channel, alpha included. */
    private fun blendColours(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * u).toInt()
        return Color.argb(ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)),
            ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
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
            if (squareFont) {
                // IchthysOS: drawn, not set; centred like the text (its advances, side bearings
                // included).
                val left = width / 2f - pairWidth(digitPaint) / 2f
                SquareDigits.textPath(hh, 0, 2, left, line1Base, digitSize, digits)
                SquareDigits.textPath(mm, 0, 2, left, line2Base, digitSize, digits)
                return digits
            }
            val p = Path()
            glyphPaint.set(digitPaint)
            glyphPaint.textAlign = Paint.Align.LEFT
            val cell = digitCell(glyphPaint)
            val left = width / 2f - cell
            for ((text, base) in arrayOf(hh to line1Base, mm to line2Base)) {
                for (i in 0..1) {
                    p.reset()
                    glyphPaint.getTextPath(text, i, i + 1, cellLeft(glyphPaint, text, i, left, cell),
                        base, p)
                    digits.addPath(p)
                }
            }
        }
        return digits
    }

    /**
     * IchthysOS depth fit, as One UI does it: the digits are never distorted. With depth on and a
     * subject (or, in any mode, a skyline: DepthSource), the whole clock is scaled evenly so its
     * bottom stays above the outline (16 dp clear, and the weather line under the clock:
     * [underLineH]); a person may hide its lowest [AdaptiveShape.TUCK] (the "behind the head"
     * look), a skyline nothing. When it would have to shrink below [FIT_MIN_K] the subject is
     * where the time belongs: depth steps back ([depthApplies] false: no cut-out over the clock)
     * and the clock stands whole in front. The factor is worked out again only when the time, the
     * outline, the style or this face's place on the screen changes, then eased in (450 ms; at
     * once while dozing or on the first draw).
     */
    private fun shapedPath(natural: Path): Path {
        val profile = previewOutline ?: SubjectOutline.current
        val skyline = profile?.skyline == true
        val fit = depthMode != DEPTH_OFF || skyline
        val place = if (fit && profile != null) placement() else null
        if (place == null || place[2] <= 0f || profile == null) {
            if (context.packageName == "com.android.systemui") {
                val why = "mode $depthMode profile ${profile != null} skyline $skyline " +
                    "place ${place?.let { "${it[0]},${it[1]} x${it[2]}" }}"
                if (why != lastNoFit) {
                    lastNoFit = why
                    android.util.Log.i("IchthysDepthFit", "no fit: $why")
                }
            }
            setDepthApplies(true)
            sideLayout = 0
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
            // IchthysOS: not the time: the fit is worked out for the widest digits ([fitColumns]),
            // so the clock keeps its place while the minutes change.
            inputsChanged(shapeInputs, 0, (left / 8).toInt(), (top / 8).toInt(),
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
            // The largest even size that sits above the outline, at the clock's own place or
            // moved sideways as little as it takes (One UI moves the clock beside the subject).
            val tuck = if (skyline) 0f else AdaptiveShape.TUCK * blockH
            fitColumns()
            val midX = (fitCols[0] + fitCols[3]) / 2f
            val half = (fitCols[3] - fitCols[0]) / 2f
            val margin = dp(4f)
            fun evenShift(k: Float): Float {
                val hw = half * k
                val minS = min(0f, margin + hw - midX)
                val maxS = max(0f, width - margin - hw - midX)
                for (s in FIT_SHIFTS) {
                    val shift = if (s < 0f) -s * minS else s * maxS
                    if (digitTop + blockH * k <= limit(midX + shift - hw, midX + shift + hw) + tuck) {
                        return shift
                    }
                }
                return Float.NaN
            }
            // 1. Full size, at its own place or moved beside the subject.
            // 2. A person or a thing in the way (not a skyline): One UI's layout beside it, each
            //    minute digit as tall as the room in its own column ([sidePlan]).
            // 3. Smaller, evenly, down to [FIT_MIN_K]; else depth steps back.
            var bestK = Float.NaN
            var bestShift = 0f
            var v: FloatArray? = null
            var side = 0
            val full = if (blockH > 0f) evenShift(1f) else Float.NaN
            if (!full.isNaN()) {
                bestK = 1f
                bestShift = full
            } else {
                val plan = if (skyline || blockH <= 0f) null else sidePlan(midX, half, left, scale, pw, ::limit)
                if (plan != null) {
                    bestK = plan[0]
                    bestShift = plan[1]
                    side = plan[2].toInt()
                    v = floatArrayOf(plan[3], plan[4], plan[5], plan[6])
                } else {
                    var k = 1f - 0.05f
                    while (blockH > 0f && k >= FIT_MIN_K - 0.001f) {
                        val s = evenShift(k)
                        if (!s.isNaN()) {
                            bestK = k
                            bestShift = s
                            break
                        }
                        k -= 0.05f
                    }
                }
            }
            if (context.packageName == "com.android.systemui") {
                android.util.Log.i("IchthysDepthFit", "fit: k ${bestK} shift ${bestShift} " +
                    "side $side v ${v?.joinToString()} " +
                    "block ${digitTop}+${blockH} room ${limit(midX - half, midX + half) + tuck} " +
                    "skyline $skyline mode $depthMode place ${left},${top} x${scale}")
            }
            if (bestK.isNaN()) {
                setDepthApplies(false)
                retarget(1f, 1f, 0f)
            } else {
                setDepthApplies(true)
                retarget(bestK, bestK, bestShift, v)
            }
            sideLayout = if (bestK.isNaN()) 0 else side
            val extra = max(0f, targetBottom() - (line2Base + digitSize * 0.02f))
            // Grows at once (the taller digits need the room now), shrinks once they have eased.
            if (extra > stretchExtra + 1f) {
                stretchExtra = extra
                requestLayout()
            }
        }
        if (easeStart == 0L && stretchExtra > 0f) {
            val extra = max(0f, targetBottom() - (line2Base + digitSize * 0.02f))
            if (kotlin.math.abs(extra - stretchExtra) > 1f) {
                stretchExtra = extra
                requestLayout()
            }
        }
        return easedGlyphs()
    }

    /** New target factors: eased from where the digits are now, or at once before the first draw. */
    private fun retarget(k0: Float, k1: Float, shift: Float = 0f, v: FloatArray? = null) {
        val sameV = (0 until 4).all { targetV[it] == (v?.get(it) ?: 1f) }
        if (shapeShown && k0 == targetK[0] && k1 == targetK[1] && shift == targetShift && sameV) return
        targetK[0] = k0
        targetK[1] = k1
        targetShift = shift
        for (i in 0 until 4) targetV[i] = v?.get(i) ?: 1f
        if (!hasDrawn || dozingNow) {
            shownK[0] = k0
            shownK[1] = k1
            shownShift = shift
            targetV.copyInto(shownV)
            easeStart = 0L
        } else {
            fromK[0] = shownK[0]
            fromK[1] = shownK[1]
            fromShift = shownShift
            shownV.copyInto(fromV)
            easeStart = android.os.SystemClock.uptimeMillis()
        }
        shapeShown = true
    }

    /** The turn the face is drawn at on the screen (its own and its hosts'; the preview's). */
    private fun turnDegrees(): Float {
        previewPlacement?.let { return if (it.size >= 6) it[3] else 0f }
        var r = 0f
        var v: android.view.View? = this
        while (v != null) {
            r += v.rotation
            v = v.parent as? android.view.View
        }
        return r
    }

    /**
     * IchthysOS: the columns the depth fit plans with: those of the widest pair ("00"), centred,
     * the same whatever the time shows. Fitting the digits actually shown moved the clock each
     * time the minute changed (07:45 and 12:00 differ in width).
     */
    private val fitCols = FloatArray(4)

    private fun fitColumns() {
        val w = pairWidth(digitPaint)
        val mid = width / 2f
        fitCols[0] = mid - w / 2f
        fitCols[1] = mid
        fitCols[2] = mid
        fitCols[3] = mid + w / 2f
    }

    /** The minutes' ink top in the natural face (the hours' and the gap above it unscaled). */
    private fun minuteTop(): Float = line2Base - capH

    /**
     * Where the minutes' top goes with the hours at even factor [k] and height factor [vh]: the
     * hours keep their place at the face's top, the gap between the rows scales evenly.
     */
    private fun minuteTopAt(k: Float, vh: Float): Float =
        digitTop + (line1Base - digitTop) * k * vh + (minuteTop() - line1Base) * k

    /** The target layout's lowest point (face px, unshifted). */
    private fun targetBottom(): Float {
        val k = max(targetK[0], targetK[1])
        val vh = max(targetV[0], targetV[1])
        val vm = max(targetV[2], targetV[3])
        return minuteTopAt(k, vh) + (capH * vm + digitSize * 0.02f) * k
    }

    /**
     * IchthysOS: One UI's depth clock beside the subject (the owner's reference: their portrait
     * on a Samsung). The clock goes to the side with more room, a little smaller; every digit
     * keeps its width and only its height changes: the hours an even pair, each minute digit as
     * tall as the room above the subject in its own column ([SIDE_MIN_V] .. [SIDE_MAX_V] of its
     * height), so one over a raised hand stays short and one in the free space reaches down
     * beside it. [limit] is the lowest y (this face) a column x0..x1 may reach. Returns
     * k, shift, side, then the four height factors; null when neither side has room.
     */
    private fun sidePlan(midX: Float, half: Float, left: Float, scale: Float, screenW: Float,
            limit: (Float, Float) -> Float): FloatArray? {
        // Kept off the screen's edge (not the face's, which may be offset or wider): a margin,
        // and more for a turned clock, whose corners and date swing out (face px).
        val turn = Math.toRadians(turnDegrees().toDouble())
        val turnOut = kotlin.math.abs(kotlin.math.sin(turn)).toFloat()
        var k = SIDE_K
        while (k >= SIDE_MIN_K - 0.001f) {
            val hw = half * k
            val margin = dp(SIDE_MARGIN_DP) / scale +
                turnOut * (line2Base + digitSize * 0.02f - digitTop) * k * 0.6f
            var best: FloatArray? = null
            for (side in intArrayOf(1, -1)) {
                // Where the clock's outer edge goes on the screen, as a move of the face's middle;
                // and never past this face's own edges (what is drawn outside them is clipped:
                // the right column went missing), with room for the glass's shadow.
                val inside = dp(14f)
                val shift =
                    if (side > 0) min((screenW - left) / scale - margin, width - inside) - hw - midX
                    else max(-left / scale + margin, inside) + hw - midX
                if (side > 0 && shift <= 0f || side < 0 && shift >= 0f) continue
                fun x(c: Float) = midX + (c - midX) * k + shift
                // The hours whole above the subject.
                val hoursBottom = digitTop + (line1Base + digitSize * 0.02f - digitTop) * k
                if (hoursBottom > limit(x(fitCols[0]), x(fitCols[3]))) continue
                val top = minuteTopAt(k, 1f)
                val capK = capH * k
                val vl = (limit(x(fitCols[0]), x(fitCols[1])) - top - digitSize * 0.02f * k) / capK
                val vr = (limit(x(fitCols[2]), x(fitCols[3])) - top - digitSize * 0.02f * k) / capK
                if (vl < SIDE_MIN_V || vr < SIDE_MIN_V) continue
                val plan = floatArrayOf(k, shift, side.toFloat(), 1f, 1f,
                    min(vl, SIDE_MAX_V), min(vr, SIDE_MAX_V))
                if (best == null || plan[5] + plan[6] > best[5] + best[6]) best = plan
            }
            if (best != null) return best
            k -= 0.05f
        }
        return null
    }

    /**
     * The glyphs at the eased factors (one more frame asked for while easing). IchthysOS: the
     * Square digits are drawn again at their new height, their strokes keeping their weight
     * (only the verticals grow), where a font's glyphs are scaled. Made again only when the
     * factors or the glyphs changed.
     */
    private fun easedGlyphs(): Path {
        buildGlyphs()
        if (easeStart != 0L) {
            val t = ((android.os.SystemClock.uptimeMillis() - easeStart) / EASE_MS).coerceIn(0f, 1f)
            val e = EASE.getInterpolation(t)
            shownK[0] = fromK[0] + (targetK[0] - fromK[0]) * e
            shownK[1] = fromK[1] + (targetK[1] - fromK[1]) * e
            shownShift = fromShift + (targetShift - fromShift) * e
            for (i in 0 until 4) shownV[i] = fromV[i] + (targetV[i] - fromV[i]) * e
            if (t >= 1f) easeStart = 0L else postInvalidateOnAnimation()
        }
        if (shownK[0] == shapedFor[0] && shownK[1] == shapedFor[1] &&
            (0 until 4).all { shownV[it] == shapedForV[it] } &&
            shapedVersion == glyphVersion) return shaped
        shapedFor[0] = shownK[0]
        shapedFor[1] = shownK[1]
        shownV.copyInto(shapedForV)
        shapedVersion = glyphVersion
        shaped.reset()
        // IchthysOS: evenly, about the top middle of the digit block; beside the subject each
        // glyph's height on top ([sidePlan]), its width kept. The minutes follow the hours down.
        val midX = (columns[0] + columns[3]) / 2f
        val mTop = minuteTop()
        val vh = max(shownV[0], shownV[1])
        for (i in 0 until 4) {
            val k = shownK[i % 2]
            val v = shownV[i]
            val minutes = i >= 2
            val anchor = if (minutes) mTop else digitTop
            var glyph = glyphs[i]
            var sy = k * v
            if (squareFont && v != 1f) {
                // Drawn again at the new height, the strokes keeping their weight.
                val top = if (minutes) mTop else line1Base - capH
                SquareDigits.glyph(glyphDigit[i], glyphLeft[i], top, top + capH * v, digitSize,
                    stretchedGlyph)
                glyph = stretchedGlyph
                sy = k
            }
            shapeMatrix.setScale(k, sy, midX, anchor)
            if (minutes) shapeMatrix.postTranslate(0f, minuteTopAt(k, vh) - mTop)
            shaped.addPath(glyph, shapeMatrix)
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
        glyphVersion++
        for ((row, pair) in listOf(hh to line1Base, mm to line2Base).withIndex()) {
            val text = pair.first
            // Tabular: both rows are the same two cells wide, so they line up as one grid.
            val w = pairWidth(glyphPaint)
            val a = if (squareFont) SquareDigits.advance(digitSize) else w / 2f
            val x = width / 2f - w / 2f
            glyphs[row * 2].reset()
            glyphs[row * 2 + 1].reset()
            glyphDigit[row * 2] = text[0] - '0'
            glyphDigit[row * 2 + 1] = text[1] - '0'
            glyphLeft[row * 2] = x
            glyphLeft[row * 2 + 1] = x + a
            if (squareFont) {
                // IchthysOS: SquareDigits.glyph sets the path itself.
                SquareDigits.glyph(glyphDigit[row * 2], x, pair.second - capH, pair.second,
                    digitSize, glyphs[row * 2])
                SquareDigits.glyph(glyphDigit[row * 2 + 1], x + a, pair.second - capH,
                    pair.second, digitSize, glyphs[row * 2 + 1])
            } else {
                glyphPaint.getTextPath(text, 0, 1, cellLeft(glyphPaint, text, 0, x, a),
                    pair.second, glyphs[row * 2])
                glyphPaint.getTextPath(text, 1, 2, cellLeft(glyphPaint, text, 1, x, a),
                    pair.second, glyphs[row * 2 + 1])
            }
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
        // IchthysOS: the shaped digits as drawn (beside the subject the columns differ in height).
        if (path === shaped) shaped.computeBounds(shapedBounds, true)
        val digitsBottom =
            if (path === shaped && !shapedBounds.isEmpty) shapedBounds.bottom + digitSize * 0.02f * k
            else digitTop + (line2Base + digitSize * 0.02f - digitTop) * k
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
                fitColumns()
                val subject = (profile.minTop((place[0] + fitCols[0] * scale) / pw,
                    (place[0] + fitCols[3] * scale) / pw) * profile.height - place[1]) / scale
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
        // The fit's even scale and sideways move included.
        val half = pairWidth(digitPaint) / 2f * k
        val cx = width / 2f + if (path === shaped) shownShift else 0f
        contentRect.set(cx - half, line1Base - capH - digitSize * 0.01f, cx + half, digitsBottom)
        if (path === shaped && !shapedBounds.isEmpty) {
            contentRect.left = shapedBounds.left + shownShift
            contentRect.right = shapedBounds.right + shownShift
        }
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
        // IchthysOS: the depth fit's sideways move takes the date and the digits along.
        val shift = if (path === shaped) shownShift else 0f
        drawShift = shift
        canvas.save()
        canvas.translate(shift, 0f)
        dateRect.setEmpty()
        if (showDate) {
            // Below shaped digits, the line follows their (lowest) bottom.
            if (datePos == DATE_BELOW && path === shaped) {
                shaped.computeBounds(shapedBounds, true)
                statusBase = shapedBounds.bottom + digitSize * 0.05f * max(shownK[0], shownK[1]) +
                    statusH - dp(10f)
            } else if (datePos == DATE_BELOW) {
                statusBase = line2Base + digitSize * 0.03f + statusH - dp(10f)
            }
            weatherRect.setEmpty()
            if (details) drawStatus(canvas) else drawDate(canvas, doze)
        } else {
            weatherRect.setEmpty()
        }
        if (!weatherRect.isEmpty) weatherRect.offset(shift, 0f)
        reportWeather()
        paint.shader = null
        // IchthysOS polish: awake and dozing looks cross-fade with the doze amount (SystemUI's
        // doze animation); without one, it is one or the other.
        if (doze < 1f) drawAwake(canvas, path, 1f - doze)
        if (doze > 0f) drawDozing(canvas, path, doze)
        canvas.restore()
        if (!dateRect.isEmpty) dateRect.offset(shift, 0f)
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
                // IchthysOS: over a bright picture (a midday sky) the pale digits need more
                // to stand on: a deeper, wider shadow and a faint dark edge (ClockBackdrop).
                val bright = ClockBackdrop.brightness()
                paint.setShadowLayer(dp(14f + 6f * bright), 0f, dp(6f),
                    Color.argb(((90 + 110 * bright) * alpha).toInt(), 0, 20, 24))
                paint.shader = fillShader()
                // With a shader only the paint's alpha counts: it fades the fill.
                paint.alpha = (Color.alpha(primary) * alpha).toInt()
                canvas.drawPath(path, paint)
                paint.shader = null
                paint.clearShadowLayer()
                if (bright > 0.05f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1.1f)
                    paint.color = Color.argb((70 * bright * alpha).toInt(), 0, 18, 22)
                    canvas.drawPath(path, paint)
                    paint.style = Paint.Style.FILL
                }
            }
            FILL_GLASS -> {
                val bright = ClockBackdrop.brightness()
                // A soft lift off the picture, more over a bright one.
                paint.color = Color.WHITE
                paint.setShadowLayer(dp(10f), 0f, dp(3f),
                    Color.argb(((28 + 70 * bright) * alpha).toInt(), 0, 10, 20))
                // IchthysOS: clear glass first: the scene itself through the digits, bent like a
                // lens at their edges and lit on them ([drawClearGlass]); the frosted veil only
                // when there is no picture to show.
                if (drawClearGlass(canvas, path, alpha)) {
                    paint.clearShadowLayer()
                    paint.shader = null
                    return
                }
                val frosted = drawFrost(canvas, path, alpha)
                if (frosted) paint.clearShadowLayer()
                paint.shader = glassShader(if (frosted) -1f else bright)
                paint.alpha = (255 * alpha).toInt()
                canvas.drawPath(path, paint)
                paint.clearShadowLayer()
                // The light catching the glass's upper edges.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.6f)
                paint.shader = glassEdgeShader()
                paint.alpha = (255 * alpha).toInt()
                canvas.drawPath(path, paint)
                paint.shader = null
                paint.style = Paint.Style.FILL
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
        val bright = ClockBackdrop.brightness()
        datePaint.setShadowLayer(dp(5f + 3f * bright), 0f, dp(1f),
            Color.argb(((110 + 100 * bright) * (1f - doze)).toInt(), 0, 14, 18))
        val text = dateLine()
        datePaint.getFontMetrics(dateMetrics)
        // IchthysOS: beside the subject the line aligns with the clock's outer edge (One UI), its
        // own alignment otherwise. The canvas is moved with the clock: edges in its face px.
        val align = when {
            sideLayout > 0 -> ALIGN_END
            sideLayout < 0 -> ALIGN_START
            else -> dateAlign
        }
        val clockHalf = (columns[3] - columns[0]) / 2f * max(shownK[0], shownK[1])
        val clockMid = (columns[0] + columns[3]) / 2f
        val startX = if (sideLayout != 0) clockMid - clockHalf else dp(4f)
        val endX = if (sideLayout != 0) clockMid + clockHalf else width - dp(4f)
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
                    when (align) {
                        ALIGN_START -> startX
                        ALIGN_END -> endX - total
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
            when (align) {
                ALIGN_START -> {
                    datePaint.textAlign = Paint.Align.LEFT
                    startX
                }
                ALIGN_END -> {
                    datePaint.textAlign = Paint.Align.RIGHT
                    endX
                }
                else -> {
                    datePaint.textAlign = Paint.Align.CENTER
                    width / 2f
                }
            }
        canvas.drawText(text, x, statusBase, datePaint)
        val l =
            when (align) {
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
        /**
         * IchthysOS: frosted glass (OriginOS-like): the picture shows through a pale veil,
         * brighter at the top, with a crisp light edge; inside, the picture itself, very soft
         * (ClockBackdrop.frost), so the digits read as frosted glass in the scene.
         */
        const val FILL_GLASS = 3
        /** Settings.Secure: the glass clear (0) or frosted (1), as Apple's Glass clock offers. */
        const val KEY_GLASS_FROST = "ichthys_clock_glass_frost"
        /**
         * Settings.Secure: the glass's opacity, Apple's slider: 0 smoky .. 100 milky; -1 picks it
         * from the picture (milk over a bright one, smoke over a dark one).
         */
        const val KEY_GLASS_OPACITY = "ichthys_clock_glass_opacity"

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
         * IchthysOS: the smallest the depth fit shrinks the clock; needing more, depth steps back
         * and the clock stands whole in front.
         */
        private const val FIT_MIN_K = 0.6f
        /**
         * IchthysOS: the sideways moves the depth fit tries, as fractions of the room to each
         * side (negative: left), nearest the clock's own place first.
         */
        private val FIT_SHIFTS = floatArrayOf(0f, 0.25f, -0.25f, 0.5f, -0.5f, 0.75f, -0.75f, 1f, -1f)
        /**
         * IchthysOS: the layout beside the subject ([sidePlan]): its largest even factor, and how
         * short / tall a minute digit may be made (of its own height; its width stays).
         */
        private const val SIDE_K = 0.8f
        /** One UI's side clock is small: a large face may shrink this far beside the subject. */
        private const val SIDE_MIN_K = 0.4f
        /** The side clock's distance from the screen's edge. */
        private const val SIDE_MARGIN_DP = 24f
        private const val SIDE_MIN_V = 0.55f
        private const val SIDE_MAX_V = 1.35f

        /** The clear glass's edge map: this share of the face's px. */
        private const val MASK_SCALE = 0.25f

        /**
         * Clear glass ([drawClearGlass]). In face px: [picture] the scene (its own px via
         * frostMul / frostAdd), [mask] the digits blurred (its px via maskMul). The mask's
         * gradient points inwards; near the rim the picture is sampled further in (a lens) and the
         * rim is lit by its facing against a light from the top left.
         */
        private const val CLEAR_GLASS = """
            uniform shader picture;
            uniform shader mask;
            uniform float3x3 toFrost;
            uniform float maskMul;
            uniform float bend;
            uniform float dim;
            uniform float add;
            uniform float milk;
            uniform float2 lightDir;
            uniform float alpha;
            uniform half3 tint;
            uniform float tintAmt;
            uniform float2 tintSpan;

            uniform float frost;

            half3 scene(float2 q) {
                float3 f = toFrost * float3(q, 1.0);
                float2 uv = f.xy / f.z;
                if (frost <= 0.0) return picture.eval(uv).rgb;
                // frosted: a soft disc of 12 taps around the point, plus the point itself
                half3 acc = picture.eval(uv).rgb;
                for (int i = 0; i < 12; i++) {
                    float an = float(i) * 0.5235988;
                    float r = (i - (i / 2) * 2) == 0 ? frost : frost * 0.55;
                    acc += picture.eval(uv + float2(cos(an), sin(an)) * r).rgb;
                }
                return acc / 13.0;
            }

            half4 main(float2 p) {
                // IchthysOS: the clock as a flat pane of real glass (Liquid Glass). The face is
                // flat and shows the scene true; only a narrow rounded edge slopes. Through that
                // edge the scene is refracted (pulled in, red a touch further than blue), the
                // edge reflects the room by the Fresnel law, a key light and the opposite corner
                // catch crisp highlights, and a fine light line runs round the whole outline.
                float2 m = p * maskMul;
                float a = mask.eval(m).a;
                float gx = (mask.eval(m + float2(1.0, 0.0)).a - mask.eval(m - float2(1.0, 0.0)).a) * 0.5;
                float gy = (mask.eval(m + float2(0.0, 1.0)).a - mask.eval(m - float2(0.0, 1.0)).a) * 0.5;
                float2 g = float2(gx, gy);
                float gl = length(g);
                float2 nin = gl > 0.0001 ? g / gl : float2(0.0);   // into the glyph
                // 0 deep inside .. 1 on the outline (the blurred edge is half covered there)
                float edge = clamp((1.0 - a) * 2.0, 0.0, 1.0);
                float slope = smoothstep(0.45, 1.0, edge);           // flat face, sloped rim
                float nz = 1.0 / sqrt(1.0 + slope * slope * 9.0);
                float2 off = nin * slope * bend;
                half3 c = half3(scene(p + off * 1.06).r, scene(p + off).g, scene(p + off * 0.94).b);
                c = c * half(dim) + half(add);
                // Apple's slider: a touch of milk over a bright picture, smoke over a dark one
                c = milk >= 0.0 ? 1.0 - (1.0 - c) * half(1.0 - milk) : c * half(1.0 + milk);
                // over a dark picture the glass takes the picture's accent, pale to full
                float t = clamp((p.y - tintSpan.x) / (tintSpan.y - tintSpan.x), 0.0, 1.0);
                half3 pale = 1.0 - (1.0 - tint) * 0.3;
                half3 full = 1.0 - (1.0 - tint) * 0.9;
                c = mix(c, mix(pale, full, half(t)), half(tintAmt));
                // Fresnel: the sloped rim reflects the room (a bright, soft picture)
                half3 env = 1.0 - (1.0 - scene(p - nin * bend * 3.0)) * 0.55;
                float F = clamp((0.04 + 0.96 * pow(1.0 - nz, 5.0)) * 3.0, 0.0, 1.0);
                c = mix(c, env, half(F));
                // crisp highlights from the key light and the opposite corner
                float3 n3 = normalize(float3(-nin * slope * 3.0, 1.0));
                float3 hk = normalize(normalize(float3(lightDir, 0.9)) + float3(0.0, 0.0, 1.0));
                float3 hb = normalize(normalize(float3(-lightDir, 0.85)) + float3(0.0, 0.0, 1.0));
                float sp = pow(max(dot(n3, hk), 0.0), 160.0) * 1.1 + pow(max(dot(n3, hb), 0.0), 120.0) * 0.7;
                // a fine light line round the whole outline, brightest facing the light
                float facing = dot(-nin, normalize(lightDir));
                float rl = smoothstep(0.72, 0.98, edge) * (0.28 + 0.35 * max(facing, 0.0) + 0.25 * max(-facing, 0.0));
                c = 1.0 - (1.0 - c) * half(1.0 - clamp(sp + rl, 0.0, 1.0));
                return half4(clamp(c, 0.0, 1.0), 1.0) * alpha;
            }
        """

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
        /**
         * IchthysOS: square-cornered, hollow seven-segment strokes, drawn ([SquareDigits]) rather
         * than set; [digitTypeface] gives the Tall face for it, for anything that only knows
         * typefaces.
         */
        const val FONT_SQUARE = 6
        /** IchthysOS: rounded heavy figures (Nunito ExtraBold, close to SF Pro Rounded). */
        const val FONT_ROUNDED = 7
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
                        FONT_ROUNDED ->
                            variable(NUNITO, "'wght' 800") ?: Typeface.create("sans-serif", Typeface.BOLD)
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
        private const val NUNITO = "/system/fonts/Nunito-VF.ttf"

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
/**
 * IchthysOS: how bright the picture is behind the clock (linear luma 0..1), published by SystemUI
 * (the lock wallpaper, or Living Sky's grade of it) and the editor's preview. The Depth face
 * strengthens its shadow and edge over a bright picture so the time stays readable.
 */
object ClockBackdrop {
    @Volatile
    var luma = 0f
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun publish(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(v - luma) < 0.01f) return
        luma = v
        listeners.forEach { it() }
    }

    /** The picture's own brightness behind the clock (before any Living Sky grade). */
    @Volatile
    var photoLuma = 0f
        private set

    /** [publish] for the picture itself: its brightness is also the frost's reference. */
    fun publishPhoto(value: Float) {
        photoLuma = value.coerceIn(0f, 1f)
        publish(value)
    }

    /**
     * IchthysOS: the picture, very soft (a small copy drawn smoothly over [frostW] x [frostH]
     * screen px): the glass digits (FILL_GLASS) show it through them, frosted.
     */
    @Volatile
    var frost: android.graphics.Bitmap? = null
        private set
    var frostW = 1
        private set
    var frostH = 1
        private set

    /** Publishes [picture] (covering a [screenW] x [screenH] screen) as the frost. */
    fun publishFrost(picture: android.graphics.Bitmap, screenW: Int, screenH: Int) {
        val soft = if (picture.config == android.graphics.Bitmap.Config.HARDWARE)
            picture.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else picture
        // Down in steps (each a smooth halving), so the copy is an even blur, not aliasing.
        // IchthysOS: a quarter of the screen, not a thumbnail: the glass shows the scene behind
        // it (a branch, a roof line), only a little softened, where 54 x 120 was one colour.
        val tw = kotlin.math.max(54, screenW / 4)
        val th = kotlin.math.max(120, screenH / 4)
        var b = soft
        while (b.width > tw * 2) {
            val next = android.graphics.Bitmap.createScaledBitmap(b,
                kotlin.math.max(1, b.width / 2), kotlin.math.max(1, b.height / 2), true)
            if (b !== soft && b !== picture) b.recycle()
            b = next
        }
        val small = android.graphics.Bitmap.createScaledBitmap(b, tw, th, true)
        if (b !== soft && b !== small) b.recycle()
        if (soft !== picture) soft.recycle()
        accent = accentOf(small)
        frost = small
        frostW = screenW
        frostH = screenH
        listeners.forEach { it() }
    }

    /**
     * IchthysOS: the picture's main accent colour (its most common saturated, not dark hue;
     * opaque), or 0 when it has none: the clear glass takes it over a dark background, where
     * there is nothing behind the glass to show (One UI tints its depth clock from the picture).
     */
    @Volatile
    var accent = 0
        private set

    private fun accentOf(b: android.graphics.Bitmap): Int {
        val bins = 24
        val weight = FloatArray(bins)
        val r = FloatArray(bins)
        val g = FloatArray(bins)
        val bl = FloatArray(bins)
        val hsv = FloatArray(3)
        var total = 0
        for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) {
            val c = b.getPixel(x, y)
            total++
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue
            val i = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            val w = hsv[1] * hsv[2]
            weight[i] += w
            r[i] += ((c shr 16) and 0xff) * w
            g[i] += ((c shr 8) and 0xff) * w
            bl[i] += (c and 0xff) * w
        }
        var best = 0
        for (i in 1 until bins) if (weight[i] > weight[best]) best = i
        // At least ~2 % of the picture, else it is no accent.
        if (total == 0 || weight[best] < total * 0.02f * 0.3f) return 0
        val w = weight[best]
        return android.graphics.Color.rgb((r[best] / w).toInt(), (g[best] / w).toInt(),
            (bl[best] / w).toInt())
    }

    /** How much darker or lighter the graded picture is than the frost's copy (Living Sky). */
    fun frostDim(): Float =
        if (photoLuma <= 0.002f) 1f else (luma / photoLuma).coerceIn(0.06f, 1.6f)

    /** 0 (dark enough as it is) .. 1 (a bright midday sky). */
    fun brightness(): Float {
        val t = ((luma - 0.18f) / (0.55f - 0.18f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Mean linear luma of [picture]'s area behind a lock screen clock (8%..45% of the height). */
    fun measure(picture: android.graphics.Bitmap): Float {
        val soft = if (picture.config == android.graphics.Bitmap.Config.HARDWARE)
            picture.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else picture
        val small = android.graphics.Bitmap.createScaledBitmap(soft, 36, 80, true)
        var sum = 0f
        var n = 0
        for (y in 6 until 36) for (x in 0 until 36) {
            val c = small.getPixel(x, y)
            val r = ((c shr 16) and 0xff) / 255f
            val g = ((c shr 8) and 0xff) / 255f
            val b = (c and 0xff) / 255f
            sum += 0.2126f * r * r + 0.7152f * g * g + 0.0722f * b * b
            n++
        }
        if (small !== soft) small.recycle()
        if (soft !== picture) soft.recycle()
        return if (n > 0) sum / n else 0f
    }
}

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
        val notify = !underTop.isNaN() || !depthApplies
        underTop = Float.NaN
        depthApplies = true
        if (notify) listeners.forEach { it() }
    }

    /** IchthysOS: see [DepthClockView.depthApplies]. */
    @Volatile
    var depthApplies = true
        private set

    fun publishDepthApplies(from: Any, applies: Boolean) {
        owner = from
        if (depthApplies == applies) return
        depthApplies = applies
        listeners.forEach { it() }
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
