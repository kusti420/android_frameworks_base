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

package com.android.settingslib.ichthys.charging

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.PathParser
import android.util.TypedValue
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.android.internal.graphics.ColorUtils
import java.text.NumberFormat
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import org.json.JSONObject

/**
 * IchthysOS: the charger-connected animation styles (Settings.System
 * [ChargingAnimationSettings.KEY], IchthysOS > System > Battery and display). "ripple" is the stock surfaceeffects
 * ripple (recoloured to the accent) and "off" plays nothing; both are handled by
 * SystemUI's WiredChargingRippleController. The other styles are drawn by [ChargingStylePainter],
 * in SystemUI's overlay (ShoalChargingView) and in the Settings previews.
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

    /**
     * Live preview: the Settings picker asks SystemUI to play a style once, full screen. Explicit
     * to SystemUI, and SystemUI only takes it from a holder of [PREVIEW_PERMISSION]
     * (android.permission.STATUS_BAR, signature|privileged; Settings holds it).
     */
    const val ACTION_PREVIEW = "com.android.systemui.ichthys.action.PREVIEW_CHARGING_ANIMATION"
    const val EXTRA_STYLE = "style"
    /** Battery level 0-100 to show; absent or -1 means the current level. */
    const val EXTRA_LEVEL = "level"
    const val PREVIEW_PERMISSION = android.Manifest.permission.STATUS_BAR
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"

    /** Asks SystemUI to play [style] once now, as when power is connected. */
    @JvmStatic
    fun sendPreview(context: Context, style: String, level: Int = -1) {
        val intent =
            android.content.Intent(ACTION_PREVIEW)
                .setPackage(SYSTEMUI_PACKAGE)
                .putExtra(EXTRA_STYLE, style)
                .putExtra(EXTRA_LEVEL, level)
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            android.util.Log.w("ChargingAnimation", "preview broadcast failed", e)
        }
    }

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

    @JvmStatic
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
 * IchthysOS: the Shoal colours and display face the charging styles draw with: the framework
 * ichthys_* tokens (which follow dark / light), the theme accent (the picked seed, else the Monet
 * accent) and the display family (ichthys_theme_font_display, Syne by default). The same reading
 * as SystemUI's ShoalShade, so SystemUI and the Settings previews look alike.
 */
class ChargingPalette(
    val accent: Int,
    val ink: Int,
    val card: Int,
    val hairline: Int,
    val cream: Int,
    val display: Typeface,
) {
    companion object {
        @JvmStatic
        fun fromTheme(context: Context): ChargingPalette =
            ChargingPalette(
                accent = accent(context),
                ink = token(context, "ichthys_ink", 0xFF07090B.toInt()),
                card = token(context, "ichthys_card", 0xFF0C1013.toInt()),
                hairline = token(context, "ichthys_hairline", 0xFF20282D.toInt()),
                cream = token(context, "ichthys_text", 0xFFF2F4F1.toInt()),
                display = displayFace(context, 600),
            )

        private fun token(context: Context, name: String, fallback: Int): Int =
            try {
                val id = context.resources.getIdentifier(name, "color", "android")
                if (id != 0) context.getColor(id) else fallback
            } catch (e: Exception) {
                fallback
            }

        /** The theme accent: the picked seed (system_palette), else the Monet accent. */
        @JvmStatic
        fun accent(context: Context): Int {
            try {
                val json =
                    Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    )
                if (!json.isNullOrEmpty()) {
                    val raw =
                        JSONObject(json).optString("android.theme.customization.system_palette")
                    if (raw.isNotEmpty()) {
                        var c = java.lang.Long.parseLong(raw.removePrefix("#"), 16).toInt()
                        if ((c ushr 24) == 0) c = c or 0xFF000000.toInt()
                        return c
                    }
                }
            } catch (e: Exception) {}
            return try {
                context.getColor(android.R.color.system_accent1_400)
            } catch (e: Exception) {
                0xFFFF6B3D.toInt()
            }
        }

        private fun displayFace(context: Context, weight: Int): Typeface {
            val name =
                try {
                    Settings.Secure.getString(context.contentResolver,
                        "ichthys_theme_font_display")
                } catch (e: Exception) {
                    null
                }
            val base = Typeface.create(if (TextUtils.isEmpty(name)) "syne" else name,
                Typeface.NORMAL)
            return Typeface.create(base ?: Typeface.DEFAULT, weight, false)
        }
    }
}

