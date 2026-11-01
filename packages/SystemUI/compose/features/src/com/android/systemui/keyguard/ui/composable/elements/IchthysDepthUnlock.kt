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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
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
private const val SPLIT = 0.6f

/** A swipe shorter than this has no direction of its own yet: the layers go straight up. */
private const val SWIPE_DIR_DP = 24f

/**
 * IchthysOS: the swipe's unit direction, from where the finger went down to where it is (or was
 * lifted: a fling keeps it). Straight up until it has travelled [SWIPE_DIR_DP].
 */
private fun IchthysDepthRepository.swipeDirection(density: Float): Offset {
    val v = swipe
    val d = v.getDistance()
    return if (d < SWIPE_DIR_DP * density) Offset(0f, -1f) else v / d
}


/**
 * IchthysOS depth lock screen, layer unlock (kusti420/ichthysos-tracker#58). As the lock screen
 * goes (the Lockscreen to Gone transition, 1.2 s with depth on), its layers drift apart in depth,
 * like a camera racking focus onto the subject: the pond falls back and out of focus, the clock
 * settles a little, the subject comes towards you, sharp; then everything dissolves into the home
 * screen (the transition fades the clock and the subject; the pond fades here). Draw phase only,
 * so it costs no recomposition. Nothing moves while depth is off.
 *
 * A swipe takes the clock and the subject along the finger's direction ([swipeDirection]). A
 * fingerprint, face or tap unlock has no direction: the layers only rack focus, and the subject
 * dissolves from the lock icon the way the screen itself comes on after a fingerprint (the reveal
 * hole, IchthysDepthLayers.kt).
 */
fun Modifier.ichthysDepthPlane(
    repository: IchthysDepthRepository,
    plane: IchthysDepthPlane,
): Modifier = graphicsLayer {
    val p = repository.unlockProgress()
    if (p <= 0f || !repository.enabled.value) return@graphicsLayer
    val split = FastOutSlowInEasing.transform((p / SPLIT).coerceIn(0f, 1f))
    val fold = FastOutSlowInEasing.transform(((p - SPLIT) / (1f - SPLIT)).coerceIn(0f, 1f))
    val swipe = repository.unlockBySwipe()
    val dir = if (swipe) repository.swipeDirection(density) else Offset.Zero
    transformOrigin = TransformOrigin(0.5f, 0.5f)
    when (plane) {
        IchthysDepthPlane.BACK -> {
            val sc = 1f - 0.08f * split - 0.04f * fold
            scaleX = sc
            scaleY = sc
            val blur = (22f * split + 10f * fold) * density
            if (blur > 0.5f) renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
            alpha = 1f - fold
        }
        IchthysDepthPlane.MIDDLE -> {
            val sc = 1f - 0.03f * split
            scaleX = sc
            scaleY = sc
            translationX = dir.x * size.height * 0.02f * split
            translationY = dir.y * size.height * 0.02f * split
        }
        IchthysDepthPlane.FRONT -> {
            transformOrigin = TransformOrigin(0.5f, 0.3f)
            if (swipe) {
                val sc = 1f + 0.10f * split + 0.04f * fold
                scaleX = sc
                scaleY = sc
                translationX = dir.x * size.height * 0.08f * split
                translationY = dir.y * size.height * 0.08f * split
            } else {
                // The reveal hole takes the subject away; a touch of lift so it still comes to you.
                val sc = 1f + 0.04f * split
                scaleX = sc
                scaleY = sc
            }
        }
    }
}
