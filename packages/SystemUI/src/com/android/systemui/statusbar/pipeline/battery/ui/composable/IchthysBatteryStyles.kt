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

package com.android.systemui.statusbar.pipeline.battery.ui.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepositoryImpl
import com.android.systemui.statusbar.pipeline.battery.shared.model.BatteryCustomization
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryGlyph
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/*
 * IchthysOS (kusti420/ichthysos-tracker#37): the battery styles the A17 unified pill doesn't draw.
 * Classic upright (Android 9-15 geometry: 12x20 canvas, terminal on top, fills from the bottom),
 * circle, dotted circle and filled circle, plus the user's color options for every style.
 */

/** Colors for one frame of the battery, after the user's options are applied. */
internal data class BatteryPalette(
    /** Frame / outline / terminal, and the fill when no state color applies. */
    val frame: Color,
    /** The level fill (state colors: charging, battery saver, low). */
    val level: Color,
    /** The same choice expressed as [BatteryColors], for the landscape pill and text styles. */
    val colors: BatteryColors,
)

private enum class ColorKind {
    Default,
    Charging,
    PowerSave,
    Error,
}

private fun BatteryColors.kind(): ColorKind =
    when (this) {
        BatteryColors.DarkTheme.Charging,
        BatteryColors.LightTheme.Charging -> ColorKind.Charging
        BatteryColors.DarkTheme.PowerSave,
        BatteryColors.LightTheme.PowerSave -> ColorKind.PowerSave
        BatteryColors.DarkTheme.Error,
        BatteryColors.LightTheme.Error -> ColorKind.Error
        else -> ColorKind.Default
    }

/**
 * Applies the fill mode (status bar tint / custom / accent) and the state color mode (off / theme /
 * custom) to a theme color profile. With all options at their defaults the theme colors are
 * returned unchanged.
 */
internal fun BatteryColors.customizedFor(
    custom: BatteryCustomization,
    accent: Color,
): BatteryPalette {
    val base =
        when (custom.fillMode) {
            BatteryCustomization.FILL_MODE_CUSTOM ->
                // No colour picked yet: the accent drives it, like every other default.
                if (custom.fillColor == BatteryCustomization.FILL_COLOR_UNSET) accent
                else Color(custom.fillColor)
            BatteryCustomization.FILL_MODE_ACCENT -> accent
            // Follow the tint of the other status bar icons
            else -> attribution
        }
    val kind = kind()
    val level =
        if (kind == ColorKind.Default) {
            base
        } else {
            when (custom.stateColorMode) {
                BatteryCustomization.STATE_COLORS_OFF -> base
                BatteryCustomization.STATE_COLORS_CUSTOM ->
                    Color(
                        when (kind) {
                            ColorKind.Charging -> custom.chargingColor
                            ColorKind.PowerSave -> custom.powerSaveColor
                            else -> custom.lowColor
                        }
                    )
                else -> fill
            }
        }
    val colors =
        if (level == fill) {
            this
        } else {
            BatteryColors.Custom(
                glyph = glyph,
                fill = level,
                backgroundOnly = backgroundOnly,
                backgroundWithGlyph = backgroundWithGlyph,
                attribution = attribution,
            )
        }
    return BatteryPalette(frame = base, level = level, colors = colors)
}

/** Whether the percentage can be drawn inside the icon for [style]. */
internal fun styleFitsPercentInside(style: Int): Boolean =
    style == BatteryRepositoryImpl.BATTERY_STYLE_CIRCLE ||
        style == BatteryRepositoryImpl.BATTERY_STYLE_DOTTED_CIRCLE ||
        style == BatteryRepositoryImpl.BATTERY_STYLE_ARC ||
        style == BatteryRepositoryImpl.BATTERY_STYLE_HEXAGON

/**
 * Portrait, circle, dotted circle or filled circle battery. Sized from the incoming height; the
 * width follows the style's aspect ratio.
 *
 * [glyphsProvider] is the percentage to draw inside (only used by the circle styles).
 */
