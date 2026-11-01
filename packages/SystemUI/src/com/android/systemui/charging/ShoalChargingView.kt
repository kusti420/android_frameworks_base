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

package com.android.systemui.charging

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.UserHandle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.android.internal.graphics.ColorUtils
import com.android.systemui.shade.ui.ShoalShade
import java.text.NumberFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * IchthysOS: the charger-connected animation styles (Settings.System
 * [ChargingAnimationSettings.KEY], Look > Lock screen). "ripple" is the stock surfaceeffects
 * ripple (recoloured to the accent) and "off" plays nothing; both are handled by
 * [WiredChargingRippleController]. The other styles are drawn by [ShoalChargingView].
 */
object ChargingAnimationSettings {
    const val KEY = "ichthys_charging_animation"

    const val RIPPLE = "ripple"
    const val PULSE = "pulse"
    const val FILL = "fill"
    const val SPARK = "spark"
    const val OFF = "off"
    const val ONEUI = "oneui"
    const val CAPSULE = "capsule"
    const val DOTS = "dots"
    const val AURORA = "aurora"
    const val BUBBLES = "bubbles"
    const val BOLT = "bolt"
    const val WAVE = "wave"
    const val NUMBER = "number"

    /**
     * Settings.System, 0/1: with the screen off, connecting power turns it on to play the
     * animation (system.chargingAnimation.wakeScreen, default off).
     */
    const val WAKE_KEY = "ichthys_charging_animation_wake"

    /** Unset means the option default (lockscreen.chargingAnimation = "pulse"). */
    const val DEFAULT = PULSE

    /** Every style, in the order of the Settings list ("off" last). */
    val STYLES =
        listOf(
            PULSE,
            ONEUI,
            FILL,
            SPARK,
            CAPSULE,
            DOTS,
            AURORA,
            BUBBLES,
            BOLT,
            WAVE,
            NUMBER,
            RIPPLE,
            OFF,
        )

    fun isStyle(value: String?): Boolean = value != null && value in STYLES

    fun read(context: Context): String {
        val v =
            try {
                Settings.System.getStringForUser(
                    context.contentResolver,
                    KEY,
                    UserHandle.USER_CURRENT,
                )
            } catch (e: Exception) {
                null
            }
        return if (isStyle(v)) v!! else DEFAULT
    }

    fun readWake(context: Context): Boolean =
        try {
            Settings.System.getIntForUser(context.contentResolver, WAKE_KEY, 0,
                UserHandle.USER_CURRENT) != 0
        } catch (e: Exception) {
            false
        }
}

/**
 * IchthysOS "Shoal" charger-connected animation: one short (1.5 s), untouchable, full-screen
 * Canvas view on the charging ripple's overlay window. Colours are the framework ichthys_* tokens
 * (ink, card, hairline, cream text) and the theme accent, the number is in the display face
 * (Syne), all read from [ShoalShade] when the animation starts.
 *
 * Styles:
 *  - [ChargingAnimationSettings.PULSE]: from the charging port, a soft ink vignette with an accent
 *    glow, three thin accent rings rising and fading, and the battery level in the display face
 *    above the port.
 *  - [ChargingAnimationSettings.FILL]: a pill near the port whose accent liquid runs up to the
 *    level with a settling wave; the level sits in the pill and changes colour where the liquid
 *    is under it.
 *  - [ChargingAnimationSettings.SPARK]: a thin accent line drawn from the port both ways along
 *    the screen edge (following the display's rounded corners) to the opposite side, where the
 *    two heads meet in a small flash; the level shows in a small chip above the port.
 *
 * The view never draws outside its own bounds and uses no extra layers.
 */
class ShoalChargingView(context: Context) : View(context) {

    internal var style = ChargingAnimationSettings.PULSE
    internal var level = 0
    internal var levelText = ""

