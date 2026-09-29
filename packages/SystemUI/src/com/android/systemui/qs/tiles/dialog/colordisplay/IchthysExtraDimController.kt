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

package com.android.systemui.qs.tiles.dialog.colordisplay

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.ColorDisplayManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * IchthysOS Extra Dim (Reduce Bright Colors) control for the long-press dialog: on/off, intensity
 * ([ColorDisplayManager.setReduceBrightColorsStrength]) and
 * [Settings.Secure.REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS].
 */
@SysUISingleton
class IchthysExtraDimController
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Background private val bgContext: CoroutineContext,
    private val userTracker: UserTracker,
) {
    data class State(
        val activated: Boolean = false,
        val strength: Int = 0,
        val persist: Boolean = false,
    )

    private val cdm: ColorDisplayManager? = context.getSystemService(ColorDisplayManager::class.java)

    val minStrength: Int = ColorDisplayManager.getMinimumReduceBrightColorsStrength(context)
    val maxStrength: Int = ColorDisplayManager.getMaximumReduceBrightColorsStrength(context)

    private val strengthRequests =
        MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch(bgContext) {
            strengthRequests.conflate().collect { s ->
                try {
                    cdm?.setReduceBrightColorsStrength(s)
                } catch (e: Exception) {
                    Log.w(TAG, "setReduceBrightColorsStrength($s) failed", e)
                }
            }
        }
    }

    /** Reads the current state (binder / settings calls; call off the main thread). */
    fun readState(): State {
        val m = cdm ?: return State()
        return State(
            activated = m.isReduceBrightColorsActivated,
            strength = m.reduceBrightColorsStrength,
            persist =
                Settings.Secure.getIntForUser(
                    context.contentResolver,
                    Settings.Secure.REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS,
                    0,
                    userTracker.userId,
                ) != 0,
        )
    }

    /** Emits the state now and whenever an Extra Dim setting changes for any user. */
    fun state(): Flow<State> =
        callbackFlow {
                val observer =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }
                val cr = context.contentResolver
                for (key in OBSERVED_KEYS) {
                    cr.registerContentObserver(
                        Settings.Secure.getUriFor(key),
                        false,
                        observer,
                        UserHandle.USER_ALL,
                    )
                }
                trySend(Unit)
                awaitClose { cr.unregisterContentObserver(observer) }
            }
            .conflate()
            .map { readState() }
            .flowOn(bgContext)

    fun setActivated(activated: Boolean) {
        scope.launch(bgContext) { cdm?.setReduceBrightColorsActivated(activated) }
    }

    /** Sets the intensity (clamped to the device range); applied asynchronously. */
    fun setStrength(strength: Int) {
        strengthRequests.tryEmit(strength.coerceIn(minStrength, maxStrength))
    }

    fun setPersist(persist: Boolean) {
        scope.launch(bgContext) {
            Settings.Secure.putIntForUser(
                context.contentResolver,
                Settings.Secure.REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS,
                if (persist) 1 else 0,
                userTracker.userId,
            )
        }
    }

    private companion object {
        const val TAG = "IchthysExtraDim"
        val OBSERVED_KEYS =
            arrayOf(
                Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED,
                Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL,
                Settings.Secure.REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS,
            )
    }
}