@Composable
internal fun IchthysBattery(
    style: Int,
    levelProvider: () -> Int?,
    attributionProvider: () -> BatteryGlyph?,
    glyphsProvider: () -> List<BatteryGlyph>,
    paletteProvider: () -> BatteryPalette,
    frameThicknessProvider: () -> Int,
    modifier: Modifier,
    contentDescription: String = "",
) {
    // IchthysOS: each style's width for the height it is given.
    val ratio =
        when (style) {
            BatteryRepositoryImpl.BATTERY_STYLE_CIRCLE,
            BatteryRepositoryImpl.BATTERY_STYLE_DOTTED_CIRCLE,
            BatteryRepositoryImpl.BATTERY_STYLE_FULL_CIRCLE,
            BatteryRepositoryImpl.BATTERY_STYLE_ARC,
            BatteryRepositoryImpl.BATTERY_STYLE_HEXAGON -> 1f
            BatteryRepositoryImpl.BATTERY_STYLE_SEGMENTS -> SEGMENTS_W / SEGMENTS_H
            BatteryRepositoryImpl.BATTERY_STYLE_BAR -> BAR_W / BAR_H
            else -> PORTRAIT_W / PORTRAIT_H
        }

    Canvas(
        modifier =
            modifier
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                // Offscreen so the BlendMode.Clear halo around glyphs only cuts this icon
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        contentDescription = contentDescription,
    ) {
        val palette = paletteProvider()
        val level = levelProvider()
        val attribution = attributionProvider()
        val step = frameThicknessProvider()
        when (style) {
            BatteryRepositoryImpl.BATTERY_STYLE_CIRCLE ->
                drawRing(level, attribution, glyphsProvider(), palette, step, dotted = false)
            BatteryRepositoryImpl.BATTERY_STYLE_DOTTED_CIRCLE ->
                drawRing(level, attribution, glyphsProvider(), palette, step, dotted = true)
            BatteryRepositoryImpl.BATTERY_STYLE_FULL_CIRCLE ->
                drawPie(level, attribution, palette, step)
            BatteryRepositoryImpl.BATTERY_STYLE_SEGMENTS ->
                drawSegments(level, attribution, palette, step)
            BatteryRepositoryImpl.BATTERY_STYLE_ARC ->
                drawArcGauge(level, attribution, glyphsProvider(), palette, step)
            BatteryRepositoryImpl.BATTERY_STYLE_BAR -> drawBar(level, attribution, palette, step)
            BatteryRepositoryImpl.BATTERY_STYLE_HEXAGON ->
                drawHexagon(level, attribution, glyphsProvider(), palette, step)
            else -> drawPortrait(level, attribution, palette, step)
        }
    }
}

// Classic ThemedBatteryDrawable canvas (config_batterymeterPerimeterPath is defined on 12x20)
private const val PORTRAIT_W = 12f
private const val PORTRAIT_H = 20f
private const val PORTRAIT_CORNER = 1.33f

private const val GLYPH_PADDING = 0.8f
private const val GLYPH_HALO = 2f

/** Frame thickness on the 12x20 canvas for steps 1..5: 1.0, 1.5, 2.0 (stock), 2.5, 3.0. */
private fun portraitThickness(step: Int): Float = 0.5f * (step + 1)

private fun DrawScope.drawPortrait(
    level: Int?,
    attribution: BatteryGlyph?,
    palette: BatteryPalette,
    step: Int,
) {
    val s = min(size.width / PORTRAIT_W, size.height / PORTRAIT_H)
    val t = portraitThickness(step)
    translate(
        left = (size.width - PORTRAIT_W * s) / 2f,
        top = (size.height - PORTRAIT_H * s) / 2f,
    ) {
        scale(s, pivot = Offset.Zero) {
            val outer = RoundRect(0f, 2f, PORTRAIT_W, PORTRAIT_H, CornerRadius(PORTRAIT_CORNER))
            val innerRadius = max(0.3f, PORTRAIT_CORNER - t / 2f)
            val inner =
                RoundRect(t, 2f + t, PORTRAIT_W - t, PORTRAIT_H - t, CornerRadius(innerRadius))

            // Frame ring + terminal
            val frame =
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRoundRect(outer)
                    addRoundRect(inner)
                }
            drawPath(frame, palette.frame)
            drawRoundRect(
                color = palette.frame,
                topLeft = Offset(3.5f, 0f),
                size = Size(5f, 2.5f),
                cornerRadius = CornerRadius(0.6f),
            )

            // Level, from the bottom. Like the stock drawable, 95 % and up reads as full.
            if (level != null && level > 0) {
                val top =
                    if (level >= 95) {
                        inner.top
                    } else {
                        inner.bottom - (inner.bottom - inner.top) * (level / 100f)
                    }
                clipRect(inner.left, top, inner.right, inner.bottom) {
                    drawPath(Path().apply { addRoundRect(inner) }, palette.level)
                }
            }

            attribution?.let {
                drawCenteredGlyph(
                    glyph = it,
                    box = Rect(inner.left, inner.top, inner.right, inner.bottom),
                    fraction = 0.8f,
                    color = palette.frame,
                )
            }
        }
    }
}

