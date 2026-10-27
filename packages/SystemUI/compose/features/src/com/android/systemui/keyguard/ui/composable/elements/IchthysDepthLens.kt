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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * so swipes, taps and long presses still do what they always do. The one exception: a finger held
 * on the Depth face's touch heat ([IchthysDepthRepository.clockPaintable]) paints into it.
 */
fun Modifier.ichthysDepthLens(repository: IchthysDepthRepository): Modifier = composed {
    // Lens positions are in window coordinates, like the depth pictures.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    // Read per gesture (a State, not its value), so the running pointerInput sees changes.
    val paintable = repository.clockPaintable.collectAsStateWithLifecycle()
    onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(repository) {
            awaitEachGesture {
                val down =
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val at = down.position + origin
                // Every finger on the lock screen glows in the Depth face's touch heat (live only,
                // never counted); one on the subject also gets the X-ray lens.
                pulse(at)
                repository.ripple(at)
                val lens = repository.hitsSubject(at.x, at.y)
                if (lens) repository.setLens(IchthysDepthRepository.Lens(at, down.pressure))
                // Press and hold on the clock, then draw: from then on the touch is the clock's,
                // so the shade and unlock swipes don't take it. A quick swipe still swipes. Only
                // where there is something to draw into; elsewhere a held finger stays a swipe.
                val canPaint = paintable.value && repository.clockBounds.contains(at)
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
                            } else if (
                                canPaint && change.uptimeMillis - down.uptimeMillis >= HOLD_MS
                            ) {
                                // Held: from now on the finger paints light into the digits.
                                drawing = true
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        // On the clock the held finger is the lens's, not a swipe's.
                        if (drawing) change.consume()
                        val p = change.position + origin
                        pulse(p)
                        repository.ripple(p)
                        if (lens) {
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
    // A clean window through the subject: the hole is cut by the cut-out layer; here only its
    // rim, a fine cream ring and a dotted pond-light ring just outside it.
    drawCircle(Color(0xFFF6F1EA).copy(alpha = 0.9f), radius, lens.position,
        style = Stroke(width = 1.2f * density))
    drawCircle(
        accent.copy(alpha = 0.55f),
        radius + 10f * density,
        lens.position,
        style =
            Stroke(
                width = 1f * density,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f * density, 5f * density)),
            ),
    )
}
