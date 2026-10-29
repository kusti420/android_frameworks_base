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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.android.systemui.keyguard.data.repository.IchthysDepthRepository

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
                if (lens) {
                    repository.setLens(IchthysDepthRepository.Lens(at, down.pressure))
                    // IchthysOS: the lens opening is felt as much as seen: one light tick.
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
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

/**
 * The lens over the veil the cut-out opens: a soft halo of pond light fading out past the rim,
 * one thin cream ring at the rim and a fine accent ring a little outside it. [shown] (0..1) is
 * the lens animating in (ring and halo grow and brighten together) or dissolving on release.
 * No touch-panel readout any more (it read as surgery): just a calm, watchful ring.
 */
fun DrawScope.drawDepthLens(position: Offset, radius: Float, shown: Float, accent: Color) {
    if (shown <= 0f) return
    val fade = shown * shown * (3f - 2f * shown)
    // Light spilling out of the lens into the picture around it.
    drawCircle(
        brush = androidx.compose.ui.graphics.Brush.radialGradient(
            0.78f to Color.Transparent,
            0.9f to accent.copy(alpha = 0.16f * fade),
            1f to Color.Transparent,
            center = position,
            radius = radius * 1.35f,
        ),
        radius = radius * 1.35f,
        center = position,
    )
    // The rim: cream, a hair wide, with a faint dark line just inside so it reads on a bright
    // picture too.
    drawCircle(Color(0xFF07090B).copy(alpha = 0.35f * fade), radius - 1.1f * density, position,
        style = Stroke(width = 1.2f * density))
    drawCircle(Color(0xFFF6F1EA).copy(alpha = 0.92f * fade), radius, position,
        style = Stroke(width = 1.6f * density))
    // Pond light a little outside it, fine and continuous.
    drawCircle(accent.copy(alpha = 0.5f * fade), radius + 9f * density, position,
        style = Stroke(width = 1f * density))
}