/** Ring stroke width as a fraction of the diameter for steps 1..5. */
private fun ringFraction(step: Int): Float = 0.07f + 0.025f * step

private fun DrawScope.drawRing(
    level: Int?,
    attribution: BatteryGlyph?,
    glyphs: List<BatteryGlyph>,
    palette: BatteryPalette,
    step: Int,
    dotted: Boolean,
) {
    val d = min(size.width, size.height)
    val sw = d * ringFraction(step)
    val r = (d - sw) / 2f
    val arcTopLeft = Offset(center.x - r, center.y - r)
    val arcSize = Size(2 * r, 2 * r)
    val effect =
        if (dotted) {
            val segment = (2f * PI.toFloat() * r) / DOTS
            PathEffect.dashPathEffect(floatArrayOf(segment * 0.5f, segment * 0.5f))
        } else {
            null
        }

    // Track, then the level arc clockwise from 12 o'clock
    drawCircle(
        color = palette.frame.copy(alpha = TRACK_ALPHA),
        radius = r,
        center = center,
        style = Stroke(width = sw, pathEffect = effect),
    )
    if (level != null && level > 0) {
        drawArc(
            color = palette.level,
            startAngle = -90f,
            sweepAngle = 360f * min(level, 100) / 100f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = sw, pathEffect = effect),
        )
    }

    // Inside: the percentage if requested, otherwise the charging / saver attribution
    val side = (r - sw / 2f) * 2f * 0.72f
    val box = Rect(center.x - side / 2f, center.y - side / 2f, center.x + side / 2f, center.y + side / 2f)
    if (glyphs.isNotEmpty()) {
        drawGlyphRow(glyphs, box, palette.frame)
    } else if (attribution != null) {
        drawCenteredGlyph(attribution, box, fraction = 0.9f, color = palette.frame, halo = false)
    }
}

private fun DrawScope.drawPie(
    level: Int?,
    attribution: BatteryGlyph?,
    palette: BatteryPalette,
    step: Int,
) {
    val d = min(size.width, size.height)
    val outline = d * ringFraction(step) / 2f
    val topLeft = Offset(center.x - d / 2f, center.y - d / 2f)

    drawCircle(color = palette.frame.copy(alpha = TRACK_ALPHA), radius = d / 2f, center = center)
    if (level != null && level > 0) {
        drawArc(
            color = palette.level,
            startAngle = -90f,
            sweepAngle = 360f * min(level, 100) / 100f,
            useCenter = true,
            topLeft = topLeft,
            size = Size(d, d),
        )
    }
    drawCircle(
        color = palette.frame,
        radius = (d - outline) / 2f,
        center = center,
        style = Stroke(width = outline),
    )

    attribution?.let {
        val side = d * 0.55f
        drawCenteredGlyph(
            glyph = it,
            box = Rect(center.x - side / 2f, center.y - side / 2f, center.x + side / 2f, center.y + side / 2f),
            fraction = 1f,
            color = palette.frame,
        )
    }
}

// IchthysOS styles, 2026-10-29. All on their own small canvases, scaled like the portrait one.

/** Segments: a landscape cell of four bars, lit from the left, each a quarter of the charge. */
private const val SEGMENTS_W = 22f
private const val SEGMENTS_H = 12f
private const val SEGMENT_COUNT = 4