    // Port position in view coordinates and the unit vector pointing from its edge into the
    // screen.
    internal val port = PointF()
    internal val inward = PointF(0f, -1f)
    internal val cornerRadii = FloatArray(8)

    internal var t = 0f // elapsed ms

    /** 0 normal, 1 fast, 2 super fast (the charger's advertised power; see the controller). */
    internal var chargeSpeed = 0

    /** Whether the keyguard is showing (One UI picks its lock screen variant then). */
    internal var onLockScreen = false

    /** How long this play runs; most styles 1.5 s, One UI follows its animation. */
    internal var durationMs = DURATION_MS

    // One UI: the Lottie ring for the charger's speed, and its own length.
    internal var lottie: com.airbnb.lottie.LottieDrawable? = null
    internal var lottieMs = 0f

    // Bubbles: per-bubble parameters (see prepareStyle).
    internal val bubbles = FloatArray(BUBBLE_COUNT * BUBBLE_FIELDS)

    // Scratch state of the styles in ShoalChargingStyles.kt, set up by prepareStyle.
    internal val frame = FloatArray(3)
    internal val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val centerGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val auroraPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
    internal val plain = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val shaderMatrix = android.graphics.Matrix()
    internal var waveShader: android.graphics.LinearGradient? = null
    internal var oneUiIcon: Path? = null
    internal val iconBounds = RectF()
    internal val boltPath = Path()
    internal val boltMeasure = PathMeasure()
    internal var boltLength = 0f
    internal val boltSeg = Path()

    internal var accent = 0
    internal var ink = 0
    internal var card = 0
    internal var hairline = 0
    internal var cream = 0
    internal var onAccent = 0

    internal val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    internal val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    internal val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    internal val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    internal val rect = RectF()
    internal val inner = RectF()
    internal val path = Path()
    internal val path2 = Path()
    internal val clip = Path()

    // Spark line: the screen-edge path, its measure and where along it the port is.
    internal val edgePath = Path()
    internal val measure = PathMeasure()
    internal var edgeLength = 0f
    internal var portAt = 0f
    internal val pos = FloatArray(2)

