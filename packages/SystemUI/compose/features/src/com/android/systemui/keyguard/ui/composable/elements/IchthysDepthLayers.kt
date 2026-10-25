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

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.keyguard.data.repository.IchthysDepthRepository

/** Radius of the X-ray lens. */
private const val LENS_RADIUS_DP = 70f

/*
 * IchthysOS depth lock screen (kusti420/ichthysos-tracker#58). Both pictures are the size of the
 * screen and are drawn in window coordinates, wherever their composable sits, so they always lie
 * exactly on each other:
 * - the backdrop under everything of the lock screen;
 * - the cut-out over the clock only, in the clock's own box. Outside the clock the cut-out's pixels
 *   are the backdrop's, so clipping it to the clock changes nothing you can see, and notifications
 *   below the clock stay on top of it.
 */

/** The lock wallpaper, under everything. Fades out with [dozeAmount] (AOD shows no wallpaper). */
@Composable
fun IchthysDepthBackdrop(
    repository: IchthysDepthRepository,
    dozeAmount: Float,
    modifier: Modifier = Modifier,
) {
    val layers by repository.layers.collectAsStateWithLifecycle()
    val current = layers ?: return
    Box(modifier.fillMaxSize()) {
        if (dozeAmount < 1f) {
            WindowImage(current.backdrop, alpha = 1f - dozeAmount, Modifier.matchParentSize())
        }
        // AOD keeps the depth as the subject's faint outline.
        val night = current.night
        if (night != null && dozeAmount > 0f) {
            WindowImage(night, alpha = dozeAmount, Modifier.matchParentSize())
        }
    }
}

/**
 * The wallpaper's subject, over the clock: place it over the clock's box (matchParentSize). With a
 * finger on the subject, the X-ray lens cuts a hole in it.
 */
@Composable
fun IchthysDepthCutout(
    repository: IchthysDepthRepository,
    dozeAmount: Float,
    modifier: Modifier = Modifier,
) {
    val layers by repository.layers.collectAsStateWithLifecycle()
    val lens by repository.lens.collectAsStateWithLifecycle()
    val current = layers ?: return
    val bitmap = current.cutout ?: return
    // Awake: the subject in colour. On AOD: the subject in black, so the clock's outlines stop
    // behind it (its glowing edge is drawn with the backdrop). They cross-fade with the doze.
    Box(modifier) {
        if (dozeAmount < 1f) {
            WindowImage(bitmap, alpha = 1f - dozeAmount, Modifier.matchParentSize()) { origin ->
                lens?.let {
                    Path().apply { addOval(Rect(it.position - origin, LENS_RADIUS_DP * density)) }
                }
            }
        }
        if (current.night != null && dozeAmount > 0f) {
            WindowImage(
                bitmap,
                alpha = dozeAmount,
                Modifier.matchParentSize(),
                colorFilter = ColorFilter.tint(Color.Black),
            )
        }
    }
}

/**
 * The X-ray lens itself (the touch panel's nodes, rings, reading), over the whole lock screen while
 * a finger is on the subject. Lives in the lock screen root, whose touches feed the lens.
 */
@Composable
fun IchthysDepthLensOverlay(repository: IchthysDepthRepository, modifier: Modifier = Modifier) {
    val lens by repository.lens.collectAsStateWithLifecycle()
    val current = lens ?: return
    val accent = Color(LocalContext.current.getColor(android.R.color.system_accent1_300))
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
    ) {
        translate(-origin.x, -origin.y) {
            drawDepthLens(current, LENS_RADIUS_DP * density, accent, size)
        }
    }
}

/**
 * Draws [bitmap] (screen-sized) at the window's origin, clipped to this composable's bounds, minus
 * the [hole] (given this composable's window origin, returns a path in its own coordinates).
 */
@Composable
private fun WindowImage(
    bitmap: Bitmap,
    alpha: Float,
    modifier: Modifier,
    colorFilter: ColorFilter? = null,
    hole: DrawScope.(origin: Offset) -> Path? = { null },
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .graphicsLayer { this.alpha = alpha }
    ) {
        clipRect {
            val path = hole(origin)
            if (path != null) {
                clipPath(path, ClipOp.Difference) { drawAtWindowOrigin(image, origin, colorFilter) }
            } else {
                drawAtWindowOrigin(image, origin, colorFilter)
            }
        }
    }
}

private fun DrawScope.drawAtWindowOrigin(
    image: ImageBitmap,
    origin: Offset,
    colorFilter: ColorFilter? = null,
) {
    translate(-origin.x, -origin.y) {
        drawImage(image, colorFilter = colorFilter, filterQuality = FilterQuality.Medium)
    }
}
