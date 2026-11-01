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

package com.android.systemui.keyguard.data.repository

import android.content.Context
import android.os.UserHandle
import com.android.settingslib.ichthys.clock.LockNotifications
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * IchthysOS lock screen notifications (the Quiet dock, tracker#58): how the owner wants them on
 * the lock screen ([LockNotifications]'s Settings.Secure keys, for the current user) and whether
 * the dock's sheet (the stack over the clock) is open right now.
 */
@SysUISingleton
class IchthysLockNotifRepository
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
) {
    /** The owner's choices (see [LockNotifications]). */
    data class Settings(
        val mode: Int = LockNotifications.MODE_DOCK,
        /** Percent of the screen's height; [LockNotifications.ANCHOR_AUTO] follows the clock. */
        val anchorPercent: Int = LockNotifications.ANCHOR_AUTO,
        val dockDim: Boolean = true,
    ) {
        val cards: Boolean
            get() = mode == LockNotifications.MODE_CARDS

        val glance: Boolean
            get() = mode == LockNotifications.MODE_GLANCE
    }

    private fun read(): Settings {
        val cr = context.contentResolver
        val user = UserHandle.USER_CURRENT
        return Settings(
            mode = LockNotifications.readMode(cr, user),
            anchorPercent = LockNotifications.readAnchor(cr, user),
            dockDim = LockNotifications.readDockDim(cr, user),
        )
    }

    /** Read once up front (one cached settings read), then followed live. */
    val settings: StateFlow<Settings> =
        merge(
                secureSettings.observerFlow(
                    names =
                        arrayOf(
                            LockNotifications.KEY_MODE,
                            LockNotifications.KEY_ANCHOR,
                            LockNotifications.KEY_DOCK_DIM,
                        ),
                    userId = UserHandle.USER_ALL,
                ),
                userChanges(),
            )
            .onStart { emit(Unit) }
            .map { read() }
            .distinctUntilChanged()
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.Eagerly, read())

    private val _sheetOpen = MutableStateFlow(false)

    /**
     * The dock's sheet is open: the stack slides over the clock. Only ever true while the lock
     * screen shows (the lock screen root closes it as it leaves, dozes or starts unlocking).
     */
    val sheetOpen: StateFlow<Boolean> = _sheetOpen.asStateFlow()

    fun setSheetOpen(open: Boolean) {
        _sheetOpen.value = open
    }

    /**
     * The lock screen's keep-out (the sensor or lock icon plus its margin) and the dock, window
     * px, as the lock screen root laid them out last; zero before the first layout. For whatever
     * draws in window coordinates and must stay out of them.
     */
    @Volatile var keepOutTop = 0
    @Volatile var dockTop = 0

    private fun userChanges(): Flow<Unit> =
        callbackFlow {
            val callback =
                object : UserTracker.Callback {
                    override fun onUserChanged(newUser: Int, userContext: Context) {
                        trySend(Unit)
                    }
                }
            userTracker.addCallback(callback) { it.run() }
            awaitClose { userTracker.removeCallback(callback) }
        }
}
