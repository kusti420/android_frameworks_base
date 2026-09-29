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
import android.hardware.display.ColorDisplayManager.AUTO_MODE_CUSTOM_TIME
import android.hardware.display.ColorDisplayManager.AUTO_MODE_DISABLED
import android.hardware.display.ColorDisplayManager.AUTO_MODE_TWILIGHT
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IchthysOS "f.lux style" Night Light control shared by the QS tile (tap cycles
 * [Mode.OFF] -> [Mode.ON] -> [Mode.SCHEDULED] -> [Mode.OFF]) and the long-press dialog.
 *
 * Mapping onto [ColorDisplayManager]:
 * - [Mode.OFF]: auto mode [AUTO_MODE_DISABLED], not activated.
 * - [Mode.ON]: auto mode [AUTO_MODE_DISABLED], activated.
 * - [Mode.SCHEDULED]: auto mode [AUTO_MODE_TWILIGHT] or [AUTO_MODE_CUSTOM_TIME] (the schedule set
 *   in Settings; the activation state then follows it). Leaving SCHEDULED switches the auto mode
 *   off, so the last schedule type is remembered per user and restored the next time; with nothing
 *   remembered it falls back to twilight (sunset to sunrise).
 *
 * Changing the auto mode through ColorDisplayService clears NIGHT_DISPLAY_LAST_ACTIVATED_TIME, so
 * entering SCHEDULED follows the schedule immediately instead of keeping a manual override.
 */
@SysUISingleton
class IchthysNightLightController
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Background private val bgContext: CoroutineContext,
    private val userTracker: UserTracker,
    private val userFileManager: UserFileManager,
) {
    enum class Mode {
        OFF,
        ON,
        SCHEDULED,
    }

    data class State(
        val autoMode: Int = AUTO_MODE_DISABLED,
        val activated: Boolean = false,
        val temperature: Int = 0,
    ) {
        val mode: Mode
            get() = modeOf(autoMode, activated)
    }

    private val cdm: ColorDisplayManager? = context.getSystemService(ColorDisplayManager::class.java)

    val minTemperature: Int = ColorDisplayManager.getMinimumColorTemperature(context)
    val maxTemperature: Int = ColorDisplayManager.getMaximumColorTemperature(context)

    private val _previewing = MutableStateFlow(false)
    /** True while the dialog turned Night Light on only to preview the temperature. */
    val previewing: StateFlow<Boolean> = _previewing.asStateFlow()

    private val temperatureRequests =
        MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        // Slider drags produce many values; apply them in order on the background thread, dropping
        // stale ones.
        scope.launch(bgContext) {
            temperatureRequests.conflate().collect { k ->
                try {
                    cdm?.setNightDisplayColorTemperature(k)
                } catch (e: Exception) {
                    Log.w(TAG, "setNightDisplayColorTemperature($k) failed", e)
                }
            }
        }
    }

    /** Reads the current state (binder calls; call off the main thread). */
    fun readState(): State {
        val m = cdm ?: return State()
        val state =
            State(
                autoMode = m.nightDisplayAutoMode,
                activated = m.isNightDisplayActivated,
                temperature = m.nightDisplayColorTemperature,
            )
        rememberSchedule(state.autoMode)
        return state
    }

    /** Emits the state now and whenever a Night Light setting changes for any user. */
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

    /** Tap on the tile: OFF -> ON -> SCHEDULED -> OFF. */
    suspend fun cycle(current: Mode) {
        setMode(
            when (current) {
                Mode.OFF -> Mode.ON
                Mode.ON -> Mode.SCHEDULED
                Mode.SCHEDULED -> Mode.OFF
            }
        )
    }

    suspend fun setMode(target: Mode): Unit =
        withContext(bgContext) {
            val m = cdm ?: return@withContext
            _previewing.value = false
            val autoMode = m.nightDisplayAutoMode
            rememberSchedule(autoMode)
            when (target) {
                Mode.OFF -> {
                    if (autoMode != AUTO_MODE_DISABLED) m.setNightDisplayAutoMode(AUTO_MODE_DISABLED)
                    m.setNightDisplayActivated(false)
                }
                Mode.ON -> {
                    // Also writes the raw auto mode, which opts out of a forced default schedule.
                    m.setNightDisplayAutoMode(AUTO_MODE_DISABLED)
                    m.setNightDisplayActivated(true)
                }
                Mode.SCHEDULED -> {
                    if (autoMode == AUTO_MODE_DISABLED) {
                        m.setNightDisplayAutoMode(rememberedSchedule())
                    }
                }
            }
        }

    /** [setMode] on the application scope (safe to call from a dialog that may be closing). */
    fun requestMode(target: Mode) {
        scope.launch { setMode(target) }
    }

    /** [beginPreviewIfOff] on the application scope. */
    fun requestPreview() {
        scope.launch { beginPreviewIfOff() }
    }

    /** Sets the colour temperature (clamped to the device range); applied asynchronously. */
    fun setTemperature(kelvin: Int) {
        temperatureRequests.tryEmit(kelvin.coerceIn(minTemperature, maxTemperature))
    }

    /** Turns Night Light on to preview a temperature if it is off; [endPreview] undoes it. */
    suspend fun beginPreviewIfOff(): Unit =
        withContext(bgContext) {
            val m = cdm ?: return@withContext
            if (!_previewing.value && !m.isNightDisplayActivated) {
                _previewing.value = true
                m.setNightDisplayActivated(true)
            }
        }

    /** Ends a preview started by [beginPreviewIfOff], turning Night Light off again. */
    fun endPreview() {
        if (!_previewing.value) return
        _previewing.value = false
        scope.launch(bgContext) { cdm?.setNightDisplayActivated(false) }
    }

    private fun prefs() =
        userFileManager.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE, userTracker.userId)

    private fun rememberSchedule(autoMode: Int) {
        if (autoMode != AUTO_MODE_TWILIGHT && autoMode != AUTO_MODE_CUSTOM_TIME) return
        try {
            val p = prefs()
            if (p.getInt(KEY_SCHEDULE, AUTO_MODE_DISABLED) != autoMode) {
                p.edit().putInt(KEY_SCHEDULE, autoMode).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not remember the Night Light schedule", e)
        }
    }

    private fun rememberedSchedule(): Int =
        try {
            when (val s = prefs().getInt(KEY_SCHEDULE, AUTO_MODE_TWILIGHT)) {
                AUTO_MODE_CUSTOM_TIME,
                AUTO_MODE_TWILIGHT -> s
                else -> AUTO_MODE_TWILIGHT
            }
        } catch (e: Exception) {
            AUTO_MODE_TWILIGHT
        }

    companion object {
        private const val TAG = "IchthysNightLight"
        private const val PREFS_FILE = "ichthys_night_light"
        private const val KEY_SCHEDULE = "last_schedule_auto_mode"

        private val OBSERVED_KEYS =
            arrayOf(
                Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
                Settings.Secure.NIGHT_DISPLAY_AUTO_MODE,
                Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
            )

        fun modeOf(autoMode: Int, activated: Boolean): Mode =
            when {
                autoMode == AUTO_MODE_TWILIGHT || autoMode == AUTO_MODE_CUSTOM_TIME ->
                    Mode.SCHEDULED
                activated -> Mode.ON
                else -> Mode.OFF
            }
    }
}
