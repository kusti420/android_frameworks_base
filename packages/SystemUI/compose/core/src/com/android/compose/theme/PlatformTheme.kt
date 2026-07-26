/*
 * Copyright (C) 2022 The Android Open Source Project
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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.compose.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.android.app.tracing.TraceUtils
import com.android.compose.theme.AndroidColorScheme.Companion.color
import com.android.compose.theme.typography.TypeScaleTokens
import com.android.compose.theme.typography.TypefaceNames
import com.android.compose.theme.typography.TypefaceTokens
import com.android.compose.theme.typography.TypographyTokens
import com.android.compose.theme.typography.VariableFontTypeScaleEmphasizedTokens
import com.android.compose.theme.typography.platformTypography
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.compose.windowsizeclass.calculateWindowSizeClass
import com.android.internal.R

/** The Material 3 theme that should wrap all Platform Composables. */
@Composable
fun PlatformTheme(isDarkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current

    // Force recreate the color schemes if the theme might have changed.
    val currentAssetsSeq = LocalConfiguration.current.assetsSeq
    val colorScheme =
        remember(context, isDarkTheme, currentAssetsSeq) {
            TraceUtils.trace("PlatformTheme.colorScheme") {
                platformColorScheme(isDarkTheme, context)
            }
        }
    val androidColorScheme =
        remember(context, isDarkTheme, currentAssetsSeq) {
            TraceUtils.trace("PlatformTheme.androidColorScheme") { AndroidColorScheme(context) }
        }

    val typefaceNames = remember(context) { TypefaceNames.get(context) }
    val typefaceTokens = remember(typefaceNames) { TypefaceTokens(typefaceNames) }
    val typography =
        remember(typefaceTokens) {
            platformTypography(
                TypographyTokens(
                    TypeScaleTokens(typefaceTokens),
                    VariableFontTypeScaleEmphasizedTokens(typefaceTokens),
                )
            )
        }
    val windowSizeClass = calculateWindowSizeClass()

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        motionScheme = ExpressiveMotionScheme,
    ) {
        CompositionLocalProvider(
            LocalAndroidColorScheme provides androidColorScheme,
            LocalWindowSizeClass provides windowSizeClass,
            content = content,
        )
    }
}

private fun platformColorScheme(isDarkTheme: Boolean, context: Context): ColorScheme {
    return if (isDarkTheme) {
        val scheme =
            dynamicDarkColorScheme(context)
                .copy(
                    inverseSurface = color(context, R.color.system_inverse_surface_dark),
                    inverseOnSurface = color(context, R.color.system_inverse_on_surface_dark),
                    inversePrimary = color(context, R.color.system_inverse_primary_dark),
                    error = color(context, R.color.system_error_dark),
                    onError = color(context, R.color.system_on_error_dark),
                    errorContainer = color(context, R.color.system_error_container_dark),
                    onErrorContainer = color(context, R.color.system_on_error_container_dark),
                )
        // Custom Options "pitch black": pure-black surfaces, white text, and the vivid seed accent
        // (overriding Monet's pale dark-mode tones).
        if (Settings.Secure.getInt(context.contentResolver, "custom_pitch_black", 0) == 1) {
            val accent = pitchBlackAccent(context)
            scheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceDim = Color.Black,
                surfaceBright = Color.Black,
                surfaceContainer = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerHigh = Color.Black,
                surfaceContainerHighest = Color.Black,
                surfaceVariant = Color.Black,
                onSurface = Color.White,
                onSurfaceVariant = Color.White,
                onBackground = Color.White,
                primary = accent ?: scheme.primary,
                onPrimary = Color.White,
                surfaceTint = accent ?: scheme.surfaceTint,
            )
        } else {
            scheme
        }
    } else {
        dynamicLightColorScheme(context)
            .copy(
                inverseSurface = color(context, R.color.system_inverse_surface_light),
                inverseOnSurface = color(context, R.color.system_inverse_on_surface_light),
                inversePrimary = color(context, R.color.system_inverse_primary_light),
                error = color(context, R.color.system_error_light),
                onError = color(context, R.color.system_on_error_light),
                errorContainer = color(context, R.color.system_error_container_light),
                onErrorContainer = color(context, R.color.system_on_error_container_light),
            )
    }
}

/** The exact accent seed the user picked (Custom Options), so the accent is vivid, not Monet-pale. */
private fun pitchBlackAccent(context: Context): Color? {
    return try {
        val json =
            Settings.Secure.getString(
                context.contentResolver,
                "theme_customization_overlay_packages",
            ) ?: return null
        val key = "system_palette"
        val i = json.indexOf(key)
        if (i < 0) return null
        val q1 = json.indexOf('"', json.indexOf(':', i)) + 1
        val q2 = json.indexOf('"', q1)
        // system_palette is stored as 6-hex RGB (no alpha); force opaque alpha so the accent
        // isn't parsed as fully-transparent (which made pitch-black accents render black).
        Color((0xFF000000L or json.substring(q1, q2).toLong(16)).toInt())
    } catch (e: Exception) {
        null
    }
}

private val ExpressiveMotionScheme = MotionScheme.expressive()
