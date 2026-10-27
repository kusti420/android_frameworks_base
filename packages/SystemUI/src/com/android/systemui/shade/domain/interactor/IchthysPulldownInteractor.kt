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

package com.android.systemui.shade.domain.interactor

import android.content.Context
import android.database.ContentObserver
import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import lineageos.providers.LineageSettings

/**
 * IchthysOS: crDroid "Quick pulldown" and "Smart pulldown" for the scene container.
 *
 * The crDroid port only lived in the legacy QuickSettingsControllerImpl, which never runs with
 * scene_container on. The scene path reads these flows instead: [SceneContainerViewModel] picks a
 * swipe source detector that knows the quick pulldown corners, and the Gone scene maps a one-finger
 * status bar pull to Quick Settings.
 */
@SysUISingleton
class IchthysPulldownInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Application scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    systemSettingsRepository: SystemSettingsRepository,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
) {

    /**
     * [LineageSettings.System.STATUS_BAR_QUICK_QS_PULLDOWN]: 0 = off, 1 = right (end) edge,
     * 2 = left (start) edge, 3 = always. Right/left are mirrored under RTL, like the legacy code.
     */
    val quickPulldownMode: StateFlow<Int> =
        lineageSystemIntFlow(LineageSettings.System.STATUS_BAR_QUICK_QS_PULLDOWN, QUICK_PULLDOWN_OFF)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, QUICK_PULLDOWN_OFF)

    /**
     * Whether smart pulldown currently routes a one-finger status bar pull to Quick Settings.
     * `qs_smart_pulldown` (Settings.System), matching the Settings labels: 0 = off, 1 = no
     * dismissible notifications, 2 = no ongoing notifications, 3 = no notifications at all.
     */
    val isSmartPulldownActive: Flow<Boolean> =
        systemSettingsRepository
            .intSetting(KEY_SMART_PULLDOWN, 0)
            .flatMapLatest { mode ->
                when (mode) {
                    1 -> activeNotificationsInteractor.hasClearableNotifications.map { !it }
                    2 -> activeNotificationsInteractor.hasNonClearableNotifications.map { !it }
                    3 -> activeNotificationsInteractor.areAnyNotificationsPresent.map { !it }
                    else -> flowOf(false)
                }
            }
            .distinctUntilChanged()

    private fun lineageSystemIntFlow(name: String, default: Int): Flow<Int> =
        callbackFlow {
                val resolver = context.contentResolver
                fun current(): Int =
                    LineageSettings.System.getIntForUser(
                        resolver,
                        name,
                        default,
                        UserHandle.USER_CURRENT,
                    )
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(current())
                        }
                    }
                resolver.registerContentObserver(
                    LineageSettings.System.getUriFor(name),
                    /* notifyForDescendants = */ false,
                    observer,
                    UserHandle.USER_ALL,
                )
                trySend(current())
                awaitClose { resolver.unregisterContentObserver(observer) }
            }
            .flowOn(bgDispatcher)

    companion object {
        const val QUICK_PULLDOWN_OFF = 0
        const val QUICK_PULLDOWN_END = 1
        const val QUICK_PULLDOWN_START = 2
        const val QUICK_PULLDOWN_ALWAYS = 3

        private const val KEY_SMART_PULLDOWN = "qs_smart_pulldown"
    }
}
