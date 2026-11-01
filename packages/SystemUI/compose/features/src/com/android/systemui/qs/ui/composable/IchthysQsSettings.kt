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

package com.android.systemui.qs.ui.composable

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import lineageos.providers.LineageSettings

/**
 * IchthysOS: crDroid QS options for the scene container. They used to be read only by
 * QSFragmentCompose, which is never attached while scene_container is on.
 */
object IchthysQsSettings {
    /** Settings.System, 1..100 percent, default 100 = opaque. Scales the shade/QS background. */
    const val QS_TRANSPARENCY = "qs_transparency"

    /** LineageSettings.Secure: 0 = never, 1 = when expanded, 2 = always. */
    const val SHOW_BRIGHTNESS_SLIDER = LineageSettings.Secure.QS_SHOW_BRIGHTNESS_SLIDER

    /** LineageSettings.Secure: 0 = top, 1 = bottom of the QS panel. */
    const val BRIGHTNESS_SLIDER_POSITION = LineageSettings.Secure.QS_BRIGHTNESS_SLIDER_POSITION

    /** Settings.System: 1 = the notes page left of the tiles (IchthysQsNotesPage), default on. */
    const val NOTES_PAGE = "qs_notes_page"
}

/** IchthysOS: observes a Settings.System int for the current user. */
@Composable
fun rememberIchthysSystemInt(key: String, default: Int): Int =
    rememberObservedInt(key, Settings.System.getUriFor(key)) { resolver ->
        Settings.System.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT)
    }

/** IchthysOS: observes a LineageSettings.Secure int for the current user. */
@Composable
fun rememberIchthysLineageSecureInt(key: String, default: Int): Int =
    rememberObservedInt(key, LineageSettings.Secure.getUriFor(key)) { resolver ->
        LineageSettings.Secure.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT)
    }

@Composable
private fun rememberObservedInt(key: String, uri: Uri, read: (ContentResolver) -> Int): Int {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val value by
        produceState(initialValue = read(resolver), context, key) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = read(resolver)
                    }
                }
            resolver.registerContentObserver(uri, false, observer, UserHandle.USER_ALL)
            value = read(resolver)
            awaitDispose { resolver.unregisterContentObserver(observer) }
        }
    return value
}
