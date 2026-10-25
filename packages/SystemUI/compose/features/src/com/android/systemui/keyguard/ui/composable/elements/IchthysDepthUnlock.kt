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

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import com.android.systemui.keyguard.data.repository.IchthysDepthRepository

/** The depth layers that come apart while unlocking. */
enum class IchthysDepthPlane {
    /** The wallpaper: tilts back and sinks. */
    BACK,
    /** The subject: lifts towards you. */
    FRONT,
}

/**
 * IchthysOS depth lock screen, layer unlock (kusti420/ichthysos-tracker#58): as the lock screen
 * goes (the Lockscreen to Gone transition), its real layers come apart like the pages of a
 * dissection, each outlined by a hairline, while the whole lock screen fades into the home screen.
 * All in the draw phase, so it costs no recomposition.
 */
fun Modifier.ichthysDepthPlane(
    repository: IchthysDepthRepository,
    plane: IchthysDepthPlane,
): Modifier =
    graphicsLayer {
            val p = repository.unlockProgress()
            if (p <= 0f) return@graphicsLayer
            cameraDistance = 12f * density
            transformOrigin = TransformOrigin(0.5f, 0.5f)
            when (plane) {
                IchthysDepthPlane.BACK -> {
                    rotationX = 24f * p
                    scaleX = 1f - 0.14f * p
                    scaleY = 1f - 0.14f * p
                    translationY = size.height * 0.08f * p
                }
                IchthysDepthPlane.FRONT -> {
                    rotationX = 12f * p
                    scaleX = 1f + 0.08f * p
                    scaleY = 1f + 0.08f * p
                    translationY = -size.height * 0.06f * p
                }
            }
        }
        .drawWithContent {
            drawContent()
            val p = repository.unlockProgress()
            if (p <= 0f || plane != IchthysDepthPlane.BACK) return@drawWithContent
            drawRoundRect(
                Color(0xFFF6F1EA).copy(alpha = 0.45f * p),
                cornerRadius = CornerRadius(28f * density),
                style = Stroke(width = 1.5f * density),
            )
        }
