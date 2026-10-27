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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.ichthys.sky.LivingSky
import com.android.systemui.keyguard.data.repository.IchthysDepthRepository

/** Radius of the X-ray lens. */
private const val LENS_RADIUS_DP = 70f

/**
 * IchthysOS polish: depth turned on or off, a new wallpaper or a new mask: the layers cross-fade
 * (the backdrop over the same wallpaper, the subject over the clock) instead of popping.
 */
private const val LAYERS_FADE_MS = 420

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
    val finger by IchthysDepthRepository.fingerOnSensor.collectAsStateWithLifecycle()
    // IchthysOS Living Sky: the layers load for it too; then the backdrop is drawn graded.
    val depthOn by repository.enabled.collectAsStateWithLifecycle()
    val skyNow by repository.livingSky.collectAsStateWithLifecycle()
    // Weather and stars move only while awake (nothing on AOD) and out of battery saver.
    LivingSkyMotion(layers, skyNow, moving = dozeAmount == 0f && !finger)
    if (finger) return
    Crossfade(
        targetState = layers,
        animationSpec = tween(LAYERS_FADE_MS),
        label = "IchthysDepthBackdrop",
        modifier = modifier.fillMaxSize(),
    ) { shown ->
        val current = shown ?: return@Crossfade
        val sky = skyNow?.takeIf { current.livingSky }
        if (!depthOn && sky == null) return@Crossfade
        Box(Modifier.fillMaxSize()) {
            if (dozeAmount < 1f) {
                if (sky != null) {
                    LivingSkyLayer(current, sky, alpha = 1f - dozeAmount, Modifier.matchParentSize())
                } else {
                    WindowImage(current.backdrop, alpha = 1f - dozeAmount, Modifier.matchParentSize())
                }
            }
            // AOD keeps the depth as the subject's faint outline, moved against burn-in.
            val night = current.night
            if (depthOn && night != null && dozeAmount > 0f) {
                WindowImage(
                    night,
                    alpha = dozeAmount,
                    Modifier.matchParentSize(),
                    shift = { repository.burnInShift() },
                )
            }
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
    val finger by IchthysDepthRepository.fingerOnSensor.collectAsStateWithLifecycle()
    val skyNow by repository.livingSky.collectAsStateWithLifecycle()
    // IchthysOS: the Depth face says when its fit stepped back (no room above the subject): the
    // subject isn't drawn over the clock then.
    var depthApplies by remember {
        mutableStateOf(com.android.settingslib.ichthys.clock.DepthFaceAnchor.depthApplies)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val listener: () -> Unit = {
            val v = com.android.settingslib.ichthys.clock.DepthFaceAnchor.depthApplies
            android.os.Handler(android.os.Looper.getMainLooper()).post { depthApplies = v }
        }
        com.android.settingslib.ichthys.clock.DepthFaceAnchor.addListener(listener)
        onDispose { com.android.settingslib.ichthys.clock.DepthFaceAnchor.removeListener(listener) }
    }
    if (finger) return
    // Awake: the subject in colour. On AOD: the subject in black, so the clock's outlines stop
    // behind it (its glowing edge is drawn with the backdrop). They cross-fade with the doze.
    Crossfade(
        targetState = layers,
        animationSpec = tween(LAYERS_FADE_MS),
        label = "IchthysDepthCutout",
        modifier = modifier,
    ) { shown ->
        val current = shown ?: return@Crossfade
        val bitmap = current.cutout ?: return@Crossfade
        // IchthysOS: the time must stay readable: a cut-out that would hide too much of the clock
        // isn't drawn over it (the clock then stands in front, cleanly).
        if (!depthApplies || !repository.cutoutKeepsClockReadable(current)) return@Crossfade
        Box(Modifier.fillMaxSize()) {
            if (dozeAmount < 1f) {
                val hole: DrawScope.(Offset) -> Path? = { origin ->
                    lens?.let {
                        Path().apply { addOval(Rect(it.position - origin, LENS_RADIUS_DP * density)) }
                    }
                }
                // IchthysOS Living Sky: the graded picture (and its weather), only where the
                // subject is, so it lies exactly on the graded backdrop.
                val sky = skyNow?.takeIf { current.livingSky }
                if (sky != null) {
                    LivingSkyLayer(current, sky, alpha = 1f - dozeAmount, Modifier.matchParentSize(),
                        mask = bitmap, hole = hole)
                } else {
                    WindowImage(bitmap, alpha = 1f - dozeAmount, Modifier.matchParentSize(),
                        hole = hole)
                }
            }
            if (current.night != null && dozeAmount > 0f) {
                WindowImage(
                    bitmap,
                    alpha = dozeAmount,
                    Modifier.matchParentSize(),
                    colorFilter = ColorFilter.tint(Color.Black),
                    shift = { repository.burnInShift() },
                )
            }
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
    val trail by repository.trail.collectAsStateWithLifecycle()
    val ripples by repository.ripples.collectAsStateWithLifecycle()
    val accent = Color(0xFF9FE7DD)
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Frames while ripples spread.
    var now by remember { mutableStateOf(android.os.SystemClock.uptimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(ripples) {
        while (ripples.any { now - it.start < IchthysDepthRepository.RIPPLE_MS }) {
            androidx.compose.runtime.withFrameMillis {
                now = android.os.SystemClock.uptimeMillis()
            }
        }
    }
    val current = lens
    if (current == null && ripples.none { now - it.start < IchthysDepthRepository.RIPPLE_MS }) {
        return
    }
    Canvas(
        modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
    ) {
        translate(-origin.x, -origin.y) {
            // Water ripples: two rings spreading from each touch and fading out.
            for (r in ripples) {
                val t = ((now - r.start).toFloat() / IchthysDepthRepository.RIPPLE_MS)
                if (t !in 0f..1f) continue
                val ease = 1f - (1f - t) * (1f - t)
                for (k in 0..1) {
                    val tk = (ease - k * 0.18f).coerceAtLeast(0f)
                    if (tk <= 0f) continue
                    drawCircle(
                        Color(0xFFDDFBF6).copy(alpha = 0.5f * (1f - t) * (1f - k * 0.4f)),
                        radius = (10f + 110f * tk) * density,
                        center = r.position,
                        style = androidx.compose.ui.graphics.drawscope.Stroke((1.6f - k * 0.5f) * density),
                    )
                }
            }
            if (current == null) return@translate
            // The lens's trail: rings growing and fading behind it (newest last).
            trail.dropLast(1).forEachIndexed { i, p ->
                val age = trail.size - 1 - i
                drawCircle(
                    Color(0xFFF6F1EA).copy(alpha = (0.42f - age * 0.08f).coerceAtLeast(0.06f)),
                    radius = (12f + age * 9f) * density,
                    center = p,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f * density),
                )
            }
            drawDepthLens(current, LENS_RADIUS_DP * density, accent, size)
        }
    }
}

/*
 * IchthysOS Living Sky. The backdrop owns the motion ([LivingSkyMotion]): the frame clock lives
 * here, once, and both the backdrop and the subject over the clock ([LivingSkyLayer]) draw from it,
 * with the same grade, so they match. The weather is painted into the scene by the grade itself
 * (clouds in the photo's sky, rain / snow / fog at their depth), so nothing else is needed.
 */
private object LivingSkyFrame {
    /** Seconds of motion so far (clouds, rain, snow, the stars' twinkle), and lightning (0..1). */
    var time = 0f
    var flash = 0f
    /** Changes every drawn frame of motion: the layers read it while drawing, so they redraw. */
    val frame = androidx.compose.runtime.mutableLongStateOf(0L)
}

/** Frames of falling rain or snow: about 60 a second, whatever the display runs at. */
private const val WEATHER_FRAME_NS = 15_500_000L
/** Frames when only clouds or fog drift (slowly): about 24 a second. */
private const val DRIFT_FRAME_NS = 41_000_000L
/** Frames when only the stars move (they twinkle slowly). */
private const val STARS_FRAME_NS = 40_000_000L

@Composable
private fun LivingSkyMotion(
    layers: IchthysDepthRepository.DepthLayers?,
    now: IchthysDepthRepository.SkyNow?,
    moving: Boolean,
) {
    val current = layers?.takeIf { it.livingSky }
    val saver = now?.powerSave == true
    val go = moving && current != null && now != null && !saver
    val wx = now?.weather?.takeIf { it.type != LivingSky.TYPE_NONE }
    androidx.compose.runtime.DisposableEffect(current) {
        onDispose { LivingSkyFrame.flash = 0f }
    }
    val stars =
        current != null && now != null &&
            LivingSky.grade(now.sun, now.weather, current.skyInfo!!.horizon, now.moonPhase).stars > 0f
    val storm = wx?.storm == true && android.animation.ValueAnimator.areAnimatorsEnabled()
    val falling = wx != null && (wx.type == LivingSky.TYPE_RAIN || wx.type == LivingSky.TYPE_SNOW)
    androidx.compose.runtime.LaunchedEffect(go, wx, stars, storm) {
        if (!go || (wx == null && !stars)) return@LaunchedEffect
        val step =
            when {
                falling || storm -> WEATHER_FRAME_NS
                wx != null -> DRIFT_FRAME_NS
                else -> STARS_FRAME_NS
            }
        var last = 0L
        var bolt = 0f
        var nextBolt = 2f + 6f * kotlin.random.Random.nextFloat()
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (last != 0L && t - last < step) return@withFrameNanos
                val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(0.1f)
                last = t
                LivingSkyFrame.time += dt
                if (storm) {
                    // Lightning: a bright flash and a weaker second one, every 4..14 s.
                    nextBolt -= dt
                    if (nextBolt <= 0f) {
                        bolt = 0f
                        nextBolt = 4f + 10f * kotlin.random.Random.nextFloat()
                    }
                    bolt += dt
                    LivingSkyFrame.flash =
                        if (bolt < 0.6f) {
                            0.55f * kotlin.math.exp(-bolt / 0.07f) +
                                0.3f * kotlin.math.exp(-kotlin.math.abs(bolt - 0.22f) / 0.05f)
                        } else 0f
                }
                LivingSkyFrame.frame.longValue = t
            }
        }
    }
}

/**
 * The graded picture (with its weather while it moves) at the window's origin, like [WindowImage]
 * draws a bitmap; with [mask], only where its alpha is (the subject over the clock).
 */
@Composable
private fun LivingSkyLayer(
    layers: IchthysDepthRepository.DepthLayers,
    now: IchthysDepthRepository.SkyNow,
    alpha: Float,
    modifier: Modifier,
    mask: Bitmap? = null,
    hole: DrawScope.(origin: Offset) -> Path? = { null },
) {
    val info = layers.skyInfo ?: return
    val sky = layers.sky ?: return
    val w = layers.backdrop.width
    val h = layers.backdrop.height
    val painter = remember { LivingSky.Painter() }
    val picture = remember(layers) { LivingSky.fitted(layers.backdrop, w, h) }
    val skyShader = remember(layers) { LivingSky.fitted(sky, w, h) }
    // The subject (depth's cut-out) is the nearest thing: rain and snow behind it stay behind.
    val subject = remember(layers) { layers.cutout?.let { LivingSky.fitted(it, w, h) } }
    val grade = remember(now, info) { LivingSky.grade(now.sun, now.weather, info.horizon, now.moonPhase) }
    // IchthysOS: the Depth face's contrast follows the graded sky (bright at midday, none at night).
    if (mask == null) {
        androidx.compose.runtime.SideEffect {
            com.android.settingslib.ichthys.clock.ClockBackdrop.publish(
                LivingSky.brightnessBehindClock(grade, now.options.intensity / 100f))
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .graphicsLayer { this.alpha = alpha }
    ) {
        // Read while drawing: every frame of motion draws again.
        LivingSkyFrame.frame.longValue
        painter.bind(picture, skyShader, info, grade,
            now.options.intensity / 100f, w, h, density, LivingSkyFrame.time,
            LivingSkyFrame.flash, now.weather, subject)
        clipRect(left = -origin.x, top = 0f, right = -origin.x + 100_000f, bottom = 100_000f) {
            val path = hole(origin)
            val draw: DrawScope.() -> Unit = {
                translate(-origin.x, -origin.y) {
                    drawIntoCanvas {
                        if (mask != null) painter.drawMasked(it.nativeCanvas, w, h, mask)
                        else painter.draw(it.nativeCanvas, w, h)
                    }
                }
            }
            if (path != null) clipPath(path, ClipOp.Difference) { draw() } else draw()
        }
    }
}

/**
 * Draws [bitmap] (screen-sized) at the window's origin, clipped to this composable's bounds, minus
 * the [hole] (given this composable's window origin, returns a path in its own coordinates).
 * [shift] moves the picture off the window's origin (read while drawing: the AOD burn-in).
 */
@Composable
private fun WindowImage(
    bitmap: Bitmap,
    alpha: Float,
    modifier: Modifier,
    colorFilter: ColorFilter? = null,
    shift: () -> Offset = { Offset.Zero },
    hole: DrawScope.(origin: Offset) -> Path? = { null },
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .graphicsLayer { this.alpha = alpha }
    ) {
        // Nothing above this box (the status bar's icons stay in front); below and beside it the
        // picture may spill, where it is the backdrop's own pixels anyway.
        clipRect(left = -origin.x, top = 0f, right = -origin.x + 100_000f, bottom = 100_000f) {
            val path = hole(origin)
            val at = origin - shift()
            if (path != null) {
                clipPath(path, ClipOp.Difference) { drawAtWindowOrigin(image, at, colorFilter) }
            } else {
                drawAtWindowOrigin(image, at, colorFilter)
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
