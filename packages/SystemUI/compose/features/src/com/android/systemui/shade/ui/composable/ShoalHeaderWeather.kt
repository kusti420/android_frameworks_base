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

package com.android.systemui.shade.ui.composable

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.internal.util.crdroid.OmniJawsClient
import com.android.systemui.qs.rememberSecureSettingBoolean
import com.android.systemui.shade.ui.ShoalShade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the header shows of the weather; null fields are left out. */
private data class ShoalHeaderWeatherState(
    val temperature: String,
    val icon: Drawable?,
    /** Today's "18° / 9°", or the condition in words when there is no forecast. */
    val detail: String?,
    val description: String,
)

/**
 * IchthysOS "Shoal": the weather in the shade header, a size up from the status bar's two-row
 * readout it replaces while the shade is open: the condition glyph, the temperature in the mono
 * face, and today's high and low below it. Tapping it opens the weather page.
 *
 * Shows nothing while it is switched off (quickSettings.header.weather), the weather service is
 * off or there is no data ([fallback] then shows instead). The data is the same OmniJaws
 * the status bar weather reads.
 */
@Composable
fun ShoalHeaderWeather(
    textColor: Color,
    mutedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit = {},
) {
    // quickSettings.header.weather; unset means on.
    if (!rememberSecureSettingBoolean(SETTING_ENABLED, default = true)) {
        fallback()
        return
    }
    val context = LocalContext.current
    val state = rememberShoalHeaderWeather(context)
    if (state == null) {
        fallback()
        return
    }
    val monoFamily =
        remember(context) {
            FontFamily(
                Font(DeviceFontFamilyName(ShoalShade.monoFamily(context)), FontWeight.SemiBold)
            )
        }
    val textFamily =
        remember(context) {
            FontFamily(Font(DeviceFontFamilyName(ShoalShade.textFamily(context))))
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IconGap),
        modifier =
            modifier
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = state.description },
    ) {
        if (state.icon != null) {
            Image(
                painter = rememberDrawablePainter(state.icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(textColor),
                modifier = Modifier.size(IconSize),
            )
        }
        Column(horizontalAlignment = Alignment.Start) {
            BasicText(
                text = state.temperature,
                style =
                    TextStyle(
                        color = textColor,
                        fontFamily = monoFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        lineHeight = 22.sp,
                    ),
                maxLines = 1,
            )
            if (state.detail != null) {
                BasicText(
                    text = state.detail,
                    style =
                        TextStyle(
                            color = mutedColor,
                            fontFamily = textFamily,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                        ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The current weather, re-read whenever OmniJaws reports an update; null while there is none. */
@Composable
private fun rememberShoalHeaderWeather(context: Context): ShoalHeaderWeatherState? {
    var state by remember { mutableStateOf<ShoalHeaderWeatherState?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(context) {
        val client = OmniJawsClient.get()
        fun refresh() {
            scope.launch {
                // A content provider query and an icon pack lookup: not on the main thread.
                state = withContext(Dispatchers.IO) { readWeather(context, client) }
            }
        }
        val observer =
            object : OmniJawsClient.OmniJawsObserver {
                override fun weatherUpdated() = refresh()

                override fun weatherError(errorReason: Int) {
                    if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) state = null
                }
            }
        client.addObserver(context, observer)
        refresh()
        onDispose { client.removeObserver(context, observer) }
    }
    return state
}

private fun readWeather(context: Context, client: OmniJawsClient): ShoalHeaderWeatherState? {
    return try {
        if (!client.isOmniJawsEnabled(context)) return null
        client.queryWeather(context)
        val info = client.weatherInfo ?: return null
        val temperature = info.temp + "°"
        val today = info.forecasts?.firstOrNull()
        val detail =
            if (today != null && !today.high.isNullOrEmpty() && !today.low.isNullOrEmpty()) {
                today.high + "° / " + today.low + "°"
            } else {
                info.condition?.takeIf { it.isNotEmpty() }
            }
        ShoalHeaderWeatherState(
            temperature = temperature,
            icon = client.getWeatherConditionImage(context, info.conditionCode)?.mutate(),
            detail = detail,
            description =
                listOfNotNull(info.city, info.condition, temperature)
                    .filter { it.isNotEmpty() }
                    .joinToString(", "),
        )
    } catch (e: Exception) {
        // The provider can be mid-update or the service gone; show nothing rather than stale data.
        null
    }
}

/** Settings.Secure, per user: show the weather in the shade header. */
private const val SETTING_ENABLED = "ichthys_qs_header_weather"
private val IconSize = 24.dp
private val IconGap = 6.dp
