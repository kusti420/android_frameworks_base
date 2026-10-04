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

package com.android.systemui.qs

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext

/**
 * IchthysOS: reads for the crDroid Quick Settings preferences that ship in crDroidSettings but
 * never had a SystemUI consumer.
 *
 * The preference classes in crDroidSettings (`SystemSettingSwitchPreference`,
 * `SystemSettingListPreference`) persist the preference key verbatim into `Settings.System`, so the
 * keys are plain strings here rather than `android.provider.Settings.System` constants; that keeps
 * these features SystemUI-only changes needing no `framework.jar` rebuild.
 *
 * Every read goes through the `*ForUser` variants with [UserHandle.USER_CURRENT]. The shade runs in
 * a per-display window context whose `getUserId()` is **not** the foreground user, so the plain
 * (calling-user) overloads can silently resolve to the wrong user and hand back the default with no
 * log at all. This mirrors the note in `BrightnessSlider.kt`.
 */
private fun readInt(cr: ContentResolver, key: String, default: Int): Int =
    Settings.System.getIntForUser(cr, key, default, UserHandle.USER_CURRENT)

private fun readSecureInt(cr: ContentResolver, key: String, default: Int): Int =
    Settings.Secure.getIntForUser(cr, key, default, UserHandle.USER_CURRENT)

/** Observes a `Settings.System` integer, recomposing when it changes. */
@Composable
fun rememberSystemSettingInt(key: String, default: Int): Int {
    val cr = LocalContext.current.contentResolver
    val value by
        produceState(initialValue = readInt(cr, key, default), key, cr) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = readInt(cr, key, default)
                    }
                }
            cr.registerContentObserver(Settings.System.getUriFor(key), false, observer)
            // Re-read after registering: the value can change between the initial read and the
            // observer being live.
            value = readInt(cr, key, default)
            awaitDispose { cr.unregisterContentObserver(observer) }
        }
    return value
}

/**
 * Observes a `Settings.System` switch, recomposing when it changes.
 *
 * crDroid switches store 1/0 and their XML `defaultValue` decides what an *unwritten* setting
 * means, so [default] must match the preference XML or the feature will look inverted until the
 * user first toggles it.
 */
@Composable
fun rememberSystemSettingBoolean(key: String, default: Boolean): Boolean =
    rememberSystemSettingInt(key, if (default) 1 else 0) != 0

/**
 * Observes a `Settings.Secure` integer of the current user, recomposing when it changes. The
 * `Settings.Secure` twin of [rememberSystemSettingInt], for toggles the Themes screens store there
 * (e.g. `qs_accent_icon_bg`).
 */
@Composable
fun rememberSecureSettingInt(key: String, default: Int): Int {
    val cr = LocalContext.current.contentResolver
    val value by
        produceState(initialValue = readSecureInt(cr, key, default), key, cr) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        value = readSecureInt(cr, key, default)
                    }
                }
            cr.registerContentObserver(
                Settings.Secure.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            // Re-read after registering, as in rememberSystemSettingInt.
            value = readSecureInt(cr, key, default)
            awaitDispose { cr.unregisterContentObserver(observer) }
        }
    return value
}

/** Observes a `Settings.Secure` switch (1/0), recomposing when it changes. */
@Composable
fun rememberSecureSettingBoolean(key: String, default: Boolean): Boolean =
    rememberSecureSettingInt(key, if (default) 1 else 0) != 0