private fun DrawScope.drawSegments(
    level: Int?,
    attribution: BatteryGlyph?,
    palette: BatteryPalette,
    step: Int,
) {
    val s = min(size.width / SEGMENTS_W, size.height / SEGMENTS_H)
    val t = portraitThickness(step) * 0.8f
    translate(
        left = (size.width - SEGMENTS_W * s) / 2f,
        top = (size.height - SEGMENTS_H * s) / 2f,
    ) {
        scale(s, pivot = Offset.Zero) {
            val bodyW = SEGMENTS_W - 2f
            val outer = RoundRect(0f, 0f, bodyW, SEGMENTS_H, CornerRadius(2f))
            val inner = RoundRect(t, t, bodyW - t, SEGMENTS_H - t, CornerRadius(max(0.4f, 2f - t)))
            drawPath(
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRoundRect(outer)
                    addRoundRect(inner)
                },
                palette.frame,
            )
            // Terminal on the right.
            drawRoundRect(
                color = palette.frame,
                topLeft = Offset(bodyW, SEGMENTS_H / 2f - 2.2f),
                size = Size(1.6f, 4.4f),
                cornerRadius = CornerRadius(0.5f),
            )
            val gap = 0.9f
            val cellW = (inner.width - gap * (SEGMENT_COUNT + 1)) / SEGMENT_COUNT
            val lit = if (level == null) 0 else ((level + 12) / 25).coerceIn(0, SEGMENT_COUNT)
            for (i in 0 until SEGMENT_COUNT) {
                val x = inner.left + gap + i * (cellW + gap)
                drawRoundRect(
                    color = if (i < lit) palette.level else palette.frame.copy(alpha = 0.22f),
                    topLeft = Offset(x, inner.top + gap),
                    size = Size(cellW, inner.height - 2f * gap),
                    cornerRadius = CornerRadius(0.7f),
                )
            }
            attribution?.let {
                drawCenteredGlyph(
                    glyph = it,
                    box = Rect(inner.left, inner.top, inner.right, inner.bottom),
                    fraction = 0.8f,
                    color = palette.frame,
                )
            }
        }
    }
}

/**
 * Arc: a gauge open at the bottom (270 degrees, from 7 to 5 o'clock), the level sweeping clockwise;
 * the percentage or the attribution sits in the opening's centre.
 */
private fun DrawScope.drawArcGauge(
    level: Int?,
    attribution: BatteryGlyph?,
    glyphs: List<BatteryGlyph>,
    palette: BatteryPalette,
    step: Int,
) {
    val d = min(size.width, size.height)
    val sw = d * ringFraction(step)
    val r = (d - sw) / 2f
    val arcTopLeft = Offset(center.x - r, center.y - r)
    val arcSize = Size(2 * r, 2 * r)
    val cap = androidx.compose.ui.graphics.StrokeCap.Round
    drawArc(
        color = palette.frame.copy(alpha = TRACK_ALPHA),
        startAngle = 135f,
        sweepAngle = 270f,
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcSize,
        style = Stroke(width = sw, cap = cap),
    )
    if (level != null && level > 0) {
        drawArc(
            color = palette.level,
            startAngle = 135f,
            sweepAngle = 270f * min(level, 100) / 100f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = sw, cap = cap),
        )
    }
    val side = (r - sw / 2f) * 2f * 0.78f
    val box = Rect(center.x - side / 2f, center.y - side / 2f, center.x + side / 2f, center.y + side / 2f)
    if (glyphs.isNotEmpty()) {
        drawGlyphRow(glyphs, box, palette.frame)
    } else if (attribution != null) {
        drawCenteredGlyph(attribution, box, fraction = 0.9f, color = palette.frame, halo = false)
    }
}

/** Bar: a thin horizontal track with the level as a rounded fill, no frame, no terminal. */
private const val BAR_W = 24f
private const val BAR_H = 12f

