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

package com.android.systemui.qs.notes

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * IchthysOS: what the quick settings notes page needs beyond the notes themselves: whether the
 * device is entered (locked = frosted placeholders), how to unlock and come back to the page, and
 * the p!n hand-off.
 */
@SysUISingleton
class IchthysQsNotesViewModel
@Inject
constructor(
    @Application private val context: Context,
    val repository: IchthysQsNotesRepository,
    deviceEntryInteractor: DeviceEntryInteractor,
    private val activityStarter: Lazy<ActivityStarter>,
    private val shadeController: Lazy<ShadeController>,
    private val userTracker: UserTracker,
    val dialogFactory: SystemUIDialogFactory,
) {
    val notes: StateFlow<List<IchthysNote>>
        get() = repository.notes

    /** False while the lock screen still has to be dismissed: no note text may be rendered. */
    val isDeviceEntered: StateFlow<Boolean> = deviceEntryInteractor.isDeviceEntered

    private val _openNotesWhenShown = MutableStateFlow(false)
    /** Set by [unlockToOpen]: the next time quick settings is idle, land on the notes page. */
    val openNotesWhenShown: StateFlow<Boolean> = _openNotesWhenShown.asStateFlow()

    fun start() = repository.start()

    fun consumeOpenNotes() {
        _openNotesWhenShown.value = false
    }

    /**
     * "Unlock to open": dismisses the keyguard (bouncer if needed) and, once it is gone, expands
     * quick settings again on the notes page. The shade collapses with the keyguard in between;
     * reopening it is the only way back without a second scene-container entry point.
     */
    fun unlockToOpen() {
        activityStarter
            .get()
            .dismissKeyguardThenExecute(
                {
                    _openNotesWhenShown.value = true
                    shadeController.get().animateExpandQs()
                    false /* if the dismiss should be deferred */
                },
                null /* cancelAction */,
                true, /* afterKeyguardGone */
            )
    }

}
