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

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.util.PathParser
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.android.internal.graphics.ColorUtils
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * IchthysOS: the charger-connected styles beyond pulse / fill / spark (ShoalChargingView.kt):
 * One UI, battery capsule, dot matrix, aurora, bubbles, lightning bolt, rising wave, big number.
 * Each is an extension of ShoalChargingView drawing at its elapsed time [ShoalChargingView.t].
 */

private const val TAG = "ShoalChargingStyles"

// One UI: the stock charger animation's Lottie rings, by charging speed (1 normal, 2 fast,
// 3 super fast, 4 super fast 2.0), in SystemUI's assets. The _lock ones play on the keyguard.
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
internal fun ShoalChargingView.prepareStyle(w: Float, h: Float) {
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
            for (i in 0 until ShoalChargingView.BUBBLE_COUNT) {
                val o = i * ShoalChargingView.BUBBLE_FIELDS
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

private fun ShoalChargingView.prepareOneUi() {
    // chargeSpeed 0..3 -> l1..l4.
    val name = String.format(Locale.ROOT, ONEUI_ASSET, chargeSpeed.coerceIn(0, 3) + 1,
        if (onLockScreen) "_lock" else "")
    try {
        val comp = LottieCompositionFactory.fromAssetSync(context, name).value
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
internal fun ShoalChargingView.drawOneUi(canvas: Canvas) {
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
internal fun ShoalChargingView.drawCapsule(canvas: Canvas) {
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
internal fun ShoalChargingView.drawDots(canvas: Canvas) {
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
internal fun ShoalChargingView.drawAurora(canvas: Canvas) {
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
internal fun ShoalChargingView.drawBubbles(canvas: Canvas) {
    val glow = easeOut(seg(t, 0f, 300f)) * (1f - easeIn(seg(t, 800f, 700f)))
    glowPaint.alpha = (glow * 255).toInt()
    canvas.drawCircle(port.x, port.y, min(width, height) * 0.32f, glowPaint)

    enterPortFrame(canvas)
    val hh = frame[1]
    val px = frame[2]
    fill.shader = null
    for (i in 0 until ShoalChargingView.BUBBLE_COUNT) {
        val o = i * ShoalChargingView.BUBBLE_FIELDS
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
internal fun ShoalChargingView.drawBoltStyle(canvas: Canvas) {
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
internal fun ShoalChargingView.drawWave(canvas: Canvas) {
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
internal fun ShoalChargingView.drawNumber(canvas: Canvas) {
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
 * Saves the canvas and turns it so the port's edge is the bottom: [ShoalChargingView.frame] gets
 * the local width, height and the port's x. Pair with canvas.restore().
 */
internal fun ShoalChargingView.enterPortFrame(canvas: Canvas) {
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
internal fun ShoalChargingView.drawLevelLabel(
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