private fun DrawScope.drawBar(
    level: Int?,
    attribution: BatteryGlyph?,
    palette: BatteryPalette,
    step: Int,
) {
    val s = min(size.width / BAR_W, size.height / BAR_H)
    translate(left = (size.width - BAR_W * s) / 2f, top = (size.height - BAR_H * s) / 2f) {
        scale(s, pivot = Offset.Zero) {
            // Thickness steps 1..5: 3 .. 7 of the 12 high cell.
            val h = 2f + step
            val top = (BAR_H - h) / 2f
            drawRoundRect(
                color = palette.frame.copy(alpha = TRACK_ALPHA),
                topLeft = Offset(0f, top),
                size = Size(BAR_W, h),
                cornerRadius = CornerRadius(h / 2f),
            )
            if (level != null && level > 0) {
                val w = max(h, BAR_W * min(level, 100) / 100f)
                drawRoundRect(
                    color = palette.level,
                    topLeft = Offset(0f, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(h / 2f),
                )
            }
            attribution?.let {
                // Above the bar, small, so it never hides the level.
                drawCenteredGlyph(
                    glyph = it,
                    box = Rect(BAR_W / 2f - 3f, 0f, BAR_W / 2f + 3f, top - 0.5f),
                    fraction = 1f,
                    color = palette.frame,
                    halo = false,
                )
            }
        }
    }
}

/** Hexagon: a flat-topped hexagon outline, filling from the bottom; percent or attribution inside. */
private fun DrawScope.drawHexagon(
    level: Int?,
    attribution: BatteryGlyph?,
    glyphs: List<BatteryGlyph>,
    palette: BatteryPalette,
    step: Int,
) {
    val d = min(size.width, size.height)
    val sw = d * ringFraction(step) * 0.9f
    val r = (d - sw) / 2f
    fun hex(radius: Float): Path =
        Path().apply {
            for (i in 0 until 6) {
                val a = Math.toRadians(60.0 * i - 30.0)
                val x = center.x + radius * kotlin.math.cos(a).toFloat()
                val y = center.y + radius * kotlin.math.sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    val outline = hex(r)
    if (level != null && level > 0) {
        val inner = hex(r - sw * 0.9f)
        val top = center.y + r - 2f * r * min(level, 100) / 100f
        clipRect(center.x - r, top, center.x + r, center.y + r) { drawPath(inner, palette.level) }
    }
    drawPath(outline, palette.frame, style = Stroke(width = sw))
    val side = (r - sw) * 1.1f
    val box = Rect(center.x - side / 2f, center.y - side / 2f, center.x + side / 2f, center.y + side / 2f)
    if (glyphs.isNotEmpty()) {
        drawGlyphRow(glyphs, box, palette.frame)
    } else if (attribution != null) {
        drawCenteredGlyph(attribution, box, fraction = 0.85f, color = palette.frame)
    }
}

/** Draws [glyph] scaled to [fraction] of [box] and centered in it, with an optional clear halo. */
private fun DrawScope.drawCenteredGlyph(
    glyph: BatteryGlyph,
    box: Rect,
    fraction: Float,
    color: Color,
    halo: Boolean = true,
) {
    val gs = min(box.width * fraction / glyph.width, box.height * fraction / glyph.height)
    translate(
        left = box.left + (box.width - glyph.width * gs) / 2f,
        top = box.top + (box.height - glyph.height * gs) / 2f,
    ) {
        scale(gs, pivot = Offset.Zero) {
            if (halo) {
                drawPath(
                    path = glyph.path,
                    color = Color.Black,
                    style = Stroke(width = GLYPH_HALO),
                    blendMode = BlendMode.Clear,
                )
            }
            drawPath(glyph.path, color)
        }
    }
}

/** Draws the percentage digits as one row centered in [box]. */
private fun DrawScope.drawGlyphRow(glyphs: List<BatteryGlyph>, box: Rect, color: Color) {
    val totalWidth =
        glyphs.drop(1).fold(glyphs.first().width) { acc, next -> acc + GLYPH_PADDING + next.width }
    val maxHeight = glyphs.maxOf { it.height }
    val gs = min(box.width / totalWidth, box.height * 0.8f / maxHeight)
    var x = box.left + (box.width - totalWidth * gs) / 2f
    for (glyph in glyphs) {
        translate(left = x, top = box.top + (box.height - glyph.height * gs) / 2f) {
            scale(gs, pivot = Offset.Zero) { drawPath(glyph.path, color) }
        }
        x += (glyph.width + GLYPH_PADDING) * gs
    }
}

private const val DOTS = 18
private const val TRACK_ALPHA = 0.3f
