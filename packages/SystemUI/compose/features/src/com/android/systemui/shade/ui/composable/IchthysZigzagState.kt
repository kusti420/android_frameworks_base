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

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.statusbar.phone.IchthysZigzag

/**
 * IchthysOS "Shoal": whether the zig-zag status bar layout (Settings.Secure
 * ichthys_statusbar_layout = "zigzag") is on, kept up to date while in composition.
 */
@Composable
fun rememberIchthysZigzag(): Boolean {
    val context = LocalContext.current
    var zigzag by remember(context) { mutableStateOf(IchthysZigzag.isEnabled(context)) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    zigzag = IchthysZigzag.isEnabled(context)
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(IchthysZigzag.LAYOUT_SETTING),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        zigzag = IchthysZigzag.isEnabled(context)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return zigzag
}