    internal val animator =
        ValueAnimator.ofFloat(0f, DURATION_MS).apply {
            duration = DURATION_MS.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun isRunning(): Boolean = animator.isRunning

    /**
     * Sets up one play. [portX]/[portY] are the charging port in view coordinates, [corners] the
     * display corner radii (top-left, top-right, bottom-right, bottom-left) in px.
     */
    fun configure(
        style: String,
        level: Int,
        portX: Float,
        portY: Float,
        width: Int,
        height: Int,
        corners: IntArray,
        chargeSpeed: Int = 0,
        onLockScreen: Boolean = false,
    ) {
        this.style = style
        this.chargeSpeed = chargeSpeed
        this.onLockScreen = onLockScreen
        durationMs = DURATION_MS
        this.level = level.coerceIn(0, 100)
        levelText =
            if (level < 0) "" else NumberFormat.getPercentInstance().format(this.level / 100.0)

        accent = ShoalShade.accent(context)
        ink = ShoalShade.inkColor(context)
        card = ShoalShade.cardColor(context)
        hairline = ShoalShade.hairlineColor(context)
        cream = ShoalShade.textColor(context)
        // The colour on the liquid: whichever of ink and cream reads better on the accent.
        onAccent =
            if (
                ColorUtils.calculateContrast(ink, opaque(accent)) >=
                    ColorUtils.calculateContrast(cream, opaque(accent))
            )
                ink
            else cream
        text.typeface = ShoalShade.display(context, 600)

        // The port sits on (or near) an edge; take the nearest one as "its" edge.
        val w = width.toFloat()
        val h = height.toFloat()
        val x = portX.coerceIn(0f, w)
        val y = portY.coerceIn(0f, h)
        val dl = x
        val dr = w - x
        val dt = y
        val db = h - y
        val m = min(min(dl, dr), min(dt, db))
        when (m) {
            db -> {
                port.set(x, h)
                inward.set(0f, -1f)
            }
            dt -> {
                port.set(x, 0f)
                inward.set(0f, 1f)
            }
            dl -> {
                port.set(0f, y)
                inward.set(1f, 0f)
            }
            else -> {
                port.set(w, y)
                inward.set(-1f, 0f)
            }
        }

        val diag = min(w, h)
        val dark = opaque(ink)
        scrimPaint.shader =
            RadialGradient(
                port.x,
                port.y,
                diag * 0.85f,
                intArrayOf(withAlpha(dark, 0.62f), withAlpha(dark, 0.30f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        glowPaint.shader =
            RadialGradient(
                port.x,
                port.y,
                diag * 0.32f,
                intArrayOf(withAlpha(accent, 0.38f), withAlpha(accent, 0.10f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )

        if (style == ChargingAnimationSettings.SPARK || style == ChargingAnimationSettings.AURORA) {
            buildEdge(w, h, corners)
        }
        prepareStyle(w, h)
        t = 0f
    }

    fun start(onEnd: Runnable) {
        animator.setFloatValues(0f, durationMs)
        animator.duration = durationMs.toLong()
        animator.removeAllListeners()
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd.run()
                }
            }
        )
        animator.start()
    }

    fun cancel() {
        animator.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.removeAllListeners()
        animator.cancel()
        lottie = null
    }

    override fun onDraw(canvas: Canvas) {
        when (style) {
            ChargingAnimationSettings.PULSE -> drawPulse(canvas)
            ChargingAnimationSettings.FILL -> drawFill(canvas)
            ChargingAnimationSettings.SPARK -> drawSpark(canvas)
            ChargingAnimationSettings.ONEUI -> drawOneUi(canvas)
            ChargingAnimationSettings.CAPSULE -> drawCapsule(canvas)
            ChargingAnimationSettings.DOTS -> drawDots(canvas)
            ChargingAnimationSettings.AURORA -> drawAurora(canvas)
            ChargingAnimationSettings.BUBBLES -> drawBubbles(canvas)
            ChargingAnimationSettings.BOLT -> drawBoltStyle(canvas)
            ChargingAnimationSettings.WAVE -> drawWave(canvas)
            ChargingAnimationSettings.NUMBER -> drawNumber(canvas)
        }
    }

    // region pulse ring

    internal fun drawPulse(canvas: Canvas) {
        val diag = min(width, height).toFloat()
        // Vignette in, hold, out.
        val env = min(seg(t, 0f, 260f), 1f - seg(t, 1120f, 380f))
        if (env <= 0f) return
        scrimPaint.alpha = (env * 255).toInt()
        canvas.drawCircle(port.x, port.y, diag * 0.85f, scrimPaint)

        val glow = easeOut(seg(t, 0f, 320f)) * (1f - easeIn(seg(t, 700f, 800f)))
        glowPaint.alpha = (glow * 255).toInt()
        canvas.drawCircle(port.x, port.y, diag * 0.32f, glowPaint)

        // Three rings, 170 ms apart, each rising for 1 s.
        val maxR = diag * 0.66f
        for (i in 0 until 3) {
            val p = seg(t, i * 170f, 1000f)
            if (p <= 0f || p >= 1f) continue
            val r = dp(10f) + (maxR - dp(10f)) * easeOut(p)
            val fade = (1f - p).pow(1.4f) * RING_ALPHA[i]
            stroke.color = accent
            stroke.alpha = (fade * 255).toInt()
            stroke.strokeWidth = dp(0.75f) + dp(2f) * (1f - p)
            canvas.drawCircle(port.x, port.y, r, stroke)
        }

        // The level, above the port.
        if (levelText.isEmpty()) return
        val tIn = easeOut(seg(t, 200f, 360f))
        val tOut = seg(t, 1100f, 400f)
        val a = tIn * (1f - tOut)
        if (a <= 0f) return
        val lift = dp(12f) * (1f - tIn) - dp(6f) * easeIn(tOut)
        val cx = port.x + inward.x * (dp(150f) - lift)
        val cy = port.y + inward.y * (dp(150f) - lift)
        text.textSize = sp(40f)
        text.letterSpacing = -0.02f
        text.color = cream
        text.alpha = (a * 255).toInt()
        val base = cy - (text.descent() + text.ascent()) / 2f
        canvas.drawText(levelText, cx, base, text)
        // A small accent bolt over the number.
        drawBolt(canvas, cx, base + text.ascent() - dp(12f), dp(13f), withAlpha(accent, a))
    }

    // endregion

    // region liquid fill

    internal fun drawFill(canvas: Canvas) {
        val tIn = easeOut(seg(t, 0f, 240f))
        val tOut = easeIn(seg(t, 1150f, 350f))
        val a = tIn * (1f - tOut)
        if (a <= 0f) return

        val w = dp(176f)
        val h = dp(48f)
        val off = dp(110f) - dp(14f) * (1f - tIn) - dp(8f) * tOut
        val cx = port.x + inward.x * off
        val cy = port.y + inward.y * off
        val scale = 0.92f + 0.08f * tIn
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        // Pill: card ground and a 1 dp hairline.
        fill.shader = null
        fill.color = card
        fill.alpha = (Color.alpha(card) / 255f * 0.96f * a * 255).toInt()
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fill)
        stroke.color = hairline
        stroke.alpha = (Color.alpha(hairline) * a).toInt()
        stroke.strokeWidth = dp(1f)
        val hs = dp(0.5f)
        inner.set(rect.left + hs, rect.top + hs, rect.right - hs, rect.bottom - hs)
        canvas.drawRoundRect(inner, h / 2f - hs, h / 2f - hs, stroke)

        // Liquid inside a 4 dp inset, up to the level, with a wave on its leading edge that
        // settles as the fill arrives.
        val pad = dp(4f)
        inner.set(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
        val ir = inner.height() / 2f
        clip.rewind()
        clip.addRoundRect(inner, ir, ir, Path.Direction.CW)
        val f = easeOut(seg(t, 160f, 820f)) * (level / 100f)
        val edge = inner.left + inner.width() * f
        val settle = seg(t, 640f, 560f)
        val amp = dp(3.5f) * (1f - settle) * min(1f, f * 8f)
        val phase = (t / 1000f) * 2f * PI.toFloat() * 1.7f
        wave(path, inner, edge, amp, phase)
        wave(path2, inner, edge - dp(2f), amp * 0.8f, phase + PI.toFloat())

        canvas.save()
        canvas.clipPath(clip)
        fill.color = accent
        fill.alpha = (0.45f * a * 255).toInt()
        canvas.drawPath(path2, fill)
        fill.alpha = (a * 255).toInt()
        canvas.drawPath(path, fill)
        canvas.restore()

        // The level: cream on the pill, ink (or cream) on the liquid.
        if (levelText.isNotEmpty()) {
            text.textSize = sp(17f)
            text.letterSpacing = 0.01f
            val base = cy - (text.descent() + text.ascent()) / 2f
            canvas.save()
            canvas.clipOutPath(path)
            text.color = cream
            text.alpha = (a * 255).toInt()
            canvas.drawText(levelText, cx, base, text)
            canvas.restore()
            canvas.save()
            canvas.clipPath(clip)
            canvas.clipPath(path)
            text.color = onAccent
            text.alpha = (a * 255).toInt()
            canvas.drawText(levelText, cx, base, text)
            canvas.restore()
        }
        canvas.restore()
    }

    /** Liquid from [box]'s left edge to [edge], whose right side is a sine wave. */
    internal fun wave(out: Path, box: RectF, edge: Float, amp: Float, phase: Float) {
        out.rewind()
        out.moveTo(box.left - dp(2f), box.top)
        val steps = 12
        val lambda = box.height() * 1.2f
        for (i in 0..steps) {
            val y = box.top + box.height() * i / steps
            val x = edge + amp * sin(2f * PI.toFloat() * (y - box.top) / lambda + phase)
            out.lineTo(max(box.left - dp(2f), x), y)
        }
        out.lineTo(box.left - dp(2f), box.bottom)
        out.close()
    }

    // endregion

    // region spark line

    internal fun buildEdge(w: Float, h: Float, corners: IntArray) {
        val inset = dp(1.5f)
        for (i in 0 until 4) {
            val r = max(0f, corners.getOrElse(i) { 0 }.toFloat() - inset)
            cornerRadii[i * 2] = r
            cornerRadii[i * 2 + 1] = r
        }
        rect.set(inset, inset, w - inset, h - inset)
        edgePath.rewind()
        edgePath.addRoundRect(rect, cornerRadii, Path.Direction.CW)
        measure.setPath(edgePath, true)
        edgeLength = measure.length
        // Where along the edge the port is: the nearest of 360 samples, then refined.
        var best = 0f
        var bestD = Float.MAX_VALUE
        val n = 360
        for (i in 0 until n) {
            val s = edgeLength * i / n
            measure.getPosTan(s, pos, null)
            val d = abs(pos[0] - port.x) + abs(pos[1] - port.y)
            if (d < bestD) {
                bestD = d
                best = s
            }
        }
        val stepLen = edgeLength / n
        var s = best - stepLen
        while (s <= best + stepLen) {
            measure.getPosTan(wrap(s), pos, null)
            val d = abs(pos[0] - port.x) + abs(pos[1] - port.y)
            if (d < bestD) {
                bestD = d
                best = wrap(s)
            }
            s += stepLen / 16f
        }
        portAt = best
    }

    internal fun drawSpark(canvas: Canvas) {
        if (edgeLength <= 0f) return
        val half = edgeLength / 2f
        val head = half * easeInOut(seg(t, 0f, 900f))
        val tail = half * easeInOut(seg(t, 260f, 1000f))
        if (head > tail) {
            path.rewind()
            addSegment(path, portAt + tail, portAt + head)
            addSegment(path, portAt - head, portAt - tail)
            // Soft glow under the line, then the line.
            stroke.color = accent
            stroke.strokeWidth = dp(6f)
            stroke.alpha = (0.22f * 255).toInt()
            canvas.drawPath(path, stroke)
            stroke.strokeWidth = dp(2f)
            stroke.alpha = 255
            canvas.drawPath(path, stroke)
            // Bright heads while they travel.
            if (head < half) {
                fill.shader = null
                fill.color = blend(accent, Color.WHITE, 0.55f)
                for (s in floatArrayOf(portAt + head, portAt - head)) {
                    measure.getPosTan(wrap(s), pos, null)
                    canvas.drawCircle(pos[0], pos[1], dp(2.6f), fill)
                }
            }
        }
        // Where the heads meet: a short flash.
        val flash = seg(t, 860f, 460f)
        if (flash > 0f && flash < 1f) {
            val bell = sin(PI.toFloat() * flash)
            measure.getPosTan(wrap(portAt + half), pos, null)
            fill.shader = null
            fill.color = accent
            fill.alpha = (0.30f * bell * 255).toInt()
            canvas.drawCircle(pos[0], pos[1], dp(10f) + dp(22f) * flash, fill)
            fill.alpha = (0.85f * bell * 255).toInt()
            canvas.drawCircle(pos[0], pos[1], dp(3f), fill)
        }

        // The level in a small chip above the port.
        if (levelText.isEmpty()) return
        val tIn = easeOut(seg(t, 220f, 300f))
        val tOut = seg(t, 1150f, 350f)
        val a = tIn * (1f - tOut)
        if (a <= 0f) return
        text.textSize = sp(15f)
        text.letterSpacing = 0.01f
        val tw = text.measureText(levelText)
        val dot = dp(6f)
        val gap = dp(8f)
        val cw = tw + dot + gap + dp(28f)
        val ch = dp(32f)
        val off = dp(84f) - dp(8f) * (1f - tIn)
        val cx = port.x + inward.x * off
        val cy = port.y + inward.y * off
        rect.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
        fill.shader = null
        fill.color = card
        fill.alpha = (Color.alpha(card) / 255f * 0.96f * a * 255).toInt()
        canvas.drawRoundRect(rect, ch / 2f, ch / 2f, fill)
        stroke.color = hairline
        stroke.alpha = (Color.alpha(hairline) * a).toInt()
        stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, ch / 2f, ch / 2f, stroke)
        val left = cx - (tw + dot + gap) / 2f
        fill.color = accent
        fill.alpha = (a * 255).toInt()
        canvas.drawCircle(left + dot / 2f, cy, dot / 2f, fill)
        text.color = cream
        text.alpha = (a * 255).toInt()
        canvas.drawText(
            levelText,
            left + dot + gap + tw / 2f,
            cy - (text.descent() + text.ascent()) / 2f,
            text,
        )
    }

    /** Appends the edge between [from] and [to] (may wrap past either end). */
    internal fun addSegment(out: Path, from: Float, to: Float) {
        if (to <= from) return
        val a = wrap(from)
        val b = a + (to - from)
        if (b <= edgeLength) {
            measure.getSegment(a, b, out, true)
        } else {
            measure.getSegment(a, edgeLength, out, true)
            measure.getSegment(0f, b - edgeLength, out, true)
        }
    }

    internal fun wrap(s: Float): Float {
        if (edgeLength <= 0f) return 0f
        var v = s % edgeLength
        if (v < 0f) v += edgeLength
        return v
    }

    // endregion

    // region helpers

    /** A small lightning bolt centred at ([cx], [cy]), [h] tall. */
    internal fun drawBolt(canvas: Canvas, cx: Float, cy: Float, h: Float, color: Int) {
        val w = h * 0.62f
        val l = cx - w / 2f
        val tp = cy - h / 2f
        path.rewind()
        path.moveTo(l + w * 0.62f, tp)
        path.lineTo(l + w * 0.06f, tp + h * 0.58f)
        path.lineTo(l + w * 0.46f, tp + h * 0.58f)
        path.lineTo(l + w * 0.36f, tp + h)
        path.lineTo(l + w * 0.94f, tp + h * 0.40f)
        path.lineTo(l + w * 0.54f, tp + h * 0.40f)
        path.close()
        fill.shader = null
        fill.color = color
        canvas.drawPath(path, fill)
    }

    internal fun dp(v: Float): Float = v * resources.displayMetrics.density

    internal fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        const val DURATION_MS = 1500f
        internal const val BUBBLE_COUNT = 26
        internal const val BUBBLE_FIELDS = 7
        internal val RING_ALPHA = floatArrayOf(0.95f, 0.65f, 0.40f)
    }

    // endregion
}

// IchthysOS: timing and colour helpers shared by the styles (ShoalChargingStyles.kt too).

/** Progress of [t] through [start] .. [start] + [dur], clamped to 0..1. */
internal fun seg(t: Float, start: Float, dur: Float): Float = ((t - start) / dur).coerceIn(0f, 1f)

internal fun easeOut(x: Float): Float = 1f - (1f - x).pow(3)

internal fun easeIn(x: Float): Float = x * x * x

internal fun easeInOut(x: Float): Float =
    if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

internal fun opaque(c: Int): Int = c or 0xFF000000.toInt()

internal fun withAlpha(c: Int, a: Float): Int =
    ColorUtils.setAlphaComponent(c, (a.coerceIn(0f, 1f) * 255).toInt())

internal fun blend(a: Int, b: Int, r: Float): Int = ColorUtils.blendARGB(a, b, r)
