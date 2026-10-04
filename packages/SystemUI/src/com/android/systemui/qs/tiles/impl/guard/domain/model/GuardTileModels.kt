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

package com.android.systemui.qs.tiles.impl.guard.domain.model

/**
 * Guard Lockdown tile model.
 *
 * @param isEnabled whether lockdown is on; null while Settings' Guard can't be asked (user still
 *   locked, Settings not answering).
 */
data class GuardLockdownTileModel(val isEnabled: Boolean?)

/**
 * Guard Pause tile model.
 *
 * @param pausedUntilMs wall-clock end of the pause, 0 while Guard enforces, null if the Guard
 *   service can't be reached.
 * @param remainingMs time left of the pause at the moment the model was made (0 when not paused).
 */
data class GuardPauseTileModel(val pausedUntilMs: Long?, val remainingMs: Long) {
    val isReachable: Boolean
        get() = pausedUntilMs != null

    val isPaused: Boolean
        get() = remainingMs > 0
}
