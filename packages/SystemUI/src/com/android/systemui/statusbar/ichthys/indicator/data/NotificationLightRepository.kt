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

package com.android.systemui.statusbar.ichthys.indicator.data

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.CommandQueue
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The notification light while it is on: its colour and its flash rhythm. */
data class NotificationLightModel(val argb: Int, val onMs: Int, val offMs: Int)

/**
 * The notification light as the notification service drives it: the state an LED would have on a
 * phone with one. All the rules are the service's (NotificationAttentionHelper): which
 * notifications may use the light, that it only flashes while the screen is off and no call is
 * going on, and what clears it. Null while the light is off.
 *
 * The service sends the state on every change, so after a restart of this process the light reads
 * as off until the screen next turns off or a notification changes.
 */
@SysUISingleton
class NotificationLightRepository @Inject constructor(commandQueue: CommandQueue) {
    private val _light = MutableStateFlow<NotificationLightModel?>(null)
    val light: StateFlow<NotificationLightModel?> = _light

    init {
        commandQueue.addCallback(
            object : CommandQueue.Callbacks {
                override fun setNotificationLight(on: Boolean, argb: Int, onMs: Int, offMs: Int) {
                    _light.value = if (on) NotificationLightModel(argb, onMs, offMs) else null
                }
            }
        )
    }
}