/**
 * IchthysOS "Shoal" charger-connected animation styles: the drawing, with no view of its own. One
 * implementation, two callers: SystemUI's ShoalChargingView plays it full screen on the charging
 * ripple's overlay window, and the Settings picker (OptionPreviews) draws it into preview cards at
 * a scaled-down virtual screen. [configure] once per play, then [draw] each frame with the elapsed
 * time; [durationMs] is how long the play runs. Colours and face come from a [ChargingPalette].
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
 * The other styles are described where they are drawn below. Nothing is drawn outside the
 * configured area, and no extra layers are used.
 */
class ChargingStylePainter(internal val context: Context) {

    /** The size the painter draws at, in px (the screen, or a preview card's virtual screen). */
    internal var width = 0
    internal var height = 0
    private val resources
        get() = context.resources

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
    var durationMs = DURATION_MS
        internal set

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
        palette: ChargingPalette = ChargingPalette.fromTheme(context),
    ) {
        this.width = width
        this.height = height
        this.style = style
        this.chargeSpeed = chargeSpeed
        this.onLockScreen = onLockScreen
        durationMs = DURATION_MS
        this.level = level.coerceIn(0, 100)
        levelText =
            if (level < 0) "" else NumberFormat.getPercentInstance().format(this.level / 100.0)

        accent = palette.accent
        ink = palette.ink
        card = palette.card
        hairline = palette.hairline
        cream = palette.cream
        // The colour on the liquid: whichever of ink and cream reads better on the accent.
        onAccent =
            if (
                ColorUtils.calculateContrast(ink, opaque(accent)) >=
                    ColorUtils.calculateContrast(cream, opaque(accent))
            )
                ink
            else cream
        text.typeface = palette.display

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

    /** The style of the last [configure]. */
    fun style(): String = style

    /** Draws the frame [timeMs] into the [width] x [height] area set by [configure]. */
    fun draw(canvas: Canvas, timeMs: Float) {
        t = timeMs
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

private const val TAG = "ShoalChargingStyles"

// One UI: the stock charger animation's Lottie rings, by charging speed (1 normal, 2 fast,
// 3 super fast, 4 super fast 2.0), in the calling app's assets (SystemUI has all eight, Settings
// the l1 one for its preview). The _lock ones play on the keyguard.
private const val ONEUI_ASSET = "ichthys_oneui_charging_l%d%s.json"

// One UI's charging icons (bolt, and the fast-charging double bolt), as path data.
private const val ONEUI_BOLT =
    "M6.933,7.087L2.332,13.958C2.172,14.197 1.816,14.003 1.911,13.729L3.62,8.82C3.676,8.657 " +
        "3.561,8.484 3.395,8.484H1.808C1.477,8.484 1.246,8.139 1.359,7.813L3.293,2.262C3.362," +
        "2.064 3.541,1.933 3.742,1.933H5.335C5.667,1.933 5.898,2.278 5.785,2.604L4.614,5.965C" +
        "4.557,6.128 4.673,6.301 4.839,6.301H6.541C6.928,6.301 7.155,6.756 6.933,7.087Z"
private const val ONEUI_FAST_BOLT =
    "M20.501,5.6L17.646,5.6C17.286,5.6 16.964,5.825 16.84,6.164L13.375,15.68C13.171,16.239 " +
        "13.585,16.831 14.18,16.831L17.024,16.831C17.321,16.831 17.528,17.127 17.426,17.406L" +
        "14.365,25.821C14.193,26.292 14.831,26.624 15.119,26.213L23.364,14.436C23.762,13.868 " +
        "23.356,13.087 22.662,13.087L19.611,13.087C19.313,13.087 19.107,12.791 19.208,12.512L" +
        "21.306,6.75C21.51,6.191 21.096,5.6 20.501,5.6ZM11.603,13.763C11.646,13.264 10.944," +
        "13.108 10.773,13.579L8.532,19.734C8.329,20.293 8.743,20.884 9.338,20.884L10.512," +
        "20.884C10.764,20.884 10.961,21.1 10.939,21.35L10.538,25.932C10.494,26.432 11.196," +
        "26.587 11.368,26.116L13.605,19.972C13.808,19.412 13.394,18.821 12.799,18.821L11.628," +
        "18.821C11.377,18.821 11.18,18.606 11.201,18.355Z"

private const val IOS_GREEN = 0xFF34C759.toInt()

// Dot matrix: 5 x 7 glyphs for 0-9 and %.
private val DOT_GLYPHS =
    mapOf(
        '0' to arrayOf(" ### ", "#   #", "#  ##", "# # #", "##  #", "#   #", " ### "),
        '1' to arrayOf("  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "),
        '2' to arrayOf(" ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####"),
        '3' to arrayOf("#####", "   # ", "  #  ", "   # ", "    #", "#   #", " ### "),
        '4' to arrayOf("   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # "),
        '5' to arrayOf("#####", "#    ", "#### ", "    #", "    #", "#   #", " ### "),
        '6' to arrayOf("  ## ", " #   ", "#    ", "#### ", "#   #", "#   #", " ### "),
        '7' to arrayOf("#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   "),
        '8' to arrayOf(" ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### "),
        '9' to arrayOf(" ### ", "#   #", "#   #", " ####", "    #", "   # ", " ##  "),
        '%' to arrayOf("##   ", "##  #", "   # ", "  #  ", " #   ", "#  ##", "   ##"),
    )

/** Per-style setup, once per play (after the colours and the port are known). */
internal fun ChargingStylePainter.prepareStyle(w: Float, h: Float) {
    labelPaint.shader = null
    centerGlow.shader = null
    auroraPaint.shader = null
    auroraPaint.maskFilter = null
    waveShader = null
    lottie = null
    lottieMs = 0f
    oneUiIcon = null
    when (style) {
        ChargingAnimationSettings.ONEUI -> prepareOneUi()
        ChargingAnimationSettings.BUBBLES -> {
            val r = Random(SystemClock.uptimeMillis())
            for (i in 0 until ChargingStylePainter.BUBBLE_COUNT) {
                val o = i * ChargingStylePainter.BUBBLE_FIELDS
                bubbles[o] = r.nextFloat() * 2f - 1f // across the port
                bubbles[o + 1] = r.nextFloat() * 450f // delay
                bubbles[o + 2] = 750f + r.nextFloat() * 300f // duration
                bubbles[o + 3] = 0.22f + r.nextFloat() * 0.33f // rise, of the height
                bubbles[o + 4] = dp(3f) + r.nextFloat() * dp(6.5f) // radius
                bubbles[o + 5] = dp(3f) + r.nextFloat() * dp(9f) // wobble
                bubbles[o + 6] = r.nextFloat() * 2f * PI.toFloat() // phase
            }
        }
        ChargingAnimationSettings.BOLT -> {
            val cx = w / 2f
            val cy = h * 0.44f
            val bh = dp(150f)
            val bw = bh * 0.62f
            val l = cx - bw / 2f
            val tp = cy - bh / 2f
            boltPath.rewind()
            boltPath.moveTo(l + bw * 0.62f, tp)
            boltPath.lineTo(l + bw * 0.06f, tp + bh * 0.58f)
            boltPath.lineTo(l + bw * 0.46f, tp + bh * 0.58f)
            boltPath.lineTo(l + bw * 0.36f, tp + bh)
            boltPath.lineTo(l + bw * 0.94f, tp + bh * 0.40f)
            boltPath.lineTo(l + bw * 0.54f, tp + bh * 0.40f)
            boltPath.close()
            boltMeasure.setPath(boltPath, true)
            boltLength = boltMeasure.length
            centerGlow.shader =
                RadialGradient(
                    cx,
                    cy,
                    dp(170f),
                    intArrayOf(withAlpha(accent, 0.45f), withAlpha(accent, 0.12f), 0),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
        }
        ChargingAnimationSettings.AURORA -> {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(opaque(accent), hsl)
            fun hue(d: Float): Int {
                val c = hsl.copyOf()
                c[0] = (c[0] + d + 360f) % 360f
                c[1] = max(c[1], 0.55f)
                return ColorUtils.HSLToColor(c)
            }
            auroraPaint.shader =
                SweepGradient(
                    w / 2f,
                    h / 2f,
                    intArrayOf(opaque(accent), hue(45f), hue(-50f), hue(20f), opaque(accent)),
                    null,
                )
            auroraPaint.maskFilter = BlurMaskFilter(dp(16f), BlurMaskFilter.Blur.NORMAL)
        }
        ChargingAnimationSettings.WAVE -> {
            waveShader =
                LinearGradient(
                    0f,
                    0f,
                    0f,
                    dp(260f),
                    intArrayOf(withAlpha(accent, 0.34f), withAlpha(accent, 0.10f), 0),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
        }
    }
}

// region One UI

private fun ChargingStylePainter.prepareOneUi() {
    // chargeSpeed 0..3 -> l1..l4.
    val name = String.format(Locale.ROOT, ONEUI_ASSET, chargeSpeed.coerceIn(0, 3) + 1,
        if (onLockScreen) "_lock" else "")
    try {
        var comp = LottieCompositionFactory.fromAssetSync(context, name).value
        if (comp == null) {
            // IchthysOS: an app with only the normal ring (the Settings preview) uses that.
            comp = LottieCompositionFactory.fromAssetSync(context,
                String.format(Locale.ROOT, ONEUI_ASSET, 1, "")).value
        }
        if (comp != null) {
            lottie = LottieDrawable().also { it.setComposition(comp) }
            lottieMs = comp.duration
        }
    } catch (e: Exception) {
        Log.w(TAG, "no One UI animation $name", e)
    }
    if (lottie == null) Log.w(TAG, "no One UI animation $name, drawing the ring")
    durationMs = (if (lottieMs > 0f) lottieMs else 1700f) + 300f
    oneUiIcon =
        try {
            PathParser.createPathFromPathData(if (chargeSpeed > 0) ONEUI_FAST_BOLT else ONEUI_BOLT)
        } catch (e: Exception) {
            null
        }
    @Suppress("DEPRECATION") oneUiIcon?.computeBounds(iconBounds, true)
}

/**
 * One UI: the stock charger animation. The screen dims (20 %, 60 % on the lock screen), the
 * 176 dp ring plays in the middle, and inside it the charging icon (24 dp; a double bolt when
 * fast charging) sits over the level, 40 dp digits with a 20 dp "%", white, regular weight.
 */
internal fun ChargingStylePainter.drawOneUi(canvas: Canvas) {
    val fadeIn = easeOut(seg(t, 0f, 200f))
    val fadeOut = seg(t, durationMs - 300f, 300f)
    val a = fadeIn * (1f - fadeOut)
    if (a <= 0f) return
    canvas.drawColor(withAlpha(Color.BLACK, (if (onLockScreen) 0.6f else 0.2f) * a))

    val cx = width / 2f
    val cy = height / 2f
    val size = dp(176f)
    val d = lottie
    if (d != null) {
        d.progress = if (lottieMs > 0f) (t / lottieMs).coerceIn(0f, 1f) else 1f
        d.alpha = (a * 255).toInt()
        // IchthysOS: placed by the canvas, not by setBounds: LottieDrawable draws its
        // composition at its own intrinsic size from the bounds' corner, which put the ring up and
        // to the left of the centred icon and level. Bounds at the intrinsic size, then moved
        // and scaled so the composition's square is exactly the 176 dp square around the centre.
        val iw = d.intrinsicWidth.coerceAtLeast(1)
        val ih = d.intrinsicHeight.coerceAtLeast(1)
        d.setBounds(0, 0, iw, ih)
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(size / iw, size / ih)
        d.draw(canvas)
        canvas.restore()
    } else {
        stroke.color = Color.WHITE
        stroke.alpha = (0.25f * a * 255).toInt()
        stroke.strokeWidth = dp(3f)
        canvas.drawCircle(cx, cy, size * 0.42f, stroke)
        stroke.alpha = (a * 255).toInt()
        canvas.drawArc(
            cx - size * 0.42f,
            cy - size * 0.42f,
            cx + size * 0.42f,
            cy + size * 0.42f,
            -90f,
            360f * level / 100f * easeOut(seg(t, 100f, 900f)),
            false,
            stroke,
        )
    }

    // Icon over the level row, the pair centred in the ring.
    val content = easeOut(seg(t, 120f, 260f)) * (1f - fadeOut)
    if (content <= 0f) return
    plain.typeface = Typeface.create(Typeface.SANS_SERIF, 400, false)
    plain.textAlign = Paint.Align.LEFT
    plain.color = Color.WHITE
    plain.alpha = (content * 255).toInt()
    val num = String.format("%d", level)
    plain.textSize = dp(40f)
    val numW = plain.measureText(num)
    val fm = plain.fontMetrics
    val rowH = fm.bottom - fm.top
    plain.textSize = dp(20f)
    val pctW = plain.measureText("%")
    val icon = dp(24f)
    val top = cy - (icon + rowH) / 2f

    val ip = oneUiIcon
    if (ip != null && iconBounds.width() > 0f && iconBounds.height() > 0f) {
        val sc = min(icon / iconBounds.width(), icon / iconBounds.height())
        canvas.save()
        canvas.translate(
            cx - iconBounds.centerX() * sc,
            top + icon / 2f - iconBounds.centerY() * sc,
        )
        canvas.scale(sc, sc)
        fill.shader = null
        fill.color = Color.WHITE
        fill.alpha = (content * 255).toInt()
        canvas.drawPath(ip, fill)
        canvas.restore()
    }

    // One UI puts "%" first in Hebrew, Urdu, Turkish and Basque; the row has a 6 dp start margin.
    val lang = Locale.getDefault().language
    val pctFirst = lang == "he" || lang == "ur" || lang == "tr" || lang == "eu"
    val margin = dp(6f)
    var x = cx - (numW + pctW + margin) / 2f + margin
    val base = top + icon - fm.top
    if (pctFirst) {
        canvas.drawText("%", x, base, plain)
        x += pctW
    }
    plain.textSize = dp(40f)
    canvas.drawText(num, x, base, plain)
    x += numW
    if (!pctFirst) {
        plain.textSize = dp(20f)
        canvas.drawText("%", x, base, plain)
    }
}

// endregion

// region battery capsule

/** A green battery capsule fills to the level in the middle of a dimmed screen, iOS-like. */
internal fun ChargingStylePainter.drawCapsule(canvas: Canvas) {
    val tIn = easeOut(seg(t, 0f, 260f))
    val tOut = easeIn(seg(t, 1150f, 350f))
    val a = tIn * (1f - tOut)
    if (a <= 0f) return
    canvas.drawColor(withAlpha(Color.BLACK, 0.45f * a))
    val cx = width / 2f
    val cy = height / 2f
    val bw = dp(128f)
    val bh = dp(60f)
    val nubW = dp(7f)
    canvas.save()
    val sc = 0.9f + 0.1f * tIn
    canvas.scale(sc, sc, cx, cy)
    val left = cx - (bw + nubW + dp(4f)) / 2f
    rect.set(left, cy - bh / 2f, left + bw, cy + bh / 2f)
    stroke.color = Color.WHITE
    stroke.alpha = (0.92f * a * 255).toInt()
    stroke.strokeWidth = dp(3f)
    canvas.drawRoundRect(rect, dp(18f), dp(18f), stroke)
    fill.shader = null
    fill.color = Color.WHITE
    fill.alpha = (0.6f * a * 255).toInt()
    inner.set(rect.right + dp(4f), cy - dp(11f), rect.right + dp(4f) + nubW, cy + dp(11f))
    canvas.drawRoundRect(inner, nubW / 2f, nubW / 2f, fill)

    val pad = dp(6f)
    val f = easeOut(seg(t, 180f, 720f)) * level / 100f
    inner.set(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
    if (f > 0f) {
        val r = dp(12f)
        clip.rewind()
        clip.addRoundRect(inner, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        fill.color = IOS_GREEN
        fill.alpha = (a * 255).toInt()
        canvas.drawRect(inner.left, inner.top, inner.left + inner.width() * f, inner.bottom, fill)
        canvas.restore()
    }
    // The bolt over the capsule, with a dark rim so it reads on the green.
    drawBolt(canvas, rect.centerX(), cy, dp(30f), withAlpha(Color.BLACK, 0.55f * a))
    drawBolt(canvas, rect.centerX(), cy, dp(26f), withAlpha(Color.WHITE, a))

    if (levelText.isNotEmpty()) {
        plain.typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
        plain.textAlign = Paint.Align.CENTER
        plain.textSize = sp(24f)
        plain.color = Color.WHITE
        plain.alpha = (a * 255).toInt()
        canvas.drawText(levelText, rect.centerX(), rect.top - dp(22f), plain)
    }
    canvas.restore()
}

// endregion

// region dot matrix

/** The level in big dot-matrix digits that light up left to right, the % in the accent. */
internal fun ChargingStylePainter.drawDots(canvas: Canvas) {
    val tOut = seg(t, 1150f, 350f)
    val env = easeOut(seg(t, 0f, 220f)) * (1f - tOut)
    if (env <= 0f) return
    canvas.drawColor(withAlpha(opaque(ink), 0.66f * env))
    val chars = (level.coerceIn(0, 100).toString() + "%").toCharArray()
    val cols = chars.size * 5 + (chars.size - 1)
    var pitch = dp(11f)
    if (cols * pitch > width * 0.84f) pitch = width * 0.84f / cols
    val r = pitch * 0.36f
    val x0 = width / 2f - (cols - 1) * pitch / 2f
    val y0 = height * 0.45f - 3f * pitch
    fill.shader = null
    var col = 0
    for (ch in chars) {
        val g = DOT_GLYPHS[ch] ?: continue
        val lit = if (ch == '%') accent else cream
        for (c in 0 until 5) {
            val on = easeOut(seg(t, 120f + (col + c) * 22f, 160f))
            for (row in 0 until 7) {
                val x = x0 + (col + c) * pitch
                val y = y0 + row * pitch
                if (g[row][c] == '#') {
                    fill.color = lit
                    fill.alpha = ((0.12f + 0.88f * on) * env * 255).toInt()
                    val pop = 1f + 0.35f * sin(PI.toFloat() * on)
                    canvas.drawCircle(x, y, r * pop, fill)
                } else {
                    fill.color = cream
                    fill.alpha = (0.10f * env * 255).toInt()
                    canvas.drawCircle(x, y, r * 0.8f, fill)
                }
            }
        }
        col += 6
    }
}

// endregion

// region aurora

/** A soft, slowly turning accent glow around the screen edge that swells and fades. */
internal fun ChargingStylePainter.drawAurora(canvas: Canvas) {
    if (edgeLength <= 0f) return
    val env = min(easeOut(seg(t, 0f, 420f)), 1f - easeIn(seg(t, 1000f, 500f)))
    if (env <= 0f) return
    shaderMatrix.setRotate(t * 0.2f, width / 2f, height / 2f)
    auroraPaint.shader?.setLocalMatrix(shaderMatrix)
    auroraPaint.strokeWidth = dp(22f) + dp(10f) * sin(PI.toFloat() * seg(t, 0f, 1500f))
    auroraPaint.alpha = (0.9f * env * 255).toInt()
    canvas.drawPath(edgePath, auroraPaint)
    stroke.color = blend(accent, Color.WHITE, 0.35f)
    stroke.alpha = (0.55f * env * 255).toInt()
    stroke.strokeWidth = dp(1.5f)
    canvas.drawPath(edgePath, stroke)
    val a = easeOut(seg(t, 260f, 320f)) * (1f - seg(t, 1100f, 400f))
    drawLevelLabel(canvas, port.x + inward.x * dp(160f), port.y + inward.y * dp(160f), 36f, a,
        dp(10f) * (1f - a))
}

// endregion

// region bubbles

/** Accent bubbles rise from the charging port, wobbling and fading; the level above them. */
internal fun ChargingStylePainter.drawBubbles(canvas: Canvas) {
    val glow = easeOut(seg(t, 0f, 300f)) * (1f - easeIn(seg(t, 800f, 700f)))
    glowPaint.alpha = (glow * 255).toInt()
    canvas.drawCircle(port.x, port.y, min(width, height) * 0.32f, glowPaint)

    enterPortFrame(canvas)
    val hh = frame[1]
    val px = frame[2]
    fill.shader = null
    for (i in 0 until ChargingStylePainter.BUBBLE_COUNT) {
        val o = i * ChargingStylePainter.BUBBLE_FIELDS
        val p = seg(t, bubbles[o + 1], bubbles[o + 2])
        if (p <= 0f || p >= 1f) continue
        val x = px + bubbles[o] * dp(64f) * (0.4f + 0.6f * p) +
            bubbles[o + 5] * sin(bubbles[o + 6] + p * 5f)
        val y = hh - dp(4f) - bubbles[o + 3] * hh * easeOut(p)
        val r = bubbles[o + 4] * (0.6f + 0.4f * p)
        val a = sin(PI.toFloat() * p).pow(0.7f)
        fill.color = accent
        fill.alpha = (0.20f * a * 255).toInt()
        canvas.drawCircle(x, y, r, fill)
        stroke.color = accent
        stroke.alpha = (0.9f * a * 255).toInt()
        stroke.strokeWidth = dp(1.4f)
        canvas.drawCircle(x, y, r, stroke)
        fill.color = cream
        fill.alpha = (0.55f * a * 255).toInt()
        canvas.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.22f, fill)
    }
    canvas.restore()

    val a = easeOut(seg(t, 300f, 320f)) * (1f - seg(t, 1120f, 380f))
    drawLevelLabel(canvas, port.x + inward.x * dp(230f), port.y + inward.y * dp(230f), 36f, a,
        dp(12f) * (1f - a))
}

// endregion

// region lightning bolt

/** A large bolt drawn on in the accent, then filled with a flash; the level under it. */
internal fun ChargingStylePainter.drawBoltStyle(canvas: Canvas) {
    val env = easeOut(seg(t, 0f, 240f)) * (1f - easeIn(seg(t, 1150f, 350f)))
    if (env <= 0f || boltLength <= 0f) return
    canvas.drawColor(withAlpha(opaque(ink), 0.55f * env))
    val flash = seg(t, 560f, 600f)
    if (flash > 0f && flash < 1f) {
        centerGlow.alpha = (sin(PI.toFloat() * flash) * env * 255).toInt()
        canvas.drawCircle(width / 2f, height * 0.44f, dp(170f), centerGlow)
    }
    val fillA = easeOut(seg(t, 560f, 260f))
    if (fillA > 0f) {
        fill.shader = null
        fill.color = accent
        fill.alpha = (fillA * env * 255).toInt()
        canvas.drawPath(boltPath, fill)
    }
    boltSeg.rewind()
    boltMeasure.getSegment(0f, boltLength * easeInOut(seg(t, 60f, 600f)), boltSeg, true)
    stroke.color = blend(accent, Color.WHITE, 0.3f)
    stroke.alpha = (env * 255).toInt()
    stroke.strokeWidth = dp(3f)
    stroke.strokeJoin = Paint.Join.ROUND
    canvas.drawPath(boltSeg, stroke)
    stroke.strokeJoin = Paint.Join.MITER
    val a = easeOut(seg(t, 520f, 300f)) * (1f - seg(t, 1150f, 350f))
    drawLevelLabel(canvas, width / 2f, height * 0.44f + dp(122f), 30f, a, dp(8f) * (1f - a),
        scrim = false)
}

// endregion

// region rising wave

/** A low accent wave sweeps up the whole screen from the port's edge; the level in the middle. */
internal fun ChargingStylePainter.drawWave(canvas: Canvas) {
    val env = 1f - easeIn(seg(t, 1150f, 350f))
    if (env <= 0f) return
    enterPortFrame(canvas)
    val ww = frame[0]
    val hh = frame[1]
    val crest = hh + dp(20f) - (hh + dp(300f)) * easeInOut(seg(t, 0f, 1350f))
    val amp = dp(14f)
    val lambda = ww / 1.3f
    val phase = t / 1000f * 2f * PI.toFloat() * 0.9f
    path.rewind()
    val steps = 24
    for (i in 0..steps) {
        val x = ww * i / steps
        val y = crest + amp * sin(2f * PI.toFloat() * x / lambda + phase)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val shader = waveShader
    if (shader != null) {
        path2.set(path)
        path2.lineTo(ww, crest + dp(260f) + amp)
        path2.lineTo(0f, crest + dp(260f) + amp)
        path2.close()
        shaderMatrix.setTranslate(0f, crest - amp)
        shader.setLocalMatrix(shaderMatrix)
        fill.shader = shader
        fill.alpha = (env * 255).toInt()
        canvas.drawPath(path2, fill)
        fill.shader = null
    }
    stroke.color = accent
    stroke.alpha = (0.75f * env * 255).toInt()
    stroke.strokeWidth = dp(2f)
    canvas.drawPath(path, stroke)
    canvas.restore()
    val a = easeOut(seg(t, 280f, 320f)) * (1f - seg(t, 1150f, 350f))
    drawLevelLabel(canvas, width / 2f, height * 0.45f, 44f, a, dp(10f) * (1f - a))
}

// endregion

// region big number

/** Just the level, large in the display face, with an accent line that grows to it. */
internal fun ChargingStylePainter.drawNumber(canvas: Canvas) {
    val tIn = easeOut(seg(t, 0f, 380f))
    val tOut = easeIn(seg(t, 1150f, 350f))
    val a = tIn * (1f - tOut)
    if (a <= 0f || levelText.isEmpty()) return
    canvas.drawColor(withAlpha(opaque(ink), 0.6f * a))
    val cx = width / 2f
    val cy = height * 0.46f
    canvas.save()
    val sc = 0.94f + 0.06f * tIn
    canvas.scale(sc, sc, cx, cy)
    text.textSize = sp(88f)
    text.letterSpacing = -0.03f
    text.color = cream
    text.alpha = (a * 255).toInt()
    val base = cy - (text.descent() + text.ascent()) / 2f
    canvas.drawText(levelText, cx, base, text)
    val tw = text.measureText(levelText)
    val ly = base + dp(18f)
    stroke.strokeWidth = dp(3f)
    stroke.color = hairline
    stroke.alpha = (Color.alpha(hairline) * a).toInt()
    canvas.drawLine(cx - tw / 2f, ly, cx + tw / 2f, ly, stroke)
    val f = easeOut(seg(t, 300f, 650f)) * level / 100f
    if (f > 0f) {
        stroke.color = accent
        stroke.alpha = (a * 255).toInt()
        canvas.drawLine(cx - tw / 2f, ly, cx - tw / 2f + tw * f, ly, stroke)
    }
    canvas.restore()
}

// endregion

// region shared

/**
 * Saves the canvas and turns it so the port's edge is the bottom: [ChargingStylePainter.frame] gets
 * the local width, height and the port's x. Pair with canvas.restore().
 */
internal fun ChargingStylePainter.enterPortFrame(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    canvas.save()
    when {
        inward.y < 0f -> {
            frame[0] = w
            frame[1] = h
            frame[2] = port.x
        }
        inward.y > 0f -> {
            canvas.rotate(180f, w / 2f, h / 2f)
            frame[0] = w
            frame[1] = h
            frame[2] = w - port.x
        }
        inward.x > 0f -> {
            canvas.translate(w, 0f)
            canvas.rotate(90f)
            frame[0] = h
            frame[1] = w
            frame[2] = port.y
        }
        else -> {
            canvas.translate(0f, h)
            canvas.rotate(-90f)
            frame[0] = h
            frame[1] = w
            frame[2] = h - port.y
        }
    }
}

/**
 * The level in the display face, cream, at ([cx], [cy]) moved down by [lift]; with [scrim] a soft
 * ink pool behind it so it reads over any app.
 */
internal fun ChargingStylePainter.drawLevelLabel(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    sizeSp: Float,
    a: Float,
    lift: Float,
    scrim: Boolean = true,
) {
    if (levelText.isEmpty() || a <= 0f) return
    if (scrim) {
        if (labelPaint.shader == null) {
            val dark = opaque(ink)
            labelPaint.shader =
                RadialGradient(
                    cx,
                    cy,
                    dp(130f),
                    intArrayOf(withAlpha(dark, 0.55f), withAlpha(dark, 0.25f), 0),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
        }
        labelPaint.alpha = (a * 255).toInt()
        canvas.drawCircle(cx, cy, dp(130f), labelPaint)
    }
    text.textSize = sp(sizeSp)
    text.letterSpacing = -0.02f
    text.color = cream
    text.alpha = (a * 255).toInt()
    canvas.drawText(levelText, cx, cy + lift - (text.descent() + text.ascent()) / 2f, text)
}

// endregion
