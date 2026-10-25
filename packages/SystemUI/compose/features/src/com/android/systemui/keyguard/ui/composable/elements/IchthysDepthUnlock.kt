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

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import com.android.systemui.keyguard.data.repository.IchthysDepthRepository

/** The depth layers that come apart while unlocking, back to front. */
enum class IchthysDepthPlane {
    /** The wallpaper (the backdrop): the bottom sheet of the stack. */
    BACK,
    /** The clock: the middle sheet. */
    MIDDLE,
    /** The subject, inside the clock's box: lifted off the clock towards you. */
    FRONT,
}

/** Share of the unlock spent pulling the layers apart; the rest folds them into the home screen. */
private const val SPLIT = 0.5f

private const val TILT = 38f
private const val CREAM = 0xFFF6F1EA

/**
 * IchthysOS depth lock screen, layer unlock (kusti420/ichthysos-tracker#58). As the lock screen
 * goes (the Lockscreen to Gone transition, 700 ms):
 * 1. the real layers come apart like an exploded drawing: all tilt back into one perspective, the
 *    wallpaper sinks to the bottom of the stack, the subject rises off the clock, the sheets get
 *    hairline edges;
 * 2. the stack folds into the home screen: it shrinks and fades (the transition fades the clock
 *    and the subject with it; the wallpaper fades here).
 * Draw phase only, so it costs no recomposition. Nothing moves while depth is off.
 */
fun Modifier.ichthysDepthPlane(
    repository: IchthysDepthRepository,
    plane: IchthysDepthPlane,
): Modifier =
    graphicsLayer {
            val p = repository.unlockProgress()
            if (p <= 0f || !repository.enabled.value) return@graphicsLayer
            val split = FastOutSlowInEasing.transform((p / SPLIT).coerceIn(0f, 1f))
            val fold = FastOutSlowInEasing.transform(((p - SPLIT) / (1f - SPLIT)).coerceIn(0f, 1f))
            cameraDistance = 10f * density
            when (plane) {
                IchthysDepthPlane.BACK -> {
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    rotationX = TILT * split
                    val s = 1f - 0.22f * split - 0.12f * fold
                    scaleX = s
                    scaleY = s
                    translationY = size.height * (0.16f * split - 0.04f * fold)
                    alpha = 1f - fold
                }
                IchthysDepthPlane.MIDDLE -> {
                    // The clock's box is near the top of the screen: tilt it about the screen's
                    // middle row, like the wallpaper, so the sheets stay one stack.
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    rotationX = TILT * split
                    val s = 1f - 0.16f * split - 0.12f * fold
                    scaleX = s
                    scaleY = s
                    translationY = size.height * (0.02f * split - 0.06f * fold)
                }
                IchthysDepthPlane.FRONT -> {
                    // Already tilted with the clock, its parent: only lifted off it.
                    translationY = -size.height * 0.22f * split
                    val s = 1f + 0.04f * split
                    scaleX = s
                    scaleY = s
                }
            }
        }
        .drawWithContent {
            drawContent()
            val p = repository.unlockProgress()
            if (p <= 0f || !repository.enabled.value) return@drawWithContent
            val split = FastOutSlowInEasing.transform((p / SPLIT).coerceIn(0f, 1f))
            val fold = ((p - SPLIT) / (1f - SPLIT)).coerceIn(0f, 1f)
            val edge = split * (1f - fold)
            if (edge <= 0f) return@drawWithContent
            val color =
                when (plane) {
                    IchthysDepthPlane.FRONT -> Color(repository.accent)
                    else -> Color(CREAM)
                }
            drawRoundRect(
                color.copy(alpha = 0.55f * edge),
                cornerRadius = CornerRadius(24f * density),
                style = Stroke(width = 1.5f * density),
            )
        }
