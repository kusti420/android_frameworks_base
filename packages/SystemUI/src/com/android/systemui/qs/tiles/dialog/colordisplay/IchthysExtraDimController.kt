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

import android.view.accessibility.AccessibilityManager
import com.android.internal.accessibility.AccessibilityShortcutController
import com.android.internal.accessibility.common.ShortcutConstants
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
 * and [Settings.Secure.REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS].
 *
 * The intensity is exposed exactly like Settings (Accessibility > Extra dim > Intensity, see
 * Settings' extradim IntensityDataStore): a 0..100 % slider whose value is
 * `100 - Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL` (unset level reads as 0), so left = dimmer and
 * right = brighter. Note [ColorDisplayManager.getReduceBrightColorsStrength] returns the level
 * already mapped into the device's config_reduceBrightColorsStrengthMin..Max range, so it must
 * not be used for display; [ColorDisplayManager.setReduceBrightColorsStrength] writes the raw
 * level.
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
        /** Settings-identical intensity percent (0 = dimmest, 100 = brightest). */
        val intensityPercent: Int = PERCENT_MAX,
        val persist: Boolean = false,
        /** Accessibility hardware shortcut (hold both volume keys) toggles Extra Dim. */
        val volumeShortcut: Boolean = false,
    )

    private val cdm: ColorDisplayManager? = context.getSystemService(ColorDisplayManager::class.java)

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
        if (cdm == null) return State()
        return State(
            // IchthysOS: the setting, not ColorDisplayManager.isReduceBrightColorsActivated, which
            // ColorDisplayService updates asynchronously after a toggle (the switch showed the old
            // state); the tile reads the setting for the same reason. It is also a cached
            // settings read rather than a binder call.
            activated =
                Settings.Secure.getIntForUser(
                    context.contentResolver,
                    Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED,
                    0,
                    userTracker.userId,
                ) != 0,
            intensityPercent =
                PERCENT_MAX -
                    Settings.Secure.getIntForUser(
                            context.contentResolver,
                            Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL,
                            0,
                            userTracker.userId,
                        )
                        .coerceIn(PERCENT_MIN, PERCENT_MAX),
            persist =
                Settings.Secure.getIntForUser(
                    context.contentResolver,
                    Settings.Secure.REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS,
                    0,
                    userTracker.userId,
                ) != 0,
            volumeShortcut =
                Settings.Secure.getStringForUser(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                        userTracker.userId,
                    )
                    ?.split(':')
                    ?.contains(SHORTCUT_TARGET) == true,
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

    /**
     * Sets the intensity as the Settings slider does (percent 0..100, higher = brighter); applied
     * asynchronously.
     */
    fun setIntensityPercent(percent: Int) {
        strengthRequests.tryEmit(PERCENT_MAX - percent.coerceIn(PERCENT_MIN, PERCENT_MAX))
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

    /**
     * Assigns (or removes) Extra Dim on the accessibility volume-keys shortcut, exactly what
     * Settings' "Hold volume keys" option for Extra Dim does. Other shortcut targets are kept.
     */
    fun setVolumeShortcut(enabled: Boolean) {
        scope.launch(bgContext) {
            try {
                val am = context.getSystemService(AccessibilityManager::class.java) ?: return@launch
                am.enableShortcutsForTargets(
                    enabled,
                    ShortcutConstants.UserShortcutType.HARDWARE,
                    setOf(SHORTCUT_TARGET),
                    userTracker.userId,
                )
                if (enabled) {
                    // The owner chose it here, so skip the one-time "use shortcut?" confirmation.
                    Settings.Secure.putIntForUser(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN,
                        1,
                        userTracker.userId,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "volume shortcut update failed", e)
            }
        }
    }

    companion object {
        private val SHORTCUT_TARGET: String =
            AccessibilityShortcutController.REDUCE_BRIGHT_COLORS_COMPONENT_NAME.flattenToString()
        /** Slider range, identical to Settings' extradim IntensityDataStore MIN/MAX_VALUE. */
        const val PERCENT_MIN = 0
        const val PERCENT_MAX = 100
        private const val TAG = "IchthysExtraDim"
        private val OBSERVED_KEYS =
            arrayOf(
                Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED,
                Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL,
                Settings.Secure.REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS,
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
            )
    }
}
