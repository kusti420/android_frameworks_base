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

package com.android.systemui.keyguard.ui.composable.elements

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.android.systemui.keyguard.data.repository.IchthysDepthRepository
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * IchthysOS depth lock screen, X-ray (kusti420/ichthysos-tracker#58): a finger put down on the
 * wallpaper's subject sees through it. Watches touches on the initial pass and never consumes them,
 * so swipes, taps and long presses still do what they always do.
 */
fun Modifier.ichthysDepthLens(repository: IchthysDepthRepository): Modifier = composed {
    // Lens positions are in window coordinates, like the depth pictures.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(repository) {
            awaitEachGesture {
                val down =
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val at = down.position + origin
                // Every finger on the lock screen glows in the Depth face's touch heat (live only,
                // never counted); one on the subject also gets the X-ray lens.
                pulse(at)
                val lens = repository.hitsSubject(at.x, at.y)
                if (lens) repository.setLens(IchthysDepthRepository.Lens(at, down.pressure))
                // Press and hold on the clock, then draw: from then on the touch is the clock's,
                // so the shade and unlock swipes don't take it. A quick swipe still swipes.
                val onClock = repository.clockBounds.contains(at)
                var drawing = false
                try {
                    while (true) {
                        // Main pass: the scene's swipe handling (an ancestor) comes after this, so
                        // what is consumed here never becomes a swipe.
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val moved = (change.position - down.position).getDistance()
                        if (!drawing && !lens) {
                            if (moved > viewConfiguration.touchSlop) {
                                // A swipe: leave it to the scene.
                                return@awaitEachGesture finish(lens, repository)
                            } else if (change.uptimeMillis - down.uptimeMillis >= HOLD_MS) {
                                // Held: the X-ray lens opens under the finger, wherever it is.
                                drawing = true
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        // On the clock the held finger is the lens's, not a swipe's.
                        if (drawing && onClock) change.consume()
                        val p = change.position + origin
                        pulse(p)
                        if (lens || drawing) {
                            repository.setLens(IchthysDepthRepository.Lens(p, change.pressure))
                        }
                    }
                } finally {
                    repository.setLens(null)
                }
            }
        }
}

private fun androidx.compose.ui.input.pointer.PointerInputScope.pulse(at: Offset) {
    val dm = android.content.res.Resources.getSystem().displayMetrics
    com.android.settingslib.ichthys.clock.TouchHeat.pulse(
        (at.x / dm.widthPixels).coerceIn(0f, 1f),
        (at.y / dm.heightPixels).coerceIn(0f, 1f),
    )
}

/** How long a finger rests on the clock before it draws instead of swiping. */
private const val HOLD_MS = 180L

/** The lens ends with the gesture (the finally block does the same for every other way out). */
private fun finish(lens: Boolean, repository: IchthysDepthRepository) {
    if (lens) repository.setLens(null)
}

/** Number of panel cells across the screen (the A52s' touch panel reads 16 x 37 nodes). */
private const val COLUMNS = 16
private const val ROWS = 37

/** Highest node value drawn, like the touch IC's delta frame. */
private const val PEAK = 950f

private val monoFace: Typeface by lazy {
    val f = File("/system/fonts/JetBrainsMono-VF.ttf")
    (if (f.isFile) runCatching { Typeface.Builder(f).build() }.getOrNull() else null)
        ?: Typeface.MONOSPACE
}

/**
 * What the lens shows over the hole it cuts in the subject: the touch panel's nodes around the
 * finger, each with its strength, a ring, and its reading. The strengths are modelled from where the
 * finger is and how hard it presses, in the shape a delta frame has (a peak spreading over a few
 * nodes); reading the panel's real frames is a later step.
 */
fun DrawScope.drawDepthLens(
    lens: IchthysDepthRepository.Lens,
    radius: Float,
    accent: Color,
    screen: Size,
) {
    val cw = screen.width / COLUMNS
    val ch = screen.height / ROWS
    val sigma = 1.35f * cw
    val peak = PEAK * (0.75f + 0.25f * lens.pressure.coerceIn(0f, 1f))
    drawCircle(Color(0x40040A0C), radius, lens.position)
    val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = monoFace
            textAlign = Paint.Align.CENTER
            textSize = cw * 0.3f
        }
    var max = 0
    for (cx in 0 until COLUMNS) {
        for (cy in 0 until ROWS) {
            val nx = (cx + 0.5f) * cw
            val ny = (cy + 0.5f) * ch
            val dx = nx - lens.position.x
            val dy = ny - lens.position.y
            val dd = dx * dx + dy * dy
            if (dd > radius * radius) continue
            // A little noise, as a real frame has; steady per node so it doesn't flicker.
            val noise = (sin(cx * 12.9898f + cy * 78.233f) * 43758.547f).let { it - it.toInt() }
            val v = (peak * exp(-dd / (2 * sigma * sigma)) - noise * 30f).roundToInt()
            if (v < 26) continue
            max = max(max, v)
            drawRoundRect(
                accent.copy(alpha = (v / PEAK).coerceAtMost(0.85f)),
                topLeft = Offset(nx - cw * 0.46f, ny - ch * 0.46f),
                size = Size(cw * 0.92f, ch * 0.92f),
                cornerRadius = CornerRadius(cw * 0.08f),
            )
            text.color = (if (v > 500) Color(0xFF07090B) else Color(0xFFF6F1EA)).toArgb()
            drawContext.canvas.nativeCanvas.drawText(v.toString(), nx, ny + text.textSize * 0.36f, text)
        }
    }
    drawCircle(Color(0xFFF6F1EA), radius, lens.position, style = Stroke(width = 1.5f * density))
    drawCircle(
        accent.copy(alpha = 0.6f),
        radius + 12f * density,
        lens.position,
        style =
            Stroke(
                width = 1f * density,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * density, 5f * density)),
            ),
    )
    text.textAlign = Paint.Align.LEFT
    text.textSize = 9f * density * 1.2f
    text.color = Color(0xFFF6F1EA).toArgb()
    drawContext.canvas.nativeCanvas.drawText(
        "X-RAY · Δ PEAK $max",
        lens.position.x - radius,
        lens.position.y - radius - 14f * density,
        text,
    )
}
