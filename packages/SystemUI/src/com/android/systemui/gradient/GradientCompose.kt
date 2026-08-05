/*
 * Copyright (C) 2026 The IchthysOS Project
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

package com.android.systemui.gradient

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/**
 * IchthysOS: Compose front-end for [GradientColors].
 *
 * Kept separate from `GradientPalette.kt` so View-based consumers (brightness / volume sliders) can
 * use the palette without pulling Compose in.
 */

/**
 * The current gradient configuration, re-read whenever any of [GradientColors.KEYS] changes.
 *
 * Prefer this over [rememberGradientPalette] when the base color animates: the returned
 * [GradientConfig] is stable across frames, so calling [GradientConfig.paletteFor] per frame costs
 * nothing, whereas re-keying on an animating color would tear down and rebuild the observer.
 */
@Composable
fun rememberGradientConfig(): GradientConfig {
    val resolver = LocalContext.current.contentResolver
    val config by
        produceState(initialValue = GradientColors.config(resolver), resolver) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = GradientColors.config(resolver)
                    }
                }
            GradientColors.registerObserver(resolver, observer)
            value = GradientColors.config(resolver)
            awaitDispose { GradientColors.unregisterObserver(resolver, observer) }
        }
    return config
}

/** The palette for an element painted [baseColor]. Reactive — see [rememberGradientConfig]. */
@Composable
fun rememberGradientPalette(baseColor: Color): GradientPalette =
    rememberGradientConfig().paletteFor(baseColor.toArgb())

/** The palette for an element painted [baseColor]. */
fun GradientConfig.paletteFor(baseColor: Color): GradientPalette = paletteFor(baseColor.toArgb())

/** [GradientPalette.startColor] as a Compose [Color]. */
val GradientPalette.start: Color
    get() = Color(startColor)

/** [GradientPalette.endColor] as a Compose [Color]. */
val GradientPalette.end: Color
    get() = Color(endColor)

/** The two stops, start first — for [Brush] factories that take a color list. */
fun GradientPalette.toColorList(): List<Color> = listOf(start, end)

/** Top-left to bottom-right sweep; the default look for a QS tile / icon bubble. */
fun GradientPalette.diagonalBrush(): Brush =
    Brush.linearGradient(colors = toColorList(), start = Offset.Zero, end = Offset.Infinite)

/** Left-to-right sweep; the natural direction for horizontal sliders. */
fun GradientPalette.horizontalBrush(): Brush = Brush.horizontalGradient(toColorList())

/** Top-to-bottom sweep. */
fun GradientPalette.verticalBrush(): Brush = Brush.verticalGradient(toColorList())
